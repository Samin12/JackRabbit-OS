"""Stdlib HTTP client for one user-configured T3 Code server.

The T3 server normally lives on the user's Mac on the LAN, so this client does
not use ``security.outbound`` (which rejects private hosts). Instead the
endpoint itself is validated: plain http only for private, loopback, link-local
or Tailscale (CGNAT) addresses, https for anything else. The bearer token is
never logged and never included in exception messages.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from http.client import HTTPConnection, HTTPException, HTTPSConnection
import ipaddress
import json
import socket
import ssl
from urllib.parse import quote, urlencode, urlsplit
import zlib


GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
SUBJECT_TOKEN_TYPE = "urn:t3:params:oauth:token-type:environment-bootstrap"
ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
REQUESTED_SCOPES = "orchestration:read orchestration:operate"
CLIENT_LABEL = "Rabbit R1"
DEFAULT_T3_PORT = 3773
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
PAIRING_ALPHABET = frozenset("23456789ABCDEFGHJKLMNPQRSTUVWXYZ")
_CGNAT = ipaddress.ip_network("100.64.0.0/10")


class T3Error(RuntimeError):
    code = "t3_error"


class T3EndpointError(T3Error, ValueError):
    code = "invalid_server_url"


class T3Unavailable(T3Error):
    """Network failure, timeout, oversized or undecodable response."""

    code = "t3_unavailable"


class T3Unauthorized(T3Error):
    """HTTP 401 from T3: the bearer token (or pairing code) was rejected."""

    code = "t3_unauthorized"

    def __init__(self, message: str, reason: str | None = None) -> None:
        super().__init__(message)
        self.reason = reason


class T3RequestError(T3Error):
    code = "t3_request_failed"

    def __init__(self, status: int, error_code: str | None, reason: str | None) -> None:
        super().__init__(f"T3 Code returned HTTP {status}" + (f" ({reason})" if reason else ""))
        self.status = status
        self.error_code = error_code
        self.reason = reason


@dataclass(frozen=True, slots=True)
class T3Endpoint:
    scheme: str
    host: str
    port: int

    @property
    def url(self) -> str:
        host = f"[{self.host}]" if ":" in self.host else self.host
        default = 443 if self.scheme == "https" else 80
        return f"{self.scheme}://{host}" + ("" if self.port == default else f":{self.port}")


Resolver = Callable[[str], tuple[str, ...]]


def _resolve(host: str) -> tuple[str, ...]:
    infos = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    return tuple(sorted({str(info[4][0]) for info in infos}))


def _private_address(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value.split("%", 1)[0])
    except ValueError:
        return False
    if address.is_multicast or address.is_unspecified or address.is_reserved:
        return False
    if address.is_loopback or address.is_link_local or address.is_private:
        return True
    return address.version == 4 and address in _CGNAT


def parse_server_url(value: object, *, resolver: Resolver = _resolve, verify_host: bool = True) -> T3Endpoint:
    """Validate the user's T3 server address. http is allowed only on private hosts.

    ``verify_host=False`` re-parses an address that was already validated when
    it was saved, without resolving its host name again.
    """
    text = str(value or "").strip()
    if not text:
        raise T3EndpointError("Enter the T3 Code server address, for example http://192.168.1.183:3773.")
    if len(text) > 300:
        raise T3EndpointError("The server address is too long.")
    if "://" not in text:
        text = "http://" + text
    parts = urlsplit(text)
    scheme = parts.scheme.lower()
    if scheme not in {"http", "https"}:
        raise T3EndpointError("The server address must start with http:// or https://.")
    if parts.username or parts.password:
        raise T3EndpointError("The server address must not contain a user name or password.")
    if parts.path not in {"", "/"} or parts.query or parts.fragment:
        raise T3EndpointError("Use only the server address, for example http://192.168.1.183:3773.")
    host = (parts.hostname or "").strip().lower()
    if not host or any(ch.isspace() for ch in host):
        raise T3EndpointError("The server address needs a host name or IP address.")
    try:
        port = parts.port
    except ValueError as error:
        raise T3EndpointError("The server port is invalid.") from error
    if port is None:
        port = DEFAULT_T3_PORT if scheme == "http" else 443
    if scheme == "http" and verify_host:
        if host == "localhost":
            pass
        elif _is_ip_literal(host):
            if not _private_address(host):
                raise T3EndpointError("Plain http is allowed only for a private LAN address. Use https for other hosts.")
        else:
            try:
                addresses = resolver(host)
            except (OSError, UnicodeError) as error:
                raise T3EndpointError("That host name could not be resolved. Use the Mac's IP address instead.") from error
            if not addresses or not all(_private_address(item) for item in addresses):
                raise T3EndpointError("Plain http is allowed only for a private LAN host. Use https for other hosts.")
    return T3Endpoint(scheme, host, int(port))


def normalize_pairing_code(value: object) -> tuple[str, str | None]:
    """Return (code, server_url_from_link). Accepts a bare code or a full pair link."""
    text = str(value or "").strip()
    server_url: str | None = None
    if "token=" in text:
        parts = urlsplit(text)
        if parts.scheme in {"http", "https"} and parts.netloc:
            server_url = f"{parts.scheme}://{parts.netloc}"
        fragment = parts.fragment or parts.query
        for item in fragment.split("&"):
            name, _, found = item.partition("=")
            if name == "token":
                text = found
                break
    code = "".join(ch for ch in text if ch.isalnum()).upper()
    if len(code) != 12 or any(ch not in PAIRING_ALPHABET for ch in code):
        raise T3EndpointError("The pairing code is the 12-character code from T3 Code > Settings > Connections.")
    return code, server_url


def _is_ip_literal(host: str) -> bool:
    try:
        ipaddress.ip_address(host.split("%", 1)[0])
    except ValueError:
        return False
    return True


class T3HttpClient:
    def __init__(
        self,
        endpoint: T3Endpoint,
        token: str | None = None,
        *,
        timeout: float = 6.0,
    ) -> None:
        self._endpoint = endpoint
        self._token = token
        self._timeout = timeout

    @property
    def endpoint(self) -> T3Endpoint:
        return self._endpoint

    def with_token(self, token: str) -> "T3HttpClient":
        return T3HttpClient(self._endpoint, token, timeout=self._timeout)

    # -- auth -------------------------------------------------------------
    def descriptor(self) -> dict[str, object]:
        value = self._request("GET", "/.well-known/t3/environment", auth=False, timeout=4.0)
        return value if isinstance(value, dict) else {}

    def exchange_pairing_code(self, code: str, *, label: str = CLIENT_LABEL) -> dict[str, object]:
        form = {
            "grant_type": GRANT_TYPE,
            "subject_token": code,
            "subject_token_type": SUBJECT_TOKEN_TYPE,
            "requested_token_type": ACCESS_TOKEN_TYPE,
            "scope": REQUESTED_SCOPES,
            "client_label": label,
            "client_device_type": "mobile",
            "client_os": "android",
        }
        value = self._request(
            "POST",
            "/oauth/token",
            body=urlencode(form).encode(),
            content_type="application/x-www-form-urlencoded",
            auth=False,
        )
        if not isinstance(value, dict) or not isinstance(value.get("access_token"), str) or not value["access_token"]:
            raise T3Unavailable("T3 Code returned an unexpected pairing response.")
        return value

    def session_state(self) -> dict[str, object]:
        value = self._request("GET", "/api/auth/session", timeout=4.0)
        return value if isinstance(value, dict) else {}

    # -- orchestration ------------------------------------------------------
    def shell(self) -> dict[str, object]:
        value = self._request("GET", "/api/orchestration/shell")
        if not isinstance(value, dict) or not isinstance(value.get("threads"), list):
            raise T3Unavailable("T3 Code returned an unexpected thread list.")
        return value

    def thread(self, thread_id: str, *, turn_limit: int | None = 3) -> dict[str, object]:
        path = f"/api/orchestration/threads/{quote(thread_id, safe='')}"
        if turn_limit is not None:
            path += "?" + urlencode({"turnLimit": int(turn_limit)})
        value = self._request("GET", path, timeout=max(self._timeout, 8.0))
        if not isinstance(value, dict) or not isinstance(value.get("thread"), dict):
            raise T3Unavailable("T3 Code returned an unexpected thread.")
        return value

    def dispatch(self, command: dict[str, object]) -> dict[str, object]:
        body = json.dumps(command, separators=(",", ":"), ensure_ascii=False).encode()
        value = self._request(
            "POST",
            "/api/orchestration/dispatch",
            body=body,
            content_type="application/json",
        )
        return value if isinstance(value, dict) else {}

    # -- transport ----------------------------------------------------------
    def _request(
        self,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        content_type: str | None = None,
        auth: bool = True,
        timeout: float | None = None,
    ) -> object:
        headers = {
            "Accept": "application/json",
            "Accept-Encoding": "gzip",
            "User-Agent": "SamRabbit-R1/1",
        }
        if auth:
            if not self._token:
                raise T3Unauthorized("T3 Code is not paired.", "missing_token")
            headers["Authorization"] = f"Bearer {self._token}"
        if content_type:
            headers["Content-Type"] = content_type
        endpoint = self._endpoint
        wait = timeout if timeout is not None else self._timeout
        if endpoint.scheme == "https":
            connection = HTTPSConnection(endpoint.host, endpoint.port, timeout=wait, context=ssl.create_default_context())
        else:
            connection = HTTPConnection(endpoint.host, endpoint.port, timeout=wait)
        try:
            connection.request(method, path, body=body, headers=headers)
            response = connection.getresponse()
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            status = int(response.status)
            encoding = (response.getheader("Content-Encoding") or "").lower()
        except (OSError, HTTPException, ValueError) as error:
            raise T3Unavailable(f"T3 Code is unreachable ({type(error).__name__}).") from None
        finally:
            connection.close()
        if len(raw) > MAX_RESPONSE_BYTES:
            raise T3Unavailable("T3 Code response is too large.")
        if "gzip" in encoding and raw:
            raw = _gunzip(raw)
        value: object = None
        if raw:
            try:
                value = json.loads(raw)
            except (UnicodeDecodeError, ValueError):
                if status < 400:
                    raise T3Unavailable("T3 Code returned an unreadable response.") from None
                value = None
        if status == 401:
            reason = value.get("reason") if isinstance(value, dict) else None
            raise T3Unauthorized("T3 Code rejected the credential.", str(reason) if reason else None)
        if status >= 400:
            code = value.get("code") if isinstance(value, dict) else None
            reason = value.get("reason") if isinstance(value, dict) else None
            raise T3RequestError(status, str(code) if code else None, str(reason) if reason else None)
        return value


def _gunzip(raw: bytes) -> bytes:
    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
    try:
        out = decoder.decompress(raw, MAX_RESPONSE_BYTES)
    except zlib.error:
        raise T3Unavailable("T3 Code returned a corrupt compressed response.") from None
    if decoder.unconsumed_tail:
        raise T3Unavailable("T3 Code response is too large.")
    return out
