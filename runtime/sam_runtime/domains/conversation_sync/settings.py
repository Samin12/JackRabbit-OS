"""Conversation-sync settings and last delivery status (non-secret, in ``provider_settings``).

Keys: ``conversation_sync.enabled`` (default on), ``.include_assistant``, ``.include_tools``,
``.include_images``, ``.redact_secrets`` (all default on) and ``conversation_sync.status``
(JSON: codes and times only, never content). Only this process writes them, so the values
are cached in memory and the hot paths (ingest, tool observer) never wait on a settings read.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
import json
import threading
import time

from sam_runtime.storage.database import RuntimeDatabase

_PREFIX = "conversation_sync."
_STATUS_KEY = _PREFIX + "status"
# API name -> (setting key suffix, dataclass field)
_FIELDS = {
    "enabled": ("enabled", "enabled"),
    "includeAssistant": ("include_assistant", "include_assistant"),
    "includeTools": ("include_tools", "include_tools"),
    "includeImages": ("include_images", "include_images"),
    "redactSecrets": ("redact_secrets", "redact_secrets"),
}
# Status writes are skipped when only the timestamps moved and the last write is this recent.
_STATUS_REFRESH_SECONDS = 60.0


@dataclass(frozen=True, slots=True)
class SyncSettings:
    enabled: bool = True
    include_assistant: bool = True
    include_tools: bool = True
    include_images: bool = True
    redact_secrets: bool = True

    def view(self) -> dict[str, object]:
        return {name: getattr(self, field) for name, (_key, field) in _FIELDS.items()}


def _iso(seconds: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(seconds))


class SyncSettingsStore:
    def __init__(self, database: RuntimeDatabase, *, clock=time.time) -> None:
        self._database = database
        self._clock = clock
        self._lock = threading.Lock()
        self._settings: SyncSettings | None = None
        self._status: dict[str, object] | None = None
        self._status_written_at = 0.0

    # ------------------------------------------------------------------ settings
    def settings(self) -> SyncSettings:
        with self._lock:
            if self._settings is not None:
                return self._settings
        values = self._read(tuple(_PREFIX + key for key, _field in _FIELDS.values()))
        settings = SyncSettings()
        for _name, (key, field) in _FIELDS.items():
            raw = values.get(_PREFIX + key)
            if raw is not None:
                settings = replace(settings, **{field: raw == "1"})
        with self._lock:
            self._settings = settings
        return settings

    def save(self, changes: dict[str, object]) -> SyncSettings:
        """Partial update with strict booleans; unknown keys are rejected."""
        unknown = sorted(set(changes) - set(_FIELDS))
        if unknown:
            raise ValueError(f"Unknown setting: {unknown[0]}.")
        for name, value in changes.items():
            if not isinstance(value, bool):
                raise ValueError(f"{name} must be true or false.")
        now = _iso(self._clock())
        with self._database.connect() as connection:
            for name, value in changes.items():
                connection.execute(
                    "INSERT INTO provider_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                    "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                    "updated_at = excluded.updated_at",
                    (_PREFIX + _FIELDS[name][0], "1" if value else "0", now),
                )
            connection.commit()
        with self._lock:
            self._settings = None
        return self.settings()

    # ------------------------------------------------------------------ status
    def status(self) -> dict[str, object]:
        with self._lock:
            if self._status is not None:
                return dict(self._status)
        raw = self._read((_STATUS_KEY,)).get(_STATUS_KEY)
        try:
            value = json.loads(raw) if raw else {}
        except ValueError:
            value = {}
        value = value if isinstance(value, dict) else {}
        with self._lock:
            self._status = dict(value)
        return value

    def record_status(self, **changes: object) -> None:
        """Merge codes and times (never content). Writes only when a code changed or the
        stored timestamps are more than a minute old."""
        current = self.status()
        merged = {**current, **changes}
        now = self._clock()
        same_codes = {k: v for k, v in merged.items() if not k.endswith("At")} == \
            {k: v for k, v in current.items() if not k.endswith("At")}
        with self._lock:
            self._status = dict(merged)
            if same_codes and now - self._status_written_at < _STATUS_REFRESH_SECONDS:
                return
            self._status_written_at = now
        with self._database.connect() as connection:
            connection.execute(
                "INSERT INTO provider_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                "updated_at = excluded.updated_at",
                (_STATUS_KEY, json.dumps(merged, separators=(",", ":")), _iso(now)),
            )
            connection.commit()

    def _read(self, keys: tuple[str, ...]) -> dict[str, str]:
        marks = ",".join("?" for _ in keys)
        with self._database.connect() as connection:
            rows = connection.execute(
                f"SELECT setting_key, setting_value FROM provider_settings WHERE setting_key IN ({marks})", keys,
            ).fetchall()
        return {str(row[0]): str(row[1]) for row in rows}
