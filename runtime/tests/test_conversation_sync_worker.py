"""Hop 2 sender (runtime -> Mac bridge): ordering, retry and backoff, idempotent resends, caps and purge."""

from __future__ import annotations

import socket
import unittest

from conversation_sync_fakes import TINY_JPEG, SyncHarness

from sam_runtime.domains.conversation_sync import BACKOFF_SECONDS
from sam_runtime.domains.conversation_sync.events import blob_id_for
from sam_runtime.domains.conversation_sync.outbox import RETENTION_SECONDS

CONV = "c_0123456789abcdef0123"
SESSION = "ab" * 12


def ev(seq: int, kind: str = "message.user", **fields: object) -> dict[str, object]:
    value: dict[str, object] = {"id": f"{CONV}:{seq}", "seq": seq, "type": kind, "at": 1_760_000_000_000 + seq,
                                "text": f"line {seq}"}
    value.update(fields)
    return value


class ConversationSyncWorkerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = SyncHarness()
        self.addCleanup(self.h.close)
        self.service = self.h.service
        self.worker = self.service.worker

    def ingest(self, *events: dict[str, object]) -> None:
        self.service.ingest_events({"conversationId": CONV, "sessionId": SESSION, "events": list(events)})

    def states(self) -> list[str]:
        return [str(row["state"]) for row in self.h.rows()]

    def test_blobs_go_before_the_events_that_reference_them_and_their_bytes_are_dropped(self) -> None:
        blob = self.service.ingest_blob(TINY_JPEG, "image/jpeg", CONV)["blobId"]
        self.ingest(ev(1), ev(2, "image", blobId=blob, mime="image/jpeg", source="camera"), ev(3))
        self.worker.drain()
        self.assertEqual([("blob", blob), ("events", [f"{CONV}:1", f"{CONV}:2", f"{CONV}:3"])], self.h.bridge.requests())
        self.assertEqual(("image/jpeg", TINY_JPEG), self.h.bridge.blobs[blob])
        self.assertEqual(["sent"] * 3, self.states())
        (row,) = self.h.blob_rows()
        self.assertEqual(("sent", b""), (row["state"], bytes(row["data"])))
        self.assertEqual(f"Bearer {self.h.bridge.token}", self.h.bridge.authorization[-1])
        status = self.service.status_view()
        self.assertEqual((0, 3, None), (status["pending"], status["sent"], status["lastError"]))
        self.assertIsNotNone(status["lastOkAt"])

    def test_an_image_event_waits_for_a_blob_that_has_not_arrived_yet(self) -> None:
        blob = blob_id_for(TINY_JPEG)
        self.service.ingest_blob(TINY_JPEG, "image/jpeg", CONV)
        self.h.bridge.failures.append("503")  # the blob upload fails once: its event waits
        self.ingest(ev(1), ev(2, "image", blobId=blob, mime="image/jpeg", source="camera"))
        self.worker.drain()
        self.assertEqual([], self.h.bridge.event_ids())
        self.h.clock.advance(BACKOFF_SECONDS[0])
        self.worker.drain()
        self.assertEqual(["blob", "events"], [kind for kind, _ in self.h.bridge.requests()])
        self.assertEqual([f"{CONV}:1", f"{CONV}:2"], self.h.bridge.event_ids())

    def test_backoff_grows_2_5_15_60_300_and_recovers(self) -> None:
        self.ingest(ev(1))
        expected = list(BACKOFF_SECONDS) + [BACKOFF_SECONDS[-1]]
        self.assertEqual([2, 5, 15, 60, 300], list(BACKOFF_SECONDS))
        for wait in expected:
            self.h.bridge.failures.append("503")
            self.worker.drain()
            self.assertEqual(["pending"], self.states())
            retry_at = self.service._blocked_until  # noqa: SLF001
            self.assertAlmostEqual(self.h.clock.now + wait, retry_at, delta=0.01)
            self.assertEqual(0, self.worker.drain(), "nothing is sent while backing off")
            self.h.clock.advance(wait)
        self.assertEqual("bridge_http_503", self.service.status_view()["lastError"])
        self.worker.drain()
        self.assertEqual(["sent"], self.states())
        self.assertEqual(0, self.service._failures)  # noqa: SLF001
        self.assertIsNone(self.service.status_view()["lastError"])

    def test_an_uncertain_send_is_simply_repeated_and_the_mac_dedupes_it(self) -> None:
        self.ingest(ev(1), ev(2))
        self.h.bridge.failures.append("drop")  # stored on the Mac, but the answer never came back
        self.worker.drain()
        self.assertEqual(["pending", "pending"], self.states())
        self.assertEqual("bridge_connection_lost", self.h.rows()[0]["last_error"])
        self.h.clock.advance(BACKOFF_SECONDS[0])
        self.worker.drain()
        self.assertEqual(["sent", "sent"], self.states())
        self.assertEqual([f"{CONV}:1", f"{CONV}:2"], self.h.bridge.event_ids(), "each event exactly once on the Mac")
        self.assertEqual(2, len([kind for kind, _ in self.h.bridge.requests() if kind == "events"]))

    def test_unreachable_and_unauthorized_bridges_back_off_and_a_mac_answer_expedites(self) -> None:
        self.ingest(ev(1))
        self.h.bridge.failures.append("401")
        self.worker.drain()
        self.assertEqual("bridge_unauthorized", self.h.rows()[0]["last_error"])
        self.assertEqual(0, self.worker.drain())
        self.h.store.note_reachable()  # e.g. a mac_status call just worked
        self.worker.drain()
        self.assertEqual(["sent"], self.states())
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            closed_port = probe.getsockname()[1]
        self.h.store.save(f"http://127.0.0.1:{closed_port}", self.h.bridge.token)
        self.ingest(ev(2))
        self.worker.drain()
        self.assertEqual("bridge_unreachable", self.h.rows()[-1]["last_error"])
        self.h.store.save(self.h.bridge.url, self.h.bridge.token)  # reconfiguring also expedites
        self.worker.drain()
        self.assertEqual(["sent", "sent"], self.states())

    def test_an_outdated_bridge_without_sync_routes_is_a_bridge_wide_backoff(self) -> None:
        self.ingest(ev(1))
        self.h.bridge.failures.append("404")
        self.worker.drain()
        self.assertEqual(("pending", "bridge_outdated"), (self.h.rows()[0]["state"], self.h.rows()[0]["last_error"]))

    def test_a_refused_event_is_isolated_by_halving_the_batch(self) -> None:
        self.h.bridge.reject_marker = "poison"
        self.ingest(*[ev(seq) for seq in range(1, 8)], ev(8, text="poison pill"), ev(9))
        self.worker.drain(limit=50)
        rows = {row["event_id"]: row for row in self.h.rows()}
        self.assertEqual(("failed", "invalid_events"), (rows[f"{CONV}:8"]["state"], rows[f"{CONV}:8"]["last_error"]))
        self.assertEqual({f"{CONV}:{seq}" for seq in (1, 2, 3, 4, 5, 6, 7, 9)}, set(self.h.bridge.event_ids()))
        self.assertEqual(8, self.service.status_view()["sent"])
        self.assertEqual(1, self.service.status_view()["failed"])

    def test_events_go_in_event_time_order_in_bounded_batches(self) -> None:
        self.ingest(ev(3, at=1_760_000_000_300), ev(1, at=1_760_000_000_100), ev(2, at=1_760_000_000_200))
        self.ingest(*[ev(seq, text="x" * 60_000, at=1_760_000_001_000 + seq) for seq in range(10, 14)])
        self.worker.drain()
        batches = [ids for kind, ids in self.h.bridge.requests() if kind == "events"]
        self.assertEqual([f"{CONV}:1", f"{CONV}:2", f"{CONV}:3"], batches[0][:3])
        self.assertGreater(len(batches), 1, "~240 KB of events do not fit in one 192 KB batch")
        self.assertEqual(7, sum(len(batch) for batch in batches))

    def test_nothing_is_sent_while_sync_is_off(self) -> None:
        self.ingest(ev(1))
        self.service.save_settings({"enabled": False})
        self.assertEqual(0, self.worker.drain())
        self.service.save_settings({"enabled": True})
        self.worker.drain()
        self.assertEqual(["sent"], self.states())

    def test_interrupted_sends_are_recovered_at_start(self) -> None:
        self.ingest(ev(1))
        self.service.ingest_blob(TINY_JPEG, "image/jpeg", CONV)
        repository = self.service.repository
        repository.mark_events_sending([f"{CONV}:1"])
        repository.mark_blob_sending(blob_id_for(TINY_JPEG))
        self.assertEqual(2, repository.recover_interrupted())
        self.assertEqual(["pending"], self.states())
        self.worker.drain()
        self.assertEqual(["sent"], self.states())

    def test_purge_and_caps(self) -> None:
        self.ingest(ev(1))
        self.worker.drain()
        self.h.clock.advance(RETENTION_SECONDS + 1)
        self.service.repository.purge()
        self.assertEqual([], self.h.rows(), "sent rows are kept 7 days")
        self.service.save_settings({"enabled": False})  # keep everything pending
        self.service.save_settings({"enabled": True})
        self.ingest(*[ev(seq, "message.assistant.delta", messageId=f"a_{seq}") for seq in range(10, 16)],
                    *[ev(seq) for seq in range(20, 24)])
        dropped = self.service.repository.enforce_event_cap(limit=6)
        self.assertEqual(4, dropped)
        kinds = [str(row["kind"]) for row in self.h.rows()]
        self.assertEqual(["message.assistant.delta"] * 2 + ["message.user"] * 4, kinds, "drafts go first")
        self.assertEqual(3, self.service.repository.enforce_event_cap(limit=3))
        self.assertEqual([f"{CONV}:21", f"{CONV}:22", f"{CONV}:23"], [row["event_id"] for row in self.h.rows()])
        self.service.ingest_blob(TINY_JPEG, "image/jpeg", CONV)
        self.assertEqual(1, self.service.repository.enforce_blob_cap(limit=10))
        (blob,) = self.h.blob_rows()
        self.assertEqual(("failed", b""), (blob["state"], bytes(blob["data"])))
        self.service.ingest_blob(TINY_JPEG, "image/jpeg", CONV)
        self.assertEqual("pending", self.h.blob_rows()[0]["state"], "a dropped image is taken again when re-sent")

    def test_the_real_thread_delivers_without_inline_draining(self) -> None:
        self.service.start()
        self.assertEqual("sam-conversation-sync", self.worker._thread.name)  # noqa: SLF001
        self.ingest(ev(1), ev(2))
        self.assertTrue(self.worker.wait_until(lambda: len(self.h.bridge.event_ids()) == 2, timeout=10))
        self.assertTrue(self.worker.wait_until(lambda: self.states() == ["sent", "sent"], timeout=10))


if __name__ == "__main__":
    unittest.main()
