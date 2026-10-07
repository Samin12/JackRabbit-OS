from __future__ import annotations

import time
import unittest
from unittest import mock

from sam_runtime.domains.t3.service import T3Service
from sam_runtime.domains.t3.status import (
    DEFAULT_APPROVAL_OPTIONS,
    pending_requests,
    status_label,
    thread_status,
)
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes

from t3_fixtures import (
    PAIRING_CODE,
    PROJECT_SIDE,
    FakeT3Server,
    StubBridge,
    approval_requested,
    approval_resolved,
    input_requested,
    make_service,
    thread,
)


class T3StatusDerivationTest(unittest.TestCase):
    def test_status_follows_t3_sidebar_order(self) -> None:
        cases = {
            "needs-approval": thread("a", "A", approvals=True, user_input=True, session_status="running"),
            "needs-input": thread("b", "B", user_input=True, session_status="running"),
            "working": thread("c", "C", session_status="running", turn_state="running"),
            "error": thread("d", "D", session_status="error"),
            "done": thread("e", "E"),
        }
        for expected, item in cases.items():
            with self.subTest(expected=expected):
                self.assertEqual(expected, thread_status(item))
        self.assertEqual("working", thread_status(thread("f", "F", session_status="starting", turn_state="running")))
        self.assertEqual("working", thread_status(thread("g", "G", background="monitoring")))
        self.assertEqual("error", thread_status(thread("h", "H", turn_state="error", session_status="stopped")))
        self.assertEqual("done", thread_status(thread("i", "I", turn_state="error", session_status="stopped", settled=True)))
        self.assertEqual("done", thread_status(thread("j", "J", session_status=None, turn_state=None)))

    def test_labels_are_short_and_specific(self) -> None:
        self.assertEqual("Starting", status_label(thread("a", "A", session_status="starting"), "working"))
        self.assertEqual("Monitoring", status_label(thread("b", "B", background="monitoring"), "working"))
        planned = thread("c", "C", session_status="running", plan={"step": "Run tests", "completedSteps": 2, "totalSteps": 5})
        self.assertEqual("Step 3 of 5", status_label(planned, "working"))
        self.assertEqual("Stopped", status_label(thread("d", "D", turn_state="interrupted"), "done"))
        self.assertEqual("New", status_label(thread("e", "E", turn_state=None), "done"))


class T3PendingRequestsTest(unittest.TestCase):
    def test_requests_open_and_close_like_t3(self) -> None:
        activities = [
            approval_requested("r-1", at="2026-10-07T11:00:00.000Z"),
            approval_requested("r-2", at="2026-10-07T11:01:00.000Z", kind="file-change", detail_text="Changed files"),
            approval_resolved("r-1"),
            {"kind": "approval.requested", "createdAt": "2026-10-07T11:02:00.000Z",
             "payload": {"requestId": "r-3", "requestType": "tool_user_input"}},
            input_requested("q-1"),
            input_requested("q-2"),
            {"kind": "provider.user-input.respond.failed", "createdAt": "2026-10-07T11:03:00.000Z",
             "payload": {"requestId": "q-2", "detail": "Stale pending user-input request"}},
            {"kind": "approval.requested", "createdAt": "2026-10-07T11:04:00.000Z",
             "payload": {"requestId": "r-1", "requestKind": "command"}},
        ]
        pending = pending_requests(activities)
        self.assertEqual(["r-2"], [item.request_id for item in pending.approvals])
        self.assertEqual("file-change", pending.approvals[0].kind)
        self.assertEqual(list(DEFAULT_APPROVAL_OPTIONS), pending.view()["approvals"][0]["options"])
        self.assertEqual(
            {"requestId": "q-1", "questions": [{
                "id": "0", "header": "Storage", "question": "Which database should I use?",
                "options": ["Use SQLite", "Use DuckDB"], "allowCustom": True, "multiSelect": False,
            }]},
            pending.view()["inputs"][0],
        )

    def test_provider_options_are_kept(self) -> None:
        activity = approval_requested("r-9")
        activity["payload"]["options"] = [{"decision": "accept", "label": "Allow"}, {"decision": "decline", "label": "Deny"}]
        pending = pending_requests([activity])
        self.assertEqual([{"decision": "accept", "label": "Allow"}, {"decision": "decline", "label": "Deny"}],
                         pending.view()["approvals"][0]["options"])


class T3SummariesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fake = FakeT3Server().__enter__()
        self.service, self.announcements, self.repository, _, self.directory = make_service()

    def tearDown(self) -> None:
        self.fake.__exit__()
        self.directory.cleanup()

    def _threads(self) -> list[dict[str, object]]:
        return [
            thread("done-old", "Old done", completed_at="2026-10-07T08:00:00.000Z"),
            thread("done-new", "New done", completed_at="2026-10-07T10:00:00.000Z"),
            thread("settled", "Settled one", completed_at="2026-10-07T11:30:00.000Z", settled=True),
            thread("working", "Busy thread", session_status="running", turn_state="running", project_id=PROJECT_SIDE,
                   plan={"step": "Run tests", "completedSteps": 1, "totalSteps": 4}),
            thread("error", "Broken thread", session_status="error"),
            thread("input", "Question thread", user_input=True),
            thread("approve", "Approval thread", approvals=True),
        ]

    def test_order_counts_projects_and_revision(self) -> None:
        self.fake.set_threads(self._threads())
        self.service.connect(self.fake.url, PAIRING_CODE)
        view = self.service.threads_view(limit=40)
        self.assertTrue(view["connected"])
        self.assertEqual(
            ["approve", "input", "working", "error", "done-new", "done-old", "settled"],
            [item["id"] for item in view["threads"]],
        )
        self.assertEqual({"needsYou": 2, "working": 1, "done": 3, "error": 1}, view["counts"])
        working = view["threads"][2]
        self.assertEqual(
            {"id": "working", "projectId": PROJECT_SIDE, "projectTitle": "Orbit Lab", "title": "Busy thread",
             "status": "working", "statusLabel": "Step 2 of 4", "unread": False, "model": "claude-opus-5-5",
             "phase": "Run tests", "progress": 0.25, "completedAt": None},
            {key: value for key, value in working.items() if key != "updatedAt"},
        )
        self.assertEqual("Workbench", view["projects"][0]["title"])
        self.assertEqual(set(view["threads"][0]), {
            "id", "projectId", "projectTitle", "title", "status", "statusLabel", "updatedAt", "completedAt",
            "unread", "model", "phase", "progress",
        })
        revision = view["revision"]
        self.service.sync_once()
        self.assertEqual(revision, self.service.threads_view()["revision"])
        changed = self._threads()
        changed[3] = thread("working", "Busy thread", project_id=PROJECT_SIDE, completed_at="2026-10-07T12:10:00.000Z")
        self.fake.set_threads(changed)
        self.service.sync_once()
        self.assertEqual(revision + 1, self.service.threads_view()["revision"])
        self.assertEqual(2, len(self.service.threads_view(limit=2)["threads"]))

    def test_unread_tracks_seen_and_history_starts_read(self) -> None:
        self.fake.set_threads(self._threads())
        self.service.connect(self.fake.url, PAIRING_CODE)
        self.assertFalse(any(item["unread"] for item in self.service.threads_view()["threads"]))
        threads = self._threads()
        threads[1] = thread("done-new", "New done", completed_at="2026-10-07T12:20:00.000Z", turn_id="turn-2")
        self.fake.set_threads(threads)
        self.service.sync_once()
        before = self.service.threads_view()
        unread = {item["id"] for item in before["threads"] if item["unread"]}
        self.assertEqual({"done-new"}, unread)
        self.service.mark_seen("done-new")
        after = self.service.threads_view()
        self.assertFalse(any(item["unread"] for item in after["threads"]))
        self.assertGreater(after["revision"], before["revision"])

    def test_revision_never_repeats_after_a_runtime_restart(self) -> None:
        # The app skips re-rendering when the revision equals the one it holds.
        self.fake.set_threads(self._threads())
        self.service.connect(self.fake.url, PAIRING_CODE)
        held = self.service.threads_view()["revision"]
        later = time.time() + 30
        with mock.patch("sam_runtime.domains.t3.service.time.time", return_value=later):
            restarted = T3Service(self.repository, ConnectionCredentialEnvelopes(StubBridge()))
            restarted.sync_once()
            self.assertGreater(restarted.threads_view()["revision"], held)

    def test_not_connected_view_and_status(self) -> None:
        view = self.service.threads_view()
        self.assertEqual(
            {"connected": False, "counts": {"needsYou": 0, "working": 0, "done": 0, "error": 0}, "projects": [], "threads": []},
            {key: view[key] for key in ("connected", "counts", "projects", "threads")},
        )
        self.assertEqual(
            {"connected": False, "serverUrl": None, "label": None, "healthState": "unconfigured",
             "detail": None, "expiresAt": None, "lastSyncAt": None},
            self.service.status_view(),
        )


if __name__ == "__main__":
    unittest.main()
