"""Calendar changes on the Google Calendar iCal subscription go through the Mac bridge.

The R1 reads Google Calendar from a read-only secret iCal address. calendar_create_event /
calendar_update_event / calendar_delete_event aimed at it (or at no calendar) are made through the Mac
bridge (fake here), mirrored into the local store at once, and the next iCal refresh never duplicates them.
"""

from __future__ import annotations

from dataclasses import replace
from datetime import UTC, datetime, timedelta
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4
from zoneinfo import ZoneInfo

from sam_runtime.agents import AgentKind
from sam_runtime.connectors.calendar import CaldavCalendarProviderClient, IcsCalendarProviderClient
from sam_runtime.domains.calendar import CalendarAccountConfiguration, CalendarCapabilities, CalendarRepository
from sam_runtime.domains.calendar.repository import local_write_tag
from sam_runtime.domains.calendar.service import CalendarService
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools import ToolCatalog, ToolInvocationContext
from sam_runtime.tools.calendar import CalendarToolPackage

from calendar_bridge_fakes import GOOGLE_FEED, FakeCalendarBridge, make_google_bridge

NEW_YORK = ZoneInfo("America/New_York")
CREATE = "/v1/calendar/events"
UPDATE = "/v1/calendar/events/update"
DELETE = "/v1/calendar/events/delete"


def ics(*events: str) -> str:
    return ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Google Inc//Google Calendar 70.9054//EN\r\n"
            "X-WR-CALNAME:samin@aianswer.us\r\nX-WR-TIMEZONE:America/New_York\r\n" + "".join(events)
            + "END:VCALENDAR\r\n")


def vevent(uid: str, title: str, start: datetime, end: datetime, extra: str = "") -> str:
    stamp = "%Y%m%dT%H%M%SZ"
    return (f"BEGIN:VEVENT\r\nUID:{uid}\r\nDTSTART:{start.astimezone(UTC).strftime(stamp)}\r\n"
            f"DTEND:{end.astimezone(UTC).strftime(stamp)}\r\nSUMMARY:{title}\r\n{extra}END:VEVENT\r\n")


class FeedClient(IcsCalendarProviderClient):
    """The Google iCal feed as a string (no network)."""

    def __init__(self) -> None:
        self.body = ics()

    def fetch_events(self, *, credentials, starts_at_from=None, starts_at_to=None, limit=100):  # noqa: ANN001
        events = self.parse(self.body, expand_from=starts_at_from, expand_to=starts_at_to)
        return sorted(events, key=lambda item: item.starts_at)[:limit]


class GoogleCalendarWriteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.database = RuntimeDatabase(Path(self.tmp.name) / "runtime.sqlite3")
        self.database.migrate()
        self.repo = CalendarRepository(self.database)
        self.feed = FeedClient()
        self.service = CalendarService(self.repo, ConnectionCredentialRepository(self.database), None, self.feed,
                                       CaldavCalendarProviderClient())
        self.fake = FakeCalendarBridge()
        self.addCleanup(self.fake.close)
        _store, self.bridge = make_google_bridge(self.database, self.fake)
        self.service.use_google_bridge(self.bridge, timezone_name=lambda: "America/New_York")
        self.account_id = str(uuid4())
        self.repo.create_account(CalendarAccountConfiguration(self.account_id, "ics_subscription", "samin@aianswer.us",
                                                              GOOGLE_FEED, None), None)
        self.repo.set_capabilities(self.account_id, CalendarCapabilities(False, False, False))
        self.service.sync(self.account_id)
        self.utterance = 7

    def context(self) -> ToolInvocationContext:
        self.utterance += 1
        return ToolInvocationContext(AgentKind.VOICE, voice_session_id="voice-1", tool_call_id=f"call-{self.utterance}",
                                     user_utterance_id=self.utterance)

    def invoke(self, name: str, arguments: dict) -> tuple[bool, object]:
        result = self.service.invoke_tool(name, self.context(), arguments)
        return result.is_error, (result.text if result.is_error else json.loads(result.text))

    def upcoming(self) -> list:
        return [event for event in self.repo.upcoming_events((datetime.now(UTC) - timedelta(days=1)).isoformat(),
                                                             limit=200)]

    def soon(self, hours: int = 3) -> tuple[datetime, datetime]:
        start = (datetime.now(NEW_YORK) + timedelta(hours=hours)).replace(minute=0, second=0, microsecond=0)
        return start, start + timedelta(minutes=30)

    def create(self, title: str = "Research Session", **extra: object) -> dict:
        start, end = self.soon()
        arguments = {"calendarAccountId": self.account_id, "title": title,
                     "startsAt": start.replace(tzinfo=None).isoformat(), "endsAt": end.replace(tzinfo=None).isoformat(),
                     **extra}
        failed, value = self.invoke("calendar_create_event", arguments)
        self.assertFalse(failed, value)
        return value

    # ------------------------------------------------------------------ create

    def test_create_on_the_ics_subscription_goes_to_google_and_shows_at_once(self) -> None:
        start, end = self.soon()
        value = self.create(description="the AI influencer video")
        self.assertEqual("completed", value["state"])
        self.assertEqual("Google Calendar", value["calendar"])
        self.assertEqual("samin@aianswer.us", value["account"])
        self.assertIn("Added “Research Session” to Google Calendar", value["summary"])
        self.assertNotIn("confirmationRequired", value, "Google Calendar events are added right away")
        [body] = self.fake.bodies(CREATE)
        self.assertEqual({"title": "Research Session", "startsAt": start.isoformat(), "endsAt": end.isoformat(),
                          "timezone": "America/New_York", "description": "the AI influencer video",
                          "calendarId": "samin@aianswer.us"}, body, "local wall times, with the offset, on the feed's calendar")
        self.assertEqual(start.isoformat(timespec="minutes"), value["event"]["startsLocal"])
        self.assertEqual(end.isoformat(timespec="minutes"), value["event"]["endsLocal"])
        # The Cards widget and calendar_list_upcoming read the local store: the event is there already.
        [stored] = [event for event in self.upcoming() if event.title == "Research Session"]
        self.assertEqual("fakegoogle0001@google.com", stored.provider_event_id)
        self.assertEqual(start.astimezone(UTC), datetime.fromisoformat(stored.starts_at))
        failed, listed = self.invoke("calendar_list_upcoming", {})
        self.assertFalse(failed)
        [item] = [item for item in listed if item["title"] == "Research Session"]
        self.assertTrue(item["editable"])
        self.assertEqual(start.isoformat(timespec="minutes"), item["startsLocal"])
        self.assertIn(item["startsInMinutes"], range(120, 181))

    def test_no_calendar_given_means_the_google_calendar_and_now_plus_30_minutes(self) -> None:
        before = datetime.now(NEW_YORK).replace(second=0, microsecond=0)
        failed, value = self.invoke("calendar_create_event", {"title": "Research Session", "startsAt": "now",
                                                              "durationMinutes": 30})
        after = datetime.now(NEW_YORK).replace(second=0, microsecond=0)
        self.assertFalse(failed, value)
        [body] = self.fake.bodies(CREATE)
        start = datetime.fromisoformat(body["startsAt"])
        self.assertIn(start, (before, after), "now, rounded to the minute")
        self.assertEqual(0, start.second)
        self.assertEqual(start + timedelta(minutes=30), datetime.fromisoformat(body["endsAt"]))
        self.assertEqual(NEW_YORK.utcoffset(start.replace(tzinfo=None)), start.utcoffset(), "in America/New_York")

    def test_now_in_any_case_and_whole_number_float_durations(self) -> None:
        for starts, minutes in (("Now", 30.0), (" NOW ", 45)):
            with self.subTest(starts=starts, minutes=minutes):
                failed, value = self.invoke("calendar_create_event", {"title": "Focus", "startsAt": starts,
                                                                      "durationMinutes": minutes})
                self.assertFalse(failed, value)
                body = self.fake.bodies(CREATE)[-1]
                length = datetime.fromisoformat(body["endsAt"]) - datetime.fromisoformat(body["startsAt"])
                self.assertEqual(timedelta(minutes=int(minutes)), length)
        failed, value = self.invoke("calendar_create_event", {"title": "Focus", "startsAt": "now",
                                                              "durationMinutes": 30.5})
        self.assertTrue(failed)
        self.assertIn("whole number of minutes", value)

    def test_primary_the_google_email_or_the_label_name_the_google_calendar(self) -> None:
        # A second Google feed: a shared (group) calendar, listed first by label.
        work = str(uuid4())
        group = "c_0123abcd@group.calendar.google.com"
        self.repo.create_account(CalendarAccountConfiguration(
            work, "ics_subscription", "Work calendar",
            "https://calendar.google.com/calendar/ical/c_0123abcd%40group.calendar.google.com/private-0123/basic.ics",
            None), None)
        self.repo.set_capabilities(work, CalendarCapabilities(False, False, False))
        for name, calendar in (("primary", "samin@aianswer.us"), ("Primary", "samin@aianswer.us"),
                               ("samin@aianswer.us", "samin@aianswer.us"), ("SAMIN@aianswer.us ", "samin@aianswer.us"),
                               ("Work calendar", group), ("work  CALENDAR", group), (group, group)):
            with self.subTest(name=name):
                failed, value = self.invoke("calendar_create_event", {"calendarAccountId": name, "title": "Sync",
                                                                      "startsAt": "now", "durationMinutes": 15})
                self.assertFalse(failed, value)
                self.assertEqual("completed", value["state"], "added through the Mac bridge, not reviewed")
                self.assertEqual(calendar, self.fake.bodies(CREATE)[-1]["calendarId"])
        before = len(self.fake.bodies(CREATE))
        failed, value = self.invoke("calendar_create_event", {"calendarAccountId": "not-a-calendar", "title": "x",
                                                              "startsAt": "now", "durationMinutes": 15})
        self.assertTrue(failed)
        self.assertEqual("Calendar connection was not found.", value)
        self.assertEqual(before, len(self.fake.bodies(CREATE)))

    def test_the_label_and_the_bridges_google_account_name_the_feed_but_other_emails_do_not(self) -> None:
        with self.database.connect() as connection:
            connection.execute("UPDATE calendar_accounts SET label = 'My Google', endpoint = ? WHERE account_id = ?",
                               ("https://calendar.google.com/calendar/ical/c_team%40group.calendar.google.com/"
                                "private-0123/basic.ics", self.account_id))
            connection.commit()
        attempt = lambda name: self.invoke("calendar_create_event", {"calendarAccountId": name,  # noqa: E731
                                                                     "title": "x", "startsAt": "now",
                                                                     "durationMinutes": 15})
        failed, value = attempt("my google")
        self.assertFalse(failed, value)
        # The bridge's status (now known) says which Google account the Mac writes with: samin@aianswer.us.
        failed, value = attempt("samin@aianswer.us")
        self.assertFalse(failed, value)
        self.assertEqual(2, len(self.fake.bodies(CREATE)))
        failed, value = attempt("someone.else@example.com")
        self.assertEqual((True, "Calendar connection was not found."), (failed, value), "never a silent guess")
        self.assertEqual(2, len(self.fake.bodies(CREATE)))

    def test_an_event_needs_an_end_and_must_end_after_it_starts(self) -> None:
        start, end = self.soon()
        cases = (
            ({"title": "x", "startsAt": start.isoformat()}, "endsAt or durationMinutes"),
            ({"title": "x", "startsAt": end.isoformat(), "endsAt": start.isoformat()}, "end after it starts"),
            ({"title": "x", "startsAt": start.date().isoformat(), "allDay": True}, "All-day"),
            ({"startsAt": start.isoformat(), "durationMinutes": 30}, "title"),
        )
        for arguments, message in cases:
            with self.subTest(arguments=arguments):
                failed, value = self.invoke("calendar_create_event", arguments)
                self.assertTrue(failed)
                self.assertIn(message, value)
        self.assertEqual([], self.fake.bodies(CREATE), "nothing reached the Mac")

    def test_writes_need_a_live_voice_tool_call(self) -> None:
        result = self.service.invoke_tool("calendar_create_event", ToolInvocationContext(AgentKind.TEXT), {
            "title": "x", "startsAt": "now", "durationMinutes": 30})
        self.assertTrue(result.is_error)
        self.assertEqual([], self.fake.bodies(CREATE))

    # ------------------------------------------------------------------ the iCal refresh

    def test_the_ical_refresh_never_duplicates_a_mirrored_event(self) -> None:
        self.create()
        [mirror] = [event for event in self.upcoming() if event.title == "Research Session"]
        start, end = datetime.fromisoformat(mirror.starts_at), datetime.fromisoformat(mirror.ends_at)
        # The feed has not caught up yet: the R1's copy stays.
        self.service.sync(self.account_id)
        self.assertEqual(["Research Session"], [event.title for event in self.upcoming()])
        # Now Google's feed lists it with UID <eventId>@google.com: one row, the feed's.
        self.feed.body = ics(vevent("fakegoogle0001@google.com", "Research Session", start, end),
                             vevent("other@google.com", "Lunch", start + timedelta(hours=2), end + timedelta(hours=2)))
        self.service.sync(self.account_id)
        events = self.upcoming()
        self.assertEqual(["Research Session", "Lunch"], [event.title for event in events])
        self.assertEqual(mirror.event_id, events[0].event_id, "the same row: same account, UID and occurrence")
        self.assertIsNone(events[0].source_etag, "the feed confirmed it")
        self.service.sync(self.account_id)
        self.assertEqual(2, len(self.upcoming()))

    def test_a_mirror_the_feed_never_shows_goes_after_the_grace_period(self) -> None:
        self.create()
        [mirror] = self.upcoming()
        old = datetime.now(UTC) - timedelta(hours=7)
        self.repo.store_local_write(replace(mirror, source_etag=local_write_tag("create", old)))
        self.service.sync(self.account_id)
        self.assertEqual([], self.upcoming(), "the feed is the truth again")

    # ------------------------------------------------------------------ update

    def test_moving_a_feed_event_keeps_its_length_and_survives_a_stale_feed(self) -> None:
        start, end = self.soon(hours=5)
        self.feed.body = ics(vevent("abcdefghij0123@google.com", "Design review", start, end + timedelta(minutes=30)))
        self.service.sync(self.account_id)
        [event] = self.upcoming()
        failed, listed = self.invoke("calendar_list_upcoming", {})
        self.assertTrue(listed[0]["editable"], "Google feed events can be changed through the Mac")
        new_start = start + timedelta(hours=1)
        failed, value = self.invoke("calendar_update_event", {"eventId": event.event_id,
                                                              "startsAt": new_start.replace(tzinfo=None).isoformat()})
        self.assertFalse(failed, value)
        self.assertEqual("completed", value["state"])
        self.assertIn("Moved “Design review” to", value["summary"])
        [body] = self.fake.bodies(UPDATE)
        self.assertEqual({"iCalUID": "abcdefghij0123@google.com", "timezone": "America/New_York",
                          "startsAt": new_start.isoformat(), "endsAt": (new_start + timedelta(hours=1)).isoformat(),
                          "calendarId": "samin@aianswer.us"}, body, "a move keeps the hour-long length")
        [moved] = self.upcoming()
        self.assertEqual(new_start.astimezone(UTC), datetime.fromisoformat(moved.starts_at))
        self.service.sync(self.account_id)  # the feed still has the old time
        [kept] = self.upcoming()
        self.assertEqual(new_start.astimezone(UTC), datetime.fromisoformat(kept.starts_at))
        self.feed.body = ics(vevent("abcdefghij0123@google.com", "Design review", new_start,
                                    new_start + timedelta(hours=1)))
        self.service.sync(self.account_id)
        [confirmed] = self.upcoming()
        self.assertIsNone(confirmed.source_etag)
        self.assertEqual(event.event_id, confirmed.event_id)

    def test_one_occurrence_of_a_repeating_event_is_changed_alone(self) -> None:
        start, end = self.soon(hours=26)
        self.feed.body = ics(vevent("series0123@google.com", "Standup", start, end, "RRULE:FREQ=DAILY;COUNT=3\r\n"))
        self.service.sync(self.account_id)
        first = self.upcoming()[0]
        self.assertTrue(first.recurrence_id)
        failed, value = self.invoke("calendar_update_event", {"eventId": first.event_id, "title": "Standup (moved)"})
        self.assertFalse(failed, value)
        self.assertIn("only this occurrence", value["summary"])
        [body] = self.fake.bodies(UPDATE)
        self.assertEqual(first.recurrence_id, body["recurrenceId"], "that occurrence, never the series")
        self.assertEqual(["Standup (moved)", "Standup", "Standup"], [event.title for event in self.upcoming()])

    def test_renaming_an_event_without_an_end_changes_only_its_title(self) -> None:
        start, _end = self.soon(hours=4)
        self.feed.body = ics("BEGIN:VEVENT\r\nUID:noend0123@google.com\r\n"
                             f"DTSTART:{start.astimezone(UTC).strftime('%Y%m%dT%H%M%SZ')}\r\nSUMMARY:Reminder\r\n"
                             "END:VEVENT\r\n")
        self.service.sync(self.account_id)
        [event] = self.upcoming()
        self.assertIsNone(event.ends_at)
        failed, value = self.invoke("calendar_update_event", {"eventId": event.event_id, "title": "Call the bank"})
        self.assertFalse(failed, value)
        self.assertIn("Updated “Call the bank”", value["summary"])
        [body] = self.fake.bodies(UPDATE)
        self.assertNotIn("startsAt", body)
        self.assertNotIn("endsAt", body)
        failed, value = self.invoke("calendar_update_event", {"eventId": event.event_id})
        self.assertTrue(failed)
        self.assertIn("Say what to change", value)

    # ------------------------------------------------------------------ delete

    def test_delete_asks_first_then_removes_it_from_google_and_the_r1(self) -> None:
        self.create()
        [event] = self.upcoming()
        failed, review = self.invoke("calendar_delete_event", {"eventId": event.event_id})
        self.assertFalse(failed, review)
        self.assertTrue(review["confirmationRequired"])
        self.assertIn("Delete “Research Session”", review["summary"])
        self.assertEqual([], self.fake.bodies(DELETE), "nothing is deleted before the user says yes")
        failed, value = self.invoke("calendar_confirm_action", {"actionId": review["actionId"],
                                                                "contentHash": review["contentHash"]})
        self.assertFalse(failed, value)
        self.assertEqual("completed", value["state"])
        self.assertIn("Deleted “Research Session”", value["summary"])
        self.assertEqual([{"iCalUID": "fakegoogle0001@google.com", "calendarId": "samin@aianswer.us"}],
                         self.fake.bodies(DELETE))
        self.assertEqual([], self.upcoming(), "gone from the widget and the lists at once")
        start, end = self.soon()
        self.feed.body = ics(vevent("fakegoogle0001@google.com", "Research Session", start, end))
        self.service.sync(self.account_id)
        self.assertEqual([], self.upcoming(), "a feed that still lists it does not bring it back")
        self.feed.body = ics()
        self.service.sync(self.account_id)
        with self.database.connect() as connection:
            self.assertEqual(0, connection.execute("SELECT COUNT(*) FROM calendar_events").fetchone()[0])

    def test_changes_to_events_with_guests_say_the_guests_were_not_notified(self) -> None:
        start, end = self.soon(hours=5)
        self.feed.body = ics(
            vevent("team0123@google.com", "Team sync", start, end, "ORGANIZER;CN=Samin:mailto:samin@aianswer.us\r\n"
                   "ATTENDEE:mailto:partner@example.com\r\n"),
            vevent("invite0123@google.com", "Partner pitch", start + timedelta(hours=1), end + timedelta(hours=1),
                   "ORGANIZER;CN=Partner:mailto:partner@example.com\r\n"),
            vevent("solo0123@google.com", "Focus", start + timedelta(hours=2), end + timedelta(hours=2)))
        self.service.sync(self.account_id)
        events = {event.title: event for event in self.upcoming()}
        self.fake.guests = {"team0123@google.com": 1, "solo0123@google.com": 0}
        failed, value = self.invoke("calendar_update_event", {"eventId": events["Team sync"].event_id, "title": "Team sync v2"})
        self.assertFalse(failed, value)
        self.assertEqual((False, 1), (value["guestsNotified"], value["guests"]))
        self.assertIn("guests were not notified", value["summary"])
        failed, value = self.invoke("calendar_update_event", {"eventId": events["Focus"].event_id, "title": "Deep focus"})
        self.assertFalse(failed, value)
        self.assertNotIn("guestsNotified", value, "no guests, nothing to say")
        self.assertNotIn("guests", value["summary"])
        # Someone else organizes it: Samin is a guest, so it has guests even without Google's count.
        failed, value = self.invoke("calendar_update_event", {"eventId": events["Partner pitch"].event_id,
                                                              "title": "Partner pitch (moved)"})
        self.assertFalse(failed, value)
        self.assertIn("guests were not notified", value["summary"])
        # Deleting: the question and the result both say it.
        failed, review = self.invoke("calendar_delete_event", {"eventId": events["Partner pitch"].event_id})
        self.assertFalse(failed, review)
        self.assertFalse(review["guestsNotified"])
        self.assertIn("Its guests won't be notified", review["summary"])
        failed, done = self.invoke("calendar_confirm_action", {"actionId": review["actionId"],
                                                               "contentHash": review["contentHash"]})
        self.assertFalse(failed, done)
        self.assertIn("guests were not notified", done["summary"])
        self.assertFalse(done["guestsNotified"])
        failed, review = self.invoke("calendar_delete_event", {"eventId": events["Focus"].event_id})
        self.assertFalse(failed, review)
        self.assertIn("No emails are sent, so guests, if it has any, won't be told.", review["summary"])

    # ------------------------------------------------------------------ honest failures

    def test_without_the_bridge_the_error_says_why_and_nothing_changes(self) -> None:
        self.fake.available = False
        failed, value = self.invoke("calendar_create_event", {"title": "x", "startsAt": "now", "durationMinutes": 30})
        self.assertTrue(failed)
        self.assertIn("Google Calendar can't be changed from the R1 right now", value)
        self.assertIn("Composio", value)
        self.assertIn("Nothing was changed", value)
        self.assertNotIn("does not allow", value)
        self.assertNotIn("background", value.lower())
        self.assertEqual([], self.fake.bodies(CREATE))
        self.assertEqual([], self.upcoming())

    def test_an_unconfigured_or_unreachable_mac_is_an_honest_error(self) -> None:
        store, bridge = make_google_bridge(self.database)
        store.clear()
        self.service.use_google_bridge(bridge, timezone_name=lambda: "America/New_York")
        failed, value = self.invoke("calendar_create_event", {"title": "x", "startsAt": "now", "durationMinutes": 30})
        self.assertTrue(failed)
        self.assertIn("the Mac bridge is not connected to this R1", value)
        gone = FakeCalendarBridge()
        url, token = gone.url, gone.token
        gone.close()
        store.save(url, token)
        failed, value = self.invoke("calendar_create_event", {"title": "x", "startsAt": "now", "durationMinutes": 30})
        self.assertTrue(failed)
        self.assertIn("the Mac is unreachable", value)
        self.assertEqual([], self.upcoming())

    def test_bridge_refusals_and_unknown_outcomes_are_reported_as_errors(self) -> None:
        self.fake.responses[CREATE] = (409, {"error": {"code": "calendar_not_connected", "retryable": False,
                                                       "written": False, "fix": "Run on the Mac: composio link googlecalendar",
                                                       "message": "Google Calendar is not linked in Composio on the Mac."}})
        failed, value = self.invoke("calendar_create_event", {"title": "x", "startsAt": "now", "durationMinutes": 30})
        self.assertTrue(failed)
        self.assertIn("I couldn't add “x” to Google Calendar: Google Calendar is not linked in Composio", value)
        self.assertIn("composio link googlecalendar", value)
        self.fake.responses[CREATE] = (504, {"error": {"code": "calendar_timeout", "retryable": False,
                                                       "written": "unknown",
                                                       "message": "Google Calendar did not answer in time."}})
        failed, value = self.invoke("calendar_create_event", {"title": "x", "startsAt": "now", "durationMinutes": 30})
        self.assertTrue(failed)
        self.assertIn("may or may not have happened", value)
        self.assertEqual([], self.upcoming(), "no mirror for a change that wasn't confirmed")
        self.fake.responses[CREATE] = (404, {"error": {"code": "not_found", "message": "Not found."}})
        failed, value = self.invoke("calendar_create_event", {"title": "x", "startsAt": "now", "durationMinutes": 30})
        self.assertTrue(failed)
        self.assertIn("install.sh", value, "an old bridge says how to update it")

    def test_other_read_only_calendars_keep_their_old_answer(self) -> None:
        other = str(uuid4())
        self.repo.create_account(CalendarAccountConfiguration(other, "ics_subscription", "Holidays",
                                                              "https://example.com/holidays.ics", None), None)
        failed, value = self.invoke("calendar_create_event", {"calendarAccountId": other, "title": "x",
                                                              "startsAt": "2026-10-08T10:00:00-04:00",
                                                              "endsAt": "2026-10-08T11:00:00-04:00"})
        self.assertTrue(failed)
        self.assertEqual("This calendar does not allow create operations.", value)
        self.assertEqual([], self.fake.bodies(CREATE))

    def test_the_tool_contract_needs_only_title_and_start_to_create(self) -> None:
        catalog = ToolCatalog()
        CalendarToolPackage(self.service).register(catalog)
        tools = {item["name"]: item for item in catalog.mcp_definitions(AgentKind.VOICE)}
        create = tools["calendar_create_event"]
        self.assertEqual(["title", "startsAt"], create["inputSchema"]["required"])
        self.assertIn("durationMinutes", create["inputSchema"]["properties"])
        self.assertIn("now", create["description"])
        self.assertEqual(["eventId"], tools["calendar_delete_event"]["inputSchema"]["required"])


class CaldavStillReviewsTest(unittest.TestCase):
    """A CalDAV calendar keeps the review-then-confirm flow, now with "now" and durationMinutes."""

    def test_caldav_create_is_prepared_not_executed(self) -> None:
        with TemporaryDirectory() as tmp:
            database = RuntimeDatabase(Path(tmp) / "runtime.sqlite3")
            database.migrate()
            repo = CalendarRepository(database)
            service = CalendarService(repo, ConnectionCredentialRepository(database), None, IcsCalendarProviderClient(),
                                      CaldavCalendarProviderClient())
            account_id = str(uuid4())
            repo.create_account(CalendarAccountConfiguration(account_id, "caldav", "iCloud", "https://caldav.example.com/c/",
                                                             "https://caldav.example.com/c/"), None)
            repo.set_capabilities(account_id, CalendarCapabilities(True, True, True))
            result = service.invoke_tool("calendar_create_event", ToolInvocationContext(
                AgentKind.VOICE, voice_session_id="voice-1", tool_call_id="call-1", user_utterance_id=3),
                {"title": "Focus", "startsAt": "now", "durationMinutes": 45})
            self.assertFalse(result.is_error, result.text)
            value = json.loads(result.text)
            self.assertTrue(value["confirmationRequired"])
            starts, ends = (datetime.fromisoformat(value["payload"][key]) for key in ("startsAt", "endsAt"))
            self.assertEqual(timedelta(minutes=45), ends - starts)
            self.assertNotIn("durationMinutes", value["payload"])


if __name__ == "__main__":
    unittest.main()
