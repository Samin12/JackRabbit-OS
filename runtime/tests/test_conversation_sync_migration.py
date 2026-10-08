from __future__ import annotations

from pathlib import Path
import sqlite3
import tempfile
import unittest

from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.storage.migrations import LATEST_VERSION, MIGRATIONS


class ConversationSyncMigrationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "runtime.sqlite3"

    def test_v045_is_the_latest_and_creates_the_sync_tables(self) -> None:
        self.assertEqual(45, LATEST_VERSION)
        self.assertEqual(list(range(5, 46)), [migration.version for migration in MIGRATIONS])
        database = RuntimeDatabase(self.path)
        database.migrate()
        self.assertEqual({"status": "ready", "migrationVersion": 45}, database.health())
        with database.connect() as connection:
            tables = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
            indexes = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type = 'index'")}
        self.assertTrue({"conversation_sync_outbox", "conversation_sync_blobs", "conversation_sync_sessions"} <= tables)
        self.assertTrue({"conversation_sync_queue", "conversation_sync_coalesce", "conversation_sync_blob_queue",
                         "conversation_sync_sessions_conversation"} <= indexes)
        database.migrate()  # idempotent
        self.assertEqual(45, database.health()["migrationVersion"])

    def test_upgrading_a_v044_database_keeps_its_data(self) -> None:
        connection = sqlite3.connect(self.path)
        connection.row_factory = sqlite3.Row
        connection.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)")
        for migration in MIGRATIONS:
            if migration.version > 44:
                break
            migration.apply(connection)
            connection.execute("INSERT INTO schema_migrations VALUES (?, 'x')", (migration.version,))
        connection.execute("INSERT INTO provider_settings(setting_key, setting_value, updated_at) "
                           "VALUES ('heptabase.bridge.url', 'http://192.168.1.10:3780', 'x')")
        connection.commit()
        connection.close()
        database = RuntimeDatabase(self.path)
        database.migrate()
        self.assertEqual(45, database.health()["migrationVersion"])
        with database.connect() as connection:
            self.assertEqual("http://192.168.1.10:3780", connection.execute(
                "SELECT setting_value FROM provider_settings WHERE setting_key = 'heptabase.bridge.url'").fetchone()[0])
            self.assertEqual(0, connection.execute("SELECT COUNT(*) FROM conversation_sync_outbox").fetchone()[0])

    def test_constraints(self) -> None:
        database = RuntimeDatabase(self.path)
        database.migrate()
        with database.connect() as connection:
            connection.execute(
                "INSERT INTO conversation_sync_outbox(event_id, conversation_id, kind, payload_json, event_at, created_at) "
                "VALUES ('c_1:1', 'c_1', 'message.user', '{}', 1, 1)")
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute(
                    "INSERT INTO conversation_sync_outbox(event_id, conversation_id, kind, payload_json, event_at, "
                    "created_at, state) VALUES ('c_1:2', 'c_1', 'x', '{}', 1, 1, 'uncertain')")
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute(
                    "INSERT INTO conversation_sync_outbox(event_id, conversation_id, kind, payload_json, event_at, "
                    "created_at) VALUES ('c_1:1', 'c_1', 'x', '{}', 1, 1)")
            with self.assertRaises(sqlite3.IntegrityError):
                connection.execute(
                    "INSERT INTO conversation_sync_blobs(blob_id, mime, size, data, created_at) "
                    "VALUES ('md5:abc', 'image/jpeg', 1, x'00', 1)")
            row = connection.execute("SELECT state, attempts, next_attempt_at FROM conversation_sync_outbox").fetchone()
            self.assertEqual(("pending", 0, 0), tuple(row))


if __name__ == "__main__":
    unittest.main()
