"""Typed failures for the Heptabase journal connector.

Messages are user-facing and never contain tokens, codes, or journal text.
"""

from __future__ import annotations


class HeptabaseError(RuntimeError):
    """Base failure. ``retryable`` drives outbox backoff; ``sent`` tells whether a
    request body may have reached the server (``True`` makes a write *uncertain*)."""

    def __init__(
        self,
        code: str,
        message: str,
        *,
        status: int | None = None,
        retryable: bool = False,
        sent: bool = False,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.status = status
        self.retryable = retryable
        self.sent = sent


class TransportError(HeptabaseError):
    """Network failure. ``sent=False`` means the connection never carried the body."""

    def __init__(self, message: str, *, sent: bool) -> None:
        super().__init__("network", message, retryable=True, sent=sent)


class NotConnected(HeptabaseError):
    def __init__(self) -> None:
        super().__init__("heptabase_not_connected", "Heptabase is not connected.", status=409)


class ReconnectRequired(HeptabaseError):
    """The grant is gone (revoked, expired, or rotated away). Entries stay queued."""

    def __init__(self, message: str = "Reconnect Heptabase to keep journaling.") -> None:
        super().__init__("heptabase_reconnect_required", message, status=409)


class ToolFailure(HeptabaseError):
    """Heptabase answered the tool call with ``status: failed`` (or ``isError``)."""

    def __init__(self, reason: str) -> None:
        super().__init__(reason or "tool_error", f"Heptabase rejected the request ({reason or 'tool_error'}).")
        self.reason = reason or "tool_error"


class AuthorizationError(HeptabaseError):
    """OAuth bootstrap failure surfaced to the management UI / callback page."""

    def __init__(self, code: str, message: str, *, status: int = 400) -> None:
        super().__init__(code, message, status=status)
