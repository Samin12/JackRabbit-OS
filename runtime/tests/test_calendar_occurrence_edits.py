"""Expanded occurrences of a repeating CalDAV event are read-only.

CalDAV updates and deletes act on the whole resource (the series UID). Once recurring masters are
expanded into one row per occurrence, "delete tomorrow's standup" would otherwise delete every
standup, and an update would replace the series with a single event.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4

from sam_runtime.agents import AgentKind
from sam_runtime.connectors.calendar import CaldavCalendarProviderClient, IcsCalendarProviderClient
from sam_runtime.domains.calendar import CalendarAccountConfiguration, CalendarCapabilities, CalendarRepository
from sam_runtime.domains.calendar.service import CalendarService
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools import ToolInvocationContext

FEED = """BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Apple Inc.//iCloud//EN
BEGIN:VEVENT
UID:standup@example.com
DTSTART;TZID=America/New_York:{day}T093000
DTEND;TZID=America/New_York:{day}T094500
RRULE:FREQ=DAILY;COUNT=5
SUMMARY:Standup
END:VEVENT
BEGIN:VEVENT
UID:dentist@example.com
DTSTART;TZID=America/New_York:{day}T150000
DTEND;TZID=America/New_York:{day}T160000
SUMMARY:Dentist
END:VEVENT
END:VCALENDAR
"""


class CalendarOccurrenceEditsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        database = RuntimeDatabase(Path(self.tmp.name) / "runtime.sqlite3")
        database.migrate()
        self.repo = CalendarRepository(database)
        self.service = CalendarService(self.repo, ConnectionCredentialRepository(database), None,
                                       IcsCalendarProviderClient(), CaldavCalendarProviderClient())
        account_id = str(uuid4())
        self.repo.create_account(CalendarAccountConfiguration(account_id, "caldav", "iCloud",
                                                              "https://caldav.example.com/cal/",
                                                              "https://caldav.example.com/cal/"), None)
        self.repo.set_capabilities(account_id, CalendarCapabilities(True, True, True))
        self.account = self.repo.get_account(account_id)
        now = datetime.now(UTC)
        day = (now + timedelta(days=1)).strftime("%Y%m%d")
        events = IcsCalendarProviderClient().parse(FEED.format(day=day), expand_from=now - timedelta(days=1),
                                                   expand_to=now + timedelta(days=30))
        self.service._store(self.account, events)
        self.stored = self.repo.upcoming_events((now - timedelta(days=1)).isoformat(), limit=50)

    def context(self) -> ToolInvocationContext:
        return ToolInvocationContext(AgentKind.VOICE, voice_session_id="voice-1", tool_call_id="call-1",
                                     user_utterance_id=7)

    def test_occurrences_are_read_only_and_single_events_stay_editable(self) -> None:
        standups = [event for event in self.stored if event.title == "Standup"]
        dentist = [event for event in self.stored if event.title == "Dentist"]
        self.assertEqual(5, len(standups))
        self.assertTrue(all(event.recurrence_id and not event.editable for event in standups))
        self.assertEqual(1, len(dentist))
        self.assertTrue(dentist[0].editable)

    def test_deleting_one_occurrence_is_refused_instead_of_deleting_the_series(self) -> None:
        standup = next(event for event in self.stored if event.title == "Standup")
        result = self.service.invoke_tool("calendar_delete_event", self.context(), {
            "calendarAccountId": self.account.configuration.account_id, "eventId": standup.event_id})
        self.assertTrue(result.is_error)
        self.assertIn("read-only", result.text)
        dentist = next(event for event in self.stored if event.title == "Dentist")
        result = self.service.invoke_tool("calendar_delete_event", self.context(), {
            "calendarAccountId": self.account.configuration.account_id, "eventId": dentist.event_id})
        self.assertFalse(result.is_error)
        self.assertTrue(result.structured_content["result"]["confirmationRequired"])


if __name__ == "__main__":
    unittest.main()
