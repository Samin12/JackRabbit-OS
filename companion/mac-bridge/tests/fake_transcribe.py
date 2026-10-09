"""A stand-in for the ``samrabbit-transcribe`` Swift helper (tests only).

    fake_transcribe.py --state <dir> (--file <audio> | --check | --prepare | --version) [--locale ..] [--max-seconds ..]
                       [--deadline ..]

Behaviour comes from files in the state folder (all optional):

* ``mode``: what ``--file`` answers: ``ok`` (default; the words in ``text``), ``permission``, ``unavailable``,
  ``model_missing`` (becomes ``ok`` once ``--prepare`` ran), ``insufficient``, ``failed``, ``no_speech``,
  ``too_long``, ``language``, ``audio``, ``crash`` (exit 134, no output), ``garbage``, ``slow`` (``delay`` s,
  default 1.5, then ok),
  ``hang`` (30 s, ignoring ``--deadline``: a stuck helper), ``deadline`` (what the real helper answers when its own
  ``--deadline`` passes: ``transcribe_timeout``, exit 9), ``spawn_hang`` (starts a child process, in the helper's
  process group, writes the child's pid to ``child.pid``, then hangs: only a kill of the whole group stops both),
  ``orphan`` (starts such a child, which keeps stdout open, and exits at once without an answer);
* ``check``: what ``--check`` answers: ``available`` (default), ``model_missing``, ``speech_unavailable``,
  ``permission`` (the model is there but Speech Recognition is denied: ``permission: denied``), ``deadline``,
  ``garbage``, ``hang``;
* ``prepare``: ``ok`` (default), ``fail``, ``slow`` (2 s, then ok).

Every call is appended to ``calls.jsonl``: the arguments, the audio file's size, sha256 and permissions (never its
bytes), its folder's permissions, and which environment variables were passed.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import time


def read(state: Path, name: str, default: str) -> str:
    path = state / name
    return path.read_text().strip() if path.exists() else default


def say(value: dict, status: int = 0) -> None:
    sys.stdout.write(json.dumps(value) + "\n")
    sys.stdout.flush()
    sys.exit(status)


def main(argv: list) -> None:
    state = Path(argv[argv.index("--state") + 1])
    args = argv[argv.index("--state") + 2:]
    record = {"args": args, "cwd": os.getcwd(), "env": sorted(os.environ)}
    locale = args[args.index("--locale") + 1] if "--locale" in args else "en-US"
    if "--file" in args:
        path = Path(args[args.index("--file") + 1])
        data = path.read_bytes()
        record.update({"file": str(path), "size": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                       "fileMode": stat.S_IMODE(path.stat().st_mode),
                       "dirMode": stat.S_IMODE(path.parent.stat().st_mode)})
    with (state / "calls.jsonl").open("a") as handle:
        handle.write(json.dumps(record) + "\n")

    if "--version" in args:
        say({"ok": True, "version": "1"})
    if "--check" in args:
        check = read(state, "check", "available")
        if check == "hang":
            time.sleep(30)
        if check == "garbage":
            sys.stdout.write("not json\n")
            sys.exit(0)
        if check == "speech_unavailable":
            say({"ok": True, "available": False, "model": "unknown", "reason": "speech_unavailable"})
        if check == "deadline":
            say({"ok": False, "code": "transcribe_timeout", "message": "out of time", "detail": "8000ms"}, 9)
        if check == "permission":  # as the real helper before it learnt to say so itself: available, but denied
            say({"ok": True, "available": True, "engine": "SpeechTranscriber", "locale": locale, "model": "installed",
                 "permission": "denied"})
        missing = check == "model_missing" and not (state / "prepared").exists()
        say({"ok": True, "available": not missing, "engine": "SpeechTranscriber", "locale": locale,
             "model": "missing" if missing else "installed", **({"reason": "model_missing"} if missing else {})})
    if "--prepare" in args:
        prepare = read(state, "prepare", "ok")
        if prepare == "slow":
            time.sleep(2)
        if prepare == "fail":
            say({"ok": False, "code": "transcribe_unavailable", "message": "no network", "detail": "model_missing"}, 3)
        (state / "prepared").write_text("yes")
        say({"ok": True, "engine": "SpeechTranscriber", "locale": locale, "model": "installed", "downloaded": True})

    mode = read(state, "mode", "ok")
    if mode == "model_missing" and (state / "prepared").exists():
        mode = "ok"
    if mode == "hang":
        time.sleep(30)
    if mode in ("spawn_hang", "orphan"):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"], stdin=subprocess.DEVNULL)
        (state / "child.pid").write_text(str(child.pid))
        if mode == "orphan":
            os._exit(0)  # no answer; the child still holds stdout
        time.sleep(30)
    if mode == "deadline":
        say({"ok": False, "code": "transcribe_timeout", "message": "out of time", "detail": "40000ms"}, 9)
    if mode == "slow":
        time.sleep(float(read(state, "delay", "1.5")))
        mode = "ok"
    if mode == "crash":
        sys.exit(134)
    if mode == "garbage":
        sys.stdout.write("Traceback: something\n")
        sys.exit(1)
    failures = {
        "permission": ("transcribe_permission", "SFSpeechErrorDomain#1700", 4),
        "unavailable": ("transcribe_unavailable", "speech_unavailable", 3),
        "model_missing": ("transcribe_unavailable", "model_missing", 3),
        "insufficient": ("transcribe_unavailable", "insufficient_resources", 3),
        "failed": ("transcribe_failed", "SFSpeechErrorDomain#1 secret words here", 1),
        "no_speech": ("no_speech", "3000ms", 8),
        "too_long": ("audio_too_long", "95000ms", 7),
        "language": ("unsupported_language", None, 5),
        "audio": ("unsupported_audio", "com.apple.coreaudio.avfaudio#1954115647", 6),
    }
    if mode in failures:
        code, detail, status = failures[mode]
        say({"ok": False, "code": code, "message": "helper message", **({"detail": detail} if detail else {})},
            status)
    say({"ok": True, "text": read(state, "text", "Move the dentist to Friday."), "durationMs": 6358,
         "engine": "SpeechTranscriber", "locale": locale, "elapsedMs": 210})


if __name__ == "__main__":
    main(sys.argv)
