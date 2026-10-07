"""GET /v1/journal/status "today" counts used by the Cards board Journal widget."""

from __future__ import annotations

from datetime import datetime
import unittest
from zoneinfo import ZoneInfo

from heptabase_fakes import FakeClock, JournalHarness, ScriptedTransport

from sam_runtime.domains.heptabase_journal.errors import TransportError

NY = ZoneInfo("America/New_York")


def _ny(*parts: int) -> float:
    return datetime(*parts, tzinfo=NY).timestamp()


class JournalTodayStatusTest(unittest.TestCase):
    def setUp(self) -> None:
        self.transport = ScriptedTransport()
        # 23:30 in New York is already 03:30 UTC the next day: "today" must stay the 7th.
        self.h = JournalHarness(clock=FakeClock(_ny(2026, 10, 7, 23, 30)), transport=self.transport)
        self.addCleanup(self.h.close)

    def test_not_connected_reports_an_empty_local_today(self) -> None:
        status = self.h.service.device_status()
        self.assertFalse(status["connected"])
        self.assertEqual({"date": "2026-10-07", "sent": 0, "queued": 0, "failed": 0}, status["today"])

    def test_today_counts_sent_and_queued_entries_of_the_local_day(self) -> None:
        self.h.connect()
        self.assertEqual("sent", self.h.service.record_note("first thought")["state"])
        self.transport.script.append(TransportError("refused", sent=False))
        self.assertEqual("queued", self.h.service.record_note("offline thought")["state"])
        status = self.h.service.device_status()
        self.assertEqual({"date": "2026-10-07", "sent": 1, "queued": 1, "failed": 0}, status["today"])
        self.assertEqual(1, status["pending"])

        # Past local midnight the new day starts empty; yesterday's queued entry stays pending overall.
        self.h.clock.advance(31 * 60)
        status = self.h.service.device_status()
        self.assertEqual({"date": "2026-10-08", "sent": 0, "queued": 0, "failed": 0}, status["today"])
        self.assertEqual(1, status["pending"])
        self.h.service.drain()
        self.assertEqual(0, self.h.service.device_status()["pending"])


if __name__ == "__main__":
    unittest.main()
