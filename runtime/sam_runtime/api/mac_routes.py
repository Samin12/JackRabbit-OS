"""Mac control status for the management page (paired browser session).

``GET /v1/management/mac`` probes the bridge's ``/health`` and answers whether the Mac is
reachable and what it can do (cua-driver, Accessibility, Screen Recording and the one command
that turns screen vision on). The bridge itself is configured on the Heptabase journal card;
the orchestration project lives on ``/v1/management/t3``. Never returns the bridge token.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

from ..core.logging import runtime_logger
from ..domains.mac import MacControlClient, MacFailure
from ..security.pairing import PairingAuthority

if TYPE_CHECKING:
    from .routes import RouteRequest

_PATH = "/v1/management/mac"
_LOG = runtime_logger()


class MacRoutes:
    def __init__(self, client: MacControlClient) -> None:
        self._client = client

    def handle_get(self, request: "RouteRequest", pairing: PairingAuthority | None) -> bool:
        if request.path.split("?", 1)[0] != _PATH:
            return False
        if pairing is None:
            request.respond_json(503, {"error": {"code": "management_unavailable",
                                                 "message": "Management pairing is unavailable."}})
            return True
        if request.browser_session(pairing, mutation=False) is None:
            return True
        request.respond_json(200, self.view())
        return True

    def handle_post(self, request: "RouteRequest", pairing: PairingAuthority | None) -> bool:
        return False

    def view(self) -> dict[str, object]:
        checked = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        if not self._client.configured():
            return {"configured": False, "url": None, "reachable": None, "capabilities": None, "checkedAt": checked,
                    "message": "Connect the Mac bridge first (Heptabase journal > Connect through your Mac)."}
        view: dict[str, object] = {"configured": True, "url": self._client.bridge_url(), "checkedAt": checked}
        try:
            health = self._client.health()
        except MacFailure as failure:
            view.update({"reachable": failure.code not in ("mac_unreachable", "mac_no_answer"), "capabilities": None,
                         "error": failure.code, "message": failure.message})
            return view
        except Exception:
            _LOG.exception("mac.management.health_failed")
            view.update({"reachable": None, "capabilities": None, "error": "internal_error",
                         "message": "The Mac status could not be read."})
            return view
        view.update({"reachable": True, "bridgeVersion": health.get("version"), "capabilities": health.get("mac")})
        return view
