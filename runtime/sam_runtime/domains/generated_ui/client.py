"""HTTP client for the generated-UI routes of the SamRabbit Mac bridge.

Same bridge, URL and sealed token as the Heptabase journal and Mac control (``BridgeStore``); only private-LAN
addresses are ever contacted. The bridge makes the widget with Claude Code on the Mac and serves a JPEG
preview sized for the R1.
"""

from __future__ import annotations

import json
import re
import sqlite3
from urllib.parse import urlsplit

from sam_runtime.domains.heptabase_journal.bridge import BridgeStore, is_private_host
from sam_runtime.domains.heptabase_journal.client import HttpResponse, HttpTransport
from sam_runtime.domains.heptabase_journal.errors import HeptabaseError, TransportError

GENERATE_TIMEOUT_SECONDS = 12.0
STATUS_TIMEOUT_SECONDS = 10.0
IMAGE_TIMEOUT_SECONDS = 20.0
MAX_IMAGE_BYTES = 400 * 1024
MAX_BODY_BYTES = 60 * 1024  # the bridge reads at most 64 KB of JSON; 24000 non-ASCII characters can be ~96 KB
ARTIFACT_ID = re.compile(r"^ui_[0-9a-f]{24}$")
_SAFE_ID = re.compile(r"[^A-Za-z0-9._:\-]")

_FRIENDLY = {
    "mac_not_configured": "The Mac is not connected to this R1, so I can't make visuals right now.",
    "mac_unreachable": "The Mac is unreachable. Check that it is awake and on the same network.",
    "mac_unauthorized": "The Mac bridge rejected the R1's token. Connect the Mac bridge again.",
    "mac_token_unavailable": "The R1 cannot read its Mac bridge token. Connect the Mac bridge again.",
    "bridge_outdated": "The bridge on the Mac is too old for visuals. Run companion/mac-bridge/install.sh again.",
    "genui_busy": "The Mac is already making several visuals. Try again in a minute.",
}


class GeneratedUiFailure(Exception):
    """A failed bridge call: stable ``code``, speakable ``message``."""

    def __init__(self, code: str, message: str, *, status: int | None = None, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.retryable = retryable


def safe_id(value: str, *, limit: int = 128) -> str:
    """Request and conversation ids as the bridge accepts them (letters, digits, ``._:-``)."""
    return _SAFE_ID.sub("_", value)[:limit]


class GeneratedUiClient:
    def __init__(self, store: BridgeStore, transport: HttpTransport | None = None) -> None:
        self._store = store
        self._transport = transport or HttpTransport(timeout=STATUS_TIMEOUT_SECONDS, allow_http=is_private_host)

    def configured(self) -> bool:
        try:
            return self._store.config() is not None
        except sqlite3.Error:
            return False

    def generate(self, *, request_id: str, prompt: str, data: str | None, conversation_id: str | None,
                 size: str = "r1") -> dict[str, object]:
        body: dict[str, object] = {"requestId": safe_id(request_id), "prompt": prompt, "size": size}
        if conversation_id:
            body["conversationId"] = safe_id(conversation_id)
        if data:
            body["data"] = data
            while len(_encode(body)) > MAX_BODY_BYTES and body["data"]:
                text = str(body["data"])  # shorten the data (never the request) until the bridge accepts it
                body["data"] = text[:max(0, int(len(text) * 0.85) - 16)]
            if not body["data"]:
                body.pop("data")
        value = self._json("POST", "/v1/ui/generate", body=body, timeout=GENERATE_TIMEOUT_SECONDS)
        if not isinstance(value.get("artifactId"), str) or not ARTIFACT_ID.match(value["artifactId"]):
            raise GeneratedUiFailure("bad_answer", "The Mac answered with something unexpected.")
        return value

    def artifact(self, artifact_id: str) -> dict[str, object]:
        _check(artifact_id)
        return self._json("GET", f"/v1/ui/artifacts/{artifact_id}", timeout=STATUS_TIMEOUT_SECONDS)

    def image(self, artifact_id: str) -> bytes:
        _check(artifact_id)
        response = self._request("GET", f"/v1/ui/artifacts/{artifact_id}/image", accept="image/jpeg",
                                 timeout=IMAGE_TIMEOUT_SECONDS)
        if 200 <= response.status < 300:
            if not response.header("content-type").startswith("image/jpeg") or not response.body.startswith(b"\xff\xd8"):
                raise GeneratedUiFailure("bad_image", "The Mac sent an unreadable picture.")
            if len(response.body) > MAX_IMAGE_BYTES:
                raise GeneratedUiFailure("image_too_large", "The Mac sent a picture that is too large.")
            return response.body
        self._raise(response)
        raise AssertionError("unreachable")

    # ------------------------------------------------------------------ plumbing
    def _credentials(self) -> tuple[str, str]:
        try:
            config = self._store.config()
        except sqlite3.Error:
            config = None
        if config is None:
            raise GeneratedUiFailure("mac_not_configured", _FRIENDLY["mac_not_configured"])
        token = self._store.token(config.url)
        if token is None:
            raise GeneratedUiFailure("mac_token_unavailable", _FRIENDLY["mac_token_unavailable"])
        return config.url, token

    def _request(self, method: str, path: str, *, body: dict[str, object] | None = None,
                 accept: str = "application/json", timeout: float) -> HttpResponse:
        url, token = self._credentials()
        if not is_private_host(urlsplit(url).hostname or ""):
            raise GeneratedUiFailure("mac_not_local", "The Mac bridge address is not on the local network.")
        headers = {"Authorization": f"Bearer {token}", "Accept": accept}
        raw = None
        if body is not None:
            raw = _encode(body)
            headers["Content-Type"] = "application/json"
        try:
            return self._transport.request(method, url + path, body=raw, headers=headers, timeout=timeout)
        except TransportError as error:
            # Generation is idempotent per requestId, so a lost answer is safe to retry.
            raise GeneratedUiFailure("mac_unreachable", _FRIENDLY["mac_unreachable"], retryable=True) from error
        except HeptabaseError:
            raise GeneratedUiFailure("mac_response_too_large", "The Mac sent too much at once.") from None
        except ValueError:
            raise GeneratedUiFailure("mac_url_invalid", "The Mac bridge address is invalid.") from None

    def _json(self, method: str, path: str, *, body: dict[str, object] | None = None,
              timeout: float) -> dict[str, object]:
        response = self._request(method, path, body=body, timeout=timeout)
        if 200 <= response.status < 300:
            return response.json()
        self._raise(response)
        raise AssertionError("unreachable")

    def _raise(self, response: HttpResponse) -> None:
        if response.status == 401:
            raise GeneratedUiFailure("mac_unauthorized", _FRIENDLY["mac_unauthorized"], status=401)
        value = response.json()
        error = value.get("error") if isinstance(value.get("error"), dict) else {}
        code = str(error.get("code") or f"http_{response.status}")[:64]
        if response.status == 404 and code == "not_found":
            raise GeneratedUiFailure("bridge_outdated", _FRIENDLY["bridge_outdated"], status=404)
        message = _FRIENDLY.get(code) or str(error.get("message") or "The Mac could not make that visual.")[:300]
        raise GeneratedUiFailure(code, message, status=response.status, retryable=bool(error.get("retryable")))


def _encode(body: dict[str, object]) -> bytes:
    return json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _check(artifact_id: str) -> None:
    if not isinstance(artifact_id, str) or not ARTIFACT_ID.match(artifact_id):
        raise GeneratedUiFailure("artifact_not_found", "No such visual.", status=404)
