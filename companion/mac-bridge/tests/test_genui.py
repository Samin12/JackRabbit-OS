"""Generative UI on the bridge: a fake ``claude`` executable and a fake renderer (real ``sips`` encodes).

Run: python3 -m unittest discover -s companion/mac-bridge/tests
"""

from __future__ import annotations

import io
import json
import logging
import os
from pathlib import Path
import shutil
import stat
import struct
import sys
import tempfile
import threading
import time
import types
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import zlib

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import samrabbit_bridge as bridge  # noqa: E402
import samrabbit_genui as genui  # noqa: E402

TOKEN = "test-token-" + "g" * 32
DESKTOP = "desktop-token-" + "d" * 32
SECRET = "my secret prompt about the quarterly plan 9931"


def make_png(width: int, height: int) -> bytes:
    """A real PNG: dark rows with a little texture, so the JPEG encoder has something to compress."""
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        for x in range(width):
            shade = 12 + ((x // 24 + y // 24) % 2) * 10
            rows += bytes((shade, shade + 2, shade + 8))
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + \
        chunk(b"IEND", b"")


class FakeRenderer:
    def __init__(self) -> None:
        self.pages: list[str] = []
        self.errors: list[list[str]] = []
        self.fail = False
        self.lock = threading.Lock()

    def executable(self) -> str:
        return "/fake/agent-browser"

    def stop(self) -> None:
        pass

    def render(self, page: str, out_png: str, *, width: int, scale: int, timeout: float) -> genui.RenderResult:
        with open(page, encoding="utf-8") as handle:
            text = handle.read()
        with self.lock:
            self.pages.append(text)
            errors = self.errors.pop(0) if self.errors else []
        if self.fail:
            raise genui.GenUiError("renderer_missing", "The renderer is missing.", status=503)
        with open(out_png, "wb") as handle:
            handle.write(make_png(width * scale, 600 * scale))
        return genui.RenderResult(600, errors, True)


class FakeSync:
    def __init__(self) -> None:
        self.events: list[tuple[str, dict]] = []
        self.blobs: list[tuple[bytes, str]] = []

    def record_local_event(self, conversation_id: str, event: dict) -> None:
        self.events.append((conversation_id, event))

    def put_blob(self, data: bytes, mime: str) -> str:
        self.blobs.append((data, mime))
        return genui.blob_id_for(data)


class GenUiTestBase(unittest.TestCase):
    generation_timeout = 60.0

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.state = root / "claude-state"
        self.state.mkdir()
        bin_dir = root / "bin"
        bin_dir.mkdir()
        self.claude = bin_dir / "claude"
        self.claude.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{HERE / "fake_claude.py"}" --state "{self.state}" "$@"\n')
        self.claude.chmod(0o755)
        self.artifacts = root / "artifacts"
        self.config = root / "genui.json"
        self.desktop_file = root / "desktop-token"
        self.desktop_file.write_text(DESKTOP + "\n")
        self.desktop_file.chmod(0o600)
        self.renderer = FakeRenderer()
        self.sync = FakeSync()
        self.service = genui.GenUiService(
            genui.ArtifactStore(str(self.artifacts)),
            cli=genui.ClaudeCli(str(self.claude), config_file=str(self.config)),
            renderer=self.renderer,
            sync=genui.SyncHooks(self.sync),
            desktop_token=genui.DesktopToken(str(self.desktop_file)),
            generation_timeout=self.generation_timeout,
        )
        self.token_file = root / "bridge-token"
        self.token_file.write_text(TOKEN + "\n")
        self.token_file.chmod(0o600)
        self.log = io.StringIO()
        handler = logging.StreamHandler(self.log)
        bridge._LOG.addHandler(handler)  # noqa: SLF001
        bridge._LOG.setLevel(logging.INFO)  # noqa: SLF001
        self.addCleanup(bridge._LOG.removeHandler, handler)  # noqa: SLF001
        self.server = bridge.make_server("127.0.0.1", 0, token_file=str(self.token_file), genui_service=self.service)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def _stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def modes(self, value: str) -> None:
        (self.state / "modes").write_text(value)

    def calls(self) -> list[dict]:
        path = self.state / "calls.jsonl"
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines() if line.strip()]

    def request(self, method: str, path: str, body: object = None, *, token: str | None = TOKEN,
                headers: dict | None = None) -> tuple[int, dict, bytes]:
        merged = {"Content-Type": "application/json"}
        if token is not None:
            merged["Authorization"] = f"Bearer {token}"
        merged.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        try:
            with urlopen(Request(self.base + path, data=data, method=method, headers=merged), timeout=20) as response:
                return response.status, dict(response.headers), response.read()
        except HTTPError as error:
            return error.code, dict(error.headers), error.read()

    def call(self, method: str, path: str, body: object = None, **kwargs: object) -> tuple[int, dict]:
        status, _headers, raw = self.request(method, path, body, **kwargs)
        return status, json.loads(raw) if raw else {}

    def generate(self, request_id: str = "rt:voice1:call1", prompt: str = "a bar chart of my meetings",
                 **extra: object) -> tuple[int, dict]:
        return self.call("POST", "/v1/ui/generate", {"requestId": request_id, "prompt": prompt, **extra})

    def finish(self) -> None:
        self.service.start()
        self.addCleanup(self.service.stop)
        self.assertTrue(self.service.wait_idle(30), "generation did not finish")


class GenerateTest(GenUiTestBase):
    def test_generate_renders_an_r1_picture_and_a_desktop_document(self) -> None:
        status, body = self.generate(conversationId="c_" + "a" * 20, data="Mon 3, Tue 5")
        self.assertEqual(202, status)
        self.assertEqual("generating", body["status"])
        artifact_id = body["artifactId"]
        self.assertRegex(artifact_id, r"^ui_[0-9a-f]{24}$")
        status, meta = self.call("GET", f"/v1/ui/artifacts/{artifact_id}")
        self.assertEqual((200, "generating"), (status, meta["status"]))
        status, _ = self.call("GET", f"/v1/ui/artifacts/{artifact_id}/image")
        self.assertEqual(409, status, "no picture before it is ready")
        self.finish()

        status, meta = self.call("GET", f"/v1/ui/artifacts/{artifact_id}")
        self.assertEqual(200, status)
        self.assertEqual("ready", meta["status"])
        self.assertEqual("Meetings this week", meta["title"])
        self.assertEqual("Thursday is the busiest day with 6 meetings.", meta["summary"])
        self.assertTrue(meta["imageBlobId"].startswith("sha256:"))
        self.assertLessEqual(meta["width"], 960)

        status, headers, image = self.request("GET", f"/v1/ui/artifacts/{artifact_id}/image")
        self.assertEqual(200, status)
        self.assertEqual("image/jpeg", headers["Content-Type"])
        self.assertTrue(image.startswith(b"\xff\xd8"))
        self.assertLessEqual(len(image), 150 * 1024)
        self.assertEqual(meta["imageBlobId"], genui.blob_id_for(image))
        width, height = genui.jpeg_size(image)
        self.assertEqual((meta["width"], meta["height"]), (width, height))
        self.assertLessEqual(width, 960)

        status, headers, document = self.request("GET", f"/v1/ui/artifacts/{artifact_id}/document")
        self.assertEqual(200, status)
        self.assertTrue(headers["Content-Type"].startswith("text/html"))
        self.assertIn("sandbox allow-scripts", headers["Content-Security-Policy"])
        text = document.decode()
        self.assertIn("<head>", text)
        self.assertIn('id="bars"', text)
        self.assertIn("window.__SR_STATIC__=false", text, "the desktop gets the live (animated) variant")
        self.assertIn("SamRabbit.__run([\"mark();\"])", text)

        folder = self.artifacts / artifact_id
        for name in ("meta.json", "request.json", "args.json", "document.html", "preview.png", "preview.jpg"):
            path = folder / name
            self.assertTrue(path.is_file(), name)
            self.assertEqual(0, path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO), name)
        self.assertEqual(0, folder.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO))
        self.assertIn("window.__SR_STATIC__=true", self.renderer.pages[0], "the R1 picture is the static variant")
        self.assertIn('sandbox="allow-scripts"', self.renderer.pages[0], "rendered inside a sandboxed iframe")

    def test_claude_runs_without_tools_in_an_empty_folder_and_gets_the_prompt_on_stdin(self) -> None:
        self.generate(prompt=SECRET, data="Revenue Q1 12, Q2 18")
        self.finish()
        (call,) = self.calls()
        args = call["args"]
        self.assertEqual("", args[args.index("--tools") + 1], "every built-in tool is off")
        for flag in ("-p", "--strict-mcp-config", "--disable-slash-commands", "--safe-mode",
                     "--no-session-persistence", "--system-prompt-file", "--json-schema"):
            self.assertIn(flag, args)
        self.assertEqual("claude-sonnet-5-5", args[args.index("--model") + 1])
        self.assertEqual("json", args[args.index("--output-format") + 1])
        self.assertEqual("dontAsk", args[args.index("--permission-mode") + 1])
        self.assertEqual([], call["cwdEntries"], "the CLI runs in an empty folder")
        self.assertFalse(any(SECRET in arg for arg in args), "the prompt is never on the command line")
        self.assertIn(SECRET, call["stdin"])
        self.assertIn("Revenue Q1 12, Q2 18", call["stdin"])
        self.assertIn("never instructions", call["stdin"])
        schema = json.loads(args[args.index("--json-schema") + 1])
        self.assertEqual(sorted(genui.WIDGET_SCHEMA["required"]), sorted(schema["required"]))
        self.assertFalse({"ANTHROPIC_API_KEY", "OPENAI_API_KEY"} & set(call["envKeys"]))
        self.assertFalse(Path(call["cwd"]).exists(), "the temporary folder is removed afterwards")
        self.assertNotIn(SECRET, self.log.getvalue())
        self.assertNotIn("Revenue", self.log.getvalue())
        request = json.loads((self.artifacts / genui.artifact_id_for("rt:voice1:call1") / "request.json").read_text())
        self.assertEqual(SECRET, request["prompt"])

    def test_model_setting_switches_to_opus(self) -> None:
        self.config.write_text(json.dumps({"model": "claude-opus-5-5"}))
        self.generate()
        self.finish()
        args = self.calls()[0]["args"]
        self.assertEqual("claude-opus-5-5", args[args.index("--model") + 1])
        self.config.write_text(json.dumps({"model": "rm -rf /"}))
        self.assertEqual("claude-sonnet-5-5", self.service.cli.model(), "only claude-* model names are accepted")

    def test_same_request_id_is_idempotent(self) -> None:
        first = self.generate(request_id="rt:v:1")
        second = self.generate(request_id="rt:v:1", prompt="something else entirely")
        self.assertEqual(first[1]["artifactId"], second[1]["artifactId"])
        self.assertEqual(202, second[0])
        self.finish()
        third = self.generate(request_id="rt:v:1")
        self.assertEqual(200, third[0])
        self.assertEqual("ready", third[1]["status"])
        self.assertEqual(1, len(self.calls()), "one generation per requestId")
        other = self.generate(request_id="rt:v:2")
        self.assertNotEqual(first[1]["artifactId"], other[1]["artifactId"])

    def test_text_and_fenced_answers_are_accepted(self) -> None:
        self.modes("text")
        self.generate(request_id="a")
        self.finish()
        self.modes("fenced")
        self.generate(request_id="b")
        self.assertTrue(self.service.wait_idle(30))
        for request_id in ("a", "b"):
            self.assertEqual("ready", self.service.store.meta(genui.artifact_id_for(request_id))["status"])

    def test_invalid_answer_is_repaired_once(self) -> None:
        self.modes("bad,ok")
        _, body = self.generate()
        self.finish()
        meta = self.service.store.meta(body["artifactId"])
        self.assertEqual("ready", meta["status"])
        self.assertTrue(meta["repaired"])
        calls = self.calls()
        self.assertEqual(2, len(calls))
        self.assertIn("could not be used", calls[1]["stdin"])
        self.assertIn("`html` is empty", calls[1]["stdin"])

    def test_invalid_twice_fails(self) -> None:
        self.modes("bad")
        _, body = self.generate(conversationId="c_" + "b" * 20)
        self.finish()
        status, meta = self.call("GET", f"/v1/ui/artifacts/{body['artifactId']}")
        self.assertEqual("failed", meta["status"])
        self.assertEqual("invalid_widget", meta["error"])
        self.assertTrue(meta["errorMessage"])
        self.assertEqual(2, len(self.calls()))
        kinds = [event["type"] for _, event in self.sync.events]
        self.assertEqual(["ui.generating", "ui.failed"], kinds)
        self.assertEqual("invalid_widget", self.sync.events[-1][1]["error"])

    def test_script_errors_get_one_repair(self) -> None:
        self.modes("throws,ok")
        self.renderer.errors = [["ReferenceError: boom is not defined"], []]
        _, body = self.generate()
        self.finish()
        meta = self.service.store.meta(body["artifactId"])
        self.assertEqual("ready", meta["status"])
        self.assertTrue(meta["repaired"])
        self.assertEqual(0, meta["scriptErrors"])
        self.assertIn("ReferenceError: boom is not defined", self.calls()[1]["stdin"])
        args = json.loads((self.artifacts / body["artifactId"] / "args.json").read_text())
        self.assertEqual(["mark();"], args["jsExpressions"], "the repaired widget is kept")

    def test_claude_errors_fail_with_a_stable_code(self) -> None:
        for index, (mode, code) in enumerate((("busy", "claude_busy"), ("signed_out", "claude_signed_out"),
                                               ("garbage", "claude_failed"))):
            self.modes(mode)
            _, body = self.generate(request_id=f"err-{index}")
            self.service.start()
            self.assertTrue(self.service.wait_idle(30))
            meta = self.service.store.meta(body["artifactId"])
            self.assertEqual(("failed", code), (meta["status"], meta["error"]), mode)
        self.service.stop()

    def test_render_failure_keeps_the_document_for_the_desktop(self) -> None:
        self.renderer.fail = True
        _, body = self.generate()
        self.finish()
        meta = self.service.store.meta(body["artifactId"])
        self.assertEqual(("failed", "renderer_missing"), (meta["status"], meta["error"]))
        status, _headers, document = self.request("GET", f"/v1/ui/artifacts/{body['artifactId']}/document")
        self.assertEqual(200, status)
        self.assertIn(b'id="bars"', document)

    def test_sync_events_and_blob(self) -> None:
        conversation = "c_" + "c" * 20
        _, body = self.generate(conversationId=conversation, prompt="chart of my sleep")
        self.finish()
        self.assertEqual(["ui.generating", "ui.generated"], [event["type"] for _, event in self.sync.events])
        for conversation_id, event in self.sync.events:
            self.assertEqual(conversation, conversation_id)
            self.assertEqual(conversation, event["conversationId"])
            self.assertEqual("mac", event["origin"])
            self.assertEqual(body["artifactId"], event["artifactId"])
            self.assertTrue(event["id"].startswith("mac:"))
            self.assertIsInstance(event["at"], int)
        generated = self.sync.events[-1][1]
        self.assertEqual("Meetings this week", generated["title"])
        self.assertEqual(genui.blob_id_for(self.sync.blobs[0][0]), generated["imageBlobId"])
        self.assertEqual("image/jpeg", self.sync.blobs[0][1])
        self.assertEqual("chart of my sleep", self.sync.events[0][1]["prompt"])
        self.assertNotEqual(self.sync.events[0][1]["id"], generated["id"])

    def test_no_conversation_means_no_events_and_missing_sync_module_is_fine(self) -> None:
        self.service.sync = genui.SyncHooks(None)
        _, body = self.generate()
        self.finish()
        meta = self.service.store.meta(body["artifactId"])
        self.assertEqual("ready", meta["status"])
        self.assertTrue(meta["imageBlobId"].startswith("sha256:"))
        self.assertEqual([], self.sync.events)

    def test_failing_sync_module_never_breaks_generation(self) -> None:
        broken = types.SimpleNamespace(record_local_event=lambda *_: 1 / 0, put_blob=lambda *_: 1 / 0)
        self.service.sync = genui.SyncHooks(broken)
        _, body = self.generate(conversationId="c_" + "e" * 20)
        self.finish()
        self.assertEqual("ready", self.service.store.meta(body["artifactId"])["status"])


class TimeoutTest(GenUiTestBase):
    generation_timeout = 2.0

    def test_generation_timeout_kills_claude(self) -> None:
        self.modes("slow")
        started = time.monotonic()
        _, body = self.generate()
        self.finish()
        self.assertLess(time.monotonic() - started, 15)
        meta = self.service.store.meta(body["artifactId"])
        self.assertEqual(("failed", "generation_timeout"), (meta["status"], meta["error"]))


class RoutesTest(GenUiTestBase):
    def test_validation(self) -> None:
        cases = [
            ({"requestId": "bad id!", "prompt": "x"}, "invalid_request_id"),
            ({"requestId": "a", "prompt": "  "}, "invalid_prompt"),
            ({"requestId": "a", "prompt": "x" * 4001}, "invalid_prompt"),
            ({"requestId": "a", "prompt": "x", "size": "huge"}, "invalid_size"),
            ({"requestId": "a", "prompt": "x", "data": "d" * 30000}, "invalid_data"),
            ({"requestId": "a", "prompt": "x", "conversationId": "has spaces"}, "invalid_conversation"),
        ]
        for body, code in cases:
            status, payload = self.call("POST", "/v1/ui/generate", body)
            self.assertEqual((400, code), (status, payload["error"]["code"]), body)
        status, payload = self.call("GET", "/v1/ui/generate")
        self.assertEqual(405, status)
        status, payload = self.call("GET", "/v1/ui/artifacts/ui_" + "0" * 24)
        self.assertEqual((404, "artifact_not_found"), (status, payload["error"]["code"]))
        status, payload = self.call("GET", "/v1/ui/artifacts/../../etc/passwd")
        self.assertEqual(404, status)
        status, payload = self.call("POST", "/v1/ui/artifacts/ui_" + "0" * 24, {})
        self.assertEqual(405, status)
        status, payload = self.call("POST", "/v1/ui/generate", {"requestId": "a", "prompt": "x"}, token="wrong-" + "x" * 30)
        self.assertEqual(401, status)
        self.assertEqual([], self.calls())

    def test_structured_data_is_accepted(self) -> None:
        status, body = self.generate(data={"Mon": 3, "Tue": 5})
        self.assertEqual(202, status)
        stored = json.loads((self.artifacts / body["artifactId"] / "request.json").read_text())
        self.assertEqual({"Mon": 3, "Tue": 5}, json.loads(stored["data"]))

    def test_desktop_token_reads_artifacts_from_loopback_only(self) -> None:
        _, body = self.generate()
        self.finish()
        path = f"/v1/ui/artifacts/{body['artifactId']}"
        status, _headers, _ = self.request("GET", path + "/document", token=None,
                                          headers={"X-SamRabbit-Desktop": DESKTOP})
        self.assertEqual(200, status)
        status, _headers, image = self.request("GET", path + "/image", token=None,
                                               headers={"Cookie": f"theme=dark; sr_desktop={DESKTOP}"})
        self.assertEqual(200, status)
        self.assertTrue(image.startswith(b"\xff\xd8"))
        status, _headers, _ = self.request("GET", path, token=None, headers={"X-SamRabbit-Desktop": "nope-" + "x" * 20})
        self.assertEqual(401, status)
        status, _headers, _ = self.request("POST", "/v1/ui/generate", {"requestId": "z", "prompt": "x"}, token=None,
                                           headers={"X-SamRabbit-Desktop": DESKTOP})
        self.assertEqual(401, status, "the desktop token cannot start generations")
        handler = types.SimpleNamespace(client_address=("192.168.1.20", 5000),
                                        headers={"X-SamRabbit-Desktop": DESKTOP})
        self.assertFalse(self.service.desktop_authorized(handler, "GET", path), "loopback only")
        self.desktop_file.chmod(0o644)
        self.assertFalse(self.service.desktop_token.matches(DESKTOP), "a world-readable token file is ignored")

    def test_queue_is_bounded(self) -> None:
        for index in range(genui.MAX_QUEUE):
            self.assertEqual(202, self.generate(request_id=f"q{index}")[0])
        status, payload = self.generate(request_id="one-too-many")
        self.assertEqual((503, "genui_busy"), (status, payload["error"]["code"]))
        self.assertTrue(payload["error"]["retryable"])

    def test_health_reports_generative_ui(self) -> None:
        status, health = self.call("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual({"available", "claude", "renderer", "model", "queued", "sync"}, set(health["genui"]))
        self.assertTrue(health["genui"]["claude"])
        self.assertEqual("claude-sonnet-5-5", health["genui"]["model"])

    def test_requests_log_no_content(self) -> None:
        self.generate(prompt=SECRET, data="private numbers 4242")
        self.finish()
        log = self.log.getvalue()
        self.assertIn("/v1/ui/generate", log)
        self.assertIn("ready", log)
        self.assertNotIn(SECRET, log)
        self.assertNotIn("4242", log)


class RecoveryTest(GenUiTestBase):
    def test_interrupted_requests_resume_or_fail(self) -> None:
        store = self.service.store
        fresh, stale = genui.artifact_id_for("fresh"), genui.artifact_id_for("stale")
        for artifact_id, age in ((fresh, 5), (stale, genui.RECOVER_WINDOW_SECONDS + 60)):
            store.create(artifact_id, {"artifactId": artifact_id, "status": "generating",
                                       "createdAtMs": genui._epoch_ms() - age * 1000},  # noqa: SLF001
                         {"requestId": artifact_id, "prompt": "a chart", "size": "r1"})
        self.finish()
        self.assertEqual("ready", store.meta(fresh)["status"])
        self.assertEqual(("failed", "interrupted"), (store.meta(stale)["status"], store.meta(stale)["error"]))


class AssemblyTest(unittest.TestCase):
    def test_document_order_matches_build_final_frame_content(self) -> None:
        widget = {"title": "T <x>", "css": ".a{color:red}", "html": "<p class='a'>hi</p>",
                  "jsFunctions": "function f(){ return '</script>'; }",
                  "jsExpressions": ["f(); // </script><script>alert(1)</script>", "<!-- x"]}
        document = genui.assemble_document(widget, genui.DesignSystem())
        head = document.index("<head>")
        csp = document.index("Content-Security-Policy")
        importmap = document.index('<script type="importmap">')
        design = document.index("--color-background-primary")
        css = document.index(".a{color:red}")
        body = document.index("<p class='a'>hi</p>")
        self.assertTrue(head < csp < importmap < design < css < body)
        self.assertNotIn("</script><script>alert(1)", document, "expressions cannot close the runner script")
        self.assertNotIn("'</script>'", document)
        self.assertIn("<\\/script>", document)
        self.assertNotIn("<!-- x", document)
        self.assertIn("T &lt;x&gt;", document)
        for origin in genui.CDN_ORIGINS:
            self.assertIn(origin, genui.CSP_POLICY.split("connect-src", 1)[1])
        self.assertNotIn("prefers-color-scheme: dark", document, "dark is forced")
        self.assertIn("@media all", document)

    def test_full_documents_from_the_model_are_unwrapped(self) -> None:
        widget = {"title": "x", "html": "<!DOCTYPE html><html><head><script src='https://cdn.jsdelivr.net/x.js'>"
                                         "</script></head><body><main>ok</main></body></html>",
                  "css": "", "jsFunctions": "", "jsExpressions": []}
        document = genui.assemble_document(widget, genui.DesignSystem(), static=True)
        self.assertEqual(1, document.lower().count("<html"))
        self.assertEqual(1, document.lower().count("<body"))
        self.assertEqual(1, document.count("<head>"))
        self.assertLess(document.index("cdn.jsdelivr.net/x.js"), document.index("</head>"))
        self.assertIn('<div id="content">\n<main>ok</main>', document)
        self.assertIn("window.__SR_STATIC__=true", document)

    def test_normalize_widget(self) -> None:
        widget = genui.normalize_widget({"title": "  A   b ", "summary": "s", "initialHeight": 99999, "css": None,
                                         "html": "<svg></svg>", "jsFunctions": "", "jsExpressions": "go();"},
                                        fallback_title="Fallback")
        self.assertEqual("A b", widget["title"])
        self.assertEqual(4000, widget["initialHeight"])
        self.assertEqual(["go();"], widget["jsExpressions"])
        self.assertEqual("", widget["css"])
        self.assertEqual("Fallback", genui.normalize_widget(dict(widget, title=""), fallback_title="Fallback")["title"])
        with self.assertRaises(genui.InvalidWidget):
            genui.normalize_widget({"html": "<div></div>", "jsExpressions": []})
        with self.assertRaises(genui.InvalidWidget):
            genui.normalize_widget({"html": "<p>x</p>", "jsExpressions": [1]})
        with self.assertRaises(genui.InvalidWidget):
            genui.normalize_widget({"html": "x" * (genui.FIELD_LIMITS["html"] + 1)})

    def test_render_wrapper_escapes_the_document(self) -> None:
        page = genui.render_wrapper('<p title="a&b">"quoted"</p>', 480)
        self.assertIn('srcdoc="&lt;p title=&quot;a&amp;b&quot;&gt;&quot;quoted&quot;&lt;/p&gt;"', page)
        self.assertIn('sandbox="allow-scripts"', page)
        self.assertIn("width:480px", page)


class JpegTest(unittest.TestCase):
    @unittest.skipUnless(os.access(genui.SIPS, os.X_OK), "sips is macOS only")
    def test_large_png_becomes_a_small_r1_jpeg(self) -> None:
        with tempfile.TemporaryDirectory() as folder:
            png = os.path.join(folder, "in.png")
            with open(png, "wb") as handle:
                handle.write(make_png(1440, 2000))
            data, width, height = genui.JpegEncoder().encode(png, os.path.join(folder, "out.jpg"))
        self.assertLessEqual(len(data), 150 * 1024)
        self.assertLessEqual(width, 960)
        self.assertEqual(round(2000 * width / 1440), height)


class RealRenderTest(unittest.TestCase):
    @unittest.skipUnless(genui.AgentBrowser().executable(), "agent-browser is not installed")
    def test_agent_browser_renders_a_static_widget(self) -> None:
        widget = {"title": "x", "summary": "", "css": ".big{font-size:40px;height:300px}",
                  "html": "<div class='big' id='v'>42</div>",
                  "jsFunctions": "function setV(n){ document.getElementById('v').textContent = n; }",
                  "jsExpressions": ["setV(43);", "missing();"]}
        with tempfile.TemporaryDirectory() as folder:
            page = os.path.join(folder, "render.html")
            with open(page, "w", encoding="utf-8") as handle:
                handle.write(genui.render_wrapper(genui.assemble_document(widget, genui.DesignSystem(), static=True), 480))
            out = os.path.join(folder, "out.png")
            result = genui.AgentBrowser().render(page, out, width=480, scale=2, timeout=40)
            with open(out, "rb") as handle:
                size = genui.png_size(handle.read(32))
        self.assertTrue(result.ready)
        self.assertEqual(960, size[0])
        self.assertGreaterEqual(result.css_height, genui.MIN_RENDER_HEIGHT)
        self.assertTrue(any("missing" in error for error in result.errors), result.errors)


if __name__ == "__main__":
    unittest.main()
