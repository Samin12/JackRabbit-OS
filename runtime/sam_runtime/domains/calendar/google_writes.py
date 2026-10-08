"""Changes to the user's Google Calendar from Voice, through the Mac bridge.

The R1 reads Google Calendar from its secret iCal address: an ``ics_subscription`` account, read-only. When
the Mac bridge can write (the Composio CLI on the Mac), ``calendar_create_event``, ``calendar_update_event``
and ``calendar_delete_event`` aimed at that account (or at no account) change the Google calendar behind the
feed instead of failing. Each change is mirrored into the local store at once, so the Cards widget and
``calendar_list_upcoming`` show it right away, under the feed's own key (account, iCal UID
``<eventId>@google.com``, occurrence): the next iCal refresh updates that same row, never adds a second one.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import replace
from datetime import UTC, datetime, timedelta, tzinfo
import re
from urllib.parse import unquote, urlsplit

from sam_runtime.domains.calendar.google_bridge import CalendarWriteFailure, GoogleCalendarBridge
from sam_runtime.domains.calendar.models import CalendarAccount, CalendarEvent
from sam_runtime.domains.calendar.repository import CalendarRepository, calendar_event_key, local_write_tag
from sam_runtime.domains.heptabase_journal.localtime import DEFAULT_TIMEZONE, UnknownTimezone, resolve_zone
from sam_runtime.tools import ToolInvocationContext

GOOGLE_FEED_HOSTS = frozenset({"calendar.google.com", "www.google.com", "google.com"})
CALENDAR_NAME = "Google Calendar"
# How long the R1's own copy of a change wins over an iCal feed that does not show it yet.
LOCAL_WRITE_GRACE = timedelta(hours=6)
MAX_DURATION_MINUTES = 14 * 24 * 60
MAX_TITLE_CHARS = 300
ALL_DAY_REFUSED = ("All-day events can't be added or changed in Google Calendar from the R1 yet. "
                   "Give a start and an end time.")
_CALENDAR_ID = re.compile(r"^[A-Za-z0-9._%+\-#@]{1,254}$")
_DATE_ONLY = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def is_google_feed(account: CalendarAccount) -> bool:
    """A Google Calendar secret (or public) iCal address: ``https://calendar.google.com/calendar/ical/…``."""
    if account.configuration.provider_type != "ics_subscription":
        return False
    parts = urlsplit(account.configuration.endpoint or "")
    return (parts.hostname or "").lower() in GOOGLE_FEED_HOSTS and parts.path.startswith("/calendar/ical/")


def google_calendar_id(account: CalendarAccount) -> str | None:
    """The calendar the feed shows (``/calendar/ical/<calendar id>/private-…/basic.ics``), so a new event lands
    where the R1 reads; ``None`` lets the bridge use its own default (``primary``)."""
    parts = urlsplit(account.configuration.endpoint or "").path.split("/")
    if len(parts) >= 4 and parts[1] == "calendar" and parts[2] == "ical":
        value = unquote(parts[3])
        if _CALENDAR_ID.match(value):
            return value
    return None


# --------------------------------------------------------------------------- times


def instant(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=UTC)


def parse_moment(value: object, key: str, zone: tzinfo, *, allow_now: bool = True) -> datetime:
    """``now`` (this minute), an ISO time with an offset, or a local wall time in ``zone``; whole minutes."""
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} is required.")
    text = value.strip()
    if allow_now and text.lower() == "now":
        return datetime.now(zone).replace(second=0, microsecond=0)
    if _DATE_ONLY.match(text):
        raise ValueError(ALL_DAY_REFUSED)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00")) if "T" in text or " " in text else None
    except ValueError:
        parsed = None
    if parsed is None:
        raise ValueError(f"{key} must be a date and time such as 2026-10-08T10:30:00 (local time) or 'now'.")
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=zone)
    return parsed.astimezone(zone).replace(second=0, microsecond=0)


def duration(arguments: dict[str, object]) -> timedelta | None:
    value = arguments.get("durationMinutes")
    if value is None:
        return None
    if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= MAX_DURATION_MINUTES:
        raise ValueError("durationMinutes must be a whole number of minutes (1 to 20160).")
    return timedelta(minutes=value)


def clock(moment: datetime) -> str:
    return f"{moment.hour % 12 or 12}:{moment.minute:02d} {'AM' if moment.hour < 12 else 'PM'}"


def when_text(start: datetime, end: datetime | None) -> str:
    """``Thu Oct 8, 10:33–11:03 AM`` in the user's zone."""
    day = f"{start:%a %b} {start.day}"
    first = clock(start)
    if end is None:
        return f"{day}, {first}"
    last = clock(end)
    if end.date() != start.date():
        return f"{day}, {first} – {end:%a %b} {end.day}, {last}"
    if first[-2:] == last[-2:]:
        first = first[:-3]
    return f"{day}, {first}–{last}"


def local_fields(event: CalendarEvent, zone: tzinfo, now: datetime) -> dict[str, object]:
    """The event's times in the user's zone (all-day events keep their floating date) and how many minutes
    from now it starts (negative once it has started)."""
    start, end = instant(event.starts_at), instant(event.ends_at)
    if start is None:
        return {}
    if event.all_day:
        return {"date": event.starts_at[:10]}
    value: dict[str, object] = {"startsLocal": start.astimezone(zone).isoformat(timespec="minutes"),
                                "startsInMinutes": round((start - now).total_seconds() / 60)}
    if end is not None:
        value["endsLocal"] = end.astimezone(zone).isoformat(timespec="minutes")
    return value


def event_zone(timezone_name: Callable[[], str] | None, requested: object = None) -> tuple[str, tzinfo]:
    """The zone for an event: the one asked for when it is an IANA Area/Location name (Google refuses
    abbreviations such as EST), else the user's setting, else America/New_York."""
    if not (isinstance(requested, str) and ("/" in requested or requested.strip() == "UTC")):
        requested = None
    try:
        user = timezone_name() if timezone_name is not None else None
    except Exception:  # noqa: BLE001 - a settings read must never block a calendar change
        user = None
    for name in (requested, user, DEFAULT_TIMEZONE):
        if isinstance(name, str) and name.strip():
            try:
                return name.strip(), resolve_zone(name.strip())
            except UnknownTimezone:
                continue
    return DEFAULT_TIMEZONE, resolve_zone(DEFAULT_TIMEZONE)


# --------------------------------------------------------------------------- writes


class GoogleCalendarWrites:
    def __init__(self, repository: CalendarRepository, bridge: GoogleCalendarBridge,
                 timezone_name: Callable[[], str] | None = None) -> None:
        self._repository = repository
        self._bridge = bridge
        self._timezone_name = timezone_name or (lambda: DEFAULT_TIMEZONE)

    @property
    def bridge(self) -> GoogleCalendarBridge:
        return self._bridge

    def zone(self, requested: object = None) -> tuple[str, tzinfo]:
        return event_zone(self._timezone_name, requested)

    def ready(self) -> None:
        """Raise ``RuntimeError`` (an honest, speakable reason) unless the bridge can write right now."""
        try:
            self._bridge.require_available()
        except CalendarWriteFailure as failure:
            raise RuntimeError(_unavailable(failure)) from None

    # ------------------------------------------------------------------ create
    def create(self, account: CalendarAccount, context: ToolInvocationContext,
               arguments: dict[str, object]) -> dict[str, object]:
        _require_live_request(context)
        title = _title(arguments.get("title"), required=True)
        if arguments.get("allDay") is True:
            raise ValueError(ALL_DAY_REFUSED)
        zone_name, zone = self.zone(arguments.get("timezone"))
        start = parse_moment(arguments.get("startsAt"), "startsAt", zone)
        length = duration(arguments)
        if arguments.get("endsAt") not in (None, ""):
            end = parse_moment(arguments.get("endsAt"), "endsAt", zone, allow_now=False)
        elif length is not None:
            end = start + length
        else:
            raise ValueError("An event needs an end: give endsAt or durationMinutes (ask the user how long).")
        if end <= start:
            raise ValueError("The event must end after it starts.")
        body: dict[str, object] = {"title": title, "startsAt": start.isoformat(), "endsAt": end.isoformat(),
                                   "timezone": zone_name}
        _extras(body, arguments)
        calendar_id = google_calendar_id(account)
        if calendar_id:
            body["calendarId"] = calendar_id
        self.ready()
        try:
            value = self._bridge.create(body)
        except CalendarWriteFailure as failure:
            raise RuntimeError(_failed(f"add “{title}” to {CALENDAR_NAME}", failure)) from None
        event = value.get("event") if isinstance(value.get("event"), dict) else {}
        uid = event.get("iCalUID") if isinstance(event.get("iCalUID"), str) and event.get("iCalUID") else None
        if uid is None and isinstance(event.get("eventId"), str) and event["eventId"]:
            uid = str(event["eventId"]) + "@google.com"
        starts = instant(_text(event.get("startsAt"))) or start
        ends = instant(_text(event.get("endsAt"))) or end
        final_title = _text(event.get("title")) or title
        when = when_text(starts.astimezone(zone), ends.astimezone(zone))
        account_email = _text(value.get("account"))
        result: dict[str, object] = {
            "state": "completed", "operation": "create", "calendar": CALENDAR_NAME,
            "summary": f"Added “{final_title}” to {CALENDAR_NAME}: {when}.",
        }
        if account_email:
            result["account"] = account_email
        if uid is None:  # added, but without an id the R1 can't show it before the next refresh
            result["event"] = {"title": final_title, "startsLocal": starts.astimezone(zone).isoformat(timespec="minutes"),
                               "endsLocal": ends.astimezone(zone).isoformat(timespec="minutes")}
            return result
        now = datetime.now(UTC)
        mirror = CalendarEvent(
            event_id=calendar_event_key(account.configuration.account_id, uid, ""),
            account_id=account.configuration.account_id, provider_event_id=uid, recurrence_id="", title=final_title,
            starts_at=starts.astimezone(UTC).isoformat(), ends_at=ends.astimezone(UTC).isoformat(), timezone="UTC",
            all_day=False, location=_text(body.get("location")), calendar_name=account.configuration.label,
            organizer=account_email, description=_text(body.get("description")), status="confirmed", editable=True,
            source_etag=local_write_tag("create", now), synchronized_at=now.isoformat(),
        )
        self._mirror(mirror)
        result["event"] = self.view(mirror, zone, now)
        return result

    # ------------------------------------------------------------------ update
    def update(self, account: CalendarAccount, event: CalendarEvent, context: ToolInvocationContext,
               arguments: dict[str, object]) -> dict[str, object]:
        _require_live_request(context)
        if event.status == "cancelled":
            raise ValueError("Calendar event was not found.")
        if event.all_day or arguments.get("allDay") is True:
            raise ValueError(ALL_DAY_REFUSED)
        zone_name, zone = self.zone(arguments.get("timezone"))
        old_start = instant(event.starts_at)
        if old_start is None:
            raise ValueError("Calendar event was not found.")
        old_end = instant(event.ends_at)
        start = parse_moment(arguments["startsAt"], "startsAt", zone) if arguments.get("startsAt") else None
        length = duration(arguments)
        end: datetime | None = None
        if arguments.get("endsAt"):
            end = parse_moment(arguments["endsAt"], "endsAt", zone, allow_now=False)
        elif length is not None:
            end = (start or old_start) + length
        elif start is not None and old_end is not None:
            end = start + (old_end - old_start)  # a move keeps the event's length
        final_start, final_end = start or old_start, end or old_end
        if (start is not None or end is not None) and final_end is not None and final_end <= final_start:
            raise ValueError("The event must end after it starts.")
        title = _title(arguments.get("title"))
        body: dict[str, object] = {"iCalUID": event.provider_event_id, "timezone": zone_name}
        if event.recurrence_id:
            body["recurrenceId"] = event.recurrence_id  # that one occurrence, never the series
        if start is not None:
            body["startsAt"] = start.isoformat()
        if end is not None:
            body["endsAt"] = end.isoformat()
        if title:
            body["title"] = title
        _extras(body, arguments)
        if not any(key in body for key in ("startsAt", "endsAt", "title", "description", "location")):
            raise ValueError("Say what to change: a new start, end, length, title, description or location.")
        calendar_id = google_calendar_id(account)
        if calendar_id:
            body["calendarId"] = calendar_id
        self.ready()
        try:
            self._bridge.update(body)
        except CalendarWriteFailure as failure:
            raise RuntimeError(_failed(f"change “{event.title}” in {CALENDAR_NAME}", failure)) from None
        now = datetime.now(UTC)
        mirror = replace(
            event, title=title or event.title,
            starts_at=final_start.astimezone(UTC).isoformat(),
            ends_at=final_end.astimezone(UTC).isoformat() if final_end is not None else None,
            location=_text(body.get("location")) or event.location,
            description=_text(body.get("description")) or event.description,
            editable=True, source_etag=local_write_tag("update", now), synchronized_at=now.isoformat(),
        )
        self._mirror(mirror)
        when = _event_when(mirror, zone)
        verb = "Moved" if start is not None or end is not None else "Updated"
        scope = " (only this occurrence)" if event.recurrence_id else ""
        summary = (f"Moved “{mirror.title}” to {when} in {CALENDAR_NAME}{scope}." if verb == "Moved"
                   else f"Updated “{mirror.title}” ({when}) in {CALENDAR_NAME}{scope}.")
        return {"state": "completed", "operation": "update", "calendar": CALENDAR_NAME, "summary": summary,
                "event": self.view(mirror, zone, now),
                "previous": {"title": event.title, **local_fields(event, zone, now)}}

    # ------------------------------------------------------------------ delete (after the user's yes)
    def review_delete(self, event: CalendarEvent) -> dict[str, object]:
        """What the user is asked to approve (``calendar_confirm_action`` does the delete)."""
        if event.status == "cancelled":
            raise ValueError("Calendar event was not found.")
        _, zone = self.zone()
        when = _event_when(event, zone)
        scope = " (only this occurrence)" if event.recurrence_id else ""
        return {"calendar": CALENDAR_NAME, "event": self.view(event, zone, datetime.now(UTC)),
                "summary": f"Delete “{event.title}” ({when}) from {CALENDAR_NAME}{scope}? Ask the user; call "
                           "calendar_confirm_action only after they say yes."}

    def delete(self, account: CalendarAccount, event: CalendarEvent) -> dict[str, object]:
        body: dict[str, object] = {"iCalUID": event.provider_event_id}
        if event.recurrence_id:
            body["recurrenceId"] = event.recurrence_id
        calendar_id = google_calendar_id(account)
        if calendar_id:
            body["calendarId"] = calendar_id
        self.ready()
        try:
            self._bridge.delete(body)
        except CalendarWriteFailure as failure:
            raise RuntimeError(_failed(f"delete “{event.title}” from {CALENDAR_NAME}", failure)) from None
        now = datetime.now(UTC)
        # Hidden at once (lists skip cancelled rows); the row goes when the feed no longer has the event.
        self._mirror(replace(event, status="cancelled", source_etag=local_write_tag("delete", now),
                             synchronized_at=now.isoformat()))
        _, zone = self.zone()
        when = _event_when(event, zone)
        scope = " (only this occurrence)" if event.recurrence_id else ""
        return {"operation": "delete", "calendar": CALENDAR_NAME,
                "summary": f"Deleted “{event.title}” ({when}) from {CALENDAR_NAME}{scope}.",
                "event": {"title": event.title, **local_fields(event, zone, now)}}

    # ------------------------------------------------------------------ views
    def view(self, event: CalendarEvent, zone: tzinfo, now: datetime) -> dict[str, object]:
        return {"eventId": event.event_id, "calendarAccountId": event.account_id, "title": event.title,
                "startsAt": event.starts_at, "endsAt": event.ends_at, **local_fields(event, zone, now),
                "allDay": event.all_day, "location": event.location, "calendar": event.calendar_name,
                "editable": True}

    def _mirror(self, event: CalendarEvent) -> None:
        try:
            self._repository.store_local_write(event)
        except Exception:  # noqa: BLE001 - Google already has the change; the next feed refresh brings it
            pass


def _event_when(event: CalendarEvent, zone: tzinfo) -> str:
    start, end = instant(event.starts_at), instant(event.ends_at)
    if event.all_day or start is None:
        return f"all day {event.starts_at[:10]}"
    return when_text(start.astimezone(zone), end.astimezone(zone) if end else None)


def _require_live_request(context: ToolInvocationContext) -> None:
    if not context.voice_session_id or not context.tool_call_id:
        raise ValueError("A trusted agent invocation is required for Calendar changes.")


def _title(value: object, *, required: bool = False) -> str | None:
    if value is None or (isinstance(value, str) and not value.strip()):
        if required:
            raise ValueError("title is required.")
        return None
    if not isinstance(value, str):
        raise ValueError("title must be text.")
    text = " ".join(value.split())
    if len(text) > MAX_TITLE_CHARS:
        raise ValueError("title is too long.")
    return text


def _text(value: object) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _extras(body: dict[str, object], arguments: dict[str, object]) -> None:
    for key, limit in (("description", 8000), ("location", 500)):
        value = _text(arguments.get(key))
        if value:
            body[key] = value[:limit]


def _unavailable(failure: CalendarWriteFailure) -> str:
    fix = f" To fix it: {failure.fix}" if failure.fix else ""
    return f"{CALENDAR_NAME} can't be changed from the R1 right now: {failure.reason}. Nothing was changed.{fix}"


def _failed(action: str, failure: CalendarWriteFailure) -> str:
    fix = f" To fix it: {failure.fix}" if failure.fix else ""
    if failure.written == "unknown":
        return (f"I tried to {action} but got no confirmation ({failure.reason}), so it may or may not have "
                f"happened. Check {CALENDAR_NAME} before trying again.{fix}")
    return f"I couldn't {action}: {failure.reason}. Nothing was changed.{fix}"
