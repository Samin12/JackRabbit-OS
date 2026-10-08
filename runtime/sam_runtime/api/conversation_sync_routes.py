"""Conversation-sync routes.

* Device (bearer only; refused when proxied for a browser, i.e. with ``X-SAM-Forwarded-Origin``):
  ``POST /v1/voice/conversation/events`` ``{conversationId, sessionId, events:[...]}`` -> 202
  ``{accepted, duplicates, rejected, skipped, syncing}`` (body <= 256 KB);
  ``POST /v1/voice/conversation/blobs`` raw image bytes (``Content-Type``, ``X-SAM-Conversation``)
  -> ``{blobId, bytes, mime, stored}`` (<= 400 KB); ``GET /v1/voice/conversation/status``.
* Management (paired browser session + CSRF): ``GET /v1/management/conversation-sync`` and
  ``POST /v1/management/conversation-sync/settings`` ``{enabled?, includeAssistant?, includeTools?,
  includeImages?, redactSecrets?}``.
* Hooks for ``RuntimeRoutes``: ``link_call`` (``/v1/voice/calls`` with ``conversationId``) and
  ``session_finalized`` (after ``/v1/voice/sessions/finalize``). Neither ever raises.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..core.logging import runtime_logger
from ..domains.conversation_sync import ConversationSyncService
from ..security.pairing import PairingAuthority

if TYPE_CHECKING:
    from .routes import RouteRequest

EVENTS_PATH = "/v1/voice/conversation/events"
BLOBS_PATH = "/v1/voice/conversation/blobs"
STATUS_PATH = "/v1/voice/conversation/status"
MANAGEMENT_PATH = "/v1/management/conversation-sync"
MAX_EVENTS_BODY_BYTES = 256 * 1024
MAX_BLOB_BODY_BYTES = 400 * 1024
_LOG = runtime_logger()


class ConversationSyncRoutes:
    def __init__(self, service: ConversationSyncService) -> None:
        self._service = service

    @property
    def service(self) -> ConversationSyncService:
        return self._service

    # ------------------------------------------------------------------ hooks
    def link_call(self, conversation_id: object, voice_session_id: object) -> None:
        try:
            self._service.link_call(conversation_id, voice_session_id)
        except Exception:
            _LOG.exception("conversation_sync.link_hook_failed")

    def session_finalized(self, session_id: str, entries: object, finalized: object | None) -> None:
        try:
            self._service.session_finalized(session_id, entries, finalized)
        except Exception:
            _LOG.exception("conversation_sync.finalized_hook_failed")

    # ------------------------------------------------------------------ routes
    def handle_get(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        path = request.path.split("?", 1)[0]
        if path == STATUS_PATH:
            if _device_only(request):
                request.respond_json(200, self._service.status_view())
            return True
        if path == MANAGEMENT_PATH:
            if _session(request, pairing, mutation=False):
                request.respond_json(200, self._service.management_view())
            return True
        return False

    def handle_post(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        path = request.path.split("?", 1)[0]
        if path == EVENTS_PATH:
            if not _device_only(request):
                return True
            payload = request.request_json(max_bytes=MAX_EVENTS_BODY_BYTES)
            if payload is None:
                return True
            try:
                result = self._service.ingest_events(payload)
            except ValueError as error:
                _error(request, 400, "invalid_request", str(error))
                return True
            except Exception:
                _LOG.exception("conversation_sync.ingest_failed")
                _error(request, 500, "sync_unavailable", "The conversation could not be stored for sync.")
                return True
            request.respond_json(202, result)
            return True
        if path == BLOBS_PATH:
            if not _device_only(request):
                return True
            data = request.request_bytes(max_bytes=MAX_BLOB_BODY_BYTES)
            if data is None:
                return True
            try:
                result = self._service.ingest_blob(data, request.headers.get("Content-Type", ""),
                                                   request.headers.get("X-SAM-Conversation"))
            except ValueError as error:
                _error(request, 400, "invalid_request", str(error))
                return True
            except Exception:
                _LOG.exception("conversation_sync.blob_ingest_failed")
                _error(request, 500, "sync_unavailable", "The image could not be stored for sync.")
                return True
            request.respond_json(200, result)
            return True
        if path == MANAGEMENT_PATH + "/settings":
            if not _session(request, pairing, mutation=True):
                return True
            payload = request.request_json(max_bytes=2048)
            if payload is None:
                return True
            try:
                request.respond_json(200, self._service.save_settings(payload))
            except ValueError as error:
                _error(request, 400, "invalid_request", str(error))
            return True
        return False

    def handle_delete(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        return False


def _device_only(request: "RouteRequest") -> bool:
    if request.headers.get("X-SAM-Forwarded-Origin"):
        _error(request, 403, "device_only", "Conversation sync is only available on the device.")
        return False
    return True


def _session(request: "RouteRequest", pairing: PairingAuthority | None, *, mutation: bool) -> bool:
    if pairing is None:
        _error(request, 503, "management_unavailable", "Management pairing is unavailable.")
        return False
    return request.browser_session(pairing, mutation=mutation) is not None


def _error(request: "RouteRequest", status: int, code: str, message: str) -> None:
    request.respond_json(status, {"error": {"code": code, "message": message}})
