from __future__ import annotations

from datetime import datetime
import unittest
from zoneinfo import ZoneInfo

from heptabase_fakes import FakeClock, JournalHarness, ScriptedTransport

from sam_runtime.domains.heptabase_journal.errors import TransportError
from sam_runtime.domains.heptabase_journal.outbox import NewEntry, next_work

NY = ZoneInfo("America/New_York")


def _ny(*parts: int) -> float:
    return datetime(*parts, tzinfo=NY).timestamp()


class HeptabaseOutboxTest(unittest.TestCase):
    def setUp(self) -> None:
        self.transport = ScriptedTransport()
        self.h = JournalHarness(clock=FakeClock(_ny(2026, 10, 7, 13, 42)), transport=self.transport)
        self.addCleanup(self.h.close)
        self.h.connect()

    def _states(self) -> list[str]:
        return [str(row["state"]) for row in self.h.rows()]

    def test_note_is_sent_immediately_with_local_date_and_time_label(self) -> None:
        result = self.h.service.record_note("I think the orb should pulse slower when it's listening.")
        self.assertEqual({"recorded": True, "state": "sent", "date": "2026-10-07"},
                         {k: result[k] for k in ("recorded", "state", "date")})
        self.assertEqual([("2026-10-07", "**13:42** I think the orb should pulse slower when it's listening.")],
                         self.h.fake.appends())
        self.assertIsNotNone(self.h.service.device_status()["lastSentAt"])

    def test_invalid_markdown_retries_once_as_plain_then_fails(self) -> None:
        self.h.fake.failures.extend(["invalidHeptaMarkdown"])
        result = self.h.service.record_note("first try")
        self.h.service.drain()
        self.assertEqual("sent", self.h.rows()[0]["state"], result)
        contents = [content for _, content in self.h.fake.appends()]
        self.assertEqual(["**13:42** first try", "13:42 first try"], contents)

        self.h.fake.failures.extend(["invalidHeptaMarkdown", "invalidHeptaMarkdown"])
        self.h.service.record_note("second try")
        self.h.service.drain()
        self.assertEqual("failed", self.h.rows()[-1]["state"])
        self.assertEqual("invalidHeptaMarkdown", self.h.rows()[-1]["last_error"])
        self.assertEqual(1, self.h.service.device_status()["failed"])
        self.assertIn("formatting", str(self.h.service.management_view()["lastError"]))

    def test_invalid_date_fails_without_retry(self) -> None:
        self.h.fake.failures.append("invalidDate")
        self.h.service.record_note("dated badly")
        self.h.service.drain()
        self.assertEqual(["failed"], self._states())
        self.assertEqual(1, len(self.h.fake.appends()))
        retried = self.h.service.retry_failed()
        self.assertEqual(1, retried["retried"])
        self.h.service.drain()
        self.assertEqual(["sent"], self._states())

    def test_network_error_before_send_backs_off_then_sends_once(self) -> None:
        self.transport.script.append(TransportError("refused", sent=False))
        result = self.h.service.record_note("offline thought")
        self.assertEqual("queued", result["state"])
        row = self.h.rows()[0]
        self.assertEqual(("pending", 1), (row["state"], row["attempts"]))
        self.assertEqual(0, self.h.service.drain(), "backing off for 30 s")
        self.h.clock.advance(31)
        self.h.service.drain()
        self.assertEqual(["sent"], self._states())
        self.assertEqual(1, len(self.h.fake.appends()))

    def test_5xx_after_write_is_uncertain_and_fingerprint_check_prevents_duplicate(self) -> None:
        for mode in ("500_after", "reset_after"):
            with self.subTest(mode=mode):
                self.h.fake.failures.append(mode)
                self.h.service.record_note(f"maybe written {mode}")
                self.assertEqual("uncertain", self.h.rows()[-1]["state"])
                self.h.clock.advance(31)
                self.h.service.drain()
                self.assertEqual("sent", self.h.rows()[-1]["state"])
                lines = [line for line in self.h.fake.journal["2026-10-07"] if f"maybe written {mode.replace('_', chr(92) + '_')}" in line]
                self.assertEqual(1, len(lines), "exactly one copy in the journal")
        reads = [name for name, _ in self.h.fake.calls if name == "read_journal_range"]
        self.assertEqual(2, len(reads))

    def test_uncertain_without_write_is_resent_after_check(self) -> None:
        self.h.fake.failures.append("500_before")
        self.h.service.record_note("lost in transit")
        self.assertEqual(["uncertain"], self._states())
        self.h.clock.advance(31)
        self.h.service.drain()
        self.assertEqual(["sent"], self._states())
        self.assertEqual(2, len(self.h.fake.appends()))
        self.assertEqual(1, sum("lost in transit" in line for line in self.h.fake.journal["2026-10-07"]))

    def test_verify_failure_keeps_entry_uncertain(self) -> None:
        self.h.fake.failures.extend(["500_after"])
        self.h.service.record_note("check me")
        self.h.clock.advance(31)
        self.h.fake.failures.append("read_500")
        self.h.service.drain()
        self.assertEqual(["uncertain"], self._states())
        self.h.clock.advance(3601)
        self.h.service.drain()
        self.assertEqual(["sent"], self._states())
        self.assertEqual(1, len(self.h.fake.appends()))

    def test_per_date_fifo_blocks_later_entries_but_not_other_dates(self) -> None:
        self.transport.script.append(TransportError("refused", sent=False))
        self.h.service.record_note("first thought")
        self.h.clock.advance(5)
        second = self.h.service.record_note("second thought")
        self.assertEqual("queued", second["state"], "must not overtake the backing-off head")
        yesterday = self.h.service.record_note("from last night", at=_ny(2026, 10, 6, 23, 30))
        self.assertEqual(("sent", "2026-10-06"), (yesterday["state"], yesterday["date"]))
        self.h.clock.advance(31)
        self.h.service.drain()
        today = [content for date, content in self.h.fake.appends() if date == "2026-10-07"]
        self.assertEqual(["**13:42** first thought\n\n**13:42** second thought"], today, "one batched call, in order")

    def test_batches_respect_16kb(self) -> None:
        words = "word " * 700  # ~3.5 KB each
        for index in range(6):
            self.h.service.record_note(f"{index} {words}", wait=False)
        self.h.service.drain()
        calls = self.h.fake.appends()
        self.assertGreater(len(calls), 1)
        self.assertTrue(all(len(content.encode()) <= 16 * 1024 for _, content in calls))
        self.assertEqual(["sent"] * 6, self._states())
        joined = "\n\n".join(content for _, content in calls)
        self.assertLess(joined.index("** 0 word"), joined.index("** 5 word"))

    def test_rejected_batch_is_split_into_single_entries(self) -> None:
        self.h.service.record_note("alpha", wait=False)
        self.h.service.record_note("beta", wait=False)
        self.h.fake.failures.append("invalidHeptaMarkdown")
        self.h.service.drain()
        contents = [content for _, content in self.h.fake.appends()]
        self.assertEqual(["**13:42** alpha\n\n**13:42** beta", "**13:42** alpha", "**13:42** beta"], contents)
        self.assertEqual(["sent", "sent"], self._states())

    def test_inflight_rows_recover_as_uncertain(self) -> None:
        outbox = self.h.service._outbox  # noqa: SLF001
        entry, _ = outbox.enqueue(NewEntry(journal_date="2026-10-07", kind="note", content="**13:42** crash",
                                           plain_content="13:42 crash", fingerprint="1342crash",
                                           event_at=self.h.clock()))
        outbox.mark_sending([entry.entry_id])
        self.assertEqual(1, outbox.recover_interrupted())
        self.assertEqual("uncertain", outbox.get(entry.entry_id).state)
        self.h.service.drain()
        self.assertEqual("sent", outbox.get(entry.entry_id).state)
        self.assertEqual(1, len(self.h.fake.appends()), "not found in journal, so resent once")

    def test_duplicate_source_ref_is_ignored(self) -> None:
        outbox = self.h.service._outbox  # noqa: SLF001
        item = NewEntry(journal_date="2026-10-07", kind="session", content="x", plain_content="x",
                        fingerprint="x", event_at=self.h.clock(), source_ref="session-1")
        first, created = outbox.enqueue(item)
        again, created_again = outbox.enqueue(item)
        self.assertTrue(created)
        self.assertFalse(created_again)
        self.assertEqual(first.entry_id, again.entry_id)

    def test_forbidden_pauses_the_queue_instead_of_retrying_hot(self) -> None:
        self.h.fake.failures.append("403")
        result = self.h.service.record_note("scope was revoked")
        self.assertEqual("queued", result["state"])
        self.assertEqual("reconnect_required", self.h.service.management_view()["state"])
        self.assertEqual(0, self.h.service.drain(), "gate closed: no further attempts")
        self.assertEqual([("pending", 1)], [(row["state"], row["attempts"]) for row in self.h.rows()],
                         "exactly one attempt, entry kept")

    def test_lost_credentials_require_reconnect(self) -> None:
        self.h.service._oauth._store.delete()  # noqa: SLF001 - simulate a Keystore wipe
        result = self.h.service.record_note("after a wipe")
        self.assertEqual("queued", result["state"])
        self.assertEqual("reconnect_required", self.h.service.management_view()["state"])
        self.assertEqual(0, self.h.service.drain())
        self.assertEqual([], self.h.fake.calls)

    def test_next_work_skips_held_failed_and_backing_off(self) -> None:
        outbox = self.h.service._outbox  # noqa: SLF001
        self.assertIsNone(next_work(outbox.queue(), "9999"))
        outbox.enqueue(NewEntry(journal_date="2026-10-07", kind="note", content="", plain_content="",
                                fingerprint="", event_at=self.h.clock(), state="held", candidate_text="x"))
        self.assertIsNone(next_work(outbox.queue(), "9999"))


if __name__ == "__main__":
    unittest.main()
