"""Stdlib HTTP transport and a stateless MCP client for Heptabase's remote server.

Heptabase's MCP server is stateless Streamable HTTP: a single ``POST tools/call``
with a Bearer token works without ``initialize``. Responses are
``text/event-stream`` with one ``message`` event; the client must accept both
``application/json`` and ``text/event-stream`` (406 otherwise).
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
import http.client
import itertools
import json
import ssl
import threading
from typing import Protocol
from urllib.parse import urlencode, urlsplit

from .errors import HeptabaseError, ReconnectRequired, ToolFailure, TransportError

PROTOCOL_VERSION = "2025-11-25"
USER_AGENT = "SamRabbit-R1-Journal/1.0"
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1"})


def _tls_context() -> ssl.SSLContext:
    context = ssl.create_default_context()
    try:
        if not context.get_ca_certs():
            import certifi  # ships with httpx in the Chaquopy image

            context.load_verify_locations(certifi.where())
    except Exception:
        pass
    return context


@dataclass(frozen=True, slots=True)
class HttpResponse:
    status: int
    headers: dict[str, str]
    body: bytes

    def header(self, name: str) -> str:
        return self.headers.get(name.lower(), "")

    def json(self) -> dict[str, object]:
        try:
            value = json.loads(self.body or b"{}")
        except (UnicodeDecodeError, ValueError):
            return {}
        return value if isinstance(value, dict) else {}


class HttpTransport:
    """One request per connection, redirects never followed.

    Distinguishes failures *before* the body could reach the server (connect,
    DNS, TLS handshake: ``sent=False``) from failures after (``sent=True``), which
    the outbox treats as uncertain because ``append_to_journal`` is not idempotent.
    """

    def __init__(self, *, timeout: float = 20.0, context_factory: Callable[[], ssl.SSLContext] = _tls_context) -> None:
        self._timeout = timeout
        self._context_factory = context_factory
        self._context: ssl.SSLContext | None = None
        self._lock = threading.Lock()

    def request(
        self,
        method: str,
        url: str,
        *,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
        timeout: float | None = None,
    ) -> HttpResponse:
        parsed = urlsplit(url)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            raise ValueError("Heptabase endpoint is invalid.")
        if parsed.scheme == "http" and parsed.hostname not in _LOOPBACK_HOSTS:
            raise ValueError("Heptabase endpoints require HTTPS.")
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        wait = self._timeout if timeout is None else timeout
        if parsed.scheme == "https":
            connection: http.client.HTTPConnection = http.client.HTTPSConnection(
                parsed.hostname, port, timeout=wait, context=self._tls()
            )
        else:
            connection = http.client.HTTPConnection(parsed.hostname, port, timeout=wait)
        path = (parsed.path or "/") + (f"?{parsed.query}" if parsed.query else "")
        try:
            try:
                connection.connect()
            except (OSError, http.client.HTTPException) as error:
                raise TransportError(f"Heptabase is unreachable ({type(error).__name__}).", sent=False) from None
            try:
                connection.request(method, path, body=body, headers={"User-Agent": USER_AGENT, **(headers or {})})
                response = connection.getresponse()
                raw = response.read(MAX_RESPONSE_BYTES + 1)
            except (OSError, http.client.HTTPException) as error:
                raise TransportError(f"Heptabase connection failed ({type(error).__name__}).", sent=True) from None
            if len(raw) > MAX_RESPONSE_BYTES:
                raise HeptabaseError("response_too_large", "Heptabase response exceeded the size limit.", sent=True)
            return HttpResponse(
                response.status,
                {key.lower(): value for key, value in response.getheaders()},
                raw,
            )
        finally:
            connection.close()

    def post_form(self, url: str, fields: dict[str, str], *, timeout: float | None = None) -> HttpResponse:
        return self.request(
            "POST",
            url,
            body=urlencode(fields).encode(),
            headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
            timeout=timeout,
        )

    def post_json(self, url: str, payload: dict[str, object]) -> HttpResponse:
        return self.request(
            "POST",
            url,
            body=json.dumps(payload, separators=(",", ":")).encode(),
            headers={"Content-Type": "application/json", "Accept": "application/json"},
        )

    def _tls(self) -> ssl.SSLContext:
        with self._lock:
            if self._context is None:
                self._context = self._context_factory()
            return self._context


class TokenSource(Protocol):
    def access_token(self, *, rejected: str | None = None) -> str: ...


def parse_mcp_body(response: HttpResponse, request_id: int) -> dict[str, object]:
    content_type = response.header("content-type").split(";", 1)[0].strip().lower()
    text = response.body.decode("utf-8", errors="replace")
    if content_type == "text/event-stream":
        messages = []
        data: list[str] = []
        for line in text.splitlines():
            if line.startswith("data:"):
                data.append(line[5:].lstrip())
            elif not line.strip() and data:
                messages.append("\n".join(data))
                data = []
        if data:
            messages.append("\n".join(data))
        for message in messages:
            try:
                value = json.loads(message)
            except ValueError:
                continue
            if isinstance(value, dict) and value.get("id") == request_id:
                return value
        raise HeptabaseError("mcp_invalid_response", "Heptabase returned no response event.", sent=True)
    try:
        value = json.loads(text)
    except ValueError:
        raise HeptabaseError("mcp_invalid_response", "Heptabase returned an unreadable response.", sent=True) from None
    if not isinstance(value, dict):
        raise HeptabaseError("mcp_invalid_response", "Heptabase returned an invalid response.", sent=True)
    return value


class HeptabaseMcpClient:
    """Bounded surface: append_to_journal, read_journal_range, tools/list. Nothing else."""

    def __init__(self, transport: HttpTransport, endpoint: str, tokens: TokenSource) -> None:
        self._transport = transport
        self._endpoint = endpoint
        self._tokens = tokens
        self._ids = itertools.count(1)
        self._id_lock = threading.Lock()

    def append_to_journal(self, date: str, content: str) -> dict[str, object]:
        result = self.call_tool("append_to_journal", {"date": date, "content": content})
        structured = result.get("structuredContent")
        return structured if isinstance(structured, dict) else {"status": "succeeded"}

    def read_journal_range(self, start: str, end: str) -> str:
        result = self.call_tool("read_journal_range", {"startDate": start, "endDate": end})
        structured = result.get("structuredContent")
        if isinstance(structured, dict) and isinstance(structured.get("content"), str):
            return structured["content"]
        return "\n".join(
            str(item.get("text", ""))
            for item in result.get("content", []) if isinstance(item, dict)
        )

    def list_tool_names(self, *, access_token: str | None = None) -> set[str]:
        result = self.request("tools/list", {}, access_token=access_token)
        tools = result.get("tools") if isinstance(result, dict) else None
        return {str(item.get("name")) for item in tools or [] if isinstance(item, dict)}

    def call_tool(self, name: str, arguments: dict[str, object]) -> dict[str, object]:
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        structured = result.get("structuredContent") if isinstance(result.get("structuredContent"), dict) else {}
        if result.get("isError") or structured.get("status") == "failed":
            raise ToolFailure(str(structured.get("failureReasonCode") or "tool_error"))
        return result

    def request(self, method: str, params: dict[str, object], *, access_token: str | None = None) -> dict[str, object]:
        with self._id_lock:
            request_id = next(self._ids)
        body = json.dumps(
            {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params},
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
        token = access_token or self._token()
        response = self._post(body, token)
        if response.status == 401 and access_token is None:
            token = self._token(rejected=token)
            response = self._post(body, token)
        if response.status == 401:
            raise HeptabaseError("unauthorized", "Heptabase rejected the access token.", status=401, retryable=True)
        if response.status == 403:
            raise ReconnectRequired("Heptabase denied access; reconnect and allow journal access.")
        if response.status == 429:
            raise HeptabaseError("rate_limited", "Heptabase is rate limiting requests.", status=429, retryable=True)
        if response.status >= 500:
            raise HeptabaseError("server_error", f"Heptabase is unavailable (HTTP {response.status}).",
                                 status=response.status, retryable=True, sent=True)
        if response.status >= 400 or response.status < 200:
            raise HeptabaseError("http_error", f"Heptabase rejected the request (HTTP {response.status}).",
                                 status=response.status)
        message = parse_mcp_body(response, request_id)
        if "error" in message:
            error = message.get("error") if isinstance(message.get("error"), dict) else {}
            raise HeptabaseError("mcp_error", f"Heptabase returned an error ({error.get('code', 'unknown')}).")
        result = message.get("result")
        if not isinstance(result, dict):
            raise HeptabaseError("mcp_invalid_response", "Heptabase returned no result.", sent=True)
        return result

    def _token(self, *, rejected: str | None = None) -> str:
        """Token failures happen before the MCP body is sent, so they are never 'uncertain'."""
        try:
            return self._tokens.access_token(rejected=rejected)
        except ReconnectRequired:
            raise
        except HeptabaseError as error:
            if not error.sent:
                raise
            raise HeptabaseError(error.code, str(error), status=error.status, retryable=error.retryable,
                                 sent=False) from None

    def _post(self, body: bytes, token: str) -> HttpResponse:
        return self._transport.request(
            "POST",
            self._endpoint,
            body=body,
            headers={
                "Authorization": f"Bearer {token}",
                "Content-Type": "application/json",
                "Accept": "application/json, text/event-stream",
                "MCP-Protocol-Version": PROTOCOL_VERSION,
            },
        )
