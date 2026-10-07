"""Non-secret journal settings, grant state, and the DCR client cache (SQLite)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime

from sam_runtime.storage.database import RuntimeDatabase

from .localtime import DEFAULT_TIMEZONE, resolve_zone

CONNECTED = "connected"
DISCONNECTED = "disconnected"
RECONNECT_REQUIRED = "reconnect_required"


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


@dataclass(frozen=True, slots=True)
class JournalSettings:
    auto_sessions: bool = True
    include_actions: bool = True
    include_assistant: bool = False
    redact_secrets: bool = True
    timezone: str = DEFAULT_TIMEZONE

    def view(self) -> dict[str, object]:
        return {
            "autoSessions": self.auto_sessions,
            "includeActions": self.include_actions,
            "includeAssistant": self.include_assistant,
            "redactSecrets": self.redact_secrets,
            "timezone": self.timezone,
        }


@dataclass(frozen=True, slots=True)
class ConnectionState:
    state: str = DISCONNECTED
    scope: str | None = None
    access_expires_at: int | None = None
    refresh_available: bool = False
    write_verified: bool = False
    connected_at: str | None = None
    last_sent_at: str | None = None
    last_error: str | None = None

    @property
    def has_grant(self) -> bool:
        return self.state in (CONNECTED, RECONNECT_REQUIRED)


_SETTING_COLUMNS = {
    "autoSessions": "auto_sessions",
    "includeActions": "include_actions",
    "includeAssistant": "include_assistant",
    "redactSecrets": "redact_secrets",
}


class JournalSettingsRepository:
    def __init__(self, database: RuntimeDatabase) -> None:
        self._database = database

    def settings(self) -> JournalSettings:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT auto_sessions, include_actions, include_assistant, redact_secrets, timezone "
                "FROM heptabase_journal_settings WHERE settings_id = 1"
            ).fetchone()
        if row is None:
            return JournalSettings()
        return JournalSettings(bool(row[0]), bool(row[1]), bool(row[2]), bool(row[3]), str(row[4]))

    def save_settings(self, changes: dict[str, object]) -> JournalSettings:
        """Apply a partial update. Unknown keys are rejected; types are strict."""
        updates: dict[str, object] = {}
        for key, value in changes.items():
            if key in _SETTING_COLUMNS:
                if not isinstance(value, bool):
                    raise ValueError(f"{key} must be true or false.")
                updates[_SETTING_COLUMNS[key]] = int(value)
            elif key == "timezone":
                if not isinstance(value, str) or not value.strip():
                    raise ValueError("timezone must be an IANA name such as America/New_York.")
                resolve_zone(value.strip())  # raises UnknownTimezone (a ValueError)
                updates["timezone"] = value.strip()
            else:
                raise ValueError(f"Unknown setting: {key}.")
        if updates:
            assignments = ", ".join(f"{column} = ?" for column in updates)
            with self._database.connect() as connection:
                connection.execute(
                    f"UPDATE heptabase_journal_settings SET {assignments}, updated_at = ? WHERE settings_id = 1",
                    (*updates.values(), _now()),
                )
                connection.commit()
        return self.settings()

    def state(self) -> ConnectionState:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT connection_state, scope, access_expires_at, refresh_available, write_verified, "
                "connected_at, last_sent_at, last_error FROM heptabase_connection_state WHERE state_id = 1"
            ).fetchone()
        if row is None:
            return ConnectionState()
        return ConnectionState(
            state=str(row[0]),
            scope=row[1],
            access_expires_at=int(row[2]) if row[2] is not None else None,
            refresh_available=bool(row[3]),
            write_verified=bool(row[4]),
            connected_at=row[5],
            last_sent_at=row[6],
            last_error=row[7],
        )

    def set_connected(self, *, scope: str | None, access_expires_at: int, refresh_available: bool,
                      write_verified: bool) -> None:
        self._update(connection_state=CONNECTED, scope=scope, access_expires_at=access_expires_at,
                     refresh_available=int(refresh_available), write_verified=int(write_verified),
                     connected_at=_now(), last_error=None)

    def set_tokens_refreshed(self, *, scope: str | None, access_expires_at: int, refresh_available: bool) -> None:
        self._update(scope=scope, access_expires_at=access_expires_at, refresh_available=int(refresh_available))

    def set_reconnect_required(self, detail: str) -> None:
        self._update(connection_state=RECONNECT_REQUIRED, last_error=detail[:300])

    def set_disconnected(self) -> None:
        self._update(connection_state=DISCONNECTED, scope=None, access_expires_at=None,
                     refresh_available=0, write_verified=0, connected_at=None)

    def record_sent(self) -> None:
        self._update(last_sent_at=_now(), last_error=None)

    def record_error(self, detail: str | None) -> None:
        self._update(last_error=detail[:300] if detail else None)

    def client_for(self, redirect_uri: str) -> str | None:
        with self._database.connect() as connection:
            row = connection.execute(
                "SELECT client_id FROM heptabase_oauth_clients WHERE redirect_uri = ?", (redirect_uri,)
            ).fetchone()
        return str(row[0]) if row is not None else None

    def save_client(self, redirect_uri: str, client_id: str) -> None:
        with self._database.connect() as connection:
            connection.execute(
                "INSERT INTO heptabase_oauth_clients(redirect_uri, client_id, registered_at) VALUES (?, ?, ?) "
                "ON CONFLICT(redirect_uri) DO UPDATE SET client_id = excluded.client_id, "
                "registered_at = excluded.registered_at",
                (redirect_uri, client_id, _now()),
            )
            connection.commit()

    def forget_client(self, redirect_uri: str) -> None:
        with self._database.connect() as connection:
            connection.execute("DELETE FROM heptabase_oauth_clients WHERE redirect_uri = ?", (redirect_uri,))
            connection.commit()

    def _update(self, **columns: object) -> None:
        assignments = ", ".join(f"{column} = ?" for column in columns)
        with self._database.connect() as connection:
            connection.execute(
                f"UPDATE heptabase_connection_state SET {assignments}, updated_at = ? WHERE state_id = 1",
                (*columns.values(), _now()),
            )
            connection.commit()
