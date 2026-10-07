"""Heptabase journal connector: the one owner of grant state, outbox and delivery."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
import hashlib
import sqlite3
import threading
import time
from urllib.parse import urlsplit

from sam_runtime.core.logging import runtime_logger
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools.definitions import ToolInvocationContext

from .activity import activity_lines
from .client import HeptabaseMcpClient, HttpTransport
from .errors import AuthorizationError, HeptabaseError, NotConnected, ReconnectRequired, ToolFailure
from .format import (Rendered, SessionLine, clean_words, fingerprint, journal_contains, journal_plain_text,
                     match_key, render_note, render_session, scrub_secrets, session_header, unescape_markdown)
from .localtime import (DEFAULT_TIMEZONE, UnknownTimezone, clock_label, iso_utc, journal_date, parse_iso_epoch,
                        resolve_zone, zone_source)
from .oauth import (DEVICE_CALLBACK_PATH, HEPTABASE_CONNECTION_ID, LOOPBACK_REDIRECT_URI, HeptabaseEndpoints,
                    HeptabaseOAuth, HeptabaseTokenStore)
from .outbox import JournalOutboxRepository, JournalWorker, NewEntry, Work, backoff_seconds, next_work
from .settings import CONNECTED, RECONNECT_REQUIRED, JournalSettings, JournalSettingsRepository
from .verbatim import utterance_consumed, verify_words

_LOG = runtime_logger()
_USER_EVENTS = frozenset({
    "conversation.item.input_audio_transcription.completed",
    "conversation.item.input_audio_transcript.completed",
})
_IMAGE_EVENT = "conversation.item.input_image.completed"
_ASSISTANT_EVENTS = frozenset({"response.audio_transcript.done", "response.output_audio_transcript.done"})
MAX_NOTE_CHARS = 4000
READ_LIMIT_BYTES = 4096
_PAUSE_RETENTION_SECONDS = 86400
_PURGE_INTERVAL_SECONDS = 3600
_UNKNOWN_TOOL_FAILURE_LIMIT = 5


@dataclass(frozen=True, slots=True)
class _Utterance:
    text: str
    at: float | None


class HeptabaseJournalService:
    def __init__(
        self,
        *,
        database: RuntimeDatabase,
        credentials: ConnectionCredentialRepository,
        envelopes: ConnectionCredentialEnvelopes,
        sessions: object | None = None,
        connections: object | None = None,
        endpoints: HeptabaseEndpoints = HeptabaseEndpoints(),
        transport: HttpTransport | None = None,
        clock: Callable[[], float] = time.time,
        deliver_timeout: float = 5.0,
        idle_seconds: float = 15.0,
    ) -> None:
        self._clock = clock
        self._sessions = sessions
        self._connections = connections
        self._deliver_timeout = deliver_timeout
        self._transport = transport or HttpTransport()
        self._settings = JournalSettingsRepository(database)
        self._outbox = JournalOutboxRepository(database, clock=clock)
        self._oauth = HeptabaseOAuth(
            store=HeptabaseTokenStore(credentials, envelopes),
            settings=self._settings,
            transport=self._transport,
            endpoints=endpoints,
            clock=clock,
            on_reconnect_required=self._on_reconnect_required,
        )
        self._mcp = HeptabaseMcpClient(self._transport, endpoints.mcp, self._oauth)
        self._worker = JournalWorker(self._step, idle_seconds=idle_seconds)
        self._send_lock = threading.Lock()
        self._solo: set[str] = set()
        self._paused_sessions: dict[str, float] = {}
        self._pause_lock = threading.Lock()
        self._last_purge = 0.0
        self._zone_warning = False

    # ------------------------------------------------------------------ lifecycle

    def start(self) -> None:
        recovered = self._outbox.recover_interrupted()
        if recovered:
            _LOG.info("heptabase.journal.recovered_inflight", extra={"count": recovered})
        self._worker.start()

    def stop(self) -> None:
        self._worker.stop()

    def drain(self) -> int:
        """Deliver everything currently actionable, inline (tests / diagnostics)."""
        return self._worker.drain()

    # ------------------------------------------------------------------ state views

    def connected(self) -> bool:
        """A grant exists (possibly needing reconnect: notes still queue)."""
        try:
            return self._settings.state().has_grant
        except sqlite3.Error:
            return False

    def settings(self) -> JournalSettings:
        return self._settings.settings()

    def save_settings(self, changes: dict[str, object]) -> dict[str, object]:
        self._settings.save_settings(changes)
        return self.management_view()

    def device_status(self) -> dict[str, object]:
        state = self._settings.state()
        counts = self._outbox.counts()
        return {
            "connected": state.has_grant,
            "autoSessions": self._settings.settings().auto_sessions,
            "pending": counts["pending"] + counts["sending"] + counts["uncertain"] + counts["held"],
            "failed": counts["failed"],
            "lastSentAt": state.last_sent_at,
            "needsReconnect": state.state == RECONNECT_REQUIRED,
        }

    def management_view(self) -> dict[str, object]:
        state = self._settings.state()
        settings = self._settings.settings()
        counts = self._outbox.counts()
        waiting = counts["pending"] + counts["sending"] + counts["uncertain"]
        return {
            "connected": state.has_grant,
            "state": state.state,
            "needsReconnect": state.state == RECONNECT_REQUIRED,
            "scope": state.scope,
            "writeVerified": state.write_verified,
            "refreshAvailable": state.refresh_available,
            "accessExpiresAt": iso_utc(state.access_expires_at) if state.access_expires_at else None,
            "connectedAt": state.connected_at,
            "lastSentAt": state.last_sent_at,
            "lastError": state.last_error or self._outbox.last_error(),
            "queue": {
                "pending": counts["pending"] + counts["sending"],
                "uncertain": counts["uncertain"],
                "held": counts["held"],
                "failed": counts["failed"],
                "sent": counts["sent"],
                "paused": state.state != CONNECTED and waiting > 0,
            },
            "settings": settings.view(),
            "timezoneSource": zone_source(settings.timezone),
            "loopbackRedirectUri": LOOPBACK_REDIRECT_URI,
        }

    # ------------------------------------------------------------------ OAuth

    def connect_start(self, redirect: str, forwarded_origin: str | None, *, reregister: bool = False) -> dict[str, object]:
        if redirect == "loopback":
            redirect_uri = LOOPBACK_REDIRECT_URI
        elif redirect == "device":
            origin = _https_origin(forwarded_origin)
            if origin is None:
                raise AuthorizationError("device_redirect_unavailable",
                                         "Open this page from the R1 management address to use the device redirect.")
            redirect_uri = origin + DEVICE_CALLBACK_PATH
        else:
            raise AuthorizationError("invalid_redirect", "redirect must be 'loopback' or 'device'.")
        if reregister:  # the cached DCR client was deleted on Heptabase's side
            self._settings.forget_client(redirect_uri)
        started = self._oauth.start(redirect_uri)
        _LOG.info("heptabase.connect.start", extra={"redirect": redirect})
        return {
            "authSessionId": started.auth_session_id,
            "authorizationUrl": started.authorization_url,
            "redirectUri": started.redirect_uri,
            "expiresAt": iso_utc(started.expires_at),
        }

    def complete_authorization(self, *, state: str, code: str | None, issuer: str | None,
                               error: str | None = None) -> dict[str, object]:
        previous = self._oauth.stored_record()
        record = self._oauth.complete(state=state, code=code, issuer=issuer, error=error)
        self._activate(record, source="authorization")
        if previous and previous.get("refresh_token") and previous.get("refresh_token") != record.get("refresh_token"):
            self._oauth.revoke(previous)  # the R1 holds exactly one grant
        return self.management_view()

    def import_tokens(self, payload: dict[str, object]) -> dict[str, object]:
        client_id = _bounded_str(payload.get("clientId"), 256)
        access_token = _bounded_str(payload.get("accessToken"), 8192)
        refresh_token = payload.get("refreshToken")
        if refresh_token is not None:
            refresh_token = _bounded_str(refresh_token, 4096)
        scope = payload.get("scope")
        scope = _bounded_str(scope, 512) if scope is not None else None
        expires_at = payload.get("expiresAt")
        if not client_id or not access_token or isinstance(expires_at, bool) or not isinstance(expires_at, (int, float)):
            raise AuthorizationError("invalid_import", "clientId, accessToken and expiresAt are required.")
        expires = int(expires_at / 1000) if expires_at > 10_000_000_000 else int(expires_at)
        try:
            names = self._mcp.list_tool_names(access_token=access_token)
        except HeptabaseError as error:
            if error.status == 401:
                raise AuthorizationError("import_rejected", "Heptabase rejected the imported access token.") from None
            names = None
        if names is not None and "append_to_journal" not in names:
            raise AuthorizationError("write_scope_missing", "The imported token cannot write journals.")
        record = self._oauth.import_record(client_id=client_id, access_token=access_token,
                                           refresh_token=refresh_token, expires_at=expires, scope=scope)
        self._settings.set_connected(scope=scope, access_expires_at=expires, refresh_available=bool(refresh_token),
                                     write_verified=names is not None)
        self._register_connection("ready", None)
        self._worker.wake()
        _LOG.info("heptabase.connect.imported", extra={"refresh": bool(record.get("refresh_token"))})
        return self.management_view()

    def disconnect(self) -> dict[str, object]:
        self._oauth.revoke()
        self._oauth.forget()
        self._settings.set_disconnected()
        self._unregister_connection()
        _LOG.info("heptabase.disconnected")
        return self.management_view()

    def retry_failed(self) -> dict[str, object]:
        count = self._outbox.retry_failed()
        self._worker.wake()
        return {**self.management_view(), "retried": count}

    def _activate(self, record: dict[str, object], *, source: str) -> None:
        write_verified = False
        detail = None
        try:
            names = self._mcp.list_tool_names(access_token=str(record["access_token"]))
        except HeptabaseError as error:
            names = None
            detail = f"Connected; journal access check deferred ({error.code})."
        if names is not None:
            if "append_to_journal" not in names:
                self._oauth.revoke(record)
                self._oauth.forget()
                self._settings.set_disconnected()
                raise AuthorizationError(
                    "write_scope_missing",
                    "Heptabase did not grant journal write access. Connect again and allow access to your space.",
                )
            write_verified = True
        self._settings.set_connected(scope=record.get("scope") if isinstance(record.get("scope"), str) else None,
                                     access_expires_at=int(record["expires_at"]),
                                     refresh_available=bool(record.get("refresh_token")),
                                     write_verified=write_verified)
        if not record.get("refresh_token"):
            detail = "Heptabase granted no refresh token; reconnect within 48 hours."
        self._settings.record_error(detail)
        self._register_connection("ready", detail)
        self._worker.wake()
        _LOG.info("heptabase.connect.completed", extra={"source": source, "writeVerified": write_verified,
                                                       "refresh": bool(record.get("refresh_token"))})

    def _on_reconnect_required(self, detail: str) -> None:
        self._register_connection("failed", detail)

    def _ensure_paused(self, error: Exception) -> None:
        """Every refusal of the grant must flip the connection gate, or the worker would retry hot."""
        if self._settings.state().state == CONNECTED:
            self._oauth.mark_reconnect_required(str(error) or "Reconnect Heptabase to keep journaling.")

    def _register_connection(self, health: str, detail: str | None) -> None:
        if self._connections is None:
            return
        try:
            self._connections.save(connection_id=HEPTABASE_CONNECTION_ID, kind="heptabase", label="Heptabase journal",
                                   enabled=True, health_state=health, health_detail=detail,
                                   source_owner="heptabase_journal")
        except sqlite3.Error:
            # connections.kind may not include 'heptabase' yet (owned by another migration).
            _LOG.info("heptabase.connection_row.unavailable")

    def _unregister_connection(self) -> None:
        if self._connections is None:
            return
        try:
            self._connections.remove(HEPTABASE_CONNECTION_ID)
        except sqlite3.Error:
            pass

    # ------------------------------------------------------------------ notes

    def record_note(
        self,
        words: str,
        *,
        at: float | None = None,
        source_ref: str | None = None,
        voice_session_id: str | None = None,
        utterance_id: int | None = None,
        utterance_key: str | None = None,
        wait: bool = True,
    ) -> dict[str, object]:
        """Queue the user's own words as one journal paragraph and try to send now."""
        if not self.connected():
            raise NotConnected()
        text = clean_words(words)
        if not text:
            raise ValueError("Say what to add to the journal.")
        if len(text) > MAX_NOTE_CHARS:
            raise ValueError("That note is too long for one journal entry.")
        settings = self._settings.settings()
        zone = self._zone(settings)
        when = self._clock() if at is None else at
        body = scrub_secrets(text) if settings.redact_secrets else text
        rendered = render_note(clock_label(when, zone), body)
        entry, created = self._outbox.enqueue(NewEntry(
            journal_date=journal_date(when, zone), kind="note", content=rendered.content,
            plain_content=rendered.plain_content, fingerprint=fingerprint(rendered.plain_content), event_at=when,
            source_ref=source_ref, voice_session_id=voice_session_id, utterance_id=utterance_id,
            utterance_key=utterance_key,
        ))
        if created:
            _LOG.info("heptabase.note.queued", extra={"date": entry.journal_date})
        state = self._await_delivery(entry.entry_id) if wait else self._public_state(entry.entry_id)
        return {"recorded": True, "state": state, "date": entry.journal_date, "entryId": entry.entry_id}

    def add_voice_note(self, context: ToolInvocationContext, text: str) -> dict[str, object]:
        """journal_add: record verified user words, or hold until the transcript arrives."""
        if not self.connected():
            raise NotConnected()
        session_id = context.voice_session_id
        notes = self._outbox.session_notes(session_id) if session_id else []
        words, basis = verify_words(text, context.user_utterance)
        if basis == "utterance" and context.user_utterance_id is not None and any(
            note.utterance_id == context.user_utterance_id for note in notes
        ):
            words, basis = None, "unverified"  # that utterance already produced a note: header is stale
        if words is None:
            if not session_id:
                return {"recorded": False, "reason": "not_verified",
                        "message": "I could not match those words to what the user said. Ask them to say it again."}
            zone = self._zone()
            now = self._clock()
            entry, _ = self._outbox.enqueue(NewEntry(
                journal_date=journal_date(now, zone), kind="note", content="", plain_content="", fingerprint="",
                event_at=now, source_ref=_voice_ref(session_id, None, text),
                voice_session_id=session_id, utterance_id=context.user_utterance_id,
                candidate_text=clean_words(text)[:MAX_NOTE_CHARS], state="held",
            ))
            if entry.state != "held":  # same words already recorded in this session
                return {"recorded": True, "state": self._public_state(entry.entry_id), "date": entry.journal_date,
                        "entryId": entry.entry_id}
            _LOG.info("heptabase.note.held")
            return {"recorded": True, "state": "queued", "date": entry.journal_date, "entryId": entry.entry_id,
                    "verification": "pending"}
        utterance_key = match_key(context.user_utterance or "")[:2000] or None
        return self.record_note(
            words,
            source_ref=_voice_ref(session_id, None, words) if session_id else None,
            voice_session_id=session_id,
            utterance_id=context.user_utterance_id,
            utterance_key=utterance_key,
        )

    def pause_session(self, session_id: str | None) -> dict[str, object]:
        if not session_id:
            return {"paused": False, "reason": "voice_session_required"}
        now = self._clock()
        with self._pause_lock:
            self._paused_sessions = {key: at for key, at in self._paused_sessions.items()
                                     if at > now - _PAUSE_RETENTION_SECONDS}
            self._paused_sessions[session_id] = now
        _LOG.info("heptabase.session.paused")
        return {"paused": True, "scope": "session"}

    def session_paused(self, session_id: str) -> bool:
        with self._pause_lock:
            return session_id in self._paused_sessions

    def read_journal(self, day: str | None = None) -> dict[str, object]:
        if not self.connected():
            raise NotConnected()
        zone = self._zone()
        target = _resolve_day(day, self._clock(), zone)
        try:
            raw = self._mcp.read_journal_range(target, target)
        except ReconnectRequired as error:
            self._ensure_paused(error)
            raise
        text = unescape_markdown(journal_plain_text(raw))
        text = "\n".join(line.rstrip() for line in text.splitlines() if line.strip())
        text = scrub_secrets(_strip_tags(text))
        encoded = text.encode("utf-8")
        truncated = len(encoded) > READ_LIMIT_BYTES
        if truncated:
            cut = encoded[:READ_LIMIT_BYTES].decode("utf-8", errors="ignore")
            text = cut[: cut.rfind("\n")] if "\n" in cut else cut
        return {"date": target, "text": text, "truncated": truncated, "empty": not text}

    # ------------------------------------------------------------------ session finalize

    def finalize_session(self, session_id: str, raw_entries: list[object]) -> dict[str, object]:
        """Auto-journal one finished voice session (dedupe by session id).

        Called before memory finalization so a reviewer/provider failure can
        never drop the user's words.
        """
        if not session_id or not self.connected():
            return {"journaled": False, "reason": "not_connected"}
        settings = self._settings.settings()
        zone = self._zone(settings)
        now = self._clock()
        utterances: list[_Utterance] = []
        images: list[float | None] = []
        replies: list[_Utterance] = []
        for item in raw_entries if isinstance(raw_entries, list) else []:
            if not isinstance(item, dict):
                continue
            role = str(item.get("role", "")).strip()
            event_type = str(item.get("eventType", "")).strip()
            text = clean_words(str(item.get("text", "")))
            if not text:
                continue
            at = _entry_epoch(item.get("at"), now)
            if role == "user" and event_type in _USER_EVENTS:
                utterances.append(_Utterance(text, at))
            elif role == "user" and event_type == _IMAGE_EVENT:
                images.append(at)
            elif role == "assistant" and event_type in _ASSISTANT_EVENTS:
                replies.append(_Utterance(text, at))
        consumed = self._resolve_held_notes(session_id, utterances, zone, settings)
        consumed.extend(note.utterance_key for note in self._outbox.session_notes(session_id) if note.utterance_key)
        if self.session_paused(session_id):
            self._worker.wake()
            return {"journaled": False, "reason": "off_the_record"}
        if not settings.auto_sessions:
            self._worker.wake()
            return {"journaled": False, "reason": "auto_sessions_off"}
        if not utterances:
            self._worker.wake()
            return {"journaled": False, "reason": "no_user_speech"}
        lines = self._session_lines(session_id, utterances, images, replies, consumed, settings, zone)
        if not lines:
            self._worker.wake()
            return {"journaled": False, "reason": "nothing_new"}
        timed = [line.sort_at for line in lines if line.sort_at is not None]
        start = min(timed) if timed else now
        end = max(timed) if timed else now
        header = session_header(clock_label(start, zone), clock_label(end, zone))
        blocks = render_session(header, lines)
        created_any = False
        for index, block in enumerate(blocks, start=1):
            ref = session_id if len(blocks) == 1 else f"{session_id}#{index}"
            _, created = self._outbox.enqueue(NewEntry(
                journal_date=journal_date(start, zone), kind="session", content=block.content,
                plain_content=block.plain_content, fingerprint=fingerprint(block.plain_content),
                event_at=start + index * 0.001, source_ref=ref, voice_session_id=session_id,
            ))
            created_any = created_any or created
        self._worker.wake()
        _LOG.info("heptabase.session.queued", extra={"lines": len(lines), "blocks": len(blocks),
                                                     "duplicate": not created_any})
        return {"journaled": True, "date": journal_date(start, zone), "blocks": len(blocks),
                "duplicate": not created_any}

    def _resolve_held_notes(self, session_id: str, utterances: list[_Utterance], zone, settings: JournalSettings) -> list[str]:
        from .verbatim import locate_span, strip_command

        consumed: list[str] = []
        recorded = {match_key(note.plain_content.split(" ", 1)[-1]) for note in self._outbox.session_notes(session_id)}
        for held in self._outbox.held_for_session(session_id):
            match: tuple[_Utterance, str] | None = None
            for utterance in reversed(utterances):
                span = locate_span(held.candidate_text or "", utterance.text)
                if span:
                    words, _ = strip_command(span)
                    if words:
                        match = (utterance, words)
                        break
            if match is None:
                self._outbox.drop(held.entry_id)
                _LOG.info("heptabase.note.unverified_dropped")
                continue
            utterance, words = match
            if match_key(words) in recorded:
                self._outbox.drop(held.entry_id)  # the model asked twice for the same words
                continue
            recorded.add(match_key(words))
            when = utterance.at if utterance.at is not None else (parse_iso_epoch(held.event_at) or self._clock())
            body = scrub_secrets(words) if settings.redact_secrets else words
            rendered = render_note(clock_label(when, zone), body)
            key = match_key(utterance.text)
            self._outbox.resolve_held(held.entry_id, journal_date=journal_date(when, zone), content=rendered.content,
                                      plain_content=rendered.plain_content,
                                      fingerprint=fingerprint(rendered.plain_content), event_at=when,
                                      utterance_key=key)
            consumed.append(key)
        return consumed

    def _session_lines(self, session_id: str, utterances: list[_Utterance], images: list[float | None],
                       replies: list[_Utterance], consumed: list[str], settings: JournalSettings,
                       zone) -> list[SessionLine]:
        redact = settings.redact_secrets
        timed = all(item.at is not None for item in utterances)

        def words(value: str) -> str:
            return scrub_secrets(value) if redact else value

        user_lines = [
            SessionLine(item.at, clock_label(item.at, zone) if timed and item.at is not None else None,
                        "user", words(item.text))
            for item in utterances
            if not any(utterance_consumed(item.text, key) for key in consumed)
        ]
        extra: list[SessionLine] = []
        if settings.include_assistant:
            extra.extend(
                SessionLine(item.at, clock_label(item.at, zone) if timed and item.at is not None else None,
                            "assistant", words(item.text))
                for item in replies
            )
        if settings.include_actions:
            for at in images:
                extra.append(SessionLine(at, clock_label(at, zone) if timed and at is not None else None,
                                         "activity", "Shared a photo"))
            entries = self._session_entries(session_id)
            for line in activity_lines(entries, zone):
                label = clock_label(line.at, zone) if line.at is not None else None
                extra.append(SessionLine(line.at, label, "activity", words(line.text)))
        if not user_lines and not any(line.kind == "activity" for line in extra):
            return []
        if timed:
            ordered = sorted(enumerate([*user_lines, *extra]),
                             key=lambda pair: (pair[1].sort_at if pair[1].sort_at is not None else float("inf"), pair[0]))
            return [line for _, line in ordered]
        # Without per-utterance times (older app builds) keep speech order, then actions.
        return [*user_lines, *[line for line in extra if line.kind != "activity"],
                *sorted((line for line in extra if line.kind == "activity"),
                        key=lambda line: line.sort_at or 0.0)]

    def _session_entries(self, session_id: str) -> list[object]:
        if self._sessions is None:
            return []
        try:
            return list(self._sessions.entries(session_id))
        except Exception:
            _LOG.exception("heptabase.session.evidence_unavailable")
            return []

    # ------------------------------------------------------------------ delivery

    def _await_delivery(self, entry_id: str) -> str:
        if self._settings.state().state != CONNECTED:
            return "queued"

        def settled() -> bool:
            entry = self._outbox.get(entry_id)
            return entry is None or entry.state in ("sent", "failed", "uncertain") or (
                entry.state == "pending" and entry.attempts > 0
            )

        self._worker.wait_until(settled, self._deliver_timeout)
        return self._public_state(entry_id)

    def _public_state(self, entry_id: str) -> str:
        entry = self._outbox.get(entry_id)
        return "sent" if entry is not None and entry.state == "sent" else "queued"

    def _step(self) -> bool:
        with self._send_lock:
            now = self._clock()
            if now - self._last_purge > _PURGE_INTERVAL_SECONDS:
                self._last_purge = now
                self._outbox.purge()
            if self._settings.state().state != CONNECTED:
                return False
            work = next_work(self._outbox.queue(), iso_utc(now), self._solo)
            if work is None:
                return False
            if work.action == "verify":
                self._verify(work)
            else:
                self._send(work)
            return True

    def _send(self, work: Work) -> None:
        entries = work.entries
        ids = [entry.entry_id for entry in entries]
        attempts = max(entry.attempts for entry in entries) + 1
        content = "\n\n".join(entry.body for entry in entries)
        self._outbox.mark_sending(ids)
        now = self._clock()
        try:
            self._mcp.append_to_journal(work.journal_date, content)
        except (ReconnectRequired, NotConnected) as error:
            # Not written. Keep the entries; the connection gate pauses delivery.
            self._outbox.mark_retry(ids, error.code, now)
            self._ensure_paused(error)
            return
        except ToolFailure as failure:
            self._tool_failure(work, failure, attempts, now)
            return
        except HeptabaseError as error:
            if error.sent:
                self._outbox.mark_uncertain(ids, error.code, now + backoff_seconds(attempts))
                _LOG.warning("heptabase.append.uncertain", extra={"code": error.code, "entries": len(ids)})
            elif error.retryable:
                self._outbox.mark_retry(ids, error.code, now + backoff_seconds(attempts))
                _LOG.info("heptabase.append.retry", extra={"code": error.code, "attempts": attempts})
            else:
                self._outbox.mark_failed(ids, error.code)
                self._settings.record_error(f"A journal entry could not be written ({error.code}).")
                _LOG.warning("heptabase.append.failed", extra={"code": error.code})
            return
        self._outbox.mark_sent(ids)
        self._solo.difference_update(ids)
        self._settings.record_sent()
        _LOG.info("heptabase.append.sent", extra={"entries": len(ids), "bytes": len(content.encode("utf-8"))})

    def _tool_failure(self, work: Work, failure: ToolFailure, attempts: int, now: float) -> None:
        entries = work.entries
        ids = [entry.entry_id for entry in entries]
        if failure.reason == "invalidHeptaMarkdown":
            if len(entries) > 1:
                self._solo.update(ids)
                self._outbox.mark_retry(ids, failure.reason, now)
            elif not entries[0].use_plain:
                self._outbox.set_use_plain(entries[0].entry_id)
            else:
                self._outbox.mark_failed(ids, failure.reason)
                self._settings.record_error("Heptabase rejected a journal entry's formatting.")
            _LOG.warning("heptabase.append.invalid_markdown", extra={"entries": len(ids)})
            return
        if failure.reason == "invalidDate" or attempts >= _UNKNOWN_TOOL_FAILURE_LIMIT:
            self._outbox.mark_failed(ids, failure.reason)
            self._settings.record_error(f"Heptabase rejected a journal entry ({failure.reason}).")
            _LOG.warning("heptabase.append.rejected", extra={"reason": failure.reason})
            return
        self._outbox.mark_retry(ids, failure.reason, now + backoff_seconds(attempts))

    def _verify(self, work: Work) -> None:
        ids = [entry.entry_id for entry in work.entries]
        attempts = max(entry.attempts for entry in work.entries)
        now = self._clock()
        try:
            journal = self._mcp.read_journal_range(work.journal_date, work.journal_date)
        except (ReconnectRequired, NotConnected) as error:
            self._outbox.mark_uncertain(ids, error.code, now)
            self._ensure_paused(error)
            return
        except HeptabaseError as error:
            self._outbox.mark_uncertain(ids, f"verify_{error.code}", now + backoff_seconds(max(attempts, 1)))
            return
        found = [entry.entry_id for entry in work.entries if journal_contains(journal, entry.fingerprint)]
        missing = [entry_id for entry_id in ids if entry_id not in found]
        if found:
            self._outbox.mark_sent(found)
            self._settings.record_sent()
        if missing:
            self._outbox.mark_resend(missing)
        _LOG.info("heptabase.append.verified", extra={"found": len(found), "resend": len(missing)})

    def _zone(self, settings: JournalSettings | None = None):
        name = (settings or self._settings.settings()).timezone
        try:
            return resolve_zone(name)
        except UnknownTimezone:
            if not self._zone_warning:
                self._zone_warning = True
                _LOG.warning("heptabase.timezone.unresolved")
            return resolve_zone(DEFAULT_TIMEZONE)


def _voice_ref(session_id: str | None, scope: str | None, text: str) -> str | None:
    """Dedupe key for voice notes: one entry per session and set of words."""
    if not session_id:
        return None
    digest = hashlib.sha256(f"{scope or ''}|{match_key(text)}".encode("utf-8")).hexdigest()[:24]
    return f"voice:{session_id}:{digest}"


def _entry_epoch(value: object, now: float) -> float | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    seconds = float(value) / 1000.0
    if not (now - 7 * 86400 <= seconds <= now + 3600):
        return None
    return seconds


def _resolve_day(day: str | None, now: float, zone) -> str:
    from datetime import date, timedelta

    value = (day or "today").strip().lower()
    today = date.fromisoformat(journal_date(now, zone))
    if value == "today":
        return today.isoformat()
    if value == "yesterday":
        return (today - timedelta(days=1)).isoformat()
    try:
        return date.fromisoformat(value).isoformat()
    except ValueError:
        raise ValueError("date must be today, yesterday, or YYYY-MM-DD.") from None


def _strip_tags(text: str) -> str:
    import re

    return re.sub(r"</?hepta-[a-z-]+[^>]*>", "", text)


def _https_origin(value: str | None) -> str | None:
    if not value:
        return None
    parsed = urlsplit(value.strip())
    if parsed.scheme != "https" or not parsed.hostname or parsed.path not in ("", "/") or parsed.query \
            or parsed.fragment or parsed.username or parsed.password:
        return None
    host = parsed.hostname
    if ":" in host:
        host = f"[{host}]"
    return f"https://{host}{f':{parsed.port}' if parsed.port else ''}"


def _bounded_str(value: object, limit: int) -> str:
    if not isinstance(value, str):
        return ""
    text = value.strip()
    return text if 0 < len(text) <= limit else ""
