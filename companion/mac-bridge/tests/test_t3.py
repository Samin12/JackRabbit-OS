"""Pure parts of ``samrabbit_t3`` (status, pending requests, placement, the CLI's output, the token file) and the
module's command line, plus a Python 3.9 / ``python3 -I`` import check of the new bridge modules.

Run: cd companion/mac-bridge && /usr/bin/python3 -m unittest discover -s tests -q
"""

from __future__ import annotations

import ast
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(HERE))

import samrabbit_t3 as t3  # noqa: E402
from fake_t3 import FakeT3  # noqa: E402


def record(project_id: str, title: str, root: str = "", **extra: object) -> dict:
    value = {"id": project_id, "title": title, "workspaceRoot": root, "repository": None, "scratch": False}
    value.update(extra)
    return value


class StatusTest(unittest.TestCase):
    def test_mobile_status_names(self) -> None:
        def thread(**fields: object) -> dict:
            return {"latestTurn": {"state": "completed"}, "session": {"status": "ready"}, **fields}
        cases = [(thread(hasPendingApprovals=True), "needs_approval"), (thread(hasPendingUserInput=True), "needs_input"),
                 (thread(session={"status": "running"}), "working"), (thread(session={"status": "starting"}), "working"),
                 (thread(backgroundLiveness="monitoring"), "working"), (thread(session={"status": "error"}), "error"),
                 (thread(latestTurn={"state": "error"}), "error"),
                 (thread(latestTurn={"state": "error"}, settledAt="2026-10-01T00:00:00Z"), "done"),
                 (thread(latestTurn={"state": "interrupted"}), "done"), (thread(), "done"),
                 ({"session": None, "latestTurn": None}, "idle")]
        for value, expected in cases:
            with self.subTest(expected=expected, value=value):
                self.assertEqual(expected, t3.mobile_status(value))

    def test_times_are_normalized_for_swift(self) -> None:
        self.assertEqual("2026-10-07T17:18:33Z", t3.normal_time("2026-10-07T17:18:33.806Z"))
        self.assertEqual("2026-10-07T21:18:33Z", t3.normal_time("2026-10-07T17:18:33.8-04:00"))
        self.assertEqual("2026-10-07T17:18:33Z", t3.normal_time("2026-10-07T17:18:33.806123456Z"))
        self.assertIsNone(t3.normal_time("yesterday"))
        self.assertIsNone(t3.normal_time(None))

    def test_pending_requests_follow_t3(self) -> None:
        activities = [
            {"kind": "approval.requested", "createdAt": "1", "payload": {"requestId": "a", "requestType": "file_change_approval"}},
            {"kind": "approval.requested", "createdAt": "2", "payload": {"requestId": "b", "requestKind": "command",
                                                                         "options": [{"decision": "accept", "label": "Yes"}]}},
            {"kind": "approval.resolved", "createdAt": "3", "payload": {"requestId": "a"}},
            {"kind": "approval.requested", "createdAt": "4", "payload": {"requestId": "c", "requestType": "tool_user_input"}},
            {"kind": "user-input.requested", "createdAt": "5", "payload": {"requestId": "q", "responseMode": "message",
                                                                           "questions": [{"id": "0", "header": "H",
                                                                                          "question": "Which?", "options": []}]}},
            {"kind": "provider.user-input.respond.failed", "createdAt": "6",
             "payload": {"requestId": "q", "detail": "Stale pending user-input request"}},
        ]
        pending = t3.pending_requests(activities)
        self.assertEqual(["b"], [item.request_id for item in pending.approvals])
        self.assertEqual(({"decision": "accept", "label": "Yes"},), pending.approvals[0].options)
        self.assertEqual((), pending.inputs)
        view = t3.pending_view(pending)
        self.assertEqual(("approval", "Wants to run a command"), (view["kind"], view["text"]))
        self.assertIsNone(t3.pending_view(t3.PendingRequests()))

    def test_answers_map_to_option_values(self) -> None:
        question = t3.PendingQuestion("q1", "H", "Which?", ("Use SQLite", "Postgres"), True, False, ("sqlite", "Postgres"))
        request = t3.PendingInput("r", (question,), False, "")
        self.assertEqual({"q1": "sqlite"}, t3.resolve_answers(request, {"q1": "use sqlite"}))
        self.assertEqual({"q1": "Postgres"}, t3.resolve_answers(request, {"q1": "2"}))
        self.assertEqual({"q1": "sqlite"}, t3.resolve_answers(request, {"q1": "SQLite"}))
        self.assertEqual({"q1": "something else entirely"}, t3.resolve_answers(request, {"x": "something else entirely"}))
        strict = t3.PendingInput("r", (t3.PendingQuestion("q1", "H", "Which?", ("A", "B"), False, False, ("A", "B")),),
                                 False, "")
        with self.assertRaises(t3.T3Error):
            t3.resolve_answers(strict, {"q1": "C"})

    def test_ordinal_answers_pick_by_position_as_on_the_r1(self) -> None:
        question = t3.PendingQuestion("q1", "H", "Which?", ("Use SQLite", "Postgres", "None"), True, False,
                                      ("sqlite", "Postgres", "none"))
        request = t3.PendingInput("r", (question,), False, "")
        for said, expected in (("first", "sqlite"), ("the first one", "sqlite"), ("The second option.", "Postgres"),
                               ("third one", "none"), ("the fourth one", "the fourth one"),
                               ("first, but skip tests", "first, but skip tests")):
            with self.subTest(said=said):
                self.assertEqual({"q1": expected}, t3.resolve_answers(request, {"q1": said}))
        strict = t3.PendingInput("r", (t3.PendingQuestion("q1", "H", "Which?", ("A", "B"), False, False, ("a", "b")),),
                                 False, "")
        self.assertEqual({"q1": "b"}, t3.resolve_answers(strict, {"q1": "the second one"}))
        with self.assertRaises(t3.T3Error):
            t3.resolve_answers(strict, {"q1": "the third one"})

    def test_timeline_folds_tool_steps(self) -> None:
        thread = {"messages": [
            {"id": "1", "role": "user", "text": "go", "createdAt": "2026-10-08T10:00:00Z"},
            {"id": "2", "role": "system", "text": "thinking", "createdAt": "2026-10-08T10:00:01Z"},
            {"id": "3", "role": "assistant", "text": "done", "createdAt": "2026-10-08T10:05:00Z"}],
            "activities": [{"kind": "tool.started", "createdAt": f"2026-10-08T10:0{n}:00Z",
                            "payload": {"toolCallId": f"c{n}", "title": f"Step {n}"}} for n in range(1, 5)] +
            [{"kind": "tool.completed", "createdAt": "2026-10-08T10:04:30Z", "payload": {"toolCallId": "c4",
                                                                                     "title": "Step 4"}}]}
        self.assertEqual([("user", "go"), ("tool", "4 steps: Step 1, Step 2, Step 3 (+1 more)"), ("assistant", "done")],
                         [(item["role"], item["text"]) for item in t3.timeline(thread)])


class PlacementTest(unittest.TestCase):
    def test_coding_or_not(self) -> None:
        for text in ("Fix the failing tests", "Refactor the sync module", "Add a widget to the R1",
                     "Why does the build crash in Xcode"):
            self.assertTrue(t3.is_coding_task(text), text)
        for text in ("Summarize my inbox", "What's on my calendar today", "Tell me a joke", "Book a flight to Lisbon",
                     "Open YouTube in Chrome"):
            self.assertFalse(t3.is_coding_task(text), text)

    def test_orchestration_project_fallbacks(self) -> None:
        agent = record("agent", "Assistant", "/Users/x/.t3/hermes/workspace")
        named = record("named", "Hermes", "/Users/x/agents")
        repo = record("repo", "SamRabbit", "/Users/x/jackrabbit-src")
        scratch = record("scratch", "No project", "/Users/x/.t3/scratch", scratch=True)
        self.assertEqual("named", t3.orchestration_project([repo, agent, named, scratch])["id"], "the configured title")
        self.assertEqual("repo", t3.orchestration_project([repo, agent, named], project_id="repo")["id"])
        self.assertEqual("agent", t3.orchestration_project([repo, agent, scratch])["id"], "T3's agent workspace")
        self.assertEqual("repo", t3.orchestration_project([scratch, repo])["id"], "else the most recent project")
        self.assertEqual("scratch", t3.orchestration_project([scratch])["id"])
        self.assertIsNone(t3.orchestration_project([]))

    def test_place(self) -> None:
        agent = record("agent", "Hermes", "/Users/x/.t3/hermes/workspace")
        repo = record("repo", "SamRabbit", "/Users/x/jackrabbit-src", repository={"remote": "git@github.com:x/SamRabbit.git"})
        site = record("site", "Website", "/Users/x/code/site")
        records = [agent, repo, site]

        def where(text: str, choices: list = records) -> tuple:
            chosen, reason = t3.place(text, choices)
            return chosen["id"], reason
        self.assertEqual(("repo", t3.CODING), where("Fix the failing tests"))
        self.assertEqual(("agent", t3.ORCHESTRATION), where("Summarize my inbox"))
        self.assertEqual(("site", t3.MENTIONED), where("Change the website hero"))
        self.assertEqual(("repo", t3.MENTIONED), where("Merge the samrabbit branch"))
        self.assertEqual(("agent", t3.ORCHESTRATION), where("Fix the tests", [agent]),
                         "nothing else: the orchestration project after all")
        with self.assertRaises(t3.T3Error):
            t3.place("anything", [])


class CliAndTokenTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def test_cli_output_parsing(self) -> None:
        self.assertEqual({"credential": "X"}, t3._json_object('warning: old db\n{\n  "credential": "X"\n}\n'))  # noqa: SLF001
        self.assertIsNone(t3._json_object("no json here"))  # noqa: SLF001

    def test_token_store_is_private_and_rereads_changes(self) -> None:
        store = t3.TokenStore(str(self.root / "cfg" / "t3-token"))
        self.assertIsNone(store.load())
        store.save({"token": "a" * 40, "expiresAt": "2026-11-01T00:00:00Z"})
        path = self.root / "cfg" / "t3-token"
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))
        path.write_text(json.dumps({"token": "b" * 40}))
        path.chmod(0o644)
        self.assertEqual("b" * 40, store.load()["token"])
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode), "tightened")
        path.write_text("{not json")
        self.assertIsNone(store.load())

    def test_session_ids_come_from_the_claims(self) -> None:
        fake = FakeT3(str(self.root))
        self.addCleanup(fake.close)
        self.assertEqual("sid-1", t3.session_id_of(fake.issue_token()))
        self.assertIsNone(t3.session_id_of("garbage.token"))

    def test_http_refuses_plain_http_to_the_internet(self) -> None:
        for url in ("http://8.8.8.8:3773", "ftp://127.0.0.1", "http://user:pw@127.0.0.1:3773", ""):
            with self.assertRaises(ValueError, msg=url):
                t3.T3Http(url)
        for url in ("http://127.0.0.1:3773", "http://192.168.1.183:3773", "http://100.100.1.2:3773",
                    "https://t3.example.com"):
            self.assertTrue(t3.T3Http(url).base_url)

    def test_command_line_pairs_once_and_never_prints_the_token(self) -> None:
        fake = FakeT3(str(self.root))
        self.addCleanup(fake.close)
        cli = self.root / "t3"
        cli.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{HERE / "fake_t3_cli.py"}" --state "{self.root}" "$@"\n')
        cli.chmod(0o755)
        token_file = self.root / "cfg" / "t3-token"
        args = ["--token-file", str(token_file), "--url", fake.url, "--cli", str(cli)]

        def run(*command: str) -> subprocess.CompletedProcess:
            return subprocess.run(["/usr/bin/python3", "-I", str(ROOT / "samrabbit_t3.py"), *command, *args],
                                  capture_output=True, text=True, timeout=60)
        done = run("status")
        self.assertEqual((0, "T3 not paired"), (done.returncode, done.stdout.strip()))
        done = run("ensure-paired")
        self.assertEqual((0, "T3 paired (expires 2026-11-07)"), (done.returncode, done.stdout.strip()), done.stderr)
        token = json.loads(token_file.read_text())["token"]
        self.assertNotIn(token, done.stdout + done.stderr)
        done = run("ensure-paired")
        self.assertEqual(0, done.returncode)
        self.assertEqual(1, len((self.root / "cli-calls.jsonl").read_text().splitlines()), "kept, not paired again")
        (self.root / "cli-mode").write_text("fail")
        token_file.unlink()
        done = run("ensure-paired")
        self.assertEqual(3, done.returncode)
        self.assertIn("t3_cli_failed", done.stdout)


class Python39Test(unittest.TestCase):
    def test_new_modules_parse_as_python_3_9_and_import_isolated(self) -> None:
        for name in ("samrabbit_mobile.py", "samrabbit_t3.py", "samrabbit_bridge.py"):
            ast.parse((ROOT / name).read_text(), filename=name, feature_version=(3, 9))
        # As the LaunchAgent runs it: the macOS system Python, isolated mode, from another folder.
        code = ("import sys; sys.path.append(sys.argv[1]); import samrabbit_mobile, samrabbit_t3, samrabbit_bridge; "
                "print(sys.version_info[:2] >= (3, 9), samrabbit_bridge.mobile is not None)")
        done = subprocess.run(["/usr/bin/python3", "-I", "-c", code, str(ROOT)], capture_output=True, text=True,
                              timeout=60, cwd=tempfile.gettempdir())
        self.assertEqual("True True", done.stdout.strip(), done.stderr)


if __name__ == "__main__":
    unittest.main()
