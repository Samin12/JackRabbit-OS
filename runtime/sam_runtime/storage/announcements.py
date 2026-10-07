"""Durable runtime-to-app announcements outbox with an in-process long-poll.

Any runtime owner can ``publish`` one short, speakable announcement. The native
app long-polls ``next`` with its last cursor and acknowledges what it did with
each item (spoke it, posted a notification, or showed it). Only the most recent
``RETAIN`` items are kept.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
import json
import threading
import time

from .database import RuntimeDatabase


RETAIN = 200
MAX_BATCH = 50
MAX_WAIT_SECONDS = 25.0
ACK_CHANNELS = {"voice": "voice_acked_at", "notification": "notification_acked_at", "seen": "seen_acked_at"}
_RECENT_UNACKED_WINDOW = timedelta(hours=6)


@dataclass(frozen=True, slots=True)
class Announcement:
    announcement_id: int
    kind: str
    title: str
    text: str
    payload: dict[str, object]
    created_at: str
    acked: tuple[str, ...] = ()

    def view(self) -> dict[str, object]:
        return {
            "id": self.announcement_id,
            "kind": self.kind,
            "title": self.title,
            "text": self.text,
            "payload": dict(self.payload),
            "createdAt": self.created_at,
        }


class AnnouncementRepository:
    def __init__(
        self,
        database: RuntimeDatabase,
        *,
        on_publish: Callable[[Announcement], None] | None = None,
        clock: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self._database = database
        self._on_publish = on_publish
        self._clock = clock
        self._condition = threading.Condition()

    def set_publish_listener(self, listener: Callable[[Announcement], None] | None) -> None:
        self._on_publish = listener

    def publish(self, kind: str, title: str, text: str, payload: dict[str, object] | None = None) -> Announcement:
        kind = str(kind).strip()
        if not kind:
            raise ValueError("Announcement kind is required.")
        created_at = _iso(self._clock())
        body = json.dumps(dict(payload or {}), separators=(",", ":"), ensure_ascii=False)
        with self._condition:
            with self._database.connect() as connection:
                cursor = connection.execute(
                    "INSERT INTO announcements(kind, title, text, payload_json, created_at) VALUES (?, ?, ?, ?, ?)",
                    (kind, str(title)[:200], str(text)[:600], body, created_at),
                )
                announcement_id = int(cursor.lastrowid)
                connection.execute(
                    "DELETE FROM announcements WHERE announcement_id <= ?",
                    (announcement_id - RETAIN,),
                )
                connection.commit()
            self._condition.notify_all()
        item = Announcement(announcement_id, kind, str(title)[:200], str(text)[:600], json.loads(body), created_at)
        listener = self._on_publish
        if listener is not None:
            try:
                listener(item)
            except Exception:
                pass
        return item

    def latest_id(self) -> int:
        with self._database.connect() as connection:
            row = connection.execute("SELECT MAX(announcement_id) FROM announcements").fetchone()
        return int(row[0] or 0)

    def after(self, cursor: int, *, limit: int = MAX_BATCH) -> tuple[Announcement, ...]:
        with self._database.connect() as connection:
            rows = connection.execute(
                "SELECT announcement_id, kind, title, text, payload_json, created_at, voice_acked_at, "
                "notification_acked_at, seen_acked_at FROM announcements WHERE announcement_id > ? "
                "ORDER BY announcement_id LIMIT ?",
                (int(cursor), int(limit)),
            ).fetchall()
        return tuple(_row(row) for row in rows)

    def recent_unacked(self, *, limit: int = 20) -> tuple[Announcement, ...]:
        since = _iso(self._clock() - _RECENT_UNACKED_WINDOW)
        with self._database.connect() as connection:
            rows = connection.execute(
                "SELECT announcement_id, kind, title, text, payload_json, created_at, voice_acked_at, "
                "notification_acked_at, seen_acked_at FROM announcements WHERE voice_acked_at IS NULL "
                "AND notification_acked_at IS NULL AND seen_acked_at IS NULL AND created_at >= ? "
                "ORDER BY announcement_id DESC LIMIT ?",
                (since, int(limit)),
            ).fetchall()
        return tuple(reversed([_row(row) for row in rows]))

    def next(self, after: int | None, *, wait_seconds: float = MAX_WAIT_SECONDS) -> tuple[tuple[Announcement, ...], int]:
        """Return items newer than ``after``, blocking up to ``wait_seconds`` for one.

        ``after=None`` asks for recent unacknowledged items (app restart). A
        cursor beyond the newest id (for example after app data was reset)
        returns immediately with the current newest id so the client resyncs.
        """
        wait_seconds = max(0.0, min(MAX_WAIT_SECONDS, float(wait_seconds)))
        latest = self.latest_id()
        if after is None:
            items = self.recent_unacked()
            return items, latest
        after = int(after)
        if after > latest:
            return (), latest
        deadline = time.monotonic() + wait_seconds
        with self._condition:
            while True:
                items = self.after(after)
                if items:
                    return items, items[-1].announcement_id
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return (), after
                self._condition.wait(timeout=remaining)

    def ack(self, announcement_id: int, channel: str) -> bool:
        column = ACK_CHANNELS.get(channel)
        if column is None:
            raise ValueError("Announcement channel must be voice, notification, or seen.")
        with self._database.connect() as connection:
            cursor = connection.execute(
                f"UPDATE announcements SET {column} = COALESCE({column}, ?) WHERE announcement_id = ?",
                (_iso(self._clock()), int(announcement_id)),
            )
            connection.commit()
        return cursor.rowcount > 0

    def get(self, announcement_id: int) -> Announcement | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT announcement_id, kind, title, text, payload_json, created_at, voice_acked_at, "
                "notification_acked_at, seen_acked_at FROM announcements WHERE announcement_id = ?",
                (int(announcement_id),),
            ).fetchone()
        return _row(row) if row is not None else None


def _row(row: object) -> Announcement:
    try:
        payload = json.loads(row[4])
    except (TypeError, ValueError):
        payload = {}
    acked = tuple(
        name for name, value in (("voice", row[6]), ("notification", row[7]), ("seen", row[8])) if value
    )
    return Announcement(
        announcement_id=int(row[0]),
        kind=str(row[1]),
        title=str(row[2]),
        text=str(row[3]),
        payload=payload if isinstance(payload, dict) else {},
        created_at=str(row[5]),
        acked=acked,
    )


def _iso(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
