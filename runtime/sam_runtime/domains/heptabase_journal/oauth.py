"""Heptabase OAuth 2.1 for the R1's own grant (public client, PKCE S256).

* RFC 7591 dynamic client registration, cached per redirect URI.
* ``state`` is 256-bit, single use, 10 minutes, in memory only.
* RFC 9207 ``iss`` is required (Heptabase advertises
  ``authorization_response_iss_parameter_supported``).
* RFC 8707 ``resource`` pins tokens to the MCP audience.
* Refresh tokens are assumed to rotate: refreshes are serialized and the new
  refresh token is sealed *before* the new access token is used.

Tokens are sealed with ``ConnectionCredentialEnvelopes`` (Android Keystore) under
one fixed connection UUID; plaintext never touches SQLite or logs.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
import base64
import hashlib
import hmac
import json
import secrets
import threading
import time
from typing import NoReturn
from urllib.parse import urlencode
from uuid import NAMESPACE_URL, uuid5

from sam_runtime.core.logging import runtime_logger
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes, CredentialUnavailable
from sam_runtime.storage.connection_credentials import ConnectionCredentialRepository

from .client import HttpTransport
from .errors import AuthorizationError, HeptabaseError, NotConnected, ReconnectRequired
from .settings import RECONNECT_REQUIRED, JournalSettingsRepository

HEPTABASE_CONNECTION_ID = str(uuid5(NAMESPACE_URL, "sam:heptabase-journal"))
SCOPE = "space:read space:write offline_access"
LOOPBACK_REDIRECT_URI = "http://127.0.0.1:53682/callback"
DEVICE_CALLBACK_PATH = "/v1/heptabase/oauth/callback"
CLIENT_NAME = "SamRabbit R1 Journal"
STATE_TTL_SECONDS = 600
REFRESH_LEEWAY_SECONDS = 300
MAX_PENDING = 8
_LOG = runtime_logger()


@dataclass(frozen=True, slots=True)
class HeptabaseEndpoints:
    issuer: str = "https://api.heptabase.com"
    authorization: str = "https://api.heptabase.com/auth"
    token: str = "https://api.heptabase.com/token"
    registration: str = "https://api.heptabase.com/v1/oauth/register"
    revocation: str = "https://api.heptabase.com/token/revocation"
    mcp: str = "https://api.heptabase.com/mcp"
    resource: str = "https://api.heptabase.com/mcp"


@dataclass(frozen=True, slots=True)
class AuthorizationStart:
    auth_session_id: str
    authorization_url: str
    redirect_uri: str
    expires_at: float


@dataclass(slots=True)
class _Pending:
    auth_session_id: str
    state: str
    verifier: str
    client_id: str
    redirect_uri: str
    expires_at: float


def pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)[:96]
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=").decode()
    return verifier, challenge


class HeptabaseTokenStore:
    """Sealed token record ``{client_id, redirect_uri, access_token, refresh_token,
    expires_at, scope}`` with an in-process cache (avoids a Keystore round trip per call)."""

    def __init__(self, credentials: ConnectionCredentialRepository, envelopes: ConnectionCredentialEnvelopes) -> None:
        self._credentials = credentials
        self._envelopes = envelopes
        self._cache: dict[str, object] | None = None
        self._lock = threading.Lock()

    def load(self) -> dict[str, object] | None:
        with self._lock:
            if self._cache is not None:
                return dict(self._cache)
            envelope = self._credentials.get_envelope(HEPTABASE_CONNECTION_ID)
            if envelope is None:
                return None
            try:
                record = json.loads(self._envelopes.open(HEPTABASE_CONNECTION_ID, envelope))
            except (CredentialUnavailable, ValueError):
                _LOG.warning("heptabase.tokens.unreadable")
                return None
            if not isinstance(record, dict):
                return None
            self._cache = record
            return dict(record)

    def save(self, record: dict[str, object]) -> None:
        plaintext = json.dumps(record, separators=(",", ":"))
        with self._lock:
            # Cache first: a rotated refresh token must not be lost even if sealing fails.
            self._cache = dict(record)
            self._credentials.put_envelope(HEPTABASE_CONNECTION_ID, self._envelopes.seal(HEPTABASE_CONNECTION_ID, plaintext))

    def delete(self) -> None:
        with self._lock:
            self._cache = None
            self._credentials.delete(HEPTABASE_CONNECTION_ID)


class HeptabaseOAuth:
    def __init__(
        self,
        *,
        store: HeptabaseTokenStore,
        settings: JournalSettingsRepository,
        transport: HttpTransport,
        endpoints: HeptabaseEndpoints = HeptabaseEndpoints(),
        clock: Callable[[], float] = time.time,
        on_reconnect_required: Callable[[str], None] | None = None,
    ) -> None:
        self._store = store
        self._settings = settings
        self._transport = transport
        self._endpoints = endpoints
        self._clock = clock
        self._on_reconnect_required = on_reconnect_required
        self._pending: list[_Pending] = []
        self._pending_lock = threading.Lock()
        self._refresh_lock = threading.Lock()

    @property
    def endpoints(self) -> HeptabaseEndpoints:
        return self._endpoints

    # ------------------------------------------------------------------ bootstrap

    def start(self, redirect_uri: str) -> AuthorizationStart:
        client_id = self._client_for(redirect_uri)
        verifier, challenge = pkce_pair()
        state = secrets.token_urlsafe(32)
        now = self._clock()
        pending = _Pending(
            auth_session_id=secrets.token_urlsafe(16),
            state=state,
            verifier=verifier,
            client_id=client_id,
            redirect_uri=redirect_uri,
            expires_at=now + STATE_TTL_SECONDS,
        )
        with self._pending_lock:
            self._pending = [item for item in self._pending if item.expires_at > now][-(MAX_PENDING - 1):]
            self._pending.append(pending)
        query = urlencode({
            "response_type": "code",
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "scope": SCOPE,
            "state": state,
            "code_challenge": challenge,
            "code_challenge_method": "S256",
            "resource": self._endpoints.resource,
            "prompt": "consent",  # oidc-provider only issues offline_access with explicit consent
        })
        return AuthorizationStart(pending.auth_session_id, f"{self._endpoints.authorization}?{query}",
                                  redirect_uri, pending.expires_at)

    def has_pending(self, auth_session_id: str) -> bool:
        now = self._clock()
        with self._pending_lock:
            return any(item.auth_session_id == auth_session_id and item.expires_at > now for item in self._pending)

    def complete(
        self,
        *,
        state: str,
        code: str | None,
        issuer: str | None,
        error: str | None = None,
    ) -> dict[str, object]:
        """Validate the callback, exchange the code, seal and return the token record."""
        pending = self._claim(state)
        if error:
            raise AuthorizationError("authorization_denied", _denied_message(error), status=400)
        if issuer is None or not hmac.compare_digest(str(issuer), self._endpoints.issuer):
            raise AuthorizationError("issuer_mismatch", "The authorization response came from an unexpected issuer.")
        if not code or len(code) > 4096:
            raise AuthorizationError("invalid_callback", "The authorization response had no code.")
        try:
            response = self._transport.post_form(self._endpoints.token, {
                "grant_type": "authorization_code",
                "client_id": pending.client_id,
                "code": code,
                "redirect_uri": pending.redirect_uri,
                "code_verifier": pending.verifier,
                "resource": self._endpoints.resource,
            })
        except HeptabaseError:
            raise AuthorizationError("token_endpoint_unreachable", "Heptabase could not be reached; try again.",
                                     status=502) from None
        payload = response.json()
        if response.status >= 400 or not isinstance(payload.get("access_token"), str):
            if payload.get("error") == "invalid_client":
                self._settings.forget_client(pending.redirect_uri)
            raise AuthorizationError("code_exchange_failed",
                                     f"Heptabase refused the authorization ({_safe_code(payload.get('error'))}).",
                                     status=502)
        record = self._record(payload, client_id=pending.client_id, redirect_uri=pending.redirect_uri)
        with self._refresh_lock:
            self._store.save(record)
        return record

    def import_record(self, *, client_id: str, access_token: str, refresh_token: str | None,
                      expires_at: int, scope: str | None) -> dict[str, object]:
        record: dict[str, object] = {
            "client_id": client_id,
            "redirect_uri": None,
            "access_token": access_token,
            "refresh_token": refresh_token,
            "expires_at": int(expires_at),
            "scope": scope,
        }
        with self._refresh_lock:
            self._store.save(record)
        return record

    # ------------------------------------------------------------------ tokens

    def access_token(self, *, rejected: str | None = None) -> str:
        """Current access token, refreshed when within 300 s of expiry or when the
        server rejected exactly this token. Raises ReconnectRequired/NotConnected."""
        with self._refresh_lock:
            record = self._store.load()
            state = self._settings.state()
            if record is None or not isinstance(record.get("access_token"), str):
                if state.has_grant:  # sealed record lost or unreadable: only a reconnect can fix it
                    self._reconnect_required("Heptabase credentials are unavailable; reconnect to keep journaling.")
                raise NotConnected()
            if state.state == RECONNECT_REQUIRED:
                raise ReconnectRequired()
            current = str(record["access_token"])
            expires_at = int(record.get("expires_at") or 0)
            forced = rejected is not None and hmac.compare_digest(rejected, current)
            if not forced and expires_at > self._clock() + REFRESH_LEEWAY_SECONDS:
                return current
            try:
                record = self._refresh_locked(record)
            except HeptabaseError as error:
                if isinstance(error, ReconnectRequired) or forced:
                    raise
                if expires_at > self._clock() + 30:
                    _LOG.warning("heptabase.refresh.deferred", extra={"code": error.code})
                    return current  # still valid; try refreshing again on the next call
                raise
            return str(record["access_token"])

    def stored_record(self) -> dict[str, object] | None:
        return self._store.load()

    def mark_reconnect_required(self, detail: str) -> None:
        """Record that the server refused the grant (e.g. HTTP 403) without raising."""
        try:
            self._reconnect_required(detail)
        except ReconnectRequired:
            pass

    def revoke(self, record: dict[str, object] | None = None) -> None:
        """Best-effort RFC 7009 revocation of the given (default: stored) grant."""
        record = record if record is not None else self._store.load()
        if not record:
            return
        for token, hint in ((record.get("refresh_token"), "refresh_token"), (record.get("access_token"), "access_token")):
            if not isinstance(token, str) or not token:
                continue
            try:
                self._transport.post_form(self._endpoints.revocation, {
                    "token": token, "token_type_hint": hint, "client_id": str(record.get("client_id", "")),
                })
            except HeptabaseError:
                _LOG.warning("heptabase.revoke.unreachable", extra={"hint": hint})

    def forget(self) -> None:
        with self._refresh_lock:
            self._store.delete()
        with self._pending_lock:
            self._pending.clear()

    def _refresh_locked(self, record: dict[str, object]) -> dict[str, object]:
        refresh_token = record.get("refresh_token")
        if not isinstance(refresh_token, str) or not refresh_token:
            self._reconnect_required("Heptabase did not grant offline access; reconnect to keep journaling.")
        response = self._transport.post_form(self._endpoints.token, {
            "grant_type": "refresh_token",
            "client_id": str(record.get("client_id", "")),
            "refresh_token": str(refresh_token),
            "resource": self._endpoints.resource,
        })
        payload = response.json()
        if response.status in (400, 401) and payload.get("error") in ("invalid_grant", "invalid_client", "unauthorized_client"):
            self._reconnect_required("Heptabase access expired or was revoked; reconnect to keep journaling.")
        if 400 <= response.status < 500 and response.status not in (408, 429):
            # Any other refusal of this grant needs a new one. It must never surface as a
            # per-entry failure: the outbox would mark every queued entry 'failed'.
            self._reconnect_required(
                f"Heptabase refused to renew access (HTTP {response.status}); reconnect to keep journaling.")
        if response.status >= 400 or not isinstance(payload.get("access_token"), str):
            raise HeptabaseError("refresh_failed", f"Heptabase token refresh failed (HTTP {response.status}).",
                                 status=response.status, retryable=True)
        updated = self._record(payload, client_id=str(record.get("client_id", "")),
                               redirect_uri=record.get("redirect_uri"), previous=record)
        self._store.save(updated)  # persist the rotated refresh token before anyone uses the access token
        self._settings.set_tokens_refreshed(scope=_opt_str(updated.get("scope")),
                                            access_expires_at=int(updated["expires_at"]),
                                            refresh_available=bool(updated.get("refresh_token")))
        _LOG.info("heptabase.refresh.ok", extra={"rotated": payload.get("refresh_token") is not None})
        return updated

    def _reconnect_required(self, detail: str) -> NoReturn:
        self._settings.set_reconnect_required(detail)
        _LOG.warning("heptabase.reconnect_required")
        if self._on_reconnect_required is not None:
            try:
                self._on_reconnect_required(detail)
            except Exception:
                _LOG.exception("heptabase.reconnect_hook_failed")
        raise ReconnectRequired(detail)

    def _record(self, payload: dict[str, object], *, client_id: str, redirect_uri: object,
                previous: dict[str, object] | None = None) -> dict[str, object]:
        try:
            expires_in = max(60, int(payload.get("expires_in") or 3600))
        except (TypeError, ValueError):
            expires_in = 3600
        refresh = payload.get("refresh_token")
        if not isinstance(refresh, str) or not refresh:
            refresh = (previous or {}).get("refresh_token")  # keep the old one only if not rotated
        return {
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "access_token": str(payload["access_token"]),
            "refresh_token": refresh,
            "expires_at": int(self._clock()) + expires_in,
            "scope": _opt_str(payload.get("scope")) or (previous or {}).get("scope"),
            "token_type": _opt_str(payload.get("token_type")) or "Bearer",
        }

    def _claim(self, state: str) -> _Pending:
        now = self._clock()
        with self._pending_lock:
            match = None
            for item in self._pending:
                if hmac.compare_digest(item.state.encode(), str(state or "").encode()):
                    match = item
            if match is not None:
                self._pending.remove(match)  # single use, even when the callback fails
        if match is None:
            raise AuthorizationError("invalid_state", "This authorization link is unknown or was already used.")
        if match.expires_at <= now:
            raise AuthorizationError("state_expired", "This authorization link expired; start again.")
        return match

    def _client_for(self, redirect_uri: str) -> str:
        cached = self._settings.client_for(redirect_uri)
        if cached:
            return cached
        try:
            response = self._transport.post_json(self._endpoints.registration, {
                "client_name": CLIENT_NAME,
                "redirect_uris": [redirect_uri],
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
                "scope": SCOPE,
            })
        except HeptabaseError:
            raise AuthorizationError("registration_unreachable", "Heptabase could not be reached; try again.",
                                     status=502) from None
        payload = response.json()
        client_id = payload.get("client_id")
        if response.status >= 400 or not isinstance(client_id, str) or not client_id:
            raise AuthorizationError("registration_failed",
                                     f"Heptabase refused client registration ({_safe_code(payload.get('error'))}).",
                                     status=502)
        self._settings.save_client(redirect_uri, client_id)
        return client_id


def _opt_str(value: object) -> str | None:
    return value if isinstance(value, str) and value else None


def _safe_code(value: object) -> str:
    text = str(value or "unknown")
    return "".join(ch for ch in text if ch.isalnum() or ch in "_-")[:40] or "unknown"


def _denied_message(error: str) -> str:
    code = _safe_code(error)
    if code == "access_denied":
        return "Access was not allowed in Heptabase."
    return f"Heptabase did not authorize the R1 ({code})."
