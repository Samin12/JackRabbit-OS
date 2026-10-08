"""In-process stand-in for the sync half of the SamRabbit Mac bridge (``/v1/sync/*``), plus a harness.

``FakeSyncBridge`` dedupes events by id like the real bridge, verifies blob hashes, records the order
of requests, and can be scripted: ``failures`` (popped per request) holds ``"503"``, ``"401"``,
``"404"``, ``"drop"`` (stores the events, then closes the connection without answering: an
uncertain send) or ``"reset"`` (closes without storing); ``reject_marker`` answers 400 to any event
batch that contains it.
"""

from __future__ import annotations

from collections import deque
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import tempfile
import threading

from sam_runtime.domains.conversation_sync import ConversationSyncService
from sam_runtime.domains.heptabase_journal.bridge import BridgeStore, is_private_host
from sam_runtime.domains.heptabase_journal.client import HttpTransport
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository
from sam_runtime.storage.database import RuntimeDatabase

from heptabase_fakes import FakeClock
from t3_fixtures import StubBridge

TINY_JPEG = bytes.fromhex("ffd8ffe000104a46494600010100000100010000ffc0000b080002000201011100ffd9")


class FakeSyncBridge:
    def __init__(self, token: str | None = None) -> None:
        self.token = token or "bridge-" + secrets.token_urlsafe(24)
        self.events: dict[str, dict[str, object]] = {}
        self.blobs: dict[str, tuple[str, bytes]] = {}
        self.log: list[tuple[str, object]] = []
        self.failures: deque[str] = deque()
        self.reject_marker: str | None = None
        self.authorization: list[str] = []
        self.lock = threading.Lock()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _handler(self))
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self._thread.start()
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def event_ids(self) -> list[str]:
        with self.lock:
            return list(self.events)

    def requests(self) -> list[tuple[str, object]]:
        with self.lock:
            return list(self.log)


def _handler(fake: FakeSyncBridge) -> type[BaseHTTPRequestHandler]:
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

        def _error(self, status: int, code: str) -> None:
            self._json(status, {"error": {"code": code, "message": code, "retryable": status >= 500}})

        def _body(self) -> bytes:
            return self.rfile.read(int(self.headers.get("Content-Length") or 0))

        def _failure(self) -> str | None:
            with fake.lock:
                return fake.failures.popleft() if fake.failures else None

        def _scripted(self, failure: str | None) -> bool:
            if failure in ("503", "401", "404"):
                self._error(int(failure), {"503": "bridge_busy", "401": "unauthorized", "404": "not_found"}[failure])
                return True
            if failure == "reset":
                self.close_connection = True
                return True
            return False

        def do_POST(self) -> None:  # noqa: N802
            raw = self._body()
            fake.authorization.append(self.headers.get("Authorization", ""))
            if self.headers.get("Authorization") != f"Bearer {fake.token}":
                self._error(401, "unauthorized")
                return
            if self.path != "/v1/sync/events":
                self._error(404, "not_found")
                return
            failure = self._failure()
            if self._scripted(failure):
                return
            body = json.loads(raw)
            if fake.reject_marker and fake.reject_marker in raw.decode():
                self._error(400, "invalid_events")
                return
            accepted = duplicates = 0
            with fake.lock:
                fake.log.append(("events", [item["id"] for item in body["events"]]))
                for item in body["events"]:
                    if item["id"] in fake.events:
                        duplicates += 1
                    else:
                        fake.events[item["id"]] = item
                        accepted += 1
                cursor = len(fake.events)
            if failure == "drop":
                self.close_connection = True  # stored, but the runtime never hears back
                return
            self._json(200, {"accepted": accepted, "duplicates": duplicates, "cursor": cursor})

        def do_PUT(self) -> None:  # noqa: N802
            raw = self._body()
            if self.headers.get("Authorization") != f"Bearer {fake.token}":
                self._error(401, "unauthorized")
                return
            if not self.path.startswith("/v1/sync/blobs/"):
                self._error(404, "not_found")
                return
            failure = self._failure()
            if self._scripted(failure):
                return
            digest = self.path.rsplit("/", 1)[-1]
            if hashlib.sha256(raw).hexdigest() != digest:
                self._error(400, "hash_mismatch")
                return
            with fake.lock:
                created = "sha256:" + digest not in fake.blobs
                fake.blobs["sha256:" + digest] = (self.headers.get("Content-Type", ""), raw)
                fake.log.append(("blob", "sha256:" + digest))
            self._json(200, {"blobId": "sha256:" + digest, "bytes": len(raw), "created": created})

    return Handler


class SyncHarness:
    """A migrated database, the journal's BridgeStore (configured for the fake bridge) and the sync service."""

    def __init__(self, *, configure: bool = True, clock: FakeClock | None = None) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.clock = clock or FakeClock()
        self.store = BridgeStore(self.database, ConnectionCredentialRepository(self.database),
                                 ConnectionCredentialEnvelopes(StubBridge()))
        self.bridge = FakeSyncBridge()
        self.service = ConversationSyncService(
            self.database, self.store, transport=HttpTransport(timeout=3.0, allow_http=is_private_host),
            clock=self.clock,
        )
        if configure:
            self.store.save(self.bridge.url, self.bridge.token)

    def rows(self) -> list[dict[str, object]]:
        with self.database.connect() as connection:
            return [dict(row) for row in connection.execute(
                "SELECT * FROM conversation_sync_outbox ORDER BY event_at, created_at, rowid").fetchall()]

    def blob_rows(self) -> list[dict[str, object]]:
        with self.database.connect() as connection:
            return [dict(row) for row in connection.execute(
                "SELECT * FROM conversation_sync_blobs ORDER BY created_at, rowid").fetchall()]

    def payloads(self, state: str | None = None) -> list[dict[str, object]]:
        return [json.loads(str(row["payload_json"])) for row in self.rows() if state is None or row["state"] == state]

    def close(self) -> None:
        self.service.stop()
        self.bridge.close()
        self.directory.cleanup()
