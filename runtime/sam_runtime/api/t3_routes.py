"""T3 Code routes.

Device routes (``/v1/t3/*``, ``/v1/live/t3-thread/*``) are bearer-only and are
never allowlisted in the LAN management proxy. Management routes
(``/v1/management/t3*``) additionally require a paired browser session, and
CSRF for mutations. Responses never contain the T3 bearer token.
"""

from __future__ import annotations

from typing import TYPE_CHECKING
from urllib.parse import parse_qs, unquote

from ..core.logging import runtime_logger
from ..domains.t3.client import T3EndpointError, T3Error, T3RequestError, T3Unauthorized, T3Unavailable
from ..domains.t3.placement import ProjectPlacement
from ..domains.t3.service import (
    T3DispatchFailed,
    T3InvalidRequest,
    T3NotConnected,
    T3ReauthRequired,
    T3RequestNotPending,
    T3Service,
    T3ThreadNotFound,
)
from ..security.pairing import PairingAuthority

if TYPE_CHECKING:
    from .routes import RouteRequest


_THREADS = "/v1/t3/threads"
_LIVE = "/v1/live/t3-thread/"
_MANAGEMENT = "/v1/management/t3"
_LOG = runtime_logger()


class T3Routes:
    def __init__(self, service: T3Service, placement: ProjectPlacement | None = None) -> None:
        self._service = service
        self._placement = placement

    def _management_view(self) -> dict[str, object]:
        view = self._service.management_view()
        if self._placement is not None:
            try:
                view["orchestration"] = self._placement.management_view() if view.get("connected") else None
            except Exception:
                _LOG.exception("t3.orchestration.view_failed")
                view["orchestration"] = None
        return view

    # ---------------------------------------------------------------- GET
    def handle_get(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        path, query = _split(request.path)
        if path == _MANAGEMENT:
            if not _session(request, pairing, mutation=False):
                return True
            request.respond_json(200, self._management_view())
            return True
        if path == "/v1/t3/status":
            request.respond_json(200, self._service.status_view())
            return True
        if path == _THREADS:
            limit = _int(query, "limit", 40)
            request.respond_json(200, self._service.threads_view(limit=limit))
            return True
        if path.startswith(_THREADS + "/"):
            segments = _segments(path, _THREADS + "/")
            if len(segments) != 1:
                return False
            self._run(request, lambda: (200, self._service.thread_view(segments[0], turns=_int(query, "turns", 3))))
            return True
        if path.startswith(_LIVE):
            segments = _segments(path, _LIVE)
            if len(segments) != 1:
                return False
            self._run(request, lambda: (200, self._service.live_view(segments[0])))
            return True
        return False

    # --------------------------------------------------------------- POST
    def handle_post(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        path, _ = _split(request.path)
        if path == _MANAGEMENT + "/connect":
            if not _session(request, pairing, mutation=True):
                return True
            payload = request.request_json(max_bytes=4096)
            if payload is None:
                return True
            self._run(request, lambda: (200, self._service.connect(payload.get("serverUrl"), payload.get("pairingCode"))), connecting=True)
            return True
        if path == _MANAGEMENT + "/disconnect":
            if not _session(request, pairing, mutation=True):
                return True
            self._run(request, lambda: (200, self._service.disconnect()))
            return True
        if path == _MANAGEMENT + "/settings" and self._placement is not None:
            if not _session(request, pairing, mutation=True):
                return True
            payload = request.request_json(max_bytes=4096)
            if payload is None:
                return True
            if "orchestrationProjectId" not in payload:
                _error(request, 400, "invalid_request", "orchestrationProjectId is required (a project id or null).")
                return True
            placement = self._placement

            def save() -> tuple[int, dict[str, object]]:
                if not self._service.connected():
                    raise T3NotConnected("T3 Code is not connected.")
                placement.set_orchestration(payload.get("orchestrationProjectId"))
                return 200, self._management_view()

            self._run(request, save)
            return True
        if path == _THREADS:
            payload = request.request_json(max_bytes=65_536)
            if payload is None:
                return True
            self._run(request, lambda: (202, self._service.create_thread(
                _string(payload, "text"),
                title=_optional(payload, "title"),
                project_id=_optional(payload, "projectId"),
                project=_optional(payload, "project"),
                runtime_mode=_optional(payload, "runtimeMode"),
            )))
            return True
        if not path.startswith(_THREADS + "/"):
            return False
        segments = _segments(path, _THREADS + "/")
        if len(segments) == 2 and segments[1] == "messages":
            payload = request.request_json(max_bytes=65_536)
            if payload is None:
                return True
            self._run(request, lambda: (202, _ok(self._service.send_message(segments[0], _string(payload, "text")))))
            return True
        if len(segments) == 3 and segments[1] == "approvals":
            payload = request.request_json(max_bytes=1024)
            if payload is None:
                return True
            self._run(request, lambda: (200, _ok(self._service.respond_approval(segments[0], segments[2], _string(payload, "decision")))))
            return True
        if len(segments) == 3 and segments[1] == "inputs":
            payload = request.request_json(max_bytes=16_384)
            if payload is None:
                return True
            self._run(request, lambda: (200, _ok(self._service.respond_input(segments[0], segments[2], payload.get("answers")))))
            return True
        if len(segments) == 2 and segments[1] == "interrupt":
            self._run(request, lambda: (200, _ok(self._service.interrupt(segments[0]))))
            return True
        if len(segments) == 2 and segments[1] == "seen":
            self._run(request, lambda: (200, self._service.mark_seen(segments[0])))
            return True
        return False

    def handle_delete(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        return False

    # ------------------------------------------------------------ errors
    def _run(self, request: "RouteRequest", action, *, connecting: bool = False) -> None:
        try:
            status, payload = action()
        except T3NotConnected as error:
            _error(request, 409, "t3_not_connected", str(error) or "T3 Code is not connected.")
            return
        except T3ReauthRequired as error:
            _error(request, 409, "t3_reauth_required", str(error))
            return
        except T3ThreadNotFound as error:
            _error(request, 404, "t3_thread_not_found", str(error))
            return
        except T3RequestNotPending as error:
            _error(request, 409, "t3_request_not_pending", str(error))
            return
        except (T3InvalidRequest, T3EndpointError) as error:
            _error(request, 400, "invalid_request", str(error))
            return
        except T3Unauthorized:
            if connecting:
                _error(request, 400, "t3_pairing_rejected", "T3 Code rejected that pairing code. Create a new code in T3 Code > Settings > Connections and try again.")
            else:
                _error(request, 409, "t3_reauth_required", "T3 Code rejected this R1. Pair again.")
            return
        except T3DispatchFailed as error:
            details = {"threadId": error.thread_id} if error.thread_id else None
            _error(request, 502, "t3_dispatch_failed", str(error), details)
            return
        except T3Unavailable:
            _error(request, 502, "t3_unavailable", "T3 Code is unreachable. Check that the Mac is awake and on the same network.")
            return
        except T3RequestError as error:
            if connecting and error.status == 400 and error.reason == "scope_not_granted":
                _error(request, 400, "t3_pairing_rejected", "That pairing code does not allow reading and operating threads.")
            else:
                _error(request, 502, "t3_request_failed", str(error))
            return
        except T3Error as error:
            _error(request, 502, "t3_error", str(error) or "T3 Code request failed.")
            return
        except ValueError as error:
            _error(request, 400, "invalid_request", str(error))
            return
        except Exception:
            _LOG.exception("t3.route.failed")
            _error(request, 500, "t3_internal_error", "The T3 request failed on the R1.")
            return
        request.respond_json(status, payload)


def _ok(value: dict[str, object]) -> dict[str, object]:
    return {"ok": True, **{key: item for key, item in value.items() if key != "ok"}}


def _split(raw: str) -> tuple[str, dict[str, list[str]]]:
    path, _, query = raw.partition("?")
    return path, parse_qs(query)


def _segments(path: str, prefix: str) -> list[str]:
    return [unquote(part) for part in path[len(prefix):].split("/") if part]


def _int(query: dict[str, list[str]], key: str, default: int) -> int:
    try:
        return int((query.get(key) or [str(default)])[0])
    except ValueError:
        return default


def _string(payload: dict[str, object], key: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or not value.strip():
        raise T3InvalidRequest(f"{key} is required.")
    return value


def _optional(payload: dict[str, object], key: str) -> str | None:
    value = payload.get(key)
    if value is None:
        return None
    if not isinstance(value, str):
        raise T3InvalidRequest(f"{key} must be a string.")
    return value.strip() or None


def _session(request: "RouteRequest", pairing: PairingAuthority | None, *, mutation: bool) -> bool:
    if pairing is None:
        _error(request, 503, "management_unavailable", "Management pairing is unavailable.")
        return False
    return request.browser_session(pairing, mutation=mutation) is not None


def _error(request: "RouteRequest", status: int, code: str, message: str, details: dict[str, object] | None = None) -> None:
    error: dict[str, object] = {"code": code, "message": message}
    if details:
        error.update(details)
    request.respond_json(status, {"error": error})
