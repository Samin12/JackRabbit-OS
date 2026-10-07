"""Dictation (speech-to-text for typed fields): session shape, variant fallback and the route."""

from __future__ import annotations

from email import message_from_bytes
from email.policy import HTTP
from io import BytesIO
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.api.http_server import RuntimeHttpServer
from sam_runtime.providers.controller import ProviderController
from sam_runtime.providers.openai import OpenAIProviderError, ProviderModels
from sam_runtime.providers.openai.platform import (
    DICTATION_VARIANTS,
    OpenAIPlatform,
    _dictation_session,
)
from sam_runtime.security.credentials import ProviderCredentials
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.storage.lifecycle_repository import LifecycleRepository
from sam_runtime.storage.provider_settings import ProviderSettingsRepository


LOCAL_TOKEN = "d" * 43
OFFER = "v=0\r\noffer"


class _Bridge:
    def __init__(self) -> None:
        self.value: str | None = None

    def hasOpenAiPlatformKey(self) -> bool: return self.value is not None
    def getOpenAiPlatformKey(self) -> str | None: return self.value
    def putOpenAiPlatformKey(self, value: str) -> None: self.value = value
    def deleteOpenAiPlatformKey(self) -> None: self.value = None
    def hasOpenAiSubscriptionTokens(self) -> bool: return False
    def getOpenAiSubscriptionTokens(self) -> str | None: return None
    def putOpenAiSubscriptionTokens(self, value: str) -> None: pass
    def deleteOpenAiSubscriptionTokens(self) -> None: pass


class _ListingPlatform:
    """Only used while connecting: reports the models the controller may select."""

    def __init__(self, key: str, *, safety_source: str) -> None:
        self.key = key

    def list_models(self) -> ProviderModels:
        return ProviderModels(("gpt-5.4",), ("gpt-realtime-2.1",))


class _RejectingTranscriptionPlatform(_ListingPlatform):
    calls: list[tuple[str, str]] = []

    def create_dictation_call(self, *, offer_sdp: str, variant: str, model: str, transcription_model: str) -> str:
        self.__class__.calls.append((variant, transcription_model))
        if variant == "transcription":
            raise OpenAIProviderError("provider_rejected", "OpenAI could not start this session.", status=400)
        return "v=0\r\nanswer"

    def create_realtime_call(self, **_: object) -> str:
        raise AssertionError("dictation must not open a voice call")


class _UnauthorizedPlatform(_ListingPlatform):
    calls = 0

    def create_dictation_call(self, **_: object) -> str:
        self.__class__.calls += 1
        raise OpenAIProviderError("credential_rejected", "OpenAI rejected this credential.", status=401)


class _Response:
    def __init__(self, body: bytes) -> None:
        self._body = body
        self.status = 200

    def __enter__(self) -> "_Response": return self
    def __exit__(self, *_: object) -> None: pass
    def read(self, *_: object) -> bytes: return self._body


def _session_from(request: Request) -> dict[str, object]:
    content_type = request.get_header("Content-type")
    raw = b"Content-Type: " + content_type.encode() + b"\r\n\r\n" + request.data
    message = message_from_bytes(raw, policy=HTTP)
    fields = {part.get_param("name", header="content-disposition"): part.get_payload(decode=True)
              for part in message.iter_parts()}
    assert fields["sdp"].decode().startswith("v=0")
    return json.loads(fields["session"].decode())


class DictationSessionShapeTest(unittest.TestCase):
    def test_transcription_session_cannot_answer(self) -> None:
        session = _dictation_session("transcription", model="gpt-realtime-2.1", transcription_model="gpt-4o-transcribe")

        self.assertEqual("transcription", session["type"])
        self.assertNotIn("tools", session)
        self.assertNotIn("instructions", session)
        self.assertNotIn("output_modalities", session)
        audio = session["audio"]
        self.assertNotIn("output", audio)
        self.assertEqual({"model": "gpt-4o-transcribe"}, audio["input"]["transcription"])
        self.assertEqual("server_vad", audio["input"]["turn_detection"]["type"])
        self.assertNotIn("create_response", audio["input"]["turn_detection"])

    def test_realtime_fallback_never_creates_a_response_and_has_no_tools(self) -> None:
        session = _dictation_session("realtime", model="gpt-realtime-2.1", transcription_model="gpt-4o-mini-transcribe")

        self.assertEqual("realtime", session["type"])
        self.assertEqual([], session["tools"])
        self.assertEqual("none", session["tool_choice"])
        self.assertEqual(["text"], session["output_modalities"])
        self.assertEqual(1, session["max_output_tokens"])
        turn = session["audio"]["input"]["turn_detection"]
        self.assertIs(False, turn["create_response"])
        self.assertIs(False, turn["interrupt_response"])
        self.assertNotIn("output", session["audio"])
        self.assertEqual({"type": "audio/pcm", "rate": 24_000}, session["audio"]["input"]["format"])

    def test_every_variant_is_speech_to_text_only(self) -> None:
        for variant, transcription_model in DICTATION_VARIANTS:
            session = _dictation_session(variant, model="gpt-realtime-2.1", transcription_model=transcription_model)
            self.assertFalse(session.get("tools"), variant)
            self.assertNotEqual(True, session["audio"]["input"]["turn_detection"].get("create_response"), variant)
            self.assertNotIn("output", session["audio"], variant)

    def test_unknown_variant_is_refused(self) -> None:
        with self.assertRaises(ValueError):
            _dictation_session("conversation", model="gpt-realtime-2.1", transcription_model="gpt-4o-transcribe")

    def test_platform_posts_the_dictation_session_to_realtime_calls(self) -> None:
        captured: list[Request] = []

        def fake_urlopen(request: Request, timeout: float) -> _Response:
            captured.append(request)
            return _Response(b"v=0\r\nanswer")

        with patch("sam_runtime.providers.openai.platform.urlopen", fake_urlopen):
            answer = OpenAIPlatform("sk-test", safety_source="local").create_dictation_call(
                offer_sdp=OFFER, variant="realtime", model="gpt-realtime-2.1", transcription_model="gpt-4o-transcribe")

        self.assertEqual("v=0\r\nanswer", answer)
        self.assertTrue(captured[0].full_url.endswith("/v1/realtime/calls"))
        session = _session_from(captured[0])
        self.assertIs(False, session["audio"]["input"]["turn_detection"]["create_response"])
        self.assertEqual([], session["tools"])

    def test_platform_rejects_a_bad_offer_before_calling_openai(self) -> None:
        with patch("sam_runtime.providers.openai.platform.urlopen") as request:
            with self.assertRaises(OpenAIProviderError) as raised:
                OpenAIPlatform("sk-test", safety_source="local").create_dictation_call(
                    offer_sdp="nope", variant="transcription", model="", transcription_model="gpt-4o-transcribe")
        self.assertEqual("invalid_sdp", raised.exception.code)
        request.assert_not_called()


class _ControllerCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.temporary.name) / "runtime.sqlite3")
        self.database.migrate()
        self.bridge = _Bridge()
        self.controller = ProviderController(
            credentials=ProviderCredentials(self.bridge),
            settings=ProviderSettingsRepository(self.database),
            events=RuntimeEventStream(),
            safety_source="local-install",
        )
        with patch("sam_runtime.providers.controller.OpenAIPlatform", _ListingPlatform):
            self.controller.connect_platform("sk-test-value-long-enough")

    def tearDown(self) -> None:
        self.temporary.cleanup()


class DictationControllerTest(_ControllerCase):
    def test_falls_back_when_openai_rejects_a_transcription_session_and_remembers(self) -> None:
        _RejectingTranscriptionPlatform.calls = []
        with patch("sam_runtime.providers.controller.OpenAIPlatform", _RejectingTranscriptionPlatform):
            first = self.controller.create_dictation_call(OFFER)
            second = self.controller.create_dictation_call(OFFER)

        self.assertEqual("realtime", first.variant)
        self.assertEqual("v=0\r\nanswer", first.sdp)
        self.assertTrue(first.session_id.startswith("dict_"))
        self.assertNotEqual(first.session_id, second.session_id)
        self.assertEqual(
            [("transcription", "gpt-4o-transcribe"), ("realtime", "gpt-4o-transcribe"), ("realtime", "gpt-4o-transcribe")],
            _RejectingTranscriptionPlatform.calls,
        )

    def test_dictation_is_never_an_active_voice_session(self) -> None:
        with patch("sam_runtime.providers.controller.OpenAIPlatform", _RejectingTranscriptionPlatform):
            call = self.controller.create_dictation_call(OFFER)
        self.assertFalse(self.controller.is_active_realtime_session(call.session_id))

    def test_credential_errors_are_not_retried_with_other_shapes(self) -> None:
        _UnauthorizedPlatform.calls = 0
        with patch("sam_runtime.providers.controller.OpenAIPlatform", _UnauthorizedPlatform):
            with self.assertRaises(OpenAIProviderError) as raised:
                self.controller.create_dictation_call(OFFER)
        self.assertEqual(401, raised.exception.status)
        self.assertEqual(1, _UnauthorizedPlatform.calls)

    def test_needs_a_connected_credential(self) -> None:
        self.bridge.value = None
        with self.assertRaises(OpenAIProviderError) as raised:
            self.controller.create_dictation_call(OFFER)
        self.assertEqual(409, raised.exception.status)


class DictationRouteTest(_ControllerCase):
    def setUp(self) -> None:
        super().setUp()
        lifecycle = LifecycleRepository(self.database)
        lifecycle.record_start()
        self.server = RuntimeHttpServer(
            host="127.0.0.1", port=0, token=LOCAL_TOKEN,
            health=lambda: {"status": "ready"}, lifecycle=lifecycle, events=RuntimeEventStream(),
            providers=self.controller,
        )
        self.server.start()
        self.base = f"http://127.0.0.1:{self.server.port}"
        self.openai: list[Request] = []

    def tearDown(self) -> None:
        self.server.stop()
        super().tearDown()

    def _fake_openai(self, request: Request, timeout: float) -> _Response:
        self.openai.append(request)
        session = _session_from(request)
        if session["type"] == "transcription":
            raise HTTPError(request.full_url, 400, "Bad Request", {}, BytesIO(
                b'{"error":{"type":"invalid_request_error","message":"Transcription sessions are not allowed."}}'))
        return _Response(b"v=0\r\nanswer-sdp")

    def _post(self, body: object, headers: dict[str, str] | None = None, *, bearer: bool = True) -> tuple[int, dict[str, object]]:
        merged = {"Content-Type": "application/json", **(headers or {})}
        if bearer:
            merged["Authorization"] = f"Bearer {LOCAL_TOKEN}"
        request = Request(self.base + "/v1/voice/dictation/calls", data=json.dumps(body).encode(),
                          headers=merged, method="POST")
        try:
            with patch("sam_runtime.providers.openai.platform.urlopen", self._fake_openai):
                with urlopen(request, timeout=10) as response:
                    return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def test_requires_the_local_bearer(self) -> None:
        status, body = self._post({"sdp": OFFER}, bearer=False)
        self.assertEqual(401, status)
        self.assertEqual("unauthorized", body["error"]["code"])
        self.assertEqual([], self.openai)

    def test_refuses_requests_forwarded_for_a_browser(self) -> None:
        status, body = self._post({"sdp": OFFER}, {"X-SAM-Forwarded-Origin": "https://r1.local:8443"})
        self.assertEqual(403, status)
        self.assertEqual("device_only", body["error"]["code"])
        self.assertEqual([], self.openai)

    def test_returns_the_answer_and_posts_a_session_that_never_answers(self) -> None:
        status, body = self._post({"sdp": OFFER})

        self.assertEqual(200, status)
        self.assertEqual("v=0\r\nanswer-sdp", body["sdp"])
        self.assertEqual("realtime", body["mode"])
        self.assertEqual("gpt-4o-transcribe", body["transcriptionModel"])
        self.assertTrue(body["sessionId"].startswith("dict_"))
        self.assertNotIn("sk-test", json.dumps(body))
        sessions = [_session_from(request) for request in self.openai]
        self.assertEqual(["transcription", "realtime"], [session["type"] for session in sessions])
        for session in sessions:
            self.assertFalse(session.get("tools"))
            self.assertNotEqual(True, session["audio"]["input"]["turn_detection"].get("create_response"))
            self.assertNotIn("output", session["audio"])
        self.assertEqual("gpt-realtime-2.1", sessions[1]["model"])

    def test_invalid_offer_is_a_client_error(self) -> None:
        status, body = self._post({"sdp": "nope"})
        self.assertEqual(400, status)
        self.assertEqual("invalid_sdp", body["error"]["code"])


if __name__ == "__main__":
    unittest.main()
