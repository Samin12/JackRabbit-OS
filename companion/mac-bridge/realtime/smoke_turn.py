#!/usr/bin/env python3
"""One live watch turn against the running bridge, to check the realtime voice end to end (the integrator's smoke
test). Nothing is played: the reply's audio is only counted.

    /usr/bin/python3 -I companion/mac-bridge/realtime/smoke_turn.py [--port 3780]
        [--desktop-token-file ~/.config/samrabbit/desktop-token] [--text "..."] [--allow-claude]

It pairs a temporary device ("SamRabbit smoke test", with the desktop token over loopback), warms up a conversation,
sends one streamed text turn (``Accept: application/x-samrabbit-stream``), ends the conversation and revokes the
device again (always, even after a failure). It prints only the brain, the frame and event counts, the seconds of
audio and the timings: never the tokens, the words or the audio. Exit 0 when the realtime brain answered with audio
(or any brain with ``--allow-claude``). The turn is recorded in the conversation store like any watch turn. Stdlib
only, Python 3.9.
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
import uuid
from typing import Any, Dict, List, Optional, Tuple

DEFAULT_TEXT = "This is a quick test from the Mac. Please answer with one short sentence."
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # loopback: never a system proxy


def call(port: int, method: str, path: str, body: Any = None, headers: Optional[Dict[str, str]] = None,
         timeout: float = 60.0) -> Tuple[int, Dict[str, Any]]:
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            return response.status, json.loads(response.read() or b"{}")
    except urllib.error.HTTPError as error:
        try:
            return error.code, json.loads(error.read() or b"{}")
        except ValueError:
            return error.code, {}


def stream_turn(port: int, token: str, body: Dict[str, Any]) -> Tuple[int, List[Tuple[str, bytes]], Dict[str, str]]:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=120)
    connection.request("POST", "/v1/mobile/assistant/turn", body=json.dumps(body).encode(), headers={
        "Authorization": "Bearer " + token, "Accept": "application/x-samrabbit-stream",
        "Content-Type": "application/json"})
    response = connection.getresponse()
    headers = {key.lower(): value for key, value in response.getheaders()}
    frames: List[Tuple[str, bytes]] = []
    if response.status == 200 and headers.get("content-type", "").startswith("application/x-samrabbit-stream"):
        while True:
            head = response.read(5)
            if len(head) < 5:
                break
            frames.append((chr(head[0]), response.read(struct.unpack(">I", head[1:5])[0])))
    else:
        response.read()
    connection.close()
    return response.status, frames, headers


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="One live streamed watch turn against the running bridge.")
    parser.add_argument("--port", type=int, default=int(os.environ.get("SAMRABBIT_BRIDGE_PORT", "3780")))
    parser.add_argument("--desktop-token-file", default="~/.config/samrabbit/desktop-token")
    parser.add_argument("--text", default=DEFAULT_TEXT)
    parser.add_argument("--allow-claude", action="store_true", help="a Claude answer counts as a pass too")
    options = parser.parse_args(argv)
    try:
        with open(os.path.expanduser(options.desktop_token_file), "r", encoding="utf-8") as handle:
            desktop = {"X-SamRabbit-Desktop": handle.read().strip()}
    except OSError:
        print("no desktop token; run companion/mac-bridge/install.sh first", file=sys.stderr)
        return 2
    port = options.port
    try:
        status, start = call(port, "POST", "/v1/mobile/pairing/start", {}, desktop)
    except OSError:
        print(f"the bridge is not answering on port {port}", file=sys.stderr)
        return 1
    if status != 200:
        print(f"pairing refused (HTTP {status})", file=sys.stderr)
        return 1
    status, paired = call(port, "POST", "/v1/mobile/pair", {"code": start.get("code"),
                                                            "deviceName": "SamRabbit smoke test",
                                                            "platform": "watchos"})
    if status != 200:
        print(f"pairing failed (HTTP {status})", file=sys.stderr)
        return 1
    token, device = str(paired.get("token") or ""), str(paired.get("deviceId") or "")
    auth = {"Authorization": "Bearer " + token}
    ok = False
    try:
        status, summary = call(port, "GET", "/v1/mobile/summary", headers=auth)
        part = summary.get("assistant") or {}
        print(f"summary: assistant available={part.get('available')} brain={part.get('brain')} "
              f"model={part.get('model')} chatgpt connected={(part.get('chatgpt') or {}).get('connected')}"
              f"{' reason=' + str(part.get('reason')) if part.get('reason') else ''}")
        status, session = call(port, "POST", "/v1/mobile/assistant/session", {}, auth)
        if status != 200:
            print(f"warm-up refused (HTTP {status}, {(session.get('error') or {}).get('code')})")
            return 1
        conversation = str(session.get("conversationId") or "")
        print(f"warm-up: brain={session.get('brain')} ready={session.get('ready')}")
        time.sleep(1.5)  # let the session open, as the watch's warm-up does
        started = time.monotonic()
        status, frames, headers = stream_turn(port, token, {"text": options.text, "turnId": str(uuid.uuid4()),
                                                            "conversationId": conversation})
        if status != 200 or not frames:
            print(f"turn failed (HTTP {status}, {headers.get('content-type')})")
            return 1
        events = [json.loads(payload) for kind, payload in frames if kind == "J"]
        audio = sum(len(payload) for kind, payload in frames if kind == "A")
        kinds: Dict[str, int] = {}
        for event in events:
            kinds[str(event.get("type"))] = kinds.get(str(event.get("type")), 0) + 1
        done = events[-1] if events and events[-1].get("type") == "done" else {}
        errors = [event.get("code") for event in events if event.get("type") == "error"]
        timings = done.get("timings") or {}
        print("events: " + ", ".join(f"{name}×{count}" for name, count in kinds.items()))
        print(f"audio: {audio / 32000:.1f} s of PCM16 16 kHz in {sum(1 for kind, _ in frames if kind == 'A')} frames")
        print(f"done: brain={done.get('brain')} expectReply={done.get('expectReply')} "
              f"interrupted={bool(done.get('interrupted'))} timings stt={timings.get('stt')} ms "
              f"firstAudio={timings.get('firstAudio')} ms total={timings.get('total')} ms "
              f"(wall {int((time.monotonic() - started) * 1000)} ms)")
        if errors:
            print("errors: " + ", ".join(str(code) for code in errors))
        ok = bool(done) and not errors and (done.get("brain") == "realtime" and audio > 0 or
                                            (options.allow_claude and done.get("brain") == "claude"))
        call(port, "POST", "/v1/mobile/assistant/end", {"conversationId": conversation}, auth)
    finally:
        status, _ = call(port, "DELETE", f"/v1/mobile/devices/{device}", headers=desktop)
        print(f"temporary device revoked: {status == 200}")
    print("smoke test: " + ("ok" if ok else "FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
