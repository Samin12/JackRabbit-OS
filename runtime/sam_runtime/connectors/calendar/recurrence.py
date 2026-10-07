"""iCalendar time zones and recurrence (an RFC 5545 RRULE subset) for ICS feeds.

Google, iCloud and Outlook feeds write most events as ``DTSTART;TZID=<zone>:<local time>``
and repeat them with ``RRULE`` masters plus ``RECURRENCE-ID`` overrides and ``EXDATE``s.
This module resolves the zones (IANA names through ``zoneinfo``, which the Android build gets
from the bundled ``tzdata`` wheel, plus the common Windows names) and expands DAILY, WEEKLY,
MONTHLY and YEARLY rules with INTERVAL, COUNT, UNTIL, BYDAY (with ordinals for monthly and
yearly rules), BYMONTHDAY and BYMONTH. Rules are expanded in wall-clock time, so a 7:00 event
stays at 7:00 across daylight-saving changes. Unsupported rules (BYSETPOS, BYWEEKNO, sub-daily
frequencies) return ``None`` and the caller keeps the first occurrence only, as before.
"""

from __future__ import annotations

import calendar as _calendar
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta, tzinfo
from functools import lru_cache

_WEEKDAYS = {"MO": 0, "TU": 1, "WE": 2, "TH": 3, "FR": 4, "SA": 5, "SU": 6}
_FREQUENCIES = frozenset({"DAILY", "WEEKLY", "MONTHLY", "YEARLY"})
_UNSUPPORTED = frozenset({"BYSETPOS", "BYWEEKNO", "BYYEARDAY", "BYHOUR", "BYMINUTE", "BYSECOND"})
# Guards against hostile or broken feeds; a daily rule from 1990 is ~13k periods.
_MAX_PERIODS = 40_000
_MAX_OCCURRENCES = 2_000

# Outlook / Exchange feeds use Windows zone names.
_WINDOWS_ZONES = {
    "eastern standard time": "America/New_York",
    "us eastern standard time": "America/Indiana/Indianapolis",
    "central standard time": "America/Chicago",
    "mountain standard time": "America/Denver",
    "us mountain standard time": "America/Phoenix",
    "pacific standard time": "America/Los_Angeles",
    "alaskan standard time": "America/Anchorage",
    "hawaiian standard time": "Pacific/Honolulu",
    "atlantic standard time": "America/Halifax",
    "gmt standard time": "Europe/London",
    "greenwich standard time": "Atlantic/Reykjavik",
    "w. europe standard time": "Europe/Berlin",
    "romance standard time": "Europe/Paris",
    "central europe standard time": "Europe/Budapest",
    "central european standard time": "Europe/Warsaw",
    "e. europe standard time": "Europe/Chisinau",
    "fle standard time": "Europe/Kiev",
    "india standard time": "Asia/Kolkata",
    "china standard time": "Asia/Shanghai",
    "tokyo standard time": "Asia/Tokyo",
    "aus eastern standard time": "Australia/Sydney",
    "utc": "UTC",
}


@lru_cache(maxsize=64)
def resolve_zone(name: str | None) -> tzinfo | None:
    """tzinfo for an iCalendar TZID, or None when it cannot be resolved."""
    value = (name or "").strip().strip('"')
    if not value:
        return None
    if value.upper() in {"UTC", "GMT", "Z", "ETC/UTC", "ETC/GMT"}:
        return UTC
    candidates = [value, _WINDOWS_ZONES.get(value.casefold(), "")]
    # "/mozilla.org/20050126_1/America/New_York", "/citadel.org/.../America/New_York"
    parts = [part for part in value.split("/") if part]
    if len(parts) >= 2:
        candidates.append("/".join(parts[-2:]))
        if len(parts) >= 3:
            candidates.append("/".join(parts[-3:]))
    for candidate in candidates:
        if not candidate:
            continue
        try:
            from zoneinfo import ZoneInfo

            return ZoneInfo(candidate)
        except Exception:  # ZoneInfoNotFoundError, ValueError, OSError, ImportError
            continue
    return None


@dataclass(frozen=True, slots=True)
class Rule:
    freq: str
    interval: int
    count: int | None
    until: datetime | date | None
    by_day: tuple[tuple[int, int], ...]
    by_month_day: tuple[int, ...]
    by_month: tuple[int, ...]


def parse_rule(value: str, *, zone: tzinfo | None, all_day: bool) -> Rule | None:
    """Parse an RRULE value; None when it is malformed or uses parts this module does not expand."""
    parts: dict[str, str] = {}
    for item in value.strip().split(";"):
        key, _, part = item.partition("=")
        if key.strip():
            parts[key.strip().upper()] = part.strip()
    freq = parts.get("FREQ", "").upper()
    if freq not in _FREQUENCIES or any(key in parts for key in _UNSUPPORTED):
        return None
    try:
        interval = max(1, int(parts.get("INTERVAL", "1")))
        count = int(parts["COUNT"]) if "COUNT" in parts else None
        by_day = tuple(_weekday(item) for item in _items(parts.get("BYDAY")))
        by_month_day = tuple(int(item) for item in _items(parts.get("BYMONTHDAY")))
        by_month = tuple(int(item) for item in _items(parts.get("BYMONTH")))
        until = _until(parts.get("UNTIL"), zone=zone, all_day=all_day)
    except (ValueError, KeyError):
        return None
    if count is not None and count < 1:
        return None
    if any(day == 0 or abs(day) > 31 for day in by_month_day) or any(not 1 <= month <= 12 for month in by_month):
        return None
    if freq == "YEARLY" and by_day and not by_month:
        return None  # ordinals within the whole year ("20MO") are not expanded
    return Rule(freq, interval, count, until, by_day, by_month_day, by_month)


def expand(
    start: datetime,
    rule: Rule,
    *,
    all_day: bool,
    window_start: datetime,
    window_end: datetime,
    open_ended_limit: int | None = None,
) -> list[datetime]:
    """Occurrence starts of ``rule`` that begin in [window_start - one day, window_end].

    ``start`` is the master DTSTART as an aware datetime (all-day: UTC midnight of its date).
    Results keep ``start``'s tzinfo and wall-clock time; all-day results are UTC midnights.
    Occurrences are counted from DTSTART, so COUNT holds even when the window starts later.
    ``open_ended_limit`` caps rules without COUNT or UNTIL the way the source does (Google
    Calendar stops a never-ending series after 730 occurrences, two years of a daily event).
    """
    zone = start.tzinfo or UTC
    local = start.replace(tzinfo=None)
    first_day = local.date()
    clock = local.time()
    low = window_start - timedelta(days=1)
    results: list[datetime] = []
    produced = 0
    limit = rule.count
    if limit is None and rule.until is None and open_ended_limit is not None:
        limit = max(1, open_ended_limit)
    skip = 0 if limit is not None else _skip_periods(rule, first_day, low.astimezone(zone).date())
    for index in range(skip, skip + _MAX_PERIODS):
        period = _period_days(rule, first_day, index)
        if period is None:
            break
        if not period:
            if _period_start(rule, first_day, index) > window_end.astimezone(zone).date() + timedelta(days=1):
                break
            continue
        for day in period:
            if day < first_day:
                continue
            moment = _moment(day, clock, zone, all_day)
            if not _before_until(day, moment, rule.until, all_day):
                return results
            produced += 1
            if limit is not None and produced > limit:
                return results
            if moment > window_end:
                return results
            if moment >= low:
                results.append(moment)
                if len(results) >= _MAX_OCCURRENCES:
                    return results
    return results


def occurrence_key(moment: datetime, *, all_day: bool) -> str:
    """Stable RECURRENCE-ID form: ``YYYYMMDD`` (all-day) or UTC ``YYYYMMDDTHHMMSSZ``."""
    if all_day:
        return moment.strftime("%Y%m%d")
    return moment.astimezone(UTC).strftime("%Y%m%dT%H%M%SZ")


# ------------------------------------------------------------------ internals


def _items(value: str | None) -> list[str]:
    return [item.strip() for item in (value or "").split(",") if item.strip()]


def _weekday(value: str) -> tuple[int, int]:
    text = value.strip().upper()
    code = text[-2:]
    if code not in _WEEKDAYS:
        raise ValueError(value)
    ordinal = int(text[:-2]) if text[:-2] not in {"", "+"} else 0
    if abs(ordinal) > 53:
        raise ValueError(value)
    return ordinal, _WEEKDAYS[code]


def _until(value: str | None, *, zone: tzinfo | None, all_day: bool) -> datetime | date | None:
    if not value:
        return None
    text = value.strip()
    if len(text) == 8:
        return datetime.strptime(text, "%Y%m%d").date()
    if text.endswith("Z"):
        return datetime.strptime(text, "%Y%m%dT%H%M%SZ").replace(tzinfo=UTC)
    parsed = datetime.strptime(text, "%Y%m%dT%H%M%S")
    return parsed.date() if all_day else parsed.replace(tzinfo=zone or UTC)


def _before_until(day: date, moment: datetime, until: datetime | date | None, all_day: bool) -> bool:
    if until is None:
        return True
    if isinstance(until, datetime):
        return (moment if not all_day else datetime.combine(day, datetime.min.time(), tzinfo=UTC)) <= until
    return day <= until


def _moment(day: date, clock, zone: tzinfo, all_day: bool) -> datetime:
    if all_day:
        return datetime.combine(day, datetime.min.time(), tzinfo=UTC)
    return datetime.combine(day, clock).replace(tzinfo=zone)


def _add_months(day: date, months: int) -> tuple[int, int]:
    total = day.year * 12 + (day.month - 1) + months
    return total // 12, total % 12 + 1


def _period_start(rule: Rule, first: date, index: int) -> date:
    if rule.freq == "DAILY":
        return first + timedelta(days=index * rule.interval)
    if rule.freq == "WEEKLY":
        return first - timedelta(days=first.weekday()) + timedelta(weeks=index * rule.interval)
    if rule.freq == "MONTHLY":
        year, month = _add_months(first, index * rule.interval)
        return date(year, month, 1)
    return date(first.year + index * rule.interval, 1, 1)


def _skip_periods(rule: Rule, first: date, low: date) -> int:
    """Whole periods that end before ``low`` (only used without COUNT)."""
    if low <= first:
        return 0
    if rule.freq == "DAILY":
        span = (low - first).days // rule.interval
    elif rule.freq == "WEEKLY":
        span = (low - first).days // (7 * rule.interval)
    elif rule.freq == "MONTHLY":
        span = ((low.year - first.year) * 12 + low.month - first.month) // rule.interval
    else:
        span = (low.year - first.year) // rule.interval
    return max(0, span - 1)


def _period_days(rule: Rule, first: date, index: int) -> list[date] | None:
    """Candidate days of period ``index`` in order; None past the supported calendar range."""
    try:
        start = _period_start(rule, first, index)
    except (OverflowError, ValueError):
        return None
    if start.year > 9000:
        return None
    if rule.freq == "DAILY":
        days = [start]
    elif rule.freq == "WEEKLY":
        weekdays = sorted({weekday for _, weekday in rule.by_day}) or [first.weekday()]
        days = [start + timedelta(days=weekday) for weekday in weekdays]
    elif rule.freq == "MONTHLY":
        days = _month_days(rule, first, start.year, start.month)
    else:
        months = rule.by_month or (first.month,)
        days = []
        for month in sorted(months):
            if rule.by_day or rule.by_month_day or rule.by_month:
                days.extend(_month_days(rule, first, start.year, month, yearly=True))
            else:
                days.extend(_valid(start.year, month, (first.day,)))
    return [day for day in sorted(set(days)) if _matches(rule, day)]


def _month_days(rule: Rule, first: date, year: int, month: int, *, yearly: bool = False) -> list[date]:
    if rule.by_day:
        last = _calendar.monthrange(year, month)[1]
        days: list[date] = []
        for ordinal, weekday in rule.by_day:
            matches = [date(year, month, day) for day in range(1, last + 1) if date(year, month, day).weekday() == weekday]
            if ordinal == 0:
                days.extend(matches)
            elif 0 < ordinal <= len(matches):
                days.append(matches[ordinal - 1])
            elif 0 < -ordinal <= len(matches):
                days.append(matches[ordinal])
        return days
    if rule.by_month_day:
        return _valid(year, month, rule.by_month_day)
    return _valid(year, month, (first.day,))


def _valid(year: int, month: int, month_days: tuple[int, ...]) -> list[date]:
    last = _calendar.monthrange(year, month)[1]
    days = []
    for value in month_days:
        day = value if value > 0 else last + 1 + value
        if 1 <= day <= last:
            days.append(date(year, month, day))
    return days


def _matches(rule: Rule, day: date) -> bool:
    if rule.by_month and day.month not in rule.by_month:
        return False
    if rule.freq == "DAILY":
        if rule.by_day and day.weekday() not in {weekday for _, weekday in rule.by_day}:
            return False
        if rule.by_month_day and day not in _valid(day.year, day.month, rule.by_month_day):
            return False
    return True
