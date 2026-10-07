from __future__ import annotations

from pathlib import Path
import tempfile
import threading
import time
import unittest

from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.storage.announcements import RETAIN, AnnouncementRepository
from sam_runtime.storage.database import RuntimeDatabase


class AnnouncementOutboxTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.events = RuntimeEventStream()
        self.outbox = AnnouncementRepository(
            self.database, on_publish=lambda item: self.events.publish(item.kind, item.view())
        )

    def tearDown(self) -> None:
        self.directory.cleanup()

    def test_publish_then_next_returns_items_and_cursor(self) -> None:
        first = self.outbox.publish("t3.thread.finished", "Fix login", "“Fix login” in Workbench finished.", {"threadId": "t-1"})
        second = self.outbox.publish("t3.thread.error", "Deploy", "“Deploy” in Workbench hit an error.", {"threadId": "t-2"})
        items, cursor = self.outbox.next(0, wait_seconds=0)
        self.assertEqual([first.announcement_id, second.announcement_id], [item.announcement_id for item in items])
        self.assertEqual(second.announcement_id, cursor)
        self.assertEqual(
            {"id": first.announcement_id, "kind": "t3.thread.finished", "title": "Fix login",
             "text": "“Fix login” in Workbench finished.", "payload": {"threadId": "t-1"},
             "createdAt": first.created_at},
            items[0].view(),
        )
        items, cursor = self.outbox.next(cursor, wait_seconds=0)
        self.assertEqual(((), second.announcement_id), (items, cursor))

    def test_long_poll_wakes_on_publish_and_times_out_quietly(self) -> None:
        latest = self.outbox.latest_id()
        started = time.monotonic()
        items, cursor = self.outbox.next(latest, wait_seconds=0.3)
        self.assertEqual(((), latest), (items, cursor))
        self.assertGreaterEqual(time.monotonic() - started, 0.25)

        threading.Timer(0.2, lambda: self.outbox.publish("t3.thread.finished", "A", "A finished.", {})).start()
        started = time.monotonic()
        items, cursor = self.outbox.next(latest, wait_seconds=5)
        elapsed = time.monotonic() - started
        self.assertEqual(1, len(items))
        self.assertLess(elapsed, 2.0)
        self.assertEqual(items[0].announcement_id, cursor)

    def test_ack_channels_and_unknown_ids(self) -> None:
        item = self.outbox.publish("t3.thread.finished", "A", "A finished.", {})
        self.assertTrue(self.outbox.ack(item.announcement_id, "voice"))
        self.assertTrue(self.outbox.ack(item.announcement_id, "seen"))
        self.assertEqual(("voice", "seen"), self.outbox.get(item.announcement_id).acked)
        self.assertFalse(self.outbox.ack(999_999, "voice"))
        with self.assertRaises(ValueError):
            self.outbox.ack(item.announcement_id, "sms")

    def test_reset_cursor_unacked_replay_and_retention(self) -> None:
        for index in range(RETAIN + 5):
            self.outbox.publish("demo.kind", f"T{index}", f"Item {index}.", {"i": index})
        latest = self.outbox.latest_id()
        items, cursor = self.outbox.next(latest + 50, wait_seconds=5)
        self.assertEqual(((), latest), (items, cursor))
        with self.database.connect() as connection:
            count = connection.execute("SELECT COUNT(*) FROM announcements").fetchone()[0]
        self.assertEqual(RETAIN, count)
        replay, cursor = self.outbox.next(None)
        self.assertEqual(20, len(replay))
        self.assertEqual(latest, replay[-1].announcement_id)
        self.assertEqual(latest, cursor)

    def test_publish_mirrors_to_runtime_event_stream(self) -> None:
        _, subscriber = self.events.subscribe()
        self.outbox.publish("t3.thread.needs_approval", "A", "A needs approval.", {"threadId": "t"})
        event = self.events.next_event(subscriber, timeout=1)
        self.assertEqual("t3.thread.needs_approval", event.event_type)
        self.assertEqual("A needs approval.", event.payload["text"])


if __name__ == "__main__":
    unittest.main()
