"""SamRabbit desktop web UI, served by the Mac bridge at ``/app/``.

The SamRabbit desktop app (``companion/desktop``) is a small native shell around a web view that
loads ``http://127.0.0.1:3780/app/``. This module serves those static files (HTML, CSS, ES modules,
images) from the installed web folder:

* ``$SAMRABBIT_APP_WEB_DIR`` when set (tests, development),
* else ``/Applications/SamRabbit.app/Contents/Resources/web`` (what ``companion/desktop/install.sh``
  installs), ``~/Applications/SamRabbit.app/Contents/Resources/web``,
* else ``companion/desktop/web`` next to this file when the bridge runs from a checkout.

Access rules (the same as the desktop sync API): the peer must be loopback (127.0.0.0/8 or ::1)
and present the desktop token from ``~/.config/samrabbit/desktop-token`` (0600) as the header
``X-SamRabbit-Desktop`` or the cookie ``sr_desktop``. The bridge's LAN bearer token is NOT
accepted here, and LAN peers are refused even with ``--allow-any-client``.

Only files below the web folder with a known extension are served (no directory listings, no
dotfiles, no ``..``, symlinks must stay inside the folder). Nothing about the request beyond the
method, the route bucket and the status is logged. Stdlib only; Python 3.9 compatible.
"""

from __future__ import annotations

import hmac
import ipaddress
import logging
import os
import stat
import threading
import time
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import unquote, urlsplit

PREFIX = "/app"
DEFAULT_TOKEN_FILE = "~/.config/samrabbit/desktop-token"
TOKEN_HEADER = "X-SamRabbit-Desktop"
TOKEN_COOKIE = "sr_desktop"
MIN_TOKEN_CHARS = 16
MAX_FILE_BYTES = 8 * 1024 * 1024
INSTALLED_WEB_DIRS = (
    "/Applications/SamRabbit.app/Contents/Resources/web",
    "~/Applications/SamRabbit.app/Contents/Resources/web",
)
CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".webmanifest": "application/manifest+json; charset=utf-8",
    ".txt": "text/plain; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
    ".woff": "font/woff",
}
# The UI only talks to its own origin; generated UIs live in sandboxed iframes from the same origin.
PAGE_CSP = ("default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            "img-src 'self' data: blob:; connect-src 'self'; frame-src 'self'; font-src 'self' data:; "
            "media-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'; "
            "frame-ancestors 'none'")
_LOG = logging.getLogger("samrabbit-bridge")
_HERE = os.path.dirname(os.path.abspath(__file__))


def handles(route: str) -> bool:
    """True for ``/app`` and everything below it."""
    return route == PREFIX or route.startswith(PREFIX + "/")


def is_loopback(address: str) -> bool:
    try:
        ip = ipaddress.ip_address(address.split("%", 1)[0])
    except ValueError:
        return False
    if ip.version == 6 and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    return ip.is_loopback


class DesktopToken:
    """The desktop token file; re-read when it changes. A missing file means "not set up yet"."""

    def __init__(self, path: str = DEFAULT_TOKEN_FILE) -> None:
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
        token: Optional[bytes] = None
        try:
            if info.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                os.chmod(self.path, 0o600)
                _LOG.warning("desktop token permissions tightened to 0600")
            with open(self.path, "rb") as handle:
                value = handle.read(4096).strip()
            if len(value) >= MIN_TOKEN_CHARS and all(0x20 < byte < 0x7F for byte in value):
                token = value
            else:
                _LOG.warning("the desktop token file is empty or malformed")
        except OSError:
            token = None
        with self._lock:
            self._token, self._stamp = token, stamp
        return token

    def configured(self) -> bool:
        return self._current() is not None

    def matches(self, presented: Optional[str]) -> bool:
        token = self._current()
        if not token or not presented:
            return False
        return hmac.compare_digest(presented.encode("utf-8", errors="replace"), token)


def presented_token(headers: Any) -> Optional[str]:
    """The desktop token from ``X-SamRabbit-Desktop`` or the ``sr_desktop`` cookie."""
    value = (headers.get(TOKEN_HEADER) or "").strip()
    if value:
        return value
    if hasattr(headers, "get_all"):
        cookies = headers.get_all("Cookie") or []
    else:
        cookies = [headers.get("Cookie") or ""]
    for header in cookies:
        for part in str(header).split(";"):
            name, sep, raw = part.strip().partition("=")
            if sep and name == TOKEN_COOKIE:
                raw = raw.strip()
                if len(raw) >= 2 and raw[0] == raw[-1] == '"':
                    raw = raw[1:-1]
                return raw or None
    return None


def candidate_web_dirs() -> List[str]:
    explicit = os.environ.get("SAMRABBIT_APP_WEB_DIR", "").strip()
    if explicit:
        return [os.path.expanduser(explicit)]
    dirs = [os.path.expanduser(path) for path in INSTALLED_WEB_DIRS]
    dirs.append(os.path.normpath(os.path.join(_HERE, "..", "desktop", "web")))
    return dirs


class NotFound(Exception):
    pass


def resolve_file(web_dir: str, route: str) -> str:
    """Maps ``/app/<path>`` to a regular file inside ``web_dir`` or raises ``NotFound``."""
    relative = route[len(PREFIX):]
    try:
        relative = unquote(relative, errors="strict")
    except UnicodeDecodeError:
        raise NotFound() from None
    if "\x00" in relative or "\\" in relative:
        raise NotFound()
    parts = [part for part in relative.split("/") if part]
    for part in parts:
        if part in (".", "..") or part.startswith(".") or ":" in part:
            raise NotFound()
    if not parts or relative.endswith("/"):
        parts.append("index.html")
    root = os.path.realpath(web_dir)
    path = os.path.realpath(os.path.join(root, *parts))
    if not path.startswith(root + os.sep):
        raise NotFound()
    if os.path.isdir(path):
        path = os.path.join(path, "index.html")
    if os.path.splitext(path)[1].lower() not in CONTENT_TYPES:
        raise NotFound()
    try:
        info = os.stat(path)
    except OSError:
        raise NotFound() from None
    if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_FILE_BYTES:
        raise NotFound()
    return path


_PAGES = {
    401: ("Open SamRabbit from the app",
          "This page belongs to the SamRabbit desktop app. Open the SamRabbit app on this Mac."),
    403: ("Not available", "The SamRabbit desktop page is only available on this Mac."),
    404: ("Not found", "There is no such page in the SamRabbit desktop app."),
    405: ("Not allowed", "Only GET is supported here."),
}


def _error_page(status: int, title: str, message: str) -> bytes:
    return ("<!doctype html><html><head><meta charset=\"utf-8\"><title>SamRabbit</title>"
            "<style>body{background:#090b10;color:#f5f8ff;font:15px -apple-system,system-ui,sans-serif;"
            "display:grid;place-items:center;height:100vh;margin:0}main{max-width:420px;text-align:center}"
            "p{color:#8c98ac;line-height:1.5}</style></head><body><main><h1>" + title + "</h1><p>" + message
            + "</p><p style=\"font-size:12px\">HTTP " + str(status) + "</p></main></body></html>").encode("utf-8")


class AppSite:
    """Serves the desktop web UI for one bridge (thread-safe; one instance per process is enough)."""

    def __init__(self, web_dir: Optional[str] = None, token_file: Optional[str] = None) -> None:
        self._web_dir = os.path.expanduser(web_dir) if web_dir else None
        self.token = DesktopToken(token_file or os.environ.get("SAMRABBIT_DESKTOP_TOKEN_FILE") or DEFAULT_TOKEN_FILE)

    def web_dir(self) -> Optional[str]:
        dirs = [self._web_dir] if self._web_dir else candidate_web_dirs()
        for path in dirs:
            if os.path.isfile(os.path.join(path, "index.html")):
                return path
        return None

    def serve(self, handler: Any, method: str) -> int:
        """Answers one request on a ``BaseHTTPRequestHandler``; returns the status for logging."""
        started = time.monotonic()
        status = 500
        try:
            status = self._serve(handler, method)
        except Exception:  # never leak details; never take the bridge down
            _LOG.exception("unexpected failure on %s %s", method, PREFIX)
            status = self._send_page(handler, 500, "Something went wrong",
                                     "The SamRabbit bridge failed to serve the desktop page.")
        _LOG.info("%s %s %s %dms", method, PREFIX, status, int((time.monotonic() - started) * 1000))
        return status

    def _serve(self, handler: Any, method: str) -> int:
        if not is_loopback(str(handler.client_address[0])):
            return self._send_page(handler, 403, *_PAGES[403])
        if not self.token.configured():
            return self._send_page(handler, 503, "Desktop token missing",
                                   "Run companion/desktop/install.sh to set up the SamRabbit desktop app.")
        if not self.token.matches(presented_token(handler.headers)):
            return self._send_page(handler, 401, *_PAGES[401])
        if method != "GET":
            return self._send_page(handler, 405, *_PAGES[405], extra={"Allow": "GET"})
        split = urlsplit(handler.path)
        if split.path == PREFIX:
            location = PREFIX + "/" + ("?" + split.query if split.query else "")
            return self._send(handler, 308, b"", "text/plain; charset=utf-8", extra={"Location": location})
        web_dir = self.web_dir()
        if web_dir is None:
            return self._send_page(handler, 503, "Desktop app not installed",
                                   "Run companion/desktop/install.sh to install the SamRabbit desktop app.")
        try:
            path = resolve_file(web_dir, split.path)
            with open(path, "rb") as handle:
                body = handle.read(MAX_FILE_BYTES + 1)
        except (NotFound, OSError):
            return self._send_page(handler, 404, *_PAGES[404])
        content_type = CONTENT_TYPES[os.path.splitext(path)[1].lower()]
        extra: Dict[str, str] = {}
        if content_type.startswith("text/html"):
            extra["Content-Security-Policy"] = PAGE_CSP
        return self._send(handler, 200, body, content_type, extra=extra)

    def _send_page(self, handler: Any, status: int, title: str, message: str,
                   extra: Optional[Dict[str, str]] = None) -> int:
        return self._send(handler, status, _error_page(status, title, message), "text/html; charset=utf-8",
                          extra=extra)

    @staticmethod
    def _send(handler: Any, status: int, body: bytes, content_type: str,
              extra: Optional[Dict[str, str]] = None) -> int:
        try:
            handler.send_response(status)
            handler.send_header("Content-Type", content_type)
            handler.send_header("Content-Length", str(len(body)))
            handler.send_header("Cache-Control", "no-cache")
            handler.send_header("X-Content-Type-Options", "nosniff")
            handler.send_header("Referrer-Policy", "no-referrer")
            handler.send_header("Cross-Origin-Resource-Policy", "same-origin")
            for name, value in (extra or {}).items():
                handler.send_header(name, value)
            handler.send_header("Connection", "close")
            handler.end_headers()
            handler.wfile.write(body)
        except OSError:
            pass  # the client went away
        return status


_SITE: Optional[AppSite] = None
_SITE_LOCK = threading.Lock()


def site() -> AppSite:
    """The process-wide site (configured from the environment on first use)."""
    global _SITE
    with _SITE_LOCK:
        if _SITE is None:
            _SITE = AppSite()
        return _SITE


def serve(handler: Any, method: str) -> int:
    return site().serve(handler, method)
