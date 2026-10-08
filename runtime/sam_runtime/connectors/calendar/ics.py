from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from email.utils import parseaddr
import re
from typing import Iterable

import httpx

from sam_runtime.security.outbound import assert_redirect_safe, validate_public_url

from .recurrence import expand, occurrence_key, parse_rule, resolve_zone

# How far ahead recurring events are expanded (single events keep the caller's range).
RECURRENCE_HORIZON = timedelta(days=92)
# Google Calendar ends a series without COUNT/UNTIL after this many occurrences (its own UI and
# API stop there, while its ICS feed still says "repeat forever").
GOOGLE_OPEN_ENDED_LIMIT = 730


@dataclass(slots=True, frozen=True)
class IcsCalendarCredentials:
    feed_url: str


@dataclass(slots=True, frozen=True)
class IcsCalendarEvent:
    provider_event_id: str
    title: str
    starts_at: datetime
    ends_at: datetime | None
    all_day: bool
    recurrence_id: str = ""
    status: str = "confirmed"
    description: str | None = None
    location: str | None = None
    organizer: str | None = None
    calendar_label: str | None = None


@dataclass(slots=True, frozen=True)
class _Property:
    name: str
    params: dict[str, str] = field(default_factory=dict)
    value: str = ""


class IcsCalendarProviderClient:
    def parse(
        self,
        body: str,
        *,
        expand_from: datetime | None = None,
        expand_to: datetime | None = None,
    ) -> list[IcsCalendarEvent]:
        if "BEGIN:VCALENDAR" not in body:
            raise ValueError("The provided file is not valid iCalendar content.")
        return list(self._parse_ics(body, expand_from=expand_from, expand_to=expand_to))

    def validate_feed(self, *, credentials: IcsCalendarCredentials) -> None:
        response = self._get_public_url(credentials.feed_url)
        response.raise_for_status()
        if "BEGIN:VCALENDAR" not in response.text:
            raise ValueError("The provided feed did not return valid iCalendar content.")

    def fetch_events(
        self,
        *,
        credentials: IcsCalendarCredentials,
        starts_at_from: datetime | None = None,
        starts_at_to: datetime | None = None,
        limit: int = 100,
    ) -> list[IcsCalendarEvent]:
        response = self._get_public_url(credentials.feed_url)
        response.raise_for_status()
        events = self.parse(response.text, expand_from=starts_at_from, expand_to=starts_at_to)
        if starts_at_from is not None:
            events = [event for event in events if event.starts_at >= starts_at_from]
        if starts_at_to is not None:
            events = [event for event in events if event.starts_at <= starts_at_to]
        events.sort(key=lambda item: item.starts_at)
        return events[:limit]

    def _get_public_url(self, url: str) -> httpx.Response:
        current_url = validate_public_url(url, target="feedUrl")
        for _ in range(5):
            response = httpx.get(current_url, timeout=20.0, follow_redirects=False)
            if response.status_code not in {301, 302, 303, 307, 308}:
                return response
            location = response.headers.get("location")
            if not location:
                return response
            current_url = assert_redirect_safe(current_url, str(httpx.URL(current_url).join(location)), target="feedUrl")
        raise ValueError("The provided feed redirects too many times.")

    def _parse_ics(
        self,
        body: str,
        *,
        expand_from: datetime | None = None,
        expand_to: datetime | None = None,
    ) -> Iterable[IcsCalendarEvent]:
        """Events of one iCalendar body; recurring masters are expanded inside the window.

        The window defaults to yesterday through ``RECURRENCE_HORIZON`` ahead and is never wider
        than that horizon, so a daily rule yields about three months of rows, not two years.
        """
        calendar_name: str | None = None
        default_zone = None
        open_ended_limit: int | None = None
        components: list[dict[str, list[_Property]]] = []
        current: dict[str, list[_Property]] | None = None
        nested = 0
        for raw_line in self._unfold_lines(body.splitlines()):
            line = raw_line.strip()
            if not line:
                continue
            upper = line.upper()
            if current is None:
                if upper == "BEGIN:VEVENT":
                    current, nested = {}, 0
                elif upper.startswith("X-WR-CALNAME:"):
                    calendar_name = self._decode_value(line.partition(":")[2])
                elif upper.startswith("X-WR-TIMEZONE:"):
                    default_zone = resolve_zone(line.partition(":")[2])
                elif upper.startswith("PRODID:") and "GOOGLE CALENDAR" in upper:
                    open_ended_limit = GOOGLE_OPEN_ENDED_LIMIT
                continue
            # VALARM and other components nested in an event carry their own DESCRIPTION/SUMMARY.
            if upper.startswith("BEGIN:"):
                nested += 1
                continue
            if upper.startswith("END:"):
                if nested:
                    nested -= 1
                elif upper == "END:VEVENT":
                    if current:
                        components.append(current)
                    current = None
                continue
            if nested:
                continue
            prop = _parse_property(line)
            if prop is not None:
                current.setdefault(prop.name, []).append(prop)
        low = expand_from or datetime.now(UTC) - timedelta(days=1)
        high = min(expand_to or low + RECURRENCE_HORIZON, low + RECURRENCE_HORIZON)
        overridden: dict[str, set[str]] = {}
        for component in components:
            uid = _first_value(component, "UID")
            rid = _first(component, "RECURRENCE-ID")
            if uid and rid is not None:
                key = self._occurrence_key(rid, default_zone)
                if key:
                    overridden.setdefault(uid, set()).add(key)
        for component in components:
            try:
                yield from self._build_events(
                    component, calendar_name=calendar_name, default_zone=default_zone,
                    overridden=overridden, low=low, high=high, open_ended_limit=open_ended_limit,
                )
            except (ValueError, OverflowError):
                continue  # one malformed event never fails the whole feed

    @staticmethod
    def _unfold_lines(lines: list[str]) -> list[str]:
        unfolded: list[str] = []
        for line in lines:
            if unfolded and line.startswith((" ", "\t")):
                unfolded[-1] += line[1:]
            else:
                unfolded.append(line)
        return unfolded

    def _build_events(
        self,
        component: dict[str, list[_Property]],
        *,
        calendar_name: str | None,
        default_zone,
        overridden: dict[str, set[str]],
        low: datetime,
        high: datetime,
        open_ended_limit: int | None = None,
    ) -> Iterable[IcsCalendarEvent]:
        uid = _first_value(component, "UID")
        dtstart = _first(component, "DTSTART")
        if not uid or dtstart is None:
            return
        starts_at, all_day = self._parse_datetime(dtstart, default_zone)
        ends_at = None
        dtend = _first(component, "DTEND")
        if dtend is not None:
            ends_at, _ = self._parse_datetime(dtend, default_zone)
        else:
            duration = _parse_duration(_first_value(component, "DURATION") or "")
            if duration is not None:
                ends_at = starts_at + duration
        if ends_at is not None and ends_at < starts_at:
            ends_at = None
        status_value = (_first_value(component, "STATUS") or "CONFIRMED").strip().casefold()
        base = dict(
            provider_event_id=uid,
            title=self._decode_value(_first_value(component, "SUMMARY") or "Untitled event") or "Untitled event",
            all_day=all_day,
            status=status_value if status_value in {"tentative", "confirmed", "cancelled"} else "confirmed",
            description=self._optional_decoded(_first_value(component, "DESCRIPTION")),
            location=self._optional_decoded(_first_value(component, "LOCATION")),
            organizer=self._normalize_organizer(_first_value(component, "ORGANIZER")),
            calendar_label=calendar_name,
        )
        rid = _first(component, "RECURRENCE-ID")
        if rid is not None:
            yield IcsCalendarEvent(starts_at=starts_at, ends_at=ends_at,
                                   recurrence_id=self._occurrence_key(rid, default_zone), **base)
            return
        rrule = _first_value(component, "RRULE")
        rule = parse_rule(rrule, zone=starts_at.tzinfo, all_day=all_day) if rrule else None
        if rule is None:
            yield IcsCalendarEvent(starts_at=starts_at, ends_at=ends_at, **base)
            return
        skipped = set(overridden.get(uid, ()))
        for prop in component.get("EXDATE", ()):
            for item in prop.value.split(","):
                if item.strip():
                    key = self._occurrence_key(_Property("EXDATE", prop.params, item.strip()), default_zone)
                    if key:
                        skipped.add(key)
        length = ends_at - starts_at if ends_at is not None else None
        for moment in expand(starts_at, rule, all_day=all_day, window_start=low, window_end=high,
                             open_ended_limit=open_ended_limit):
            key = occurrence_key(moment, all_day=all_day)
            if key in skipped:
                continue
            yield IcsCalendarEvent(starts_at=moment, ends_at=moment + length if length is not None else None,
                                   recurrence_id=key, **base)

    def _occurrence_key(self, prop: _Property, default_zone) -> str:
        try:
            moment, all_day = self._parse_datetime(prop, default_zone)
        except ValueError:
            return self._decode_value(prop.value)
        return occurrence_key(moment, all_day=all_day)

    def _parse_datetime(self, prop: _Property, default_zone) -> tuple[datetime, bool]:
        """DATE (all-day: UTC midnight of the floating date), UTC, TZID-local or floating values.

        Floating times use the feed's X-WR-TIMEZONE, else UTC; an unknown TZID does the same.
        """
        decoded = self._decode_value(prop.value)
        if prop.params.get("VALUE", "").upper() == "DATE" or (len(decoded) == 8 and decoded.isdigit()):
            parsed_date = datetime.strptime(decoded, "%Y%m%d").date()
            return datetime.combine(parsed_date, datetime.min.time(), tzinfo=UTC), True
        if decoded.endswith("Z"):
            return datetime.strptime(decoded, "%Y%m%dT%H%M%SZ").replace(tzinfo=UTC), False
        parsed = datetime.strptime(decoded, "%Y%m%dT%H%M%S")
        zone = resolve_zone(prop.params.get("TZID")) or default_zone or UTC
        return parsed.replace(tzinfo=zone), False

    @staticmethod
    def _normalize_organizer(value: str | None) -> str | None:
        if not value:
            return None
        _, email_address = parseaddr(value.replace("MAILTO:", "mailto:"))
        return email_address or value

    @staticmethod
    def _decode_value(value: str) -> str:
        return (
            value.replace("\\n", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
            .strip()
        )

    def _optional_decoded(self, value: str | None) -> str | None:
        if not value:
            return None
        decoded = self._decode_value(value)
        return decoded or None


def _parse_property(line: str) -> _Property | None:
    """``NAME;PARAM=value;PARAM="quoted":value`` (the value may itself contain colons)."""
    quoted = False
    split = -1
    for index, char in enumerate(line):
        if char == '"':
            quoted = not quoted
        elif char == ":" and not quoted:
            split = index
            break
    if split <= 0:
        return None
    head, value = line[:split], line[split + 1:]
    pieces = head.split(";")
    params: dict[str, str] = {}
    for piece in pieces[1:]:
        key, _, item = piece.partition("=")
        if key.strip():
            params[key.strip().upper()] = item.strip().strip('"')
    return _Property(pieces[0].strip().upper(), params, value)


def _first(component: dict[str, list[_Property]], name: str) -> _Property | None:
    values = component.get(name)
    return values[0] if values else None


def _first_value(component: dict[str, list[_Property]], name: str) -> str | None:
    prop = _first(component, name)
    return prop.value if prop is not None else None


_DURATION = re.compile(r"^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$")


def _parse_duration(value: str) -> timedelta | None:
    """RFC 5545 DURATION ("PT1H30M", "P1D", "P2W"); None when absent or malformed."""
    match = _DURATION.match(value.strip().upper())
    if not match or not any(match.groups()[1:]):
        return None
    weeks, days, hours, minutes, seconds = (int(part) if part else 0 for part in match.groups()[1:])
    span = timedelta(weeks=weeks, days=days, hours=hours, minutes=minutes, seconds=seconds)
    return -span if match.group(1) == "-" else span
