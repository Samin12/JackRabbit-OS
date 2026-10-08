"""calendar_list_upcoming with a time window: "the next 30 minutes" holds only what is in those 30 minutes.

The real case: at 10:33 the voice model listed the next 20 events and read out events at 12:00 and 12:30 as if
they were in the next 30 minutes. With withinMinutes (or from/to) the runtime filters with its own clock.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4

from sam_runtime.agents import AgentKind
from sam_runtime.domains.calendar import CalendarAccountConfiguration, CalendarEvent, CalendarRepository
from sam_runtime.domains.calendar.service import CalendarService
from sam_runtime.domains.calendar.window import list_window
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools import ToolCatalog
from sam_runtime.tools.calendar import CalendarToolPackage
from sam_runtime.tools.definitions import ToolInvocationContext

# 10:33 AM EDT on Thursday, October 8, 2026.
NOW = datetime(2026, 10, 8, 14, 33, tzinfo=UTC)


def _view(event: CalendarEvent) -> dict[str, object]:
    return {"eventId": event.event_id, "title": event.title, "startsAt": event.starts_at, "allDay": event.all_day}


class CalendarWindowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.database = RuntimeDatabase(Path(self.tmp.name) / "runtime.sqlite3")
        self.database.migrate()
        self.repository = CalendarRepository(self.database)
        self.account = self.repository.create_account(CalendarAccountConfiguration(
            str(uuid4()), "ics_subscription", "samin@aianswer.us", "https://example.com/basic.ics", None), None
        ).configuration.account_id

    def events(self, *items: tuple[str, datetime, datetime | None, bool]) -> None:
        self.repository.replace_account_events(self.account, tuple(
            CalendarEvent(str(uuid4()), self.account, title, "", title, start.isoformat(),
                          end.isoformat() if end else None, "UTC", all_day, None, "samin@aianswer.us", None, None,
                          "confirmed", False, None, NOW.isoformat())
            for title, start, end, all_day in items))

    def morning(self, *extra: tuple[str, datetime, datetime | None, bool]) -> None:
        self.events(*extra,
            ("Standup", datetime(2026, 10, 8, 13, 30, tzinfo=UTC), datetime(2026, 10, 8, 14, 0, tzinfo=UTC), False),
            ("Deep work", datetime(2026, 10, 8, 14, 0, tzinfo=UTC), datetime(2026, 10, 8, 15, 0, tzinfo=UTC), False),
            ("Lunch with Sam", datetime(2026, 10, 8, 16, 0, tzinfo=UTC), datetime(2026, 10, 8, 16, 30, tzinfo=UTC), False),
            ("Partner call", datetime(2026, 10, 8, 16, 30, tzinfo=UTC), datetime(2026, 10, 8, 17, 0, tzinfo=UTC), False),
            ("Webinar", datetime(2026, 10, 8, 20, 0, tzinfo=UTC), datetime(2026, 10, 8, 21, 0, tzinfo=UTC), False),
            # A floating all-day date: stored as UTC midnight to midnight.
            ("Mom's birthday", datetime(2026, 10, 9, 0, 0, tzinfo=UTC), datetime(2026, 10, 10, 0, 0, tzinfo=UTC), True),
        )

    def window(self, **arguments: object) -> dict[str, object]:
        return list_window(self.repository, arguments, now=NOW, limit=25, view=_view)

    def test_the_next_30_minutes_excludes_noon_events(self) -> None:
        self.morning()
        result = self.window(withinMinutes=30)
        self.assertEqual(["Deep work"], [item["title"] for item in result["events"]])
        self.assertEqual(1, result["count"])
        self.assertTrue(result["events"][0]["inProgress"], "started at 10:00, still on")
        self.assertEqual(("Thu Oct 8, 10:00 AM", "11:00 AM"),
                         (result["events"][0]["localStart"], result["events"][0]["localEnd"]))
        self.assertEqual("from 10:33 AM to 11:03 AM on Thu Oct 8", result["window"]["label"])
        self.assertEqual(("2026-10-08T10:33-04:00", "2026-10-08T11:03-04:00", "America/New_York"),
                         (result["window"]["from"], result["window"]["to"], result["window"]["timezone"]))
        self.assertEqual(("Lunch with Sam", "Thu Oct 8, 12:00 PM"),
                         (result["nextAfterWindow"]["title"], result["nextAfterWindow"]["localStart"]))
        self.assertIn("never present it as inside it", result["note"])

    def test_an_empty_window_says_so(self) -> None:
        self.morning()
        result = self.window(**{"from": "2026-10-08T11:05:00-04:00", "to": "2026-10-08T11:55:00-04:00"})
        self.assertEqual((0, []), (result["count"], result["events"]))
        self.assertTrue(result["note"].startswith("Nothing is on the calendar from 11:05 AM to 11:55 AM"))
        self.assertEqual("Lunch with Sam", result["nextAfterWindow"]["title"])

    def test_this_afternoon_with_local_times_without_an_offset(self) -> None:
        self.morning()
        result = self.window(**{"from": "2026-10-08T12:00", "to": "2026-10-08T17:00"})
        self.assertEqual(["Lunch with Sam", "Partner call", "Webinar"], [item["title"] for item in result["events"]])
        self.assertNotIn("inProgress", result["events"][0])

    def test_all_day_dates_follow_the_users_zone(self) -> None:
        self.morning()
        tonight = self.window(**{"from": "2026-10-08T20:00:00-04:00", "to": "2026-10-08T23:59:00-04:00"})
        self.assertNotIn("Mom's birthday", [item["title"] for item in tonight["events"]],
                         "UTC midnight Oct 9 is 8 PM Oct 8 in New York, but the birthday is on Oct 9")
        tomorrow = self.window(**{"from": "2026-10-09", "to": "2026-10-10"})
        self.assertEqual(["Mom's birthday"], [item["title"] for item in tomorrow["events"]])
        self.assertEqual("Fri Oct 9, all day", tomorrow["events"][0]["localStart"])

    def test_all_day_events_are_beside_a_short_window_not_in_it(self) -> None:
        # A floating all-day date today (Oct 8), stored as UTC midnight to midnight.
        self.morning(("Company offsite", datetime(2026, 10, 8, tzinfo=UTC), datetime(2026, 10, 9, tzinfo=UTC), True))
        soon = self.window(withinMinutes=30)
        self.assertEqual(["Deep work"], [item["title"] for item in soon["events"]], "not 'in the next 30 minutes'")
        self.assertEqual(1, soon["count"])
        self.assertEqual(["Company offsite"], [item["title"] for item in soon["allDayToday"]])
        self.assertEqual("Thu Oct 8, all day", soon["allDayToday"][0]["localStart"])
        self.assertIn("allDayToday", soon["note"])
        just_short = self.window(**{"from": "2026-10-08T12:00", "to": "2026-10-08T17:59"})
        self.assertNotIn("Company offsite", [item["title"] for item in just_short["events"]])
        self.assertEqual(["Company offsite"], [item["title"] for item in just_short["allDayToday"]])
        afternoon = self.window(**{"from": "2026-10-08T12:00", "to": "2026-10-08T18:00"})  # 6 hours: it counts
        self.assertIn("Company offsite", [item["title"] for item in afternoon["events"]])
        self.assertNotIn("allDayToday", afternoon)
        other_day = self.window(**{"from": "2026-10-10T09:00", "to": "2026-10-10T10:00"})
        self.assertNotIn("allDayToday", other_day, "only all-day events of the window's own day")
        empty = self.window(**{"from": "2026-10-08T11:05:00-04:00", "to": "2026-10-08T11:55:00-04:00"})
        self.assertEqual(0, empty["count"])
        self.assertTrue(empty["note"].startswith("Nothing is on the calendar from 11:05 AM to 11:55 AM"))
        self.assertEqual(["Company offsite"], [item["title"] for item in empty["allDayToday"]])

    def test_a_window_crossing_midnight_counts_only_the_day_it_mostly_covers(self) -> None:
        self.morning(("Company offsite", datetime(2026, 10, 8, tzinfo=UTC), datetime(2026, 10, 9, tzinfo=UTC), True))
        evening = self.window(**{"from": "2026-10-08T18:00", "to": "2026-10-09T02:00"})  # 6 h of Oct 8, 2 h of Oct 9
        self.assertIn("Company offsite", [item["title"] for item in evening["events"]])
        self.assertNotIn("Mom's birthday", [item["title"] for item in evening["events"]])
        self.assertEqual(["Mom's birthday"], [item["title"] for item in evening["allDayToday"]])

    def test_whole_number_floats_and_now_in_any_case(self) -> None:
        self.morning()
        self.assertEqual(self.window(withinMinutes=30), self.window(withinMinutes=30.0))
        self.assertEqual(["Deep work"], [item["title"] for item in self.window(
            **{"from": "Now", "to": "2026-10-08T11:03:00-04:00"})["events"]])
        self.assertEqual("2026-10-08T10:33-04:00", self.window(**{"from": " NOW ", "withinMinutes": 30})["window"]["from"])
        for bad in (30.5, "30", True, 0.0):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, "whole number"):
                self.window(withinMinutes=bad)

    def test_bad_windows_are_explained(self) -> None:
        for arguments, message in (
            ({"withinMinutes": 30, "to": "2026-10-08T12:00:00-04:00"}, "not both"),
            ({"from": "2026-10-08T12:00:00-04:00"}, "withinMinutes or to"),
            ({"from": "2026-10-08T12:00:00-04:00", "to": "2026-10-08T11:00:00-04:00"}, "end after"),
            ({"from": "noon", "to": "3pm"}, "ISO 8601"),
            ({"withinMinutes": 30, "timezone": "Mars/Olympus"}, "Unknown timezone"),
            ({"from": "2026-10-01T00:00:00-04:00", "to": "2026-10-20T00:00:00-04:00"}, "7 days"),
        ):
            with self.subTest(arguments=arguments), self.assertRaisesRegex(ValueError, message):
                self.window(**arguments)

    def test_through_the_tool_the_window_is_validated_and_answered(self) -> None:
        now = datetime.now(UTC)
        self.events(
            ("Soon", now + timedelta(minutes=10), now + timedelta(minutes=40), False),
            ("Later today", now + timedelta(minutes=90), now + timedelta(minutes=120), False),
        )
        catalog = ToolCatalog()
        CalendarToolPackage(CalendarService(self.repository, None, None, None, None)).register(catalog)
        schema = {item["name"]: item for item in catalog.realtime_definitions()}["calendar_list_upcoming"]
        self.assertEqual({"limit", "withinMinutes", "from", "to", "timezone"},
                         set(schema["parameters"]["properties"]))
        self.assertIn("withinMinutes", schema["description"])
        self.assertIn("never present nextAfterWindow", schema["description"])
        context = ToolInvocationContext(AgentKind.VOICE, "voice-1", "call-1")
        result = catalog.invoke("calendar_list_upcoming", {"withinMinutes": 30}, agent=AgentKind.VOICE,
                                context=context)
        self.assertFalse(result.is_error, result.text)
        value = json.loads(result.text)
        self.assertEqual(["Soon"], [item["title"] for item in value["events"]])
        self.assertEqual("Later today", value["nextAfterWindow"]["title"])
        self.assertEqual("samin@aianswer.us", value["events"][0]["calendar"], "the normal event fields are kept")
        plain = json.loads(catalog.invoke("calendar_list_upcoming", {"limit": 5}, agent=AgentKind.VOICE,
                                          context=context).text)
        self.assertEqual(["Soon", "Later today"], [item["title"] for item in plain], "no window: the list as before")
        invalid = catalog.invoke("calendar_list_upcoming", {"withinMinutes": 0}, agent=AgentKind.VOICE, context=context)
        self.assertTrue(invalid.is_error)
        floating = catalog.invoke("calendar_list_upcoming", {"withinMinutes": 30.0, "limit": 5.0},
                                  agent=AgentKind.VOICE, context=context)
        self.assertFalse(floating.is_error, floating.text)
        self.assertEqual(["Soon"], [item["title"] for item in json.loads(floating.text)["events"]])
        self.assertIn("startsInMinutes", json.loads(floating.text)["events"][0], "the same event view as the list")
        failed = catalog.invoke("calendar_list_upcoming", {"from": "2026-10-08T12:00:00-04:00"},
                                agent=AgentKind.VOICE, context=context)
        self.assertTrue(failed.is_error)
        self.assertIn("withinMinutes or to", failed.text)


if __name__ == "__main__":
    unittest.main()
