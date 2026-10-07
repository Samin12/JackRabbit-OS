"""Heptabase journal routes.

* Device (bearer only, never proxied to the LAN): ``/v1/journal/status``, ``/v1/journal/notes``.
* Management (paired browser session + CSRF): ``/v1/management/heptabase[...]``.
* OAuth callback ``/v1/heptabase/oauth/callback`` (GET query or POST JSON): authenticated
  by the single-use ``state`` alone, because the SameSite=Strict session cookie is not
  sent on the cross-site redirect back from Heptabase. It is proxied by :8443.
"""

from __future__ import annotations

import html
from typing import TYPE_CHECKING
from urllib.parse import parse_qs

from sam_runtime.core.logging import runtime_logger
from sam_runtime.domains.heptabase_journal import AuthorizationError, HeptabaseError, HeptabaseJournalService, NotConnected
from sam_runtime.security.pairing import PairingAuthority

if TYPE_CHECKING:
    from .routes import RouteRequest

CALLBACK_PATH = "/v1/heptabase/oauth/callback"
_BASE = "/v1/management/heptabase"
_SETTINGS_KEYS = ("autoSessions", "includeActions", "includeAssistant", "timezone", "redactSecrets")
_LOG = runtime_logger()


class HeptabaseRoutes:
    def __init__(self, service: HeptabaseJournalService) -> None:
        self._service = service

    @property
    def service(self) -> HeptabaseJournalService:
        return self._service

    def voice_session_finalized(self, session_id: str, entries: object) -> None:
        """Finalize hook: never raises, so memory finalization always proceeds."""
        try:
            self._service.finalize_session(session_id, entries if isinstance(entries, list) else [])
        except Exception:
            _LOG.exception("heptabase.session.finalize_hook_failed")

    def handle_get(self, request: "RouteRequest", pairing: PairingAuthority | None) -> bool:
        path = request.path.split("?", 1)[0]
        if path == "/v1/journal/status":
            request.respond_json(200, self._service.device_status())
            return True
        if path == CALLBACK_PATH:
            query = parse_qs(request.path.split("?", 1)[1] if "?" in request.path else "", max_num_fields=16)
            self._callback(request, {key: values[0] for key, values in query.items() if values}, as_html=True)
            return True
        if path == _BASE:
            if not _session(request, pairing, mutation=False):
                return True
            request.respond_json(200, self._service.management_view())
            return True
        return False

    def handle_post(self, request: "RouteRequest", pairing: PairingAuthority | None) -> bool:
        path = request.path.split("?", 1)[0]
        if path == "/v1/journal/notes":
            payload = request.request_json(max_bytes=16_384)
            if payload is None:
                return True
            text = payload.get("text")
            if not isinstance(text, str) or not text.strip():
                _error(request, 400, "invalid_request", "text is required.")
                return True
            try:
                request.respond_json(200, self._service.record_note(text))
            except NotConnected as error:
                _error(request, 409, error.code, str(error))
            except ValueError as error:
                _error(request, 400, "invalid_request", str(error))
            return True
        if path == CALLBACK_PATH:
            payload = request.request_json(max_bytes=8192)
            if payload is None:
                return True
            self._callback(request, {key: value for key, value in payload.items() if isinstance(value, str)},
                           as_html=False)
            return True
        if path != _BASE and not path.startswith(_BASE + "/"):
            return False
        if not _session(request, pairing, mutation=True):
            return True
        payload = request.request_json(max_bytes=8192)
        if payload is None:
            return True
        try:
            if path == _BASE + "/connect/start":
                redirect = payload.get("redirect", "loopback")
                result = self._service.connect_start(
                    str(redirect), request.headers.get("X-SAM-Forwarded-Origin"),
                    reregister=payload.get("reregister") is True,
                )
            elif path == _BASE + "/settings":
                changes = {key: payload[key] for key in _SETTINGS_KEYS if key in payload}
                unknown = sorted(set(payload) - set(_SETTINGS_KEYS))
                if unknown:
                    raise ValueError(f"Unknown setting: {unknown[0]}.")
                result = self._service.save_settings(changes)
            elif path == _BASE + "/disconnect":
                result = self._service.disconnect()
            elif path == _BASE + "/retry":
                result = self._service.retry_failed()
            elif path == _BASE + "/oauth/import":
                result = self._service.import_tokens(payload)
            else:
                _error(request, 404, "not_found", "Not found.")
                return True
        except AuthorizationError as error:
            _error(request, error.status or 400, error.code, str(error))
            return True
        except HeptabaseError as error:
            _error(request, 502, error.code, str(error))
            return True
        except ValueError as error:
            _error(request, 400, "invalid_request", str(error))
            return True
        request.respond_json(200, result)
        return True

    def handle_delete(self, request: "RouteRequest", pairing: PairingAuthority | None) -> bool:
        return False

    def _callback(self, request: "RouteRequest", params: dict[str, str], *, as_html: bool) -> None:
        state = params.get("state", "")
        try:
            if not state:
                raise AuthorizationError("invalid_callback", "The authorization response had no state.")
            view = self._service.complete_authorization(
                state=state, code=params.get("code"), issuer=params.get("iss"), error=params.get("error"),
            )
        except AuthorizationError as error:
            _LOG.warning("heptabase.callback.rejected", extra={"code": error.code})
            if as_html:
                request.respond_bytes(error.status or 400, _page(False, str(error)), content_type="text/html; charset=utf-8")
            else:
                _error(request, error.status or 400, error.code, str(error))
            return
        except Exception:
            _LOG.exception("heptabase.callback.failed")
            if as_html:
                request.respond_bytes(500, _page(False, "The R1 could not finish connecting. Try again."),
                                      content_type="text/html; charset=utf-8")
            else:
                _error(request, 500, "callback_failed", "The R1 could not finish connecting.")
            return
        result = {"connected": True, "writeVerified": view.get("writeVerified"),
                  "refreshAvailable": view.get("refreshAvailable")}
        if as_html:
            request.respond_bytes(200, _page(True, "Heptabase is connected to your R1. You can close this tab."),
                                  content_type="text/html; charset=utf-8")
        else:
            request.respond_json(200, result)


def _session(request: "RouteRequest", pairing: PairingAuthority | None, *, mutation: bool) -> bool:
    if pairing is None:
        _error(request, 503, "management_unavailable", "Management pairing is unavailable.")
        return False
    return request.browser_session(pairing, mutation=mutation) is not None


def _error(request: "RouteRequest", status: int, code: str, message: str) -> None:
    request.respond_json(status, {"error": {"code": code, "message": message}})


def _page(ok: bool, message: str) -> bytes:
    title = "Connected" if ok else "Not connected"
    accent = "#79f2dd" if ok else "#ff8f8f"
    body = (
        "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        f"<title>SamRabbit · Heptabase {title}</title><style>"
        "body{margin:0;min-height:100vh;display:grid;place-items:center;background:#07090f;color:#e8eef7;"
        "font:16px/1.5 -apple-system,system-ui,sans-serif}"
        "main{max-width:420px;padding:32px;text-align:center}"
        f".orb{{width:84px;height:84px;margin:0 auto 24px;border-radius:50%;"
        f"background:radial-gradient(circle at 35% 30%,#fff8,{accent} 45%,#0000 72%);"
        f"box-shadow:0 0 60px {accent}55}}"
        "h1{margin:0 0 8px;font-weight:500;font-size:1.4rem}p{margin:0;color:#9fb0c6}"
        "</style></head><body><main><div class=\"orb\"></div>"
        f"<h1>Heptabase journal · {html.escape(title)}</h1><p>{html.escape(message)}</p></main></body></html>"
    )
    return body.encode("utf-8")
