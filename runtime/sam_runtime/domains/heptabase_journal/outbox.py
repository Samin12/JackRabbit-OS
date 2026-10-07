"""Durable journal outbox: per-date FIFO, batching, retry, and uncertain-send checks.

States: held (unverified voice note awaiting the session transcript, never sent),
pending, sending, uncertain (body may have reached Heptabase), sent, failed.
The queue is paused at the connection level (worker gate) while Heptabase needs
reconnecting, so ``uncertain`` rows keep their state and are still verified
before any resend after reconnect.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import dataclass
import sqlite3
import threading
import time
from uuid import uuid4

from sam_runtime.core.logging import runtime_logger
from sam_runtime.storage.database import RuntimeDatabase

from .format import MAX_CALL_BYTES
from .localtime import iso_utc

_LOG = runtime_logger()
BACKOFF_SECONDS = (30, 120, 600, 3600)
SENT_RETENTION_SECONDS = 30 * 86400
HELD_RETENTION_SECONDS = 86400
# Mac-bridge failures that say nothing about the entry itself (Mac asleep, Heptabase app closed):
# safe to retry as soon as the user is active again. OAuth/network backoff is left unchanged.
TRANSIENT_ERRORS = (
    "bridge_unreachable", "bridge_connection_lost", "bridge_not_configured", "bridge_busy", "bridge_unauthorized",
    "bridge_token_unavailable", "heptabase_app_unavailable", "heptabase_cli_missing", "heptabase_busy",
    "heptabase_app_error", "heptabase_cli_timeout", "heptabase_cli_failed", "heptabase_cli_bad_output",
)
_COLUMNS = (
    "entry_id, journal_date, kind, source_ref, voice_session_id, utterance_id, utterance_key, content, "
    "plain_content, fingerprint, candidate_text, use_plain, state, attempts, event_at, next_attempt_at, "
    "last_error, created_at, sent_at"
)


def backoff_seconds(attempts: int) -> int:
    return BACKOFF_SECONDS[min(max(attempts, 1), len(BACKOFF_SECONDS)) - 1]


@dataclass(frozen=True, slots=True)
class OutboxEntry:
    entry_id: str
    journal_date: str
    kind: str
    source_ref: str | None
    voice_session_id: str | None
    utterance_id: int | None
    utterance_key: str | None
    content: str
    plain_content: str
    fingerprint: str
    candidate_text: str | None
    use_plain: bool
    state: str
    attempts: int
    event_at: str
    next_attempt_at: str
    last_error: str | None
    created_at: str
    sent_at: str | None

    @property
    def body(self) -> str:
        return self.plain_content if self.use_plain else self.content


@dataclass(frozen=True, slots=True)
class NewEntry:
    journal_date: str
    kind: str
    content: str
    plain_content: str
    fingerprint: str
    event_at: float
    source_ref: str | None = None
    voice_session_id: str | None = None
    utterance_id: int | None = None
    utterance_key: str | None = None
    candidate_text: str | None = None
    state: str = "pending"


@dataclass(frozen=True, slots=True)
class Work:
    action: str  # "send" | "verify"
    journal_date: str
    entries: tuple[OutboxEntry, ...]


def _entry(row: sqlite3.Row) -> OutboxEntry:
    return OutboxEntry(
        entry_id=str(row["entry_id"]),
        journal_date=str(row["journal_date"]),
        kind=str(row["kind"]),
        source_ref=row["source_ref"],
        voice_session_id=row["voice_session_id"],
        utterance_id=int(row["utterance_id"]) if row["utterance_id"] is not None else None,
        utterance_key=row["utterance_key"],
        content=str(row["content"]),
        plain_content=str(row["plain_content"]),
        fingerprint=str(row["fingerprint"]),
        candidate_text=row["candidate_text"],
        use_plain=bool(row["use_plain"]),
        state=str(row["state"]),
        attempts=int(row["attempts"]),
        event_at=str(row["event_at"]),
        next_attempt_at=str(row["next_attempt_at"]),
        last_error=row["last_error"],
        created_at=str(row["created_at"]),
        sent_at=row["sent_at"],
    )


class JournalOutboxRepository:
    def __init__(self, database: RuntimeDatabase, *, clock: Callable[[], float] = time.time) -> None:
        self._database = database
        self._clock = clock

    def _now(self) -> str:
        return iso_utc(self._clock())

    # ------------------------------------------------------------------ writes

    def enqueue(self, item: NewEntry) -> tuple[OutboxEntry, bool]:
        """Insert one entry. Returns (entry, created); a duplicate ``(kind, source_ref)``
        returns the existing row with ``created=False``."""
        now = self._now()
        entry_id = str(uuid4())
        with self._database.connect() as connection:
            try:
                connection.execute(
                    f"INSERT INTO journal_outbox({_COLUMNS}, updated_at) VALUES "
                    "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?, NULL, ?, NULL, ?)",
                    (entry_id, item.journal_date, item.kind, item.source_ref, item.voice_session_id,
                     item.utterance_id, item.utterance_key, item.content, item.plain_content, item.fingerprint,
                     item.candidate_text, item.state, iso_utc(item.event_at), now, now, now),
                )
                connection.commit()
                created = True
            except sqlite3.IntegrityError:
                connection.rollback()
                if item.source_ref is None:
                    raise
                row = connection.execute(
                    f"SELECT {_COLUMNS} FROM journal_outbox WHERE kind = ? AND source_ref = ?",
                    (item.kind, item.source_ref),
                ).fetchone()
                return _entry(row), False
        stored = self.get(entry_id)
        assert stored is not None
        return stored, created

    def resolve_held(self, entry_id: str, *, journal_date: str, content: str, plain_content: str,
                     fingerprint: str, event_at: float, utterance_key: str) -> None:
        with self._database.connect() as connection:
            connection.execute(
                "UPDATE journal_outbox SET state = 'pending', journal_date = ?, content = ?, plain_content = ?, "
                "fingerprint = ?, event_at = ?, utterance_key = ?, candidate_text = NULL, next_attempt_at = ?, "
                "updated_at = ? WHERE entry_id = ? AND state = 'held'",
                (journal_date, content, plain_content, fingerprint, iso_utc(event_at), utterance_key,
                 self._now(), self._now(), entry_id),
            )
            connection.commit()

    def drop(self, entry_id: str) -> None:
        with self._database.connect() as connection:
            connection.execute("DELETE FROM journal_outbox WHERE entry_id = ?", (entry_id,))
            connection.commit()

    def mark_sending(self, entry_ids: Iterable[str]) -> None:
        self._update(entry_ids, "state = 'sending', attempts = attempts + 1")

    def mark_sent(self, entry_ids: Iterable[str]) -> None:
        now = self._now()
        self._update(entry_ids, "state = 'sent', sent_at = ?, last_error = NULL, candidate_text = NULL", (now,))

    def mark_uncertain(self, entry_ids: Iterable[str], error: str, retry_at: float) -> None:
        self._update(entry_ids, "state = 'uncertain', last_error = ?, next_attempt_at = ?",
                     (error[:300], iso_utc(retry_at)))

    def mark_retry(self, entry_ids: Iterable[str], error: str, retry_at: float) -> None:
        self._update(entry_ids, "state = 'pending', last_error = ?, next_attempt_at = ?",
                     (error[:300], iso_utc(retry_at)))

    def mark_failed(self, entry_ids: Iterable[str], error: str) -> None:
        self._update(entry_ids, "state = 'failed', last_error = ?", (error[:300],))

    def mark_resend(self, entry_ids: Iterable[str]) -> None:
        """Fingerprint not found after an uncertain send: safe to send again now."""
        self._update(entry_ids, "state = 'pending', next_attempt_at = ?", (self._now(),))

    def set_use_plain(self, entry_id: str) -> None:
        self._update((entry_id,), "use_plain = 1, state = 'pending', next_attempt_at = ?", (self._now(),))

    def expedite(self, journal_date: str | None = None) -> int:
        """Retry now instead of waiting out a backoff that a transient Mac-bridge failure caused.
        Rejections keep their backoff and attempt count."""
        now = self._now()
        codes = (*TRANSIENT_ERRORS, *(f"verify_{code}" for code in TRANSIENT_ERRORS))
        marks = ",".join("?" for _ in codes)
        scope, params = ("AND journal_date = ?", (journal_date,)) if journal_date else ("", ())
        with self._database.connect() as connection:
            cursor = connection.execute(
                f"UPDATE journal_outbox SET next_attempt_at = ?, updated_at = ? "
                f"WHERE state IN ('pending', 'uncertain') AND next_attempt_at > ? AND last_error IN ({marks}) {scope}",
                (now, now, now, *codes, *params),
            )
            connection.commit()
        return cursor.rowcount

    def retry_failed(self) -> int:
        with self._database.connect() as connection:
            cursor = connection.execute(
                "UPDATE journal_outbox SET state = 'pending', attempts = 0, use_plain = 0, next_attempt_at = ?, "
                "updated_at = ? WHERE state = 'failed'",
                (self._now(), self._now()),
            )
            connection.commit()
        return cursor.rowcount

    def recover_interrupted(self) -> int:
        """A crash mid-request leaves 'sending' rows: they may have been written."""
        with self._database.connect() as connection:
            cursor = connection.execute(
                "UPDATE journal_outbox SET state = 'uncertain', next_attempt_at = ?, updated_at = ? "
                "WHERE state = 'sending'",
                (self._now(), self._now()),
            )
            connection.commit()
        return cursor.rowcount

    def purge(self) -> None:
        now = self._clock()
        with self._database.connect() as connection:
            connection.execute("DELETE FROM journal_outbox WHERE state = 'sent' AND sent_at < ?",
                               (iso_utc(now - SENT_RETENTION_SECONDS),))
            connection.execute("DELETE FROM journal_outbox WHERE state = 'held' AND created_at < ?",
                               (iso_utc(now - HELD_RETENTION_SECONDS),))
            connection.commit()

    # ------------------------------------------------------------------ reads

    def get(self, entry_id: str) -> OutboxEntry | None:
        with self._database.connect() as connection:
            row = connection.execute(f"SELECT {_COLUMNS} FROM journal_outbox WHERE entry_id = ?", (entry_id,)).fetchone()
        return _entry(row) if row is not None else None

    def held_for_session(self, session_id: str) -> list[OutboxEntry]:
        return self._select("WHERE voice_session_id = ? AND state = 'held' ORDER BY created_at, rowid", (session_id,))

    def session_notes(self, session_id: str) -> list[OutboxEntry]:
        return self._select("WHERE voice_session_id = ? AND kind = 'note' AND state != 'held' ORDER BY created_at, rowid",
                            (session_id,))

    def queue(self) -> list[OutboxEntry]:
        return self._select(
            "WHERE state IN ('pending', 'uncertain') ORDER BY journal_date, event_at, created_at, rowid", ()
        )

    def counts(self) -> dict[str, int]:
        with self._database.connect() as connection:
            rows = connection.execute("SELECT state, COUNT(*) FROM journal_outbox GROUP BY state").fetchall()
        values = {str(row[0]): int(row[1]) for row in rows}
        return {state: values.get(state, 0) for state in ("held", "pending", "sending", "uncertain", "sent", "failed")}

    def last_error(self) -> str | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT last_error FROM journal_outbox WHERE state = 'failed' ORDER BY updated_at DESC LIMIT 1"
            ).fetchone()
        return str(row[0]) if row is not None and row[0] else None

    def _select(self, where: str, params: tuple[object, ...]) -> list[OutboxEntry]:
        with self._database.connect() as connection:
            rows = connection.execute(f"SELECT {_COLUMNS} FROM journal_outbox {where}", params).fetchall()
        return [_entry(row) for row in rows]

    def _update(self, entry_ids: Iterable[str], assignments: str, params: tuple[object, ...] = ()) -> None:
        ids = tuple(entry_ids)
        if not ids:
            return
        marks = ",".join("?" for _ in ids)
        with self._database.connect() as connection:
            connection.execute(
                f"UPDATE journal_outbox SET {assignments}, updated_at = ? WHERE entry_id IN ({marks})",
                (*params, self._now(), *ids),
            )
            connection.commit()


def next_work(queue: list[OutboxEntry], now_iso: str, solo: frozenset[str] | set[str] = frozenset()) -> Work | None:
    """Pick the next action, keeping each journal date strictly chronological.

    A date whose oldest unsent entry is backing off or uncertain blocks every
    later entry of that date (so a resend never lands out of order); other
    dates proceed independently. Pending entries are batched up to 16 KB;
    plain-fallback entries and ids in ``solo`` (split out of a rejected batch)
    are always sent alone.
    """
    by_date: dict[str, list[OutboxEntry]] = {}
    for item in queue:
        by_date.setdefault(item.journal_date, []).append(item)
    for journal_date in sorted(by_date):
        rows = by_date[journal_date]
        head = rows[0]
        if head.next_attempt_at > now_iso:
            continue
        if head.state == "uncertain":
            verify = []
            for row in rows:
                if row.state != "uncertain":
                    break
                verify.append(row)
            return Work("verify", journal_date, tuple(verify))
        batch: list[OutboxEntry] = []
        size = 0
        for row in rows:
            if row.state != "pending" or row.next_attempt_at > now_iso:
                break
            alone = row.use_plain or row.entry_id in solo
            row_size = len(row.body.encode("utf-8")) + (2 if batch else 0)
            if batch and (size + row_size > MAX_CALL_BYTES or alone):
                break
            batch.append(row)
            size += row_size
            if alone:
                break
        if batch:
            return Work("send", journal_date, tuple(batch))
    return None


class JournalWorker:
    """Single writer thread. ``step()`` performs at most one network action and
    returns True when it did work; the loop drains, then idles (15 s) until woken."""

    def __init__(self, step: Callable[[], bool], *, idle_seconds: float = 15.0) -> None:
        self._step = step
        self._idle = idle_seconds
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._changed = threading.Condition()
        self._thread: threading.Thread | None = None
        self._inline = threading.Lock()

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="sam-heptabase-journal", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
        self._thread = None

    def wake(self) -> None:
        self._wake.set()

    def wait_until(self, predicate: Callable[[], bool], timeout: float) -> bool:
        """Wait for the worker (or drain inline when no worker thread runs)."""
        if not self.running:
            deadline = time.monotonic() + timeout
            with self._inline:
                while not predicate() and time.monotonic() < deadline:
                    if not self._safe_step():
                        break
            return predicate()
        self.wake()
        with self._changed:
            return self._changed.wait_for(predicate, timeout)

    def drain(self, limit: int = 100) -> int:
        """Run steps inline until idle (tests and shutdown flushes)."""
        done = 0
        with self._inline:
            while done < limit and self._safe_step():
                done += 1
        return done

    def _safe_step(self) -> bool:
        try:
            did = self._step()
        except Exception:
            _LOG.exception("heptabase.journal.step_failed")
            did = False
        with self._changed:
            self._changed.notify_all()
        return did

    def _run(self) -> None:
        while not self._stop.is_set():
            self._wake.clear()  # before draining, so a wake() during the drain is never lost
            while not self._stop.is_set() and self._safe_step():
                pass
            self._wake.wait(self._idle)
