"""Mac-control routes against a fake ``cua-driver`` (and fake ``open`` / ``lsappinfo``).

Run: python3 -m unittest discover -s companion/mac-bridge/tests
"""

from __future__ import annotations

import io
import json
import logging
import os
from pathlib import Path
import shutil
import sys
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import samrabbit_bridge as bridge  # noqa: E402
import samrabbit_mac as mac  # noqa: E402

TOKEN = "test-token-" + "m" * 32
SECRET_TEXT = "my private window sentence 9182"
CHROME, HEPTA, FINDER = 101, 202, 303


def _window(window_id: int, pid: int, app: str, title: str, z: int, **extra: object) -> dict:
    return {"window_id": window_id, "pid": pid, "app_name": app, "title": title, "z_index": z, "layer": 0,
            "is_on_screen": True, "bounds": {"x": 0, "y": 30, "width": 1200, "height": 800}, **extra}


def _element(index: int, role: str, label: object = None, value: object = None, *, parent: object = 0,
             depth: int = 2, **extra: object) -> dict:
    item = {"element_index": index, "role": role, "depth": depth, "parent_index": parent}
    if label is not None:
        item["label"] = label
    if value is not None:
        item["value"] = value
    item.update(extra)
    return item


def base_state() -> dict:
    return {
        "permissions": {"accessibility": True, "screen_recording": False},
        "front_pid": CHROME,
        "apps": [
            {"pid": CHROME, "name": "Google Chrome", "bundle_id": "com.google.Chrome"},
            {"pid": HEPTA, "name": "Heptabase", "bundle_id": "app.projectmeta.projectmeta"},
            {"pid": FINDER, "name": "Finder", "bundle_id": "com.apple.finder"},
            {"pid": 404, "name": "‎WhatsApp", "bundle_id": "net.whatsapp.WhatsApp"},
        ],
        "installed": [
            {"name": "Google Chrome", "bundle_id": "com.google.Chrome", "running": True, "pid": CHROME},
            {"name": "Heptabase", "bundle_id": "app.projectmeta.projectmeta", "running": True, "pid": HEPTA},
            {"name": "Calculator", "bundle_id": "com.apple.calculator", "running": False, "pid": 0},
            {"name": "Finder", "bundle_id": "com.apple.finder", "running": True, "pid": FINDER},
        ],
        "windows": [
            _window(9, 999, "Cua Driver", "", 30, bounds={"x": 0, "y": 0, "width": 2560, "height": 1440}),
            _window(11, CHROME, "Google Chrome", "Example Domain", 20),
            _window(12, CHROME, "Google Chrome", "", 19, bounds={"x": 0, "y": 0, "width": 1, "height": 1}),
            _window(21, HEPTA, "Heptabase", "Journal | Heptabase", 10),
            _window(31, FINDER, "Finder", "Downloads", 5),
        ],
        "elements": {
            "11": [
                _element(0, "AXWindow", "Example Domain - Google Chrome", depth=0, parent=None),
                _element(1, "AXButton", "Back"),
                _element(2, "AXTextField", "Address and search bar", "example.com/path?q=secret#frag"),
                _element(3, "AXRadioButton", "Example Domain - Memory usage - 120 MB", "1", selected=True),
                _element(4, "AXRadioButton", "Inbox (3) - Audio playing", "0", selected=False),
                _element(5, "AXWebArea", "Example Domain", parent=0, depth=1, in_web_content=True),
                _element(6, "AXHeading", "Example Domain", parent=5, depth=2, in_web_content=True),
                _element(7, "AXStaticText", None, "Example Domain", parent=6, depth=3, in_web_content=True),
                _element(8, "AXStaticText", None, SECRET_TEXT, parent=5, depth=2, in_web_content=True),
                _element(9, "AXLink", "More information...", parent=5, depth=2, in_web_content=True),
                _element(10, "AXStaticText", None, "More information...", parent=9, depth=3, in_web_content=True),
                _element(11, "AXButton", "Save", parent=5, depth=2, in_web_content=True),
                _element(12, "AXButton", "Save", parent=5, depth=2, in_web_content=True),
                _element(13, "AXTextField", "Search", "", parent=5, depth=2, in_web_content=True),
                _element(14, "AXMenuBarItem", "File", parent=None, depth=1),
            ],
            "21": [
                _element(0, "AXWindow", "Journal | Heptabase", depth=0, parent=None),
                _element(1, "AXButton", None, parent=0),
                _element(2, "AXStaticText", None, "Open settings", parent=1, depth=3),
            ],
        },
        "launch": {
            "app.projectmeta.projectmeta": {"pid": HEPTA, "windows": [_window(21, HEPTA, "Heptabase", "Journal | Heptabase", 10)]},
            "com.apple.calculator": {"pid": 505, "windows": []},
            "com.google.Chrome": {"pid": CHROME, "windows": [_window(11, CHROME, "Google Chrome", "Example Domain", 20)]},
        },
    }


class MacControlTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.state_dir = root / "state"
        self.state_dir.mkdir()
        self.home = root / "home"
        (self.home / "Documents").mkdir(parents=True)
        (self.home / "Documents" / "notes.txt").write_text("hello")
        (self.home / ".ssh").mkdir()
        (self.home / ".ssh" / "id_rsa").write_text("nope")
        script = self.home / "Documents" / "run.command"
        script.write_text("#!/bin/sh\n")
        tool = self.home / "Documents" / "tool"
        tool.write_text("#!/bin/sh\n")
        tool.chmod(0o755)
        self.bin = root / "bin"
        self.bin.mkdir()
        self.driver = self._wrapper("cua-driver", "driver")
        opener = self._wrapper("open", "open")
        lsappinfo = self._wrapper("lsappinfo", "lsappinfo")
        self.write_state(base_state())
        self.old_env = os.environ.get("FAKE_CUA_DIR")
        os.environ["FAKE_CUA_DIR"] = str(self.state_dir)
        self.addCleanup(self._restore_env)
        token_file = root / "bridge-token"
        token_file.write_text(TOKEN + "\n")
        token_file.chmod(0o600)
        self.log = io.StringIO()
        handler = logging.StreamHandler(self.log)
        bridge._LOG.addHandler(handler)  # noqa: SLF001
        bridge._LOG.setLevel(logging.INFO)  # noqa: SLF001
        self.addCleanup(bridge._LOG.removeHandler, handler)  # noqa: SLF001
        self.control = mac.MacControl(mac.CuaDriver(str(self.driver), timeout=5.0), open_command=str(opener),
                                      lsappinfo=str(lsappinfo), home=str(self.home),
                                      computer_name=lambda: "Test Mac Studio")
        self.server = bridge.make_server("127.0.0.1", 0, token_file=str(token_file), cli_timeout=2.0,
                                         cli=str(self.bin / "no-heptabase"), mac_control=self.control)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop)
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def _wrapper(self, name: str, role: str) -> Path:
        path = self.bin / name
        path.write_text(f'#!/bin/sh\nFAKE_CUA_ROLE={role} exec "{sys.executable}" "{HERE / "fake_cua_driver.py"}" "$@"\n')
        path.chmod(0o755)
        return path

    def _restore_env(self) -> None:
        if self.old_env is None:
            os.environ.pop("FAKE_CUA_DIR", None)
        else:
            os.environ["FAKE_CUA_DIR"] = self.old_env

    def _stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def write_state(self, value: dict) -> None:
        (self.state_dir / "state.json").write_text(json.dumps(value))

    def update_state(self, **changes: object) -> None:
        value = json.loads((self.state_dir / "state.json").read_text())
        value.update(changes)
        self.write_state(value)

    def calls(self, role: str = "driver") -> list[dict]:
        path = self.state_dir / "calls.jsonl"
        lines = path.read_text().splitlines() if path.exists() else []
        return [entry for entry in map(json.loads, lines) if entry["role"] == role]

    def tools(self) -> list[str]:
        return [entry["tool"] for entry in self.calls()]

    def call(self, method: str, path: str, body: object = None, *, token: str | None = TOKEN) -> tuple[int, dict]:
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = "Bearer " + token
        data = json.dumps(body).encode() if body is not None else None
        request = Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urlopen(request, timeout=30) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    # ------------------------------------------------------------------ auth and health

    def test_every_mac_route_requires_the_token_and_a_local_peer(self) -> None:
        routes = (("GET", "/v1/mac/state", None), ("POST", "/v1/mac/open", {"app": "Heptabase"}),
                  ("GET", "/v1/mac/read", None), ("POST", "/v1/mac/act", {"action": "bring_to_front"}),
                  ("GET", "/v1/mac/screenshot", None), ("GET", "/v1/mac/nope", None))
        for method, path, body in routes:
            for token in (None, "wrong-" + "t" * 40, ""):
                with self.subTest(path=path, token=token):
                    status, value = self.call(method, path, body, token=token)
                    self.assertEqual(401, status)
                    self.assertEqual("unauthorized", value["error"]["code"])
        self.assertEqual([], self.calls(), "nothing reached cua-driver")
        self.assertEqual([], self.calls("open"))
        # Even with --allow-any-client, Mac control refuses peers outside the local network.
        self.server.allow_any_client = True
        original = bridge.client_allowed
        bridge.client_allowed = lambda _address: False
        try:
            self.assertEqual(403, self.call("GET", "/v1/mac/state")[0])
            self.assertEqual(403, self.call("POST", "/v1/mac/open", {"url": "https://example.com"})[0])
            self.assertEqual(200, self.call("GET", "/health")[0], "the journal keeps its old opt-in")
        finally:
            bridge.client_allowed = original
        self.assertEqual([], self.calls("open"))

    def test_health_reports_capabilities_and_the_screen_vision_fix(self) -> None:
        status, health = self.call("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual("1.1.0", health["version"])
        capabilities = health["mac"]
        self.assertTrue(capabilities["driver"]["available"])
        self.assertEqual("9.8.7", capabilities["driver"]["version"])
        self.assertTrue(capabilities["driver"]["daemon"])
        self.assertEqual({"accessibility": True, "screenRecording": False}, capabilities["permissions"])
        self.assertEqual({"state": True, "open": True, "read": True, "act": True, "screenshot": False},
                         capabilities["features"])
        self.assertEqual(f"Run on the Mac: {self.driver} permissions grant", capabilities["screenRecordingFix"])
        self.assertEqual("Test Mac Studio", capabilities["computer"])
        prompts = [entry for entry in self.calls() if entry["tool"] == "check_permissions"]
        self.assertTrue(prompts and all(entry["args"] == {"prompt": False} for entry in prompts),
                        "permission checks are read-only (never raise a macOS prompt)")

    def test_missing_driver_is_reported_not_fatal(self) -> None:
        self.control.driver = mac.CuaDriver(str(self.bin / "absent-driver"))
        status, health = self.call("GET", "/health")
        self.assertEqual(200, status)
        self.assertFalse(health["mac"]["driver"]["available"])
        self.assertFalse(health["mac"]["features"]["screenshot"])
        status, value = self.call("GET", "/v1/mac/state")
        self.assertEqual(503, status)
        self.assertEqual("driver_missing", value["error"]["code"])

    # ------------------------------------------------------------------ state

    def test_state_lists_front_app_windows_and_chrome_tabs(self) -> None:
        status, state = self.call("GET", "/v1/mac/state")
        self.assertEqual(200, status, state)
        self.assertEqual({"app": "Google Chrome", "window": "Example Domain"}, state["front"])
        self.assertEqual(["Google Chrome", "Heptabase", "Finder"], [item["app"] for item in state["visible"]])
        self.assertNotIn("Cua Driver", json.dumps(state), "the driver's own overlay is not on the screen")
        self.assertIn("WhatsApp", state["running"], "invisible direction marks are stripped")
        chrome = state["chrome"][0]
        self.assertEqual("Example Domain", chrome["activeTab"])
        self.assertEqual(["Example Domain", "Inbox (3)"], chrome["tabs"], "tab-strip suffixes are trimmed")
        self.assertEqual("example.com/path?…", chrome["activeUrl"], "queries and fragments are not repeated")
        self.assertFalse(state["screenVision"])
        self.assertEqual("Test Mac Studio", state["computer"])

    def test_front_app_falls_back_to_the_top_window_when_an_agent_app_is_in_front(self) -> None:
        self.update_state(front_pid=77777)  # e.g. Universal Control or a setup wizard
        status, state = self.call("GET", "/v1/mac/state")
        self.assertEqual(200, status)
        self.assertEqual("Google Chrome", state["front"]["app"])

    # ------------------------------------------------------------------ open

    def test_open_app_launches_then_brings_it_forward(self) -> None:
        self.update_state(front_pid=CHROME)
        status, value = self.call("POST", "/v1/mac/open", {"app": "heptabase"})
        self.assertEqual(200, status, value)
        self.assertEqual({"ok": True, "opened": "app", "app": "Heptabase", "wasRunning": True, "frontmost": True,
                          "window": "Journal | Heptabase"}, value)
        launch = [entry for entry in self.calls() if entry["tool"] == "launch_app"]
        self.assertEqual([{"bundle_id": "app.projectmeta.projectmeta"}], [entry["args"] for entry in launch])
        front = [entry for entry in self.calls() if entry["tool"] == "bring_to_front"]
        self.assertEqual([{"pid": HEPTA, "window_id": 21}], [entry["args"] for entry in front])
        self.assertEqual([], self.calls("open"), "no LaunchServices fallback when it already came forward")

    def test_open_app_matches_spoken_names_and_suggests_on_a_miss(self) -> None:
        status, value = self.call("POST", "/v1/mac/open", {"app": "Chrome"})
        self.assertEqual(200, status, value)
        self.assertEqual("Google Chrome", value["app"])
        status, value = self.call("POST", "/v1/mac/open", {"app": "Calculatr"})
        self.assertEqual(200, status, value)
        self.assertEqual("Calculator", value["app"])
        status, value = self.call("POST", "/v1/mac/open", {"app": "Photoshop Elements"})
        self.assertEqual(404, status)
        self.assertEqual("app_not_found", value["error"]["code"])

    def test_open_url_goes_to_chrome_and_only_http(self) -> None:
        status, value = self.call("POST", "/v1/mac/open", {"url": "https://example.com/a?b=c"})
        self.assertEqual(200, status, value)
        self.assertEqual("example.com", value["host"])
        self.assertEqual([["-a", "Google Chrome", "https://example.com/a?b=c"]],
                         [entry["argv"] for entry in self.calls("open")])
        for bad in ("file:///etc/passwd", "javascript:alert(1)", "https://exa mple.com", "ftp://x.y", "chrome://settings"):
            with self.subTest(url=bad):
                status, value = self.call("POST", "/v1/mac/open", {"url": bad})
                self.assertEqual(400, status)
                self.assertEqual("invalid_url", value["error"]["code"])
        self.assertEqual(1, len(self.calls("open")))
        self.assertNotIn("example.com", self.log.getvalue(), "links are never logged")
        status, value = self.call("POST", "/v1/mac/open", {"url": "https://example.com", "app": "Finder"})
        self.assertEqual(400, status)

    def test_open_path_is_limited_to_the_home_folder(self) -> None:
        status, value = self.call("POST", "/v1/mac/open", {"path": "~/Documents/notes.txt"})
        self.assertEqual(200, status, value)
        self.assertEqual({"ok": True, "opened": "file", "name": "notes.txt"}, value)
        self.assertEqual([[os.path.realpath(self.home / "Documents" / "notes.txt")]],
                         [entry["argv"] for entry in self.calls("open")])
        cases = {
            "/etc/hosts": (403, "path_outside_home"),
            str(self.home / ".." / "outside"): (403, "path_outside_home"),
            "~/.ssh/id_rsa": (403, "path_private"),
            "~/Library": (403, "path_private"),
            "~/Documents/run.command": (403, "path_executable"),
            "~/Documents/tool": (403, "path_executable"),
            "~/Documents/missing.pdf": (404, "not_found"),
        }
        for path, (expected, code) in cases.items():
            with self.subTest(path=path):
                status, value = self.call("POST", "/v1/mac/open", {"path": path})
                self.assertEqual(expected, status, value)
                self.assertEqual(code, value["error"]["code"])
        self.assertEqual(1, len(self.calls("open")))

    # ------------------------------------------------------------------ read

    def test_read_condenses_the_front_window_and_never_logs_it(self) -> None:
        status, value = self.call("GET", "/v1/mac/read")
        self.assertEqual(200, status, value)
        self.assertEqual("Google Chrome", value["app"])
        self.assertEqual("Example Domain", value["window"])
        lines = value["text"].split("\n")
        self.assertIn("[Address and search bar: example.com/path?…]", lines)
        self.assertIn("# Example Domain", lines)
        self.assertEqual(1, value["text"].count("Example Domain"), "a heading's own text is not repeated")
        self.assertEqual(1, value["text"].count("More information..."), "a link's label is not repeated")
        self.assertIn(SECRET_TEXT, value["text"])
        self.assertNotIn("File", lines, "menu bar items are dropped")
        self.assertEqual(["Back", "Example Domain", "Inbox (3)", "Save"], value["controls"])
        self.assertNotIn(SECRET_TEXT, self.log.getvalue())
        snapshot = [entry["args"] for entry in self.calls() if entry["tool"] == "get_window_state"][-1]
        self.assertFalse(snapshot["include_screenshot"], "reading never captures the screen")
        status, value = self.call("GET", "/v1/mac/read?app=heptabase&max=300")
        self.assertEqual(200, status, value)
        self.assertEqual("Heptabase", value["app"])
        status, value = self.call("GET", "/v1/mac/read?app=Numbers")
        self.assertEqual(404, status)
        self.assertEqual("app_not_running", value["error"]["code"])
        self.assertEqual(400, self.call("GET", "/v1/mac/read?max=lots")[0])

    def test_condense_truncates_at_the_limit(self) -> None:
        elements = [_element(index, "AXStaticText", None, f"line {index} " + "x" * 50, parent=index)
                    for index in range(200)]
        text, _, truncated = mac.condense(elements, 500)
        self.assertTrue(truncated)
        self.assertLessEqual(len(text), 500)

    # ------------------------------------------------------------------ act

    def test_hotkeys_go_to_the_front_window_and_system_chords_are_blocked(self) -> None:
        status, value = self.call("POST", "/v1/mac/act", {"action": "hotkey", "keys": "cmd+w"})
        self.assertEqual(200, status, value)
        self.assertEqual({"ok": True, "action": "hotkey", "app": "Google Chrome", "keys": "cmd+w",
                          "effect": "confirmed"}, value)
        sent = [entry["args"] for entry in self.calls() if entry["tool"] == "hotkey"]
        self.assertEqual([{"pid": CHROME, "window_id": 11, "keys": ["cmd", "w"]}], sent)
        status, value = self.call("POST", "/v1/mac/act", {"action": "hotkey", "keys": ["Return"], "app": "Finder"})
        self.assertEqual(200, status, value)
        self.assertEqual([{"pid": FINDER, "window_id": 31, "key": "return"}],
                         [entry["args"] for entry in self.calls() if entry["tool"] == "press_key"])
        for keys in ("cmd+shift+q", ["command", "option", "esc"], "cmd+backspace", "ctrl+cmd+q", "cmd"):
            with self.subTest(keys=keys):
                status, value = self.call("POST", "/v1/mac/act", {"action": "hotkey", "keys": keys})
                self.assertIn(status, (400, 403))
        self.assertEqual(1, len([entry for entry in self.calls() if entry["tool"] == "hotkey"]))

    def test_click_by_label_uses_a_fresh_snapshot_token(self) -> None:
        status, value = self.call("POST", "/v1/mac/act", {"action": "click", "label": "more information"})
        self.assertEqual(200, status, value)
        self.assertEqual({"role": "link", "label": "More information..."}, value["clicked"])
        snapshot, click = [entry for entry in self.calls() if entry["tool"] in ("get_window_state", "click")][-2:]
        self.assertEqual("more information", snapshot["args"]["query"])
        self.assertEqual({"pid": CHROME, "window_id": 11, "element_token": "s00000042:9"}, click["args"])
        status, value = self.call("POST", "/v1/mac/act", {"action": "click", "label": "Save"})
        self.assertEqual(409, status)
        self.assertEqual("ambiguous", value["error"]["code"])
        self.assertEqual([1, 2], [option["index"] for option in value["error"]["options"]])
        status, value = self.call("POST", "/v1/mac/act", {"action": "click", "label": "Save", "index": 2})
        self.assertEqual(200, status, value)
        self.assertEqual("s00000042:12", [entry for entry in self.calls() if entry["tool"] == "click"][-1]["args"]["element_token"])
        status, value = self.call("POST", "/v1/mac/act", {"action": "click", "label": "Open settings", "app": "Heptabase"})
        self.assertEqual(200, status, value)
        self.assertEqual("button", value["clicked"]["role"], "an unlabeled button is found by its text")
        status, value = self.call("POST", "/v1/mac/act", {"action": "click", "label": "Launch rockets"})
        self.assertEqual(404, status)
        self.assertEqual("element_not_found", value["error"]["code"])

    def test_type_text_never_logs_the_words(self) -> None:
        status, value = self.call("POST", "/v1/mac/act", {"action": "type_text", "text": SECRET_TEXT, "label": "Search"})
        self.assertEqual(200, status, value)
        self.assertEqual({"ok": True, "action": "type_text", "app": "Google Chrome", "characters": len(SECRET_TEXT),
                          "effect": "unverifiable"}, value)
        typed = [entry["args"] for entry in self.calls() if entry["tool"] == "type_text"]
        self.assertEqual("s00000042:13", typed[0]["element_token"])
        self.assertNotIn(SECRET_TEXT, self.log.getvalue())
        self.assertEqual(400, self.call("POST", "/v1/mac/act", {"action": "type_text", "text": "a\x07b"})[0])
        self.assertEqual(400, self.call("POST", "/v1/mac/act", {"action": "type_text", "text": "x" * 2001})[0])

    def test_menus_scroll_and_the_action_allowlist(self) -> None:
        status, value = self.call("POST", "/v1/mac/act", {"action": "invoke_menu", "path": "File > New Window"})
        self.assertEqual(200, status, value)
        self.assertEqual({"pid": CHROME, "window_id": 11, "path": ["File", "New Window"]},
                         [entry["args"] for entry in self.calls() if entry["tool"] == "invoke_menu"][0])
        for path in (["Apple", "Shut Down…"], ["Finder", "Empty Trash…"], ["File", "Log Out Samin…"]):
            with self.subTest(path=path):
                self.assertEqual(403, self.call("POST", "/v1/mac/act", {"action": "invoke_menu", "path": path})[0])
        status, value = self.call("POST", "/v1/mac/act", {"action": "scroll", "direction": "down", "amount": 3})
        self.assertEqual(200, status, value)
        self.assertEqual(400, self.call("POST", "/v1/mac/act", {"action": "scroll", "direction": "sideways"})[0])
        self.assertEqual(400, self.call("POST", "/v1/mac/act", {"action": "scroll", "direction": "up", "amount": 99})[0])
        for action in ("kill_app", "shell", "run", "launch_app", None):
            with self.subTest(action=action):
                status, value = self.call("POST", "/v1/mac/act", {"action": action, "pid": CHROME})
                self.assertEqual(400, status)
                self.assertEqual("invalid_action", value["error"]["code"])
        self.assertNotIn("kill_app", self.tools())
        status, value = self.call("POST", "/v1/mac/act", {"action": "bring_to_front", "app": "Heptabase"})
        self.assertEqual(200, status, value)
        self.assertTrue(value["frontmost"])

    # ------------------------------------------------------------------ screenshot

    def test_screenshot_without_screen_recording_is_a_clean_409(self) -> None:
        status, value = self.call("GET", "/v1/mac/screenshot")
        self.assertEqual(409, status)
        self.assertEqual("screen_recording_required", value["error"]["code"])
        self.assertEqual("screen_recording_required", value["code"])
        self.assertEqual(f"Run on the Mac: {self.driver} permissions grant", value["fix"])
        self.assertEqual(value["fix"], value["error"]["fix"])
        self.assertNotIn("get_desktop_state", self.tools(), "no capture is attempted (it could raise a prompt)")

    @unittest.skipUnless(os.path.exists("/usr/bin/sips"), "needs macOS sips")
    def test_screenshot_is_a_small_downscaled_jpeg(self) -> None:
        self.update_state(permissions={"accessibility": True, "screen_recording": True})
        status, value = self.call("GET", "/v1/mac/screenshot?max=1024")
        self.assertEqual(200, status, value)
        self.assertEqual("image/jpeg", value["mime"])
        import base64
        data = base64.b64decode(value["base64"])
        self.assertTrue(data.startswith(b"\xff\xd8"))
        self.assertLessEqual(len(data), mac.MAX_SCREENSHOT_BYTES)
        self.assertLessEqual(max(value["width"], value["height"]), 1024)
        self.assertGreater(value["width"], 0)
        self.assertEqual([], list(Path(self.control._scratch).iterdir()), "temporary captures are deleted")  # noqa: SLF001
        status, value = self.call("GET", "/v1/mac/screenshot?app=Heptabase&max=600")
        self.assertEqual(200, status, value)
        self.assertEqual("Heptabase", value["app"])
        self.assertLessEqual(max(value["width"], value["height"]), 600)

    # ------------------------------------------------------------------ serialization and failures

    def test_driver_calls_never_overlap(self) -> None:
        self.update_state(slow={"get_accessibility_tree": 0.3})
        results: list[int] = []
        threads = [threading.Thread(target=lambda: results.append(self.call("GET", "/v1/mac/state")[0]))
                   for _ in range(3)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(30)
        self.assertEqual([200, 200, 200], results)
        spans = sorted((entry["start"], entry["end"]) for entry in self.calls()
                       if entry["tool"] != "check_permissions")
        for (_, end), (start, _) in zip(spans, spans[1:]):
            self.assertLessEqual(end, start + 0.05, "one cua-driver call at a time")

    def test_a_hung_driver_times_out_with_a_clear_error(self) -> None:
        self.control.driver = mac.CuaDriver(str(self.driver), timeout=1.0)
        self.update_state(slow={"get_accessibility_tree": 3})
        status, value = self.call("GET", "/v1/mac/state")
        self.assertEqual(504, status)
        self.assertEqual("driver_timeout", value["error"]["code"])
        self.assertTrue(value["error"]["retryable"])

    def test_no_accessibility_permission_is_explained(self) -> None:
        self.update_state(permissions={"accessibility": False, "screen_recording": False})
        self.control._permissions = None  # noqa: SLF001
        status, value = self.call("GET", "/v1/mac/state")
        self.assertEqual(409, status)
        self.assertEqual("accessibility_required", value["error"]["code"])
        self.assertIn("permissions grant", value["fix"])


class PureFunctionTest(unittest.TestCase):
    def test_key_parsing(self) -> None:
        self.assertEqual(["cmd", "shift", "t"], mac._keys("Shift+Command+T"))  # noqa: SLF001
        self.assertEqual(["return"], mac._keys(["enter"]))  # noqa: SLF001
        self.assertEqual(["cmd", "option", "i"], mac._keys(["⌘", "⌥", "i"]))  # noqa: SLF001
        for bad in ("", "cmd+a+b", 42, ["cmd", 3], "cmd+shift+q", "hyper+x"):
            with self.subTest(keys=bad):
                with self.assertRaises(mac.MacError):
                    mac._keys(bad)  # noqa: SLF001

    def test_app_matching(self) -> None:
        apps = [{"name": "Google Chrome", "bundle_id": "com.google.Chrome"}, {"name": "T3 Code (Alpha)"},
                {"name": "Notion Calendar"}, {"name": "Notion"}, {"name": "‎WhatsApp"}]
        self.assertEqual("Google Chrome", mac._best_app("chrome", apps)["name"])  # noqa: SLF001
        self.assertEqual("T3 Code (Alpha)", mac._best_app("T3", apps)["name"])  # noqa: SLF001
        self.assertEqual("Notion", mac._best_app("notion", apps)["name"])  # noqa: SLF001
        self.assertEqual("‎WhatsApp", mac._best_app("whatsapp", apps)["name"])  # noqa: SLF001
        self.assertEqual("Google Chrome", mac._best_app("com.google.chrome", apps)["name"])  # noqa: SLF001
        self.assertIsNone(mac._best_app("excel", apps))  # noqa: SLF001

    def test_driver_discovery_prefers_the_newest_install(self) -> None:
        with tempfile.TemporaryDirectory() as home:
            for version in ("0.9.1", "0.21.0", "0.3.0"):
                path = Path(home, f".hermes/tools/cua-driver-{version}-darwin-arm64/CuaDriver.app/Contents/MacOS")
                path.mkdir(parents=True)
                (path / "cua-driver").write_text("#!/bin/sh\n")
                (path / "cua-driver").chmod(0o755)
            old_home, old_path = os.environ.get("HOME"), os.environ.get("PATH")
            os.environ["HOME"], os.environ["PATH"] = home, "/usr/bin:/bin"
            try:
                found = mac.CuaDriver().executable() or ""
            finally:
                os.environ["HOME"] = old_home or ""
                os.environ["PATH"] = old_path or ""
            if shutil.which("cua-driver", path="/usr/bin:/bin") is None and \
                    not os.path.exists("/Applications/CuaDriver.app/Contents/MacOS/cua-driver"):
                self.assertIn("cua-driver-0.21.0-", found)


if __name__ == "__main__":
    unittest.main()
