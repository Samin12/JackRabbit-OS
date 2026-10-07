from __future__ import annotations

import sqlite3


def apply(connection: sqlite3.Connection) -> None:
    """Add T3 Code state, the generic announcements outbox, and widen connection kinds.

    The connections rebuild copies the v030 pattern: end the open migration
    transaction first, because SQLite ignores foreign_keys changes inside a
    transaction, then rebuild the referenced table and restore the index.
    """
    connection.commit()
    connection.executescript(
        """
        PRAGMA foreign_keys = OFF;
        DROP TABLE IF EXISTS connections_v43;
        CREATE TABLE connections_v43 (
            connection_id TEXT PRIMARY KEY,
            kind TEXT NOT NULL CHECK (kind IN ('mail', 'mcp', 'calendar', 't3', 'heptabase')),
            label TEXT NOT NULL,
            enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
            health_state TEXT NOT NULL CHECK (
                health_state IN ('unconfigured', 'ready', 'syncing', 'failed', 'disabled')
            ),
            health_detail TEXT,
            source_owner TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );
        INSERT INTO connections_v43(
            connection_id, kind, label, enabled, health_state, health_detail,
            source_owner, created_at, updated_at
        )
        SELECT connection_id, kind, label, enabled, health_state, health_detail,
               source_owner, created_at, updated_at
        FROM connections;
        DROP TABLE connections;
        ALTER TABLE connections_v43 RENAME TO connections;
        CREATE INDEX connections_kind_idx ON connections(kind, label);
        PRAGMA foreign_key_check;
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS t3_connections (
            connection_id TEXT PRIMARY KEY
                REFERENCES connections(connection_id) ON DELETE CASCADE,
            server_url TEXT NOT NULL,
            environment_label TEXT,
            scopes TEXT NOT NULL DEFAULT '',
            expires_at TEXT,
            health_state TEXT NOT NULL CHECK (health_state IN ('ready', 'failed', 'reauth')),
            health_detail TEXT,
            last_sync_at TEXT,
            baseline_pending INTEGER NOT NULL DEFAULT 1 CHECK (baseline_pending IN (0, 1)),
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS t3_thread_seen (
            thread_id TEXT PRIMARY KEY,
            seen_marker TEXT NOT NULL,
            seen_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS announcements (
            announcement_id INTEGER PRIMARY KEY AUTOINCREMENT,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            text TEXT NOT NULL,
            payload_json TEXT NOT NULL,
            created_at TEXT NOT NULL,
            voice_acked_at TEXT,
            notification_acked_at TEXT,
            seen_acked_at TEXT
        );
        CREATE INDEX IF NOT EXISTS announcements_created_idx ON announcements(created_at);
        """
    )
