"""Device routes (bearer, never proxied for browsers), the /v1/voice/calls link, the finalize hook and
the management routes, over the real runtime HTTP server."""

from __future__ import annotations

import json
from types import SimpleNamespace
import unittest
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from conversation_sync_fakes import TINY_JPEG, SyncHarness

from sam_runtime.api.conversation_sync_routes import ConversationSyncRoutes
from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.api.http_server import RuntimeHttpServer
from sam_runtime.domains.conversation_sync.events import blob_id_for
from sam_runtime.security.pairing import PairingAuthority
from sam_runtime.storage.lifecycle_repository import LifecycleRepository
from sam_runtime.storage.sessions import SessionTranscriptRepository

TOKEN = "r" * 43
ORIGIN = "https://192.168.1.186:8443"
CONV = "c_0123456789abcdef0123"
SESSION = "9f" * 12


class _Providers:
    def __init__(self) -> None:
        self.closed: list[str] = []

    def create_realtime_call(self, sdp: str):
        return SimpleNamespace(sdp="answer", connect_greeting_event=None, session_id=SESSION)

    def close_realtime_session(self, session_id: str) -> None:
        self.closed.append(session_id)


class _Memory:
    def __init__(self) -> None:
        self.fail = False

    def finalize(self, session_id: str):
        if self.fail:
            raise RuntimeError("reviewer down")
        return SimpleNamespace(session_id=session_id, summary="A short chat.", memory_count=2, embedded_count=0,
                               model="m", embeddings_available=False)


class ConversationSyncRoutesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = SyncHarness()
        self.addCleanup(self.h.close)
        self.pairing = PairingAuthority()
        self.memory = _Memory()
        self.server = RuntimeHttpServer(
            host="127.0.0.1", port=0, token=TOKEN, health=lambda: {"status": "ready"},
            lifecycle=LifecycleRepository(self.h.database), events=RuntimeEventStream(), pairing=self.pairing,
            providers=_Providers(), sessions=SessionTranscriptRepository(self.h.database), memory=self.memory,
            conversation_sync=ConversationSyncRoutes(self.h.service),
        )
        self.server.start()
        self.addCleanup(self.server.stop)
        self.base = f"http://127.0.0.1:{self.server.port}"
        self.cookie = self.csrf = ""

    def call(self, method: str, path: str, body: object = None, *, bearer: bool = True, forwarded: bool = False,
             browser: bool = False, raw: bytes | None = None, headers: dict[str, str] | None = None
             ) -> tuple[int, dict[str, object]]:
        sent = {"Content-Type": "application/json", **(headers or {})}
        if bearer:
            sent["Authorization"] = "Bearer " + TOKEN
        if forwarded or browser:
            sent["X-SAM-Forwarded-Origin"] = ORIGIN
        if browser:
            sent.update({"Origin": ORIGIN, "Cookie": self.cookie, "X-CSRF-Token": self.csrf})
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else
                                            (b"{}" if method == "POST" else None))
        request = Request(self.base + path, data=data, method=method, headers=sent)
        try:
            with urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read() or b"{}")
        except HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def refused(self, method: str, path: str, **kwargs: object) -> int:
        """An oversized body is answered 400 before it is read, so the client may see a reset instead."""
        try:
            return self.call(method, path, **kwargs)[0]
        except (URLError, ConnectionError):
            return 400

    def pair(self) -> None:
        code = self.pairing.current_code().value
        request = Request(self.base + "/v1/management/pair", data=json.dumps({"code": code}).encode(), method="POST",
                          headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json",
                                   "X-SAM-Forwarded-Origin": ORIGIN, "Origin": ORIGIN})
        with urlopen(request, timeout=10) as response:
            self.cookie = response.headers["Set-Cookie"].split(";", 1)[0]
            self.csrf = json.loads(response.read())["csrfToken"]

    def test_device_routes_need_the_bearer_and_refuse_browser_proxied_requests(self) -> None:
        batch = {"conversationId": CONV, "sessionId": SESSION,
                 "events": [{"id": f"{CONV}:1", "seq": 1, "type": "message.user", "at": 1, "text": "hi"}]}
        for path, kwargs in (("/v1/voice/conversation/events", {"body": batch}),
                             ("/v1/voice/conversation/blobs", {"raw": TINY_JPEG,
                                                               "headers": {"Content-Type": "image/jpeg"}})):
            with self.subTest(path=path):
                self.assertEqual(401, self.call("POST", path, bearer=False, **kwargs)[0])
                status, value = self.call("POST", path, forwarded=True, **kwargs)
                self.assertEqual((403, "device_only"), (status, value["error"]["code"]))
        self.assertEqual((403, "device_only"), (lambda r: (r[0], r[1]["error"]["code"]))(
            self.call("GET", "/v1/voice/conversation/status", forwarded=True)))
        self.assertEqual([], self.h.rows())
        self.assertEqual([], self.h.blob_rows())
        status, value = self.call("POST", "/v1/voice/conversation/events", batch)
        self.assertEqual((202, 1, 0), (status, value["accepted"], value["duplicates"]))
        status, value = self.call("POST", "/v1/voice/conversation/events", batch)
        self.assertEqual((202, 0, 1), (status, value["accepted"], value["duplicates"]))
        status, value = self.call("POST", "/v1/voice/conversation/blobs", raw=TINY_JPEG,
                                  headers={"Content-Type": "image/jpeg", "X-SAM-Conversation": CONV})
        self.assertEqual((200, blob_id_for(TINY_JPEG), True), (status, value["blobId"], value["stored"]))
        status, value = self.call("GET", "/v1/voice/conversation/status")
        self.assertEqual((200, 1, 1, True), (status, value["pending"], value["imagesPending"],
                                             value["bridgeConfigured"]))

    def test_bad_requests(self) -> None:
        status, value = self.call("POST", "/v1/voice/conversation/events", {"conversationId": "../x", "events": []})
        self.assertEqual((400, "invalid_request"), (status, value["error"]["code"]))
        self.assertEqual(400, self.refused("POST", "/v1/voice/conversation/events",
                                           raw=b'{"conversationId":"' + CONV.encode() + b'","events":[' +
                                           b" " * 262_200 + b"]}"), "over 256 KB")
        status, _ = self.call("POST", "/v1/voice/conversation/events", raw=b"not json")
        self.assertEqual(400, status)
        status, value = self.call("POST", "/v1/voice/conversation/blobs", raw=b"<html>",
                                  headers={"Content-Type": "text/html"})
        self.assertEqual((400, "invalid_request"), (status, value["error"]["code"]))
        self.assertEqual(400, self.refused("POST", "/v1/voice/conversation/blobs", raw=b"x" * (400 * 1024 + 1),
                                           headers={"Content-Type": "image/jpeg"}))
        self.assertEqual([], self.h.rows())
        self.assertEqual([], self.h.blob_rows())

    def test_voice_calls_link_the_session_and_finalize_mirrors_the_session(self) -> None:
        status, value = self.call("POST", "/v1/voice/calls", {"sdp": "offer", "conversationId": CONV})
        self.assertEqual((200, SESSION), (status, value["sessionId"]))
        self.assertEqual(CONV, self.h.service.conversation_for(SESSION))
        entries = [{"role": "user", "eventType": "conversation.item.input_audio_transcription.completed",
                    "text": "Plan my Friday", "at": 1_760_000_000_000},
                   {"role": "assistant", "eventType": "response.audio_transcript.done", "text": "Sure."}]
        status, value = self.call("POST", "/v1/voice/sessions/finalize", {"sessionId": SESSION, "entries": entries})
        self.assertEqual((200, "A short chat."), (status, value["summary"]))
        (event,) = self.h.payloads()
        self.assertEqual(("session.finalized", CONV, True, 2, 2),
                         (event["type"], event["conversationId"], event["reviewed"], event["entryCount"],
                          event["memoryCount"]))
        self.memory.fail = True
        other = "77" * 12
        status, _ = self.call("POST", "/v1/voice/sessions/finalize", {"sessionId": other, "entries": entries})
        self.assertEqual(500, status)
        last = self.h.payloads()[-1]
        self.assertEqual((f"rt:{other}:finalized", False, "s_" + other),
                         (last["id"], last["reviewed"], last["conversationId"]),
                         "the session is mirrored even when memory review fails")

    def test_calls_without_or_with_a_bad_conversation_id_still_work(self) -> None:
        for body in ({"sdp": "offer"}, {"sdp": "offer", "conversationId": "../../x"}, {"sdp": "offer", "conversationId": 5}):
            with self.subTest(body=body):
                status, value = self.call("POST", "/v1/voice/calls", body)
                self.assertEqual((200, SESSION), (status, value["sessionId"]))
        self.assertEqual("s_" + SESSION, self.h.service.conversation_for(SESSION))

    def test_management_routes_need_a_paired_session(self) -> None:
        status, value = self.call("GET", "/v1/management/conversation-sync", forwarded=True)
        self.assertEqual(403, status)
        status, _ = self.call("POST", "/v1/management/conversation-sync/settings", {"enabled": False}, forwarded=True)
        self.assertEqual(403, status)
        self.pair()
        status, value = self.call("GET", "/v1/management/conversation-sync", browser=True)
        self.assertEqual(200, status)
        self.assertEqual({"enabled": True, "includeAssistant": True, "includeTools": True, "includeImages": True,
                          "redactSecrets": True}, value["settings"])
        self.assertNotIn(self.h.bridge.token, json.dumps(value))
        status, value = self.call("POST", "/v1/management/conversation-sync/settings", {"includeImages": False},
                                  browser=True)
        self.assertEqual((200, False), (status, value["settings"]["includeImages"]))
        status, value = self.call("POST", "/v1/management/conversation-sync/settings", {"nope": True}, browser=True)
        self.assertEqual((400, "invalid_request"), (status, value["error"]["code"]))


if __name__ == "__main__":
    unittest.main()
