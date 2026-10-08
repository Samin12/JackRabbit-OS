"""Mac bridge transport: the journal is written through the Heptabase CLI on the user's Mac.

``companion/mac-bridge/samrabbit_bridge.py`` runs on the Mac next to the Heptabase
desktop app and exposes ``/health``, ``journal/append`` and ``journal/read`` on the
LAN behind a bearer token. No Heptabase OAuth grant is involved.

* The bridge URL must be http(s) to a loopback or private-LAN address; it is stored
  non-secretly in ``provider_settings`` (no migration needed).
* The token is sealed with ``ConnectionCredentialEnvelopes`` under a fixed UUID and
  bound to the URL it was configured for, so a rewritten URL never receives it.
* Bridge errors say whether an append may have reached the journal (``written``),
  which maps onto the outbox's retry vs. read-back-first (uncertain) handling.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
import ipaddress
import json
import re
import threading
import time
from urllib.parse import quote, urlsplit
from uuid import NAMESPACE_URL, uuid5

from sam_runtime.core.logging import runtime_logger
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes, CredentialUnavailable
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository
from sam_runtime.storage.database import RuntimeDatabase

from .client import HttpResponse, HttpTransport
from .errors import AuthorizationError, HeptabaseError, ToolFailure, TransportError
from .format import cli_markdown

BRIDGE_CONNECTION_ID = str(uuid5(NAMESPACE_URL, "sam:heptabase-mac-bridge"))
BRIDGE_SERVICE = "samrabbit-bridge"
DEFAULT_BRIDGE_PORT = 3780
# The bridge gives the CLI 30 s; the R1 waits a little longer so the bridge's own answer wins.
APPEND_TIMEOUT_SECONDS = 40.0
READ_TIMEOUT_SECONDS = 40.0
HEALTH_TIMEOUT_SECONDS = 15.0
MIN_TOKEN_CHARS = 16
MAX_TOKEN_CHARS = 512
MAX_READ_DAYS = 31
_URL_KEY = "heptabase.bridge.url"
_CONFIGURED_AT_KEY = "heptabase.bridge.configured_at"
_STATUS_KEY = "heptabase.bridge.status"
_TOKEN = re.compile(r"^[\x21-\x7e]+$")
_CODE = re.compile(r"[^a-z0-9_]")
_PRIVATE_V4 = tuple(ipaddress.ip_network(net) for net in
                    ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"))
_PRIVATE_V6 = tuple(ipaddress.ip_network(net) for net in ("::1/128", "fc00::/7", "fe80::/10"))
DRY_RUN_ERROR = "bridge_dry_run"
_MESSAGES = {
    DRY_RUN_ERROR: "This Mac bridge is a test copy (dry run) that never writes to Heptabase. Entries wait on the "
                   "R1 until the installed bridge is connected.",
    "heptabase_app_unavailable": "The Heptabase app is not running on the Mac. Entries wait on the R1 until it is.",
    "heptabase_cli_missing": "The Heptabase CLI is not installed on the Mac.",
    "heptabase_cli_timeout": "Heptabase on the Mac did not answer in time.",
    "heptabase_busy": "Heptabase on the Mac asked to retry later.",
    "bridge_busy": "The Mac bridge is busy; retrying.",
}
_LOG = runtime_logger()


def is_private_host(host: str) -> bool:
    """Loopback or private-LAN IP literal (or ``localhost``). Public hosts and names never qualify."""
    value = (host or "").strip().lower().strip("[]")
    if value == "localhost":
        return True
    try:
        ip = ipaddress.ip_address(value.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return any(ip in network for network in (_PRIVATE_V4 if ip.version == 4 else _PRIVATE_V6))


def normalize_bridge_url(value: object) -> str:
    """``http(s)://<private host>:<port>`` with no path, query or credentials.

    A bare ``192.168.1.183`` becomes ``http://192.168.1.183:3780``.
    """
    example = "Use the Mac bridge address, for example http://192.168.1.183:3780."
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > 300:
        raise AuthorizationError("invalid_bridge_url", example)
    text = value.strip()
    if "://" not in text:
        text = "http://" + text
    parsed = urlsplit(text)
    try:
        port = parsed.port
    except ValueError:
        raise AuthorizationError("invalid_bridge_url", example) from None
    if parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password \
            or parsed.query or parsed.fragment or parsed.path not in ("", "/"):
        raise AuthorizationError("invalid_bridge_url", example)
    if not is_private_host(parsed.hostname):
        raise AuthorizationError("bridge_url_not_local",
                                 "The Mac bridge must be on your local network (a 192.168.x.x, 10.x.x.x or "
                                 "172.16-31.x.x address).")
    host = parsed.hostname
    if ":" in host:
        host = f"[{host}]"
    return f"{parsed.scheme}://{host}:{port or DEFAULT_BRIDGE_PORT}"


def valid_bridge_token(value: object) -> str:
    token = value.strip() if isinstance(value, str) else ""
    if not (MIN_TOKEN_CHARS <= len(token) <= MAX_TOKEN_CHARS) or not _TOKEN.match(token):
        raise AuthorizationError("invalid_bridge_token",
                                 "Paste the token from ~/.config/samrabbit/bridge-token on the Mac.")
    return token


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def _code(value: object) -> str:
    return _CODE.sub("", str(value or "").lower())[:64]


@dataclass(frozen=True, slots=True)
class BridgeConfig:
    url: str
    configured_at: str | None


_UNSET = object()


class BridgeStore:
    """Bridge URL and status (``provider_settings``) plus the sealed token, with an in-process cache."""

    def __init__(self, database: RuntimeDatabase, credentials: ConnectionCredentialRepository,
                 envelopes: ConnectionCredentialEnvelopes) -> None:
        self._database = database
        self._credentials = credentials
        self._envelopes = envelopes
        self._lock = threading.Lock()
        self._config: object = _UNSET
        self._token: tuple[str, str] | None = None
        self._listeners: tuple[Callable[[], None], ...] = ()
        self._generation = 0  # bumped by every save/clear: "the pairing changed"

    @property
    def generation(self) -> int:
        with self._lock:
            return self._generation

    def add_listener(self, callback: Callable[[], None]) -> None:
        """Called (on the caller's thread, errors swallowed) when the bridge is (re)configured or
        answers a request: lets other bridge users (conversation sync) retry at once."""
        with self._lock:
            self._listeners = (*self._listeners, callback)

    def note_reachable(self) -> None:
        with self._lock:
            listeners = self._listeners
        for callback in listeners:
            try:
                callback()
            except Exception:
                _LOG.warning("heptabase.bridge.listener_failed")

    def config(self) -> BridgeConfig | None:
        with self._lock:
            if self._config is not _UNSET:
                return self._config  # type: ignore[return-value]
        values = self._values()
        config = None
        if values.get(_URL_KEY):
            try:
                config = BridgeConfig(normalize_bridge_url(values[_URL_KEY]), values.get(_CONFIGURED_AT_KEY))
            except AuthorizationError:
                _LOG.warning("heptabase.bridge.url_invalid")
        with self._lock:
            self._config = config
        return config

    def token(self, url: str) -> str | None:
        with self._lock:
            if self._token is not None and self._token[0] == url:
                return self._token[1]
        envelope = self._credentials.get_envelope(BRIDGE_CONNECTION_ID)
        if envelope is None:
            return None
        try:
            record = json.loads(self._envelopes.open(BRIDGE_CONNECTION_ID, envelope))
        except (CredentialUnavailable, ValueError):
            _LOG.warning("heptabase.bridge.token_unreadable")
            return None
        if not isinstance(record, dict) or record.get("url") != url or not isinstance(record.get("token"), str):
            _LOG.warning("heptabase.bridge.token_mismatch")
            return None
        with self._lock:
            self._token = (url, record["token"])
        return record["token"]

    def save(self, url: str, token: str) -> None:
        sealed = self._envelopes.seal(BRIDGE_CONNECTION_ID, json.dumps({"url": url, "token": token},
                                                                       separators=(",", ":")))
        self._credentials.put_envelope(BRIDGE_CONNECTION_ID, sealed)
        configured_at = _now()
        with self._database.connect() as connection:
            for key, value in ((_URL_KEY, url), (_CONFIGURED_AT_KEY, configured_at)):
                connection.execute(
                    "INSERT INTO provider_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                    "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                    "updated_at = excluded.updated_at",
                    (key, value, configured_at),
                )
            connection.execute("DELETE FROM provider_settings WHERE setting_key = ?", (_STATUS_KEY,))
            connection.commit()
        with self._lock:
            self._config = BridgeConfig(url, configured_at)
            self._token = (url, token)
            self._generation += 1
        self.note_reachable()

    def clear(self) -> None:
        self._credentials.delete(BRIDGE_CONNECTION_ID)
        with self._database.connect() as connection:
            connection.execute("DELETE FROM provider_settings WHERE setting_key IN (?, ?, ?)",
                               (_URL_KEY, _CONFIGURED_AT_KEY, _STATUS_KEY))
            connection.commit()
        with self._lock:
            self._config = None
            self._token = None
            self._generation += 1

    def status(self) -> dict[str, object]:
        raw = self._values().get(_STATUS_KEY)
        try:
            value = json.loads(raw) if raw else {}
        except ValueError:
            value = {}
        return value if isinstance(value, dict) else {}

    def record_status(self, *, force: bool = False, **changes: object) -> None:
        """Merge non-secret reachability facts (codes and times only, never content).

        Routine calls skip the write when nothing changed; an explicit check (``force``)
        always stamps ``checkedAt``."""
        if self.config() is None:
            return
        if "lastOkAt" in changes or (changes.get("reachable") is True and changes.get("lastError", "") is None):
            self.note_reachable()  # a real success only (a 401 or 5xx also says reachable=True)
        current = self.status()
        merged = {**current, **changes, "checkedAt": _now()}
        if not force and "lastOkAt" not in changes and \
                {k: v for k, v in merged.items() if k != "checkedAt"} == \
                {k: v for k, v in current.items() if k != "checkedAt"}:
            return  # unchanged: skip the write
        with self._database.connect() as connection:
            connection.execute(
                "INSERT INTO provider_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                "updated_at = excluded.updated_at",
                (_STATUS_KEY, json.dumps(merged, separators=(",", ":")), merged["checkedAt"]),
            )
            connection.commit()

    def _values(self) -> dict[str, str]:
        with self._database.connect() as connection:
            rows = connection.execute(
                "SELECT setting_key, setting_value FROM provider_settings WHERE setting_key IN (?, ?, ?)",
                (_URL_KEY, _CONFIGURED_AT_KEY, _STATUS_KEY),
            ).fetchall()
        return {str(row[0]): str(row[1]) for row in rows}


class MacBridgeClient:
    """Same surface the outbox uses on the MCP client: ``append_to_journal`` and
    ``read_journal_range`` (plain text), plus ``health`` and ``read_day``."""

    def __init__(self, store: BridgeStore, transport: HttpTransport | None = None, *,
                 clock: Callable[[], float] = time.time) -> None:
        self._store = store
        self._transport = transport or HttpTransport(timeout=APPEND_TIMEOUT_SECONDS, allow_http=is_private_host)
        self._clock = clock

    # ------------------------------------------------------------------ calls

    def health(self, url: str, token: str) -> dict[str, object]:
        """Probe a bridge with explicit credentials (configure / check). Never records status."""
        response = self._request("GET", url, "/health", token, timeout=HEALTH_TIMEOUT_SECONDS, writing=False,
                                 record=False)
        if response.status in (401, 403):
            raise HeptabaseError("bridge_unauthorized", "The Mac bridge rejected the token.", status=401)
        value = response.json()
        if response.status != 200 or value.get("service") != BRIDGE_SERVICE or value.get("ok") is not True:
            raise HeptabaseError("not_a_bridge", "That address answered, but it is not the SamRabbit Mac bridge.",
                                 status=response.status)
        return value

    def append_to_journal(self, journal_date: str, content: str) -> dict[str, object]:
        url, token = self._credentials()
        body = json.dumps({"date": journal_date, "content": cli_markdown(content)}, separators=(",", ":"),
                          ensure_ascii=False).encode("utf-8")
        response = self._request("POST", url, "/v1/heptabase/journal/append", token, body=body,
                                 timeout=APPEND_TIMEOUT_SECONDS, writing=True)
        value = self._result(response, writing=True)
        self._refuse_dry_run(value)
        self._store.record_status(reachable=True, appReachable=True, lastError=None, lastOkAt=_now())
        return value

    def read_day(self, journal_date: str) -> dict[str, object]:
        url, token = self._credentials()
        response = self._request("GET", url, "/v1/heptabase/journal/read?date=" + quote(journal_date, safe=""), token,
                                 timeout=READ_TIMEOUT_SECONDS, writing=False)
        value = self._result(response, writing=False)
        self._refuse_dry_run(value)
        if not isinstance(value.get("text"), str):
            raise HeptabaseError("bridge_invalid_response", "The Mac bridge sent an unreadable journal.")
        self._store.record_status(reachable=True, appReachable=True, lastError=None)
        return {"date": journal_date, "title": value.get("title"), "text": value["text"]}

    def read_journal_range(self, start: str, end: str) -> str:
        first, last = date.fromisoformat(start), date.fromisoformat(end)
        if last < first or (last - first).days >= MAX_READ_DAYS:
            raise ValueError("Read at most a month of journal at once.")
        days = []
        current = first
        while current <= last:
            days.append(str(self.read_day(current.isoformat())["text"]))
            current += timedelta(days=1)
        return "\n".join(day for day in days if day)

    # ------------------------------------------------------------------ plumbing

    def _refuse_dry_run(self, value: dict[str, object]) -> None:
        """A dry-run bridge (a test or dev copy) keeps journal text in its own memory: its "written" is not
        Heptabase, so the entry stays queued (never "sent") and its reads are not the user's journal."""
        if value.get("dryRun") is True:
            self._store.record_status(reachable=True, appReachable=False, lastError=DRY_RUN_ERROR)
            raise HeptabaseError(DRY_RUN_ERROR, _MESSAGES[DRY_RUN_ERROR], retryable=True, sent=False)

    def _credentials(self) -> tuple[str, str]:
        config = self._store.config()
        if config is None:  # disconnected while a send was starting: keep the entries queued
            raise HeptabaseError("bridge_not_configured", "The Mac bridge is not connected.", retryable=True)
        token = self._store.token(config.url)
        if token is None:
            self._store.record_status(lastError="bridge_token_unavailable")
            raise HeptabaseError("bridge_token_unavailable",
                                 "The R1 cannot read its Mac bridge token. Connect the Mac bridge again.",
                                 retryable=True)
        return config.url, token

    def _request(self, method: str, url: str, path: str, token: str, *, body: bytes | None = None,
                 timeout: float, writing: bool, record: bool = True) -> HttpResponse:
        if not is_private_host(urlsplit(url).hostname or ""):
            raise HeptabaseError("bridge_url_not_local", "The Mac bridge address is not on the local network.")
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        try:
            return self._transport.request(method, url + path, body=body, headers=headers, timeout=timeout)
        except TransportError as error:
            if error.sent and writing:
                if record:
                    self._store.record_status(lastError="bridge_connection_lost")
                raise HeptabaseError("bridge_connection_lost", "The connection to the Mac bridge dropped mid-write.",
                                     retryable=True, sent=True) from None
            if record:
                self._store.record_status(reachable=False, lastError="bridge_unreachable")
            raise HeptabaseError("bridge_unreachable",
                                 "The Mac bridge is unreachable. Check that the Mac is awake and on the same network.",
                                 retryable=True) from None
        except ValueError:
            raise HeptabaseError("bridge_url_invalid", "The Mac bridge address is invalid.") from None

    def _result(self, response: HttpResponse, *, writing: bool) -> dict[str, object]:
        status = response.status
        value = response.json()
        if 200 <= status < 300:
            if not value:
                raise HeptabaseError("bridge_invalid_response", "The Mac bridge sent an unreadable answer.",
                                     status=status, retryable=True, sent=writing)
            return value
        error = value.get("error") if isinstance(value.get("error"), dict) else {}
        code = _code(error.get("code")) or f"http_{status}"
        if status in (401, 403):
            self._store.record_status(reachable=True, lastError="bridge_unauthorized")
            raise HeptabaseError("bridge_unauthorized",
                                 "The Mac bridge rejected the R1's token. Connect the Mac bridge again.",
                                 status=401, retryable=True)
        if status == 422:
            # Heptabase refused the content itself: the outbox splits the batch, then retries
            # the entry as escaped plain paragraphs before giving up.
            self._store.record_status(reachable=True, appReachable=True, lastError="heptabase_rejected")
            raise ToolFailure("invalidHeptaMarkdown")
        if status >= 500 or status == 429:
            app_down = code in ("heptabase_app_unavailable", "heptabase_cli_missing")
            self._store.record_status(reachable=True, appReachable=False if app_down else None, lastError=code)
            message = _MESSAGES.get(code, f"The Mac bridge could not reach Heptabase ({code}).")
            raise HeptabaseError(code, message, status=status, retryable=True,
                                 sent=writing and error.get("written") is not False)
        self._store.record_status(reachable=True, lastError=code)
        raise HeptabaseError(f"bridge_{code}" if not code.startswith("bridge_") else code,
                             f"The Mac bridge refused the request ({code}).", status=status)
