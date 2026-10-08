"""Stand-ins for ``cua-driver``, ``/usr/bin/open`` and ``/usr/bin/lsappinfo`` used by the Mac-control tests.

One script, three personalities (``FAKE_CUA_ROLE`` = driver | open | lsappinfo). State lives in
``$FAKE_CUA_DIR/state.json``; every invocation is appended to ``calls.jsonl`` with start/end times
so the tests can check that driver calls never overlap.

state.json keys: ``permissions`` {accessibility, screen_recording}, ``apps`` (running regular apps for
get_accessibility_tree), ``installed`` (list_apps), ``windows`` (list_windows records), ``elements``
({window_id: [element, ...]}), ``front_pid``, ``launch`` ({bundle_id: {pid, windows}}), ``slow``
({tool: seconds}), ``open_rc``/``open_stderr``, ``missing_daemon``.
"""

from __future__ import annotations

import json
import os
import random
import struct
import sys
import time
import zlib

STATE_DIR = os.environ["FAKE_CUA_DIR"]
ROLE = os.environ.get("FAKE_CUA_ROLE", "driver")


def _state() -> dict:
    with open(os.path.join(STATE_DIR, "state.json"), encoding="utf-8") as handle:
        return json.load(handle)


def _save(state: dict) -> None:
    path = os.path.join(STATE_DIR, "state.json")
    with open(path + ".tmp", "w", encoding="utf-8") as handle:
        json.dump(state, handle)
    os.replace(path + ".tmp", path)


def _record(entry: dict) -> None:
    with open(os.path.join(STATE_DIR, "calls.jsonl"), "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")


def _png(path: str, width: int, height: int) -> None:
    """A noisy RGB PNG (compresses badly, so the bridge's JPEG shrinking loop has work to do)."""
    rng = random.Random(7)
    rows = bytearray()
    for _ in range(height):
        rows.append(0)
        rows += rng.randbytes(width * 3)
    def chunk(kind: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + \
        chunk(b"IDAT", zlib.compress(bytes(rows), 6)) + chunk(b"IEND", b"")
    with open(path, "wb") as handle:
        handle.write(data)


def _driver(argv: list) -> int:
    state = _state()
    if argv[:1] == ["--version"]:
        print("cua-driver 9.8.7")
        return 0
    if argv[:1] == ["status"]:
        print("Cua Driver daemon is not running" if state.get("missing_daemon") else "Cua Driver daemon is running")
        return 0
    if len(argv) != 2 or argv[0] != "call":
        print("usage", file=sys.stderr)
        return 2
    tool = argv[1]
    raw = sys.stdin.read()
    args = json.loads(raw or "{}")
    started = time.time()
    time.sleep(float((state.get("slow") or {}).get(tool, 0)))
    result = _tool(tool, args, state)
    _record({"role": "driver", "tool": tool, "args": args, "start": started, "end": time.time()})
    if isinstance(result, str):
        print(result)
    else:
        print(json.dumps(result, indent=2))
    return 0


def _tool(tool: str, args: dict, state: dict):
    windows = state.get("windows", [])
    if tool == "check_permissions":
        permissions = state.get("permissions", {})
        return {"accessibility": permissions.get("accessibility", True),
                "screen_recording": permissions.get("screen_recording", False),
                "screen_recording_capturable": None, "direct_capture_status": "not_checked"}
    if tool == "get_accessibility_tree":
        return {"apps": state.get("apps", []),
                "windows": [{k: w[k] for k in ("app_name", "pid", "title", "window_id")} for w in windows]}
    if tool == "list_apps":
        return {"apps": state.get("installed", [])}
    if tool == "list_windows":
        if "pid" in args:
            return {"windows": [w for w in windows if w["pid"] == args["pid"]]}
        return {"current_space_id": 1, "windows": [w for w in windows if w.get("is_on_screen", True)]}
    if tool == "get_window_state":
        window_id = args.get("window_id")
        if not any(w["window_id"] == window_id and w["pid"] == args.get("pid") for w in windows):
            return {"code": "window_id_not_found", "pid": args.get("pid"), "window_id": window_id}
        elements = state.get("elements", {}).get(str(window_id), [])
        depth = args.get("max_depth")
        if depth is not None:
            elements = [e for e in elements if e.get("depth", 0) <= depth]
        query = (args.get("query") or "").lower()
        if query:
            keep = {e["element_index"] for e in elements
                    if query in (str(e.get("role")) + " " + str(e.get("label") or "") + " " +
                                 str(e.get("value") or "")).lower()}
            by_index = {e["element_index"]: e for e in elements}
            for index in list(keep):
                parent = by_index.get(index, {}).get("parent_index")
                while parent is not None and parent not in keep:
                    keep.add(parent)
                    parent = by_index.get(parent, {}).get("parent_index")
            elements = [e for e in elements if e["element_index"] in keep]
        if args.get("screenshot_out_file"):
            _png(args["screenshot_out_file"], 1400, 900)
        return {"pid": args.get("pid"), "window_id": window_id, "snapshot_id": "s00000042",
                "elements": [{**e, "element_token": f"s00000042:{e['element_index']}"} for e in elements],
                "elements_complete": True, "tree_markdown": "(omitted)"}
    if tool == "get_desktop_state":
        if not state.get("permissions", {}).get("screen_recording"):
            return {"code": "screen_recording_permission_missing"}
        _png(args["screenshot_out_file"], 2400, 1500)
        return {"screenshot_file_path": args["screenshot_out_file"], "width": 2400, "height": 1500}
    if tool == "launch_app":
        launch = state.get("launch", {}).get(args.get("bundle_id") or args.get("name") or "")
        if launch is None:
            return {"error": "APP_NOT_INSTALLED", "name": args.get("name")}
        return {"pid": launch["pid"], "bundle_id": args.get("bundle_id"), "windows": launch.get("windows", []),
                "launch_state": "window_ready"}
    if tool == "bring_to_front":
        if not any(a["pid"] == args.get("pid") for a in state.get("apps", [])):
            return {"code": "window_target_not_found", "effect": "refused", "pid": args.get("pid")}
        state["front_pid"] = args["pid"]
        _save(state)
        return {"effect": "confirmed", "pid": args["pid"], "window_id": args.get("window_id")}
    if tool in ("click", "hotkey", "press_key", "type_text", "invoke_menu", "scroll"):
        if "pid" not in args:
            return "Missing required integer field: pid"
        token = args.get("element_token")
        if token is not None and not str(token).startswith("s00000042:"):
            return {"code": "stale_element_token", "effect": "refused"}
        return {"effect": "unverifiable" if tool == "type_text" else "confirmed", "route": "accessibility"}
    return f"Permission denied: tool '{tool}' has no reviewed risk classification"


def _open(argv: list) -> int:
    state = _state()
    _record({"role": "open", "argv": argv, "start": time.time(), "end": time.time()})
    if argv[:1] == ["-b"]:
        for app in state.get("apps", []):
            if app.get("bundle_id") == argv[1]:
                state["front_pid"] = app["pid"]
                _save(state)
    sys.stderr.write(state.get("open_stderr", ""))
    return int(state.get("open_rc", 0))


def _lsappinfo(argv: list) -> int:
    state = _state()
    if argv[:1] == ["front"]:
        print("ASN:0x0-0x1234:")
        return 0
    if argv[:3] == ["info", "-only", "pid"]:
        print(f'"Something" ASN:0x0-0x1234: (in front)\n    pid = {state.get("front_pid", 0)} type="Foreground"')
        return 0
    return 1


if __name__ == "__main__":
    sys.exit({"driver": _driver, "open": _open, "lsappinfo": _lsappinfo}[ROLE](sys.argv[1:]))
