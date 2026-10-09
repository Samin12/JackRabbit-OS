"""Stand-ins for OpenAI's side of the realtime voice (tests only, loopback HTTP):

* ``FakeIssuer``: the ChatGPT device login (``auth.openai.com``): ``/api/accounts/deviceauth/usercode``,
  ``/api/accounts/deviceauth/token`` (403 for the first ``pending`` polls), ``/oauth/token`` (the authorization code
  exchange, form-encoded, and refreshes, JSON; ``refuse_refresh`` answers 400 ``invalid_grant``);
* ``FakeSignaling``: ``POST /v1/realtime/calls`` (multipart ``sdp`` + ``session``) answering a fake SDP, or the
  statuses queued in ``statuses``.

Both record what they got (``requests``), so tests can check the exact shape, and that no real service was used.
"""

from __future__ import annotations

import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional
from urllib.parse import parse_qsl


def jwt(claims: Dict[str, Any]) -> str:
    def part(value: Dict[str, Any]) -> str:
        return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")

    return part({"alg": "none"}) + "." + part(claims) + ".fake-signature"


class _Server:
    def __init__(self, handler: Any) -> None:
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


class FakeIssuer(_Server):
    def __init__(self) -> None:
        self.requests: List[Dict[str, Any]] = []
        self.pending = 1
        self.polls = 0
        self.issued = 0
        self.refuse_refresh = False
        self.fail_refresh = False
        self.expires_in = 3600
        self.plan = "pro"
        self.email = "samin@example.com"
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args: Any) -> None:
                return

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                kind = str(self.headers.get("Content-Type") or "")
                body: Dict[str, Any] = dict(parse_qsl(raw.decode())) if "form-urlencoded" in kind else \
                    (json.loads(raw or b"{}") if raw else {})
                fake.requests.append({"path": self.path, "contentType": kind, "body": body,
                                      "userAgent": self.headers.get("User-Agent")})
                status, value = fake.answer(self.path, body)
                data = json.dumps(value).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        super().__init__(Handler)

    def tokens(self) -> Dict[str, Any]:
        self.issued += 1
        claims = {"email": self.email, "https://api.openai.com/auth": {
            "chatgpt_plan_type": self.plan, "chatgpt_account_id": "acct-fake-1"}}
        return {"access_token": jwt({"exp": 0, "n": self.issued, "aud": "https://api.openai.com/v1"}),
                "refresh_token": f"refresh-{self.issued}", "id_token": jwt(claims), "expires_in": self.expires_in,
                "token_type": "Bearer"}

    def answer(self, path: str, body: Dict[str, Any]) -> Any:
        if path == "/api/accounts/deviceauth/usercode":
            return 200, {"device_auth_id": "device-auth-1", "user_code": "WXYZ-12345", "interval": 1}
        if path == "/api/accounts/deviceauth/token":
            self.polls += 1
            if self.polls <= self.pending:
                return 403, {"error": "authorization_pending"}
            return 200, {"authorization_code": "auth-code-1", "code_verifier": "verifier-1"}
        if path == "/oauth/token":
            if body.get("grant_type") == "refresh_token":
                if self.refuse_refresh:
                    return 400, {"error": "invalid_grant"}
                if self.fail_refresh:
                    return 503, {"error": "busy"}
            return 200, self.tokens()
        return 404, {"error": "not_found"}


def parse_multipart(content_type: str, raw: bytes) -> Dict[str, str]:
    boundary = content_type.split("boundary=", 1)[1].strip()
    fields: Dict[str, str] = {}
    for chunk in raw.split(("--" + boundary).encode()):
        chunk = chunk.strip(b"\r\n")
        if not chunk or chunk == b"--":
            continue
        head, _, value = chunk.partition(b"\r\n\r\n")
        name = head.decode().split('name="', 1)[1].split('"', 1)[0]
        fields[name] = value.decode()
    return fields


class FakeSignaling(_Server):
    def __init__(self) -> None:
        self.requests: List[Dict[str, Any]] = []
        self.statuses: List[int] = []
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args: Any) -> None:
                return

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                kind = str(self.headers.get("Content-Type") or "")
                fields = parse_multipart(kind, raw) if "multipart/form-data" in kind else {}
                fake.requests.append({
                    "path": self.path, "authorization": self.headers.get("Authorization"),
                    "accept": self.headers.get("Accept"), "safety": self.headers.get("OpenAI-Safety-Identifier"),
                    "contentType": kind, "headers": sorted(self.headers.keys()), "fields": sorted(fields),
                    "sdp": fields.get("sdp"),
                    "session": json.loads(fields["session"]) if fields.get("session") else None})
                status = fake.statuses.pop(0) if fake.statuses else 201
                if 200 <= status < 300:
                    data = b"v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\ns=fake answer\r\n"
                    self.send_response(status)
                    self.send_header("Content-Type", "application/sdp")
                    self.send_header("Location", "/v1/realtime/calls/rtc_fake")
                else:
                    data = json.dumps({"error": {"type": "invalid_request_error", "code": "fake_refusal",
                                                 "message": "refused (fake)"}}).encode()
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        super().__init__(Handler)

    def last_session(self) -> Optional[Dict[str, Any]]:
        return self.requests[-1]["session"] if self.requests else None
