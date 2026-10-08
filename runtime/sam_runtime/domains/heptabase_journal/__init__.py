"""Heptabase journal connector: Mac bridge or OAuth grant, durable outbox, and verbatim voice tools."""

from .bridge import BRIDGE_CONNECTION_ID, MacBridgeClient, is_private_host, normalize_bridge_url
from .errors import AuthorizationError, HeptabaseError, NotConnected, ReconnectRequired, ToolFailure
from .oauth import HEPTABASE_CONNECTION_ID, LOOPBACK_REDIRECT_URI, HeptabaseEndpoints
from .service import HeptabaseJournalService
from .tools import JOURNAL_TOOL_SET, register_journal_tools

__all__ = [
    "AuthorizationError",
    "BRIDGE_CONNECTION_ID",
    "HEPTABASE_CONNECTION_ID",
    "HeptabaseEndpoints",
    "HeptabaseError",
    "HeptabaseJournalService",
    "JOURNAL_TOOL_SET",
    "LOOPBACK_REDIRECT_URI",
    "MacBridgeClient",
    "NotConnected",
    "ReconnectRequired",
    "ToolFailure",
    "is_private_host",
    "normalize_bridge_url",
    "register_journal_tools",
]
