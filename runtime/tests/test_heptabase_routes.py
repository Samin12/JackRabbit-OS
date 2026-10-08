from __future__ import annotations

import json
from types import SimpleNamespace
import unittest
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from heptabase_fakes import JournalHarness

from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.api.heptabase_routes import HeptabaseRoutes
from sam_runtime.api.http_server import RuntimeHttpServer
from sam_runtime.security.pairing import PairingAuthority
from sam_runtime.storage.lifecycle_repository import LifecycleRepository

TOKEN = "t" * 43
ORIGIN = "https://192.168.1.186:8443"
USER = "conversation.item.input_audio_transcription.completed"


class _Memory:
    def __init__(self, fail: bool) -> None:
        self.fail = fail
        self.calls = 0

    def finalize(self, session_id: str):
        self.calls += 1
        if self.fail:
            raise RuntimeError("reviewer unavailable")
        return SimpleNamespace(session_id=session_id, summary="s", memory_count=0, embedded_count=0,
                               model="m", embeddings_available=False)


class HeptabaseRoutesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = JournalHarness()
        self.addCleanup(self.h.close)
        self.pairing = PairingAuthority()
        self.memory = _Memory(fail=True)
        self.server = RuntimeHttpServer(
            host="127.0.0.1", port=0, token=TOKEN, health=lambda: {"status": "ready"},
            lifecycle=LifecycleRepository(self.h.database), events=RuntimeEventStream(), pairing=self.pairing,
            sessions=self.h.sessions, memory=self.memory, heptabase=HeptabaseRoutes(self.h.service),
        )
        self.server.start()
        self.addCleanup(self.server.stop)
        self.base = f"http://127.0.0.1:{self.server.port}"
        self.cookie = ""
        self.csrf = ""

    def call(self, method: str, path: str, body: dict[str, object] | None = None, *, bearer: bool = True,
             browser: bool = False, csrf: bool = True) -> tuple[int, dict[str, str], bytes]:
        headers = {"Content-Type": "application/json"}
        if bearer:
            headers["Authorization"] = "Bearer " + TOKEN
        if browser:
            headers.update({"X-SAM-Forwarded-Origin": ORIGIN, "Origin": ORIGIN, "Cookie": self.cookie})
            if csrf and self.csrf:
                headers["X-CSRF-Token"] = self.csrf
        data = json.dumps(body).encode() if body is not None else (b"{}" if method == "POST" else None)
        request = Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, dict(response.headers.items()), response.read()
        except HTTPError as error:
            return error.code, dict(error.headers.items()), error.read()

    def json_call(self, *args, **kwargs) -> tuple[int, dict[str, object]]:
        status, _, raw = self.call(*args, **kwargs)
        return status, json.loads(raw or b"{}")

    def pair(self) -> None:
        code = self.pairing.current_code().value
        status, headers, raw = self.call("POST", "/v1/management/pair", {"code": code}, browser=True)
        self.assertEqual(200, status)
        self.cookie = headers["Set-Cookie"].split(";", 1)[0]
        self.csrf = json.loads(raw)["csrfToken"]

    def test_device_routes_follow_the_contract(self) -> None:
        status, value = self.json_call("GET", "/v1/journal/status")
        self.assertEqual(200, status)
        self.assertEqual({"connected", "mode", "autoSessions", "pending", "failed", "lastSentAt", "needsReconnect", "today"},
                         set(value))
        self.assertFalse(value["connected"])
        status, value = self.json_call("POST", "/v1/journal/notes", {"text": "hello"})
        self.assertEqual((409, "heptabase_not_connected"), (status, value["error"]["code"]))
        self.h.connect()
        status, value = self.json_call("POST", "/v1/journal/notes", {"text": "typed on the R1"})
        self.assertEqual(200, status)
        self.assertEqual({"recorded", "state", "date", "entryId"}, set(value))
        self.assertEqual((True, "sent"), (value["recorded"], value["state"]))
        status, value = self.json_call("POST", "/v1/journal/notes", {"text": "  "})
        self.assertEqual(400, status)
        self.assertEqual(401, self.call("GET", "/v1/journal/status", bearer=False)[0])

    def test_management_routes_require_a_paired_session_and_csrf(self) -> None:
        for method, path in (("GET", "/v1/management/heptabase"),
                             ("POST", "/v1/management/heptabase/connect/start"),
                             ("POST", "/v1/management/heptabase/settings"),
                             ("POST", "/v1/management/heptabase/disconnect"),
                             ("POST", "/v1/management/heptabase/oauth/import")):
            with self.subTest(path=path):
                self.assertEqual(403, self.call(method, path, browser=True)[0])
        self.pair()
        status, view = self.json_call("GET", "/v1/management/heptabase", browser=True)
        self.assertEqual(200, status)
        self.assertEqual("disconnected", view["state"])
        self.assertEqual(403, self.call("POST", "/v1/management/heptabase/settings", {"autoSessions": False},
                                        browser=True, csrf=False)[0])
        status, view = self.json_call("POST", "/v1/management/heptabase/settings",
                                      {"autoSessions": False, "timezone": "America/Los_Angeles"}, browser=True)
        self.assertEqual(200, status)
        self.assertEqual((False, "America/Los_Angeles"),
                         (view["settings"]["autoSessions"], view["settings"]["timezone"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/settings", {"bogus": 1}, browser=True)
        self.assertEqual((400, "invalid_request"), (status, value["error"]["code"]))
        self.assertNotIn("access_token", json.dumps(view))

    def test_routes_without_pairing_authority_are_unavailable(self) -> None:
        routes = HeptabaseRoutes(self.h.service)

        class _Request:
            headers: dict[str, str] = {}
            status = 0

            def __init__(self, path: str) -> None:
                self.path = path

            def browser_session(self, *_args, **_kwargs):
                raise AssertionError("must not look up a session without an authority")

            def respond_json(self, status: int, payload, *, headers=None) -> None:
                self.status = status

        for path in ("/v1/management/heptabase", "/v1/management/heptabase/settings"):
            request = _Request(path)
            handled = routes.handle_get(request, None) if path.endswith("heptabase") else routes.handle_post(request, None)
            self.assertTrue(handled)
            self.assertEqual(503, request.status)

    def test_device_redirect_callback_via_get_is_state_authenticated_and_single_use(self) -> None:
        self.pair()
        status, started = self.json_call("POST", "/v1/management/heptabase/connect/start", {"redirect": "device"},
                                         browser=True)
        self.assertEqual(200, status)
        self.assertEqual(ORIGIN + "/v1/heptabase/oauth/callback", started["redirectUri"])
        self.assertEqual({"authSessionId", "authorizationUrl", "redirectUri", "expiresAt"}, set(started))
        params = self.h.fake.authorize(started["authorizationUrl"])
        path = "/v1/heptabase/oauth/callback?" + urlencode(params)
        status, headers, raw = self.call("GET", path)  # no cookie, no CSRF: state alone
        self.assertEqual(200, status)
        self.assertTrue(headers["Content-Type"].startswith("text/html"))
        self.assertIn(b"Heptabase is connected to your R1", raw)
        self.assertNotIn(params["code"].encode(), raw)
        status, _, raw = self.call("GET", path)
        self.assertEqual(400, status)
        self.assertIn(b"already used", raw)
        self.assertTrue(self.h.service.connected())

    def test_loopback_callback_via_post_json(self) -> None:
        self.pair()
        _, started = self.json_call("POST", "/v1/management/heptabase/connect/start", {"redirect": "loopback"},
                                    browser=True)
        self.assertEqual("http://127.0.0.1:53682/callback", started["redirectUri"])
        params = self.h.fake.authorize(started["authorizationUrl"])
        status, value = self.json_call("POST", "/v1/heptabase/oauth/callback", {"state": "nope", "code": "x",
                                                                                "iss": params["iss"]})
        self.assertEqual((400, "invalid_state"), (status, value["error"]["code"]))
        status, value = self.json_call("POST", "/v1/heptabase/oauth/callback",
                                       {"code": params["code"], "state": params["state"], "iss": params["iss"]})
        self.assertEqual(200, status)
        self.assertEqual({"connected": True, "writeVerified": True, "refreshAvailable": True}, value)
        status, view = self.json_call("POST", "/v1/management/heptabase/disconnect", browser=True)
        self.assertEqual((200, False), (status, view["connected"]))

    def test_finalize_journals_before_memory_and_dedupes_by_session(self) -> None:
        self.h.connect()
        at = int(self.h.clock() * 1000)
        payload = {"sessionId": "voice-123", "entries": [
            {"role": "user", "eventType": USER, "text": "Remember the orb should breathe slower", "at": at},
            {"role": "assistant", "eventType": "response.output_audio_transcript.done", "text": "Got it", "at": at + 900},
        ]}
        status, value = self.json_call("POST", "/v1/voice/sessions/finalize", payload)
        self.assertEqual((500, "finalize_failed"), (status, value["error"]["code"]), "memory review failed")
        sessions = [row for row in self.h.rows() if row["kind"] == "session"]
        self.assertEqual(1, len(sessions), "the user's words were queued anyway")
        self.memory.fail = False
        self.assertEqual(200, self.json_call("POST", "/v1/voice/sessions/finalize", payload)[0])
        self.assertEqual(1, len([row for row in self.h.rows() if row["kind"] == "session"]), "deduped by session id")
        self.h.service.drain()
        (date, content), = self.h.fake.appends()
        self.assertIn("Remember the orb should breathe slower", content)
        self.assertNotIn("Got it", content)


if __name__ == "__main__":
    unittest.main()
