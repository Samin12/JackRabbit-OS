"""In-process fakes for the Heptabase journal tests: an OAuth 2.1 authorization
server (DCR, PKCE S256, rotating refresh tokens, revocation) plus a stateless
Streamable-HTTP MCP server with a journal store and scripted failure modes.

Nothing here talks to the real Heptabase. Tokens are random test values.
"""

from __future__ import annotations

import base64
from collections import deque
from dataclasses import dataclass, field
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import secrets
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlencode, urlsplit

from sam_runtime.domains.heptabase_journal import HeptabaseEndpoints, HeptabaseJournalService
from sam_runtime.domains.heptabase_journal.client import HttpTransport
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.storage.sessions import SessionTranscriptRepository

JOURNAL_TOOLS = ("read_journal_range", "append_to_journal", "search_by_semantic")


class FakeBridge:
    """Stands in for the Android Keystore bridge; 'seals' by reversible obfuscation."""

    def __init__(self) -> None:
        self.sealed: list[str] = []

    def sealConnectionCredential(self, record_name: str, plaintext: str) -> str:  # noqa: N802
        self.sealed.append(record_name)
        return "v1." + base64.b64encode((record_name + "\n" + plaintext).encode()[::-1]).decode()

    def openConnectionCredential(self, record_name: str, envelope: str) -> str:  # noqa: N802
        raw = base64.b64decode(envelope[3:])[::-1].decode()
        name, _, plaintext = raw.partition("\n")
        assert name == record_name, "envelope opened under the wrong record name"
        return plaintext


class FakeClock:
    def __init__(self, start: float | None = None) -> None:
        self.now = float(start if start is not None else time.time())

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@dataclass
class Grant:
    client_id: str
    refresh_token: str
    revoked: bool = False
    old_refresh: set[str] = field(default_factory=set)
    grant_id: str = field(default_factory=lambda: secrets.token_hex(8))
    issued_refresh: set[str] = field(default_factory=set)  # older, still-valid tokens of a reused grant


class FakeHeptabase:
    def __init__(self) -> None:
        self.clients: dict[str, list[str]] = {}
        self.registrations: list[dict[str, object]] = []
        self.codes: dict[str, dict[str, str]] = {}
        self.access: dict[str, Grant] = {}
        self.grants: list[Grant] = []
        self.revoked: list[str] = []
        self.journal: dict[str, list[str]] = {}
        self.calls: list[tuple[str, dict[str, object]]] = []
        self.token_requests: list[dict[str, str]] = []
        self.failures: deque[str] = deque()
        self.refresh_failures: deque[str] = deque()
        self.tools = list(JOURNAL_TOOLS)
        self.expires_in = 172_800
        self.rotate = True
        self.issue_refresh = True
        # node-oidc-provider semantics: JWT access tokens carry grant_id, a new authorization
        # for the same client reuses the live grant, and revoking any token revokes the grant.
        self.oidc_grants = False
        self.lock = threading.Lock()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _handler(self))
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self._thread.start()
        self.base = f"http://127.0.0.1:{self._server.server_address[1]}"

    @property
    def endpoints(self) -> HeptabaseEndpoints:
        return HeptabaseEndpoints(
            issuer=self.base, authorization=self.base + "/auth", token=self.base + "/token",
            registration=self.base + "/v1/oauth/register", revocation=self.base + "/token/revocation",
            mcp=self.base + "/mcp", resource=self.base + "/mcp",
        )

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def appends(self) -> list[tuple[str, str]]:
        return [(str(args["date"]), str(args["content"])) for name, args in self.calls if name == "append_to_journal"]

    def expire_all_access_tokens(self) -> None:
        with self.lock:
            self.access.clear()

    # -- browser simulation -------------------------------------------------

    def authorize(self, authorization_url: str, *, deny: bool = False) -> dict[str, str]:
        """Act as the user's browser: GET /auth and return the redirect's query params."""
        import http.client

        parsed = urlsplit(authorization_url)
        connection = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=5)
        connection.request("GET", parsed.path + "?" + parsed.query + ("&deny=1" if deny else ""))
        response = connection.getresponse()
        response.read()
        location = response.getheader("Location") or ""
        connection.close()
        assert response.status == 302, f"authorize failed: {response.status}"
        return {key: values[0] for key, values in parse_qs(urlsplit(location).query).items()}

    # -- internals ------------------------------------------------------------

    def _new_tokens(self, grant: Grant) -> dict[str, object]:
        access = "at-" + secrets.token_urlsafe(24)
        if self.oidc_grants:
            claims = json.dumps({"grant_id": grant.grant_id, "client_id": grant.client_id, "jti": access})
            access = "eyJhbGciOiJSUzI1NiJ9." + base64.urlsafe_b64encode(claims.encode()).rstrip(b"=").decode() + ".sig"
        self.access[access] = grant
        payload: dict[str, object] = {"access_token": access, "token_type": "Bearer",
                                      "expires_in": self.expires_in, "scope": "space:read space:write offline_access"}
        if self.issue_refresh:
            payload["refresh_token"] = grant.refresh_token
        return payload


def _redirect_matches(registered: list[str], candidate: str) -> bool:
    for uri in registered:
        if uri == candidate:
            return True
        a, b = urlsplit(uri), urlsplit(candidate)
        if a.hostname in ("127.0.0.1", "localhost") and a.scheme == b.scheme == "http" \
                and a.hostname == b.hostname and a.path == b.path:
            return True  # RFC 8252 loopback: any port
    return False


def _handler(fake: FakeHeptabase):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args) -> None:
            return

        def _json(self, status: int, payload: dict[str, object]) -> None:
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _body(self) -> bytes:
            return self.rfile.read(int(self.headers.get("Content-Length", "0")))

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlsplit(self.path)
            if parsed.path != "/auth":
                self._json(404, {"error": "not_found"})
                return
            q = {key: values[0] for key, values in parse_qs(parsed.query).items()}
            registered = fake.clients.get(q.get("client_id", ""))
            ok = (registered is not None and q.get("response_type") == "code"
                  and q.get("code_challenge_method") == "S256" and q.get("code_challenge")
                  and q.get("resource") == fake.base + "/mcp" and "offline_access" in q.get("scope", "")
                  and q.get("prompt") == "consent" and q.get("state")
                  and _redirect_matches(registered, q.get("redirect_uri", "")))
            if not ok:
                self._json(400, {"error": "invalid_request"})
                return
            if q.get("deny"):
                params = {"error": "access_denied", "state": q["state"], "iss": fake.base}
            else:
                code = "code-" + secrets.token_urlsafe(16)
                fake.codes[code] = {"client_id": q["client_id"], "redirect_uri": q["redirect_uri"],
                                    "challenge": q["code_challenge"]}
                params = {"code": code, "state": q["state"], "iss": fake.base}
            self.send_response(302)
            self.send_header("Location", q["redirect_uri"] + "?" + urlencode(params))
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_POST(self) -> None:  # noqa: N802
            path = urlsplit(self.path).path
            if path == "/v1/oauth/register":
                body = json.loads(self._body() or b"{}")
                uris = body.get("redirect_uris")
                if not isinstance(uris, list) or not uris or body.get("token_endpoint_auth_method") != "none":
                    self._json(400, {"error": "invalid_client_metadata"})
                    return
                client_id = "client-" + secrets.token_hex(6)
                fake.clients[client_id] = list(uris)
                fake.registrations.append(body)
                self._json(201, {"client_id": client_id, "redirect_uris": uris})
                return
            if path == "/token":
                form = {key: values[0] for key, values in parse_qs(self._body().decode()).items()}
                fake.token_requests.append(form)
                with fake.lock:
                    self._token(form)
                return
            if path == "/token/revocation":
                form = {key: values[0] for key, values in parse_qs(self._body().decode()).items()}
                token = form.get("token", "")
                fake.revoked.append(token)
                with fake.lock:
                    owner = fake.access.pop(token, None)
                    if owner is not None and fake.oidc_grants:
                        owner.revoked = True
                    for grant in fake.grants:
                        if grant.refresh_token == token or token in grant.issued_refresh:
                            grant.revoked = True
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if path == "/mcp":
                self._mcp()
                return
            self._json(404, {"error": "not_found"})

        def _token(self, form: dict[str, str]) -> None:
            if form.get("resource") != fake.base + "/mcp":
                self._json(400, {"error": "invalid_target"})
                return
            if form.get("grant_type") == "authorization_code":
                code = fake.codes.pop(form.get("code", ""), None)
                verifier = form.get("code_verifier", "")
                challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
                if (code is None or code["client_id"] != form.get("client_id")
                        or code["redirect_uri"] != form.get("redirect_uri") or code["challenge"] != challenge):
                    self._json(400, {"error": "invalid_grant", "error_description": "grant request is invalid"})
                    return
                grant = next((item for item in fake.grants if fake.oidc_grants and not item.revoked
                              and item.client_id == form["client_id"]), None)
                if grant is None:
                    grant = Grant(form["client_id"], "rt-" + secrets.token_urlsafe(24))
                    fake.grants.append(grant)
                else:
                    grant.issued_refresh.add(grant.refresh_token)
                    grant.refresh_token = "rt-" + secrets.token_urlsafe(24)
                self._json(200, fake._new_tokens(grant))
                return
            if form.get("grant_type") == "refresh_token":
                if fake.refresh_failures:
                    mode = fake.refresh_failures.popleft()
                    if mode == "invalid_grant":
                        self._json(400, {"error": "invalid_grant"})
                    else:
                        self._json(int(mode), {"error": "server_error"})
                    return
                token = form.get("refresh_token", "")
                for grant in fake.grants:
                    if token in grant.old_refresh:
                        grant.revoked = True  # reuse of a rotated token kills the grant
                    if grant.refresh_token == token and not grant.revoked and grant.client_id == form.get("client_id"):
                        if fake.rotate:
                            grant.old_refresh.add(grant.refresh_token)
                            grant.refresh_token = "rt-" + secrets.token_urlsafe(24)
                        self._json(200, fake._new_tokens(grant))
                        return
                self._json(400, {"error": "invalid_grant"})
                return
            self._json(400, {"error": "unsupported_grant_type"})

        def _mcp(self) -> None:
            accept = self.headers.get("Accept", "")
            if "application/json" not in accept or "text/event-stream" not in accept:
                self._json(406, {"error": "Not Acceptable"})
                return
            auth = self.headers.get("Authorization", "")
            token = auth[7:] if auth.startswith("Bearer ") else ""
            body = self._body()
            with fake.lock:
                grant = fake.access.get(token)
                valid = grant is not None and not grant.revoked
            if not valid:
                self.send_response(401)
                self.send_header("WWW-Authenticate", 'Bearer realm="MCP API" error="invalid_token"')
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            if fake.failures and fake.failures[0] == "403":
                fake.failures.popleft()
                self._json(403, {"error": "insufficient_scope"})
                return
            message = json.loads(body)
            method = message.get("method")
            request_id = message.get("id")
            if method == "tools/list":
                result: dict[str, object] = {"tools": [{"name": name, "inputSchema": {"type": "object"}} for name in fake.tools]}
                self._sse(request_id, result)
                return
            params = message.get("params") or {}
            name = params.get("name")
            arguments = params.get("arguments") or {}
            fake.calls.append((str(name), dict(arguments)))
            if name == "append_to_journal":
                mode = fake.failures.popleft() if fake.failures else None
                if mode == "500_before":
                    self._json(503, {"error": "unavailable"})
                    return
                date = str(arguments.get("date", ""))
                content = str(arguments.get("content", ""))
                if mode in ("invalidHeptaMarkdown", "invalidDate") or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date):
                    reason = mode or "invalidDate"
                    self._sse(request_id, {"content": [{"type": "text", "text": "failed"}], "isError": True,
                                           "structuredContent": {"status": "failed", "content": "",
                                                                 "failureReasonCode": reason}})
                    return
                if mode == "plain_only" and ("**" in content or "<hepta" in content):
                    self._sse(request_id, {"content": [], "structuredContent": {
                        "status": "failed", "content": "", "failureReasonCode": "invalidHeptaMarkdown"}})
                    return
                with fake.lock:
                    fake.journal.setdefault(date, []).extend(content.split("\n"))
                if mode == "500_after":
                    self._json(502, {"error": "bad gateway"})
                    return
                if mode == "reset_after":
                    self.connection.close()
                    return
                self._sse(request_id, {"content": [{"type": "text", "text": "ok"}],
                                       "structuredContent": {"status": "succeeded", "content": content, "date": date}})
                return
            if name == "read_journal_range":
                mode = fake.failures[0] if fake.failures and fake.failures[0] == "read_500" else None
                if mode:
                    fake.failures.popleft()
                    self._json(500, {"error": "boom"})
                    return
                start, end = str(arguments.get("startDate")), str(arguments.get("endDate"))
                blocks = []
                for day in sorted(fake.journal):
                    if start <= day <= end:
                        lines = [line.replace("_", "\\_") for line in fake.journal[day]]
                        blocks.append(f"journal [{day}] {len(lines)} lines\n"
                                      + "\n".join(f"{i}\t{line}" for i, line in enumerate(lines, 1)))
                text = "\n".join(blocks)
                self._sse(request_id, {"content": [{"type": "text", "text": text}],
                                       "structuredContent": {"status": "succeeded", "content": text}})
                return
            self._sse(request_id, {"content": [], "isError": True})

        def _sse(self, request_id: object, result: dict[str, object]) -> None:
            data = json.dumps({"result": result, "jsonrpc": "2.0", "id": request_id})
            body = f"event: message\ndata: {data}\n\n".encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return Handler


class ScriptedTransport(HttpTransport):
    """Real HTTP, except scripted exceptions raised for MCP calls (network faults)."""

    def __init__(self, *, timeout: float = 3.0) -> None:
        super().__init__(timeout=timeout)
        self.script: deque[BaseException] = deque()

    def request(self, method, url, **kwargs):  # noqa: ANN001
        if url.endswith("/mcp") and self.script:
            raise self.script.popleft()
        return super().request(method, url, **kwargs)


class JournalHarness:
    """A migrated database + journal service wired to a fake Heptabase."""

    def __init__(self, *, clock: FakeClock | None = None,
                 connections: object | None = None, timeout: float = 3.0,
                 transport: HttpTransport | None = None) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.fake = FakeHeptabase()
        self.bridge = FakeBridge()
        self.clock = clock or FakeClock()
        self.credentials = ConnectionCredentialRepository(self.database)
        self.sessions = SessionTranscriptRepository(self.database)
        self.service = HeptabaseJournalService(
            database=self.database, credentials=self.credentials,
            envelopes=ConnectionCredentialEnvelopes(self.bridge), sessions=self.sessions, connections=connections,
            endpoints=self.fake.endpoints, transport=transport or HttpTransport(timeout=timeout), clock=self.clock,
            deliver_timeout=3.0,
        )

    def connect(self, redirect: str = "loopback", origin: str | None = None) -> dict[str, object]:
        started = self.service.connect_start(redirect, origin)
        params = self.fake.authorize(str(started["authorizationUrl"]))
        return self.service.complete_authorization(state=params["state"], code=params.get("code"),
                                                   issuer=params.get("iss"), error=params.get("error"))

    def rows(self) -> list[dict[str, object]]:
        with self.database.connect() as connection:
            return [dict(row) for row in connection.execute(
                "SELECT * FROM journal_outbox ORDER BY journal_date, event_at, created_at, rowid").fetchall()]

    def close(self) -> None:
        self.service.stop()
        self.fake.close()
        self.directory.cleanup()
