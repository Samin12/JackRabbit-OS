"""HTTP client for the Mac bridge's sync routes (hop 2: runtime -> Mac).

It reuses the journal's ``BridgeStore`` (one pairing: the URL in ``provider_settings`` and the
token sealed with ``ConnectionCredentialEnvelopes``), re-checks that the bridge is on a private
address before every request, and sends the bearer token. Failures carry a stable code and
say whether they concern the bridge as a whole (back off every send) or this request only.
Nothing here logs content; callers log the codes.
"""

from __future__ import annotations

import json
import sqlite3
from urllib.parse import urlsplit

from sam_runtime.domains.heptabase_journal.bridge import BridgeStore, is_private_host
from sam_runtime.domains.heptabase_journal.client import HttpResponse, HttpTransport
from sam_runtime.domains.heptabase_journal.errors import HeptabaseError, TransportError

EVENTS_TIMEOUT_SECONDS = 20.0
BLOB_TIMEOUT_SECONDS = 30.0


class SyncFailure(Exception):
    """``bridge_wide`` failures (unreachable, unauthorized, busy, outdated) pause all sends;
    the others reject only what was sent (``status`` 4xx)."""

    def __init__(self, code: str, *, bridge_wide: bool, status: int | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.bridge_wide = bridge_wide
        self.status = status


class ConversationSyncClient:
    def __init__(self, store: BridgeStore, transport: HttpTransport | None = None) -> None:
        self._store = store
        self._transport = transport or HttpTransport(timeout=EVENTS_TIMEOUT_SECONDS, allow_http=is_private_host)

    def configured(self) -> bool:
        try:
            return self._store.config() is not None
        except sqlite3.Error:
            return False

    def post_events(self, events_json: list[str]) -> dict[str, object]:
        body = ('{"device":"r1","events":[' + ",".join(events_json) + "]}").encode("utf-8")
        return self._request("POST", "/v1/sync/events", body, "application/json", EVENTS_TIMEOUT_SECONDS)

    def put_blob(self, blob_id: str, mime: str, data: bytes) -> dict[str, object]:
        digest = blob_id.split(":", 1)[1]
        return self._request("PUT", "/v1/sync/blobs/" + digest, data, mime, BLOB_TIMEOUT_SECONDS)

    def _request(self, method: str, path: str, body: bytes, content_type: str, timeout: float) -> dict[str, object]:
        try:
            config = self._store.config()
        except sqlite3.Error:
            config = None
        if config is None:
            raise SyncFailure("bridge_not_configured", bridge_wide=True)
        token = self._store.token(config.url)
        if token is None:
            raise SyncFailure("bridge_token_unavailable", bridge_wide=True)
        if not is_private_host(urlsplit(config.url).hostname or ""):
            raise SyncFailure("bridge_url_not_local", bridge_wide=True)
        headers = {"Authorization": f"Bearer {token}", "Accept": "application/json", "Content-Type": content_type}
        try:
            response = self._transport.request(method, config.url + path, body=body, headers=headers, timeout=timeout)
        except TransportError as error:
            raise SyncFailure("bridge_connection_lost" if error.sent else "bridge_unreachable",
                              bridge_wide=True) from None
        except HeptabaseError:
            raise SyncFailure("bridge_invalid_response", bridge_wide=True) from None
        except ValueError:
            raise SyncFailure("bridge_url_invalid", bridge_wide=True) from None
        return _result(response)


def _result(response: HttpResponse) -> dict[str, object]:
    status = response.status
    if 200 <= status < 300:
        value = response.json()
        if not value:
            raise SyncFailure("bridge_invalid_response", bridge_wide=True, status=status)
        return value
    if status in (401, 403):
        raise SyncFailure("bridge_unauthorized", bridge_wide=True, status=status)
    if status in (404, 405):
        raise SyncFailure("bridge_outdated", bridge_wide=True, status=status)  # a bridge without sync routes
    if status in (408, 429) or status >= 500:
        raise SyncFailure(f"bridge_http_{status}", bridge_wide=True, status=status)
    error = {}
    try:
        error = json.loads(response.body or b"{}").get("error") or {}
    except (ValueError, AttributeError):
        pass
    code = str(error.get("code") or "") if isinstance(error, dict) else ""
    safe = "".join(ch for ch in code.lower() if ch.isalnum() or ch == "_")[:48]
    raise SyncFailure(safe or f"rejected_{status}", bridge_wide=False, status=status)
