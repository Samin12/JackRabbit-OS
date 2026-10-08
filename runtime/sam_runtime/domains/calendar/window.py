"""Time windows for ``calendar_list_upcoming``: "the next 30 minutes", "this afternoon", "until 3".

Without a window the tool lists the next events and the model had to pick the ones in range itself, which it got
wrong (events at noon read out as "in the next 30 minutes" at 10:33). With ``withinMinutes`` or ``from``/``to`` the
runtime does the filtering with its own clock: the answer holds only events that overlap the window, each with
its local time, a speakable label for the window, and the first event after it, clearly marked as later.

All-day events (a birthday, an offsite) are not "in the next 30 minutes": they count as inside a window only when
at least ``ALL_DAY_MIN_WINDOW`` (6 hours) of the window falls on their local day, as for "today" or "this
afternoon". Otherwise the ones the window touches come separately as ``allDayToday``.

Read-only: it uses ``CalendarRepository.upcoming_events`` and nothing else.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime, time, timedelta, tzinfo

from sam_runtime.domains.heptabase_journal.localtime import DEFAULT_TIMEZONE, resolve_zone

from .google_writes import is_now, whole_number
from .models import CalendarEvent
from .repository import CalendarRepository

WINDOW_KEYS = ("withinMinutes", "from", "to")
MAX_WINDOW = timedelta(days=7)
ALL_DAY_MIN_WINDOW = timedelta(hours=6)
# All-day events are stored as UTC midnight of their floating date; keep them that much longer so a zone
# behind UTC still sees "today" (the same grace the board widgets use).
_ALL_DAY_GRACE_HOURS = 14
_SCAN_LIMIT = 200
_WEEKDAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


def requested(arguments: dict[str, object]) -> bool:
    """True when the call asks for a time window (otherwise the plain upcoming list is kept as it was)."""
    return any(arguments.get(key) is not None for key in WINDOW_KEYS)


def list_window(
    repository: CalendarRepository,
    arguments: dict[str, object],
    *,
    now: datetime,
    limit: int,
    view: Callable[[CalendarEvent], dict[str, object]],
    default_timezone: str = DEFAULT_TIMEZONE,
) -> dict[str, object]:
    zone_name, zone = window_zone(arguments, default_timezone)
    start, end = _bounds(arguments, now.astimezone(UTC), zone)
    candidates = repository.upcoming_events(start.isoformat(), limit=_SCAN_LIMIT,
                                            all_day_grace_hours=_ALL_DAY_GRACE_HOURS)
    inside: list[dict[str, object]] = []
    all_day_beside: list[dict[str, object]] = []  # all-day events of the day(s) a short window falls on
    later: CalendarEvent | None = None
    for event in candidates:
        bounds = _event_bounds(event, zone)
        if bounds is None:
            continue
        event_start, event_end = bounds
        if _overlaps(event_start, event_end, start, end):
            target = inside
            if event.all_day and min(end, event_end) - max(start, event_start) < ALL_DAY_MIN_WINDOW:
                target = all_day_beside  # a short slice of that day (or a window crossing midnight into it)
            if len(target) < limit:
                item = view(event)
                item.update(_local_fields(event, event_start, event_end, start, zone))
                target.append(item)
        elif event_start >= end and later is None and not event.all_day:
            later = event
    label = _window_label(start, end, zone)
    result: dict[str, object] = {
        "window": {"from": _local_iso(start, zone), "to": _local_iso(end, zone), "timezone": zone_name,
                   "label": label},
        "now": _local_iso(now, zone),
        "count": len(inside),
        "events": inside,
    }
    if later is not None:
        later_bounds = _event_bounds(later, zone)
        result["nextAfterWindow"] = {"title": later.title, "startsAt": later.starts_at,
                                     "localStart": _day_clock(later_bounds[0], zone) if later_bounds else None}
    if inside:
        result["note"] = (f"Only these events are on the calendar {label}; nothing else is in this window. "
                          "nextAfterWindow, if present, is after the window: never present it as inside it.")
    else:
        result["note"] = (f"Nothing is on the calendar {label}. Say so. nextAfterWindow, if present, is after "
                          "this window: you may mention it as later, never as inside the window.")
    if all_day_beside:
        result["allDayToday"] = all_day_beside
        result["note"] += (" allDayToday lists all-day events of that day: they are not at a time in this window, "
                           "so do not count them as in it; mention them only as all day, if at all.")
    return result


def window_zone(arguments: dict[str, object], default: str = DEFAULT_TIMEZONE) -> tuple[str, tzinfo]:
    """The zone the window is read in: ``timezone`` when given, else ``default`` (the user's setting)."""
    name = _zone_name(arguments.get("timezone"), default)
    return name, resolve_zone(name)  # UnknownTimezone is a ValueError


def _zone_name(value: object, default: str = DEFAULT_TIMEZONE) -> str:
    if value is None:
        return default
    if not isinstance(value, str) or not value.strip():
        raise ValueError("timezone must be an IANA name such as America/New_York.")
    return value.strip()


def _bounds(arguments: dict[str, object], now: datetime, zone: tzinfo) -> tuple[datetime, datetime]:
    minutes = arguments.get("withinMinutes")
    if minutes is not None:
        minutes = whole_number(minutes)
        if minutes is None or minutes < 1:
            raise ValueError("withinMinutes must be a whole number of minutes.")
    start = _instant(arguments.get("from"), "from", zone, now) or now
    if minutes is not None and arguments.get("to") is not None:
        raise ValueError("Pass withinMinutes or to, not both.")
    if minutes is not None:
        end = start + timedelta(minutes=int(minutes))
    else:
        end = _instant(arguments.get("to"), "to", zone, now)
        if end is None:
            raise ValueError("A window needs withinMinutes or to (for example withinMinutes 30).")
    if end <= start:
        raise ValueError("The window must end after it starts.")
    if end - start > MAX_WINDOW:
        raise ValueError("A window can be at most 7 days.")
    return start, end


def _instant(value: object, key: str, zone: tzinfo, now: datetime) -> datetime | None:
    """``now`` (any case), or an ISO 8601 date or date-time; without a UTC offset it is local time in ``zone``."""
    if value is None:
        return None
    if is_now(value):
        return now
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{key} must be an ISO 8601 date-time, e.g. 2026-10-08T12:00:00-04:00.")
    text = value.strip().replace("Z", "+00:00").replace("z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        raise ValueError(f"{key} must be an ISO 8601 date-time, e.g. 2026-10-08T12:00:00-04:00.") from None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=zone)
    return parsed.astimezone(UTC)


def _parse(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else parsed.replace(tzinfo=UTC)


def _event_bounds(event: CalendarEvent, zone: tzinfo) -> tuple[datetime, datetime] | None:
    start = _parse(event.starts_at)
    if start is None:
        return None
    end = _parse(event.ends_at)
    if event.all_day:
        # A floating date: midnight to midnight in the user's zone, not in UTC.
        first = datetime.combine(start.astimezone(UTC).date(), time(0), tzinfo=zone)
        last_day = end.astimezone(UTC).date() if end is not None else start.astimezone(UTC).date() + timedelta(days=1)
        last = datetime.combine(last_day, time(0), tzinfo=zone)
        return first.astimezone(UTC), max(last, first + timedelta(hours=1)).astimezone(UTC)
    return start.astimezone(UTC), (end or start).astimezone(UTC)


def _overlaps(event_start: datetime, event_end: datetime, start: datetime, end: datetime) -> bool:
    if event_end <= event_start:  # a point in time
        return start <= event_start < end
    return event_start < end and event_end > start


def _local_fields(event: CalendarEvent, event_start: datetime, event_end: datetime, start: datetime,
                  zone: tzinfo) -> dict[str, object]:
    if event.all_day:
        return {"localStart": _day(event_start.astimezone(zone)) + ", all day"}
    fields: dict[str, object] = {"localStart": _day_clock(event_start, zone)}
    if event_end > event_start:
        fields["localEnd"] = _clock(event_end.astimezone(zone))
    if event_start < start:
        fields["inProgress"] = True
    return fields


def _local_iso(value: datetime, zone: tzinfo) -> str:
    return value.astimezone(zone).replace(second=0, microsecond=0).isoformat(timespec="minutes")


def _clock(value: datetime) -> str:
    hour = value.hour % 12 or 12
    return f"{hour}:{value.minute:02d} {'AM' if value.hour < 12 else 'PM'}"


def _day(value: datetime) -> str:
    return f"{_WEEKDAYS[value.weekday()]} {_MONTHS[value.month - 1]} {value.day}"


def _day_clock(value: datetime, zone: tzinfo) -> str:
    local = value.astimezone(zone)
    return f"{_day(local)}, {_clock(local)}"


def _window_label(start: datetime, end: datetime, zone: tzinfo) -> str:
    first, last = start.astimezone(zone), end.astimezone(zone)
    if first.date() == last.date():
        return f"from {_clock(first)} to {_clock(last)} on {_day(first)}"
    return f"from {_day_clock(start, zone)} to {_day_clock(end, zone)}"
