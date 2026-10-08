"""In-process stand-in for the Google Calendar half of the SamRabbit Mac bridge (``/v1/calendar/*``).

``FakeCalendarBridge`` answers behind a bearer token, records every request, keeps a tiny fake Google
calendar (``events`` by Google event id), and can be scripted per route (``responses``). Nothing here
reaches a real calendar.
"""

from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import secrets
import threading
from urllib.parse import urlsplit

from sam_runtime.domains.calendar.google_bridge import GoogleCalendarBridge
from sam_runtime.domains.heptabase_journal.bridge import BridgeStore
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository

from t3_fixtures import StubBridge

GOOGLE_FEED = "https://calendar.google.com/calendar/ical/samin%40aianswer.us/private-0123456789abcdef/basic.ics"


class FakeCalendarBridge:
    def __init__(self, token: str | None = None) -> None:
        self.token = token or "bridge-" + secrets.token_urlsafe(24)
        self.requests: list[dict[str, object]] = []
        self.responses: dict[str, tuple[int, dict[str, object]]] = {}
        self.available = True
        self.events: dict[str, dict[str, object]] = {}
        self.guests: dict[str, int] = {}  # iCalUID -> the guest count update answers report (as Google has it)
        self.counter = 0
        self.lock = threading.Lock()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _handler(self))
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self._thread.start()
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def bodies(self, path: str) -> list[dict[str, object]]:
        with self.lock:
            return [dict(item["body"] or {}) for item in self.requests if item["path"] == path]

    def paths(self) -> list[str]:
        with self.lock:
            return [str(item["path"]) for item in self.requests]


def _handler(fake: FakeCalendarBridge) -> type[BaseHTTPRequestHandler]:
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
            path = urlsplit(self.path).path
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            body = json.loads(raw) if raw else None
            with fake.lock:
                fake.requests.append({"method": method, "path": path, "body": body})
            if self.headers.get("Authorization", "") != f"Bearer {fake.token}":
                self._json(401, {"error": {"code": "unauthorized", "message": "A valid bridge token is required."}})
                return
            scripted = fake.responses.get(path)
            if scripted is not None:
                self._json(*scripted)
                return
            if path == "/v1/calendar/status":
                self._json(200, {"available": fake.available, "composio": fake.available, "calendarId": "primary",
                                 "account": "samin@aianswer.us"})
            elif path == "/v1/calendar/events":
                with fake.lock:
                    fake.counter += 1
                    event_id = f"fakegoogle{fake.counter:04d}"
                    event = {"eventId": event_id, "iCalUID": event_id + "@google.com",
                             "calendarId": body.get("calendarId", "primary"), "title": body["title"],
                             "startsAt": body["startsAt"], "endsAt": body["endsAt"], "timezone": body.get("timezone"),
                             "allDay": False, "status": "confirmed"}
                    fake.events[event_id] = event
                self._json(200, {"ok": True, "calendarId": event["calendarId"], "account": "samin@aianswer.us",
                                 "event": event})
            elif path == "/v1/calendar/events/update":
                uid = str(body.get("iCalUID", ""))
                event_id = uid.removesuffix("@google.com") + (f"_{body['recurrenceId']}" if body.get("recurrenceId") else "")
                event = {"eventId": event_id, "iCalUID": uid, "title": body.get("title"),
                         "startsAt": body.get("startsAt"), "endsAt": body.get("endsAt")}
                if uid in fake.guests:
                    event["guests"] = fake.guests[uid]
                self._json(200, {"ok": True, "calendarId": "primary", "account": "samin@aianswer.us", "event": event})
            elif path == "/v1/calendar/events/delete":
                uid = str(body.get("iCalUID", ""))
                self._json(200, {"ok": True, "deleted": True, "calendarId": "primary",
                                 "eventId": uid.removesuffix("@google.com")})
            else:
                self._json(404, {"error": {"code": "not_found", "message": "Not found."}})

        def do_GET(self) -> None:  # noqa: N802
            self._serve("GET")

        def do_POST(self) -> None:  # noqa: N802
            self._serve("POST")

    return Handler


def make_google_bridge(database, fake: FakeCalendarBridge | None = None) -> tuple[BridgeStore, GoogleCalendarBridge]:
    """A BridgeStore and GoogleCalendarBridge over ``database``; configured for ``fake`` when given."""
    store = BridgeStore(database, ConnectionCredentialRepository(database), ConnectionCredentialEnvelopes(StubBridge()))
    if fake is not None:
        store.save(fake.url, fake.token)
    return store, GoogleCalendarBridge(store)
