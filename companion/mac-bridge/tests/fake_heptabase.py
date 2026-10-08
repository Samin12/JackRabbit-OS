"""Stand-in for the ``heptabase`` CLI used by the bridge tests.

State lives in ``$FAKE_HEPTABASE_DIR``: ``mode`` (ok, down, slow, reject, busy,
http500, garbage), ``slow`` (seconds), ``journal.json`` (date -> appended
markdown), ``docs/<date>.json`` (a ProseMirror doc returned by ``read``), and
``calls.jsonl`` (one record per command).
"""

from __future__ import annotations

import hashlib
import json
import os
import stat
import sys
import time

STATE = os.environ["FAKE_HEPTABASE_DIR"]


def _path(*parts: str) -> str:
    return os.path.join(STATE, *parts)


def _read(name: str, default: str = "") -> str:
    try:
        with open(_path(name), encoding="utf-8") as handle:
            return handle.read().strip()
    except FileNotFoundError:
        return default


def _record(entry: dict) -> None:
    with open(_path("calls.jsonl"), "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")


def _journal() -> dict:
    try:
        with open(_path("journal.json"), encoding="utf-8") as handle:
            return json.load(handle)
    except FileNotFoundError:
        return {}


def _fail(error: object) -> None:
    sys.stderr.write(json.dumps({"error": error}, indent=2) + "\n")
    sys.exit(1)


def main(argv: list) -> None:
    if argv == ["--version"]:
        print("0.7.0")
        return
    mode = _read("mode", "ok")
    started = time.time()
    if mode == "slow":
        time.sleep(float(_read("slow", "2")))
    if mode == "down":
        _fail('Heptabase CLI cannot connect to the desktop app. Run "heptabase start" first (or open Heptabase), '
              "enable CLI in Settings > AI Features, and retry.")
    if mode == "reject":
        _fail({"code": "invalidInput", "message": "Invalid markdown near the secret words", "retryable": False})
    if mode == "busy":
        _fail({"code": "appBusy", "message": "try later", "retryable": True})
    if mode == "http500":
        _fail("HTTP 500")
    if mode == "garbage":
        print("this is not json")
        return
    if argv[:2] == ["journal", "append"]:
        date, flag, path = argv[2], argv[3], argv[4]
        assert flag == "--content-file", argv
        mode_bits = stat.S_IMODE(os.stat(path).st_mode)
        with open(path, encoding="utf-8") as handle:
            content = handle.read()
        journal = _journal()
        journal.setdefault(date, []).append(content)
        with open(_path("journal.json"), "w", encoding="utf-8") as handle:
            json.dump(journal, handle)
        _record({"command": "append", "date": date, "path": path, "mode": mode_bits, "started": started,
                 "ended": time.time()})
        print(json.dumps({"date": date, "title": "Oct 7, 2026",
                          "contentMd5": hashlib.md5("".join(journal[date]).encode()).hexdigest()}, indent=2))
        return
    if argv[:2] == ["journal", "read"]:
        date = argv[2]
        _record({"command": "read", "date": date})
        try:
            with open(_path("docs", date + ".json"), encoding="utf-8") as handle:
                document = json.load(handle)
        except FileNotFoundError:
            paragraphs = [block for item in _journal().get(date, []) for block in item.split("\n\n") if block.strip()]
            document = {"type": "doc", "content": [
                {"type": "paragraph", "attrs": {"id": None},
                 "content": [{"type": "text", "text": text}]} for text in paragraphs
            ] or [{"type": "paragraph", "attrs": {"id": None}}]}
        content = json.dumps(document)
        print(json.dumps({"date": date, "title": "Oct 7, 2026", "content": content,
                          "contentMd5": hashlib.md5(content.encode()).hexdigest()}, indent=2))
        return
    _fail(f"unknown command: {' '.join(argv[:2])}")


if __name__ == "__main__":
    main(sys.argv[1:])
