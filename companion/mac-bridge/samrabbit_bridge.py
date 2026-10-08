#!/usr/bin/env python3
"""SamRabbit Mac bridge: the R1 writes to the Heptabase journal and controls this Mac.

The Heptabase desktop app ships a local CLI (``heptabase``) that talks to the
running app. This small HTTP server lets the R1 on the same network use two of
its commands:

* ``POST /v1/heptabase/journal/append`` runs ``heptabase journal append``.
* ``GET  /v1/heptabase/journal/read?date=YYYY-MM-DD`` runs ``heptabase journal read``
  and returns the day as plain text lines.
* ``GET  /health`` reports the CLI version and whether the app answers, plus the
  Mac-control capabilities.

and, for the R1's voice orchestrator, Mac control through the ``cua-driver`` CLI
and LaunchServices (``samrabbit_mac.py``): ``GET /v1/mac/state``, ``POST /v1/mac/open``,
``GET /v1/mac/read``, ``POST /v1/mac/act`` and ``GET /v1/mac/screenshot``.

Every route needs ``Authorization: Bearer <token>`` (the token lives in
``~/.config/samrabbit/bridge-token``, mode 0600). Journal text and the token are
never logged. Stdlib only; runs on the macOS system Python 3.9+.
"""

from __future__ import annotations

import argparse
import atexit
from datetime import date as Date, datetime, timezone
import hmac
import ipaddress
import json
import logging
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urlsplit

# ``python3 -I`` (as the LaunchAgent runs us) leaves the script's folder off sys.path; the sibling
# module lives there. Appended, so it can never shadow the standard library.
_HERE = os.path.dirname(os.path.abspath(__file__))
if _HERE not in sys.path:
    sys.path.append(_HERE)

import samrabbit_mac as mac  # noqa: E402

VERSION = "1.1.0"
SERVICE = "samrabbit-bridge"
DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 3780
DEFAULT_TOKEN_FILE = "~/.config/samrabbit/bridge-token"
FALLBACK_CLI = "/opt/homebrew/bin/heptabase"
MAX_BODY_BYTES = 64 * 1024
MAX_CLI_OUTPUT_BYTES = 32 * 1024 * 1024
CLI_TIMEOUT_SECONDS = 30.0
HEALTH_CLI_TIMEOUT_SECONDS = 10.0
HEALTH_CACHE_SECONDS = 10.0
VERSION_CACHE_SECONDS = 300.0
MAX_CONCURRENT_READS = 2
MIN_TOKEN_CHARS = 16
_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_REASON = re.compile(r"[^A-Za-z0-9_.-]")
_APP_DOWN_MARKERS = ("cannot connect to the desktop app", "runtime was not found", "cli bundle was not found")
_LOG = logging.getLogger(SERVICE)


# --------------------------------------------------------------------------- errors


class BridgeError(Exception):
    """An error answered as ``{"error": {...}}``. ``written`` tells the R1 whether an
    append may have reached the journal: ``False`` (definitely not) or ``"unknown"``."""

    def __init__(self, status: int, code: str, message: str, *, retryable: bool = False,
                 written: Any = False, reason: Optional[str] = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.written = written
        self.reason = reason

    def payload(self) -> Dict[str, Any]:
        error: Dict[str, Any] = {"code": self.code, "message": self.message, "retryable": self.retryable,
                                 "written": self.written}
        if self.reason:
            error["reason"] = self.reason
        return {"error": error}


def _app_unavailable() -> BridgeError:
    return BridgeError(503, "heptabase_app_unavailable",
                       "The Heptabase app is not running on the Mac, or its CLI is turned off "
                       "(Heptabase > Settings > AI Features).", retryable=True, written=False)


# --------------------------------------------------------------------------- CLI


class HeptabaseCli:
    """Runs the ``heptabase`` CLI. Output is parsed, never logged."""

    def __init__(self, executable: Optional[str] = None) -> None:
        self._explicit = executable
        self._version: Optional[str] = None
        self._version_at = 0.0
        self._lock = threading.Lock()

    def executable(self) -> Optional[str]:
        if self._explicit:
            return self._explicit if os.access(self._explicit, os.X_OK) else None
        found = shutil.which("heptabase")
        if found:
            return found
        return FALLBACK_CLI if os.access(FALLBACK_CLI, os.X_OK) else None

    def version(self) -> Optional[str]:
        with self._lock:
            if self._version and time.monotonic() - self._version_at < VERSION_CACHE_SECONDS:
                return self._version
        executable = self.executable()
        if executable is None:
            return None
        try:
            done = subprocess.run([executable, "--version"], stdin=subprocess.DEVNULL, capture_output=True,
                                  timeout=HEALTH_CLI_TIMEOUT_SECONDS, check=False)
        except (OSError, subprocess.SubprocessError):
            return None
        text = done.stdout.decode("utf-8", errors="replace").strip().splitlines()
        value = text[0].strip()[:32] if done.returncode == 0 and text else None
        with self._lock:
            self._version, self._version_at = value, time.monotonic()
        return value

    def run(self, args: List[str], *, timeout: float, append: bool = False) -> Dict[str, Any]:
        """Run one CLI command and return its JSON result.

        Failures are mapped to ``BridgeError``. For appends, ``written`` is
        ``False`` whenever the app answered with an error (the app validates
        before it changes the journal) and ``"unknown"`` when the CLI timed out
        or died without a clear answer.
        """
        unknown: Any = "unknown" if append else False
        executable = self.executable()
        if executable is None:
            raise BridgeError(503, "heptabase_cli_missing",
                              "The heptabase CLI was not found on the Mac. Install it from Heptabase > Settings.",
                              retryable=True, written=False)
        try:
            done = subprocess.run([executable, *args], stdin=subprocess.DEVNULL, capture_output=True,
                                  timeout=timeout, check=False)
        except subprocess.TimeoutExpired:
            raise BridgeError(504, "heptabase_cli_timeout", "The Heptabase CLI did not answer in time.",
                              retryable=True, written=unknown) from None
        except OSError:
            raise BridgeError(503, "heptabase_cli_missing", "The heptabase CLI could not be started.",
                              retryable=True, written=False) from None
        if len(done.stdout) > MAX_CLI_OUTPUT_BYTES:
            raise BridgeError(502, "heptabase_cli_output_too_large", "The Heptabase CLI answer was too large.",
                              retryable=False, written=unknown)
        if done.returncode == 0:
            try:
                value = json.loads(done.stdout.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                value = None
            if isinstance(value, dict):
                return value
            raise BridgeError(502, "heptabase_cli_bad_output", "The Heptabase CLI answered with unreadable output.",
                              retryable=True, written=unknown)
        raise _cli_failure(done.stderr, unknown)


def _cli_failure(stderr: bytes, unknown: Any) -> BridgeError:
    text = stderr.decode("utf-8", errors="replace")
    try:
        parsed = json.loads(text)
    except ValueError:
        parsed = None
    error = parsed.get("error") if isinstance(parsed, dict) else None
    if isinstance(error, dict):  # structured app error: {code, message, retryable}
        reason = _REASON.sub("", str(error.get("code", "")))[:64] or "rejected"
        if error.get("retryable") is True:
            return BridgeError(503, "heptabase_busy", "The Heptabase app asked to retry later.",
                               retryable=True, written=False, reason=reason)
        return BridgeError(422, "heptabase_rejected", "The Heptabase app rejected the request.",
                           retryable=False, written=False, reason=reason)
    message = (error if isinstance(error, str) else text).strip().lower()
    if any(marker in message for marker in _APP_DOWN_MARKERS):
        return _app_unavailable()
    if re.match(r"^http 5\d\d$", message):
        return BridgeError(503, "heptabase_app_error", "The Heptabase app failed while handling the request.",
                           retryable=True, written=unknown)
    if isinstance(error, str) and message:
        # The app's own validation errors arrive as a plain message and are raised before any write.
        return BridgeError(422, "heptabase_rejected", "The Heptabase app rejected the request.",
                           retryable=False, written=False, reason="rejected")
    return BridgeError(503, "heptabase_cli_failed", "The Heptabase CLI failed without an explanation.",
                       retryable=True, written=unknown)


# --------------------------------------------------------------------------- ProseMirror to text

_LIST_ITEMS = ("bullet_list_item", "numbered_list_item", "todo_list_item", "toggle_list_item")
_SKIPPED_BLOCKS = frozenset({"image", "video", "audio", "file", "embed", "horizontal_rule", "mention"})


def _inline_text(nodes: Any) -> str:
    parts: List[str] = []
    for node in nodes if isinstance(nodes, list) else []:
        if not isinstance(node, dict):
            continue
        kind = node.get("type")
        attrs = node.get("attrs") if isinstance(node.get("attrs"), dict) else {}
        if kind == "text":
            parts.append(str(node.get("text", "")))
        elif kind == "hard_break":
            parts.append("\n")
        elif kind == "date":
            parts.append(str(attrs.get("date") or ""))
        elif kind == "web":
            parts.append(str(attrs.get("title") or attrs.get("url") or ""))
        elif isinstance(node.get("content"), list):
            parts.append(_inline_text(node["content"]))
    return "".join(parts)


def prosemirror_lines(document: Any) -> List[str]:
    """Plain text lines for a ProseMirror journal: paragraphs, headings, list items
    (``- ``, ``1. ``, ``[ ] ``/``[x] ``, ``+ `` for toggles), quotes, code and tables.
    Marks are dropped; nested blocks are indented two spaces; empty blocks vanish."""
    lines: List[str] = []

    def emit(text: str, depth: int, marker: str) -> None:
        indent = "  " * depth
        first = True
        for piece in text.split("\n"):
            piece = piece.rstrip()
            if not piece.strip():
                continue
            lines.append(indent + (marker if first else " " * len(marker)) + piece)
            first = False

    def blocks(nodes: Any, depth: int, marker: str = "") -> None:
        number = 0
        for node in nodes if isinstance(nodes, list) else []:
            if not isinstance(node, dict):
                continue
            kind = node.get("type")
            if kind == "numbered_list_item":
                order = (node.get("attrs") or {}).get("order") if isinstance(node.get("attrs"), dict) else None
                number = order if isinstance(order, int) and not isinstance(order, bool) and order > 0 else number + 1
            else:
                number = 0
            block(node, depth, marker, number)

    def block(node: Dict[str, Any], depth: int, marker: str, number: int) -> None:
        kind = node.get("type")
        attrs = node.get("attrs") if isinstance(node.get("attrs"), dict) else {}
        content = node.get("content") if isinstance(node.get("content"), list) else []
        if kind in ("paragraph", "heading", "code_block", "math_display"):
            emit(_inline_text(content), depth, marker)
        elif kind in _LIST_ITEMS:
            if kind == "bullet_list_item":
                item = "- "
            elif kind == "numbered_list_item":
                item = f"{number}. "
            elif kind == "todo_list_item":
                item = "[x] " if attrs.get("checked") else "[ ] "
            else:
                item = "+ "
            head, rest = (content[0], content[1:]) if content else (None, [])
            if isinstance(head, dict):
                emit(_inline_text(head.get("content")), depth, marker + item)
            blocks(rest, depth + 1)
        elif kind == "blockquote":
            blocks(content, depth, marker + "> ")
        elif kind == "table":
            for row in content:
                cells = []
                for cell in (row.get("content") if isinstance(row, dict) and isinstance(row.get("content"), list) else []):
                    inner: List[str] = []
                    for child in cell.get("content", []) if isinstance(cell, dict) else []:
                        if isinstance(child, dict):
                            inner.append(_inline_text(child.get("content")).replace("\n", " ").strip())
                    cells.append(" ".join(part for part in inner if part))
                if any(cells):
                    emit(" | ".join(cells), depth, marker)
        elif kind == "bookmark":
            emit(str(attrs.get("title") or attrs.get("url") or ""), depth, marker)
        elif kind in _SKIPPED_BLOCKS:
            return
        else:
            blocks(content, depth, marker)

    if isinstance(document, dict):
        blocks(document.get("content"), 0)
    return lines


# --------------------------------------------------------------------------- token and clients


class TokenFile:
    """Bearer token read from a 0600 file; re-read when the file changes."""

    def __init__(self, path: str) -> None:
        self.path = os.path.expanduser(path)
        self._token: Optional[bytes] = None
        self._stamp: Optional[Tuple[float, int]] = None
        self._lock = threading.Lock()
        self.load()

    def load(self) -> bytes:
        info = os.stat(self.path)
        if info.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
            os.chmod(self.path, 0o600)
            _LOG.warning("token file permissions tightened to 0600")
        with open(self.path, "rb") as handle:
            token = handle.read().strip()
        if len(token) < MIN_TOKEN_CHARS or any(byte <= 0x20 or byte >= 0x7F for byte in token):
            raise ValueError("the bridge token file is empty or malformed; run install.sh again")
        with self._lock:
            self._token, self._stamp = token, (info.st_mtime, info.st_size)
        return token

    def matches(self, presented: str) -> bool:
        try:
            info = os.stat(self.path)
            if (info.st_mtime, info.st_size) != self._stamp:
                self.load()
        except (OSError, ValueError):
            pass  # keep the last good token
        with self._lock:
            token = self._token or b""
        candidate = presented.encode("utf-8", errors="replace")
        return bool(token) and hmac.compare_digest(candidate, token)


_PRIVATE_V4 = tuple(ipaddress.ip_network(net) for net in
                    ("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16"))
_PRIVATE_V6 = tuple(ipaddress.ip_network(net) for net in ("::1/128", "fc00::/7", "fe80::/10"))


def client_allowed(address: str) -> bool:
    """Only loopback and private-LAN peers may talk to the bridge."""
    try:
        ip = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    networks = _PRIVATE_V4 if ip.version == 4 else _PRIVATE_V6
    return any(ip in network for network in networks)


# --------------------------------------------------------------------------- server


def _today() -> str:
    return datetime.now().astimezone().date().isoformat()


def _valid_date(value: Any) -> str:
    if not isinstance(value, str) or not _DATE.match(value):
        raise BridgeError(400, "invalid_date", "date must be YYYY-MM-DD.")
    try:
        Date.fromisoformat(value)
    except ValueError:
        raise BridgeError(400, "invalid_date", "date is not a real calendar day.") from None
    return value


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address: Tuple[str, int], *, token: TokenFile, cli: HeptabaseCli,
                 cli_timeout: float = CLI_TIMEOUT_SECONDS, allow_any_client: bool = False,
                 mac_control: Optional[mac.MacControl] = None) -> None:
        self.token = token
        self.cli = cli
        self.mac = mac_control or mac.MacControl(mac.CuaDriver())
        self.cli_timeout = cli_timeout
        self.allow_any_client = allow_any_client
        self.append_lock = threading.Lock()
        self.read_slots = threading.BoundedSemaphore(MAX_CONCURRENT_READS)
        self.scratch = tempfile.mkdtemp(prefix="samrabbit-bridge-")  # 0700
        self._health: Optional[Tuple[float, Dict[str, Any]]] = None
        self._health_lock = threading.Lock()
        super().__init__(address, BridgeHandler)

    def server_close(self) -> None:
        super().server_close()
        shutil.rmtree(self.scratch, ignore_errors=True)
        self.mac.close()

    def health(self) -> Dict[str, Any]:
        with self._health_lock:
            cached = self._health
            if cached and time.monotonic() - cached[0] < HEALTH_CACHE_SECONDS:
                return cached[1]
            version = self.cli.version()
            reachable = False
            detail: Optional[str] = None
            if version is None:
                detail = "heptabase_cli_missing"
            else:
                try:
                    self.cli.run(["journal", "read", _today()], timeout=HEALTH_CLI_TIMEOUT_SECONDS)
                    reachable = True
                except BridgeError as error:
                    detail = error.code
            try:
                capabilities: Dict[str, Any] = self.mac.capabilities()
            except Exception:  # Mac control must never break the journal's health check
                _LOG.warning("mac capabilities failed")
                capabilities = {"driver": {"available": False}, "features": {}}
            value: Dict[str, Any] = {
                "ok": True, "service": SERVICE, "version": VERSION,
                "cli": {"available": version is not None, "version": version},
                "app": {"reachable": reachable, "detail": detail},
                "mac": capabilities,
                "checkedAt": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
            }
            self._health = (time.monotonic(), value)
            return value

    def append(self, journal_date: str, content: str) -> Dict[str, Any]:
        if not self.append_lock.acquire(timeout=self.cli_timeout + 5):
            raise BridgeError(503, "bridge_busy", "Another journal write is still running.", retryable=True)
        try:
            handle, path = tempfile.mkstemp(prefix="entry-", suffix=".md", dir=self.scratch)
            try:
                os.fchmod(handle, 0o600)
                with os.fdopen(handle, "wb") as file:
                    file.write(content.encode("utf-8"))
                result = self.cli.run(["journal", "append", journal_date, "--content-file", path],
                                      timeout=self.cli_timeout, append=True)
            finally:
                try:
                    os.unlink(path)
                except FileNotFoundError:
                    pass
        finally:
            self.append_lock.release()
        with self._health_lock:
            self._health = None
        return {"date": str(result.get("date") or journal_date), "title": result.get("title"),
                "contentMd5": result.get("contentMd5")}

    def read(self, journal_date: str) -> Dict[str, Any]:
        if not self.read_slots.acquire(timeout=self.cli_timeout + 5):
            raise BridgeError(503, "bridge_busy", "Too many journal reads at once.", retryable=True)
        try:
            result = self.cli.run(["journal", "read", journal_date], timeout=self.cli_timeout)
        finally:
            self.read_slots.release()
        raw = result.get("content")
        try:
            document = json.loads(raw) if isinstance(raw, str) and raw else (raw if isinstance(raw, dict) else {})
        except ValueError:
            raise BridgeError(502, "heptabase_cli_bad_output", "The journal content was unreadable.",
                              retryable=True) from None
        return {"date": str(result.get("date") or journal_date), "title": result.get("title"),
                "text": "\n".join(prosemirror_lines(document)), "contentMd5": result.get("contentMd5")}


class BridgeHandler(BaseHTTPRequestHandler):
    server: BridgeServer
    server_version = "SamRabbitBridge/" + VERSION
    sys_version = ""
    timeout = 20  # socket timeout while reading a request

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        return  # the default line includes the raw request target; we log our own summary

    def do_GET(self) -> None:  # noqa: N802
        self._dispatch("GET")

    def do_POST(self) -> None:  # noqa: N802
        self._dispatch("POST")

    def do_PUT(self) -> None:  # noqa: N802
        self._dispatch("PUT")

    def do_DELETE(self) -> None:  # noqa: N802
        self._dispatch("DELETE")

    def _dispatch(self, method: str) -> None:
        started = time.monotonic()
        route = urlsplit(self.path).path
        status = 500
        try:
            if not client_allowed(self.client_address[0]) and \
                    (not self.server.allow_any_client or route.startswith(_MAC_PREFIX)):
                # Mac control never leaves the local network, even with --allow-any-client.
                raise BridgeError(403, "forbidden", "Only devices on the local network may use this bridge.")
            if not self._authorized():
                raise BridgeError(401, "unauthorized", "A valid bridge token is required.")
            status, payload = self._route(method, route)
        except BridgeError as error:
            status, payload = error.status, error.payload()
        except mac.MacError as error:
            status, payload = error.status, error.payload()
        except Exception:  # never leak details (or journal text) to the caller or the log
            _LOG.exception("unexpected failure on %s %s", method, route)
            status, payload = 500, BridgeError(500, "internal_error", "The bridge failed unexpectedly.",
                                               written="unknown").payload()
        self._send(status, payload)
        code = payload.get("error", {}).get("code") if isinstance(payload.get("error"), dict) else None
        _LOG.info("%s %s %s %dms%s", method, route if route in _ROUTES else "(other)", status,
                  int((time.monotonic() - started) * 1000), f" {code}" if code else "")

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        scheme, _, value = header.partition(" ")
        if scheme.lower() != "bearer" or not value.strip():
            return False
        return self.server.token.matches(value.strip())

    def _route(self, method: str, route: str) -> Tuple[int, Dict[str, Any]]:
        if route == "/health":
            self._require(method, "GET")
            return 200, self.server.health()
        if route == "/v1/heptabase/journal/append":
            self._require(method, "POST")
            body = self._json_body()
            journal_date = _valid_date(body.get("date"))
            content = body.get("content")
            if not isinstance(content, str) or not content.strip():
                raise BridgeError(400, "invalid_content", "content must be non-empty markdown text.")
            if "\x00" in content:
                raise BridgeError(400, "invalid_content", "content must not contain NUL characters.")
            return 200, self.server.append(journal_date, content)
        if route == "/v1/heptabase/journal/read":
            self._require(method, "GET")
            query = parse_qs(urlsplit(self.path).query, max_num_fields=4)
            values = query.get("date") or [""]
            return 200, self.server.read(_valid_date(values[0]))
        if route.startswith(_MAC_PREFIX):
            return self._mac_route(method, route)
        raise BridgeError(404, "not_found", "Not found.")

    def _mac_route(self, method: str, route: str) -> Tuple[int, Dict[str, Any]]:
        control = self.server.mac
        if route == "/v1/mac/state":
            self._require(method, "GET")
            return 200, control.state()
        if route == "/v1/mac/open":
            self._require(method, "POST")
            return 200, control.open(self._json_body())
        if route == "/v1/mac/read":
            self._require(method, "GET")
            query = self._query()
            return 200, control.read(_one(query, "app"), _number(query, "max"))
        if route == "/v1/mac/act":
            self._require(method, "POST")
            return 200, control.act(self._json_body())
        if route == "/v1/mac/screenshot":
            self._require(method, "GET")
            query = self._query()
            return 200, control.screenshot(_one(query, "app"), _number(query, "max"))
        raise BridgeError(404, "not_found", "Not found.")

    def _query(self) -> Dict[str, List[str]]:
        return parse_qs(urlsplit(self.path).query, max_num_fields=8)

    @staticmethod
    def _require(method: str, expected: str) -> None:
        if method != expected:
            raise BridgeError(405, "method_not_allowed", f"Use {expected}.")

    def _json_body(self) -> Dict[str, Any]:
        if self.headers.get("Transfer-Encoding"):
            raise BridgeError(411, "length_required", "Send a Content-Length body.")
        try:
            length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            raise BridgeError(411, "length_required", "Send a Content-Length body.") from None
        if length < 0 or length > MAX_BODY_BYTES:
            raise BridgeError(413, "body_too_large", f"The body must be at most {MAX_BODY_BYTES} bytes.")
        raw = self.rfile.read(length)
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            raise BridgeError(400, "invalid_json", "The body must be a JSON object.") from None
        if not isinstance(value, dict):
            raise BridgeError(400, "invalid_json", "The body must be a JSON object.")
        return value

    def _send(self, status: int, payload: Dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        try:
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            if status == 401:
                self.send_header("WWW-Authenticate", 'Bearer realm="samrabbit-bridge"')
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
        except OSError:
            pass  # the client went away


_MAC_PREFIX = "/v1/mac/"
_ROUTES = frozenset({"/health", "/v1/heptabase/journal/append", "/v1/heptabase/journal/read", "/v1/mac/state",
                     "/v1/mac/open", "/v1/mac/read", "/v1/mac/act", "/v1/mac/screenshot"})


def _one(query: Dict[str, List[str]], key: str) -> Optional[str]:
    values = query.get(key) or []
    value = values[0].strip() if values else ""
    if len(value) > 200:
        raise BridgeError(400, "invalid_query", f"{key} is too long.")
    return value or None


def _number(query: Dict[str, List[str]], key: str) -> Optional[int]:
    value = _one(query, key)
    if value is None:
        return None
    if not value.isdigit() or len(value) > 6:
        raise BridgeError(400, "invalid_query", f"{key} must be a whole number.")
    return int(value)


def make_server(host: str = DEFAULT_HOST, port: int = DEFAULT_PORT, *, token_file: str = DEFAULT_TOKEN_FILE,
                cli: Optional[str] = None, cli_timeout: float = CLI_TIMEOUT_SECONDS,
                allow_any_client: bool = False, driver: Optional[str] = None,
                mac_control: Optional[mac.MacControl] = None) -> BridgeServer:
    return BridgeServer((host, port), token=TokenFile(token_file), cli=HeptabaseCli(cli),
                        cli_timeout=cli_timeout, allow_any_client=allow_any_client,
                        mac_control=mac_control or mac.MacControl(mac.CuaDriver(driver)))


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="SamRabbit Mac bridge for the Heptabase journal.")
    parser.add_argument("--host", default=os.environ.get("SAMRABBIT_BRIDGE_HOST", DEFAULT_HOST))
    parser.add_argument("--port", type=int, default=int(os.environ.get("SAMRABBIT_BRIDGE_PORT", DEFAULT_PORT)))
    parser.add_argument("--token-file", default=os.environ.get("SAMRABBIT_BRIDGE_TOKEN_FILE", DEFAULT_TOKEN_FILE))
    parser.add_argument("--cli", default=os.environ.get("SAMRABBIT_HEPTABASE_CLI") or None,
                        help="path to the heptabase CLI (default: found on PATH)")
    parser.add_argument("--cli-timeout", type=float, default=CLI_TIMEOUT_SECONDS)
    parser.add_argument("--cua-driver", default=os.environ.get("SAMRABBIT_CUA_DRIVER") or None,
                        help="path to the cua-driver CLI (default: found on PATH or in the usual install folders)")
    parser.add_argument("--allow-any-client", action="store_true",
                        help="accept peers outside loopback/private networks (not recommended)")
    options = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s", stream=sys.stderr)
    try:
        server = make_server(options.host, options.port, token_file=options.token_file, cli=options.cli,
                             cli_timeout=options.cli_timeout, allow_any_client=options.allow_any_client,
                             driver=options.cua_driver)
    except (OSError, ValueError) as error:
        _LOG.error("cannot start: %s", error)
        return 2
    atexit.register(server.server_close)

    def stop(_signum: int, _frame: Any) -> None:
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    cli_path = server.cli.executable()
    _LOG.info("%s %s listening on %s:%d (cli %s, cua-driver %s)", SERVICE, VERSION, options.host,
              server.server_address[1], cli_path or "missing", "found" if server.mac.driver.executable() else "missing")
    server.serve_forever(poll_interval=0.5)
    _LOG.info("stopped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
