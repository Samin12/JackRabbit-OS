from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
import secrets
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from sam_runtime.core.logging import runtime_logger


_LOG = runtime_logger()

@dataclass(frozen=True, slots=True)
class ProviderModels:
    text: tuple[str, ...]
    realtime: tuple[str, ...]


class OpenAIProviderError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status: int = 502,
        details: dict[str, object] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.status = status
        self.details = details


class OpenAIPlatform:
    API_ROOT = "https://api.openai.com/v1"

    def __init__(self, api_key: str, *, safety_source: str) -> None:
        self._api_key = api_key
        self._safety_id = hashlib.sha256(
            f"sam-r1:{safety_source}".encode()
        ).hexdigest()

    def list_models(self) -> ProviderModels:
        _LOG.info("openai.models.request_begin", extra={"path": "/models"})
        payload = json.loads(self._request("GET", "/models").decode())
        _LOG.info("openai.models.request_success")
        identifiers = sorted(
            str(item.get("id", ""))
            for item in payload.get("data", [])
            if isinstance(item, dict) and item.get("id")
        )
        realtime = tuple(
            item
            for item in identifiers
            if item.startswith("gpt-realtime") or item == "gpt-live-1"
        )
        text = tuple(item for item in identifiers if _is_text_model(item))
        return ProviderModels(text=text, realtime=realtime)

    def create_realtime_call(
        self,
        *,
        offer_sdp: str,
        model: str,
        instructions_extra: str = "",
        extra_tools: tuple[dict[str, object], ...] = (),
        tool_definitions: tuple[dict[str, object], ...] | None = None,
    ) -> str:
        _LOG.info(
            "openai.realtime.create.begin",
            extra={"model": model, "sdpLen": len(offer_sdp)},
        )
        if not offer_sdp.startswith("v=0") or len(offer_sdp) > 262_144:
            raise OpenAIProviderError("invalid_sdp", "The WebRTC offer is invalid.", status=400)
        if not (model.startswith("gpt-realtime") or model == "gpt-live-1") or len(model) > 128:
            raise OpenAIProviderError("unsupported_model", "Select an available Realtime model.", status=400)
        boundary = f"sam-{secrets.token_hex(16)}"
        session = json.dumps(
            _realtime_session(
                model,
                instructions_extra=instructions_extra,
                extra_tools=extra_tools,
                tool_definitions=tool_definitions,
            ),
            separators=(",", ":"),
        )
        body = _multipart(boundary, (("sdp", offer_sdp), ("session", session)))
        answer = self._request(
            "POST",
            "/realtime/calls",
            body=body,
            content_type=f"multipart/form-data; boundary={boundary}",
        ).decode()
        _LOG.info("openai.realtime.create.success", extra={"model": model, "sdpLen": len(offer_sdp)})
        if not answer.startswith("v=0"):
            raise OpenAIProviderError("invalid_answer", "OpenAI returned an invalid WebRTC answer.")
        return answer

    def create_dictation_call(
        self,
        *,
        offer_sdp: str,
        variant: str,
        model: str,
        transcription_model: str,
    ) -> str:
        """WebRTC call that only transcribes the microphone (no model response, no audio out).

        ``variant`` is ``"transcription"`` (a Realtime transcription session) or
        ``"realtime"`` (a Realtime session whose server VAD never creates a response).
        """
        if not offer_sdp.startswith("v=0") or len(offer_sdp) > 262_144:
            raise OpenAIProviderError("invalid_sdp", "The WebRTC offer is invalid.", status=400)
        if variant == "realtime" and (
            not (model.startswith("gpt-realtime") or model == "gpt-live-1") or len(model) > 128
        ):
            raise OpenAIProviderError("unsupported_model", "Select an available Realtime model.", status=400)
        _LOG.info(
            "openai.dictation.create.begin",
            extra={"variant": variant, "transcriptionModel": transcription_model, "sdpLen": len(offer_sdp)},
        )
        boundary = f"sam-{secrets.token_hex(16)}"
        session = json.dumps(
            _dictation_session(variant, model=model, transcription_model=transcription_model),
            separators=(",", ":"),
        )
        body = _multipart(boundary, (("sdp", offer_sdp), ("session", session)))
        answer = self._request(
            "POST",
            "/realtime/calls",
            body=body,
            content_type=f"multipart/form-data; boundary={boundary}",
        ).decode()
        if not answer.startswith("v=0"):
            raise OpenAIProviderError("invalid_answer", "OpenAI returned an invalid WebRTC answer.")
        _LOG.info("openai.dictation.create.success", extra={"variant": variant})
        return answer

    def _request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        content_type: str | None = None,
    ) -> bytes:
        _LOG.debug(
            "openai.request.start",
            extra={"method": method, "path": path, "hasBody": body is not None, "hasContentType": bool(content_type)},
        )
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Accept": "application/json, application/sdp, text/plain",
            "OpenAI-Safety-Identifier": self._safety_id,
        }
        if content_type:
            headers["Content-Type"] = content_type
        request = Request(self.API_ROOT + path, data=body, headers=headers, method=method)
        try:
            with urlopen(request, timeout=25) as response:
                _LOG.info("openai.request.success", extra={"method": method, "path": path, "status": response.status})
                return response.read(524_288)
        except HTTPError as error:
            status = int(error.code)
            details = _read_http_error_details(error)
            _LOG.warning(
                "openai.request.error method=%s path=%s status=%s details=%s",
                method,
                path,
                status,
                details,
            )
            code = "credential_rejected" if status in (401, 403) else "provider_rejected"
            message = (
                "OpenAI rejected this credential."
                if code == "credential_rejected"
                else "OpenAI could not start this session."
            )
            raise OpenAIProviderError(code, message, status=status, details=details) from error
        except (URLError, TimeoutError, OSError) as error:
            _LOG.error(
                "openai.request.error_unreachable",
                extra={"method": method, "path": path},
            )
            raise OpenAIProviderError(
                "provider_unavailable", "OpenAI is currently unreachable.", status=503
            ) from error


def _read_http_error_details(error: HTTPError) -> dict[str, object]:
    body = error.read()
    if not body:
        return {}
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {"response": body.decode("utf-8", errors="replace")[:300]}
    if not isinstance(payload, dict):
        return {}
    openai_error = payload.get("error")
    if isinstance(openai_error, dict):
        parsed: dict[str, object] = {}
        for key in ("type", "code", "message", "param"):
            value = openai_error.get(key)
            if isinstance(value, str) and value.strip():
                parsed[key] = value.strip()
        if parsed:
            return parsed
    response = payload.get("detail")
    if isinstance(response, str) and response.strip():
        return {"detail": response.strip()}
    return {}


def _is_text_model(identifier: str) -> bool:
    if not identifier.startswith(("gpt-5", "gpt-4.1", "gpt-4o")):
        return False
    excluded = ("realtime", "audio", "transcribe", "tts", "image", "search")
    return not any(part in identifier for part in excluded)


def _realtime_session(
    model: str,
    *,
    instructions_extra: str = "",
    extra_tools: tuple[dict[str, object], ...] = (),
    tool_definitions: tuple[dict[str, object], ...] | None = None,
) -> dict[str, object]:
    """Small on-device adaptation of the proven Voice device Realtime contract.

    ``instructions_extra`` carries the session-start recalled memory context
    (most recent approved memories + previous session summary), appended to the
    base voice instructions. ``extra_tools`` extends the granted function tools
    (the ``memory_lookup`` semantic-search tool is granted here so the voice
    agent can recall prior context mid-session).
    """
    # Keep Realtime mode composition out of provider module import time. The
    # agents package also consumes OpenAI provider types during initialization.
    from sam_runtime.realtime.modes import PRIMARY_VOICE_INSTRUCTION
    instructions = PRIMARY_VOICE_INSTRUCTION
    if instructions_extra:
        instructions = instructions + "\n\n" + instructions_extra
    tools = list(tool_definitions) if tool_definitions is not None else [
        {
            "type": "function",
            "name": "get_device_status",
            "description": "Read the current health of this SamRabbit on-device runtime.",
            "parameters": {
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        }
    ]
    if tool_definitions is None:
        tools.extend(extra_tools)
    return {
        "type": "realtime",
        "model": model,
        "instructions": instructions,
        "output_modalities": ["audio"],
        "audio": {
            "input": {
                "format": {"type": "audio/pcm", "rate": 24_000},
                "noise_reduction": {"type": "near_field"},
                "transcription": {"model": "gpt-4o-mini-transcribe"},
                "turn_detection": {
                    "type": "server_vad",
                    "create_response": True,
                    "interrupt_response": False,
                    "threshold": 0.92,
                    "prefix_padding_ms": 500,
                    "silence_duration_ms": 1_200,
                },
            },
            "output": {
                "format": {"type": "audio/pcm", "rate": 24_000},
                "voice": "marin",
            },
        },
        "tools": tools,
        "tool_choice": "auto",
    }


DICTATION_INSTRUCTION = (
    "Dictation relay. The user's speech is transcribed by the input transcription model. "
    "Never respond, never call tools, never speak."
)

# Dictation is English: without a language the transcriber turns room noise into text in other
# scripts (seen on the R1). Server VAD needs a clear voice near the mic (Voice uses 0.92).
DICTATION_LANGUAGE = "en"
DICTATION_VAD_THRESHOLD = 0.7

# Tried in order until OpenAI accepts one (the ChatGPT subscription credential may not allow a
# transcription-only session); the controller remembers the first accepted variant.
DICTATION_VARIANTS: tuple[tuple[str, str], ...] = (
    ("transcription", "gpt-4o-transcribe"),
    ("realtime", "gpt-4o-transcribe"),
    ("realtime", "gpt-4o-mini-transcribe"),
)


def _dictation_session(variant: str, *, model: str, transcription_model: str) -> dict[str, object]:
    """Speech-to-text only: server VAD segments the speech, nothing ever answers.

    ``transcription`` sessions cannot create responses at all. The ``realtime`` fallback
    keeps the Voice audio contract but turns off response creation, has no tools, text-only
    output capped at one token, and no output voice.
    """
    turn_detection: dict[str, object] = {
        "type": "server_vad",
        "threshold": DICTATION_VAD_THRESHOLD,
        "prefix_padding_ms": 300,
        "silence_duration_ms": 700,
    }
    audio_input: dict[str, object] = {
        "format": {"type": "audio/pcm", "rate": 24_000},
        "noise_reduction": {"type": "near_field"},
        "transcription": {"model": transcription_model, "language": DICTATION_LANGUAGE},
        "turn_detection": turn_detection,
    }
    if variant == "transcription":
        return {"type": "transcription", "audio": {"input": audio_input}}
    if variant != "realtime":
        raise ValueError(f"unknown dictation variant {variant!r}")
    turn_detection["create_response"] = False
    turn_detection["interrupt_response"] = False
    return {
        "type": "realtime",
        "model": model,
        "instructions": DICTATION_INSTRUCTION,
        "output_modalities": ["text"],
        "max_output_tokens": 1,
        "audio": {"input": audio_input},
        "tools": [],
        "tool_choice": "none",
    }


def _multipart(boundary: str, fields: tuple[tuple[str, str], ...]) -> bytes:
    chunks: list[bytes] = []
    for name, value in fields:
        chunks.extend(
            (
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode(),
                b"\r\n",
            )
        )
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks)
