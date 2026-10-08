from __future__ import annotations

from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest import mock

from sam_runtime.domains.generated_ui import ArtifactWatcher, GeneratedUiClient
from sam_runtime.domains.generated_ui import watcher as watcher_module
from sam_runtime.storage.announcements import AnnouncementRepository
from sam_runtime.storage.database import RuntimeDatabase

from generated_ui_fakes import FakeGeneratedUiBridge, artifact_id_for
from mac_fakes import make_mac

CONVERSATION = "c_" + "5" * 20
SESSION = "d" * 24


class Clock:
    def __init__(self) -> None:
        self.now = 1_000_000.0

    def __call__(self) -> float:
        return self.now


class ArtifactWatcherTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.fake = FakeGeneratedUiBridge()
        self.store, _mac = make_mac(self.database)
        self.store.save(self.fake.url, self.fake.token)
        self.client = GeneratedUiClient(self.store)
        self.announcements = AnnouncementRepository(self.database)
        self.clock = Clock()
        self.watcher = ArtifactWatcher(self.client, self.announcements, self.database, clock=self.clock)

    def tearDown(self) -> None:
        self.watcher.stop()
        self.fake.close()
        self.directory.cleanup()

    def start_artifact(self, request_id: str = "rt:x:1") -> str:
        self.client.generate(request_id=request_id, prompt="a chart", data=None, conversation_id=CONVERSATION)
        artifact_id = artifact_id_for(request_id)
        self.assertTrue(self.watcher.track(artifact_id, conversation_id=CONVERSATION, voice_session_id=SESSION))
        return artifact_id

    def advance(self, seconds: float) -> int:
        self.clock.now += seconds
        return self.watcher.poll_once()

    def test_ready_artifact_is_announced_once_with_its_picture(self) -> None:
        artifact_id = self.start_artifact()
        self.assertEqual(0, self.watcher.poll_once(), "the first poll waits a moment")
        self.assertEqual(0, self.advance(3.5), "still generating")
        self.fake.ready(artifact_id)
        self.assertEqual(1, self.advance(2.5))
        (item,) = self.announcements.after(0)
        self.assertEqual("ui.generated", item.kind)
        self.assertEqual("Meetings this week", item.title)
        self.assertEqual("“Meetings this week” is ready.", item.text)
        self.assertEqual({
            "artifactId": artifact_id, "conversationId": CONVERSATION, "voiceSessionId": SESSION,
            "title": "Meetings this week", "summary": "Thursday is the busiest day with 6 meetings.",
            "imageBlobId": "sha256:" + "a" * 64, "width": 960, "height": 1024, "mime": "image/jpeg",
            "imagePath": f"/v1/ui/artifacts/{artifact_id}/image",
        }, item.payload)
        self.assertEqual([], self.watcher.pending())
        self.assertEqual(0, self.advance(10))
        self.assertEqual(1, len(self.announcements.after(0)))

    def test_failed_and_lost_artifacts_are_announced_as_failures(self) -> None:
        failed = self.start_artifact("rt:x:1")
        self.fake.fail(failed, "claude_busy")
        lost = "ui_" + "9" * 24
        self.watcher.track(lost, conversation_id=None, voice_session_id=None)
        self.assertEqual(2, self.advance(5))
        items = {item.payload["artifactId"]: item for item in self.announcements.after(0)}
        self.assertEqual("ui.failed", items[failed].kind)
        self.assertEqual({"code": "claude_busy", "message": "Making the visual took too long."},
                         items[failed].payload["error"], "the app shows error.message")
        self.assertEqual("Making the visual took too long.", items[failed].payload["message"])
        self.assertTrue(items[failed].text.startswith("I couldn't make that visual."))
        self.assertEqual("artifact_not_found", items[lost].payload["error"]["code"])
        self.assertEqual("No such visual.", items[lost].payload["error"]["message"])

    def test_unreachable_mac_keeps_trying_then_gives_up(self) -> None:
        artifact_id = self.start_artifact()
        self.store.save("http://127.0.0.1:9", self.fake.token)
        self.assertEqual(0, self.advance(5))
        self.assertEqual([artifact_id], self.watcher.pending())
        self.assertEqual(1, self.advance(watcher_module.GIVE_UP_SECONDS + 5))
        (item,) = self.announcements.after(0)
        self.assertEqual(("ui.failed", "timeout"), (item.kind, item.payload["error"]["code"]))

    def test_pending_watches_survive_a_runtime_restart(self) -> None:
        artifact_id = self.start_artifact()
        restarted = ArtifactWatcher(self.client, self.announcements, self.database, clock=self.clock)
        self.assertEqual(0, restarted.poll_once())
        self.fake.ready(artifact_id)
        self.clock.now += 2
        self.assertEqual(1, restarted.poll_once())
        self.assertEqual(artifact_id, self.announcements.after(0)[0].payload["artifactId"])
        again = ArtifactWatcher(self.client, self.announcements, self.database, clock=self.clock)
        again.start()
        again.stop()
        self.assertEqual([], again.pending(), "announced artifacts are not followed again after a restart")

    def test_a_failed_announcement_is_retried_not_dropped(self) -> None:
        artifact_id = self.start_artifact()
        self.fake.ready(artifact_id)
        with mock.patch.object(self.announcements, "publish",
                               side_effect=sqlite3.OperationalError("database is locked")):
            self.assertEqual(0, self.advance(5))
        self.assertEqual(0, len(self.announcements.after(0)))
        self.assertEqual([artifact_id], self.watcher.pending(), "still followed in memory")
        restarted = ArtifactWatcher(self.client, self.announcements, self.database, clock=self.clock)
        restarted._load()  # noqa: SLF001
        self.assertEqual([artifact_id], restarted.pending(), "and still stored for a runtime restart")
        self.assertEqual(1, self.advance(watcher_module.SLOW_POLL_SECONDS + 1))
        self.assertEqual(["ui.generated"], [item.kind for item in self.announcements.after(0)])
        self.assertEqual([], self.watcher.pending())
        again = ArtifactWatcher(self.client, self.announcements, self.database, clock=self.clock)
        again._load()  # noqa: SLF001
        self.assertEqual([], again.pending(), "forgotten on disk once announced")

    def test_a_watch_tracked_before_the_stored_list_loads_does_not_erase_it(self) -> None:
        stored = self.start_artifact("rt:x:1")
        fresh = ArtifactWatcher(self.client, self.announcements, self.database, clock=self.clock)
        with mock.patch.object(watcher_module.ArtifactWatcher, "_load", lambda _self: None):
            self.assertTrue(fresh.track("ui_" + "7" * 24, conversation_id=None, voice_session_id=None))
        fresh._load()  # noqa: SLF001
        self.assertEqual(sorted([stored, "ui_" + "7" * 24]), fresh.pending())
        check = ArtifactWatcher(self.client, self.announcements, self.database, clock=self.clock)
        check._load()  # noqa: SLF001
        self.assertEqual(sorted([stored, "ui_" + "7" * 24]), check.pending())

    def test_worker_thread_announces_in_the_background(self) -> None:
        watcher = ArtifactWatcher(self.client, self.announcements, self.database)
        with mock.patch.object(watcher_module, "FIRST_POLL_SECONDS", 0.05):
            watcher.start()
            try:
                self.client.generate(request_id="rt:bg:1", prompt="a chart", data=None, conversation_id=None)
                artifact_id = artifact_id_for("rt:bg:1")
                self.fake.ready(artifact_id)
                watcher.track(artifact_id, conversation_id=None, voice_session_id=SESSION)
                items, _cursor = self.announcements.next(0, wait_seconds=5)
            finally:
                watcher.stop()
        self.assertEqual(["ui.generated"], [item.kind for item in items])

    def test_the_watch_list_is_bounded(self) -> None:
        for index in range(watcher_module.MAX_PENDING + 3):
            self.clock.now += 1
            self.watcher.track("ui_" + f"{index:024x}", conversation_id=None, voice_session_id=None)
        self.assertEqual(watcher_module.MAX_PENDING, len(self.watcher.pending()))
        self.assertNotIn("ui_" + f"{0:024x}", self.watcher.pending(), "the oldest watch makes room")
        self.assertFalse(self.watcher.track("not-an-id", conversation_id=None, voice_session_id=None))


if __name__ == "__main__":
    unittest.main()
