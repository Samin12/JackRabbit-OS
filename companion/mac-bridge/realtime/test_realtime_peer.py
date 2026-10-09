"""The realtime peer helper end to end, with a local aiortc peer standing in for OpenAI Realtime (no network, no
OpenAI, no tokens).

Run it with the realtime venv's Python (it needs aiortc and av), from anywhere:

    <venv>/bin/python -m unittest discover -s companion/mac-bridge/realtime -p 'test_*.py' -v

``HelperTest`` drives ``samrabbit_realtime_peer.py`` over its stdin/stdout protocol the way the bridge does and checks
the WebRTC side against ``FakeOpenAI``: an answerer with an ``oai-events`` data channel that answers
``response.create`` with canned events (transcript deltas, a function call, ``output_audio_buffer.*``) and a 440 Hz
tone on its audio track while it "speaks". ``BridgeEndToEndTest`` runs the real Mac bridge (the system Python 3.9,
a dev copy with temp tokens and fake T3 / calendar / journal) with this helper, a fake signaling server that hands
the offer to ``FakeOpenAI``, and a fake ChatGPT login file, and checks a whole streamed watch turn, a tool call, an
announcement and a cancel.
"""

from __future__ import annotations

import asyncio
import base64
from array import array
import fractions
import json
import math
import os
from pathlib import Path
import queue
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, List, Optional, Tuple

HERE = Path(__file__).resolve().parent
BRIDGE_DIR = HERE.parent
HELPER = HERE / "samrabbit_realtime_peer.py"

import av  # noqa: E402
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription  # noqa: E402
from aiortc.mediastreams import AudioStreamTrack  # noqa: E402

TONE_HZ = 440.0
TONE_LEVEL = 9000


class ToneTrack(AudioStreamTrack):
    """48 kHz mono: a 440 Hz tone while ``on`` (the fake model is speaking), silence otherwise; real-time paced."""

    def __init__(self) -> None:
        super().__init__()
        self.on = False
        self._phase = 0
        self._start: Optional[float] = None
        self._timestamp = 0

    async def recv(self) -> Any:
        rate, samples = 48_000, 960
        if self._start is None:
            self._start = time.time()
        else:
            self._timestamp += samples
            wait = self._start + self._timestamp / rate - time.time()
            if wait > 0:
                await asyncio.sleep(wait)
        values = array("h", [0] * samples)
        if self.on:
            for index in range(samples):
                values[index] = int(TONE_LEVEL * math.sin(2 * math.pi * TONE_HZ * (self._phase + index) / rate))
        self._phase += samples
        frame = av.AudioFrame(format="s16", layout="mono", samples=samples)
        frame.planes[0].update(values.tobytes())
        frame.pts = self._timestamp
        frame.sample_rate = rate
        frame.time_base = fractions.Fraction(1, rate)
        return frame


class FakeOpenAI:
    """An aiortc answerer standing in for OpenAI Realtime's WebRTC side. ``script``: what each ``response.create``
    does, in order (the last repeats): ``{"say": str, "ms": int}`` speaks (transcript deltas + the tone for ``ms``),
    ``{"call": name, "arguments": {...}}`` asks for a function call, ``{"say": str, "ms": int, "verbatim": True}``
    speaks the ``instructions`` it was given (announcements). Everything the client sent is in ``received``."""

    def __init__(self, loop: asyncio.AbstractEventLoop) -> None:
        self.loop = loop
        self.script: List[Dict[str, Any]] = [{"say": "Hello there.", "ms": 800}]
        self.received: List[Dict[str, Any]] = []
        self.pcs: List[Any] = []
        self.track: Optional[ToneTrack] = None
        self.dc: Any = None
        self.responses = 0
        self.speaking_task: Optional[asyncio.Task] = None
        self.offers: List[str] = []

    async def answer(self, offer: str) -> str:
        self.offers.append(offer)
        pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        self.pcs.append(pc)
        self.track = ToneTrack()

        @pc.on("datachannel")
        def _channel(channel: Any) -> None:
            self.dc = channel

            @channel.on("message")
            def _message(message: Any) -> None:
                event = json.loads(message)
                self.received.append(event)
                self.loop.create_task(self.handle(event))

        await pc.setRemoteDescription(RTCSessionDescription(sdp=offer, type="offer"))
        pc.addTrack(self.track)
        answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        return pc.localDescription.sdp

    def emit(self, event: Dict[str, Any]) -> None:
        if self.dc is not None and self.dc.readyState == "open":
            self.dc.send(json.dumps(event))

    async def handle(self, event: Dict[str, Any]) -> None:
        kind = event.get("type")
        if kind == "response.create":
            step = self.script[min(self.responses, len(self.script) - 1)]
            self.responses += 1
            response_id = f"resp_{self.responses}"
            self.emit({"type": "response.created", "response": {"id": response_id, "status": "in_progress"}})
            if "call" in step:
                call_id = f"call_{self.responses}"
                arguments = json.dumps(step.get("arguments") or {})
                self.emit({"type": "response.function_call_arguments.done", "response_id": response_id,
                           "call_id": call_id, "name": step["call"], "arguments": arguments})
                self.emit({"type": "response.done", "response": {"id": response_id, "status": "completed", "output": [
                    {"type": "function_call", "call_id": call_id, "name": step["call"], "arguments": arguments}]}})
                return
            text = step.get("say") or ""
            if step.get("verbatim"):
                instructions = str((event.get("response") or {}).get("instructions") or "")
                text = instructions.split("“", 1)[-1].rsplit("”", 1)[0] if "“" in instructions else text
            self.speaking_task = self.loop.create_task(self.speak(response_id, text, int(step.get("ms") or 800)))
        elif kind == "response.cancel":
            if self.speaking_task is not None and not self.speaking_task.done():
                self.speaking_task.cancel()
        elif kind == "output_audio_buffer.clear":
            if self.track is not None:
                self.track.on = False
            self.emit({"type": "output_audio_buffer.cleared"})

    async def speak(self, response_id: str, text: str, ms: int) -> None:
        assert self.track is not None
        try:
            self.emit({"type": "output_audio_buffer.started", "response_id": response_id})
            self.track.on = True
            words = text.split(" ")
            for index, word in enumerate(words):
                self.emit({"type": "response.output_audio_transcript.delta", "response_id": response_id,
                           "delta": (" " if index else "") + word})
                await asyncio.sleep(0.02)
            self.emit({"type": "response.output_audio_transcript.done", "response_id": response_id, "transcript": text})
            self.emit({"type": "response.done", "response": {"id": response_id, "status": "completed", "output": [
                {"type": "message", "role": "assistant", "content": [{"type": "output_audio", "transcript": text}]}]}})
            await asyncio.sleep(ms / 1000.0)
            self.track.on = False
            self.emit({"type": "output_audio_buffer.stopped", "response_id": response_id})
        except asyncio.CancelledError:
            self.track.on = False
            self.emit({"type": "response.done", "response": {"id": response_id, "status": "cancelled", "output": []}})
            raise

    async def close(self) -> None:
        for pc in self.pcs:
            await pc.close()


class LoopThread:
    """An asyncio loop on its own thread (the fake OpenAI lives there)."""

    def __init__(self) -> None:
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self.loop.run_forever, daemon=True)
        self.thread.start()

    def run(self, coroutine: Any, timeout: float = 30.0) -> Any:
        return asyncio.run_coroutine_threadsafe(coroutine, self.loop).result(timeout)

    def stop(self) -> None:
        self.loop.call_soon_threadsafe(self.loop.stop)
        self.thread.join(timeout=5)
        if not self.loop.is_running():
            self.loop.close()


class HelperProcess:
    """The helper as the bridge runs it: ``-I``, a minimal environment, JSON lines both ways."""

    def __init__(self) -> None:
        env = {"PATH": "/usr/bin:/bin", "HOME": os.environ.get("HOME", "/tmp"), "LANG": "en_US.UTF-8"}
        self.process = subprocess.Popen([sys.executable, "-I", str(HELPER)], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
                                        cwd=tempfile.gettempdir())
        self.messages: "queue.Queue[Optional[Dict[str, Any]]]" = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self) -> None:
        assert self.process.stdout is not None
        for raw in iter(self.process.stdout.readline, b""):
            self.messages.put(json.loads(raw))
        self.messages.put(None)

    def send(self, value: Dict[str, Any]) -> None:
        assert self.process.stdin is not None
        self.process.stdin.write((json.dumps(value) + "\n").encode())
        self.process.stdin.flush()

    def wait_for(self, ev: str, timeout: float = 20.0, collect: Optional[List[Dict[str, Any]]] = None) -> Dict[str, Any]:
        deadline = time.monotonic() + timeout
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                raise AssertionError(f"no {ev} from the helper")
            message = self.messages.get(timeout=left)
            if message is None:
                raise AssertionError(f"the helper exited before {ev}")
            if collect is not None:
                collect.append(message)
            if message.get("ev") == ev:
                return message

    def close(self, *, keep_stderr: bool = False) -> int:
        try:
            self.send({"op": "close"})
        except (OSError, ValueError):
            pass
        try:
            return self.process.wait(timeout=10)
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=5)
            for stream in (self.process.stdin, self.process.stdout,
                           None if keep_stderr else self.process.stderr):
                try:
                    if stream is not None:
                        stream.close()
                except OSError:
                    pass


def pcm_of(messages: List[Dict[str, Any]]) -> bytes:
    return b"".join(base64.b64decode(item["pcm"]) for item in messages if item.get("ev") == "audio")


def loudest(data: bytes) -> int:
    samples = array("h")
    samples.frombytes(data[: len(data) - len(data) % 2])
    return max((abs(value) for value in samples), default=0)


class HelperTest(unittest.TestCase):
    def setUp(self) -> None:
        self.loop = LoopThread()
        self.addCleanup(self.loop.stop)
        self.fake = FakeOpenAI(self.loop.loop)
        self.addCleanup(lambda: self.loop.run(self.fake.close()))
        self.helper = HelperProcess()
        self.addCleanup(self.helper.close)

    def connect(self) -> None:
        self.helper.wait_for("ready")
        self.helper.send({"op": "offer"})
        offer = self.helper.wait_for("offer")["sdp"]
        self.assertTrue(offer.startswith("v=0"))
        self.assertIn("m=audio", offer)
        self.assertIn("m=application", offer, "the oai-events data channel is in the offer")
        self.assertIn("a=sendrecv", offer, "an outbound (silent) audio track, like the R1")
        self.assertIn("a=candidate", offer, "ICE is gathered before the offer is handed over")
        answer = self.loop.run(self.fake.answer(offer))
        self.helper.send({"op": "answer", "sdp": answer})
        self.helper.wait_for("open")

    def test_check(self) -> None:
        done = subprocess.run([sys.executable, "-I", str(HELPER), "--check"], capture_output=True, text=True,
                              timeout=60)
        value = json.loads(done.stdout)
        self.assertEqual((0, True), (done.returncode, value["ok"]))
        self.assertTrue(value["aiortc"] and value["av"])

    def test_a_spoken_reply_streams_as_16k_pcm_between_the_buffer_events(self) -> None:
        self.connect()
        self.fake.script = [{"say": "Two tasks need you.", "ms": 1200}]
        self.helper.send({"op": "send", "event": {"type": "conversation.item.create", "item": {
            "type": "message", "role": "user", "content": [{"type": "input_text", "text": "what needs me"}]}}})
        self.helper.send({"op": "send", "event": {"type": "response.create"}})
        collected: List[Dict[str, Any]] = []
        self.helper.wait_for("audio_end", timeout=20, collect=collected)
        kinds = [item["event"]["type"] for item in collected if item.get("ev") == "event"]
        self.assertIn("response.created", kinds)
        self.assertIn("response.output_audio_transcript.done", kinds)
        self.assertIn("output_audio_buffer.stopped", kinds)
        deltas = "".join(item["event"]["delta"] for item in collected if item.get("ev") == "event" and
                         item["event"]["type"] == "response.output_audio_transcript.delta")
        self.assertEqual("Two tasks need you.", deltas)
        pcm = pcm_of(collected)
        seconds = len(pcm) / 32000
        self.assertGreater(seconds, 0.8, "about the 1.2 s the fake spoke")
        self.assertLess(seconds, 2.2, "only while it spoke (plus a short pre-roll and tail)")
        self.assertGreater(loudest(pcm), 3000, "the tone came through, resampled to 16 kHz")
        sizes = [len(base64.b64decode(item["pcm"])) for item in collected if item.get("ev") == "audio"]
        self.assertTrue(all(size == 3200 for size in sizes[:-1]), "100 ms frames")
        self.assertEqual([{"type": "conversation.item.create", "item": {
            "type": "message", "role": "user", "content": [{"type": "input_text", "text": "what needs me"}]}},
            {"type": "response.create"}], self.fake.received)
        # Between replies nothing is forwarded.
        time.sleep(0.6)
        idle = []
        while not self.helper.messages.empty():
            idle.append(self.helper.messages.get())
        self.assertEqual([], [item for item in idle if item.get("ev") == "audio"])

    def test_a_function_call_then_the_answer(self) -> None:
        self.connect()
        self.fake.script = [{"call": "t3_list_threads", "arguments": {"filter": "needs-you"}},
                            {"say": "Nothing needs you.", "ms": 500}]
        self.helper.send({"op": "send", "event": {"type": "response.create"}})
        collected: List[Dict[str, Any]] = []
        self.helper.wait_for("event", collect=collected)
        while not any(item.get("ev") == "event" and item["event"]["type"] == "response.done" for item in collected):
            collected.append(self.helper.wait_for("event"))
        done = [item["event"] for item in collected if item.get("ev") == "event" and
                item["event"]["type"] == "response.function_call_arguments.done"]
        self.assertEqual([("t3_list_threads", {"filter": "needs-you"})],
                         [(item["name"], json.loads(item["arguments"])) for item in done])
        self.helper.send({"op": "send", "event": {"type": "conversation.item.create", "item": {
            "type": "function_call_output", "call_id": done[0]["call_id"], "output": "{\"threads\":[]}"}}})
        self.helper.send({"op": "send", "event": {"type": "response.create"}})
        rest: List[Dict[str, Any]] = []
        self.helper.wait_for("audio_end", collect=rest)
        self.assertGreater(len(pcm_of(rest)), 6400)
        self.assertEqual("function_call_output", self.fake.received[1]["item"]["type"])

    def test_drop_audio_stops_forwarding_at_once(self) -> None:
        self.connect()
        self.fake.script = [{"say": "This is a long answer that keeps going.", "ms": 4000}]
        self.helper.send({"op": "send", "event": {"type": "response.create"}})
        first = self.helper.wait_for("audio", timeout=15)
        self.assertTrue(first["pcm"])
        self.helper.send({"op": "drop_audio"})
        self.helper.send({"op": "send", "event": {"type": "response.cancel"}})
        self.helper.send({"op": "send", "event": {"type": "output_audio_buffer.clear"}})
        collected: List[Dict[str, Any]] = []
        self.helper.wait_for("audio_end", timeout=5, collect=collected)
        time.sleep(0.8)
        while not self.helper.messages.empty():
            collected.append(self.helper.messages.get())
        after_end = collected[[item.get("ev") for item in collected].index("audio_end") + 1:]
        self.assertEqual([], [item for item in after_end if item.get("ev") == "audio"], "nothing after the drop")
        self.assertIn("output_audio_buffer.cleared", [item["event"]["type"] for item in collected
                                                      if item.get("ev") == "event"])

    def test_close_and_end_of_input_end_the_helper(self) -> None:
        self.connect()
        self.assertEqual(0, self.helper.close())
        other = HelperProcess()
        other.wait_for("ready")
        assert other.process.stdin is not None
        other.process.stdin.close()
        self.assertEqual(0, other.process.wait(timeout=10), "stdin closed (the bridge died): the helper ends")
        other.close()

    def test_nothing_is_written_to_stderr(self) -> None:
        self.connect()
        self.fake.script = [{"say": "secret words 4471", "ms": 300}]
        self.helper.send({"op": "send", "event": {"type": "response.create"}})
        self.helper.wait_for("audio_end")
        stderr = self.helper.process.stderr
        self.helper.close(keep_stderr=True)
        assert stderr is not None
        self.assertEqual(b"", stderr.read())
        stderr.close()


def _free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _jwt(claims: Dict[str, Any]) -> str:
    def part(value: Dict[str, Any]) -> str:
        return base64.urlsafe_b64encode(json.dumps(value).encode()).decode().rstrip("=")
    return part({"alg": "none"}) + "." + part(claims) + ".sig"


ACCESS = "e2e-access-token-" + "q" * 40
SPOKEN = "Hello from the fake model."
UTTERANCE = "what needs me about the secret 5512 plan"


class FakeCalls:
    """``POST /v1/realtime/calls`` (multipart sdp + session): hands the offer to ``FakeOpenAI`` and answers its SDP."""

    def __init__(self, loop: LoopThread, fake: FakeOpenAI) -> None:
        self.sessions: List[Dict[str, Any]] = []
        self.headers: List[Dict[str, str]] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args: Any) -> None:
                return

            def do_POST(self) -> None:  # noqa: N802
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                boundary = str(self.headers.get("Content-Type")).split("boundary=", 1)[1]
                fields: Dict[str, str] = {}
                for chunk in raw.split(("--" + boundary).encode()):
                    chunk = chunk.strip(b"\r\n")
                    if chunk and chunk != b"--":
                        head, _, value = chunk.partition(b"\r\n\r\n")
                        fields[head.decode().split('name="', 1)[1].split('"', 1)[0]] = value.decode()
                outer.sessions.append(json.loads(fields["session"]))
                outer.headers.append({"authorization": str(self.headers.get("Authorization")),
                                      "safety": str(self.headers.get("OpenAI-Safety-Identifier"))})
                answer = loop.run(fake.answer(fields["sdp"])).encode()
                self.send_response(201)
                self.send_header("Content-Type", "application/sdp")
                self.send_header("Content-Length", str(len(answer)))
                self.end_headers()
                self.wfile.write(answer)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}/v1"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


@unittest.skipUnless(Path("/usr/bin/python3").exists(), "needs the macOS system Python for the bridge")
class BridgeEndToEndTest(unittest.TestCase):
    """The real bridge (a dev copy, temp HOME and tokens, journal dry run, no T3, no calendar) with this helper."""

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        config = root / ".config" / "samrabbit"
        config.mkdir(parents=True, mode=0o700)
        self.desktop = "e2e-desktop-token-" + "d" * 32
        bridge_token = "e2e-bridge-token-" + "b" * 32
        for name, value in (("desktop-token", self.desktop), ("bridge-token", bridge_token)):
            (config / name).write_text(value + "\n")
            (config / name).chmod(0o600)
        auth = config / "chatgpt-auth.json"
        auth.write_text(json.dumps({"version": 1, "tokens": {
            "access_token": ACCESS, "refresh_token": "e2e-refresh-" + "r" * 30,
            "id_token": _jwt({"email": "e2e@example.com", "https://api.openai.com/auth": {
                "chatgpt_plan_type": "pro", "chatgpt_account_id": "acct-e2e"}})},
            "expiresAt": int(time.time()) + 3600, "account": {"plan": "pro", "accountId": "acct-e2e"}}))
        auth.chmod(0o600)
        self.loop = LoopThread()
        self.addCleanup(self.loop.stop)
        self.fake = FakeOpenAI(self.loop.loop)
        self.addCleanup(lambda: self.loop.run(self.fake.close()))
        self.calls = FakeCalls(self.loop, self.fake)
        self.addCleanup(self.calls.close)
        self.port = _free_port()
        self.log_path = root / "bridge.log"
        self.log = self.log_path.open("wb")
        env = {"HOME": str(root), "PATH": "/usr/bin:/bin", "LANG": "en_US.UTF-8", "TMPDIR": tempfile.gettempdir()}
        self.bridge = subprocess.Popen([
            "/usr/bin/python3", "-I", str(BRIDGE_DIR / "samrabbit_bridge.py"), "--host", "127.0.0.1",
            "--port", str(self.port), "--token-file", str(config / "bridge-token"),
            "--desktop-token-file", str(config / "desktop-token"),
            "--mobile-devices-file", str(config / "mobile-devices.json"), "--sync-dir", str(root / "sync"),
            "--cli", "dry-run", "--cua-driver", "/nonexistent/cua-driver", "--artifacts-dir", str(root / "artifacts"),
            "--assistant-dir", str(root / "assistant"), "--chatgpt-auth-file", str(auth),
            "--realtime-python", sys.executable, "--realtime-api-url", self.calls.url],
            stdin=subprocess.DEVNULL, stdout=self.log, stderr=self.log, env=env, cwd=str(root))
        self.addCleanup(self._stop)
        deadline = time.monotonic() + 30
        while True:
            try:
                status, _ = self.json("GET", "/health", headers={"Authorization": "Bearer " + bridge_token})
                if status == 200:
                    break
            except OSError:
                pass
            if time.monotonic() > deadline or self.bridge.poll() is not None:
                self.fail("the bridge did not start: " + self.log_path.read_text()[-2000:])
            time.sleep(0.2)
        status, start = self.json("POST", "/v1/mobile/pairing/start", {}, headers={"X-SamRabbit-Desktop": self.desktop})
        self.assertEqual(200, status, start)
        status, paired = self.json("POST", "/v1/mobile/pair", {"code": start["code"], "deviceName": "E2E Watch",
                                                               "platform": "watchos"})
        self.assertEqual(200, status, paired)
        self.watch = paired["token"]

    def _stop(self) -> None:
        if self.bridge.poll() is None:
            self.bridge.terminate()
            try:
                self.bridge.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.bridge.kill()
        self.log.close()

    def json(self, method: str, path: str, body: Any = None, *, headers: Optional[Dict[str, str]] = None,
             token: Optional[str] = None) -> Tuple[int, Any]:
        merged = {"Content-Type": "application/json", **(headers or {})}
        if token:
            merged["Authorization"] = "Bearer " + token
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        request = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}",
                                         data=json.dumps(body).encode() if body is not None else None, method=method,
                                         headers=merged)
        try:
            with opener.open(request, timeout=60) as response:
                return response.status, json.loads(response.read() or b"{}")
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read() or b"{}")

    def stream(self, body: Dict[str, Any], on_frame: Any = None) -> List[Tuple[str, bytes]]:
        import http.client

        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=60)
        connection.request("POST", "/v1/mobile/assistant/turn", body=json.dumps(body).encode(), headers={
            "Authorization": "Bearer " + self.watch, "Accept": "application/x-samrabbit-stream",
            "Content-Type": "application/json"})
        response = connection.getresponse()
        self.assertEqual(200, response.status)
        self.assertEqual("application/x-samrabbit-stream", response.getheader("Content-Type"))
        self.assertEqual("chunked", response.getheader("Transfer-Encoding"))
        frames: List[Tuple[str, bytes]] = []
        while True:
            head = response.read(5)
            if len(head) < 5:
                break
            payload = response.read(struct.unpack(">I", head[1:5])[0])
            frames.append((chr(head[0]), payload))
            if on_frame is not None:
                on_frame(chr(head[0]), payload)
        connection.close()
        return frames

    @staticmethod
    def events(frames: List[Tuple[str, bytes]]) -> List[Dict[str, Any]]:
        return [json.loads(payload) for kind, payload in frames if kind == "J"]

    def test_watch_turns_through_real_webrtc(self) -> None:
        import uuid

        # 1. A spoken answer, streamed while the fake model speaks.
        self.fake.script = [{"say": SPOKEN, "ms": 1000}]
        frames = self.stream({"text": UTTERANCE, "turnId": str(uuid.uuid4())})
        events = self.events(frames)
        self.assertEqual({"type": "heard", "text": UTTERANCE}, events[0])
        self.assertEqual(SPOKEN, "".join(event["text"] for event in events if event["type"] == "say.delta"))
        done = events[-1]
        self.assertEqual(("done", "realtime", False), (done["type"], done["brain"], done["endConversation"]))
        conversation = done["conversationId"]
        pcm = b"".join(payload for kind, payload in frames if kind == "A")
        self.assertGreater(len(pcm) / 32000, 0.7, "about the second the fake spoke, as 16 kHz PCM")
        self.assertLess(len(pcm) / 32000, 2.0)
        self.assertGreater(loudest(pcm), 3000, "the tone, through WebRTC, Opus and the resampler")
        self.assertIsInstance(done["timings"]["firstAudio"], int)
        session = self.calls.sessions[0]
        self.assertEqual(("gpt-realtime-2.1", "marin", None), (session["model"], session["audio"]["output"]["voice"],
                                                                session["audio"]["input"]["turn_detection"]))
        self.assertTrue(session["instructions"].startswith("You are SamRabbit Voice. Be concise, natural, and helpful."))
        self.assertEqual("Bearer " + ACCESS, self.calls.headers[0]["authorization"])
        self.assertEqual("input_audio_buffer.clear", self.fake.received[0]["type"], "manual turns: emptied first")
        item = self.fake.received[1]["item"]
        self.assertTrue(item["content"][0]["text"].startswith("[Now: "))
        self.assertTrue(item["content"][0]["text"].endswith(UTTERANCE))
        # 2. A tool: a card on the watch, then the answer.
        self.fake.script = [{"call": "show_card", "arguments": {"title": "Next up", "lines": ["Dentist 3 PM"]}},
                            {"say": "Here is what is next.", "ms": 400}]
        self.fake.responses = 0
        events = self.events(self.stream({"text": "what is next", "turnId": str(uuid.uuid4()),
                                          "conversationId": conversation}))
        self.assertEqual([{"type": "card", "title": "Next up", "body": "Dentist 3 PM"}],
                         [event for event in events if event["type"] == "card"])
        outputs = [event for event in self.fake.received if event.get("type") == "conversation.item.create" and
                   event["item"]["type"] == "function_call_output"]
        self.assertEqual({"shown": True, "on": "Apple Watch"}, json.loads(outputs[-1]["item"]["output"]))
        # 3. Tap to interrupt.
        self.fake.script = [{"say": "A very long answer that keeps going and going.", "ms": 8000}]
        self.fake.responses = 0
        cancelled: List[Any] = []

        def on_frame(kind: str, _payload: bytes) -> None:
            if kind == "A" and not cancelled:
                cancelled.append(self.json("POST", "/v1/mobile/assistant/cancel", {"conversationId": conversation},
                                           token=self.watch))

        started = time.monotonic()
        frames = self.stream({"text": "tell me a story", "turnId": str(uuid.uuid4()),
                              "conversationId": conversation}, on_frame)
        self.assertLess(time.monotonic() - started, 6.0, "cut short")
        self.assertEqual((200, {"ok": True, "cancelled": True}), cancelled[0])
        self.assertTrue(self.events(frames)[-1].get("interrupted"))
        kinds = [event.get("type") for event in self.fake.received]
        self.assertIn("output_audio_buffer.clear", kinds)
        # 4. The buffered JSON answer (old clients): WAV audio.
        self.fake.script = [{"say": "Okay.", "ms": 500}]
        self.fake.responses = 0
        time.sleep(0.5)
        status, value = self.json("POST", "/v1/mobile/assistant/turn", {"text": "okay", "turnId": str(uuid.uuid4()),
                                                                        "conversationId": conversation},
                                  token=self.watch)
        self.assertEqual((200, "realtime", "audio/wav"), (status, value["brain"], value["audio"]["mime"]))
        self.assertEqual(1, len(self.calls.sessions), "one WebRTC session for the whole conversation")
        # 5. The end closes the session; the log never has words, tokens or audio.
        self.assertEqual((200, {"ok": True, "ended": True}),
                         self.json("POST", "/v1/mobile/assistant/end", {"conversationId": conversation},
                                   token=self.watch))
        time.sleep(1.0)
        log = self.log_path.read_text()
        self.assertIn("realtime session open", log)
        self.assertIn("realtime session closed", log)
        for secret in (UTTERANCE, "5512", SPOKEN, ACCESS, self.watch, self.desktop, "Dentist", "story"):
            self.assertNotIn(secret, log)
        if os.environ.get("SAMRABBIT_E2E_SHOW_LOG"):
            print("\n" + log)

    def test_the_bridge_leaves_no_helper_behind(self) -> None:
        import uuid

        self.fake.script = [{"say": "Hi.", "ms": 300}]
        self.stream({"text": "hi", "turnId": str(uuid.uuid4())})
        self._stop()
        time.sleep(1.5)
        listing = subprocess.run(["/bin/ps", "-axo", "pid,command"], capture_output=True, text=True).stdout
        mine = [line for line in listing.splitlines() if str(HELPER) in line and "--check" not in line]
        self.assertEqual([], mine, "the helper ends with the bridge")


if __name__ == "__main__":
    unittest.main()
