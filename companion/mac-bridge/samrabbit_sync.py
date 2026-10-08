"""SamRabbit conversation sync on the Mac: every R1 conversation, stored and served to the desktop app.

The R1's runtime posts its conversation events here (hop 2) and the SamRabbit desktop app reads
them back (hop 3). Storage: ``~/Library/Application Support/SamRabbit/sync/conversations.db``
(SQLite, WAL) plus content-addressed blobs in ``sync/blobs/`` (folders 0700, files 0600).

Routes (all under ``/v1/sync/``):

* From the R1 (bearer token + private-LAN peer, like ``/v1/mac/*``; never relaxed by
  ``--allow-any-client``):
  ``POST /v1/sync/events`` ``{device, events:[...]}`` -> ``{accepted, duplicates, rejected, cursor}``
  (body <= 256 KB; ``INSERT OR IGNORE`` by event id) and ``PUT /v1/sync/blobs/<sha256>`` raw bytes
  (<= 1 MB, hash verified, idempotent) -> ``{blobId, bytes, created}``.
* For the desktop app (loopback peer only, a loopback ``Host``/``Origin``, and the desktop token
  from ``~/.config/samrabbit/desktop-token`` sent as header ``X-SamRabbit-Desktop`` or cookie
  ``sr_desktop``): ``GET /v1/sync/conversations?limit=&before=&q=``,
  ``GET /v1/sync/conversations/<id>``, ``GET /v1/sync/conversations/<id>/events?after=&limit=``,
  ``GET /v1/sync/stream?after=`` (SSE ``event: sync``, ``id:`` = cursor, heartbeat comments),
  ``GET /v1/sync/blobs/<sha256>`` and ``GET /v1/sync/status``.

Other bridge modules (generative UI) add to the same timeline with ``record_local_event`` and
store images with ``put_blob``. Conversation text, prompts, tokens and image bytes are never
logged. Stdlib only, Python 3.9+.
"""

from __future__ import annotations

import base64
import binascii
import hashlib
import hmac
import ipaddress
import json
import logging
import os
import re
import select
import socket
import sqlite3
import stat
import threading
import time
import uuid
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, unquote, urlsplit

SERVICE = "samrabbit-bridge"
SYNC_VERSION = 1
DEFAULT_SYNC_DIR = "~/Library/Application Support/SamRabbit/sync"
DEFAULT_DESKTOP_TOKEN_FILE = "~/.config/samrabbit/desktop-token"
DESKTOP_HEADER = "X-SamRabbit-Desktop"
DESKTOP_COOKIE = "sr_desktop"
PREFIX = "/v1/sync/"
MAX_EVENTS_BODY_BYTES = 256 * 1024
MAX_BLOB_BODY_BYTES = 1024 * 1024
MAX_LOCAL_BLOB_BYTES = 16 * 1024 * 1024
MAX_DRAIN_BYTES = 4 * 1024 * 1024
MAX_EVENT_BYTES = 64 * 1024
MAX_EVENTS_PER_POST = 1000
MAX_PAGE = 500
DEFAULT_PAGE = 200
DEFAULT_CONVERSATIONS = 50
MAX_CONVERSATIONS = 200
MAX_QUERY_CHARS = 100
HEARTBEAT_SECONDS = 15.0
PEER_CHECK_SECONDS = 1.0  # how often an idle stream looks for a closed desktop connection
MAX_STREAMS = 8
LIVE_STALE_MS = 20 * 60 * 1000
TITLE_CHARS = 80
PREVIEW_CHARS = 160
MIN_TOKEN_CHARS = 16
DELTA = "message.assistant.delta"
DRAFT_ENDS = ("message.assistant.done", "message.assistant.interrupted")
MESSAGE_TYPES = ("message.user",) + DRAFT_ENDS
SEARCHED_TYPES = ("message.user", "message.assistant.done", "message.assistant.interrupted", "session.finalized",
                  "ui.generated", "host.note")

_CONVERSATION = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
_EVENT_ID = re.compile(r"^[\x21-\x7e]{1,200}$")
_EVENT_TYPE = re.compile(r"^[a-z][a-z0-9_]{0,31}(?:\.[a-z0-9_]{1,32}){0,4}$")
_SESSION = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
_HEX = re.compile(r"^[0-9a-f]{64}$")
_MIME = re.compile(r"^[a-z]+/[a-z0-9.+-]{1,64}$")
_DEVICE = re.compile(r"^[a-z0-9_-]{1,16}$")
_IMAGE_MIMES = frozenset({"image/jpeg", "image/png", "image/webp", "image/gif"})
_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1"})
_PRIVATE_V4 = tuple(ipaddress.ip_network(net) for net in
                    ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"))
_PRIVATE_V6 = tuple(ipaddress.ip_network(net) for net in ("::1/128", "fc00::/7", "fe80::/10"))
_LOG = logging.getLogger(SERVICE)

_DEFAULT_LOCK = threading.Lock()
_DEFAULT: Optional["SyncService"] = None
_FALLBACK_DESKTOP: Optional["DesktopToken"] = None


# --------------------------------------------------------------------------- module API for other bridge modules


def record_local_event(conversation_id: str, event: Dict[str, Any]) -> Optional[str]:
    """Add an event made on the Mac (``origin: "mac"``) to a conversation's timeline.

    ``event`` needs ``type`` and may carry any fields; ``id`` defaults to ``mac:<uuid>`` and
    ``at`` to now (epoch ms). Returns the event id, or None when sync is not running or the
    event is invalid. Never raises."""
    service = _default()
    if service is None:
        return None
    try:
        return service.store.record_local(conversation_id, event)
    except Exception:  # noqa: BLE001 - callers must never fail because of sync
        _LOG.warning("sync: local event not recorded")
        return None


def put_blob(data: bytes, mime: str, conversation_id: Optional[str] = None) -> Optional[str]:
    """Store bytes in the sync blob store and return ``"sha256:<hex>"`` (None when sync is not
    running or the input is invalid). Idempotent. Never raises."""
    service = _default()
    if service is None:
        return None
    try:
        return service.store.put_blob(data, mime, conversation_id, limit=MAX_LOCAL_BLOB_BYTES)[0]
    except Exception:  # noqa: BLE001
        _LOG.warning("sync: blob not stored")
        return None


def available() -> bool:
    return _default() is not None


def desktop_request_denied(handler: Any) -> Optional["SyncError"]:
    """The desktop-app auth rule for any bridge route (generative UI documents, the /app UI):
    None when allowed, else the error to answer. Works even when the sync store is off."""
    service = _default()
    if service is not None:
        return service.desktop_denied(handler)
    global _FALLBACK_DESKTOP
    with _DEFAULT_LOCK:
        if _FALLBACK_DESKTOP is None:
            _FALLBACK_DESKTOP = DesktopToken(DEFAULT_DESKTOP_TOKEN_FILE)
        token = _FALLBACK_DESKTOP
    return _desktop_denied(handler, token)


def _default() -> Optional["SyncService"]:
    with _DEFAULT_LOCK:
        return _DEFAULT


# --------------------------------------------------------------------------- errors and helpers


class SyncError(Exception):
    def __init__(self, status: int, code: str, message: str, *, retryable: bool = False) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable

    def payload(self) -> Dict[str, Any]:
        return {"error": {"code": self.code, "message": self.message, "retryable": self.retryable}}


def _now_ms() -> int:
    return int(time.time() * 1000)


_MAX_SAFE_INTEGER = 2 ** 53 - 1


def _int(value: Any) -> Optional[int]:
    """A JSON whole number within +-2^53 (anything else is treated as absent)."""
    if isinstance(value, bool):
        return None
    if isinstance(value, float) and value.is_integer():
        value = int(value)
    if isinstance(value, int) and -_MAX_SAFE_INTEGER <= value <= _MAX_SAFE_INTEGER:
        return value
    return None


def _clip(text: Any, limit: int) -> str:
    value = " ".join(str(text or "").split())
    return value if len(value) <= limit else value[:limit - 1].rstrip() + "…"


def private_peer(address: str) -> bool:
    """Loopback or private-LAN peer (same rule as the bridge's ``client_allowed``)."""
    try:
        ip = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return any(ip in network for network in (_PRIVATE_V4 if ip.version == 4 else _PRIVATE_V6))


def loopback_peer(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return ip.is_loopback


def _loopback_host(value: str) -> bool:
    host = value.strip().lower()
    if host.startswith("["):
        host = host[1:].split("]", 1)[0]
    elif host.count(":") == 1:
        host = host.split(":", 1)[0]
    return host in _LOOPBACK_HOSTS


def _loopback_origin(origin: str) -> bool:
    parsed = urlsplit(origin.strip())
    return parsed.scheme in ("http", "https") and (parsed.hostname or "") in _LOOPBACK_HOSTS


def blob_hex(value: str) -> Optional[str]:
    text = unquote(value or "").strip().lower()
    if text.startswith("sha256:"):
        text = text[7:]
    return text if _HEX.match(text) else None


# --------------------------------------------------------------------------- desktop token


class DesktopToken:
    """The desktop app's token (``desktop-token``, 0600, created by install.sh). Re-read when the
    file changes; a missing file disables the desktop routes until it exists."""

    def __init__(self, path: str) -> None:
        self.path = os.path.expanduser(path)
        self._token: Optional[bytes] = None
        self._stamp: Optional[Tuple[float, int]] = None
        self._lock = threading.Lock()

    def _current(self) -> Optional[bytes]:
        try:
            info = os.stat(self.path)
        except OSError:
            with self._lock:
                self._token, self._stamp = None, None
            return None
        stamp = (info.st_mtime, info.st_size)
        with self._lock:
            if stamp == self._stamp:
                return self._token
        if info.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
            try:
                os.chmod(self.path, 0o600)
                _LOG.warning("desktop token permissions tightened to 0600")
            except OSError:
                pass
        try:
            with open(self.path, "rb") as handle:
                token = handle.read().strip()
        except OSError:
            return None
        if len(token) < MIN_TOKEN_CHARS or any(byte <= 0x20 or byte >= 0x7F for byte in token):
            token = b""
        with self._lock:
            self._token, self._stamp = (token or None), stamp
        return token or None

    def available(self) -> bool:
        return self._current() is not None

    def matches(self, presented: str) -> bool:
        token = self._current()
        if not token or not presented:
            return False
        return hmac.compare_digest(presented.encode("utf-8", errors="replace"), token)


# --------------------------------------------------------------------------- store


_SCHEMA = """
CREATE TABLE IF NOT EXISTS events (
    cursor INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id TEXT NOT NULL UNIQUE,
    conversation_id TEXT NOT NULL,
    session_id TEXT,
    type TEXT NOT NULL,
    at INTEGER NOT NULL,
    seq INTEGER,
    message_id TEXT,
    origin TEXT,
    device TEXT,
    payload TEXT NOT NULL,
    received_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS events_conversation ON events(conversation_id, cursor);
CREATE INDEX IF NOT EXISTS events_message ON events(conversation_id, message_id, type)
    WHERE message_id IS NOT NULL;
CREATE TABLE IF NOT EXISTS conversations (
    conversation_id TEXT PRIMARY KEY,
    title TEXT,
    started_at INTEGER NOT NULL,
    last_at INTEGER NOT NULL,
    ended_at INTEGER,
    live INTEGER NOT NULL DEFAULT 0,
    live_at INTEGER NOT NULL DEFAULT 0,
    message_count INTEGER NOT NULL DEFAULT 0,
    live_messages INTEGER NOT NULL DEFAULT 0,
    preview TEXT,
    preview_at INTEGER NOT NULL DEFAULT 0,
    device TEXT,
    last_cursor INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS conversations_recent ON conversations(last_at DESC);
CREATE TABLE IF NOT EXISTS blobs (
    blob_id TEXT PRIMARY KEY,
    mime TEXT NOT NULL,
    size INTEGER NOT NULL,
    conversation_id TEXT,
    created_at INTEGER NOT NULL
);
"""


class SyncStore:
    """One SQLite connection behind a lock (requests are short); a Condition wakes SSE streams."""

    def __init__(self, root: str) -> None:
        self.root = os.path.expanduser(root)
        self.blob_root = os.path.join(self.root, "blobs")
        for folder in (self.root, self.blob_root):
            os.makedirs(folder, mode=0o700, exist_ok=True)
            os.chmod(folder, 0o700)
        path = os.path.join(self.root, "conversations.db")
        if not os.path.exists(path):
            os.close(os.open(path, os.O_CREAT | os.O_WRONLY, 0o600))
        self._db = sqlite3.connect(path, timeout=10.0, check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA journal_mode = WAL")
        self._db.execute("PRAGMA synchronous = NORMAL")
        self._db.execute("PRAGMA busy_timeout = 10000")
        self._db.executescript(_SCHEMA)
        self._lock = threading.RLock()
        self._changed = threading.Condition()
        self._closed = False
        self._latest = self.latest_cursor()

    def close(self) -> None:
        with self._changed:
            self._closed = True
            self._changed.notify_all()
        with self._lock:
            try:
                self._db.close()
            except sqlite3.Error:
                pass

    @property
    def closed(self) -> bool:
        return self._closed

    # ------------------------------------------------------------------ writes

    def insert_events(self, events: List[Any], device: str) -> Dict[str, int]:
        accepted = duplicates = rejected = 0
        received = _now_ms()
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                for raw in events:
                    try:
                        event = _normalize(raw, received)
                    except ValueError:
                        rejected += 1
                        continue
                    # One event can never fail the batch (or stall the R1's outbox behind it).
                    self._db.execute("SAVEPOINT one_event")
                    try:
                        inserted = self._insert(event, device, received)
                    except (sqlite3.IntegrityError, sqlite3.InterfaceError, OverflowError, TypeError, ValueError):
                        self._db.execute("ROLLBACK TO SAVEPOINT one_event")
                        self._db.execute("RELEASE SAVEPOINT one_event")
                        _LOG.warning("sync: one event refused")
                        rejected += 1
                        continue
                    self._db.execute("RELEASE SAVEPOINT one_event")
                    if inserted:
                        accepted += 1
                    else:
                        duplicates += 1
                self._db.execute("COMMIT")
            except BaseException:
                self._db.execute("ROLLBACK")
                raise
            cursor = self._latest_locked()
        self._notify(cursor)
        return {"accepted": accepted, "duplicates": duplicates, "rejected": rejected, "cursor": cursor}

    def record_local(self, conversation_id: str, event: Dict[str, Any]) -> Optional[str]:
        if not isinstance(event, dict) or not isinstance(conversation_id, str) or \
                not _CONVERSATION.match(conversation_id):
            return None
        value = dict(event)
        value.setdefault("id", "mac:" + uuid.uuid4().hex)
        value["conversationId"] = conversation_id
        value.setdefault("at", _now_ms())
        value.setdefault("origin", "mac")
        try:
            normalized = _normalize(value, _now_ms())
        except ValueError:
            return None
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                self._insert(normalized, "mac", _now_ms())
                self._db.execute("COMMIT")
            except BaseException:
                self._db.execute("ROLLBACK")
                raise
            cursor = self._latest_locked()
        self._notify(cursor)
        return str(normalized["id"])

    def put_blob(self, data: bytes, mime: str, conversation_id: Optional[str] = None, *,
                 limit: int = MAX_BLOB_BODY_BYTES, expected_hex: Optional[str] = None) -> Tuple[str, bool]:
        kind = (mime or "").split(";", 1)[0].strip().lower() or "application/octet-stream"
        if not _MIME.match(kind):
            raise SyncError(400, "invalid_mime", "Content-Type is not a valid media type.")
        if not isinstance(data, (bytes, bytearray)) or not data or len(data) > limit:
            raise SyncError(413 if data and len(data) > limit else 400, "invalid_blob",
                            f"A blob must be 1 to {limit} bytes.")
        digest = hashlib.sha256(data).hexdigest()
        if expected_hex is not None and not hmac.compare_digest(digest, expected_hex):
            raise SyncError(400, "hash_mismatch", "The bytes do not match their sha256.")
        blob_id = "sha256:" + digest
        path = self._blob_path(digest)
        with self._lock:
            row = self._db.execute("SELECT 1 FROM blobs WHERE blob_id = ?", (blob_id,)).fetchone()
            if row is not None and os.path.isfile(path):
                return blob_id, False
        folder = os.path.dirname(path)
        os.makedirs(folder, mode=0o700, exist_ok=True)
        temporary = f"{path}.{uuid.uuid4().hex}.tmp"
        handle = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        try:
            with os.fdopen(handle, "wb") as file:
                file.write(bytes(data))
            os.replace(temporary, path)
        except BaseException:
            try:
                os.unlink(temporary)
            except OSError:
                pass
            raise
        conversation = conversation_id if isinstance(conversation_id, str) and _CONVERSATION.match(conversation_id) \
            else None
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO blobs(blob_id, mime, size, conversation_id, created_at) VALUES (?, ?, ?, ?, ?)",
                (blob_id, kind, len(data), conversation, _now_ms()),
            )
        return blob_id, True

    def _blob_path(self, digest: str) -> str:
        return os.path.join(self.blob_root, digest[:2], digest)

    def _insert(self, event: Dict[str, Any], device: str, received: int) -> bool:
        conversation = str(event["conversationId"])
        kind = str(event["type"])
        message_id = event.get("messageId") if isinstance(event.get("messageId"), str) else None
        at = int(event["at"])
        seq = _int(event.get("seq"))
        if self._db.execute("SELECT 1 FROM events WHERE event_id = ?", (event["id"],)).fetchone() is not None:
            return False
        if message_id is not None and kind == DELTA:
            rows = self._db.execute(
                "SELECT type, seq, at FROM events WHERE conversation_id = ? AND message_id = ?",
                (conversation, message_id),
            ).fetchall()
            for row in rows:
                if row["type"] in DRAFT_ENDS:
                    return False  # the final message is already here
                if row["type"] == DELTA and _newer(row, seq, at):
                    return False  # a newer draft is already here
        if message_id is not None and (kind == DELTA or kind in DRAFT_ENDS):
            # Drafts carry the whole text so far: keep only the newest (none once the message is final).
            self._db.execute("DELETE FROM events WHERE conversation_id = ? AND message_id = ? AND type = ?",
                             (conversation, message_id, DELTA))
        payload = json.dumps(event, separators=(",", ":"), ensure_ascii=False)
        cursor = self._db.execute(
            "INSERT INTO events(event_id, conversation_id, session_id, type, at, seq, message_id, origin, device, "
            "payload, received_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (event["id"], conversation, event.get("sessionId"), kind, at, seq, message_id,
             str(event.get("origin") or "")[:16] or None, device, payload, received),
        ).lastrowid
        self._touch(event, int(cursor), device)
        return True

    def _touch(self, event: Dict[str, Any], cursor: int, device: str) -> None:
        conversation = str(event["conversationId"])
        kind = str(event["type"])
        at = int(event["at"])
        row = self._db.execute("SELECT * FROM conversations WHERE conversation_id = ?", (conversation,)).fetchone()
        if row is None:
            self._db.execute(
                "INSERT INTO conversations(conversation_id, started_at, last_at, device, last_cursor) "
                "VALUES (?, ?, ?, ?, ?)", (conversation, at, at, device, cursor))
            row = self._db.execute("SELECT * FROM conversations WHERE conversation_id = ?", (conversation,)).fetchone()
        values: Dict[str, Any] = {
            "started_at": min(int(row["started_at"]), at),
            "last_at": max(int(row["last_at"]), at),
            "last_cursor": cursor,
        }
        if device != "mac":
            values["device"] = device
        title = row["title"]
        count = int(row["message_count"])
        live_messages = int(row["live_messages"])
        preview, preview_at = row["preview"], int(row["preview_at"])
        session_only = conversation.startswith("s_")

        def set_preview(text: Any, when: int) -> None:
            nonlocal preview, preview_at
            clipped = _clip(text, PREVIEW_CHARS)
            if clipped and when >= preview_at:
                preview, preview_at = clipped, when

        if kind in MESSAGE_TYPES and isinstance(event.get("text"), str) and event["text"].strip():
            count += 1
            live_messages += 1
            if kind == "message.user" and not title:
                title = _clip(event["text"], TITLE_CHARS)
            set_preview(event["text"], at)
        elif kind == "session.finalized" and live_messages == 0:
            # Only the end-of-session transcript arrived (older R1 app or lost live batch).
            raw_entries = event.get("entries")
            entries = [item for item in raw_entries if isinstance(item, dict)] if isinstance(raw_entries, list) else []
            for item in entries:
                text = item.get("text")
                if not isinstance(text, str) or not text.strip():
                    continue
                count += 1
                if item.get("role") == "user" and not title:
                    title = _clip(text, TITLE_CHARS)
                set_preview(text, _int(item.get("at")) or at)
        elif kind == "ui.generated" and isinstance(event.get("title"), str):
            set_preview("▣ " + event["title"], at)
        if title != row["title"]:
            values["title"] = title
        values.update({"message_count": count, "live_messages": live_messages, "preview": preview,
                       "preview_at": preview_at})
        # Live: started (or active) and not ended yet; the view also requires recent activity.
        # A session-only conversation (``s_<voiceSessionId>``) ends with its session.
        live_at = int(row["live_at"])
        if kind == "conversation.ended" or (session_only and kind in ("session.finalized", "session.ended")):
            if at >= live_at:
                values.update({"live": 0, "live_at": at, "ended_at": at})
        elif kind == "conversation.started":
            if at >= live_at:
                values.update({"live": 1, "live_at": at, "ended_at": None})
        elif row["ended_at"] is None and not row["live"]:
            values["live"] = 1
        assignments = ", ".join(f"{key} = ?" for key in values)
        self._db.execute(f"UPDATE conversations SET {assignments} WHERE conversation_id = ?",
                         (*values.values(), conversation))

    def _notify(self, cursor: int) -> None:
        with self._changed:
            if cursor > self._latest:
                self._latest = cursor
            self._changed.notify_all()

    # ------------------------------------------------------------------ reads

    def latest_cursor(self) -> int:
        with self._lock:
            return self._latest_locked()

    def _latest_locked(self) -> int:
        row = self._db.execute("SELECT seq FROM sqlite_sequence WHERE name = 'events'").fetchone()
        return int(row[0]) if row is not None else 0

    def wait_for(self, cursor: int, timeout: float) -> bool:
        """Block until an event newer than ``cursor`` exists (True), the timeout, or close."""
        with self._changed:
            return self._changed.wait_for(lambda: self._closed or self._latest > cursor, timeout) and not self._closed

    def events_after(self, cursor: int, limit: int, conversation_id: Optional[str] = None,
                     upto: Optional[int] = None) -> List[Tuple[int, str]]:
        clauses, params = ["cursor > ?"], [int(cursor)]
        if conversation_id is not None:
            clauses.append("conversation_id = ?")
            params.append(conversation_id)
        if upto is not None:
            clauses.append("cursor <= ?")
            params.append(int(upto))
        with self._lock:
            rows = self._db.execute(
                f"SELECT cursor, payload FROM events WHERE {' AND '.join(clauses)} ORDER BY cursor LIMIT ?",
                (*params, int(limit)),
            ).fetchall()
        return [(int(row[0]), str(row[1])) for row in rows]

    def conversation(self, conversation_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            row = self._db.execute("SELECT * FROM conversations WHERE conversation_id = ?",
                                   (conversation_id,)).fetchone()
        return _conversation_view(row, _now_ms()) if row is not None else None

    def conversations(self, *, limit: int, before: Optional[int], query: str) -> List[Dict[str, Any]]:
        clauses: List[str] = []
        params: List[Any] = []
        if before is not None:
            clauses.append("c.last_at < ?")
            params.append(int(before))
        if query:
            like = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            marks = ",".join("?" for _ in SEARCHED_TYPES)
            clauses.append(
                "(c.title LIKE ? ESCAPE '\\' OR c.preview LIKE ? ESCAPE '\\' OR EXISTS (SELECT 1 FROM events e "
                f"WHERE e.conversation_id = c.conversation_id AND e.type IN ({marks}) AND e.payload LIKE ? ESCAPE '\\'))"
            )
            params.extend([like, like, *SEARCHED_TYPES, like])
        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        with self._lock:
            rows = self._db.execute(
                f"SELECT c.* FROM conversations c {where} ORDER BY c.last_at DESC, c.conversation_id DESC LIMIT ?",
                (*params, int(limit)),
            ).fetchall()
        now = _now_ms()
        return [_conversation_view(row, now) for row in rows]

    def blob(self, digest: str) -> Optional[Tuple[str, str, int]]:
        with self._lock:
            row = self._db.execute("SELECT mime, size FROM blobs WHERE blob_id = ?", ("sha256:" + digest,)).fetchone()
        path = self._blob_path(digest)
        if row is None or not os.path.isfile(path):
            return None
        return path, str(row["mime"]), int(row["size"])

    def counts(self) -> Dict[str, Any]:
        with self._lock:
            conversations = int(self._db.execute("SELECT COUNT(*) FROM conversations").fetchone()[0])
            events = int(self._db.execute("SELECT COUNT(*) FROM events").fetchone()[0])
            blobs = int(self._db.execute("SELECT COUNT(*) FROM blobs").fetchone()[0])
            last = self._db.execute("SELECT MAX(received_at) FROM events").fetchone()[0]
            cursor = self._latest_locked()
        return {"conversations": conversations, "events": events, "blobs": blobs, "cursor": cursor,
                "lastReceivedAt": int(last) if last is not None else None}


def _newer(row: sqlite3.Row, seq: Optional[int], at: int) -> bool:
    if row["seq"] is not None and seq is not None:
        return int(row["seq"]) > seq
    return int(row["at"]) > at


def _normalize(raw: Any, now_ms: int) -> Dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("not an object")
    event_id, kind, conversation = raw.get("id"), raw.get("type"), raw.get("conversationId")
    if not isinstance(event_id, str) or not _EVENT_ID.match(event_id):
        raise ValueError("id")
    if not isinstance(kind, str) or not _EVENT_TYPE.match(kind):
        raise ValueError("type")
    if not isinstance(conversation, str) or not _CONVERSATION.match(conversation):
        raise ValueError("conversationId")
    session = raw.get("sessionId")
    if session is not None and session != "" and (not isinstance(session, str) or not _SESSION.match(session)):
        raise ValueError("sessionId")
    message_id = raw.get("messageId")
    if message_id is not None and (not isinstance(message_id, str) or not 0 < len(message_id) <= 128):
        raise ValueError("messageId")
    event = dict(raw)
    at = _int(raw.get("at"))
    event["at"] = at if at is not None and at > 0 else now_ms
    if "seq" in event and (_int(event["seq"]) is None or _int(event["seq"]) < 0):
        event.pop("seq")
    if not session:
        event.pop("sessionId", None)
    if len(json.dumps(event, separators=(",", ":"), ensure_ascii=False).encode("utf-8")) > MAX_EVENT_BYTES:
        raise ValueError("too large")
    return event


def _conversation_view(row: sqlite3.Row, now: int) -> Dict[str, Any]:
    last_at = int(row["last_at"])
    return {
        "conversationId": row["conversation_id"],
        "title": row["title"],
        "startedAt": int(row["started_at"]),
        "lastAt": last_at,
        "endedAt": row["ended_at"],
        "live": bool(row["live"]) and now - last_at < LIVE_STALE_MS,
        "messageCount": int(row["message_count"]),
        "preview": row["preview"],
        "device": row["device"],
        "cursor": int(row["last_cursor"]),
    }


# --------------------------------------------------------------------------- service and routes


class SyncService:
    """The bridge's sync surface: ``handles(route)`` / ``serve(handler, method, route)``."""

    def __init__(self, sync_dir: str, desktop_token_file: str,
                 peer_allowed: Optional[Callable[[str], bool]] = None) -> None:
        """``peer_allowed`` is the bridge's own LAN rule (``client_allowed``), so ``/v1/sync/*`` and
        ``/v1/mac/*`` always accept the same peers; ``private_peer`` is the stand-alone default."""
        global _DEFAULT
        self.store = SyncStore(sync_dir)
        self.desktop = DesktopToken(desktop_token_file)
        self._peer_allowed = peer_allowed or private_peer
        self._streams = threading.BoundedSemaphore(MAX_STREAMS)
        with _DEFAULT_LOCK:
            _DEFAULT = self

    def close(self) -> None:
        global _DEFAULT
        with _DEFAULT_LOCK:
            if _DEFAULT is self:
                _DEFAULT = None
        self.store.close()

    def health(self) -> Dict[str, Any]:
        return {"available": True, "version": SYNC_VERSION, "desktopToken": self.desktop.available()}

    @staticmethod
    def handles(route: str) -> bool:
        return route.startswith(PREFIX) or route == PREFIX.rstrip("/")

    @staticmethod
    def route_label(route: str) -> str:
        """A log-safe route (ids and hashes replaced)."""
        if route in ("/v1/sync/events", "/v1/sync/conversations", "/v1/sync/stream", "/v1/sync/status"):
            return route
        if route.startswith("/v1/sync/blobs/"):
            return "/v1/sync/blobs/{sha256}"
        if route.startswith("/v1/sync/conversations/"):
            return "/v1/sync/conversations/{id}" + ("/events" if route.endswith("/events") else "")
        return "(other)"

    # ------------------------------------------------------------------ auth

    def desktop_denied(self, handler: Any) -> Optional[SyncError]:
        """None when the request comes from the desktop app on this Mac (loopback peer, loopback
        Host/Origin, valid desktop token); otherwise the error to answer. Other bridge modules
        use ``desktop_request_denied(handler)``, which applies the same rule."""
        return _desktop_denied(handler, self.desktop)

    def device_denied(self, handler: Any) -> Optional[SyncError]:
        if not self._peer_allowed(str(handler.client_address[0])):
            return SyncError(403, "forbidden", "Only devices on the local network may use this bridge.")
        header = handler.headers.get("Authorization", "")
        scheme, _, value = header.partition(" ")
        if scheme.lower() != "bearer" or not value.strip() or not handler.server.token.matches(value.strip()):
            return SyncError(401, "unauthorized", "A valid bridge token is required.")
        return None

    # ------------------------------------------------------------------ dispatch

    def serve(self, handler: Any, method: str, route: str) -> Tuple[int, Optional[str]]:
        """Answer one ``/v1/sync/*`` request. Returns ``(status, error code)`` for the log line."""
        try:
            desktop = method == "GET" and (route.startswith("/v1/sync/conversations") or route == "/v1/sync/stream"
                                           or route.startswith("/v1/sync/blobs/") or route == "/v1/sync/status")
            denied = self.desktop_denied(handler) if desktop else self.device_denied(handler)
            if denied is not None:
                raise denied
            if desktop:
                return self._desktop(handler, route), None
            if route == "/v1/sync/events":
                _require(method, "POST")
                body = _json_body(handler, MAX_EVENTS_BODY_BYTES)
                events = body.get("events")
                if not isinstance(events, list) or len(events) > MAX_EVENTS_PER_POST:
                    raise SyncError(400, "invalid_events", f"events must be a list of at most {MAX_EVENTS_PER_POST}.")
                device = body.get("device") if isinstance(body.get("device"), str) and \
                    _DEVICE.match(body["device"]) else "r1"
                return _send_json(handler, 200, self.store.insert_events(events, device)), None
            if route.startswith("/v1/sync/blobs/"):
                _require(method, "PUT")
                digest = blob_hex(route[len("/v1/sync/blobs/"):])
                if digest is None:
                    raise SyncError(400, "invalid_blob_id", "Use /v1/sync/blobs/<sha256 hex>.")
                data = _raw_body(handler, MAX_BLOB_BODY_BYTES)
                mime = handler.headers.get("Content-Type") or "application/octet-stream"
                blob_id, created = self.store.put_blob(data, mime, handler.headers.get("X-SAM-Conversation"),
                                                       expected_hex=digest)
                return _send_json(handler, 200, {"blobId": blob_id, "bytes": len(data), "created": created}), None
            raise SyncError(404, "not_found", "Not found.")
        except SyncError as error:
            return _send_json(handler, error.status, error.payload()), error.code
        except Exception:  # never leak details (or conversation text) to the caller or the log
            _LOG.exception("sync request failed")
            return _send_json(handler, 500, SyncError(500, "internal_error", "Sync failed unexpectedly.").payload()), \
                "internal_error"

    def _desktop(self, handler: Any, route: str) -> int:
        try:
            query = parse_qs(urlsplit(handler.path).query, max_num_fields=8)
        except ValueError:
            raise SyncError(400, "invalid_query", "Too many query parameters.") from None
        if route == "/v1/sync/conversations":
            limit = _bounded(query, "limit", DEFAULT_CONVERSATIONS, MAX_CONVERSATIONS)
            before = _number(query, "before")
            text = (query.get("q") or [""])[0].strip()[:MAX_QUERY_CHARS]
            cursor = self.store.latest_cursor()
            items = self.store.conversations(limit=limit, before=before, query=text)
            payload: Dict[str, Any] = {"conversations": items, "cursor": cursor}
            if len(items) == limit:
                payload["nextBefore"] = items[-1]["lastAt"]
            return _send_json(handler, 200, payload)
        if route == "/v1/sync/stream":
            return self._stream(handler, query)
        if route == "/v1/sync/status":
            return _send_json(handler, 200, {**self.store.counts(), "version": SYNC_VERSION})
        if route.startswith("/v1/sync/blobs/"):
            digest = blob_hex(route[len("/v1/sync/blobs/"):])
            found = self.store.blob(digest) if digest else None
            if found is None:
                raise SyncError(404, "blob_not_found", "No such blob (yet).", retryable=True)
            path, mime, _size = found
            with open(path, "rb") as handle:
                data = handle.read()
            return _send_bytes(handler, data, mime, digest)
        rest = route[len("/v1/sync/conversations/"):]
        conversation_id, _, tail = rest.partition("/")
        conversation_id = unquote(conversation_id)
        if not _CONVERSATION.match(conversation_id) or tail not in ("", "events"):
            raise SyncError(404, "not_found", "Not found.")
        summary = self.store.conversation(conversation_id)
        if summary is None:
            raise SyncError(404, "conversation_not_found", "No such conversation.")
        if tail == "":
            return _send_json(handler, 200, {"conversation": summary})
        after = _number(query, "after") or 0
        limit = _bounded(query, "limit", DEFAULT_PAGE, MAX_PAGE)
        latest = self.store.latest_cursor()
        if after > latest:  # a cursor from a replaced store: send this conversation again from the start
            after = 0
        rows = self.store.events_after(after, limit + 1, conversation_id, upto=latest)
        more = len(rows) > limit
        rows = rows[:limit]
        cursor = rows[-1][0] if more else max(after, latest)
        body = '{"events":[' + ",".join(_with_cursor(payload, number) for number, payload in rows) + \
            '],"cursor":' + str(cursor) + ',"more":' + ("true" if more else "false") + "}"
        return _send_raw_json(handler, 200, body.encode("utf-8"))

    def _stream(self, handler: Any, query: Dict[str, List[str]]) -> int:
        after = _number(query, "after")
        if after is None:
            last = (handler.headers.get("Last-Event-ID") or "").strip()
            after = int(last) if last.isdigit() and len(last) < 19 else None
        if not self._streams.acquire(blocking=False):
            raise SyncError(503, "too_many_streams", "Too many open sync streams.", retryable=True)
        try:
            latest = self.store.latest_cursor()
            # A cursor above the newest event comes from a store that was replaced (deleted, restored):
            # waiting for it would keep this stream silent until the new store caught up.
            cursor = latest if after is None or after > latest else after
            handler.send_response(200)
            handler.send_header("Content-Type", "text/event-stream; charset=utf-8")
            handler.send_header("Cache-Control", "no-store")
            handler.send_header("X-Accel-Buffering", "no")
            handler.send_header("Connection", "close")
            handler.end_headers()
            _write(handler, f"retry: 2000\n: ready {cursor}\n\n")
            beat = time.monotonic()
            while not self.store.closed:
                upto = self.store.latest_cursor()
                rows = self.store.events_after(cursor, 200, upto=upto)
                if rows:
                    _write(handler, "".join(f"id: {number}\nevent: sync\ndata: {_with_cursor(payload, number)}\n\n"
                                            for number, payload in rows))
                    cursor = rows[-1][0] if len(rows) == 200 else max(rows[-1][0], upto)
                    beat = time.monotonic()
                    continue
                cursor = max(cursor, upto)  # nothing left up to the newest event (superseded drafts are gone)
                wait = max(0.05, min(PEER_CHECK_SECONDS, HEARTBEAT_SECONDS - (time.monotonic() - beat)))
                if self.store.wait_for(cursor, wait):
                    continue
                if self.store.closed or _peer_closed(handler):
                    break  # the desktop app went away: free the stream slot now, not after two heartbeats
                if time.monotonic() - beat >= HEARTBEAT_SECONDS - 0.05:
                    _write(handler, ": heartbeat\n\n")
                    beat = time.monotonic()
        except OSError:
            pass  # the desktop app went away
        finally:
            self._streams.release()
        return 200

    # ------------------------------------------------------------------ Mac screenshots

    def screenshot_taken(self, result: Dict[str, Any], conversation_id: Optional[str]) -> Dict[str, Any]:
        """``/v1/mac/screenshot?conversation=<id>``: keep the JPEG as a sync blob (``blobId``) and file
        an ``image`` event in that conversation (``imageEventId``). Without a conversation (the R1
        only passes one while it syncs images) nothing is kept."""
        if not isinstance(conversation_id, str) or not _CONVERSATION.match(conversation_id):
            return result
        try:
            data = base64.b64decode(str(result.get("base64") or ""), validate=True)
            mime = str(result.get("mime") or "image/jpeg")
            if not data or mime not in _IMAGE_MIMES:
                return result
            blob_id, _created = self.store.put_blob(data, mime, conversation_id, limit=MAX_BLOB_BODY_BYTES)
            value = dict(result)
            value["blobId"] = blob_id
            event: Dict[str, Any] = {"type": "image", "source": "mac_screenshot", "blobId": blob_id, "mime": mime,
                                     "bytes": len(data), "origin": "mac"}
            for key in ("width", "height"):
                if _int(result.get(key)) is not None:
                    event[key] = int(result[key])
            if result.get("app"):
                event["app"] = str(result["app"])[:80]
            event_id = self.store.record_local(conversation_id, event)
            if event_id:
                value["imageEventId"] = event_id
            return value
        except (SyncError, binascii.Error, ValueError, OSError, sqlite3.Error):
            _LOG.warning("sync: screenshot not stored")
            return result


def _desktop_denied(handler: Any, token: DesktopToken) -> Optional[SyncError]:
    if not loopback_peer(str(handler.client_address[0])):
        return SyncError(403, "forbidden", "The desktop API is only available on this Mac.")
    host = handler.headers.get("Host")
    if host and not _loopback_host(host):
        return SyncError(403, "forbidden", "Use http://127.0.0.1 for the desktop API.")
    origin = handler.headers.get("Origin")
    if origin and not _loopback_origin(origin):
        return SyncError(403, "forbidden", "Cross-site requests are not allowed.")
    if not token.available():
        return SyncError(503, "desktop_token_missing",
                         "The desktop token is missing; run companion/mac-bridge/install.sh.", retryable=True)
    presented = (handler.headers.get(DESKTOP_HEADER) or "").strip() or _cookie(handler, DESKTOP_COOKIE)
    if not token.matches(presented):
        return SyncError(401, "unauthorized", "A valid desktop token is required.")
    return None


# --------------------------------------------------------------------------- HTTP plumbing


def _require(method: str, expected: str) -> None:
    if method != expected:
        raise SyncError(405, "method_not_allowed", f"Use {expected}.")


def _cookie(handler: Any, name: str) -> str:
    for item in (handler.headers.get("Cookie") or "").split(";"):
        key, separator, value = item.strip().partition("=")
        if separator and key == name:
            return value.strip()
    return ""


def _length(handler: Any, limit: int) -> int:
    if handler.headers.get("Transfer-Encoding"):
        raise SyncError(411, "length_required", "Send a Content-Length body.")
    try:
        length = int(handler.headers.get("Content-Length", ""))
    except ValueError:
        raise SyncError(411, "length_required", "Send a Content-Length body.") from None
    if length < 0 or length > limit:
        if 0 < length <= MAX_DRAIN_BYTES:  # read it, so the sender sees the 413 instead of a reset
            remaining = length
            while remaining > 0:
                chunk = handler.rfile.read(min(65536, remaining))
                if not chunk:
                    break
                remaining -= len(chunk)
        raise SyncError(413, "body_too_large", f"The body must be at most {limit} bytes.")
    return length


def _raw_body(handler: Any, limit: int) -> bytes:
    length = _length(handler, limit)
    data = handler.rfile.read(length) if length else b""
    if len(data) != length:
        raise SyncError(400, "short_body", "The body ended early.")
    return data


def _json_body(handler: Any, limit: int) -> Dict[str, Any]:
    raw = _raw_body(handler, limit)
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise SyncError(400, "invalid_json", "The body must be a JSON object.") from None
    if not isinstance(value, dict):
        raise SyncError(400, "invalid_json", "The body must be a JSON object.")
    return value


def _number(query: Dict[str, List[str]], key: str) -> Optional[int]:
    values = query.get(key) or []
    value = values[0].strip() if values else ""
    if not value:
        return None
    if not value.isdigit() or len(value) > 18:
        raise SyncError(400, "invalid_query", f"{key} must be a whole number.")
    return int(value)


def _bounded(query: Dict[str, List[str]], key: str, default: int, maximum: int) -> int:
    value = _number(query, key)
    return default if value is None else max(1, min(maximum, value))


def _with_cursor(payload: str, cursor: int) -> str:
    return payload[:-1] + ',"cursor":' + str(cursor) + "}" if payload.endswith("}") else payload


def _write(handler: Any, text: str) -> None:
    handler.wfile.write(text.encode("utf-8"))
    handler.wfile.flush()


def _peer_closed(handler: Any) -> bool:
    """True once the client closed its end (EOF or reset). An SSE client never sends anything after
    its request, so a readable socket that peeks empty means it is gone."""
    connection = getattr(handler, "connection", None)
    if connection is None:
        return False
    try:
        poller = select.poll()  # not select.select: no FD_SETSIZE limit
        poller.register(connection, select.POLLIN)
        if not poller.poll(0):
            return False
        # Readable now, so this peek returns at once (it would otherwise wait out the socket timeout).
        return connection.recv(1, socket.MSG_PEEK) == b""
    except (OSError, ValueError):
        return True


def _send_json(handler: Any, status: int, payload: Dict[str, Any]) -> int:
    return _send_raw_json(handler, status, json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))


def _send_raw_json(handler: Any, status: int, body: bytes) -> int:
    try:
        handler.send_response(status)
        handler.send_header("Content-Type", "application/json; charset=utf-8")
        handler.send_header("Content-Length", str(len(body)))
        handler.send_header("Cache-Control", "no-store")
        if status == 401:
            handler.send_header("WWW-Authenticate", 'Bearer realm="samrabbit-bridge"')
        handler.send_header("Connection", "close")
        handler.end_headers()
        handler.wfile.write(body)
    except OSError:
        pass
    return status


def _send_bytes(handler: Any, data: bytes, mime: str, digest: str) -> int:
    image = mime in _IMAGE_MIMES
    try:
        handler.send_response(200)
        handler.send_header("Content-Type", mime if image else "application/octet-stream")
        handler.send_header("Content-Length", str(len(data)))
        handler.send_header("Cache-Control", "private, max-age=31536000, immutable")
        handler.send_header("ETag", f'"{digest}"')
        handler.send_header("X-Content-Type-Options", "nosniff")
        handler.send_header("Content-Security-Policy", "default-src 'none'; sandbox")
        if not image:
            handler.send_header("Content-Disposition", "attachment")
        handler.send_header("Connection", "close")
        handler.end_headers()
        handler.wfile.write(data)
    except OSError:
        pass
    return 200
