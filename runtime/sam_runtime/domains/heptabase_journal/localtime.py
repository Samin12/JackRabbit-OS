"""User-local dates and clock labels for journal entries.

The R1 runs in GMT while the user lives in America/New_York, so every journal
date is computed in the user's configured zone at *event* time. ``zoneinfo``
needs tz data (the ``tzdata`` wheel on Android). If it is missing we fall back
to a fixed US Eastern rule (EST/EDT, rules in force since 2007) for the Eastern
zone names, and refuse other names instead of silently writing wrong dates.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta, timezone, tzinfo

DEFAULT_TIMEZONE = "America/New_York"
_EASTERN_NAMES = frozenset({"America/New_York", "US/Eastern", "EST5EDT", "America/Detroit"})
_UTC_NAMES = frozenset({"UTC", "Etc/UTC", "GMT", "Etc/GMT", "Z"})


class UnknownTimezone(ValueError):
    pass


def _zoneinfo(name: str) -> tzinfo:
    from zoneinfo import ZoneInfo

    return ZoneInfo(name)


# Indirection so tests can simulate an Android build without tz data.
zoneinfo_factory = _zoneinfo


def _second_sunday_of_march(year: int) -> date:
    first = date(year, 3, 1)
    return first + timedelta(days=(6 - first.weekday()) % 7 + 7)


def _first_sunday_of_november(year: int) -> date:
    first = date(year, 11, 1)
    return first + timedelta(days=(6 - first.weekday()) % 7)


class UsEasternRule(tzinfo):
    """America/New_York without tz data: DST from the second Sunday of March 02:00
    EST to the first Sunday of November 02:00 EDT (Energy Policy Act of 2005)."""

    _STD = timedelta(hours=-5)
    _DST = timedelta(hours=-4)

    def _utc_bounds(self, year: int) -> tuple[datetime, datetime]:
        start = datetime.combine(_second_sunday_of_march(year), datetime.min.time()) + timedelta(hours=7)
        end = datetime.combine(_first_sunday_of_november(year), datetime.min.time()) + timedelta(hours=6)
        return start, end

    def fromutc(self, dt: datetime) -> datetime:
        naive = dt.replace(tzinfo=None)
        start, end = self._utc_bounds(naive.year)
        if start <= naive < end:
            return (naive + self._DST).replace(tzinfo=self, fold=0)
        fold = 1 if end <= naive < end + timedelta(hours=1) else 0
        return (naive + self._STD).replace(tzinfo=self, fold=fold)

    def utcoffset(self, dt: datetime | None) -> timedelta:
        if dt is None:
            return self._STD
        local = dt.replace(tzinfo=None)
        dst_start = datetime.combine(_second_sunday_of_march(local.year), datetime.min.time()) + timedelta(hours=2)
        dst_end = datetime.combine(_first_sunday_of_november(local.year), datetime.min.time()) + timedelta(hours=1)
        if dst_start + timedelta(hours=1) <= local < dst_end:
            return self._DST
        if dst_start <= local < dst_start + timedelta(hours=1):  # spring-forward gap (PEP 495)
            return self._DST if dt.fold else self._STD
        if dst_end <= local < dst_end + timedelta(hours=1):  # repeated hour
            return self._STD if dt.fold else self._DST
        return self._STD

    def dst(self, dt: datetime | None) -> timedelta:
        return self.utcoffset(dt) - self._STD

    def tzname(self, dt: datetime | None) -> str:
        return "EDT" if self.dst(dt) else "EST"

    def __repr__(self) -> str:
        return "UsEasternRule()"


US_EASTERN_FALLBACK = UsEasternRule()


def resolve_zone(name: str | None) -> tzinfo:
    """Return a tzinfo for ``name`` or raise ``UnknownTimezone``."""
    value = (name or DEFAULT_TIMEZONE).strip()
    if not value or len(value) > 64:
        raise UnknownTimezone("Timezone is invalid.")
    if value in _UTC_NAMES:
        return timezone.utc
    try:
        return zoneinfo_factory(value)
    except Exception:  # ZoneInfoNotFoundError, ValueError, OSError, ImportError
        if value in _EASTERN_NAMES:
            return US_EASTERN_FALLBACK
        raise UnknownTimezone(f"Unknown timezone: {value}.") from None


def zone_source(name: str | None) -> str:
    """'tzdata' when real zone data resolved the name, 'fallback' for the fixed rule."""
    try:
        zone = resolve_zone(name)
    except UnknownTimezone:
        return "unknown"
    return "fallback" if zone is US_EASTERN_FALLBACK else "tzdata"


def local_datetime(epoch_seconds: float, zone: tzinfo) -> datetime:
    return datetime.fromtimestamp(float(epoch_seconds), UTC).astimezone(zone)


def journal_date(epoch_seconds: float, zone: tzinfo) -> str:
    return local_datetime(epoch_seconds, zone).date().isoformat()


def clock_label(epoch_seconds: float, zone: tzinfo) -> str:
    return local_datetime(epoch_seconds, zone).strftime("%H:%M")


def iso_utc(epoch_seconds: float) -> str:
    return datetime.fromtimestamp(float(epoch_seconds), UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_iso_epoch(value: object) -> float | None:
    """Parse runtime ISO timestamps ('...Z' or '+00:00') into epoch seconds."""
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.timestamp()
