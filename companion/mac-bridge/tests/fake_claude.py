"""Stand-in for the headless Claude Code CLI (``claude -p ... --output-format json``) used by test_genui.

Invoked as ``fake_claude.py --state <dir> <claude args...>`` (a shell wrapper adds ``--state``; the bridge
runs children with a clean environment). Each call appends ``{args, cwd, cwdEntries, stdin}`` to
``<dir>/calls.jsonl``. ``<dir>/modes`` holds a comma-separated list of behaviours consumed one per call (the
last one repeats): ok, text, fenced, bad, slow, busy, signed_out, garbage, throws.
"""

from __future__ import annotations

import json
import os
import sys
import time

WIDGET = {
    "title": "Meetings this week",
    "summary": "Thursday is the busiest day with 6 meetings.",
    "initialHeight": 420,
    "css": ".hd .t{font-size:20px;font-weight:600}",
    "html": "<div class=\"hd\"><div class=\"t\">Meetings this week</div></div><div id=\"bars\">Mon 3</div>",
    "jsFunctions": "function mark(){ document.getElementById('bars').dataset.ok = '1'; }",
    "jsExpressions": ["mark();"],
}


def main() -> int:
    state = sys.argv[2]
    args = sys.argv[3:]
    stdin = sys.stdin.read()
    calls_file = os.path.join(state, "calls.jsonl")
    count = 0
    if os.path.exists(calls_file):
        with open(calls_file) as handle:
            count = sum(1 for _ in handle)
    with open(calls_file, "a") as handle:
        handle.write(json.dumps({"args": args, "cwd": os.getcwd(), "cwdEntries": sorted(os.listdir(".")),
                                 "stdin": stdin, "envKeys": sorted(os.environ)}) + "\n")
    modes = "ok"
    try:
        with open(os.path.join(state, "modes")) as handle:
            modes = handle.read().strip() or "ok"
    except OSError:
        pass
    sequence = [item.strip() for item in modes.split(",") if item.strip()]
    mode = sequence[min(count, len(sequence) - 1)]
    envelope = {"type": "result", "subtype": "success", "is_error": False, "num_turns": 2,
                "duration_ms": 10, "result": "", "session_id": "fake"}
    if mode == "ok":
        envelope["structured_output"] = WIDGET
        envelope["result"] = json.dumps(WIDGET)
    elif mode == "text":
        envelope["result"] = "Here it is:\n" + json.dumps(WIDGET)
    elif mode == "fenced":
        envelope["result"] = "```json\n" + json.dumps(WIDGET) + "\n```"
    elif mode == "bad":
        envelope["structured_output"] = dict(WIDGET, html="", jsExpressions=[])
    elif mode == "throws":
        envelope["structured_output"] = dict(WIDGET, jsExpressions=["boom();"])
    elif mode == "slow":
        time.sleep(30)
        envelope["structured_output"] = WIDGET
    elif mode == "busy":
        envelope.update({"is_error": True, "subtype": "error_during_execution", "api_error_status": 529,
                         "result": "Overloaded"})
    elif mode == "signed_out":
        envelope.update({"is_error": True, "result": "Not logged in. Please run /login"})
    elif mode == "garbage":
        sys.stdout.write("this is not json")
        return 1
    sys.stdout.write(json.dumps(envelope))
    return 0


if __name__ == "__main__":
    sys.exit(main())
