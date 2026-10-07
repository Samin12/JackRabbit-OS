"""In-process stand-in for the SamRabbit Mac bridge (companion/mac-bridge) with scripted failures.

The journal store keeps the appended markdown; ``read`` returns it as the plain text the real
bridge produces from Heptabase's ProseMirror (bold/italic marks and escapes gone). A literal
``<hepta-color>`` tag would survive, as it does in the real CLI.
"""

from __future__ import annotations

from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import secrets
import threading
from urllib.parse import parse_qs, urlsplit

_ESCAPE = re.compile(r"\\([!-/:-@\[-`{-~])")
_EMPHASIS = re.compile(r"(?<!\\)\*")


def plain_text(markdown: str) -> str:
    lines = []
    for block in markdown.split("\n"):
        text = _ESCAPE.sub(r"\1", _EMPHASIS.sub("", block))
        if text.startswith("- "):
            text = "- " + text[2:]
        if text.strip():
            lines.append(text)
    return "\n".join(lines)


class FakeMacBridge:
    """Failure modes (popped per append/read): ``app_down`` (503, written false),
    ``timeout_after`` (writes, then 504 written unknown), ``error_unknown`` (503 written
    unknown, nothing written), ``reset_after`` (writes, then drops the connection),
    ``reject`` (422), ``unauthorized`` (401), ``read_down`` (503 on read)."""

    def __init__(self, token: str | None = None) -> None:
        self.token = token or "bridge-" + secrets.token_urlsafe(24)
        self.journal: dict[str, list[str]] = {}
        self.calls: list[tuple[str, str]] = []
        self.failures: deque[str] = deque()
        self.app_reachable = True
        self.service = "samrabbit-bridge"
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

    def appends(self) -> list[tuple[str, str]]:
        with self.lock:
            return [(day, content) for day, items in sorted(self.journal.items()) for content in items]


def _handler(fake: FakeMacBridge) -> type[BaseHTTPRequestHandler]:
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

        def _error(self, status: int, code: str, written: object = False, **extra: object) -> None:
            self._json(status, {"error": {"code": code, "message": code, "retryable": status >= 500,
                                          "written": written, **extra}})

        def _authorized(self) -> bool:
            header = self.headers.get("Authorization", "")
            fake.seen_authorization.append(header)
            if header != f"Bearer {fake.token}":
                self._error(401, "unauthorized")
                return False
            return True

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlsplit(self.path)
            fake.calls.append(("GET", parsed.path))
            if not self._authorized():
                return
            if parsed.path == "/health":
                if fake.service != "samrabbit-bridge":
                    self._json(200, {"hello": "world"})
                    return
                self._json(200, {"ok": True, "service": fake.service, "version": "1.0.0",
                                 "cli": {"available": True, "version": "0.7.0"},
                                 "app": {"reachable": fake.app_reachable,
                                         "detail": None if fake.app_reachable else "heptabase_app_unavailable"}})
                return
            if parsed.path == "/v1/heptabase/journal/read":
                if fake.failures and fake.failures[0] == "read_down":
                    fake.failures.popleft()
                    self._error(503, "heptabase_app_unavailable")
                    return
                day = parse_qs(parsed.query).get("date", [""])[0]
                with fake.lock:
                    text = "\n".join(plain_text(item) for item in fake.journal.get(day, []))
                self._json(200, {"date": day, "title": "Oct 7, 2026", "text": text, "contentMd5": "0" * 32})
                return
            self._error(404, "not_found")

        def do_POST(self) -> None:  # noqa: N802
            parsed = urlsplit(self.path)
            fake.calls.append(("POST", parsed.path))
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
            if not self._authorized():
                return
            if parsed.path != "/v1/heptabase/journal/append":
                self._error(404, "not_found")
                return
            mode = fake.failures.popleft() if fake.failures else None
            if mode == "unauthorized":
                self._error(401, "unauthorized")
                return
            if mode == "app_down":
                self._error(503, "heptabase_app_unavailable", written=False)
                return
            if mode == "error_unknown":
                self._error(503, "heptabase_app_error", written="unknown")
                return
            if mode == "reject":
                self._error(422, "heptabase_rejected", reason="invalidInput")
                return
            if mode == "reject_rich" and ("**" in body["content"] or "<hepta" in body["content"]):
                self._error(422, "heptabase_rejected", reason="invalidInput")
                return
            with fake.lock:
                fake.journal.setdefault(str(body["date"]), []).append(str(body["content"]))
            if mode == "timeout_after":
                self._error(504, "heptabase_cli_timeout", written="unknown")
                return
            if mode == "reset_after":
                self.connection.close()
                return
            self._json(200, {"date": body["date"], "title": "Oct 7, 2026", "contentMd5": "f" * 32})

    return Handler
