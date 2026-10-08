"""In-process stand-in for the Mac-control half of the SamRabbit Mac bridge, plus wiring helpers.

``FakeMacControlBridge`` answers ``/health`` (with ``mac`` capabilities) and ``/v1/mac/*`` behind a
bearer token, records every request, and can be scripted per route (``responses``).
"""

from __future__ import annotations

import base64
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
import threading
from urllib.parse import parse_qs, urlsplit

from sam_runtime.domains.heptabase_journal.bridge import BridgeStore
from sam_runtime.domains.mac import MacControlClient
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository

from t3_fixtures import StubBridge

# A real (tiny) JPEG: SOI, APP0, a 2x2 SOF0 and EOI are enough for the shape tests.
TINY_JPEG = bytes.fromhex("ffd8ffe000104a46494600010100000100010000ffc0000b080002000201011100ffd9")
FIX = "Run on the Mac: /Applications/CuaDriver.app/Contents/MacOS/cua-driver permissions grant"


def capabilities(*, screen: bool = False) -> dict[str, object]:
    value: dict[str, object] = {
        "driver": {"available": True, "version": "0.21.0", "daemon": True,
                   "path": "/Applications/CuaDriver.app/Contents/MacOS/cua-driver"},
        "permissions": {"accessibility": True, "screenRecording": screen},
        "features": {"state": True, "open": True, "read": True, "act": True, "screenshot": screen},
        "computer": "Test Mac Studio",
    }
    if not screen:
        value["screenRecordingFix"] = FIX
    return value


def default_state() -> dict[str, object]:
    return {
        "computer": "Test Mac Studio",
        "front": {"app": "Google Chrome", "window": "Example Domain"},
        "visible": [{"app": "Google Chrome", "windows": ["Example Domain", "Inbox", "Docs"]},
                    {"app": "Heptabase", "windows": ["Journal | Heptabase"]}],
        "running": [f"App {index}" for index in range(40)],
        "chrome": [{"window": "Example Domain", "activeTab": "Example Domain", "activeUrl": "example.com/",
                    "tabs": [f"Tab {index}" for index in range(12)]}],
        "screenVision": False,
    }


class FakeMacControlBridge:
    def __init__(self, token: str | None = None) -> None:
        self.token = token or "bridge-" + secrets.token_urlsafe(24)
        self.requests: list[dict[str, object]] = []
        self.responses: dict[str, tuple[int, dict[str, object]]] = {}
        self.state = default_state()
        self.screen = False
        self.seen_authorization: list[str] = []
        self.lock = threading.Lock()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _handler(self))
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self._thread.start()
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def paths(self) -> list[str]:
        with self.lock:
            return [str(item["path"]) for item in self.requests]

    def last(self, path: str) -> dict[str, object]:
        with self.lock:
            return [item for item in self.requests if item["path"] == path][-1]


def _handler(fake: FakeMacControlBridge) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args) -> None:  # noqa: ANN002
            pass

        def _json(self, status: int, payload: dict[str, object]) -> None:
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _serve(self, method: str) -> None:
            parts = urlsplit(self.path)
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            header = self.headers.get("Authorization", "")
            with fake.lock:
                fake.seen_authorization.append(header)
                fake.requests.append({"method": method, "path": parts.path, "query": parse_qs(parts.query),
                                      "body": json.loads(raw) if raw else None})
            if header != f"Bearer {fake.token}":
                self._json(401, {"error": {"code": "unauthorized", "message": "A valid bridge token is required."}})
                return
            scripted = fake.responses.get(parts.path)
            if scripted is not None:
                self._json(*scripted)
                return
            if parts.path == "/health":
                self._json(200, {"ok": True, "service": "samrabbit-bridge", "version": "1.1.0",
                                 "cli": {"available": True, "version": "0.7.0"}, "app": {"reachable": True},
                                 "mac": capabilities(screen=fake.screen)})
            elif parts.path == "/v1/mac/state":
                self._json(200, fake.state)
            elif parts.path == "/v1/mac/open":
                body = json.loads(raw or b"{}")
                self._json(200, {"ok": True, "opened": "app" if "app" in body else "url",
                                 "app": body.get("app", "Google Chrome"), "frontmost": True})
            elif parts.path == "/v1/mac/read":
                self._json(200, {"app": "Google Chrome", "window": "Example Domain",
                                 "text": "# Example Domain\nThis domain is for examples. " + "word " * 3000,
                                 "controls": [f"Button {index}" for index in range(60)], "truncated": True})
            elif parts.path == "/v1/mac/act":
                body = json.loads(raw or b"{}")
                self._json(200, {"ok": True, "action": body.get("action"), "app": "Google Chrome",
                                 "effect": "confirmed"})
            elif parts.path == "/v1/mac/screenshot":
                if not fake.screen:
                    self._json(409, {"error": {"code": "screen_recording_required", "fix": FIX, "retryable": False,
                                               "message": "Screen vision is off."},
                                     "code": "screen_recording_required", "fix": FIX})
                else:
                    self._json(200, {"mime": "image/jpeg", "base64": base64.b64encode(TINY_JPEG).decode(),
                                     "width": 1024, "height": 640, "bytes": len(TINY_JPEG)})
            else:
                self._json(404, {"error": {"code": "not_found", "message": "Not found."}})

        def do_GET(self) -> None:  # noqa: N802
            self._serve("GET")

        def do_POST(self) -> None:  # noqa: N802
            self._serve("POST")

    return Handler


def make_mac(database, fake: FakeMacControlBridge | None = None) -> tuple[BridgeStore, MacControlClient]:
    """A BridgeStore and MacControlClient over ``database``; configured for ``fake`` when given."""
    store = BridgeStore(database, ConnectionCredentialRepository(database), ConnectionCredentialEnvelopes(StubBridge()))
    if fake is not None:
        store.save(fake.url, fake.token)
    return store, MacControlClient(store)
