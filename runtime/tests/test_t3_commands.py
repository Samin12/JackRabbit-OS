from __future__ import annotations

import unittest

from sam_runtime.domains.t3.commands import T3CommandBuilder
from sam_runtime.domains.t3.service import T3DispatchFailed, T3InvalidRequest, T3RequestNotPending

from t3_fixtures import (
    PAIRING_CODE,
    PROJECT_MAIN,
    PROJECT_SCRATCH,
    PROJECT_SIDE,
    FakeT3Server,
    FixedIds,
    approval_requested,
    detail,
    input_requested,
    make_service,
    thread,
)


NOW = "2026-10-07T12:00:00.000Z"


def _builder() -> T3CommandBuilder:
    return T3CommandBuilder(new_id=FixedIds(), now=lambda: NOW)


def _id(n: int) -> str:
    return f"00000000-0000-4000-8000-{n:012d}"


class T3CommandShapeTest(unittest.TestCase):
    def test_create_thread_shape(self) -> None:
        command = _builder().create_thread(
            thread_id="thread-1", project_id="project-1", title="Check the build",
            model_selection={"instanceId": "claudeAgent", "model": "claude-opus-5-5"},
            runtime_mode="full-access",
        )
        self.assertEqual(
            {"type": "thread.create", "commandId": _id(1), "threadId": "thread-1", "projectId": "project-1",
             "title": "Check the build", "modelSelection": {"instanceId": "claudeAgent", "model": "claude-opus-5-5"},
             "runtimeMode": "full-access", "interactionMode": "default", "branch": None, "worktreePath": None,
             "createdAt": NOW},
            command,
        )

    def test_turn_start_shape(self) -> None:
        command = _builder().turn_start(
            thread_id="thread-1", text="Run the tests", runtime_mode="approval-required",
            model_selection={"instanceId": "claudeAgent", "model": "claude-opus-5-5"}, title_seed="Run the tests",
        )
        self.assertEqual(
            {"type": "thread.turn.start", "commandId": _id(1), "threadId": "thread-1",
             "message": {"messageId": _id(2), "role": "user", "text": "Run the tests", "attachments": []},
             "modelSelection": {"instanceId": "claudeAgent", "model": "claude-opus-5-5"},
             "titleSeed": "Run the tests", "runtimeMode": "approval-required", "interactionMode": "default",
             "createdAt": NOW},
            command,
        )
        plain = _builder().turn_start(thread_id="thread-1", text="More", runtime_mode="full-access")
        self.assertNotIn("modelSelection", plain)
        self.assertNotIn("titleSeed", plain)

    def test_respond_interrupt_and_archive_shapes(self) -> None:
        builder = _builder()
        self.assertEqual(
            {"type": "thread.approval.respond", "commandId": _id(1), "threadId": "t", "requestId": "r",
             "decision": "accept", "createdAt": NOW},
            builder.approval_respond(thread_id="t", request_id="r", decision="accept"),
        )
        self.assertEqual(
            {"type": "thread.user-input.respond", "commandId": _id(2), "threadId": "t", "requestId": "q",
             "answers": {"0": "Use SQLite"}, "createdAt": NOW},
            builder.user_input_respond(thread_id="t", request_id="q", answers={"0": "Use SQLite"}),
        )
        self.assertEqual(
            {"type": "thread.turn.interrupt", "commandId": _id(3), "threadId": "t", "turnId": "turn-9", "createdAt": NOW},
            builder.interrupt(thread_id="t", turn_id="turn-9"),
        )
        self.assertEqual(
            {"type": "thread.turn.interrupt", "commandId": _id(4), "threadId": "t", "createdAt": NOW},
            builder.interrupt(thread_id="t"),
        )
        self.assertEqual({"type": "thread.archive", "commandId": _id(5), "threadId": "t"}, builder.archive(thread_id="t"))
        with self.assertRaises(ValueError):
            builder.approval_respond(thread_id="t", request_id="r", decision="maybe")


class T3ServiceCommandTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fake = FakeT3Server().__enter__()
        self.service, _, _, _, self.directory = make_service()

    def tearDown(self) -> None:
        self.fake.__exit__()
        self.directory.cleanup()

    def _connect(self, threads: list[dict[str, object]]) -> None:
        self.fake.set_threads(threads)
        self.service.connect(self.fake.url, PAIRING_CODE)

    def test_create_uses_most_recent_project_and_its_latest_thread_settings(self) -> None:
        self._connect([
            thread("old", "Old", project_id=PROJECT_MAIN, completed_at="2026-10-07T08:00:00.000Z", model="gpt-old", runtime_mode="auto"),
            thread("recent", "Recent", project_id=PROJECT_SIDE, completed_at="2026-10-07T11:00:00.000Z", model="claude-sonnet-5", runtime_mode="approval-required"),
            thread("scratch", "Scratch", project_id=PROJECT_SCRATCH, completed_at="2026-10-07T11:50:00.000Z"),
        ])
        created = self.service.create_thread("Add a dark mode toggle to settings")
        create, turn = self.fake.dispatched
        thread_id = created["threadId"]
        self.assertEqual("Orbit Lab", created["projectTitle"])
        self.assertEqual(
            {"type": "thread.create", "threadId": thread_id, "projectId": PROJECT_SIDE,
             "title": "Add a dark mode toggle to settings",
             "modelSelection": {"instanceId": "claudeAgent", "model": "claude-sonnet-5", "options": [{"id": "effort", "value": "high"}]},
             "runtimeMode": "approval-required", "interactionMode": "default", "branch": None, "worktreePath": None,
             "createdAt": NOW},
            {key: value for key, value in create.items() if key != "commandId"},
        )
        self.assertEqual("thread.turn.start", turn["type"])
        self.assertEqual(thread_id, turn["threadId"])
        self.assertEqual("Add a dark mode toggle to settings", turn["message"]["text"])
        self.assertEqual([], turn["message"]["attachments"])
        self.assertEqual("approval-required", turn["runtimeMode"])
        self.assertEqual(create["modelSelection"], turn["modelSelection"])
        self.assertNotEqual(create["commandId"], turn["commandId"])

    def test_create_falls_back_to_default_model_and_named_project(self) -> None:
        self._connect([thread("recent", "Recent", project_id=PROJECT_SIDE)])
        created = self.service.create_thread("Write release notes", title="Release notes", project="workbench", runtime_mode="approval-required")
        create = self.fake.dispatched[0]
        self.assertEqual(PROJECT_MAIN, created["projectId"])
        self.assertEqual({"instanceId": "claudeAgent", "model": "claude-opus-5-5"}, create["modelSelection"])
        self.assertEqual("approval-required", create["runtimeMode"])
        self.assertEqual("Release notes", create["title"])
        with self.assertRaises(T3InvalidRequest):
            self.service.create_thread("x", project="nonexistent galaxy")
        with self.assertRaises(T3InvalidRequest):
            self.service.create_thread("   ")

    def test_failed_first_turn_reports_created_thread(self) -> None:
        self._connect([thread("recent", "Recent")])
        self.fake.fail_dispatch_types.add("thread.turn.start")
        with self.assertRaises(T3DispatchFailed) as caught:
            self.service.create_thread("Do it")
        self.assertIsNotNone(caught.exception.thread_id)

    def test_send_message_keeps_thread_modes(self) -> None:
        self._connect([thread("t-1", "Recent", runtime_mode="auto-accept-edits")])
        self.service.send_message("t-1", "Also update the docs")
        command = self.fake.dispatched[-1]
        self.assertEqual(
            {"type": "thread.turn.start", "threadId": "t-1",
             "message": {"role": "user", "text": "Also update the docs", "attachments": []},
             "runtimeMode": "auto-accept-edits", "interactionMode": "default", "createdAt": NOW},
            {**{key: value for key, value in command.items() if key not in {"commandId", "message"}},
             "message": {key: value for key, value in command["message"].items() if key != "messageId"}},
        )

    def test_interrupt_uses_active_turn_and_skips_idle_threads(self) -> None:
        self._connect([
            thread("busy", "Busy", session_status="running", turn_state="running", active_turn="turn-active"),
            thread("idle", "Idle"),
        ])
        self.assertTrue(self.service.interrupt("busy")["wasWorking"])
        self.assertEqual({"type": "thread.turn.interrupt", "threadId": "busy", "turnId": "turn-active", "createdAt": NOW},
                         {key: value for key, value in self.fake.dispatched[-1].items() if key != "commandId"})
        count = len(self.fake.dispatched)
        self.assertFalse(self.service.interrupt("idle")["wasWorking"])
        self.assertEqual(count, len(self.fake.dispatched))

    def test_approval_and_input_responses_are_validated_against_pending(self) -> None:
        shell_thread = thread("ask", "Ask", approvals=True)
        self._connect([shell_thread])
        self.fake.set_detail("ask", detail(shell_thread, activities=[approval_requested("req-1"), input_requested("q-1")]))
        self.service.respond_approval("ask", "req-1", "accept")
        self.assertEqual(
            {"type": "thread.approval.respond", "threadId": "ask", "requestId": "req-1", "decision": "accept", "createdAt": NOW},
            {key: value for key, value in self.fake.dispatched[-1].items() if key != "commandId"},
        )
        with self.assertRaises(T3RequestNotPending):
            self.service.respond_approval("ask", "req-gone", "accept")
        self.service.respond_input("ask", "q-1", {"0": "sqlite"})
        self.assertEqual({"0": "Use SQLite"}, self.fake.dispatched[-1]["answers"])
        self.service.respond_input("ask", "q-1", {"0": "second"})
        self.assertEqual({"0": "Use DuckDB"}, self.fake.dispatched[-1]["answers"])
        self.service.respond_input("ask", "q-1", {"0": "Something custom"})
        self.assertEqual({"0": "Something custom"}, self.fake.dispatched[-1]["answers"])

    def test_multi_select_and_closed_questions(self) -> None:
        shell_thread = thread("ask", "Ask", user_input=True)
        self._connect([shell_thread])
        self.fake.set_detail("ask", detail(shell_thread, activities=[
            input_requested("multi", options=("Red", "Green", "Blue"), multi=True, allow_custom=False),
        ]))
        self.service.respond_input("ask", "multi", {"0": ["red", "Blue"]})
        self.assertEqual({"0": ["Red", "Blue"]}, self.fake.dispatched[-1]["answers"])
        with self.assertRaises(T3InvalidRequest):
            self.service.respond_input("ask", "multi", {"0": "Purple"})

    def test_answers_send_option_values_and_keep_the_users_own_words(self) -> None:
        shell_thread = thread("ask", "Ask", user_input=True)
        self._connect([shell_thread])
        valued = input_requested("pick", options=("Allow once", "Always allow"), allow_custom=False)
        valued["payload"]["questions"][0]["options"] = [
            {"label": "Allow once", "description": "", "value": "opt-once"},
            {"label": "Always allow", "description": "", "value": "opt-always"},
        ]
        self.fake.set_detail("ask", detail(shell_thread, activities=[
            valued,
            input_requested("count", options=("1", "3", "5")),
            input_requested("confirm", options=("Yes", "No")),
        ]))
        pending = self.service.thread_view("ask")["pending"]["inputs"][0]["questions"][0]
        self.assertEqual(["Allow once", "Always allow"], pending["options"])

        def answer(request_id: str, value: str) -> object:
            self.service.respond_input("ask", request_id, {"0": value})
            return self.fake.dispatched[-1]["answers"]["0"]

        # T3 expects option.value ?? option.label, like its own web client.
        self.assertEqual("opt-always", answer("pick", "always allow"))
        self.assertEqual("opt-once", answer("pick", "the first one"))
        self.assertEqual("opt-once", answer("pick", "opt-once"))
        # With numeric labels a number is the label, never an index.
        self.assertEqual("3", answer("count", "3"))
        self.assertEqual("2", answer("count", "2"))
        # Extra words are the user's answer, not just the label they contain.
        self.assertEqual("Yes, but skip the slow tests", answer("confirm", "Yes, but skip the slow tests"))
        self.assertEqual("No", answer("confirm", "no."))


if __name__ == "__main__":
    unittest.main()
