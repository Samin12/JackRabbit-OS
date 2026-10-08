"""HTTP client for the Mac-control routes of the SamRabbit Mac bridge.

It reuses the Heptabase journal's bridge configuration: the same ``BridgeStore`` instance (the
URL in ``provider_settings``, the token sealed with ``ConnectionCredentialEnvelopes``), so there is
one pairing and no second copy of the secret. Only private-LAN addresses are ever contacted.
"""

from __future__ import annotations

from collections.abc import Callable
import json
import sqlite3
import threading
import time
from urllib.parse import quote, urlsplit

from sam_runtime.domains.heptabase_journal.bridge import BRIDGE_SERVICE, BridgeStore, is_private_host
from sam_runtime.domains.heptabase_journal.client import HttpResponse, HttpTransport
from sam_runtime.domains.heptabase_journal.errors import HeptabaseError, TransportError

HEALTH_TIMEOUT_SECONDS = 15.0
QUICK_HEALTH_TIMEOUT_SECONDS = 4.0
STATE_TIMEOUT_SECONDS = 25.0
OPEN_TIMEOUT_SECONDS = 55.0
READ_TIMEOUT_SECONDS = 30.0
ACT_TIMEOUT_SECONDS = 45.0
SCREENSHOT_TIMEOUT_SECONDS = 40.0
CAPABILITIES_TTL_SECONDS = 120.0

_FRIENDLY = {
    "mac_not_configured": "The Mac is not connected to this R1 yet.",
    "mac_unreachable": "The Mac is unreachable. Check that it is awake and on the same network.",
    "mac_unauthorized": "The Mac bridge rejected the R1's token. Connect the Mac bridge again.",
    "mac_token_unavailable": "The R1 cannot read its Mac bridge token. Connect the Mac bridge again.",
    "bridge_outdated": "The bridge on the Mac is too old for Mac control. Run companion/mac-bridge/install.sh again.",
    "driver_missing": "cua-driver is not installed on the Mac, so it cannot be controlled yet.",
    "driver_unavailable": "cua-driver is not running on the Mac right now.",
    "driver_timeout": "The Mac did not answer in time.",
    "mac_busy": "The Mac is busy with another step. Try again in a moment.",
}


class MacFailure(Exception):
    """A failed Mac step: ``code`` (stable), a speakable ``message``, optional ``fix`` and ``details``."""

    def __init__(self, code: str, message: str, *, status: int | None = None, fix: str | None = None,
                 details: dict[str, object] | None = None, retryable: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.fix = fix
        self.details = details or {}
        self.retryable = retryable


class MacControlClient:
    def __init__(self, store: BridgeStore, transport: HttpTransport | None = None, *,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._store = store
        self._transport = transport or HttpTransport(timeout=STATE_TIMEOUT_SECONDS, allow_http=is_private_host)
        self._clock = clock
        self._lock = threading.Lock()
        self._capabilities: tuple[float, dict[str, object]] | None = None

    # ------------------------------------------------------------------ state
    def configured(self) -> bool:
        try:
            return self._store.config() is not None
        except sqlite3.Error:
            return False

    def bridge_url(self) -> str | None:
        try:
            config = self._store.config()
        except sqlite3.Error:
            return None
        return config.url if config is not None else None

    def cached_capabilities(self) -> dict[str, object] | None:
        """The last ``/health`` Mac capabilities seen (never blocks on the network)."""
        with self._lock:
            cached = self._capabilities
        if cached is None or self._clock() - cached[0] > CAPABILITIES_TTL_SECONDS:
            return None
        return cached[1]

    def capabilities(self) -> dict[str, object] | None:
        """The bridge's Mac capabilities: the cached ones, else one quick ``/health`` probe.

        ``None`` when the Mac does not answer quickly (callers then go on without them)."""
        cached = self.cached_capabilities()
        if cached is not None:
            return cached
        try:
            value = self._request("GET", "/health", timeout=QUICK_HEALTH_TIMEOUT_SECONDS)
        except MacFailure:
            return None
        mac = value.get("mac") if value.get("service") == BRIDGE_SERVICE else None
        if not isinstance(mac, dict):
            return None
        self._remember(mac)
        return mac

    def _remember(self, capabilities: object) -> None:
        if isinstance(capabilities, dict):
            with self._lock:
                self._capabilities = (self._clock(), capabilities)

    # ------------------------------------------------------------------ calls
    def health(self) -> dict[str, object]:
        value = self._request("GET", "/health", timeout=HEALTH_TIMEOUT_SECONDS)
        if value.get("service") != BRIDGE_SERVICE:
            raise MacFailure("not_a_bridge", "That address is not the SamRabbit Mac bridge.")
        if not isinstance(value.get("mac"), dict):
            raise MacFailure("bridge_outdated", _FRIENDLY["bridge_outdated"])
        self._remember(value["mac"])
        return value

    def state(self) -> dict[str, object]:
        value = self._request("GET", "/v1/mac/state", timeout=STATE_TIMEOUT_SECONDS)
        if isinstance(value.get("screenVision"), bool):
            cached = dict(self.cached_capabilities() or {})
            if cached:
                permissions = dict(cached.get("permissions") or {})
                permissions["screenRecording"] = value["screenVision"]
                cached["permissions"] = permissions
                self._remember(cached)
        return value

    def open(self, *, app: str | None = None, url: str | None = None, path: str | None = None) -> dict[str, object]:
        body = {key: value for key, value in (("app", app), ("url", url), ("path", path)) if value}
        return self._request("POST", "/v1/mac/open", body=body, timeout=OPEN_TIMEOUT_SECONDS)

    def read(self, app: str | None = None, max_chars: int | None = None) -> dict[str, object]:
        query = []
        if app:
            query.append("app=" + quote(app, safe=""))
        if max_chars:
            query.append(f"max={int(max_chars)}")
        return self._request("GET", "/v1/mac/read" + ("?" + "&".join(query) if query else ""),
                             timeout=READ_TIMEOUT_SECONDS)

    def act(self, body: dict[str, object]) -> dict[str, object]:
        return self._request("POST", "/v1/mac/act", body=body, timeout=ACT_TIMEOUT_SECONDS)

    def screenshot(self, app: str | None = None, max_side: int = 1024, *,
                   conversation_id: str | None = None) -> dict[str, object]:
        query = f"?max={int(max_side)}" + ("&app=" + quote(app, safe="") if app else "")
        if conversation_id:
            query += "&conversation=" + quote(conversation_id, safe="")
        value = self._request("GET", "/v1/mac/screenshot" + query, timeout=SCREENSHOT_TIMEOUT_SECONDS)
        if not isinstance(value.get("base64"), str) or not str(value.get("mime", "")).startswith("image/"):
            raise MacFailure("bad_screenshot", "The Mac sent an unreadable screenshot.")
        return value

    # ------------------------------------------------------------------ plumbing
    def _credentials(self) -> tuple[str, str]:
        try:
            config = self._store.config()
        except sqlite3.Error:
            config = None
        if config is None:
            raise MacFailure("mac_not_configured", _FRIENDLY["mac_not_configured"])
        token = self._store.token(config.url)
        if token is None:
            raise MacFailure("mac_token_unavailable", _FRIENDLY["mac_token_unavailable"])
        return config.url, token

    def _request(self, method: str, path: str, *, body: dict[str, object] | None = None,
                 timeout: float) -> dict[str, object]:
        url, token = self._credentials()
        if not is_private_host(urlsplit(url).hostname or ""):
            raise MacFailure("mac_not_local", "The Mac bridge address is not on the local network.")
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
        raw = None
        if body is not None:
            raw = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            response = self._transport.request(method, url + path, body=raw, headers=headers, timeout=timeout)
        except TransportError as error:
            if error.sent:  # the request reached the Mac: the step may or may not have happened
                raise MacFailure("mac_no_answer", "The Mac did not answer in time. Check before trying again.",
                                 retryable=False) from None
            raise MacFailure("mac_unreachable", _FRIENDLY["mac_unreachable"], retryable=True) from None
        except HeptabaseError:
            raise MacFailure("mac_response_too_large", "The Mac sent too much at once.") from None
        except ValueError:
            raise MacFailure("mac_url_invalid", "The Mac bridge address is invalid.") from None
        return self._result(response)

    def _result(self, response: HttpResponse) -> dict[str, object]:
        value = response.json()
        if 200 <= response.status < 300:
            note = getattr(self._store, "note_reachable", None)
            if callable(note):
                note()  # e.g. conversation sync stops waiting out a backoff
            return value
        if response.status == 401:
            raise MacFailure("mac_unauthorized", _FRIENDLY["mac_unauthorized"], status=401)
        error = value.get("error") if isinstance(value.get("error"), dict) else {}
        code = str(error.get("code") or f"http_{response.status}")[:64]
        if response.status == 404 and code == "not_found":
            raise MacFailure("bridge_outdated", _FRIENDLY["bridge_outdated"], status=404)
        message = _FRIENDLY.get(code) or str(error.get("message") or "The Mac could not do that.")[:300]
        fix = error.get("fix") if isinstance(error.get("fix"), str) else None
        details = {key: error[key] for key in ("suggestions", "options") if isinstance(error.get(key), list)}
        raise MacFailure(code, message, status=response.status, fix=fix, details=details,
                         retryable=bool(error.get("retryable")))
