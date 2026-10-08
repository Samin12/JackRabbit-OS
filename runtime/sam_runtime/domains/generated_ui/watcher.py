"""Follows generated UIs that are being made on the Mac and announces them to the R1 app.

``ui_generate`` returns at once; this worker polls ``GET /v1/ui/artifacts/<id>`` on the bridge and, when the
artifact is ready (or failed), publishes one announcement through the existing outbox/long-poll:

* ``ui.generated`` ``{artifactId, title, summary, conversationId, voiceSessionId, imageBlobId, width, height,
  mime, imagePath}``: the app fetches ``imagePath`` (device route, proxied JPEG), shows it in the transcript
  and, during a live session, hands it to the voice model as ``[Generated UI] <title>: <summary>``.
* ``ui.failed`` ``{artifactId, conversationId, voiceSessionId, error: {code, message}, message}``.

The watch list survives a runtime restart: it is kept in ``provider_settings`` (no migration).
"""

from __future__ import annotations

from collections import deque
from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime
import json
import sqlite3
import threading
import time

from sam_runtime.core.logging import runtime_logger
from sam_runtime.storage.announcements import AnnouncementRepository
from sam_runtime.storage.database import RuntimeDatabase

from .client import ARTIFACT_ID, GeneratedUiClient, GeneratedUiFailure

PENDING_KEY = "genui.pending"
MAX_PENDING = 24
FIRST_POLL_SECONDS = 3.0
FAST_POLL_SECONDS = 2.0
SLOW_POLL_SECONDS = 5.0
FAST_PHASE_SECONDS = 90.0
GIVE_UP_SECONDS = 20 * 60.0
KIND_READY = "ui.generated"
KIND_FAILED = "ui.failed"
_LOG = runtime_logger()


@dataclass
class Watch:
    artifact_id: str
    conversation_id: str | None
    voice_session_id: str | None
    started_at: float
    next_poll: float

    def record(self) -> dict[str, object]:
        return {"artifactId": self.artifact_id, "conversationId": self.conversation_id,
                "voiceSessionId": self.voice_session_id, "startedAt": self.started_at}


class ArtifactWatcher:
    def __init__(self, client: GeneratedUiClient, announcements: AnnouncementRepository,
                 database: RuntimeDatabase, *, clock: Callable[[], float] = time.time) -> None:
        self._client = client
        self._announcements = announcements
        self._database = database
        self._clock = clock
        self._lock = threading.Lock()
        self._wake = threading.Condition(self._lock)
        self._watches: dict[str, Watch] = {}
        self._published: deque[str] = deque(maxlen=64)
        self._loaded = False
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    # ------------------------------------------------------------------ lifecycle
    def start(self) -> None:
        if self._thread is not None:
            return
        self._load()
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="sam-generated-ui", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        with self._wake:
            self._wake.notify_all()
        if self._thread is not None:
            self._thread.join(timeout=3.0)
            self._thread = None

    # ------------------------------------------------------------------ API
    def track(self, artifact_id: str, *, conversation_id: str | None, voice_session_id: str | None) -> bool:
        """Follow ``artifact_id`` until it is ready or failed. False when it is already followed or announced."""
        if not ARTIFACT_ID.match(artifact_id):
            return False
        self._load()
        now = self._clock()
        with self._wake:
            if artifact_id in self._watches or artifact_id in self._published:
                return False
            if len(self._watches) >= MAX_PENDING:
                oldest = min(self._watches.values(), key=lambda item: item.started_at)
                self._watches.pop(oldest.artifact_id, None)
            self._watches[artifact_id] = Watch(artifact_id, conversation_id, voice_session_id, now,
                                               now + FIRST_POLL_SECONDS)
            self._save_locked()
            self._wake.notify_all()
        return True

    def pending(self) -> list[str]:
        with self._lock:
            return sorted(self._watches)

    def poll_once(self) -> int:
        """Poll every watch that is due; returns how many were announced. Used by the worker and tests."""
        self._load()
        now = self._clock()
        with self._lock:
            due = [watch for watch in self._watches.values() if watch.next_poll <= now]
        announced = 0
        for watch in due:
            if self._stop.is_set():
                break
            if self._poll(watch, now):
                announced += 1
        return announced

    # ------------------------------------------------------------------ work
    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self.poll_once()
            except Exception:  # noqa: BLE001 - keep following the others
                _LOG.exception("generated_ui.poll_failed")
            with self._wake:
                if self._stop.is_set():
                    break
                upcoming = [watch.next_poll for watch in self._watches.values()]
                delay = min(upcoming) - self._clock() if upcoming else 60.0
                self._wake.wait(timeout=max(0.2, min(60.0, delay)))

    def _poll(self, watch: Watch, now: float) -> bool:
        try:
            artifact = self._client.artifact(watch.artifact_id)
        except GeneratedUiFailure as failure:
            if failure.code in ("artifact_not_found", "mac_not_configured"):
                return self._finish(watch, KIND_FAILED, {"error": failure.code, "message": failure.message})
            return self._later(watch, now)
        status = artifact.get("status")
        if status == "ready":
            return self._finish(watch, KIND_READY, artifact)
        if status == "failed":
            return self._finish(watch, KIND_FAILED, {"error": artifact.get("error") or "failed",
                                                     "message": artifact.get("errorMessage")})
        return self._later(watch, now)

    def _later(self, watch: Watch, now: float) -> bool:
        age = now - watch.started_at
        if age > GIVE_UP_SECONDS:
            return self._finish(watch, KIND_FAILED, {"error": "timeout",
                                                     "message": "The Mac did not finish the visual."})
        with self._lock:
            if watch.artifact_id in self._watches:
                watch.next_poll = now + (FAST_POLL_SECONDS if age < FAST_PHASE_SECONDS else SLOW_POLL_SECONDS)
        return False

    def _finish(self, watch: Watch, kind: str, value: dict[str, object]) -> bool:
        with self._lock:
            if self._watches.pop(watch.artifact_id, None) is None:
                return False
            self._published.append(watch.artifact_id)
            self._save_locked()
        base: dict[str, object] = {"artifactId": watch.artifact_id, "conversationId": watch.conversation_id,
                                   "voiceSessionId": watch.voice_session_id}
        if kind == KIND_READY:
            title = " ".join(str(value.get("title") or "Your visual").split())[:80] or "Your visual"
            summary = " ".join(str(value.get("summary") or "").split())[:240]
            payload = {**base, "title": title, "summary": summary, "imageBlobId": value.get("imageBlobId"),
                       "width": value.get("width"), "height": value.get("height"), "mime": "image/jpeg",
                       "imagePath": f"/v1/ui/artifacts/{watch.artifact_id}/image"}
            text = f"“{title}” is ready."
        else:
            title = "Visual not made"
            message = " ".join(str(value.get("message") or "").split())[:200] or "The Mac could not make it."
            # {code, message}: the R1 app (and the desktop) show error.message, or a plain string, to the user.
            payload = {**base, "error": {"code": str(value.get("error") or "failed")[:64], "message": message},
                       "message": message}
            text = "I couldn't make that visual. " + message
        try:
            self._announcements.publish(kind, title, text, payload)
        except sqlite3.Error:
            _LOG.warning("generated_ui.announce_failed", extra={"kind": kind})
            return False
        _LOG.info("generated_ui.announced", extra={"kind": kind, "artifactId": watch.artifact_id})
        return True

    # ------------------------------------------------------------------ persistence
    def _load(self) -> None:
        with self._lock:
            if self._loaded:
                return
            self._loaded = True
        try:
            with self._database.connect() as connection:
                row = connection.execute("SELECT setting_value FROM provider_settings WHERE setting_key = ?",
                                         (PENDING_KEY,)).fetchone()
        except sqlite3.Error:
            return
        try:
            records = json.loads(row[0]) if row and row[0] else []
        except (TypeError, ValueError):
            records = []
        now = self._clock()
        with self._lock:
            for record in records if isinstance(records, list) else []:
                if not isinstance(record, dict) or not ARTIFACT_ID.match(str(record.get("artifactId") or "")):
                    continue
                started = record.get("startedAt") if isinstance(record.get("startedAt"), (int, float)) else now
                self._watches.setdefault(str(record["artifactId"]), Watch(
                    str(record["artifactId"]), _text(record.get("conversationId")),
                    _text(record.get("voiceSessionId")), float(started), now + 1.0))

    def _save_locked(self) -> None:
        value = json.dumps([watch.record() for watch in self._watches.values()], separators=(",", ":"))
        stamp = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        try:
            with self._database.connect() as connection:
                connection.execute(
                    "INSERT INTO provider_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                    "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                    "updated_at = excluded.updated_at",
                    (PENDING_KEY, value, stamp),
                )
                connection.commit()
        except sqlite3.Error:
            _LOG.warning("generated_ui.save_failed")


def _text(value: object) -> str | None:
    return value if isinstance(value, str) and value else None
