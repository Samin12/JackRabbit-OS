from __future__ import annotations

import sqlite3


def apply(connection: sqlite3.Connection) -> None:
    """Conversation sync to the user's Mac: event outbox, image blobs, and the
    voice-session -> conversation map.

    Every event id is globally unique (``<conversationId>:<seq>`` from the R1,
    ``rt:<voiceSessionId>:<toolCallId>`` from the runtime) and the Mac bridge ignores
    ids it already has, so a send that may or may not have arrived is simply sent
    again: there is no "uncertain" state. Times are epoch seconds (REAL) except
    ``event_at``, which is the event's own epoch-millisecond ``at`` (R1 clock).
    """
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS conversation_sync_outbox (
            event_id TEXT PRIMARY KEY,
            conversation_id TEXT NOT NULL,
            voice_session_id TEXT,
            seq INTEGER,
            kind TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            blob_id TEXT,
            coalesce_key TEXT,
            state TEXT NOT NULL DEFAULT 'pending'
                CHECK (state IN ('pending', 'sending', 'sent', 'failed')),
            attempts INTEGER NOT NULL DEFAULT 0,
            event_at INTEGER NOT NULL,
            next_attempt_at REAL NOT NULL DEFAULT 0,
            last_error TEXT,
            created_at REAL NOT NULL,
            sent_at REAL
        );
        CREATE INDEX IF NOT EXISTS conversation_sync_queue
            ON conversation_sync_outbox(state, event_at, created_at);
        CREATE INDEX IF NOT EXISTS conversation_sync_coalesce
            ON conversation_sync_outbox(coalesce_key) WHERE coalesce_key IS NOT NULL;

        CREATE TABLE IF NOT EXISTS conversation_sync_blobs (
            blob_id TEXT PRIMARY KEY CHECK (blob_id GLOB 'sha256:*'),
            conversation_id TEXT,
            mime TEXT NOT NULL,
            size INTEGER NOT NULL,
            data BLOB NOT NULL,
            state TEXT NOT NULL DEFAULT 'pending'
                CHECK (state IN ('pending', 'sending', 'sent', 'failed')),
            attempts INTEGER NOT NULL DEFAULT 0,
            next_attempt_at REAL NOT NULL DEFAULT 0,
            last_error TEXT,
            created_at REAL NOT NULL,
            sent_at REAL
        );
        CREATE INDEX IF NOT EXISTS conversation_sync_blob_queue
            ON conversation_sync_blobs(state, created_at);

        CREATE TABLE IF NOT EXISTS conversation_sync_sessions (
            voice_session_id TEXT PRIMARY KEY,
            conversation_id TEXT NOT NULL,
            created_at REAL NOT NULL
        );
        CREATE INDEX IF NOT EXISTS conversation_sync_sessions_conversation
            ON conversation_sync_sessions(conversation_id);
        """
    )
