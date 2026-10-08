"""Device-only proxy for generated-UI pictures (bearer only; never answered for forwarded LAN requests).

``GET /v1/ui/artifacts/<artifactId>/image`` returns the R1 preview JPEG that the Mac bridge rendered (at most
150 KB, 960 px wide). The app fetches it after a ``ui.generated`` announcement.
"""

from __future__ import annotations

import re
from typing import TYPE_CHECKING

from ..domains.generated_ui import GeneratedUiClient, GeneratedUiFailure
from ..security.pairing import PairingAuthority

if TYPE_CHECKING:
    from .routes import RouteRequest

_IMAGE = re.compile(r"^/v1/ui/artifacts/(ui_[0-9a-f]{24})/image$")
_STATUS = {"mac_not_configured": 503, "mac_token_unavailable": 503, "mac_unreachable": 502, "mac_unauthorized": 502,
           "bridge_outdated": 502, "artifact_not_found": 404, "image_not_ready": 409}


class GeneratedUiRoutes:
    def __init__(self, client: GeneratedUiClient) -> None:
        self._client = client

    def handle_get(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        match = _IMAGE.match(request.path.split("?", 1)[0])
        if match is None:
            return False
        if request.headers.get("X-SAM-Forwarded-Origin"):
            request.respond_json(403, {"error": {"code": "device_only",
                                                 "message": "Generated pictures are only served to the device."}})
            return True
        try:
            image = self._client.image(match.group(1))
        except GeneratedUiFailure as failure:
            request.respond_json(_STATUS.get(failure.code, 502),
                                 {"error": {"code": failure.code, "message": failure.message,
                                            "retryable": failure.retryable}})
            return True
        request.respond_bytes(200, image, content_type="image/jpeg")
        return True

    def handle_post(self, request: "RouteRequest", pairing: PairingAuthority | None = None) -> bool:
        return False
