from __future__ import annotations

from datetime import UTC, datetime, timedelta
import hashlib
import json
import sqlite3
from uuid import NAMESPACE_URL, uuid5

from sam_runtime.domains.calendar.models import (
    CalendarAccount,
    CalendarAccountConfiguration,
    CalendarCapabilities,
    CalendarEvent,
)
from sam_runtime.storage.database import RuntimeDatabase


MAX_CALENDAR_ACCOUNTS = 2
# ``calendar_events.source_etag`` of a row the R1 wrote itself (through the Mac bridge) and the feed has not
# shown yet: ``samrabbit-write:<create|update|delete>:<UTC ISO time>``. Feed rows never carry it.
LOCAL_WRITE_PREFIX = "samrabbit-write:"
LOCAL_WRITE_OPERATIONS = frozenset({"create", "update", "delete"})


def calendar_event_key(account_id: str, provider_event_id: str, recurrence_id: str) -> str:
    """The stable row id of one event occurrence; a feed refresh and the R1's own write compute the same."""
    return str(uuid5(NAMESPACE_URL, f"calendar:{account_id}:{provider_event_id}:{recurrence_id}"))


def local_write_tag(operation: str, at: datetime) -> str:
    if operation not in LOCAL_WRITE_OPERATIONS:
        raise ValueError("Calendar operation is invalid.")
    return f"{LOCAL_WRITE_PREFIX}{operation}:{at.astimezone(UTC).isoformat()}"


def parse_local_write_tag(tag: str | None) -> tuple[str, datetime] | None:
    if not tag or not tag.startswith(LOCAL_WRITE_PREFIX):
        return None
    operation, _, stamp = tag[len(LOCAL_WRITE_PREFIX):].partition(":")
    try:
        at = datetime.fromisoformat(stamp)
    except ValueError:
        return None
    if operation not in LOCAL_WRITE_OPERATIONS or at.tzinfo is None:
        return None
    return operation, at


class CalendarAccountLimitError(ValueError):
    pass


class CalendarCapabilityDenied(PermissionError):
    pass


class CalendarRepository:
    """Owns canonical Calendar SQL and the transactional two-account limit."""

    def __init__(self, database: RuntimeDatabase) -> None:
        self._database = database

    def create_account(
        self,
        configuration: CalendarAccountConfiguration,
        credential_envelope: str | None,
    ) -> CalendarAccount:
        _validate_configuration(configuration)
        now = datetime.now(UTC).isoformat()
        with self._database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            count = int(connection.execute("SELECT COUNT(*) FROM calendar_accounts").fetchone()[0])
            if count >= MAX_CALENDAR_ACCOUNTS:
                raise CalendarAccountLimitError("This R1 already has two Calendar connections.")
            connection.execute(
                """
                INSERT INTO connections(
                    connection_id, kind, label, enabled, health_state, source_owner,
                    created_at, updated_at
                ) VALUES (?, 'calendar', ?, 1, 'unconfigured', 'calendar', ?, ?)
                """,
                (configuration.account_id, configuration.label, now, now),
            )
            if credential_envelope is not None:
                connection.execute(
                    """
                    INSERT INTO connection_credential_envelopes(connection_id, envelope, updated_at)
                    VALUES (?, ?, ?)
                    """,
                    (configuration.account_id, credential_envelope, now),
                )
            connection.execute(
                """
                INSERT INTO calendar_accounts(
                    account_id, provider_type, label, endpoint, calendar_path,
                    next_sync_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    configuration.account_id,
                    configuration.provider_type,
                    configuration.label,
                    configuration.endpoint,
                    configuration.calendar_path,
                    now,
                    now,
                    now,
                ),
            )
            connection.commit()
        account = self.get_account(configuration.account_id)
        if account is None:
            raise RuntimeError("Calendar connection was not persisted.")
        return account

    def get_account(self, account_id: str) -> CalendarAccount | None:
        with self._database.connect() as connection:
            row = connection.execute(_ACCOUNT_SELECT + " WHERE a.account_id = ?", (account_id,)).fetchone()
        return _account(row)

    def list_accounts(self) -> tuple[CalendarAccount, ...]:
        with self._database.connect() as connection:
            rows = connection.execute(_ACCOUNT_SELECT + " ORDER BY a.label, a.account_id").fetchall()
        return tuple(item for row in rows if (item := _account(row)) is not None)

    def remove_account_local(self, account_id: str) -> bool:
        with self._database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            cursor = connection.execute(
                "DELETE FROM connections WHERE connection_id = ? AND kind = 'calendar'",
                (account_id,),
            )
            connection.execute(
                "DELETE FROM connection_credential_envelopes WHERE connection_id = ?",
                (account_id,),
            )
            connection.commit()
        return cursor.rowcount > 0

    def set_capabilities(
        self,
        account_id: str,
        capabilities: CalendarCapabilities,
    ) -> CalendarAccount:
        now = datetime.now(UTC).isoformat()
        with self._database.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE calendar_accounts
                SET can_create = ?, can_update = ?, can_delete = ?, updated_at = ?
                WHERE account_id = ?
                """,
                (
                    int(capabilities.can_create), int(capabilities.can_update),
                    int(capabilities.can_delete), now, account_id,
                ),
            )
            connection.commit()
        if cursor.rowcount == 0:
            raise ValueError("Calendar connection was not found.")
        account = self.get_account(account_id)
        if account is None:
            raise RuntimeError("Calendar capabilities were not persisted.")
        return account

    def require_capability(
        self,
        account_id: str,
        operation: str,
        *,
        event_id: str | None = None,
    ) -> CalendarAccount:
        account = self.get_account(account_id)
        if account is None:
            raise ValueError("Calendar connection was not found.")
        allowed = {
            "create": account.capabilities.can_create,
            "update": account.capabilities.can_update,
            "delete": account.capabilities.can_delete,
        }.get(operation)
        if allowed is None:
            raise ValueError("Calendar operation is invalid.")
        if not allowed:
            raise CalendarCapabilityDenied(f"This calendar does not allow {operation} operations.")
        if event_id is not None:
            event = self.get_event(event_id)
            if event is None or event.account_id != account_id:
                raise ValueError("Calendar event was not found.")
            if not event.editable:
                raise CalendarCapabilityDenied("This calendar event is read-only.")
        return account

    def get_event(self, event_id: str) -> CalendarEvent | None:
        with self._database.connect() as connection:
            row = connection.execute(_EVENT_SELECT + " WHERE event_id = ?", (event_id,)).fetchone()
        return _event(row) if row is not None else None

    def due_accounts(self, now: str) -> tuple[CalendarAccount, ...]:
        with self._database.connect() as connection:
            rows = connection.execute(
                _ACCOUNT_SELECT + " WHERE c.enabled = 1 AND (a.next_sync_at IS NULL OR a.next_sync_at <= ?) ORDER BY a.next_sync_at, a.account_id",
                (now,),
            ).fetchall()
        return tuple(item for row in rows if (item := _account(row)) is not None)

    def begin_sync(self, account_id: str) -> None:
        now = datetime.now(UTC)
        with self._database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT last_sync_state, sync_lease_until FROM calendar_accounts WHERE account_id = ?", (account_id,)).fetchone()
            if row is None or (row[0] == "syncing" and row[1] is not None and row[1] > now.isoformat()):
                raise ValueError("Calendar synchronization is unavailable or already running.")
            connection.execute("UPDATE calendar_accounts SET last_sync_state = 'syncing', sync_lease_until = ?, last_sync_detail = NULL, updated_at = ? WHERE account_id = ?", ((now + timedelta(minutes=10)).isoformat(), now.isoformat(), account_id))
            connection.commit()

    def finish_sync(self, account_id: str, *, ready: bool, detail: str | None) -> None:
        now = datetime.now(UTC)
        state = "ready" if ready else "failed"
        with self._database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            connection.execute("UPDATE calendar_accounts SET last_sync_at = ?, next_sync_at = ?, last_sync_state = ?, last_sync_detail = ?, sync_lease_until = NULL, updated_at = ? WHERE account_id = ?", (now.isoformat(), (now + timedelta(minutes=5 if ready else 1)).isoformat(), state, detail, now.isoformat(), account_id))
            connection.execute("UPDATE connections SET health_state = ?, health_detail = ?, updated_at = ? WHERE connection_id = ?", (state, detail, now.isoformat(), account_id))
            connection.commit()

    def search_upcoming(self, now: str, query: str, *, limit: int = 50) -> tuple[CalendarEvent, ...]:
        pattern = f"%{query.strip()}%"
        with self._database.connect() as connection:
            rows = connection.execute(
                _EVENT_SELECT + " WHERE status != 'cancelled' AND CASE WHEN ends_at IS NULL THEN starts_at >= ? ELSE ends_at >= ? END AND (title LIKE ? OR location LIKE ? OR description LIKE ?) ORDER BY starts_at, event_id LIMIT ?",
                (now, now, pattern, pattern, pattern, max(1, min(limit, 50))),
            ).fetchall()
        return tuple(_event(row) for row in rows)

    def create_pending_action(self, *, account_id: str, event_id: str | None, operation: str, payload: dict[str, object], voice_session_id: str, tool_call_id: str, utterance_id: int, writable_elsewhere: bool = False) -> dict[str, object]:
        """``writable_elsewhere``: the change goes through the Mac bridge to the calendar behind a read-only
        feed, so the feed's own capabilities don't apply (the event must still belong to the account)."""
        from uuid import uuid4
        if writable_elsewhere:
            event = self.get_event(event_id) if event_id is not None else None
            if self.get_account(account_id) is None or (event_id is not None and (event is None or event.account_id != account_id)):
                raise ValueError("Calendar event was not found.")
        else:
            self.require_capability(account_id, operation, event_id=event_id)
        if not voice_session_id or not tool_call_id or utterance_id <= 0:
            raise ValueError("A trusted agent invocation is required for Calendar changes.")
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        digest = hashlib.sha256(canonical.encode()).hexdigest()
        action_id = str(uuid4())
        now = datetime.now(UTC)
        expires = now + timedelta(minutes=10)
        with self._database.connect() as connection:
            connection.execute("INSERT INTO calendar_pending_actions(action_id, account_id, event_id, operation, payload_json, content_hash, state, voice_session_id, tool_call_id, prepared_utterance_id, expires_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'pending_review', ?, ?, ?, ?, ?, ?)", (action_id, account_id, event_id, operation, canonical, digest, voice_session_id, tool_call_id, utterance_id, expires.isoformat(), now.isoformat(), now.isoformat()))
            connection.commit()
        return {"actionId": action_id, "contentHash": digest, "expiresAt": expires.isoformat(), "operation": operation, "payload": payload}

    def claim_pending_action(self, *, action_id: str, content_hash: str, voice_session_id: str, approval_utterance_id: int) -> dict[str, object]:
        now = datetime.now(UTC).isoformat()
        with self._database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT account_id, event_id, operation, payload_json FROM calendar_pending_actions WHERE action_id = ? AND content_hash = ? AND voice_session_id = ? AND state = 'pending_review' AND expires_at >= ? AND prepared_utterance_id < ?", (action_id, content_hash, voice_session_id, now, approval_utterance_id)).fetchone()
            if row is None:
                connection.rollback()
                raise ValueError("The reviewed Calendar change is stale, changed, expired, or already used.")
            connection.execute("UPDATE calendar_pending_actions SET state = 'executing', updated_at = ? WHERE action_id = ?", (now, action_id))
            connection.commit()
        return {"accountId": row[0], "eventId": row[1], "operation": row[2], "payload": json.loads(row[3])}

    def finish_pending_action(self, action_id: str, *, completed: bool) -> None:
        with self._database.connect() as connection:
            connection.execute("UPDATE calendar_pending_actions SET state = ?, updated_at = ? WHERE action_id = ?", ("completed" if completed else "failed", datetime.now(UTC).isoformat(), action_id))
            connection.commit()

    def replace_account_events(
        self,
        account_id: str,
        events: tuple[CalendarEvent, ...],
        *,
        keep_local_writes_for: timedelta | None = None,
        now: datetime | None = None,
    ) -> None:
        """Make ``events`` the account's rows.

        With ``keep_local_writes_for``, rows the R1 wrote itself (``local_write_tag``) younger than that win
        over a feed that has not caught up yet: a new or moved event stays until the feed shows it (same
        title and times; for a new event any copy), and a deleted one stays hidden while the feed still has
        it. The rows share the feed's key (account, iCal UID, occurrence), so nothing is ever duplicated.
        """
        moment = now or datetime.now(UTC)
        with self._database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if connection.execute(
                "SELECT 1 FROM calendar_accounts WHERE account_id = ?", (account_id,)
            ).fetchone() is None:
                raise ValueError("Calendar connection was not found.")
            pending: dict[tuple[str, str], tuple[str, CalendarEvent]] = {}
            if keep_local_writes_for is not None:
                rows = connection.execute(
                    _EVENT_SELECT + " WHERE account_id = ? AND source_etag LIKE ?",
                    (account_id, LOCAL_WRITE_PREFIX + "%"),
                ).fetchall()
                for row in rows:
                    local = _event(row)
                    tag = parse_local_write_tag(local.source_etag)
                    if tag is not None and moment - tag[1] <= keep_local_writes_for:
                        pending[(local.provider_event_id, local.recurrence_id)] = (tag[0], local)
            connection.execute("CREATE TEMP TABLE calendar_seen_events(event_id TEXT PRIMARY KEY)")
            for event in events:
                if event.account_id != account_id:
                    raise ValueError("Calendar event belongs to a different connection.")
                local_write = pending.pop((event.provider_event_id, event.recurrence_id), None)
                if local_write is not None and not _feed_caught_up(local_write[0], local_write[1], event):
                    event = local_write[1]
                _store_event(connection, event)
                connection.execute(
                    "INSERT INTO calendar_seen_events(event_id) VALUES (?)", (event.event_id,)
                )
            for operation, local in pending.values():
                if operation == "delete":
                    continue  # the feed no longer has it either: the row goes
                _store_event(connection, local)
                connection.execute(
                    "INSERT INTO calendar_seen_events(event_id) VALUES (?)", (local.event_id,)
                )
            connection.execute(
                """
                DELETE FROM calendar_events
                WHERE account_id = ? AND NOT EXISTS (
                    SELECT 1 FROM calendar_seen_events seen
                    WHERE seen.event_id = calendar_events.event_id
                )
                """,
                (account_id,),
            )
            connection.execute("DROP TABLE calendar_seen_events")
            connection.commit()

    def store_local_write(self, event: CalendarEvent) -> None:
        """Insert or replace one row the R1 just wrote through the Mac bridge (``local_write_tag``)."""
        if parse_local_write_tag(event.source_etag) is None:
            raise ValueError("A local calendar write needs its tag.")
        with self._database.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            if connection.execute(
                "SELECT 1 FROM calendar_accounts WHERE account_id = ?", (event.account_id,)
            ).fetchone() is None:
                raise ValueError("Calendar connection was not found.")
            _store_event(connection, event)
            connection.commit()

    def upcoming_events(self, now: str, *, limit: int = 50, all_day_grace_hours: int = 0) -> tuple[CalendarEvent, ...]:
        """Events not yet over at ``now`` (UTC ISO).

        All-day events are stored as UTC midnight of a floating date (end exclusive), so in a zone
        behind UTC they would end too early (8 PM in New York). ``all_day_grace_hours`` keeps them
        that much longer so a device can decide "still today" in its own time zone.
        """
        all_day_cutoff = now
        if all_day_grace_hours > 0:
            all_day_cutoff = (datetime.fromisoformat(now) - timedelta(hours=all_day_grace_hours)).isoformat()
        with self._database.connect() as connection:
            rows = connection.execute(
                """
                """ + _EVENT_SELECT + """
                WHERE status != 'cancelled'
                  AND CASE WHEN ends_at IS NULL THEN starts_at >= ?
                           WHEN all_day = 1 THEN ends_at >= ?
                           ELSE ends_at >= ? END
                ORDER BY starts_at, event_id
                LIMIT ?
                """,
                (now, all_day_cutoff, now, max(1, min(limit, 200))),
            ).fetchall()
        return tuple(_event(row) for row in rows)


_ACCOUNT_SELECT = """
SELECT a.account_id, a.provider_type, a.label, a.endpoint, a.calendar_path,
       a.can_create, a.can_update, a.can_delete, c.enabled,
       EXISTS(SELECT 1 FROM connection_credential_envelopes e WHERE e.connection_id = a.account_id),
       a.next_sync_at, a.last_sync_at, a.last_sync_state, a.last_sync_detail,
       a.created_at, a.updated_at
FROM calendar_accounts a JOIN connections c ON c.connection_id = a.account_id
"""

_EVENT_SELECT = """
SELECT event_id, account_id, provider_event_id, recurrence_id, title,
       starts_at, ends_at, timezone, all_day, location, calendar_name,
       organizer, description, status, editable, source_etag, synchronized_at
FROM calendar_events
"""


def _feed_caught_up(operation: str, local: CalendarEvent, feed: CalendarEvent) -> bool:
    if operation == "delete":
        return False
    if operation == "create":
        return True
    return (feed.title, _instant(feed.starts_at), _instant(feed.ends_at)) == (
        local.title, _instant(local.starts_at), _instant(local.ends_at))


def _instant(value: str | None) -> datetime | None:
    if value is None:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def _validate_configuration(configuration: CalendarAccountConfiguration) -> None:
    if configuration.provider_type not in {"ics_file", "ics_subscription", "caldav"}:
        raise ValueError("Calendar provider type is invalid.")
    if not configuration.label.strip() or len(configuration.label) > 80:
        raise ValueError("Calendar connection name is invalid.")
    if configuration.provider_type == "ics_file" and configuration.endpoint is not None:
        raise ValueError("An imported calendar file cannot have a remote endpoint.")
    if configuration.provider_type != "ics_file" and not configuration.endpoint:
        raise ValueError("Calendar endpoint is required.")


def _account(row: sqlite3.Row | tuple[object, ...] | None) -> CalendarAccount | None:
    if row is None:
        return None
    return CalendarAccount(
        configuration=CalendarAccountConfiguration(
            account_id=str(row[0]), provider_type=str(row[1]), label=str(row[2]),
            endpoint=str(row[3]) if row[3] is not None else None,
            calendar_path=str(row[4]) if row[4] is not None else None,
        ),
        capabilities=CalendarCapabilities(
            can_create=bool(row[5]), can_update=bool(row[6]), can_delete=bool(row[7]),
        ),
        enabled=bool(row[8]), credential_present=bool(row[9]),
        next_sync_at=str(row[10]) if row[10] is not None else None,
        last_sync_at=str(row[11]) if row[11] is not None else None,
        last_sync_state=str(row[12]),
        last_sync_detail=str(row[13]) if row[13] is not None else None,
        created_at=str(row[14]), updated_at=str(row[15]),
    )


def _store_event(connection: sqlite3.Connection, event: CalendarEvent) -> None:
    connection.execute(
        """
        INSERT INTO calendar_events(
            event_id, account_id, provider_event_id, recurrence_id, title, starts_at,
            ends_at, timezone, all_day, location, calendar_name, organizer, description,
            status, editable, source_etag, synchronized_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(account_id, provider_event_id, recurrence_id) DO UPDATE SET
            event_id = excluded.event_id, title = excluded.title,
            starts_at = excluded.starts_at, ends_at = excluded.ends_at,
            timezone = excluded.timezone, all_day = excluded.all_day,
            location = excluded.location, calendar_name = excluded.calendar_name,
            organizer = excluded.organizer, description = excluded.description,
            status = excluded.status, editable = excluded.editable,
            source_etag = excluded.source_etag, synchronized_at = excluded.synchronized_at
        """,
        (
            event.event_id, event.account_id, event.provider_event_id, event.recurrence_id,
            event.title, event.starts_at, event.ends_at, event.timezone, int(event.all_day),
            event.location, event.calendar_name, event.organizer, event.description,
            event.status, int(event.editable), event.source_etag, event.synchronized_at,
        ),
    )


def _event(row: sqlite3.Row | tuple[object, ...]) -> CalendarEvent:
    return CalendarEvent(
        event_id=str(row[0]), account_id=str(row[1]), provider_event_id=str(row[2]),
        recurrence_id=str(row[3]), title=str(row[4]), starts_at=str(row[5]),
        ends_at=str(row[6]) if row[6] is not None else None, timezone=str(row[7]),
        all_day=bool(row[8]), location=str(row[9]) if row[9] is not None else None,
        calendar_name=str(row[10]) if row[10] is not None else None,
        organizer=str(row[11]) if row[11] is not None else None,
        description=str(row[12]) if row[12] is not None else None,
        status=str(row[13]), editable=bool(row[14]),
        source_etag=str(row[15]) if row[15] is not None else None,
        synchronized_at=str(row[16]),
    )
