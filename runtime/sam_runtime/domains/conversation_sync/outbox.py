"""Durable conversation-sync outbox (SQLite, migration v045).

* Events: ``INSERT OR IGNORE`` by event id (a resend from the R1 is a duplicate, not a copy).
  Assistant drafts (``message.assistant.delta``) are coalesced per message: only the newest
  pending draft is kept, and the final message removes the pending drafts.
* Blobs: images by content hash. A blob is sent before any event that references it; once
  the Mac has it, its bytes are dropped here (the row stays for dedupe until the purge).
* Sessions: the voice session -> conversation map from ``/v1/voice/calls``.
* Caps: at most ``MAX_PENDING_EVENTS`` unsent events (drafts are dropped first, then the
  oldest) and ``MAX_PENDING_BLOB_BYTES`` of unsent image bytes (oldest dropped first). Sent
  and failed rows are purged after ``RETENTION_SECONDS``.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass
import sqlite3
import time

from sam_runtime.core.logging import runtime_logger
from sam_runtime.storage.database import RuntimeDatabase

from .events import DELTA, DRAFT_ENDS, OutgoingEvent

MAX_PENDING_EVENTS = 20_000
MAX_PENDING_BLOB_BYTES = 50 * 1024 * 1024
RETENTION_SECONDS = 7 * 86400
STALE_PENDING_SECONDS = 30 * 86400
SESSION_RETENTION_SECONDS = 90 * 86400
_LOG = runtime_logger()


@dataclass(frozen=True, slots=True)
class QueuedEvent:
    event_id: str
    kind: str
    payload_json: str
    blob_id: str | None
    attempts: int
    size: int


@dataclass(frozen=True, slots=True)
class QueuedBlob:
    blob_id: str
    mime: str
    data: bytes
    attempts: int
    conversation_id: str | None = None


class ConversationSyncRepository:
    def __init__(self, database: RuntimeDatabase, *, clock: Callable[[], float] = time.time) -> None:
        self._database = database
        self._clock = clock

    # ------------------------------------------------------------------ sessions
    def link_session(self, voice_session_id: str, conversation_id: str, *, replace: bool) -> None:
        verb = "INSERT OR REPLACE" if replace else "INSERT OR IGNORE"
        with self._database.connect() as connection:
            connection.execute(
                f"{verb} INTO conversation_sync_sessions(voice_session_id, conversation_id, created_at) "
                "VALUES (?, ?, ?)",
                (voice_session_id, conversation_id, self._clock()),
            )
            connection.commit()

    def conversation_for(self, voice_session_id: str) -> str | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT conversation_id FROM conversation_sync_sessions WHERE voice_session_id = ?",
                (voice_session_id,),
            ).fetchone()
        return str(row[0]) if row is not None else None

    # ------------------------------------------------------------------ events
    def enqueue(self, events: Sequence[OutgoingEvent]) -> tuple[int, int]:
        """Store events in one transaction. Returns ``(accepted, duplicates)``; a draft that a
        newer draft or the final message already supersedes counts as a duplicate."""
        accepted = duplicates = 0
        now = self._clock()
        with self._database.connect() as connection:
            for event in events:
                if event.coalesce_key is not None and not self._coalesce(connection, event):
                    duplicates += 1
                    continue
                cursor = connection.execute(
                    "INSERT OR IGNORE INTO conversation_sync_outbox(event_id, conversation_id, voice_session_id, seq, "
                    "kind, payload_json, blob_id, coalesce_key, state, attempts, event_at, next_attempt_at, "
                    "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', 0, ?, 0, ?)",
                    (event.event_id, event.conversation_id, event.session_id, event.seq, event.kind,
                     event.payload_json(), event.blob_id, event.coalesce_key, int(event.event_at), now),
                )
                if cursor.rowcount == 1:
                    accepted += 1
                else:
                    duplicates += 1
            connection.commit()
        return accepted, duplicates

    @staticmethod
    def _coalesce(connection: sqlite3.Connection, event: OutgoingEvent) -> bool:
        """Drop superseded drafts. Returns False when ``event`` itself is superseded."""
        rows = connection.execute(
            "SELECT event_id, kind, seq, event_at, state FROM conversation_sync_outbox WHERE coalesce_key = ?",
            (event.coalesce_key,),
        ).fetchall()
        if any(str(row["event_id"]) == event.event_id for row in rows):
            return True  # an exact resend: the INSERT OR IGNORE counts it as a duplicate
        if event.kind == DELTA:
            for row in rows:
                if str(row["kind"]) in DRAFT_ENDS:
                    return False
                if str(row["kind"]) == DELTA and _newer(row, event):
                    return False
        if event.kind == DELTA or event.kind in DRAFT_ENDS:
            connection.execute(
                "DELETE FROM conversation_sync_outbox WHERE coalesce_key = ? AND kind = ? AND state = 'pending'",
                (event.coalesce_key, DELTA),
            )
        return True

    def next_events(self, now: float, *, limit: int, max_bytes: int, blob_grace_seconds: float) -> list[QueuedEvent]:
        """Oldest due events (by event time). An event that references a blob this runtime is
        still sending waits for it; one that references a blob it has not received yet waits
        ``blob_grace_seconds`` (the R1 uploads the image just before the event)."""
        with self._database.connect() as connection:
            rows = connection.execute(
                "SELECT o.event_id, o.kind, o.payload_json, o.blob_id, o.attempts FROM conversation_sync_outbox o "
                "LEFT JOIN conversation_sync_blobs b ON b.blob_id = o.blob_id "
                "WHERE o.state = 'pending' AND o.next_attempt_at <= ? AND (o.blob_id IS NULL "
                "  OR b.state IN ('sent', 'failed') OR (b.blob_id IS NULL AND o.created_at <= ?)) "
                "ORDER BY o.event_at, o.created_at, o.rowid LIMIT ?",
                (now, now - blob_grace_seconds, max(1, limit)),
            ).fetchall()
        batch: list[QueuedEvent] = []
        size = 0
        for row in rows:
            payload = str(row["payload_json"])
            cost = len(payload.encode("utf-8")) + 1
            if batch and size + cost > max_bytes:
                break
            batch.append(QueuedEvent(str(row["event_id"]), str(row["kind"]), payload, row["blob_id"],
                                     int(row["attempts"]), cost))
            size += cost
        return batch

    def earliest_due(self) -> float | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT MIN(next_attempt_at) FROM ("
                " SELECT next_attempt_at FROM conversation_sync_outbox WHERE state = 'pending'"
                " UNION ALL SELECT next_attempt_at FROM conversation_sync_blobs WHERE state = 'pending')"
            ).fetchone()
        return float(row[0]) if row is not None and row[0] is not None else None

    def mark_events_sending(self, event_ids: Iterable[str]) -> None:
        self._update_events(event_ids, "state = 'sending', attempts = attempts + 1")

    def mark_events_sent(self, event_ids: Iterable[str]) -> None:
        self._update_events(event_ids, "state = 'sent', sent_at = ?, last_error = NULL", (self._clock(),))

    def mark_events_retry(self, event_ids: Iterable[str], error: str, retry_at: float) -> None:
        self._update_events(event_ids, "state = 'pending', last_error = ?, next_attempt_at = ?", (error[:120], retry_at))

    def mark_events_failed(self, event_ids: Iterable[str], error: str) -> None:
        self._update_events(event_ids, "state = 'failed', last_error = ?", (error[:120],))

    def _update_events(self, event_ids: Iterable[str], assignments: str, params: tuple[object, ...] = ()) -> None:
        ids = tuple(event_ids)
        for start in range(0, len(ids), 400):
            chunk = ids[start:start + 400]
            marks = ",".join("?" for _ in chunk)
            with self._database.connect() as connection:
                connection.execute(
                    f"UPDATE conversation_sync_outbox SET {assignments} WHERE event_id IN ({marks})", (*params, *chunk),
                )
                connection.commit()

    # ------------------------------------------------------------------ blobs
    def put_blob(self, blob_id: str, mime: str, data: bytes, conversation_id: str | None) -> bool:
        """Store one image. Returns True when it was (re)queued (an already-sent blob is not resent)."""
        with self._database.connect() as connection:
            # A blob dropped earlier (cap, refusal) is taken again; a sent or queued one is not.
            cursor = connection.execute(
                "INSERT INTO conversation_sync_blobs(blob_id, conversation_id, mime, size, data, state, "
                "attempts, next_attempt_at, created_at) VALUES (?, ?, ?, ?, ?, 'pending', 0, 0, ?) "
                "ON CONFLICT(blob_id) DO UPDATE SET mime = excluded.mime, size = excluded.size, data = excluded.data, "
                "state = 'pending', attempts = 0, next_attempt_at = 0, last_error = NULL, "
                "created_at = excluded.created_at WHERE conversation_sync_blobs.state = 'failed'",
                (blob_id, conversation_id, mime, len(data), sqlite3.Binary(data), self._clock()),
            )
            connection.commit()
            created = cursor.rowcount == 1
        if created:
            self.enforce_blob_cap()
        return created

    def has_blob(self, blob_id: str) -> bool:
        with self._database.connect() as connection:
            row = connection.execute("SELECT 1 FROM conversation_sync_blobs WHERE blob_id = ?", (blob_id,)).fetchone()
        return row is not None

    def next_blob(self, now: float) -> QueuedBlob | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT blob_id, mime, data, attempts, conversation_id FROM conversation_sync_blobs "
                "WHERE state = 'pending' AND next_attempt_at <= ? ORDER BY created_at, rowid LIMIT 1",
                (now,),
            ).fetchone()
        if row is None:
            return None
        return QueuedBlob(str(row["blob_id"]), str(row["mime"]), bytes(row["data"]), int(row["attempts"]),
                          row["conversation_id"])

    def mark_blob_sending(self, blob_id: str) -> None:
        self._update_blob(blob_id, "state = 'sending', attempts = attempts + 1")

    def mark_blob_sent(self, blob_id: str) -> None:
        # The Mac has the bytes now: keep only the row (dedupe of a later re-upload).
        self._update_blob(blob_id, "state = 'sent', sent_at = ?, data = x'', last_error = NULL", (self._clock(),))

    def mark_blob_retry(self, blob_id: str, error: str, retry_at: float) -> None:
        self._update_blob(blob_id, "state = 'pending', last_error = ?, next_attempt_at = ?", (error[:120], retry_at))

    def mark_blob_failed(self, blob_id: str, error: str) -> None:
        self._update_blob(blob_id, "state = 'failed', data = x'', last_error = ?", (error[:120],))

    def _update_blob(self, blob_id: str, assignments: str, params: tuple[object, ...] = ()) -> None:
        with self._database.connect() as connection:
            connection.execute(f"UPDATE conversation_sync_blobs SET {assignments} WHERE blob_id = ?", (*params, blob_id))
            connection.commit()

    # ------------------------------------------------------------------ maintenance
    def recover_interrupted(self) -> int:
        """A restart mid-send leaves 'sending' rows. Resending is safe (the Mac dedupes)."""
        with self._database.connect() as connection:
            events = connection.execute(
                "UPDATE conversation_sync_outbox SET state = 'pending', next_attempt_at = 0 WHERE state = 'sending'"
            ).rowcount
            blobs = connection.execute(
                "UPDATE conversation_sync_blobs SET state = 'pending', next_attempt_at = 0 WHERE state = 'sending'"
            ).rowcount
            connection.commit()
        return events + blobs

    def expedite(self) -> int:
        """Retry everything that is waiting out a backoff now (the Mac answered again)."""
        now = self._clock()
        with self._database.connect() as connection:
            changed = connection.execute(
                "UPDATE conversation_sync_outbox SET next_attempt_at = 0 WHERE state = 'pending' AND next_attempt_at > ?",
                (now,),
            ).rowcount
            changed += connection.execute(
                "UPDATE conversation_sync_blobs SET next_attempt_at = 0 WHERE state = 'pending' AND next_attempt_at > ?",
                (now,),
            ).rowcount
            connection.commit()
        return changed

    def enforce_event_cap(self, limit: int = MAX_PENDING_EVENTS) -> int:
        """Keep at most ``limit`` unsent events: drop the oldest drafts first, then the oldest events."""
        dropped = 0
        with self._database.connect() as connection:
            pending = int(connection.execute(
                "SELECT COUNT(*) FROM conversation_sync_outbox WHERE state = 'pending'").fetchone()[0])
            excess = pending - limit
            if excess > 0:
                dropped += connection.execute(
                    "DELETE FROM conversation_sync_outbox WHERE rowid IN (SELECT rowid FROM conversation_sync_outbox "
                    "WHERE state = 'pending' AND kind = ? ORDER BY event_at, rowid LIMIT ?)", (DELTA, excess),
                ).rowcount
                excess -= dropped
            if excess > 0:
                dropped += connection.execute(
                    "DELETE FROM conversation_sync_outbox WHERE rowid IN (SELECT rowid FROM conversation_sync_outbox "
                    "WHERE state = 'pending' ORDER BY event_at, rowid LIMIT ?)", (excess,),
                ).rowcount
            connection.commit()
        if dropped:
            _LOG.warning("conversation_sync.events_dropped", extra={"count": dropped})
        return dropped

    def enforce_blob_cap(self, limit: int = MAX_PENDING_BLOB_BYTES) -> int:
        dropped = 0
        with self._database.connect() as connection:
            total = int(connection.execute(
                "SELECT COALESCE(SUM(size), 0) FROM conversation_sync_blobs WHERE state IN ('pending', 'sending')"
            ).fetchone()[0])
            if total > limit:
                rows = connection.execute(
                    "SELECT blob_id, size FROM conversation_sync_blobs WHERE state = 'pending' ORDER BY created_at, rowid"
                ).fetchall()
                for row in rows:
                    if total <= limit:
                        break
                    connection.execute(
                        "UPDATE conversation_sync_blobs SET state = 'failed', data = x'', last_error = 'dropped_cap' "
                        "WHERE blob_id = ?", (row["blob_id"],),
                    )
                    total -= int(row["size"])
                    dropped += 1
            connection.commit()
        if dropped:
            _LOG.warning("conversation_sync.blobs_dropped", extra={"count": dropped})
        return dropped

    def purge(self) -> None:
        now = self._clock()
        with self._database.connect() as connection:
            connection.execute(
                "DELETE FROM conversation_sync_outbox WHERE (state = 'sent' AND sent_at < ?) "
                "OR (state = 'failed' AND created_at < ?) OR (state = 'pending' AND created_at < ?)",
                (now - RETENTION_SECONDS, now - RETENTION_SECONDS, now - STALE_PENDING_SECONDS),
            )
            connection.execute(
                "DELETE FROM conversation_sync_blobs WHERE (state IN ('sent', 'failed') AND created_at < ?) "
                "OR (state = 'pending' AND created_at < ?)",
                (now - RETENTION_SECONDS, now - STALE_PENDING_SECONDS),
            )
            connection.execute("DELETE FROM conversation_sync_sessions WHERE created_at < ?",
                               (now - SESSION_RETENTION_SECONDS,))
            connection.commit()

    def counts(self) -> dict[str, int]:
        with self._database.connect() as connection:
            events = dict(connection.execute(
                "SELECT state, COUNT(*) FROM conversation_sync_outbox GROUP BY state").fetchall())
            blobs = dict(connection.execute(
                "SELECT state, COUNT(*) FROM conversation_sync_blobs GROUP BY state").fetchall())
        return {
            "pending": int(events.get("pending", 0)) + int(events.get("sending", 0)),
            "sent": int(events.get("sent", 0)),
            "failed": int(events.get("failed", 0)),
            "imagesPending": int(blobs.get("pending", 0)) + int(blobs.get("sending", 0)),
            "imagesFailed": int(blobs.get("failed", 0)),
        }


def _newer(row: sqlite3.Row, event: OutgoingEvent) -> bool:
    if row["seq"] is not None and event.seq is not None:
        return int(row["seq"]) > event.seq
    return int(row["event_at"]) > event.event_at
