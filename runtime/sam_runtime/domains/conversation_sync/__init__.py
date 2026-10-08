"""Conversation sync: every R1 conversation mirrored, live, to the SamRabbit app on the user's Mac."""

from .client import ConversationSyncClient, SyncFailure
from .outbox import ConversationSyncRepository
from .service import ConversationSyncObserver, ConversationSyncService
from .settings import SyncSettings
from .worker import BACKOFF_SECONDS, ConversationSyncWorker

__all__ = [
    "BACKOFF_SECONDS",
    "ConversationSyncClient",
    "ConversationSyncObserver",
    "ConversationSyncRepository",
    "ConversationSyncService",
    "ConversationSyncWorker",
    "SyncFailure",
    "SyncSettings",
]
