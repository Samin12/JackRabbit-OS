"""A stand-in for ``realtime/samrabbit_realtime_peer.py`` plus OpenAI's side of the data channel (tests only).

    fake_realtime_peer.py --state <dir> [-I <script>] [--check]

It speaks the helper's protocol (JSON lines on stdin/stdout: ``offer``, ``answer``, ``send``, ``drop_audio``,
``close`` in; ``ready``, ``offer``, ``open``, ``event``, ``audio``, ``audio_end``, ``closed`` out) and answers each
``response.create`` the way OpenAI Realtime does over WebRTC, from ``<dir>/script.json`` (a list, one step per
``response.create``, the last one repeats):

* ``{"say": str, "ms": int, "pace": float}``: ``response.created``, ``output_audio_buffer.started``, transcript
  deltas, ``response.output_audio_transcript.done``, ``response.done``, then ``ms`` of a 440 Hz tone as 100 ms PCM
  frames (``pace`` seconds apart), ``output_audio_buffer.stopped`` and ``audio_end``;
* ``{"verbatim": true, "ms": int}``: says the line quoted in the response's ``instructions`` (announcements);
* ``{"call": name, "arguments": {...}}``: a function call (``response.function_call_arguments.done``, then
  ``response.done`` with the call in its output);
* ``{"fail": "rate_limit_exceeded"}``: ``response.done`` with status failed;
* ``{"die": true}``: the process exits at once (a crashed helper).

``response.cancel`` stops a reply (``response.done`` cancelled), ``output_audio_buffer.clear`` answers
``output_audio_buffer.cleared``. Every client event it got is appended to ``<dir>/received.jsonl`` and every op to
``<dir>/ops.jsonl``; ``--check`` answers ``<dir>/check.json`` (default: ready).
"""

from __future__ import annotations

import base64
from array import array
import json
import math
import os
from pathlib import Path
import sys
import threading
import time
from typing import Any, Dict, List, Optional

LOCK = threading.Lock()


def out(value: Dict[str, Any]) -> None:
    with LOCK:
        sys.stdout.write(json.dumps(value) + "\n")
        sys.stdout.flush()


def tone(ms: int) -> List[bytes]:
    frames = []
    samples = 1600
    for number in range(max(1, ms // 100)):
        values = array("h", [int(8000 * math.sin(2 * math.pi * 440 * (number * samples + index) / 16000))
                             for index in range(samples)])
        frames.append(values.tobytes())
    return frames


class Fake:
    def __init__(self, state: Path) -> None:
        self.state = state
        self.responses = 0
        self.dropped = threading.Event()
        self.cancelled = threading.Event()
        self.speaking: Optional[threading.Thread] = None

    def log(self, name: str, value: Any) -> None:
        with LOCK:
            with (self.state / name).open("a") as handle:
                handle.write(json.dumps(value) + "\n")

    def script(self) -> List[Dict[str, Any]]:
        try:
            steps = json.loads((self.state / "script.json").read_text())
        except (OSError, ValueError):
            steps = []
        return steps if isinstance(steps, list) and steps else [{"say": "Okay.", "ms": 200}]

    def handle(self, event: Dict[str, Any]) -> None:
        self.log("received.jsonl", event)
        kind = event.get("type")
        if kind == "response.create":
            steps = self.script()
            step = steps[min(self.responses, len(steps) - 1)]
            self.responses += 1
            if step.get("die"):
                os._exit(3)
            self.cancelled.clear()
            self.dropped.clear()
            self.speaking = threading.Thread(target=self.respond, args=(self.responses, step, event), daemon=True)
            self.speaking.start()
        elif kind == "response.cancel":
            self.cancelled.set()
        elif kind == "output_audio_buffer.clear":
            out({"ev": "event", "event": {"type": "output_audio_buffer.cleared"}})

    def respond(self, number: int, step: Dict[str, Any], request: Dict[str, Any]) -> None:
        response_id = f"resp_{number}"
        time.sleep(float(step.get("delay") or 0.0))
        out({"ev": "event", "event": {"type": "response.created", "response": {"id": response_id}}})
        if "call" in step:
            call_id = f"call_{number}"
            arguments = json.dumps(step.get("arguments") or {})
            out({"ev": "event", "event": {"type": "response.function_call_arguments.done", "call_id": call_id,
                                          "name": step["call"], "arguments": arguments}})
            out({"ev": "event", "event": {"type": "response.done", "response": {
                "id": response_id, "status": "completed",
                "output": [{"type": "function_call", "call_id": call_id, "name": step["call"],
                            "arguments": arguments}]}}})
            return
        if step.get("fail"):
            out({"ev": "event", "event": {"type": "response.done", "response": {
                "id": response_id, "status": "failed",
                "status_details": {"error": {"type": "invalid_request_error", "code": step["fail"]}}}}})
            return
        text = str(step.get("say") or "")
        if step.get("verbatim"):
            instructions = str((request.get("response") or {}).get("instructions") or "")
            if "“" in instructions:
                text = instructions.split("“", 1)[1].rsplit("”", 1)[0]
        out({"ev": "event", "event": {"type": "output_audio_buffer.started", "response_id": response_id}})
        for index, word in enumerate(text.split(" ")):
            if self.cancelled.is_set():
                break
            out({"ev": "event", "event": {"type": "response.output_audio_transcript.delta",
                                          "delta": (" " if index else "") + word}})
        if self.cancelled.is_set():
            out({"ev": "event", "event": {"type": "response.done", "response": {"id": response_id,
                                                                                "status": "cancelled"}}})
            return
        out({"ev": "event", "event": {"type": "response.output_audio_transcript.done", "transcript": text}})
        out({"ev": "event", "event": {"type": "response.done", "response": {
            "id": response_id, "status": "completed",
            "output": [{"type": "message", "role": "assistant",
                        "content": [{"type": "output_audio", "transcript": text}]}]}}})
        pace = float(step.get("pace") or 0.0)
        for frame in tone(int(step.get("ms") or 300)):
            if self.dropped.is_set() or self.cancelled.is_set():
                break
            out({"ev": "audio", "pcm": base64.b64encode(frame).decode("ascii")})
            if pace:
                time.sleep(pace)
        if not self.dropped.is_set():
            out({"ev": "event", "event": {"type": "output_audio_buffer.stopped", "response_id": response_id}})
            out({"ev": "audio_end"})


def main() -> int:
    args = sys.argv[1:]
    state = Path(args[args.index("--state") + 1])
    if "--check" in args:
        try:
            value = json.loads((state / "check.json").read_text())
        except (OSError, ValueError):
            value = {"ok": True, "python": "3.12.15", "aiortc": "1.15.0", "av": "17.1.0"}
        print(json.dumps(value))
        return 0 if value.get("ok") else 1
    fake = Fake(state)
    with (state / "pids.txt").open("a") as handle:
        handle.write(f"{os.getpid()}\n")
    out({"ev": "ready"})
    for raw in sys.stdin:
        try:
            message = json.loads(raw)
        except ValueError:
            continue
        op = message.get("op")
        fake.log("ops.jsonl", {"op": op, **({"sdp": message.get("sdp")} if op == "answer" else {})})
        if op == "offer":
            out({"ev": "offer", "sdp": "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=fake offer\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"})
        elif op == "answer":
            out({"ev": "open"})
            out({"ev": "event", "event": {"type": "session.created", "session": {"id": "sess_fake"}}})
        elif op == "send":
            fake.handle(message.get("event") or {})
        elif op == "drop_audio":
            fake.dropped.set()
            out({"ev": "audio_end"})
        elif op == "close":
            break
    out({"ev": "closed"})
    return 0


if __name__ == "__main__":
    sys.exit(main())
