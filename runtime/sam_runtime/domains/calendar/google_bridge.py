"""HTTP client for the Google Calendar routes of the SamRabbit Mac bridge (``/v1/calendar/*``).

The R1 reads the user's Google Calendar from its secret iCal address, which is read-only. Changes go
through the Mac bridge, which runs the signed-in Composio CLI. Same bridge, URL and sealed token as the
Heptabase journal and Mac control (``BridgeStore``); only private-LAN addresses are ever contacted.
"""

from __future__ import annotations

from collections.abc import Callable
import json
import sqlite3
import threading
import time
from urllib.parse import urlsplit

from sam_runtime.domains.heptabase_journal.bridge import BridgeStore, is_private_host
from sam_runtime.domains.heptabase_journal.client import HttpResponse, HttpTransport
from sam_runtime.domains.heptabase_journal.errors import HeptabaseError, TransportError

STATUS_TIMEOUT_SECONDS = 6.0
# The bridge allows the Composio CLI 30 s (plus a short wait for another change); the R1 app gives a
# tool call 65 s in total.
WRITE_TIMEOUT_SECONDS = 50.0
STATUS_TTL_SECONDS = 60.0

_FRIENDLY = {
    "mac_not_configured": "the Mac bridge is not connected to this R1",
    "mac_unreachable": "the Mac is unreachable (check that it is awake and on the same network)",
    "mac_unauthorized": "the Mac bridge rejected the R1's token (connect the Mac bridge again)",
    "mac_token_unavailable": "the R1 cannot read its Mac bridge token (connect the Mac bridge again)",
    "mac_not_local": "the Mac bridge address is not on the local network",
    "bridge_outdated": "the bridge on the Mac is too old for calendar changes (run companion/mac-bridge/install.sh "
                       "again)",
    "composio_missing": "the Composio CLI is not installed on the Mac",
}


class CalendarWriteFailure(RuntimeError):
    """A calendar change that did not happen (or may have): stable ``code``, a short ``reason`` (no final
    period), and ``written`` (``False``, or ``"unknown"`` when it may have reached Google Calendar)."""

    def __init__(self, code: str, reason: str, *, status: int | None = None, retryable: bool = False,
                 written: object = False, fix: str | None = None) -> None:
        super().__init__(reason)
        self.code = code
        self.reason = reason
        self.status = status
        self.retryable = retryable
        self.written = written
        self.fix = fix


class GoogleCalendarBridge:
    def __init__(self, store: BridgeStore, transport: HttpTransport | None = None, *,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._store = store
        self._transport = transport or HttpTransport(timeout=STATUS_TIMEOUT_SECONDS, allow_http=is_private_host)
        self._clock = clock
        self._lock = threading.Lock()
        self._status: tuple[float, dict[str, object]] | None = None

    def configured(self) -> bool:
        try:
            return self._store.config() is not None
        except sqlite3.Error:
            return False

    # ------------------------------------------------------------------ availability
    def cached_status(self) -> dict[str, object] | None:
        """The last ``/v1/calendar/status`` seen (never blocks on the network)."""
        with self._lock:
            cached = self._status
        if cached is None or self._clock() - cached[0] > STATUS_TTL_SECONDS:
            return None
        return cached[1]

    def require_available(self) -> dict[str, object]:
        """The bridge's calendar status when it can write; ``CalendarWriteFailure`` saying why not otherwise."""
        status = self.cached_status()
        if status is None:
            status = self._json("GET", "/v1/calendar/status", timeout=STATUS_TIMEOUT_SECONDS, writing=False)
            with self._lock:
                self._status = (self._clock(), status)
        if status.get("available") is not True:
            raise CalendarWriteFailure("composio_missing", _FRIENDLY["composio_missing"],
                                       fix="Install the Composio CLI on the Mac, sign in, link Google Calendar "
                                           "(composio link googlecalendar) and run install.sh again.")
        return status

    def forget_status(self) -> None:
        with self._lock:
            self._status = None

    # ------------------------------------------------------------------ changes
    def create(self, body: dict[str, object]) -> dict[str, object]:
        return self._write("/v1/calendar/events", body)

    def update(self, body: dict[str, object]) -> dict[str, object]:
        return self._write("/v1/calendar/events/update", body)

    def delete(self, body: dict[str, object]) -> dict[str, object]:
        return self._write("/v1/calendar/events/delete", body)

    def _write(self, path: str, body: dict[str, object]) -> dict[str, object]:
        try:
            return self._json("POST", path, body=body, timeout=WRITE_TIMEOUT_SECONDS, writing=True)
        except CalendarWriteFailure as failure:
            if failure.code in ("composio_missing", "bridge_outdated", "mac_unreachable"):
                self.forget_status()
            raise

    # ------------------------------------------------------------------ plumbing
    def _credentials(self) -> tuple[str, str]:
        try:
            config = self._store.config()
        except sqlite3.Error:
            config = None
        if config is None:
            raise CalendarWriteFailure("mac_not_configured", _FRIENDLY["mac_not_configured"])
        token = self._store.token(config.url)
        if token is None:
            raise CalendarWriteFailure("mac_token_unavailable", _FRIENDLY["mac_token_unavailable"])
        return config.url, token

    def _json(self, method: str, path: str, *, body: dict[str, object] | None = None, timeout: float,
              writing: bool) -> dict[str, object]:
        url, token = self._credentials()
        if not is_private_host(urlsplit(url).hostname or ""):
            raise CalendarWriteFailure("mac_not_local", _FRIENDLY["mac_not_local"])
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
        raw = None
        if body is not None:
            raw = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        try:
            response = self._transport.request(method, url + path, body=raw, headers=headers, timeout=timeout)
        except TransportError as error:
            if error.sent and writing:  # the change reached the Mac: it may or may not have happened
                raise CalendarWriteFailure("mac_no_answer", "the Mac did not answer in time",
                                           written="unknown") from None
            raise CalendarWriteFailure("mac_unreachable", _FRIENDLY["mac_unreachable"], retryable=True) from None
        except HeptabaseError:
            raise CalendarWriteFailure("mac_response_too_large", "the Mac sent too much at once",
                                       written="unknown" if writing else False) from None
        except ValueError:
            raise CalendarWriteFailure("mac_url_invalid", "the Mac bridge address is invalid") from None
        return self._result(response)

    def _result(self, response: HttpResponse) -> dict[str, object]:
        value = response.json()
        if 200 <= response.status < 300:
            note = getattr(self._store, "note_reachable", None)
            if callable(note):
                note()
            return value
        if response.status == 401:
            raise CalendarWriteFailure("mac_unauthorized", _FRIENDLY["mac_unauthorized"], status=401)
        error = value.get("error") if isinstance(value.get("error"), dict) else {}
        code = str(error.get("code") or f"http_{response.status}")[:64]
        if response.status == 404 and code == "not_found":
            raise CalendarWriteFailure("bridge_outdated", _FRIENDLY["bridge_outdated"], status=404)
        reason = _FRIENDLY.get(code) or _reason(error.get("message"))
        written = error.get("written") if error.get("written") in (True, False, "unknown") else False
        fix = error.get("fix") if isinstance(error.get("fix"), str) else None
        raise CalendarWriteFailure(code, reason, status=response.status, retryable=bool(error.get("retryable")),
                                   written=written, fix=fix[:200] if fix else None)


def _reason(message: object) -> str:
    """The bridge's own sentence (it never carries event text), without its final period."""
    return str(message or "the Mac could not change Google Calendar").strip().rstrip(".")[:300]
