"""Mac control status for the management page (paired browser session).

``GET /v1/management/mac`` probes the bridge's ``/health`` and answers whether the Mac is
reachable and what it can do (cua-driver, Accessibility, Screen Recording and the one command
that turns screen vision on). The bridge itself is configured on the Heptabase journal card;
the orchestration project lives on ``/v1/management/t3``. Never returns the bridge token.

The view also carries ``googleAccount`` ``{email, source, fromCalendar}``: the Google account that Google links
opened in Chrome use. ``POST /v1/management/mac`` ``{"googleAccount": "name@example.com"}`` saves it (an empty
string or null goes back to the calendar's account) and answers ``{"googleAccount": {...}}``.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

from ..core.logging import runtime_logger
from ..domains.mac import GoogleAccountSetting, MacControlClient, MacFailure
from ..security.pairing import PairingAuthority

if TYPE_CHECKING:
    from .routes import RouteRequest

_PATH = "/v1/management/mac"
_LOG = runtime_logger()


class MacRoutes:
    def __init__(self, client: MacControlClient, *, google_account: GoogleAccountSetting | None = None) -> None:
        self._client = client
        self._google_account = google_account

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
        if request.path.split("?", 1)[0] != _PATH or self._google_account is None:
            return False
        if pairing is None:
            request.respond_json(503, {"error": {"code": "management_unavailable",
                                                 "message": "Management pairing is unavailable."}})
            return True
        if request.browser_session(pairing, mutation=True) is None:
            return True
        payload = request.request_json(max_bytes=1024)
        if payload is None:
            return True
        if "googleAccount" not in payload or not (payload["googleAccount"] is None
                                                  or isinstance(payload["googleAccount"], str)):
            request.respond_json(400, {"error": {"code": "invalid_request",
                                                 "message": "googleAccount is required (an email address or null)."}})
            return True
        try:
            self._google_account.set(payload["googleAccount"])
        except ValueError as error:
            request.respond_json(400, {"error": {"code": "invalid_request", "message": str(error)}})
            return True
        request.respond_json(200, {"googleAccount": self._google_account.view()})
        return True

    def view(self) -> dict[str, object]:
        checked = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        account = {"googleAccount": self._google_account.view()} if self._google_account is not None else {}
        if not self._client.configured():
            return {"configured": False, "url": None, "reachable": None, "capabilities": None, "checkedAt": checked,
                    "message": "Connect the Mac bridge first (Heptabase journal > Connect through your Mac).",
                    **account}
        view: dict[str, object] = {"configured": True, "url": self._client.bridge_url(), "checkedAt": checked,
                                   **account}
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
