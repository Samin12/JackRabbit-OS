from __future__ import annotations

import sqlite3


def apply(connection: sqlite3.Connection) -> None:
    """Heptabase journal connector: durable outbox, user settings, and non-secret grant state.

    Secrets (OAuth tokens) never live here; they are sealed in
    ``connection_credential_envelopes`` under the fixed journal connection UUID.
    """
    connection.executescript(
        """
        CREATE TABLE IF NOT EXISTS journal_outbox (
            entry_id TEXT PRIMARY KEY,
            journal_date TEXT NOT NULL
                CHECK (journal_date GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'),
            kind TEXT NOT NULL CHECK (kind IN ('note', 'session', 'activity')),
            source_ref TEXT,
            voice_session_id TEXT,
            utterance_id INTEGER,
            utterance_key TEXT,
            content TEXT NOT NULL,
            plain_content TEXT NOT NULL,
            fingerprint TEXT NOT NULL,
            candidate_text TEXT,
            use_plain INTEGER NOT NULL DEFAULT 0 CHECK (use_plain IN (0, 1)),
            state TEXT NOT NULL DEFAULT 'pending' CHECK (
                state IN ('held', 'pending', 'sending', 'uncertain', 'sent', 'failed', 'paused')
            ),
            attempts INTEGER NOT NULL DEFAULT 0,
            event_at TEXT NOT NULL,
            next_attempt_at TEXT NOT NULL,
            last_error TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            sent_at TEXT
        );
        CREATE UNIQUE INDEX IF NOT EXISTS journal_outbox_source
            ON journal_outbox(kind, source_ref) WHERE source_ref IS NOT NULL;
        CREATE INDEX IF NOT EXISTS journal_outbox_queue
            ON journal_outbox(state, journal_date, event_at, created_at);
        CREATE INDEX IF NOT EXISTS journal_outbox_session
            ON journal_outbox(voice_session_id, kind);

        CREATE TABLE IF NOT EXISTS heptabase_journal_settings (
            settings_id INTEGER PRIMARY KEY CHECK (settings_id = 1),
            auto_sessions INTEGER NOT NULL DEFAULT 1 CHECK (auto_sessions IN (0, 1)),
            include_actions INTEGER NOT NULL DEFAULT 1 CHECK (include_actions IN (0, 1)),
            include_assistant INTEGER NOT NULL DEFAULT 0 CHECK (include_assistant IN (0, 1)),
            redact_secrets INTEGER NOT NULL DEFAULT 1 CHECK (redact_secrets IN (0, 1)),
            timezone TEXT NOT NULL DEFAULT 'America/New_York',
            updated_at TEXT NOT NULL
        );
        INSERT OR IGNORE INTO heptabase_journal_settings(settings_id, updated_at)
            VALUES (1, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));

        CREATE TABLE IF NOT EXISTS heptabase_connection_state (
            state_id INTEGER PRIMARY KEY CHECK (state_id = 1),
            connection_state TEXT NOT NULL DEFAULT 'disconnected' CHECK (
                connection_state IN ('disconnected', 'connected', 'reconnect_required')
            ),
            scope TEXT,
            access_expires_at INTEGER,
            refresh_available INTEGER NOT NULL DEFAULT 0 CHECK (refresh_available IN (0, 1)),
            write_verified INTEGER NOT NULL DEFAULT 0 CHECK (write_verified IN (0, 1)),
            connected_at TEXT,
            last_sent_at TEXT,
            last_error TEXT,
            updated_at TEXT NOT NULL
        );
        INSERT OR IGNORE INTO heptabase_connection_state(state_id, updated_at)
            VALUES (1, strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));

        CREATE TABLE IF NOT EXISTS heptabase_oauth_clients (
            redirect_uri TEXT PRIMARY KEY,
            client_id TEXT NOT NULL,
            registered_at TEXT NOT NULL
        );
        """
    )
