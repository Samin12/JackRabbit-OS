"""Device-only announcement long-poll (bearer only; never proxied to the LAN)."""

from __future__ import annotations

from typing import TYPE_CHECKING
from urllib.parse import parse_qs

from ..security.pairing import PairingAuthority
from ..storage.announcements import ACK_CHANNELS, MAX_WAIT_SECONDS, AnnouncementRepository

if TYPE_CHECKING:
    from .routes import RouteRequest


_PREFIX = "/v1/host/announcements/"


class AnnouncementRoutes:
    def __init__(self, announcements: AnnouncementRepository) -> None:
        self._announcements = announcements

    def handle_get(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        path = request.path.split("?", 1)[0]
        if path != _PREFIX + "next":
            return False
        query = parse_qs(request.path.split("?", 1)[1] if "?" in request.path else "")
        try:
            raw_after = (query.get("after") or [""])[0].strip()
            after = int(raw_after) if raw_after else None
            raw_wait = (query.get("wait") or [""])[0].strip()
            wait = float(raw_wait) if raw_wait else MAX_WAIT_SECONDS
        except ValueError:
            _error(request, 400, "invalid_request", "after and wait must be numbers.")
            return True
        if after is not None and after < 0:
            after = None
        items, cursor = self._announcements.next(after, wait_seconds=wait)
        request.respond_json(200, {"announcements": [item.view() for item in items], "cursor": cursor})
        return True

    def handle_post(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        path = request.path.split("?", 1)[0]
        if not (path.startswith(_PREFIX) and path.endswith("/ack")):
            return False
        raw_id = path[len(_PREFIX):-len("/ack")]
        try:
            announcement_id = int(raw_id)
        except ValueError:
            _error(request, 404, "announcement_not_found", "Announcement not found.")
            return True
        payload = request.request_json(max_bytes=1024)
        if payload is None:
            return True
        channel = str(payload.get("channel", "")).strip()
        if channel not in ACK_CHANNELS:
            _error(request, 400, "invalid_request", "channel must be voice, notification, or seen.")
            return True
        if not self._announcements.ack(announcement_id, channel):
            _error(request, 404, "announcement_not_found", "Announcement not found.")
            return True
        request.respond_json(200, {"ok": True, "id": announcement_id, "channel": channel})
        return True

    def handle_delete(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        return False


def _error(request: "RouteRequest", status: int, code: str, message: str) -> None:
    request.respond_json(status, {"error": {"code": code, "message": message}})
