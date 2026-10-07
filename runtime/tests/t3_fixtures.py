"""Shared fakes for the T3 tests: a stdlib fake T3 Code server and small sanitized fixtures.

Fixture shapes are modelled on real T3 Code 0.0.45 responses (shell snapshot,
thread detail, activities) but contain no user content.
"""

from __future__ import annotations

import base64
import copy
from datetime import datetime, timedelta
import gzip
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
from urllib.parse import parse_qs, unquote, urlsplit

from sam_runtime.domains.t3.client import T3Endpoint
from sam_runtime.domains.t3.commands import T3CommandBuilder
from sam_runtime.domains.t3.repository import T3Repository
from sam_runtime.domains.t3.service import T3Service
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.storage.announcements import AnnouncementRepository
from sam_runtime.storage.database import RuntimeDatabase


PAIRING_CODE = "ABCD2345EFGH"
PROJECT_MAIN = "11111111-1111-4111-8111-111111111111"
PROJECT_SIDE = "22222222-2222-4222-8222-222222222222"
PROJECT_SCRATCH = "33333333-3333-4333-8333-333333333333"


class StubBridge:
    """Mimics the Java credential bridge without Keystore (tests only)."""

    def __init__(self) -> None:
        self.sealed: list[str] = []

    def sealConnectionCredential(self, record_name: str, plaintext: str) -> str:
        envelope = "stub." + base64.b64encode(f"{record_name}|{plaintext}".encode()).decode()
        self.sealed.append(envelope)
        return envelope

    def openConnectionCredential(self, record_name: str, envelope: str) -> str:
        raw = base64.b64decode(envelope.split(".", 1)[1]).decode()
        name, _, value = raw.partition("|")
        if name != record_name:
            raise ValueError("record mismatch")
        return value


def project(project_id: str, title: str, root: str) -> dict[str, object]:
    return {
        "id": project_id, "title": title, "workspaceRoot": root, "repositoryIdentity": None,
        "defaultModelSelection": None, "scripts": [], "createdAt": "2026-10-01T10:00:00.000Z",
        "updatedAt": "2026-10-01T10:00:00.000Z",
    }


def thread(
    thread_id: str,
    title: str,
    *,
    project_id: str = PROJECT_MAIN,
    session_status: str | None = "ready",
    turn_state: str | None = "completed",
    turn_id: str | None = None,
    completed_at: str | None = "2026-10-07T12:00:00.000Z",
    updated_at: str | None = None,
    approvals: bool = False,
    user_input: bool = False,
    background: str | None = None,
    settled: bool = False,
    model: str = "claude-opus-5-5",
    runtime_mode: str = "full-access",
    plan: dict[str, object] | None = None,
    active_turn: str | None = None,
) -> dict[str, object]:
    turn_id = turn_id or f"turn-{thread_id}"
    started = _minus_minute(completed_at) if completed_at else "2026-10-07T11:59:00.000Z"
    latest = None
    if turn_state is not None:
        latest = {
            "turnId": turn_id, "state": turn_state, "requestedAt": started,
            "startedAt": started,
            "completedAt": completed_at if turn_state != "running" else None,
            "assistantMessageId": f"assistant:{turn_id}",
        }
    session = None
    if session_status is not None:
        session = {
            "threadId": thread_id, "status": session_status, "providerName": "claudeAgent",
            "providerInstanceId": "claudeAgent", "runtimeMode": runtime_mode,
            "activeTurnId": active_turn, "lastError": "Provider crashed" if session_status == "error" else None,
            "updatedAt": "2026-10-07T12:00:00.000Z",
        }
    return {
        "id": thread_id, "projectId": project_id, "title": title,
        "modelSelection": {"instanceId": "claudeAgent", "model": model, "options": [{"id": "effort", "value": "high"}]},
        "runtimeMode": runtime_mode, "interactionMode": "default", "branch": None, "worktreePath": None,
        "pullRequests": [], "latestTurn": latest, "createdAt": "2026-10-07T09:00:00.000Z",
        "updatedAt": updated_at or completed_at or "2026-10-07T12:00:00.000Z", "archivedAt": None,
        "settledOverride": "settled" if settled else None, "settledAt": "2026-10-07T12:30:00.000Z" if settled else None,
        "unsettledAt": None, "snoozedUntil": None, "session": session,
        "latestUserMessageAt": started,
        "hasPendingApprovals": approvals, "hasPendingUserInput": user_input,
        "hasActionableProposedPlan": False, "backgroundLiveness": background, "planProgress": plan,
    }


def _minus_minute(value: str) -> str:
    stamp = datetime.fromisoformat(value.replace("Z", "+00:00")) - timedelta(minutes=1)
    return stamp.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def detail(shell_thread: dict[str, object], *, messages=None, activities=None) -> dict[str, object]:
    body = {key: value for key, value in shell_thread.items() if key not in {
        "hasPendingApprovals", "hasPendingUserInput", "hasActionableProposedPlan", "backgroundLiveness",
        "planProgress", "latestUserMessageAt",
    }}
    body.update({"deletedAt": None, "messages": messages or [], "proposedPlans": [], "activities": activities or [], "checkpoints": []})
    return {"snapshotSequence": 10, "thread": body}


def message(message_id: str, role: str, text: str, *, at: str = "2026-10-07T11:59:30.000Z", streaming: bool = False) -> dict[str, object]:
    return {"id": message_id, "role": role, "text": text, "attachments": [], "turnId": None if role == "user" else "t",
            "streaming": streaming, "createdAt": at, "updatedAt": at}


def approval_requested(request_id: str, *, at: str = "2026-10-07T11:59:40.000Z", kind: str = "command", detail_text: str = "Run the unit tests") -> dict[str, object]:
    return {"id": f"act-{request_id}", "tone": "approval", "kind": "approval.requested", "summary": "Command approval requested",
            "payload": {"requestId": request_id, "requestKind": kind, "requestType": "command_execution_approval", "detail": detail_text},
            "turnId": "t", "createdAt": at}


def approval_resolved(request_id: str, *, at: str = "2026-10-07T11:59:50.000Z") -> dict[str, object]:
    return {"id": f"res-{request_id}", "tone": "approval", "kind": "approval.resolved", "summary": "Approval resolved",
            "payload": {"requestId": request_id, "requestKind": "command", "decision": "accept"}, "turnId": "t", "createdAt": at}


def input_requested(request_id: str, *, at: str = "2026-10-07T11:59:45.000Z", options=("Use SQLite", "Use DuckDB"), multi: bool = False, allow_custom: bool = True, message_mode: bool = False) -> dict[str, object]:
    payload: dict[str, object] = {
        "requestId": request_id,
        "questions": [{"id": "0", "header": "Storage", "question": "Which database should I use?",
                       "options": [{"label": label, "description": ""} for label in options],
                       "allowCustomAnswer": allow_custom, "multiSelect": multi}],
    }
    if message_mode:
        payload["responseMode"] = "message"
    return {"id": f"act-{request_id}", "tone": "info", "kind": "user-input.requested", "summary": "Question",
            "payload": payload, "turnId": "t", "createdAt": at}


class FakeT3Server:
    """In-process T3 Code stand-in. Mutate ``shell``/``details`` between polls."""

    def __init__(self) -> None:
        self.codes = {PAIRING_CODE}
        self.tokens: set[str] = set()
        self.forms: list[dict[str, list[str]]] = []
        self.requests: list[dict[str, object]] = []
        self.dispatched: list[dict[str, object]] = []
        self.fail_dispatch_types: set[str] = set()
        self.shell: dict[str, object] = {
            "snapshotSequence": 1,
            "projects": [
                project(PROJECT_MAIN, "Workbench", "/Users/test/workbench"),
                project(PROJECT_SIDE, "Orbit Lab", "/Users/test/orbit"),
                project(PROJECT_SCRATCH, "No project", "/Users/test/.t3/scratch"),
            ],
            "threads": [],
            "updatedAt": "2026-10-07T12:00:00.000Z",
        }
        self.details: dict[str, dict[str, object]] = {}
        self.unavailable = False
        self._lock = threading.Lock()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)

    def __enter__(self) -> "FakeT3Server":
        self._thread.start()
        return self

    def __exit__(self, *_: object) -> None:
        self._server.shutdown()
        self._server.server_close()

    @property
    def port(self) -> int:
        return int(self._server.server_address[1])

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    @property
    def endpoint(self) -> T3Endpoint:
        return T3Endpoint("http", "127.0.0.1", self.port)

    def set_threads(self, threads: list[dict[str, object]]) -> None:
        with self._lock:
            self.shell = {**self.shell, "threads": copy.deepcopy(threads),
                          "snapshotSequence": int(self.shell["snapshotSequence"]) + 1}

    def set_detail(self, thread_id: str, value: dict[str, object]) -> None:
        with self._lock:
            self.details[thread_id] = copy.deepcopy(value)

    def revoke_all(self) -> None:
        with self._lock:
            self.tokens.clear()

    def _handler(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_: object) -> None:
                return

            def _send(self, status: int, payload: object) -> None:
                body = json.dumps(payload).encode()
                gz = "gzip" in (self.headers.get("Accept-Encoding") or "")
                if gz:
                    body = gzip.compress(body)
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                if gz:
                    self.send_header("Content-Encoding", "gzip")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _authed(self) -> bool:
                header = self.headers.get("Authorization") or ""
                return header.startswith("Bearer ") and header[7:] in fake.tokens

            def _record(self) -> None:
                fake.requests.append({"method": self.command, "path": self.path, "headers": dict(self.headers)})

            def do_GET(self) -> None:
                self._record()
                parts = urlsplit(self.path)
                if fake.unavailable:
                    self.close_connection = True
                    self.connection.close()
                    return
                if parts.path == "/.well-known/t3/environment":
                    self._send(200, {"environmentId": "env-1", "label": "Test Mac", "serverVersion": "0.0.45"})
                    return
                if parts.path == "/api/auth/session":
                    if self._authed():
                        self._send(200, {"authenticated": True, "scopes": ["orchestration:read", "orchestration:operate"],
                                         "sessionMethod": "bearer-access-token", "expiresAt": "2026-11-06T17:25:15.183Z"})
                    else:
                        self._send(200, {"authenticated": False})
                    return
                if not self._authed():
                    self._send(401, {"_tag": "EnvironmentAuthInvalidError", "code": "auth_invalid", "reason": "invalid_credential"})
                    return
                if parts.path == "/api/orchestration/shell":
                    with fake._lock:
                        self._send(200, fake.shell)
                    return
                if parts.path.startswith("/api/orchestration/threads/"):
                    thread_id = unquote(parts.path.rsplit("/", 1)[-1])
                    with fake._lock:
                        value = fake.details.get(thread_id)
                        if value is None:
                            shell_thread = next((item for item in fake.shell["threads"] if item["id"] == thread_id), None)
                            value = detail(shell_thread) if shell_thread is not None else None
                    if value is None:
                        self._send(404, {"_tag": "EnvironmentResourceNotFoundError", "code": "not_found", "reason": "thread_not_found"})
                    else:
                        self._send(200, value)
                    return
                self._send(404, {"code": "not_found"})

            def do_POST(self) -> None:
                self._record()
                length = int(self.headers.get("Content-Length") or 0)
                raw = self.rfile.read(length)
                parts = urlsplit(self.path)
                if parts.path == "/oauth/token":
                    form = parse_qs(raw.decode())
                    fake.forms.append(form)
                    code = (form.get("subject_token") or [""])[0]
                    if code in fake.codes:
                        fake.codes.discard(code)
                        token = f"tok-{len(fake.tokens) + 1}-" + "x" * 40
                        fake.tokens.add(token)
                        self._send(200, {"access_token": token, "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
                                         "token_type": "Bearer", "expires_in": 2591999, "scope": (form.get("scope") or [""])[0]})
                    else:
                        self._send(401, {"code": "auth_invalid", "reason": "invalid_credential"})
                    return
                if not self._authed():
                    self._send(401, {"code": "auth_invalid", "reason": "invalid_credential"})
                    return
                if parts.path == "/api/orchestration/dispatch":
                    command = json.loads(raw)
                    if command.get("type") in fake.fail_dispatch_types:
                        self._send(500, {"code": "internal_error", "reason": "orchestration_dispatch_failed"})
                        return
                    with fake._lock:
                        fake.dispatched.append(command)
                        sequence = len(fake.dispatched) + 100
                    self._send(200, {"sequence": sequence})
                    return
                self._send(404, {"code": "not_found"})

        return Handler


class FixedIds:
    def __init__(self) -> None:
        self.count = 0

    def __call__(self) -> str:
        self.count += 1
        return f"00000000-0000-4000-8000-{self.count:012d}"


def make_service(fake: FakeT3Server | None = None, *, with_announcements: bool = True):
    """Return (service, announcements, repository, database, tempdir)."""
    directory = tempfile.TemporaryDirectory()
    database = RuntimeDatabase(Path(directory.name) / "runtime.sqlite3")
    database.migrate()
    announcements = AnnouncementRepository(database) if with_announcements else None
    repository = T3Repository(database)
    service = T3Service(
        repository,
        ConnectionCredentialEnvelopes(StubBridge()),
        announcements=announcements,
        commands=T3CommandBuilder(new_id=FixedIds(), now=lambda: "2026-10-07T12:00:00.000Z"),
    )
    return service, announcements, repository, database, directory
