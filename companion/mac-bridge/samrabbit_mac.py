"""Mac control for the SamRabbit bridge: the R1's voice orchestrator sees and drives this Mac.

Everything goes through two local tools, nothing else:

* the ``cua-driver`` CLI (Accessibility for reading and acting; Screen Recording only for
  screenshots), one call at a time, and
* LaunchServices ``/usr/bin/open`` for http(s) links in Google Chrome and files in the home folder.

``/usr/sbin/ioreg`` is read (never written) to tell whether the screen is locked: a capture of a
locked Mac is all black, so a screenshot is not even attempted then.

No AppleScript (it would raise Automation prompts), no shell, no ``kill_app``. Window text, typed
text, URLs and file names are never logged; errors carry codes, never the driver's own messages.
Stdlib only (macOS system Python 3.9+).
"""

from __future__ import annotations

import base64
from difflib import SequenceMatcher
import glob
import json
import os
import plistlib
import re
import shutil
import stat
import subprocess
import tempfile
import threading
import time
import unicodedata
from typing import Any, Callable, Dict, List, Optional, Sequence, Tuple
from urllib.parse import urlsplit

DRIVER_TIMEOUT_SECONDS = 15.0
STATUS_TIMEOUT_SECONDS = 5.0
OPEN_TIMEOUT_SECONDS = 15.0
LOCK_WAIT_SECONDS = 25.0
APPS_CACHE_SECONDS = 60.0
VERSION_CACHE_SECONDS = 300.0
PERMISSION_CACHE_SECONDS = 10.0
MAX_READ_CHARS = 6000
DEFAULT_READ_CHARS = 6000
MAX_TYPE_CHARS = 2000
MAX_URL_CHARS = 2048
MAX_SCREENSHOT_BYTES = 150 * 1024
DEFAULT_SCREENSHOT_SIDE = 1024
MIN_SCREENSHOT_SIDE = 320
MAX_SCREENSHOT_SIDE = 1600
MAX_DRIVER_OUTPUT_BYTES = 24 * 1024 * 1024
IOREG = "/usr/sbin/ioreg"
LOCK_CHECK_TIMEOUT_SECONDS = 2.0
MAX_IOREG_BYTES = 8 * 1024 * 1024
SCREEN_LOCKED_MESSAGE = "Your Mac's screen is locked, so I can't see it. Unlock it and ask again."
CHROME_BUNDLE = "com.google.Chrome"
CHROME_NAME = "Google Chrome"
DRIVER_CANDIDATES = (
    "/Applications/CuaDriver.app/Contents/MacOS/cua-driver",
    "~/Applications/CuaDriver.app/Contents/MacOS/cua-driver",
)
DRIVER_GLOBS = ("~/.hermes/tools/cua-driver-*/CuaDriver.app/Contents/MacOS/cua-driver",)
# Windows that are never "what is on the screen": the driver's own cursor overlay and 1x1 helpers.
_OVERLAY_APPS = frozenset({"cua driver", "window server", "dock", "betterdisplay", "universal control"})
_INVISIBLE = re.compile(r"[​-‏‪-‮⁠﻿]")
_SPACE = re.compile(r"\s+")
_WORD = re.compile(r"[a-z0-9]+")
_TAB_SUFFIX = re.compile(
    r"\s+-\s+(?:Memory usage\s+-\s+[\d.,]+\s*[KMGT]?B|Audio playing|Audio muted|Camera recording|"
    r"Microphone recording|Network error|Crashed|Pinned|Bluetooth device connected|Screen sharing)\s*$",
    re.IGNORECASE)
_VERSION = re.compile(r"(\d+)\.(\d+)\.(\d+)")
_PID = re.compile(r"\bpid\s*=\s*(\d+)")
# cua-driver answers some successes with a code too (e.g. bring_to_front_exact_window_verified), and
# some failures without an "effect" (e.g. window_id_not_found): an error is refused or error-shaped.
_ERROR_CODE = re.compile(r"(not_found|missing|mismatch|denied|invalid|unavailable|ambiguous|ambiguity|stale|"
                         r"required|refused|failed|failure|error|timeout|not_granted|unsupported|not_running)")
_ASN = re.compile(r"(ASN:[0-9a-fx-]+:?)", re.IGNORECASE)

_SKIPPED_ROLES = frozenset({
    "AXMenuBar", "AXMenuBarItem", "AXMenu", "AXMenuItem", "AXScrollBar", "AXValueIndicator", "AXSplitter",
    "AXGrowArea", "AXListMarker", "AXIncrementor", "AXBusyIndicator", "AXProgressIndicator",
})
_FIELD_ROLES = frozenset({"AXTextField", "AXTextArea", "AXSearchField", "AXComboBox", "AXSecureTextField"})
_CONTROL_ROLES = frozenset({
    "AXButton", "AXPopUpButton", "AXMenuButton", "AXCheckBox", "AXRadioButton", "AXTab", "AXDisclosureTriangle",
    "AXSwitch", "AXToggle",
})
_INLINE_ROLES = frozenset({"AXStaticText", "AXLink"})
_CLICKABLE_ROLES = _CONTROL_ROLES | frozenset({"AXLink", "AXCell", "AXRow", "AXOutlineRow", "AXImage", "AXMenuItem"})
_ROLE_ALIASES: Dict[str, Tuple[str, ...]] = {
    "button": ("AXButton", "AXPopUpButton", "AXMenuButton"),
    "link": ("AXLink",),
    "checkbox": ("AXCheckBox", "AXSwitch", "AXToggle"),
    "tab": ("AXRadioButton", "AXTab"),
    "radio": ("AXRadioButton",),
    "field": tuple(sorted(_FIELD_ROLES)),
    "text field": tuple(sorted(_FIELD_ROLES)),
    "textfield": tuple(sorted(_FIELD_ROLES)),
    "row": ("AXRow", "AXOutlineRow", "AXCell"),
    "cell": ("AXCell",),
    "menu item": ("AXMenuItem",),
    "image": ("AXImage",),
}
_MODIFIERS = {"cmd": "cmd", "command": "cmd", "⌘": "cmd", "shift": "shift", "⇧": "shift", "option": "option",
              "opt": "option", "alt": "option", "⌥": "option", "ctrl": "ctrl", "control": "ctrl", "⌃": "ctrl",
              "fn": "fn"}
_NAMED_KEYS = frozenset({
    "return", "enter", "tab", "escape", "esc", "space", "delete", "backspace", "forwarddelete", "up", "down",
    "left", "right", "home", "end", "pageup", "pagedown", *[f"f{n}" for n in range(1, 13)],
})
_KEY_ALIASES = {"esc": "escape", "enter": "return", "backspace": "delete", "arrowup": "up", "arrowdown": "down",
                "arrowleft": "left", "arrowright": "right", "page up": "pageup", "page down": "pagedown"}
# System-wide chords that end the session, lock the Mac or delete files. Everything else is the
# voice model's call (it is told to confirm destructive steps with the user first).
_BLOCKED_CHORDS = frozenset({
    ("cmd", "shift", "q"), ("cmd", "option", "shift", "q"), ("cmd", "ctrl", "q"), ("cmd", "option", "escape"),
    ("cmd", "delete"), ("cmd", "shift", "delete"), ("cmd", "option", "shift", "delete"), ("cmd", "option", "delete"),
    ("cmd", "ctrl", "power"), ("cmd", "option", "power"), ("cmd", "ctrl", "option", "power"),
})
_BLOCKED = frozenset(tuple(sorted(chord[:-1])) + (chord[-1],) for chord in _BLOCKED_CHORDS)
# Menu items that end the session or delete files (the menu twins of the blocked chords above).
_BLOCKED_MENU = re.compile(r"^(shut down|restart|log ?out|force quit|empty (the )?(trash|bin)|secure empty trash|"
                           r"move to (the )?(trash|bin)|delete immediately|erase|lock screen|sleep)", re.IGNORECASE)
_BLOCKED_SUFFIXES = frozenset({
    ".app", ".command", ".tool", ".sh", ".zsh", ".bash", ".csh", ".fish", ".py", ".rb", ".pl", ".scpt",
    ".scptd", ".applescript", ".workflow", ".terminal", ".pkg", ".mpkg", ".prefpane", ".kext", ".jar",
    ".webloc", ".inetloc", ".fileloc", ".action", ".osax", ".mobileconfig", ".shortcut",
    ".url", ".afploc", ".ftploc", ".mailloc", ".newsloc", ".vncloc", ".saver", ".mobileprovision",
    ".provisionprofile",
})
# A Finder alias is a plain file that LaunchServices resolves to its target, which may be an app or a
# script outside the home folder; realpath() does not see through it.
_ALIAS_MAGIC = b"book\x00\x00\x00\x00mark\x00\x00\x00\x00"
# Typing or pressing keys in these is a shell (or a script runner): terminal work goes to mac_task.
_TERMINAL_BUNDLES = frozenset({
    "com.apple.terminal", "com.googlecode.iterm2", "dev.warp.warp-stable", "dev.warp.warp", "com.mitchellh.ghostty",
    "net.kovidgoyal.kitty", "org.alacritty", "io.alacritty", "com.github.wez.wezterm", "co.zeit.hyper",
    "com.apple.scripteditor2", "org.tabby",
})
_TERMINAL_NAMES = frozenset({"terminal", "iterm", "iterm2", "warp", "ghostty", "kitty", "alacritty", "wezterm",
                             "hyper", "script editor", "tabby"})
_ACTIONS = ("bring_to_front", "hotkey", "type_text", "click", "invoke_menu", "scroll")


class MacError(Exception):
    """An answer of ``{"error": {"code", "message", "retryable"[, "fix"][, ...]}}``."""

    def __init__(self, status: int, code: str, message: str, *, retryable: bool = False, fix: Optional[str] = None,
                 details: Optional[Dict[str, Any]] = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.fix = fix
        self.details = details or {}
        self.driver_code: Optional[str] = None  # cua-driver's own refusal code (internal, never sent)
        self.refused = False

    def payload(self) -> Dict[str, Any]:
        error: Dict[str, Any] = {"code": self.code, "message": self.message, "retryable": self.retryable}
        if self.fix:
            error["fix"] = self.fix
        error.update(self.details)
        value: Dict[str, Any] = {"error": error}
        if self.fix:  # also flat, so a plain {"code","fix"} reader works
            value.update({"code": self.code, "fix": self.fix})
        return value


def _bad(code: str, message: str) -> MacError:
    return MacError(400, code, message)


def _clean(value: Any, limit: int = 0) -> str:
    text = _SPACE.sub(" ", _INVISIBLE.sub("", unicodedata.normalize("NFC", str(value or "")))).strip()
    if limit and len(text) > limit:
        text = text[: max(1, limit - 1)].rstrip() + "…"
    return text


def _norm(value: Any) -> str:
    text = _clean(value).lower().replace("’", "'")
    if text.endswith(".app"):
        text = text[:-4]
    return " ".join(_WORD.findall(text))


def _version_key(path: str) -> Tuple[int, int, int]:
    match = _VERSION.search(path)
    return tuple(int(part) for part in match.groups()) if match else (0, 0, 0)  # type: ignore[return-value]


# --------------------------------------------------------------------------- driver


class CuaDriver:
    """Runs ``cua-driver call <tool>`` with the JSON arguments on stdin (never on argv)."""

    def __init__(self, executable: Optional[str] = None, *, timeout: float = DRIVER_TIMEOUT_SECONDS) -> None:
        self._explicit = os.path.expanduser(executable) if executable else None
        self._timeout = timeout
        self._lock = threading.Lock()
        self._found: Optional[Tuple[float, Optional[str]]] = None
        self._version: Optional[Tuple[float, Optional[str], bool]] = None

    def executable(self) -> Optional[str]:
        if self._explicit:
            return self._explicit if os.access(self._explicit, os.X_OK) else None
        with self._lock:
            if self._found and time.monotonic() - self._found[0] < APPS_CACHE_SECONDS:
                return self._found[1]
        found = shutil.which("cua-driver")
        if not found:
            for candidate in DRIVER_CANDIDATES:
                path = os.path.expanduser(candidate)
                if os.access(path, os.X_OK):
                    found = path
                    break
        if not found:
            matches = [path for pattern in DRIVER_GLOBS for path in glob.glob(os.path.expanduser(pattern))
                       if os.access(path, os.X_OK)]
            found = sorted(matches, key=_version_key)[-1] if matches else None
        with self._lock:
            self._found = (time.monotonic(), found)
        return found

    def info(self) -> Tuple[Optional[str], bool]:
        """(version, daemon running), cached."""
        with self._lock:
            if self._version and time.monotonic() - self._version[0] < VERSION_CACHE_SECONDS:
                return self._version[1], self._version[2]
        executable = self.executable()
        version, daemon = None, False
        if executable:
            version_text = self._text([executable, "--version"])
            match = _VERSION.search(version_text or "")
            version = match.group(0) if match else None
            status_text = (self._text([executable, "status"]) or "").lower()
            daemon = "daemon is running" in status_text
        with self._lock:
            self._version = (time.monotonic(), version, daemon)
        return version, daemon

    @staticmethod
    def _text(argv: List[str]) -> Optional[str]:
        try:
            done = subprocess.run(argv, stdin=subprocess.DEVNULL, capture_output=True, timeout=STATUS_TIMEOUT_SECONDS,
                                  check=False)
        except (OSError, subprocess.SubprocessError):
            return None
        return done.stdout.decode("utf-8", errors="replace")[:4000] if done.returncode == 0 else None

    def call(self, tool: str, arguments: Dict[str, Any], *, timeout: Optional[float] = None,
             expect: Sequence[str] = ()) -> Dict[str, Any]:
        executable = self.executable()
        if executable is None:
            raise MacError(503, "driver_missing", "cua-driver is not installed on the Mac.", retryable=False)
        try:
            done = subprocess.run([executable, "call", tool], input=json.dumps(arguments).encode("utf-8"),
                                  capture_output=True, timeout=timeout or self._timeout, check=False)
        except subprocess.TimeoutExpired:
            raise MacError(504, "driver_timeout", "The Mac did not answer in time.", retryable=True) from None
        except OSError:
            raise MacError(503, "driver_missing", "cua-driver could not be started on the Mac.") from None
        if len(done.stdout) > MAX_DRIVER_OUTPUT_BYTES:
            raise MacError(502, "driver_output_too_large", "The Mac sent too much at once.")
        raw = done.stdout.decode("utf-8", errors="replace").strip()
        try:
            value = json.loads(raw) if raw.startswith("{") else None
        except ValueError:
            value = None
        if done.returncode != 0 or not isinstance(value, dict):
            text = (raw + " " + done.stderr.decode("utf-8", errors="replace")).lower()
            if "permission denied" in text or "not granted" in text:
                raise MacError(403, "driver_refused", "cua-driver refused that step.")
            if "daemon" in text or "socket" in text or "connection refused" in text:
                raise MacError(503, "driver_unavailable", "cua-driver is not running on the Mac.", retryable=True)
            raise MacError(502, "driver_failed", "cua-driver could not do that.")
        if expect and all(key in value for key in expect):
            return value
        error = value.get("error")
        code = value.get("code")
        effect = value.get("effect")
        if isinstance(error, str) or effect == "refused" or \
                (isinstance(code, str) and effect is None and _ERROR_CODE.search(code.lower())):
            failure = _driver_error(str(error if isinstance(error, str) else code))
            failure.driver_code = str(error if isinstance(error, str) else code)
            failure.refused = effect == "refused"
            raise failure
        if expect:
            raise MacError(502, "driver_bad_output", "cua-driver answered with something unexpected.")
        return value


def _driver_error(code: str) -> MacError:
    lowered = re.sub(r"[^a-z0-9_]", "", code.lower())[:64] or "failed"
    if "not_installed" in lowered or lowered in ("app_not_found", "no_such_app"):
        return MacError(404, "app_not_found", "That app is not installed on the Mac.")
    if "window" in lowered and ("not_found" in lowered or "mismatch" in lowered):
        return MacError(409, "window_gone", "That window is gone. Look again and retry.", retryable=True)
    if "stale" in lowered or "snapshot" in lowered:
        return MacError(409, "window_changed", "The window changed. Look again and retry.", retryable=True)
    if "permission" in lowered or "screen_recording" in lowered:
        return MacError(409, "permission_required", "The Mac needs a permission for that.")
    if "foreground" in lowered or "background_unavailable" in lowered:
        return MacError(409, "needs_foreground", "That app only accepts this while it is in front.")
    return MacError(502, "driver_" + lowered if not lowered.startswith("driver_") else lowered,
                    "cua-driver could not do that.")


# --------------------------------------------------------------------------- control


class MacControl:
    """The Mac-control half of the bridge. Every public call holds one lock, so a snapshot and
    the click that uses its element token are never interleaved with another request."""

    def __init__(self, driver: CuaDriver, *, open_command: str = "/usr/bin/open",
                 lsappinfo: str = "/usr/bin/lsappinfo", sips: str = "/usr/bin/sips", ioreg: str = IOREG,
                 uid: Optional[int] = None, home: Optional[str] = None, scratch: Optional[str] = None,
                 computer_name: Optional[Callable[[], Optional[str]]] = None) -> None:
        self.driver = driver
        self._open = open_command
        self._lsappinfo = lsappinfo
        self._sips = sips
        self._ioreg = ioreg
        self._uid = os.getuid() if uid is None else uid
        self._home = os.path.realpath(os.path.expanduser(home or "~"))
        self._scratch = scratch or tempfile.mkdtemp(prefix="samrabbit-mac-")
        self._lock = threading.Lock()
        self._apps: Optional[Tuple[float, List[Dict[str, Any]]]] = None
        self._permissions: Optional[Tuple[float, Dict[str, Any]]] = None
        self._computer_name = computer_name or _computer_name
        self._computer: Optional[str] = None

    # ------------------------------------------------------------------ plumbing

    def close(self) -> None:
        shutil.rmtree(self._scratch, ignore_errors=True)

    def _locked(self) -> "_Held":
        if not self._lock.acquire(timeout=LOCK_WAIT_SECONDS):
            raise MacError(503, "mac_busy", "The Mac is busy with another step. Try again in a moment.",
                           retryable=True)
        return _Held(self._lock)

    def fix_command(self) -> str:
        path = self.driver.executable() or "cua-driver"
        return f"Run on the Mac: {path} permissions grant"

    def computer(self) -> Optional[str]:
        if self._computer is None:
            self._computer = _clean(self._computer_name() or "", 80) or ""
        return self._computer or None

    def permissions(self, *, fresh: bool = False) -> Dict[str, Any]:
        """Accessibility / Screen Recording as cua-driver's daemon sees them (read-only, never prompts)."""
        cached = self._permissions
        if cached and not fresh and time.monotonic() - cached[0] < PERMISSION_CACHE_SECONDS:
            return cached[1]
        try:
            value = self.driver.call("check_permissions", {"prompt": False}, timeout=STATUS_TIMEOUT_SECONDS,
                                     expect=("accessibility",))
            result = {"accessibility": value.get("accessibility") is True,
                      "screenRecording": value.get("screen_recording") is True}
        except MacError as error:
            result = {"accessibility": None, "screenRecording": None, "error": error.code}
        self._permissions = (time.monotonic(), result)
        return result

    def screen_locked(self) -> Optional[bool]:
        """True while the Mac's screen is locked (a capture would be black), False when this user's
        desktop is on the screen, None when it cannot be told (``ioreg`` failed, timed out or answered
        oddly): unknown never blocks anything. Read-only, about 25 ms, no driver and no lock."""
        try:
            done = subprocess.run([self._ioreg, "-n", "Root", "-d1", "-a"], stdin=subprocess.DEVNULL,
                                  capture_output=True, timeout=LOCK_CHECK_TIMEOUT_SECONDS, check=False)
        except (OSError, subprocess.SubprocessError):
            return None
        if done.returncode != 0 or not done.stdout or len(done.stdout) > MAX_IOREG_BYTES:
            return None
        try:
            root = plistlib.loads(done.stdout)
        except Exception:  # plistlib raises several types for a malformed archive (expat, ValueError, ...)
            return None
        return screen_locked_from(root, self._uid)

    def capabilities(self) -> Dict[str, Any]:
        """For ``/health``: whether each feature can work right now, and the one fix if vision is off."""
        executable = self.driver.executable()
        version, daemon = self.driver.info() if executable else (None, False)
        permissions = self.permissions() if executable else {"accessibility": None, "screenRecording": None}
        accessibility = permissions.get("accessibility") is True
        screen = permissions.get("screenRecording") is True
        value: Dict[str, Any] = {
            "driver": {"available": executable is not None, "version": version, "daemon": daemon,
                       "path": executable},
            "permissions": {"accessibility": permissions.get("accessibility"),
                            "screenRecording": permissions.get("screenRecording")},
            "features": {"state": accessibility, "open": True, "read": accessibility, "act": accessibility,
                         "screenshot": accessibility and screen},
            "computer": self.computer(),
            "screenLocked": self.screen_locked(),
        }
        if executable and permissions.get("screenRecording") is False:
            value["screenRecordingFix"] = self.fix_command()
        return value

    def _call(self, tool: str, arguments: Dict[str, Any], **options: Any) -> Dict[str, Any]:
        return self.driver.call(tool, arguments, **options)

    def _deliver(self, tool: str, arguments: Dict[str, Any], **options: Any) -> Tuple[Dict[str, Any], str]:
        """Background delivery first; when cua-driver refuses it (nothing was delivered) because the
        keys could reach a sibling window or the app only listens while in front, retry once in the
        foreground. The user asked for this step on the Mac, which is the authorization."""
        try:
            return self._call(tool, arguments, **options), "background"
        except MacError as error:
            code = (error.driver_code or "").lower()
            if not (error.refused and arguments.get("window_id") and
                    ("ambiguity" in code or "background" in code or "foreground" in code)):
                raise
        return self._call(tool, {**arguments, "delivery_mode": "foreground"}, **options), "foreground"

    def _require_accessibility(self) -> None:
        if self.permissions().get("accessibility") is False:
            raise MacError(409, "accessibility_required",
                           "cua-driver has no Accessibility permission on the Mac.",
                           fix=self.fix_command())

    def _regular_apps(self) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
        """(running regular apps, visible windows) from the fast discovery read."""
        tree = self._call("get_accessibility_tree", {}, expect=("apps",))
        apps = [app for app in tree.get("apps") or [] if isinstance(app, dict) and app.get("pid")]
        return apps, [window for window in tree.get("windows") or [] if isinstance(window, dict)]

    def _on_screen(self, pid: Optional[int] = None) -> List[Dict[str, Any]]:
        arguments: Dict[str, Any] = {"pid": pid} if pid else {"on_screen_only": True}
        windows = self._call("list_windows", arguments, expect=("windows",)).get("windows") or []
        result = []
        for window in windows:
            if not isinstance(window, dict) or window.get("layer", 0) != 0:
                continue
            if pid is None and not window.get("is_on_screen", True):
                continue
            if _clean(window.get("app_name")).lower() in _OVERLAY_APPS:
                continue
            bounds = window.get("bounds") if isinstance(window.get("bounds"), dict) else {}
            if float(bounds.get("width") or 0) < 120 or float(bounds.get("height") or 0) < 60:
                continue
            result.append(window)
        result.sort(key=lambda item: item.get("z_index") if isinstance(item.get("z_index"), int) else -1,
                    reverse=True)
        return result

    def _front_pid(self, regular: Dict[int, Dict[str, Any]], windows: List[Dict[str, Any]]) -> Optional[int]:
        pid = self._lsappinfo_front()
        if pid in regular:
            return pid
        for window in windows:  # highest z first
            if int(window.get("pid") or 0) in regular:
                return int(window["pid"])
        return None

    def _lsappinfo_front(self) -> Optional[int]:
        try:
            front = subprocess.run([self._lsappinfo, "front"], stdin=subprocess.DEVNULL, capture_output=True,
                                   timeout=STATUS_TIMEOUT_SECONDS, check=False)
            match = _ASN.search(front.stdout.decode("utf-8", errors="replace"))
            if not match:
                return None
            info = subprocess.run([self._lsappinfo, "info", "-only", "pid", match.group(1)],
                                  stdin=subprocess.DEVNULL, capture_output=True, timeout=STATUS_TIMEOUT_SECONDS,
                                  check=False)
        except (OSError, subprocess.SubprocessError):
            return None
        found = _PID.search(info.stdout.decode("utf-8", errors="replace"))
        return int(found.group(1)) if found else None

    def _window_for(self, pid: int, visible: Optional[List[Dict[str, Any]]] = None) -> Optional[Dict[str, Any]]:
        candidates = [window for window in (visible or []) if int(window.get("pid") or 0) == pid]
        if not candidates:
            candidates = self._on_screen(pid)
        if not candidates:
            return None
        titled = [window for window in candidates if _clean(window.get("title"))]
        on_screen = [window for window in (titled or candidates) if window.get("is_on_screen", True)]
        return (on_screen or titled or candidates)[0]

    def _target(self, app: Any) -> Tuple[int, str, Optional[Dict[str, Any]]]:
        """(pid, app name, front window) for a named running app, or the frontmost app."""
        pid, name, window, _ = self._target_app(app)
        return pid, name, window

    def _target_app(self, app: Any) -> Tuple[int, str, Optional[Dict[str, Any]], Dict[str, Any]]:
        """``_target`` plus the app's own record (bundle id)."""
        apps, _ = self._regular_apps()
        regular = {int(item["pid"]): item for item in apps}
        visible = self._on_screen()
        if isinstance(app, str) and app.strip():
            match = _best_app(app, list(regular.values()))
            if match is None:
                raise MacError(404, "app_not_running", "That app is not open on the Mac.",
                               details={"suggestions": _suggest(app, list(regular.values()))})
            pid = int(match["pid"])
        else:
            front = self._front_pid(regular, visible)
            if front is None:
                raise MacError(409, "no_front_app", "No app is in front on the Mac right now.")
            pid = front
        return pid, _clean(regular[pid].get("name"), 60), self._window_for(pid, visible), regular[pid]

    # ------------------------------------------------------------------ state

    def state(self) -> Dict[str, Any]:
        with self._locked():
            self._require_accessibility()
            apps, _ = self._regular_apps()
            regular = {int(item["pid"]): item for item in apps}
            visible = self._on_screen()
            front_pid = self._front_pid(regular, visible)
            front: Dict[str, Any] = {}
            if front_pid is not None:
                window = self._window_for(front_pid, visible)
                front = {"app": _clean(regular[front_pid].get("name"), 60),
                         "window": _clean(window.get("title"), 120) if window else None}
            shown: List[Dict[str, Any]] = []
            by_pid: Dict[int, Dict[str, Any]] = {}
            for window in visible:
                pid = int(window.get("pid") or 0)
                if pid not in regular:
                    continue
                entry = by_pid.get(pid)
                if entry is None:
                    entry = {"app": _clean(regular[pid].get("name"), 60), "windows": []}
                    by_pid[pid] = entry
                    shown.append(entry)
                title = _clean(window.get("title"), 100)
                if title and len(entry["windows"]) < 3 and title not in entry["windows"]:
                    entry["windows"].append(title)
            result: Dict[str, Any] = {
                "computer": self.computer(),
                "front": front or None,
                "visible": shown[:10],
                "running": sorted({_clean(item.get("name"), 40) for item in apps if _clean(item.get("name"))},
                                  key=str.lower)[:40],
            }
            chrome = self._chrome(regular, visible)
            if chrome:
                result["chrome"] = chrome
            result["screenVision"] = self.permissions().get("screenRecording") is True
            result["screenLocked"] = self.screen_locked()
            return result

    def _chrome(self, regular: Dict[int, Dict[str, Any]], visible: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        pids = {pid for pid, app in regular.items() if app.get("bundle_id") == CHROME_BUNDLE}
        windows = [window for window in visible if int(window.get("pid") or 0) in pids][:2]
        result = []
        for window in windows:
            try:
                snapshot = self._call("get_window_state", {
                    "pid": int(window["pid"]), "window_id": int(window["window_id"]), "include_screenshot": False,
                    "max_depth": 2, "max_elements": 600}, expect=("elements",))
            except MacError:
                continue
            tabs, active, url = [], None, None
            for element in snapshot.get("elements") or []:
                if not isinstance(element, dict) or element.get("in_web_content"):
                    continue
                role = element.get("role")
                if role == "AXRadioButton" and element.get("parent_index") == 0:
                    title = _clean(_TAB_SUFFIX.sub("", _clean(element.get("label"))), 80)
                    if not title:
                        continue
                    if element.get("selected") is True:
                        active = title
                    if len(tabs) < 12:
                        tabs.append(title)
                elif role == "AXTextField" and url is None and "address" in _clean(element.get("label")).lower():
                    url = _short_url(element.get("value"))
            if tabs or active:
                entry: Dict[str, Any] = {"window": _clean(window.get("title"), 100), "activeTab": active,
                                         "tabs": tabs}
                if url:
                    entry["activeUrl"] = url
                result.append(entry)
        return result

    # ------------------------------------------------------------------ open

    def open(self, body: Dict[str, Any]) -> Dict[str, Any]:
        kinds = [key for key in ("app", "url", "path") if body.get(key) not in (None, "")]
        if len(kinds) != 1:
            raise _bad("invalid_open", "Pass exactly one of app, url or path.")
        kind = kinds[0]
        value = body[kind]
        if not isinstance(value, str) or len(value) > MAX_URL_CHARS:
            raise _bad("invalid_open", f"{kind} must be a short string.")
        with self._locked():
            if kind == "url":
                return self._open_url(value.strip())
            if kind == "path":
                return self._open_path(value.strip())
            return self._open_app(value.strip())

    def _launch_services(self, argv: List[str], missing_code: str) -> None:
        try:
            done = subprocess.run([self._open, *argv], stdin=subprocess.DEVNULL, capture_output=True,
                                  timeout=OPEN_TIMEOUT_SECONDS, check=False)
        except subprocess.TimeoutExpired:
            raise MacError(504, "open_timeout", "The Mac did not open it in time.", retryable=True) from None
        except OSError:
            raise MacError(503, "open_unavailable", "The Mac could not open it.") from None
        if done.returncode != 0:
            text = done.stderr.decode("utf-8", errors="replace").lower()
            if "unable to find application" in text or "can't find app" in text:
                raise MacError(404, missing_code, "That app is not installed on the Mac.")
            if "does not exist" in text:
                raise MacError(404, "not_found", "That file or folder does not exist.")
            raise MacError(502, "open_failed", "The Mac could not open it.")

    def _open_url(self, url: str) -> Dict[str, Any]:
        if any(ord(char) < 0x21 or ord(char) == 0x7F for char in url):
            raise _bad("invalid_url", "The link has spaces or control characters.")
        parsed = urlsplit(url)
        if parsed.scheme.lower() not in ("http", "https") or not parsed.hostname:
            raise _bad("invalid_url", "Only http and https links can be opened.")
        self._launch_services(["-a", CHROME_NAME, url], "chrome_missing")
        return {"ok": True, "opened": "url", "app": CHROME_NAME, "host": parsed.hostname,
                "frontmost": self._is_front_bundle(CHROME_BUNDLE)}

    def _open_path(self, raw: str) -> Dict[str, Any]:
        path = self._safe_path(raw)
        self._launch_services([path], "app_not_found")
        return {"ok": True, "opened": "folder" if os.path.isdir(path) else "file",
                "name": _clean(os.path.basename(path), 80)}

    def _safe_path(self, raw: str) -> str:
        if "\x00" in raw:
            raise _bad("invalid_path", "That path is invalid.")
        expanded = self._home + raw[1:] if raw == "~" or raw.startswith("~/") else raw
        if not os.path.isabs(expanded):
            expanded = os.path.join(self._home, expanded)
        real = os.path.realpath(expanded)
        if real != self._home and not real.startswith(self._home + os.sep):
            raise MacError(403, "path_outside_home", "Only files and folders in the home folder can be opened.")
        relative = os.path.relpath(real, self._home)
        parts = [] if relative == "." else relative.split(os.sep)
        if any(part.startswith(".") for part in parts) or (parts and parts[0] == "Library"):
            raise MacError(403, "path_private", "Hidden and Library folders are not opened from the R1.")
        if not os.path.exists(real):
            raise MacError(404, "not_found", "That file or folder does not exist.")
        lowered = real.lower()
        if any(lowered.endswith(suffix) for suffix in _BLOCKED_SUFFIXES):
            raise MacError(403, "path_executable", "Apps and scripts are not opened as files. Open the app by name.")
        if os.path.isfile(real) and os.stat(real).st_mode & (stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH):
            raise MacError(403, "path_executable", "Executable files are not opened from the R1.")
        if os.path.isfile(real) and _is_alias(real):
            raise MacError(403, "path_alias", "Aliases are not opened from the R1. Open the original file instead.")
        return real

    def _installed_apps(self) -> List[Dict[str, Any]]:
        cached = self._apps
        if cached and time.monotonic() - cached[0] < APPS_CACHE_SECONDS:
            return cached[1]
        apps = [item for item in self._call("list_apps", {}, expect=("apps",)).get("apps") or []
                if isinstance(item, dict) and (item.get("bundle_id") or item.get("name"))]
        self._apps = (time.monotonic(), apps)
        return apps

    def _open_app(self, name: str) -> Dict[str, Any]:
        self._require_accessibility()
        running, _ = self._regular_apps()  # fast; list_apps (installed apps) takes about a second
        wanted = _norm(name)
        exact = [app for app in running if _norm(app.get("name")) == wanted or
                 str(app.get("bundle_id") or "").lower() == name.strip().lower()]
        if exact:
            match: Optional[Dict[str, Any]] = {**exact[0], "running": True}
        else:
            installed = self._installed_apps()
            match = _best_app(name, installed)
            if match is None:
                raise MacError(404, "app_not_found", "No app with that name is installed on the Mac.",
                               details={"suggestions": _suggest(name, installed)})
        assert match is not None
        pid = int(match.get("pid") or 0) if match.get("running") else 0
        window: Optional[Dict[str, Any]] = None
        if pid > 0:
            window = self._window_for(pid, self._on_screen())
        else:
            arguments: Dict[str, Any] = {"bundle_id": match["bundle_id"]} if match.get("bundle_id") else \
                {"name": match.get("name")}
            launched = self._call("launch_app", arguments, timeout=30.0, expect=("pid",))
            pid = int(launched.get("pid") or 0)
            if pid <= 0:
                raise MacError(502, "launch_failed", "The app did not start.")
            window = _pick_window([item for item in launched.get("windows") or [] if isinstance(item, dict)])
            deadline = time.monotonic() + 3.0
            while window is None and time.monotonic() < deadline:
                time.sleep(0.4)
                window = _pick_window(self._on_screen(pid))
            self._apps = None  # its running state changed
        front_arguments: Dict[str, Any] = {"pid": pid}
        if window is not None and window.get("window_id"):
            front_arguments["window_id"] = int(window["window_id"])
        verified = False
        try:
            raised = self._call("bring_to_front", front_arguments, timeout=10.0)
            exact_effect = raised.get("exact_window_effect") if isinstance(raised.get("exact_window_effect"), dict) \
                else {}
            verified = raised.get("activated") is True or raised.get("status") == "activated" or \
                exact_effect.get("verified") is True
        except MacError:
            pass
        frontmost = self._lsappinfo_front() == pid
        if not frontmost and match.get("bundle_id"):
            # LaunchServices activation (no TCC): also reopens a window for apps whose windows are closed.
            try:
                self._launch_services(["-b", str(match["bundle_id"])], "app_not_found")
                frontmost = self._lsappinfo_front() == pid
            except MacError:
                pass
        return {"ok": True, "opened": "app", "app": _clean(match.get("name"), 60),
                "wasRunning": bool(match.get("running")), "frontmost": frontmost or verified,
                "window": _clean(window.get("title"), 100) if window else None}

    def _is_front_bundle(self, bundle_id: str) -> Optional[bool]:
        pid = self._lsappinfo_front()
        if pid is None:
            return None
        try:
            apps, _ = self._regular_apps()
        except MacError:
            return None
        return any(int(app.get("pid") or 0) == pid and app.get("bundle_id") == bundle_id for app in apps)

    # ------------------------------------------------------------------ read

    def read(self, app: Optional[str], limit: Optional[int]) -> Dict[str, Any]:
        max_chars = max(200, min(MAX_READ_CHARS, int(limit or DEFAULT_READ_CHARS)))
        with self._locked():
            self._require_accessibility()
            pid, name, window = self._target(app)
            if window is None:
                return {"app": name, "window": None, "text": "", "controls": [],
                        "note": "The app has no open window."}
            snapshot = self._call("get_window_state", {"pid": pid, "window_id": int(window["window_id"]),
                                                       "include_screenshot": False, "max_elements": 1500},
                                  timeout=20.0, expect=("elements",))
            text, controls, truncated = condense(snapshot.get("elements") or [], max_chars)
            result: Dict[str, Any] = {"app": name, "window": _clean(window.get("title"), 120), "text": text,
                                      "controls": controls}
            returned, total = snapshot.get("returned_element_count"), snapshot.get("total_element_count")
            if truncated or (isinstance(returned, int) and isinstance(total, int) and returned < total):
                result["truncated"] = True
            return result

    # ------------------------------------------------------------------ act

    def act(self, body: Dict[str, Any]) -> Dict[str, Any]:
        action = body.get("action")
        if action not in _ACTIONS:
            raise _bad("invalid_action", "action must be one of: " + ", ".join(_ACTIONS) + ".")
        with self._locked():
            self._require_accessibility()
            pid, name, window, record = self._target_app(body.get("app"))
            if action in ("type_text", "hotkey") and _is_terminal(record):
                _refuse_terminal_keys(action, body.get("keys"))
            window_id = int(window["window_id"]) if window and window.get("window_id") else None
            base: Dict[str, Any] = {"pid": pid}
            if window_id is not None:
                base["window_id"] = window_id
            if action == "bring_to_front":
                result = self._call("bring_to_front", base, timeout=10.0)
                return _done(action, name, result, frontmost=self._lsappinfo_front() == pid)
            if action == "hotkey":
                keys = _keys(body.get("keys"))
                if len(keys) == 1:
                    result, delivery = self._deliver("press_key", {**base, "key": keys[0]})
                else:
                    result, delivery = self._deliver("hotkey", {**base, "keys": keys})
                return _done(action, name, result, keys="+".join(keys), delivery=delivery)
            if action == "type_text":
                text = body.get("text")
                if not isinstance(text, str) or not text or len(text) > MAX_TYPE_CHARS:
                    raise _bad("invalid_text", f"text must be 1 to {MAX_TYPE_CHARS} characters.")
                if any(ord(char) < 0x20 and char not in "\n\t" for char in text):
                    raise _bad("invalid_text", "text must not contain control characters.")
                arguments = dict(base)
                if isinstance(body.get("label"), str) and body["label"].strip():
                    element = self._find(pid, window_id, body["label"], "field", body.get("index"))
                    arguments["element_token"] = element["element_token"]
                result, delivery = self._deliver("type_text", {**arguments, "text": text}, timeout=30.0)
                return _done(action, name, result, characters=len(text), delivery=delivery)
            if action == "click":
                label = body.get("label")
                if not isinstance(label, str) or not label.strip() or len(label) > 200:
                    raise _bad("invalid_label", "label must name the button, link or item to click.")
                if window_id is None:
                    raise MacError(409, "no_window", "That app has no open window.")
                element = self._find(pid, window_id, label, body.get("role"), body.get("index"))
                result, delivery = self._deliver("click", {**base, "element_token": element["element_token"]})
                return _done(action, name, result, clicked={"role": _role_name(element.get("role")),
                                                            "label": _clean(_text_of(element), 80)},
                             delivery=delivery)
            if action == "invoke_menu":
                path = body.get("path")
                if isinstance(path, str):
                    path = [part.strip() for part in re.split(r"\s*(?:>|/|→)\s*", path) if part.strip()]
                if not isinstance(path, list) or not 1 <= len(path) <= 6 or \
                        not all(isinstance(part, str) and 0 < len(part.strip()) <= 120 for part in path):
                    raise _bad("invalid_menu", 'path must be menu names, e.g. ["File", "New Window"].')
                path = [part.strip() for part in path]
                if path[0].lower() == "apple" or any(_BLOCKED_MENU.match(part) for part in path):
                    raise MacError(403, "menu_blocked", "System menu items like shut down or log out are not "
                                                        "available from the R1.")
                if window_id is None:
                    raise MacError(409, "no_window", "That app has no open window.")
                result = self._call("invoke_menu", {"pid": pid, "window_id": window_id, "path": path}, timeout=20.0)
                return _done(action, name, result, menu=" > ".join(path))
            direction = body.get("direction")
            if direction not in ("up", "down", "left", "right"):
                raise _bad("invalid_direction", "direction must be up, down, left or right.")
            amount = body.get("amount", 5)
            if not isinstance(amount, int) or isinstance(amount, bool) or not 1 <= amount <= 25:
                raise _bad("invalid_amount", "amount must be 1 to 25.")
            by = "page" if body.get("by") == "page" else "line"
            result, delivery = self._deliver("scroll", {**base, "direction": direction, "amount": amount, "by": by})
            return _done("scroll", name, result, direction=direction, delivery=delivery)

    def _find(self, pid: int, window_id: Optional[int], label: str, role: Any, index: Any) -> Dict[str, Any]:
        if window_id is None:
            raise MacError(409, "no_window", "That app has no open window.")
        wanted = _norm(label)
        if not wanted:
            raise _bad("invalid_label", "label must name the item.")
        roles: Optional[Tuple[str, ...]] = None
        if isinstance(role, str) and role.strip():
            key = role.strip().lower()
            roles = _ROLE_ALIASES.get(key) or ((role.strip(),) if role.strip().startswith("AX") else None)
            if roles is None:
                raise _bad("invalid_role", "role must be button, link, checkbox, tab, field, row, cell, menu item "
                                           "or image.")
        snapshot = self._call("get_window_state", {"pid": pid, "window_id": window_id, "include_screenshot": False,
                                                   "query": label.strip()[:120], "max_elements": 2000},
                              timeout=20.0, expect=("elements",))
        elements = [item for item in snapshot.get("elements") or []
                    if isinstance(item, dict) and item.get("element_token")]
        by_index = {item.get("element_index"): item for item in elements}
        exact: List[Dict[str, Any]] = []
        partial: List[Dict[str, Any]] = []
        for element in elements:
            element_role = str(element.get("role") or "")
            if element_role in _SKIPPED_ROLES and not (roles and element_role in roles):
                continue
            text = _norm(_text_of(element))
            if not text or wanted not in text:
                continue
            target = element
            if element_role in ("AXStaticText", "AXImage") and roles is None:
                parent = by_index.get(element.get("parent_index"))
                if parent and str(parent.get("role")) in _CLICKABLE_ROLES:
                    target = parent  # the label inside a link or button: act on the control
            target_role = str(target.get("role") or "")
            if roles is not None and target_role not in roles:
                continue
            if roles is None and target_role not in _CLICKABLE_ROLES and target_role not in _FIELD_ROLES:
                continue
            bucket = exact if text == wanted else partial
            if all(item.get("element_token") != target.get("element_token") for item in exact + partial):
                bucket.append(target)
        candidates = exact or partial
        if not candidates:
            raise MacError(404, "element_not_found", "Nothing with that label is in the front window.")
        if isinstance(index, int) and not isinstance(index, bool):
            if not 1 <= index <= len(candidates):
                raise _bad("invalid_index", f"index must be 1 to {len(candidates)}.")
            return candidates[index - 1]
        if len(candidates) > 1:
            options = [{"index": number, "role": _role_name(item.get("role")), "label": _clean(_text_of(item), 60)}
                       for number, item in enumerate(candidates[:5], start=1)]
            raise MacError(409, "ambiguous", "Several items match that label. Pass index to pick one.",
                           details={"options": options})
        return candidates[0]

    # ------------------------------------------------------------------ screenshot

    def screenshot(self, app: Optional[str], max_side: Optional[int]) -> Dict[str, Any]:
        side = max(MIN_SCREENSHOT_SIDE, min(MAX_SCREENSHOT_SIDE, int(max_side or DEFAULT_SCREENSHOT_SIDE)))
        with self._locked():
            if self.screen_locked() is True:
                # A locked Mac captures as an all-black picture: say so instead of sending one.
                raise MacError(409, "screen_locked", SCREEN_LOCKED_MESSAGE, retryable=True,
                               details={"screenLocked": True})
            permissions = self.permissions(fresh=True)
            if permissions.get("screenRecording") is not True:
                raise MacError(409, "screen_recording_required",
                               "Screen vision is off: cua-driver does not have Screen Recording permission.",
                               fix=self.fix_command())
            work = tempfile.mkdtemp(prefix="shot-", dir=self._scratch)
            try:
                png = os.path.join(work, "screen.png")
                label = None
                if isinstance(app, str) and app.strip():
                    pid, label, window = self._target(app)
                    if window is None:
                        raise MacError(409, "no_window", "That app has no open window.")
                    self._call("get_window_state", {"pid": pid, "window_id": int(window["window_id"]),
                                                    "screenshot_out_file": png, "max_elements": 1},
                               timeout=20.0)
                else:
                    self._call("get_desktop_state", {"screenshot_out_file": png}, timeout=20.0)
                if not os.path.isfile(png) or os.path.getsize(png) == 0:
                    raise MacError(502, "capture_failed", "The Mac could not take the screenshot.", retryable=True)
                data, width, height = self._jpeg(png, work, side)
            finally:
                shutil.rmtree(work, ignore_errors=True)
            result: Dict[str, Any] = {"mime": "image/jpeg", "base64": base64.b64encode(data).decode("ascii"),
                                      "width": width, "height": height, "bytes": len(data)}
            if label:
                result["app"] = label
            return result

    def _jpeg(self, png: str, work: str, side: int) -> Tuple[bytes, int, int]:
        out = os.path.join(work, "screen.jpg")
        for scale, quality in ((1.0, 70), (1.0, 55), (0.8, 50), (0.65, 45), (0.5, 40)):
            target = max(MIN_SCREENSHOT_SIDE, int(side * scale))
            try:
                subprocess.run([self._sips, "-s", "format", "jpeg", "-s", "formatOptions", str(quality), "-Z",
                                str(target), png, "--out", out], stdin=subprocess.DEVNULL, capture_output=True,
                               timeout=20.0, check=True)
            except (OSError, subprocess.SubprocessError):
                raise MacError(502, "encode_failed", "The Mac could not shrink the screenshot.") from None
            size = os.path.getsize(out) if os.path.isfile(out) else 0
            if 0 < size <= MAX_SCREENSHOT_BYTES:
                with open(out, "rb") as handle:
                    data = handle.read()
                width, height = _jpeg_size(data)
                return data, width, height
        raise MacError(502, "screenshot_too_large", "The screenshot stayed too large to send.")


class _Held:
    def __init__(self, lock: threading.Lock) -> None:
        self._lock = lock

    def __enter__(self) -> "_Held":
        return self

    def __exit__(self, *_: Any) -> None:
        self._lock.release()


# --------------------------------------------------------------------------- pure functions


def _computer_name() -> Optional[str]:
    try:
        done = subprocess.run(["/usr/sbin/scutil", "--get", "ComputerName"], stdin=subprocess.DEVNULL,
                              capture_output=True, timeout=STATUS_TIMEOUT_SECONDS, check=False)
    except (OSError, subprocess.SubprocessError):
        return None
    if done.returncode != 0:
        return None
    return done.stdout.decode("utf-8", errors="replace").strip() or None


def screen_locked_from(root: Any, uid: Optional[int] = None) -> Optional[bool]:
    """Whether ``uid``'s desktop is hidden, from the plist of ``ioreg -n Root -d1 -a``.

    True when the console session's ``CGSSessionScreenIsLocked`` is set (the lock screen), when the
    sessions say none of them is on the console (the login window), or when another user's session is
    (fast user switching): a capture then comes back black. False when this user's session is on the
    console and unlocked (macOS leaves the key out then). None when the data does not say."""
    if isinstance(root, list):  # an archive of several matching entries: the one with the console users
        root = next((item for item in root if isinstance(item, dict) and "IOConsoleUsers" in item), None)
    if not isinstance(root, dict):
        return None
    users = root.get("IOConsoleUsers")
    if not isinstance(users, list):
        return True if root.get("IOConsoleLocked") is True else None
    on_console = [item for item in users if isinstance(item, dict) and item.get("kCGSSessionOnConsoleKey") is True]
    if not on_console:
        if any(isinstance(item, dict) and item.get("kCGSSessionOnConsoleKey") is False for item in users):
            return True  # sessions exist but none is in front: the login window or another user
        return True if root.get("IOConsoleLocked") is True else None
    if uid is not None and any(isinstance(item.get("kCGSSessionUserIDKey"), int) for item in on_console):
        mine = [item for item in on_console if item.get("kCGSSessionUserIDKey") == uid]
        if not mine:
            return True
        on_console = mine
    return on_console[0].get("CGSSessionScreenIsLocked") is True


def _is_alias(path: str) -> bool:
    try:
        with open(path, "rb") as handle:
            return handle.read(len(_ALIAS_MAGIC)) == _ALIAS_MAGIC
    except OSError:
        return False  # unreadable for this user, so LaunchServices (same user) cannot resolve it either


def _short_url(value: Any) -> Optional[str]:
    text = _clean(value)
    if not text:
        return None
    text = text.split("#", 1)[0]
    if "?" in text:
        text = text.split("?", 1)[0] + "?…"
    return _clean(text, 120)


def _pick_window(windows: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    usable = [window for window in windows if isinstance(window, dict) and window.get("window_id")]
    titled = [window for window in usable if _clean(window.get("title"))]
    on_screen = [window for window in (titled or usable) if window.get("is_on_screen")]
    ordered = sorted(on_screen or titled or usable,
                     key=lambda item: item.get("z_index") if isinstance(item.get("z_index"), int) else -1,
                     reverse=True)
    return ordered[0] if ordered else None


def _score(query: str, name: str) -> float:
    if not query or not name:
        return 0.0
    if query == name:
        return 1.0
    query_words, name_words = query.split(), name.split()
    if name.startswith(query + " ") or name.endswith(" " + query) or (" " + query + " ") in (" " + name + " "):
        return 0.92
    if query.startswith(name + " ") and len(name) >= 4:
        return 0.86
    if all(any(word == part or (len(word) >= 3 and part.startswith(word)) for part in name_words)
           for word in query_words):
        return 0.85
    return SequenceMatcher(None, query, name).ratio() * 0.95


def _best_app(query: str, apps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    wanted = _norm(query)
    if not wanted:
        return None
    best: Optional[Tuple[float, int, Dict[str, Any]]] = None
    for app in apps:
        if str(app.get("bundle_id") or "").lower() == query.strip().lower():
            return app
        score = _score(wanted, _norm(app.get("name")))
        rank = (score, 1 if app.get("running") or app.get("pid") else 0)
        if best is None or rank > best[:2]:
            best = (score, rank[1], app)
    if best is None or best[0] < 0.78:
        return None
    return best[2]


def _suggest(query: str, apps: List[Dict[str, Any]]) -> List[str]:
    wanted = _norm(query)
    scored = sorted(((_score(wanted, _norm(app.get("name"))), _clean(app.get("name"), 40)) for app in apps),
                    reverse=True)
    names: List[str] = []
    for score, name in scored:
        if score >= 0.45 and name and name not in names:
            names.append(name)
        if len(names) == 3:
            break
    return names


def _keys(raw: Any) -> List[str]:
    if isinstance(raw, str):
        parts = [part for part in re.split(r"\s*\+\s*|\s+", raw.strip()) if part]
    elif isinstance(raw, list) and all(isinstance(part, str) for part in raw):
        parts = [part.strip() for part in raw if part.strip()]
    else:
        raise _bad("invalid_keys", 'keys must be like ["cmd", "w"] or "cmd+w".')
    if not 1 <= len(parts) <= 5:
        raise _bad("invalid_keys", "Pass up to four modifiers and one key.")
    modifiers: List[str] = []
    key: Optional[str] = None
    for part in parts:
        lowered = part.lower()
        if lowered in _MODIFIERS:
            name = _MODIFIERS[lowered]
            if name not in modifiers:
                modifiers.append(name)
            continue
        if key is not None:
            raise _bad("invalid_keys", "Pass exactly one non-modifier key.")
        lowered = _KEY_ALIASES.get(lowered, lowered)
        if lowered not in _NAMED_KEYS and not (len(lowered) == 1 and lowered.isprintable() and lowered != " "):
            raise _bad("invalid_keys", "That key name is not supported.")
        key = lowered
    if key is None:
        raise _bad("invalid_keys", "Pass a key, not only modifiers.")
    order = ("cmd", "ctrl", "option", "shift", "fn")
    chord = tuple(sorted(modifiers, key=order.index)) + (key,)
    if tuple(sorted(modifiers)) + (key,) in _BLOCKED:
        raise MacError(403, "keys_blocked", "That shortcut (log out, force quit, lock or delete files) is not "
                                            "available from the R1.")
    return list(chord)


def _is_terminal(app: Dict[str, Any]) -> bool:
    bundle = str(app.get("bundle_id") or "").lower()
    return bundle in _TERMINAL_BUNDLES or _norm(app.get("name")) in _TERMINAL_NAMES


def _refuse_terminal_keys(action: str, keys: Any) -> None:
    """In a terminal, typed text and plain keys (return, arrows, ctrl+c, paste) would run or change
    commands. Window shortcuts with cmd (new tab, close, clear) stay available."""
    if action == "hotkey":
        chord = _keys(keys)
        if "cmd" in chord[:-1] and chord[-1] != "v":
            return
    raise MacError(403, "terminal_blocked", "Typing or pressing keys in a terminal is not available from the R1. "
                                            "Hand terminal work to mac_task.")


def _text_of(element: Dict[str, Any]) -> str:
    role = element.get("role")
    label = _clean(element.get("label"))
    value = _clean(element.get("value")) if isinstance(element.get("value"), (str, int, float)) else ""
    if role == "AXStaticText":
        return value or label
    return label or (value if role not in _FIELD_ROLES else "")


def _role_name(role: Any) -> str:
    text = str(role or "")
    return re.sub(r"(?<!^)(?=[A-Z])", " ", text[2:]).lower() if text.startswith("AX") else text.lower()


def _done(action: str, app: str, result: Dict[str, Any], **extra: Any) -> Dict[str, Any]:
    effect = result.get("effect") if isinstance(result, dict) else None
    value: Dict[str, Any] = {"ok": True, "action": action, "app": app, **extra}
    if isinstance(effect, str):
        value["effect"] = effect  # confirmed | unverifiable | partial | suspected_noop
    return value


def condense(elements: List[Any], max_chars: int) -> Tuple[str, List[str], bool]:
    """Readable text for an accessibility snapshot: inline runs (text and links sharing a parent)
    joined into lines, headings marked ``#``, fields as ``[label: value]``; menus and scroll bars
    dropped; repeats removed. Also the labels of the window's buttons and toggles."""
    lines: List[str] = []
    controls: List[str] = []
    run: List[str] = []
    run_parent: Any = object()
    seen_controls = set()
    by_index = {item.get("element_index"): item for item in elements if isinstance(item, dict)}

    def flush() -> None:
        nonlocal run
        if run:
            emit(" ".join(run))
        run = []

    def emit(line: str) -> None:
        line = _clean(line)
        if not line:
            return
        if lines and (line == lines[-1] or (len(line) < 40 and line in lines[-1])):
            return
        lines.append(line)

    for element in elements:
        if not isinstance(element, dict):
            continue
        role = str(element.get("role") or "")
        if role in _SKIPPED_ROLES or role == "AXWindow":
            continue
        text = _text_of(element)
        parent_element = by_index.get(element.get("parent_index"))
        if role == "AXStaticText" and parent_element is not None and text and \
                str(parent_element.get("role")) in (_CONTROL_ROLES | {"AXLink", "AXHeading"}) and \
                _norm(text) in _norm(_text_of(parent_element)):
            continue  # the visible label of a link or button, already taken from the control itself
        if role in _CONTROL_ROLES:
            label = _clean(_TAB_SUFFIX.sub("", text), 40)
            key = label.lower()
            if label and key not in seen_controls and len(controls) < 40:
                seen_controls.add(key)
                controls.append(label)
            continue
        if role in _FIELD_ROLES:
            flush()
            label = _clean(element.get("label"), 60)
            value = _clean(element.get("value"), 300) if role != "AXSecureTextField" else ""
            if "address" in label.lower():
                value = _short_url(value) or ""
            if label or value:
                emit(f"[{label}: {value}]" if label and value else f"[{label or value}]")
            continue
        if role == "AXHeading":
            flush()
            emit("# " + text if text else "")
            continue
        if role in _INLINE_ROLES:
            if not text:
                continue
            parent = element.get("parent_index")
            if parent != run_parent:
                flush()
                run_parent = parent
            if run and (text == run[-1] or (len(text) < 60 and text in run[-1])):
                continue
            run.append(text)
            continue
        if text and role in ("AXCell", "AXRow", "AXImage", "AXGroup", "AXList", "AXOutline", "AXTable"):
            # Containers with their own label (e.g. an image's alt text) as a separate line.
            if role == "AXImage":
                flush()
                emit(f"[image: {_clean(text, 80)}]")
            continue
    flush()
    text = ""
    truncated = False
    for line in lines:
        addition = (("\n" if text else "") + line)
        if len(text) + len(addition) > max_chars:
            room = max_chars - len(text) - 2
            if room > 40:
                text += ("\n" if text else "") + line[:room].rstrip() + "…"
            truncated = True
            break
        text += addition
    return text, controls, truncated


def _jpeg_size(data: bytes) -> Tuple[int, int]:
    """Width and height from a JPEG's SOF marker (0, 0 if unreadable)."""
    index = 2
    while index + 9 < len(data):
        if data[index] != 0xFF:
            index += 1
            continue
        marker = data[index + 1]
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            height = (data[index + 5] << 8) | data[index + 6]
            width = (data[index + 7] << 8) | data[index + 8]
            return width, height
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            index += 2
            continue
        length = (data[index + 2] << 8) | data[index + 3]
        index += 2 + length
    return 0, 0
