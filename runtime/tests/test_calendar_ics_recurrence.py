"""ICS feeds as Google Calendar writes them: TZID-local times, RRULE masters, overrides, EXDATEs.

Before this, TZID times were read as UTC (a 12:00 PM New York meeting showed at 8:00 AM) and
recurring masters were never expanded (daily and weekly events vanished from Up next).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
import unittest

from sam_runtime.connectors.calendar.ics import IcsCalendarProviderClient
from sam_runtime.connectors.calendar.recurrence import expand, parse_rule, resolve_zone

WINDOW_FROM = datetime(2026, 10, 6, 21, 0, tzinfo=UTC)  # 5 PM in New York on Oct 6
WINDOW_TO = WINDOW_FROM + timedelta(days=730)


def feed(*events: str, header: str = "X-WR-CALNAME:sam@example.com\nX-WR-TIMEZONE:America/New_York") -> str:
    body = "\n".join(["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Google Inc//Google Calendar 70.9054//EN",
                      header, "BEGIN:VTIMEZONE", "TZID:America/New_York", "BEGIN:DAYLIGHT", "TZOFFSETFROM:-0500",
                      "TZOFFSETTO:-0400", "DTSTART:19700308T020000", "END:DAYLIGHT", "END:VTIMEZONE"])
    for event in events:
        body += "\nBEGIN:VEVENT\n" + event.strip() + "\nEND:VEVENT"
    return body + "\nEND:VCALENDAR\n"


def parse(body: str, start: datetime = WINDOW_FROM, end: datetime = WINDOW_TO):
    return IcsCalendarProviderClient().parse(body, expand_from=start, expand_to=end)


def starts(events, title: str) -> list[str]:
    return [item.starts_at.astimezone(UTC).strftime("%Y-%m-%dT%H:%MZ") for item in sorted(events, key=lambda e: e.starts_at)
            if item.title == title]


class IcsTimeZoneTest(unittest.TestCase):
    def test_tzid_local_times_are_converted_not_read_as_utc(self) -> None:
        events = parse(feed("""
DTSTART;TZID=America/New_York:20261008T120000
DTEND;TZID=America/New_York:20261008T130000
UID:mastermind@google.com
SUMMARY:Mastermind
LOCATION:https://us02web.zoom.us/j/100200300
"""))
        self.assertEqual(1, len(events))
        self.assertEqual(datetime(2026, 10, 8, 16, 0, tzinfo=UTC), events[0].starts_at.astimezone(UTC))
        self.assertEqual(datetime(2026, 10, 8, 17, 0, tzinfo=UTC), events[0].ends_at.astimezone(UTC))
        self.assertEqual("", events[0].recurrence_id)
        self.assertEqual("sam@example.com", events[0].calendar_label)

    def test_floating_times_and_unknown_zones_use_the_feed_zone(self) -> None:
        events = parse(feed("""
DTSTART:20261009T090000
UID:floating
SUMMARY:Floating
""", """
DTSTART;TZID=Mars/Olympus_Mons:20261009T090000
UID:unknown
SUMMARY:Unknown zone
""", """
DTSTART;TZID=/mozilla.org/20050126_1/America/Chicago:20261009T090000
UID:mozilla
SUMMARY:Mozilla zone
""", """
DTSTART;TZID="Pacific Standard Time":20261009T090000
UID:windows
SUMMARY:Windows zone
"""))
        self.assertEqual(["2026-10-09T13:00Z"], starts(events, "Floating"))
        self.assertEqual(["2026-10-09T13:00Z"], starts(events, "Unknown zone"))
        self.assertEqual(["2026-10-09T14:00Z"], starts(events, "Mozilla zone"))
        self.assertEqual(["2026-10-09T16:00Z"], starts(events, "Windows zone"))

    def test_alarm_fields_do_not_replace_the_event_description(self) -> None:
        events = parse(feed("""
DTSTART:20261008T160000Z
DTEND:20261008T170000Z
UID:alarm
SUMMARY:Planning
DESCRIPTION:Agenda in the doc
BEGIN:VALARM
ACTION:DISPLAY
DESCRIPTION:This is an event reminder
SUMMARY:Alarm notification
TRIGGER:-P0DT0H10M0S
END:VALARM
"""))
        self.assertEqual(("Planning", "Agenda in the doc"), (events[0].title, events[0].description))

    def test_duration_sets_the_end_and_a_broken_event_is_skipped(self) -> None:
        events = parse(feed("""
DTSTART:20261008T160000Z
DURATION:PT1H30M
UID:duration
SUMMARY:Workshop
""", """
DTSTART:not-a-date
UID:broken
SUMMARY:Broken
"""))
        self.assertEqual(["Workshop"], [item.title for item in events])
        self.assertEqual(timedelta(minutes=90), events[0].ends_at - events[0].starts_at)


class IcsRecurrenceTest(unittest.TestCase):
    def test_daily_rule_keeps_wall_clock_time_across_daylight_saving(self) -> None:
        events = parse(feed("""
DTSTART;TZID=America/New_York:20260517T070000
DTEND;TZID=America/New_York:20260517T100000
RRULE:FREQ=DAILY
UID:morning@google.com
SUMMARY:Morning Check
"""))
        times = starts(events, "Morning Check")
        self.assertEqual("2026-10-06T11:00Z", times[0])  # 7 AM EDT; the window keeps a day of slack
        self.assertIn("2026-10-08T11:00Z", times)
        self.assertIn("2026-11-02T12:00Z", times)  # 7 AM EST after the change on Nov 1
        self.assertLessEqual(len(times), 93)  # bounded by the recurrence horizon, not two years
        morning = next(item for item in events if item.starts_at.astimezone(UTC).day == 8)
        self.assertEqual("20261008T110000Z", morning.recurrence_id)
        self.assertEqual(timedelta(hours=3), morning.ends_at - morning.starts_at)

    def test_override_replaces_its_occurrence_and_exdate_removes_one(self) -> None:
        events = parse(feed("""
DTSTART;TZID=America/New_York:20260924T120000
DTEND;TZID=America/New_York:20260924T130000
RRULE:FREQ=WEEKLY;BYDAY=TH
EXDATE;TZID=America/New_York:20261015T120000
UID:mastermind@google.com
SUMMARY:Mastermind
""", """
DTSTART;TZID=America/New_York:20261008T150000
DTEND;TZID=America/New_York:20261008T160000
RECURRENCE-ID;TZID=America/New_York:20261008T120000
UID:mastermind@google.com
SUMMARY:Mastermind (moved)
"""))
        self.assertEqual(["2026-10-22T16:00Z", "2026-10-29T16:00Z"], starts(events, "Mastermind")[:2])
        self.assertEqual(["2026-10-08T19:00Z"], starts(events, "Mastermind (moved)"))
        moved = next(item for item in events if item.title == "Mastermind (moved)")
        self.assertEqual("20261008T160000Z", moved.recurrence_id)  # same key the master instance had

    def test_cancelled_override_hides_the_occurrence(self) -> None:
        events = parse(feed("""
DTSTART:20261007T170000Z
DTEND:20261007T180000Z
RRULE:FREQ=WEEKLY;BYDAY=WE;COUNT=3
UID:sync
SUMMARY:Sync
""", """
DTSTART:20261014T170000Z
RECURRENCE-ID:20261014T170000Z
STATUS:CANCELLED
UID:sync
SUMMARY:Sync
"""))
        visible = [item for item in events if item.status != "cancelled"]
        self.assertEqual(["2026-10-07T17:00Z", "2026-10-21T17:00Z"], starts(visible, "Sync"))

    def test_count_counts_from_dtstart_and_until_is_inclusive(self) -> None:
        events = parse(feed("""
DTSTART:20261001T140000Z
RRULE:FREQ=DAILY;COUNT=8
UID:count
SUMMARY:Count
""", """
DTSTART;TZID=America/New_York:20261005T090000
RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR;UNTIL=20261012T130000Z
UID:until
SUMMARY:Until
"""))
        # Oct 1..8 counted from DTSTART; the window (with a day of slack) starts Oct 5 21:00 UTC.
        self.assertEqual(["2026-10-06T14:00Z", "2026-10-07T14:00Z", "2026-10-08T14:00Z"], starts(events, "Count"))
        self.assertEqual(["2026-10-07T13:00Z", "2026-10-09T13:00Z", "2026-10-12T13:00Z"], starts(events, "Until"))

    def test_monthly_and_yearly_rules(self) -> None:
        events = parse(feed("""
DTSTART:20260113T150000Z
RRULE:FREQ=MONTHLY;BYDAY=2TU
UID:second-tuesday
SUMMARY:Second Tuesday
""", """
DTSTART:20260130T150000Z
RRULE:FREQ=MONTHLY;BYDAY=-1FR
UID:last-friday
SUMMARY:Last Friday
""", """
DTSTART:20260131T150000Z
RRULE:FREQ=MONTHLY;INTERVAL=1
UID:thirty-first
SUMMARY:Thirty-first
""", """
DTSTART;VALUE=DATE:20200229
DTEND;VALUE=DATE:20200301
RRULE:FREQ=YEARLY
UID:leap
SUMMARY:Leap birthday
""", """
DTSTART;VALUE=DATE:19900312
DTEND;VALUE=DATE:19900313
RRULE:FREQ=YEARLY
UID:birthday
SUMMARY:Birthday
"""), WINDOW_FROM, WINDOW_FROM + timedelta(days=92))
        self.assertEqual(["2026-10-13T15:00Z", "2026-11-10T15:00Z", "2026-12-08T15:00Z"], starts(events, "Second Tuesday"))
        self.assertEqual(["2026-10-30T15:00Z", "2026-11-27T15:00Z", "2026-12-25T15:00Z"], starts(events, "Last Friday"))
        self.assertEqual(["2026-10-31T15:00Z", "2026-12-31T15:00Z"], starts(events, "Thirty-first"))
        self.assertEqual([], starts(events, "Leap birthday"))  # no Feb 29 in the window
        self.assertEqual([], starts(events, "Birthday"))
        later = parse(feed("""
DTSTART;VALUE=DATE:19900312
DTEND;VALUE=DATE:19900313
RRULE:FREQ=YEARLY
UID:birthday
SUMMARY:Birthday
"""), datetime(2027, 3, 1, tzinfo=UTC), datetime(2027, 4, 1, tzinfo=UTC))
        self.assertEqual(1, len(later))
        self.assertTrue(later[0].all_day)
        self.assertEqual(datetime(2027, 3, 12, tzinfo=UTC), later[0].starts_at)
        self.assertEqual("20270312", later[0].recurrence_id)
        self.assertEqual(timedelta(days=1), later[0].ends_at - later[0].starts_at)

    def test_unsupported_rules_keep_only_the_first_occurrence(self) -> None:
        for rule in ("FREQ=HOURLY", "FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO,TU,WE,TH,FR", "FREQ=YEARLY;BYDAY=20MO", "garbage"):
            with self.subTest(rule):
                events = parse(feed(f"""
DTSTART:20261009T150000Z
RRULE:{rule}
UID:odd
SUMMARY:Odd
"""))
                self.assertEqual(["2026-10-09T15:00Z"], starts(events, "Odd"))
                self.assertEqual("", events[0].recurrence_id)


class RecurrenceHelpersTest(unittest.TestCase):
    def test_zone_resolution(self) -> None:
        self.assertIsNone(resolve_zone(""))
        self.assertIs(UTC, resolve_zone("UTC"))
        self.assertEqual("America/New_York", str(resolve_zone("Eastern Standard Time")))

    def test_expand_without_count_skips_ahead_and_stays_bounded(self) -> None:
        rule = parse_rule("FREQ=DAILY", zone=UTC, all_day=False)
        start = datetime(1995, 1, 1, 8, 0, tzinfo=UTC)
        window = datetime(2026, 10, 7, tzinfo=UTC)
        result = expand(start, rule, all_day=False, window_start=window, window_end=window + timedelta(days=2))
        self.assertEqual([datetime(2026, 10, 6, 8, 0, tzinfo=UTC), datetime(2026, 10, 7, 8, 0, tzinfo=UTC),
                          datetime(2026, 10, 8, 8, 0, tzinfo=UTC)], result)


if __name__ == "__main__":
    unittest.main()
