"""Bridge tests against a fake ``heptabase`` executable placed first on PATH.

Run: python3 -m unittest discover -s companion/mac-bridge/tests
"""

from __future__ import annotations

import io
import json
import logging
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import samrabbit_bridge as bridge  # noqa: E402

TOKEN = "test-token-" + "x" * 32
SECRET_WORDS = "my private journal sentence 4711"


class BridgeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.state = root / "state"
        (self.state / "docs").mkdir(parents=True)
        bin_dir = root / "bin"
        bin_dir.mkdir()
        fake = bin_dir / "heptabase"
        fake.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{HERE / "fake_heptabase.py"}" "$@"\n')
        fake.chmod(0o755)
        self.token_file = root / "bridge-token"
        self.token_file.write_text(TOKEN + "\n")
        self.token_file.chmod(0o600)
        self.old_env = {key: os.environ.get(key) for key in ("PATH", "FAKE_HEPTABASE_DIR")}
        os.environ["PATH"] = f"{bin_dir}{os.pathsep}{os.environ.get('PATH', '')}"
        os.environ["FAKE_HEPTABASE_DIR"] = str(self.state)
        self.addCleanup(self._restore_env)
        self.log = io.StringIO()
        handler = logging.StreamHandler(self.log)
        bridge._LOG.addHandler(handler)  # noqa: SLF001
        bridge._LOG.setLevel(logging.INFO)  # noqa: SLF001
        self.addCleanup(bridge._LOG.removeHandler, handler)  # noqa: SLF001
        self.server = bridge.make_server("127.0.0.1", 0, token_file=str(self.token_file), cli_timeout=2.0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def _restore_env(self) -> None:
        for key, value in self.old_env.items():
            if value is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = value

    def _stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def mode(self, value: str, slow: float | None = None) -> None:
        (self.state / "mode").write_text(value)
        if slow is not None:
            (self.state / "slow").write_text(str(slow))

    def call(self, method: str, path: str, body: object = None, *, token: str | None = TOKEN,
             raw: bytes | None = None) -> tuple[int, dict]:
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        request = Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def calls(self) -> list[dict]:
        path = self.state / "calls.jsonl"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def journal(self) -> dict:
        path = self.state / "journal.json"
        return json.loads(path.read_text()) if path.exists() else {}

    # ------------------------------------------------------------------ auth

    def test_every_route_requires_the_bearer_token(self) -> None:
        for method, path, body in (("GET", "/health", None),
                                   ("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": "x"}),
                                   ("GET", "/v1/heptabase/journal/read?date=2026-10-07", None),
                                   ("GET", "/nope", None)):
            for token in (None, "wrong-token-" + "y" * 30, TOKEN[:-1], ""):
                with self.subTest(path=path, token=token):
                    status, value = self.call(method, path, body, token=token)
                    self.assertEqual(401, status)
                    self.assertEqual("unauthorized", value["error"]["code"])
        self.assertEqual({}, self.journal(), "nothing reached the CLI")
        self.assertEqual(404, self.call("GET", "/nope")[0])

    def test_token_file_rotation_is_picked_up_and_permissions_are_tightened(self) -> None:
        self.token_file.chmod(0o644)
        new = "rotated-token-" + "z" * 30
        self.token_file.write_text(new)
        time.sleep(0.01)
        os.utime(self.token_file, (time.time() + 5, time.time() + 5))
        self.assertEqual(401, self.call("GET", "/health")[0])
        self.assertEqual(200, self.call("GET", "/health", token=new)[0])
        self.assertEqual(0o600, stat.S_IMODE(self.token_file.stat().st_mode))

    def test_client_filter_allows_only_loopback_and_private_networks(self) -> None:
        for address in ("127.0.0.1", "192.168.1.186", "10.0.0.7", "172.20.1.1", "::1", "fe80::1%en0",
                        "fd00::5", "::ffff:192.168.1.186", "169.254.3.3"):
            self.assertTrue(bridge.client_allowed(address), address)
        for address in ("8.8.8.8", "100.64.0.1", "2001:4860::8888", "::ffff:8.8.8.8", "garbage", ""):
            self.assertFalse(bridge.client_allowed(address), address)

    # ------------------------------------------------------------------ health

    def test_health_reports_cli_version_and_app_reachability(self) -> None:
        status, value = self.call("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual((True, "samrabbit-bridge"), (value["ok"], value["service"]))
        self.assertEqual({"available": True, "version": "0.7.0"}, value["cli"])
        self.assertEqual(True, value["app"]["reachable"])
        self.server._health = None  # noqa: SLF001
        self.mode("down")
        status, value = self.call("GET", "/health")
        self.assertEqual(200, status, "the bridge itself is fine")
        self.assertEqual((False, "heptabase_app_unavailable"), (value["app"]["reachable"], value["app"]["detail"]))

    # ------------------------------------------------------------------ append

    def test_append_runs_the_cli_with_a_private_temp_file_that_is_deleted(self) -> None:
        content = f"**17:42** {SECRET_WORDS}"
        status, value = self.call("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": content})
        self.assertEqual(200, status)
        self.assertEqual({"date", "title", "contentMd5"}, set(value))
        self.assertEqual("2026-10-07", value["date"])
        self.assertEqual({"2026-10-07": [content]}, self.journal())
        (call,) = [entry for entry in self.calls() if entry["command"] == "append"]
        self.assertEqual(0o600, call["mode"])
        self.assertFalse(Path(call["path"]).exists(), "temp file removed after the CLI ran")
        self.assertTrue(call["path"].startswith(self.server.scratch))
        self.assertEqual(0o700, stat.S_IMODE(os.stat(self.server.scratch).st_mode))

    def test_append_validates_date_body_and_size(self) -> None:
        cases = (
            ({"date": "2026-13-40", "content": "x"}, 400, "invalid_date"),
            ({"date": "07-10-2026", "content": "x"}, 400, "invalid_date"),
            ({"date": "2026-10-07", "content": "   "}, 400, "invalid_content"),
            ({"date": "2026-10-07", "content": 5}, 400, "invalid_content"),
            ({"date": "2026-10-07", "content": "a\x00b"}, 400, "invalid_content"),
            ({"date": "2026-10-07"}, 400, "invalid_content"),
        )
        for body, expected_status, code in cases:
            with self.subTest(body=body):
                status, value = self.call("POST", "/v1/heptabase/journal/append", body)
                self.assertEqual((expected_status, code), (status, value["error"]["code"]))
        status, value = self.call("POST", "/v1/heptabase/journal/append", raw=b"[1,2]")
        self.assertEqual((400, "invalid_json"), (status, value["error"]["code"]))
        status, value = self.call("POST", "/v1/heptabase/journal/append", raw=b"not json")
        self.assertEqual((400, "invalid_json"), (status, value["error"]["code"]))
        big = {"date": "2026-10-07", "content": "x" * (bridge.MAX_BODY_BYTES + 1)}
        status, value = self.call("POST", "/v1/heptabase/journal/append", big)
        self.assertEqual((413, "body_too_large"), (status, value["error"]["code"]))
        self.assertEqual(405, self.call("GET", "/v1/heptabase/journal/append")[0])
        self.assertEqual({}, self.journal())

    def test_app_not_running_is_a_retryable_503_that_wrote_nothing(self) -> None:
        self.mode("down")
        status, value = self.call("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": "hi"})
        self.assertEqual(503, status)
        self.assertEqual({"code": "heptabase_app_unavailable", "retryable": True, "written": False},
                         {key: value["error"][key] for key in ("code", "retryable", "written")})
        status, value = self.call("GET", "/v1/heptabase/journal/read?date=2026-10-07")
        self.assertEqual((503, "heptabase_app_unavailable"), (status, value["error"]["code"]))

    def test_cli_failure_shapes_map_to_clear_errors(self) -> None:
        cases = (
            ("reject", 422, "heptabase_rejected", False),
            ("busy", 503, "heptabase_busy", False),
            ("http500", 503, "heptabase_app_error", "unknown"),
            ("garbage", 502, "heptabase_cli_bad_output", "unknown"),
        )
        for mode, expected_status, code, written in cases:
            with self.subTest(mode=mode):
                self.mode(mode)
                status, value = self.call("POST", "/v1/heptabase/journal/append",
                                          {"date": "2026-10-07", "content": SECRET_WORDS})
                self.assertEqual((expected_status, code, written),
                                 (status, value["error"]["code"], value["error"]["written"]))
                self.assertNotIn("secret", json.dumps(value), "CLI messages are never echoed")
        self.mode("reject")
        _, value = self.call("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": "x"})
        self.assertEqual("invalidInput", value["error"]["reason"])

    def test_cli_timeout_is_uncertain_for_appends(self) -> None:
        self.mode("slow", slow=5)
        started = time.monotonic()
        status, value = self.call("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": "x"})
        self.assertLess(time.monotonic() - started, 4.5)
        self.assertEqual((504, "heptabase_cli_timeout", "unknown"),
                         (status, value["error"]["code"], value["error"]["written"]))

    def test_appends_are_serialized(self) -> None:
        self.mode("slow", slow=0.4)
        results: list[int] = []

        def send(index: int) -> None:
            results.append(self.call("POST", "/v1/heptabase/journal/append",
                                     {"date": "2026-10-07", "content": f"entry {index}"})[0])

        threads = [threading.Thread(target=send, args=(index,)) for index in range(3)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual([200, 200, 200], results)
        spans = sorted((entry["started"], entry["ended"]) for entry in self.calls() if entry["command"] == "append")
        self.assertEqual(3, len(spans))
        for (_, end), (start, _) in zip(spans, spans[1:]):
            self.assertLessEqual(end, start + 0.05, "one CLI append at a time")

    # ------------------------------------------------------------------ read

    def test_read_converts_prosemirror_to_plain_lines(self) -> None:
        document = {"type": "doc", "content": [
            {"type": "heading", "attrs": {"level": 2}, "content": [{"type": "text", "text": "Ideas"}]},
            {"type": "paragraph", "content": [
                {"type": "text", "marks": [{"type": "strong"}], "text": "17:42"},
                {"type": "text", "text": " R1 journal "},
                {"type": "text", "marks": [{"type": "color", "attrs": {"type": "text", "color": "gray"}}],
                 "text": "connected"},
            ]},
            {"type": "paragraph", "attrs": {"id": None}},
            {"type": "bullet_list_item", "content": [
                {"type": "paragraph", "content": [{"type": "text", "text": "first"}]},
                {"type": "bullet_list_item", "content": [
                    {"type": "paragraph", "content": [{"type": "text", "text": "nested"}]}]},
            ]},
            {"type": "numbered_list_item", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "one"}]}]},
            {"type": "numbered_list_item", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "two"}]}]},
            {"type": "todo_list_item", "attrs": {"checked": True},
             "content": [{"type": "paragraph", "content": [{"type": "text", "text": "done"}]}]},
            {"type": "todo_list_item", "attrs": {"checked": False},
             "content": [{"type": "paragraph", "content": [{"type": "text", "text": "open"}]}]},
            {"type": "toggle_list_item", "content": [
                {"type": "paragraph", "content": [{"type": "text", "text": "R1 voice"}]},
                {"type": "paragraph", "content": [{"type": "text", "text": "inside"}]}]},
            {"type": "blockquote", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "quoted"}]}]},
            {"type": "paragraph", "content": [{"type": "text", "text": "line a"}, {"type": "hard_break"},
                                              {"type": "text", "text": "line b"}, {"type": "text", "text": " on "},
                                              {"type": "date", "attrs": {"date": "2026-10-08"}}]},
            {"type": "horizontal_rule"},
            {"type": "image", "attrs": {"src": "https://example.com/x.png"}},
            {"type": "bookmark", "attrs": {"url": "https://example.com", "title": "Example"}},
            {"type": "code_block", "content": [{"type": "text", "text": "print(1)"}]},
        ]}
        (self.state / "docs" / "2026-10-07.json").write_text(json.dumps(document))
        status, value = self.call("GET", "/v1/heptabase/journal/read?date=2026-10-07")
        self.assertEqual(200, status)
        self.assertEqual({"date", "title", "text", "contentMd5"}, set(value))
        self.assertEqual([
            "Ideas", "17:42 R1 journal connected", "- first", "  - nested", "1. one", "2. two", "[x] done",
            "[ ] open", "+ R1 voice", "  inside", "> quoted", "line a", "line b on 2026-10-08", "Example", "print(1)",
        ], value["text"].split("\n"))

    def test_read_validates_the_date_and_handles_an_empty_day(self) -> None:
        for query in ("", "?date=", "?date=yesterday", "?date=2026-02-30"):
            with self.subTest(query=query):
                status, value = self.call("GET", "/v1/heptabase/journal/read" + query)
                self.assertEqual((400, "invalid_date"), (status, value["error"]["code"]))
        status, value = self.call("GET", "/v1/heptabase/journal/read?date=2031-01-01")
        self.assertEqual((200, ""), (status, value["text"]))

    def test_append_then_read_round_trip(self) -> None:
        self.call("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": "**09:00** hello there"})
        status, value = self.call("GET", "/v1/heptabase/journal/read?date=2026-10-07")
        self.assertEqual(200, status)
        self.assertIn("hello there", value["text"])

    # ------------------------------------------------------------------ privacy

    def test_logs_never_contain_journal_text_or_the_token(self) -> None:
        self.call("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": SECRET_WORDS})
        self.call("GET", "/v1/heptabase/journal/read?date=2026-10-07")
        self.call("GET", "/health", token="wrong-" + SECRET_WORDS)
        self.call("GET", "/" + SECRET_WORDS.replace(" ", "-"))
        self.mode("reject")
        self.call("POST", "/v1/heptabase/journal/append", {"date": "2026-10-07", "content": SECRET_WORDS})
        text = self.log.getvalue()
        self.assertIn("POST /v1/heptabase/journal/append 200", text)
        self.assertIn("(other)", text)
        self.assertNotIn("4711", text)
        self.assertNotIn("private journal", text)
        self.assertNotIn(TOKEN, text)
        self.assertNotIn(TOKEN[:12], text)


class ProseMirrorTest(unittest.TestCase):
    def test_garbage_input_yields_no_lines(self) -> None:
        for value in (None, [], "doc", {"content": "x"}, {"content": [1, None, {"type": "paragraph", "content": 3}]}):
            self.assertEqual([], bridge.prosemirror_lines(value))

    def test_numbered_items_honour_explicit_order(self) -> None:
        document = {"content": [
            {"type": "numbered_list_item", "attrs": {"order": 5}, "content": [{"type": "paragraph", "content": [
                {"type": "text", "text": "five"}]}]},
            {"type": "numbered_list_item", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "six"}]}]},
            {"type": "paragraph", "content": [{"type": "text", "text": "break"}]},
            {"type": "numbered_list_item", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "one"}]}]},
        ]}
        self.assertEqual(["5. five", "6. six", "break", "1. one"], bridge.prosemirror_lines(document))

    def test_tables_render_cells_on_one_line(self) -> None:
        cell = lambda text: {"type": "table_cell", "content": [  # noqa: E731
            {"type": "paragraph", "content": [{"type": "text", "text": text}]}]}
        document = {"content": [{"type": "table", "content": [
            {"type": "table_row", "content": [cell("Name"), cell("Status")]},
            {"type": "table_row", "content": [cell("Bridge"), cell("Up")]},
        ]}]}
        self.assertEqual(["Name | Status", "Bridge | Up"], bridge.prosemirror_lines(document))


if __name__ == "__main__":
    unittest.main()
