"""Stand-in for the T3 CLI's ``auth pairing create ... --json`` (the bridge runs it to pair with T3 Code).

Invoked as ``fake_t3_cli.py --state <dir> auth pairing create --label <l> --ttl <t> --base-url <u> --json``.
Writes the new one-time credential to ``<dir>/credentials.json`` (``{credential: "unused"}``, which the fake T3
server consumes), appends ``{argv, env}`` to ``<dir>/cli-calls.jsonl`` and prints pretty JSON like the real CLI.
``<dir>/cli-mode``: ok (default), fail (exit 1), garbage (no JSON).
"""

from __future__ import annotations

import json
import os
import random
import sys

ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"


def main() -> int:
    state = sys.argv[2]
    argv = sys.argv[3:]
    with open(os.path.join(state, "cli-calls.jsonl"), "a") as handle:
        handle.write(json.dumps({"argv": argv, "env": {key: os.environ.get(key) for key in
                                                       ("ELECTRON_RUN_AS_NODE", "HOME", "PATH")},
                                 "envKeys": sorted(os.environ)}) + "\n")
    try:
        with open(os.path.join(state, "cli-mode")) as handle:
            mode = handle.read().strip() or "ok"
    except OSError:
        mode = "ok"
    if mode == "fail":
        sys.stderr.write("Error: could not open the T3 database\n")
        return 1
    if mode == "garbage":
        print("pairing link created")
        return 0
    if argv[:3] != ["auth", "pairing", "create"] or "--json" not in argv:
        sys.stderr.write("usage: auth pairing create --label <l> --ttl <t> --base-url <u> --json\n")
        return 2
    credential = "".join(random.choice(ALPHABET) for _ in range(12))
    path = os.path.join(state, "credentials.json")
    try:
        with open(path) as handle:
            credentials = json.load(handle)
    except (OSError, ValueError):
        credentials = {}
    credentials[credential] = "unused"
    with open(path, "w") as handle:
        json.dump(credentials, handle)
    label = argv[argv.index("--label") + 1] if "--label" in argv else None
    base = argv[argv.index("--base-url") + 1] if "--base-url" in argv else "http://127.0.0.1:3773"
    print(json.dumps({"id": "00000000-0000-4000-8000-000000000001", "credential": credential, "label": label,
                      "scopes": ["orchestration:read", "orchestration:operate"],
                      "expiresAt": "2026-10-08T13:10:00.000Z", "pairUrl": f"{base}/pair#token={credential}"},
                     indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
