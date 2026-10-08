"""Conversation sync: mirror every R1 conversation to the user's Mac, live, without ever
touching voice, tools or finalize.

* Hop 1 (R1 app -> runtime, device-only): ``ingest_events`` / ``ingest_blob`` store what the R1
  sends; ``link_call`` records voiceSessionId -> conversationId from ``/v1/voice/calls``.
* Runtime-made events: ``record_tool`` (``tool.completed`` for every Voice tool call, with the
  ``mac_look`` screenshot as a blob) and ``session_finalized`` (``session.finalized``).
* Hop 2 (runtime -> Mac bridge): one sender thread posts batches to ``/v1/sync/events`` and
  images to ``/v1/sync/blobs/<sha256>`` with backoff 2/5/15/60/300 s; the bridge ignores ids it
  already has, so an uncertain send is simply repeated.

Gate: the Mac bridge is configured (the journal's ``BridgeStore``) and the setting
``conversation_sync.enabled`` is on (default). Everything on the request path is a local SQLite
write at most; failures are logged as codes only, never content.
"""

from __future__ import annotations

import base64
import binascii
from collections import OrderedDict
from collections.abc import Callable
import threading
import time
from uuid import uuid4

from sam_runtime.core.logging import runtime_logger
from sam_runtime.domains.heptabase_journal.bridge import BridgeStore
from sam_runtime.domains.heptabase_journal.client import HttpTransport
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools.definitions import ToolInvocationContext, ToolInvocationResult

from .client import ConversationSyncClient, SyncFailure
from .events import (IMAGE_MIMES, MAX_EVENTS_PER_BATCH, Rejected, blob_id_for, device_event, finalized_event,
                     image_event, tool_event, valid_blob_id, valid_conversation_id, valid_session_id,
                     valid_tool_call_id)
from .outbox import ConversationSyncRepository
from .settings import SyncSettings, SyncSettingsStore, iso_utc as _iso
from .worker import BACKOFF_SECONDS, ConversationSyncWorker, backoff_seconds

MAX_BLOB_BYTES = 400 * 1024
MAX_SEND_EVENTS = 100
MAX_SEND_BYTES = 192 * 1024
BLOB_GRACE_SECONDS = 20.0
WAITING_RECHECK_SECONDS = 2.0
MAINTENANCE_SECONDS = 600.0
EXPEDITE_MIN_SECONDS = 5.0
MAX_SOLO = 1000
_SESSION_CACHE = 256
_LOG = runtime_logger()


class ConversationSyncService:
    def __init__(self, database: RuntimeDatabase, bridge_store: BridgeStore, *,
                 transport: HttpTransport | None = None, clock: Callable[[], float] = time.time,
                 idle_seconds: float = 30.0) -> None:
        self._settings = SyncSettingsStore(database, clock=clock)
        self._repo = ConversationSyncRepository(database, clock=clock)
        self._client = ConversationSyncClient(bridge_store, transport)
        self._clock = clock
        self._lock = threading.Lock()
        self._sessions: OrderedDict[str, str] = OrderedDict()
        self._blocked_until = 0.0
        self._failures = 0
        self._failure_code: str | None = None
        self._failed_config: tuple[str, int] | None = None
        self._last_expedite = 0.0
        self._solo: set[str] = set()  # events to send alone (a batch holding them was refused)
        self._last_maintenance = 0.0
        self._since_cap_check = 0
        self.worker = ConversationSyncWorker(self._step, self._next_due, idle_seconds=idle_seconds, clock=clock)
        listen = getattr(bridge_store, "add_listener", None)
        if callable(listen):
            listen(self.expedite)  # the Mac answered (journal, Mac control) or the bridge was (re)configured

    @property
    def repository(self) -> ConversationSyncRepository:
        return self._repo

    # ------------------------------------------------------------------ lifecycle
    def start(self) -> None:
        recovered = self._repo.recover_interrupted()
        if recovered:
            _LOG.info("conversation_sync.recovered", extra={"count": recovered})
        self.worker.start()

    def stop(self) -> None:
        self.worker.stop()

    # ------------------------------------------------------------------ settings and status
    def settings(self) -> SyncSettings:
        return self._settings.settings()

    def save_settings(self, changes: dict[str, object]) -> dict[str, object]:
        self._settings.save(changes)
        self.worker.wake()
        return self.management_view()

    def configured(self) -> bool:
        return self._client.configured()

    def active(self) -> bool:
        return self.settings().enabled and self._client.configured()

    def status_view(self) -> dict[str, object]:
        status = self._settings.status()
        with self._lock:
            blocked = self._blocked_until if self._blocked_until > self._clock() else None
        return {
            "enabled": self.settings().enabled,
            "bridgeConfigured": self._client.configured(),
            **self._repo.counts(),
            "lastOkAt": status.get("lastOkAt"),
            "lastError": status.get("lastError"),
            "retryAt": _iso(blocked) if blocked else None,
        }

    def management_view(self) -> dict[str, object]:
        return {**self.status_view(), "settings": self.settings().view()}

    # ------------------------------------------------------------------ hop 1 (R1 app -> runtime)
    def ingest_events(self, body: dict[str, object]) -> dict[str, object]:
        """Store a batch from the R1. Raises ValueError for a malformed batch; single bad events
        are counted as ``rejected`` and the rest is kept."""
        conversation_id = valid_conversation_id(body.get("conversationId"))
        if conversation_id is None:
            raise ValueError("conversationId is invalid.")
        raw_session = body.get("sessionId")
        session_id = None
        if raw_session not in (None, ""):
            session_id = valid_session_id(raw_session)
            if session_id is None:
                raise ValueError("sessionId is invalid.")
        events = body.get("events")
        if not isinstance(events, list) or len(events) > MAX_EVENTS_PER_BATCH:
            raise ValueError(f"events must be a list of at most {MAX_EVENTS_PER_BATCH} items.")
        if session_id is not None:
            self._link(session_id, conversation_id, replace=False)
        if not self.active():
            return {"accepted": 0, "duplicates": 0, "rejected": 0, "skipped": len(events), "syncing": False}
        settings = self.settings()
        now_ms = int(self._clock() * 1000)
        prepared = []
        rejected = skipped = 0
        for raw in events:
            try:
                event = device_event(raw, conversation_id=conversation_id, session_id=session_id, now_ms=now_ms,
                                     scrub=settings.redact_secrets)
            except Rejected:
                rejected += 1
                continue
            if (not settings.include_assistant and event.kind.startswith("message.assistant")) or \
                    (not settings.include_images and event.kind == "image"):
                skipped += 1
                continue
            prepared.append(event)
        accepted, duplicates = self._repo.enqueue(prepared) if prepared else (0, 0)
        if accepted:
            self._after_enqueue(accepted)
        return {"accepted": accepted, "duplicates": duplicates, "rejected": rejected, "skipped": skipped,
                "syncing": True}

    def ingest_blob(self, data: bytes, mime: str, conversation_id: object = None) -> dict[str, object]:
        kind = (mime or "").split(";", 1)[0].strip().lower()
        if kind not in IMAGE_MIMES:
            raise ValueError("Content-Type must be image/jpeg, image/png, image/webp or image/gif.")
        if not data or len(data) > MAX_BLOB_BYTES:
            raise ValueError(f"The image must be 1 to {MAX_BLOB_BYTES} bytes.")
        conversation = valid_conversation_id(conversation_id) if conversation_id not in (None, "") else None
        if conversation_id not in (None, "") and conversation is None:
            raise ValueError("X-SAM-Conversation is invalid.")
        blob_id = blob_id_for(data)
        stored = False
        if self.active() and self.settings().include_images:
            self._repo.put_blob(blob_id, kind, data, conversation)
            stored = True
            self.worker.wake()
        return {"blobId": blob_id, "bytes": len(data), "mime": kind, "stored": stored}

    def link_call(self, conversation_id: object, voice_session_id: object) -> None:
        """``/v1/voice/calls`` hook (authoritative link). Never raises."""
        conversation = valid_conversation_id(conversation_id)
        session = valid_session_id(voice_session_id)
        if conversation is None or session is None:
            return
        try:
            self._link(session, conversation, replace=True)
        except Exception:
            _LOG.exception("conversation_sync.link_failed")

    def conversation_for(self, voice_session_id: str) -> str:
        """The R1's conversation for a voice session, or ``s_<voiceSessionId>`` for a session
        the R1 never linked (an older R1 app): it then shows on the Mac as its own conversation."""
        with self._lock:
            cached = self._sessions.get(voice_session_id)
            if cached is not None:
                self._sessions.move_to_end(voice_session_id)
                return cached
        stored = self._repo.conversation_for(voice_session_id)
        if stored is None:
            return f"s_{voice_session_id}"[:64]
        self._remember(voice_session_id, stored)
        return stored

    def screenshot_conversation(self, voice_session_id: str | None) -> str | None:
        """The conversation the Mac bridge should file a ``mac_look`` screenshot under (it then
        records the ``image`` event itself), or None when images are not synced."""
        if not voice_session_id or valid_session_id(voice_session_id) is None:
            return None
        try:
            if not self.active() or not self.settings().include_images:
                return None
            return self.conversation_for(voice_session_id)
        except Exception:
            return None

    # ------------------------------------------------------------------ runtime-made events
    def record_tool(self, context: ToolInvocationContext, name: str, arguments: dict[str, object],
                    result: ToolInvocationResult) -> None:
        """Tool observer: one local insert, never the network."""
        session = valid_session_id(context.voice_session_id)
        if session is None or not self.active():
            return
        settings = self.settings()
        conversation = self.conversation_for(session)
        call_id = valid_tool_call_id(context.tool_call_id) or uuid4().hex[:20]
        at = int(self._clock() * 1000)
        blob_id, local, image = self._tool_image(result, conversation) if settings.include_images else (None, False, None)
        events = []
        if settings.include_tools:
            events.append(tool_event(conversation_id=conversation, session_id=session, tool_call_id=call_id, name=name,
                                     arguments=arguments, text=result.text, is_error=result.is_error,
                                     utterance_id=context.user_utterance_id, at=at, scrub=settings.redact_secrets,
                                     blob_id=blob_id, local_blob=local))
        if blob_id is not None and image is not None and not image.get("recorded"):
            events.append(image_event(
                event_id=f"rt:{session}:{call_id}:image", conversation_id=conversation, session_id=session,
                blob_id=blob_id, mime=str(image.get("mime")), size=image.get("bytes"), width=image.get("width"),
                height=image.get("height"), source="mac_screenshot" if name.startswith("mac_") else "tool", at=at,
                local_blob=local, tool_call_id=call_id,
            ))
        if events:
            accepted, _ = self._repo.enqueue(events)
            if accepted:
                self._after_enqueue(accepted)

    def _tool_image(self, result: ToolInvocationResult, conversation: str):
        structured = result.structured_content if isinstance(result.structured_content, dict) else {}
        image = structured.get("image")
        if not isinstance(image, dict):
            return None, False, None
        mime = str(image.get("mime") or "").lower()
        info = {"mime": mime, "width": structured.get("width"), "height": structured.get("height"),
                "recorded": bool(structured.get("imageEventId"))}
        remote = valid_blob_id(structured.get("blobId"))
        if remote is not None:  # the bridge already keeps this screenshot in its sync store
            return remote, False, info
        raw = image.get("base64")
        if mime not in IMAGE_MIMES or not isinstance(raw, str) or len(raw) > MAX_BLOB_BYTES * 4 // 3 + 8:
            return None, False, None
        try:
            data = base64.b64decode(raw, validate=True)
        except (binascii.Error, ValueError):
            return None, False, None
        if not data or len(data) > MAX_BLOB_BYTES:
            return None, False, None
        blob_id = blob_id_for(data)
        self._repo.put_blob(blob_id, mime, data, conversation)
        info["bytes"] = len(data)
        return blob_id, True, info

    def session_finalized(self, session_id: str, entries: object, finalized: object | None) -> None:
        """Finalize hook (after memory review, success or not). Never raises."""
        try:
            session = valid_session_id(session_id)
            if session is None or not self.active():
                return
            settings = self.settings()
            event = finalized_event(
                conversation_id=self.conversation_for(session), session_id=session,
                entries=entries if isinstance(entries, list) else [],
                summary=getattr(finalized, "summary", None), memory_count=getattr(finalized, "memory_count", None),
                reviewed=finalized is not None, at=int(self._clock() * 1000), scrub=settings.redact_secrets,
                include_assistant=settings.include_assistant,
            )
            accepted, _ = self._repo.enqueue([event])
            if accepted:
                self._after_enqueue(accepted)
        except Exception:
            _LOG.exception("conversation_sync.finalized_hook_failed")

    # ------------------------------------------------------------------ hop 2 (runtime -> Mac)
    def expedite(self) -> None:
        """The Mac answered (or the bridge was reconfigured): stop waiting out a backoff.

        A bridge without sync routes (``bridge_outdated``) is retried early only once it is
        reconfigured, and answers from the Mac end a backoff at most every few seconds."""
        now = self._clock()
        config = self._client.config_stamp()
        with self._lock:
            waiting = self._failures > 0 or self._blocked_until > now
            if waiting:
                changed = config != self._failed_config
                if not changed and (self._failure_code == "bridge_outdated"
                                    or now - self._last_expedite < EXPEDITE_MIN_SECONDS):
                    return
                self._last_expedite = now
                self._failures = 0
                self._blocked_until = 0.0
        if waiting:
            try:
                self._repo.expedite()
            except Exception:
                _LOG.exception("conversation_sync.expedite_failed")
        self.worker.wake()

    def _step(self) -> bool:
        now = self._clock()
        if now - self._last_maintenance >= MAINTENANCE_SECONDS:
            self._last_maintenance = now
            self._repo.purge()
            self._repo.enforce_event_cap()
            self._repo.enforce_blob_cap()
        with self._lock:
            if now < self._blocked_until:
                return False
        if not self.active():
            return False
        blob = self._repo.next_blob(now)
        if blob is not None:
            self._repo.mark_blob_sending(blob.blob_id)
            try:
                self._client.put_blob(blob.blob_id, blob.mime, blob.data, conversation_id=blob.conversation_id)
            except SyncFailure as failure:
                if failure.bridge_wide:
                    self._repo.mark_blob_retry(blob.blob_id, failure.code, self._bridge_failed(failure))
                    return False
                self._repo.mark_blob_failed(blob.blob_id, failure.code)
                _LOG.warning("conversation_sync.blob_rejected", extra={"code": failure.code, "status": failure.status})
                return True
            self._repo.mark_blob_sent(blob.blob_id)
            self._bridge_ok()
            return True
        batch = self._repo.next_events(now, limit=MAX_SEND_EVENTS, max_bytes=MAX_SEND_BYTES,
                                       blob_grace_seconds=BLOB_GRACE_SECONDS)
        if not batch:
            return False
        with self._lock:
            solo = set(self._solo)
        if batch[0].event_id in solo:
            batch = batch[:1]
        else:
            batch = next((batch[:index] for index, item in enumerate(batch) if item.event_id in solo), batch)
        ids = [item.event_id for item in batch]
        self._repo.mark_events_sending(ids)
        try:
            self._client.post_events([item.payload_json for item in batch])
        except SyncFailure as failure:
            if failure.bridge_wide:
                self._repo.mark_events_retry(ids, failure.code, self._bridge_failed(failure))
                return False
            if len(batch) > 1:  # find the one the Mac refuses: resend each of these alone
                with self._lock:
                    if len(self._solo) + len(ids) > MAX_SOLO:
                        self._solo.clear()
                    self._solo.update(ids)
                self._repo.mark_events_retry(ids, failure.code, 0.0)
                return True
            with self._lock:
                self._solo.discard(ids[0])
            self._repo.mark_events_failed(ids, failure.code)
            _LOG.warning("conversation_sync.event_rejected", extra={"code": failure.code, "status": failure.status,
                                                                    "kind": batch[0].kind})
            return True
        self._repo.mark_events_sent(ids)
        with self._lock:
            self._solo.difference_update(ids)
        self._bridge_ok()
        return True

    def _next_due(self) -> float | None:
        now = self._clock()
        with self._lock:
            blocked = self._blocked_until
        if not self.active():
            return None
        due = self._repo.earliest_due()
        if due is None:
            return None
        if blocked > now:
            return max(blocked, due)
        # Due but not sendable (waiting for its image): look again shortly, do not spin.
        return due if due > now else now + WAITING_RECHECK_SECONDS

    def _bridge_failed(self, failure: SyncFailure) -> float:
        now = self._clock()
        config = self._client.config_stamp()
        with self._lock:
            self._failures += 1
            failures = self._failures
            self._failure_code = failure.code
            self._failed_config = config
            self._blocked_until = now + backoff_seconds(failures)
            retry_at = self._blocked_until
        if failures == 1 or failures == len(BACKOFF_SECONDS):
            _LOG.warning("conversation_sync.bridge_failed", extra={"code": failure.code, "status": failure.status,
                                                                   "failures": failures})
        self._settings.record_status(lastError=failure.code, lastErrorAt=_iso(now))
        return retry_at

    def _bridge_ok(self) -> None:
        with self._lock:
            recovered = self._failures > 0
            self._failures = 0
            self._failure_code = None
            self._blocked_until = 0.0
        if recovered:
            _LOG.info("conversation_sync.bridge_recovered")
        self._settings.record_status(lastError=None, lastOkAt=_iso(self._clock()))

    # ------------------------------------------------------------------ plumbing
    def _link(self, session_id: str, conversation_id: str, *, replace: bool) -> None:
        if not replace:
            with self._lock:
                if session_id in self._sessions:
                    return  # already linked (every R1 batch repeats it): no write
        self._repo.link_session(session_id, conversation_id, replace=replace)
        stored = conversation_id if replace else self._repo.conversation_for(session_id)
        if stored is not None:
            self._remember(session_id, stored)  # an earlier authoritative link wins

    def _remember(self, session_id: str, conversation_id: str) -> None:
        with self._lock:
            self._sessions[session_id] = conversation_id
            self._sessions.move_to_end(session_id)
            while len(self._sessions) > _SESSION_CACHE:
                self._sessions.popitem(last=False)

    def _after_enqueue(self, accepted: int) -> None:
        self._since_cap_check += accepted
        if self._since_cap_check >= 500:
            self._since_cap_check = 0
            try:
                self._repo.enforce_event_cap()
            except Exception:
                _LOG.exception("conversation_sync.cap_failed")
        self.worker.wake()


class ConversationSyncObserver:
    """``ToolCatalog`` invocation observer: enqueue ``tool.completed`` (and the screenshot)."""

    def __init__(self, service: ConversationSyncService) -> None:
        self._service = service

    def __call__(self, context: ToolInvocationContext, name: str, arguments: dict[str, object],
                 result: ToolInvocationResult) -> None:
        try:
            self._service.record_tool(context, name, arguments, result)
        except Exception:
            _LOG.exception("conversation_sync.observer_failed")
