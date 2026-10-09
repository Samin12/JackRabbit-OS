#!/usr/bin/env python3
"""SamRabbit's realtime peer: the Mac's WebRTC connection to OpenAI Realtime for one watch conversation.

The Apple Watch cannot do WebRTC and a ChatGPT subscription token cannot open a realtime WebSocket, so the Mac is
the WebRTC peer, exactly like the R1 (``NativeVoicePeer.java``): one ``RTCPeerConnection`` with no ICE servers,
an outbound audio track (silence here: the Mac sends the watch's words as text) and the ``oai-events`` data
channel. The Mac bridge (``samrabbit_realtime.py``, the system Python 3.9) runs one of these per conversation with
the realtime venv's Python (3.12+, aiortc and av pinned in ``requirements.txt``; ``install.sh`` makes the venv) and
talks to it over stdin/stdout, one JSON object per line:

    bridge -> peer   {"op": "offer"}                     make the offer (ICE gathered)
                     {"op": "answer", "sdp": "..."}      OpenAI's answer (the bridge does the signaling itself:
                                                         the access token never reaches this process)
                     {"op": "send", "event": {...}}      one client event on the data channel
                     {"op": "drop_audio"}                stop forwarding the reply's audio now (barge-in)
                     {"op": "close"}
    peer -> bridge   {"ev": "ready"} | {"ev": "offer", "sdp"} | {"ev": "open"} (data channel open)
                     {"ev": "event", "event": {...}}     every server event from the data channel
                     {"ev": "audio", "pcm": "<base64>"}  the reply's audio: PCM16LE, 16 kHz, mono, ~100 ms each
                     {"ev": "audio_end"}                 the reply's audio is all forwarded
                     {"ev": "state", "state": "connected" | "failed" | "closed" | ...}
                     {"ev": "error", "code": "..."} | {"ev": "closed"}

The reply's audio arrives as the remote WebRTC track (Opus, real time). Only what the server marks as playing
(``output_audio_buffer.started`` until ``.stopped`` / ``.cleared``, with a 300 ms pre-roll and a short tail) is
forwarded, resampled to 16 kHz mono. The pre-roll only ever holds the last half second of the response being made:
it is emptied when a response is created and when the buffer is cleared, and after ``drop_audio`` nothing is kept
until the next response, so a cut-off or earlier reply is never replayed in front of the next one. Likewise, when the
server sends no RTP between replies, aiortc's jitter buffer still holds the last four packets of the earlier reply
and hands them over as the next one starts: those frames are dropped.

The data channel advertises an SCTP max-message-size of 256 KiB (aiortc says 64 KiB; the R1's libwebrtc 256 KiB), so
OpenAI may send events over 64 KiB (the echo of a screenshot item, a long session) instead of dropping them.

Nothing is logged: no audio, no text, no events (stderr stays quiet; the bridge discards it anyway). The process ends
when stdin closes. ``--check`` prints ``{"ok", "python", "aiortc", "av", "sctpMaxMessage"}`` for install.sh and the
bridge's health.
"""

from __future__ import annotations

import asyncio
import base64
from array import array
import json
import logging
import os
import platform
import sys
import threading
import time
from collections import deque
from typing import Any, Deque, Dict, Optional, Tuple

MIN_PYTHON = (3, 12)
OUT_RATE = 16_000
FRAME_BYTES = 3_200  # 100 ms of 16 kHz mono PCM16
PREROLL_BYTES = 9_600  # 300 ms kept while nothing is playing
PREROLL_MAX_AGE = 0.5  # seconds: older pre-roll audio is never played (it belongs to an earlier reply)
SCTP_MAX_MESSAGE = 262_144  # what the data channel advertises (libwebrtc's, the R1's; the bridge sends <= 240 KiB)
JITTER_HELD_FRAMES = 4  # aiortc's audio jitter buffer (prefetch=4) keeps the last four packets when RTP stops
RTP_PAUSE_SECONDS = 0.25  # no frame for this long: the sender paused (between replies)
TAIL_SECONDS = 0.3  # RTP still in flight after output_audio_buffer.stopped
QUIET_LEVEL = 200  # |sample| below this is silence (for trimming and the fallback end)
QUIET_END_BYTES = 19_200  # 600 ms of silence ends a reply when the server sends no buffer events
MAX_LINE = 8 * 1024 * 1024


def _write(value: Dict[str, Any]) -> None:
    sys.stdout.write(json.dumps(value, separators=(",", ":"), ensure_ascii=False) + "\n")
    sys.stdout.flush()


def allow_large_messages() -> int:
    """aiortc's SCTP transport takes messages of any size in (it reassembles them, with a 1 MiB window) but tells
    the other side ``a=max-message-size:65536``, so a compliant peer drops (or refuses to send) any bigger event.
    Advertise 256 KiB, like libwebrtc. Returns what is advertised now."""
    from aiortc.rtcsctptransport import RTCSctpCapabilities, RTCSctpTransport  # noqa: PLC0415

    RTCSctpTransport.getCapabilities = classmethod(  # type: ignore[method-assign]
        lambda cls: RTCSctpCapabilities(maxMessageSize=SCTP_MAX_MESSAGE))
    return int(RTCSctpTransport.getCapabilities().maxMessageSize)


def check() -> int:
    value: Dict[str, Any] = {"ok": False, "python": platform.python_version()}
    if sys.version_info < MIN_PYTHON:
        value["reason"] = "python_too_old"
        _write(value)
        return 1
    try:
        import aiortc  # noqa: PLC0415
        import av  # noqa: PLC0415
        from aiortc import RTCConfiguration, RTCPeerConnection  # noqa: PLC0415,F401

        value["sctpMaxMessage"] = allow_large_messages()
        frame = av.AudioFrame(format="s16", layout="stereo", samples=960)
        for plane in frame.planes:
            plane.update(bytes(plane.buffer_size))
        frame.sample_rate = 48_000
        resampled = av.AudioResampler(format="s16", layout="mono", rate=OUT_RATE).resample(frame)
        if not isinstance(resampled, list):
            raise RuntimeError("resampler")
    except Exception as error:  # noqa: BLE001 - any broken wheel means "off"
        value["reason"] = "missing_packages" if isinstance(error, ImportError) else "broken_packages"
        _write(value)
        return 1
    value.update(ok=True, aiortc=getattr(aiortc, "__version__", "?"), av=getattr(av, "__version__", "?"))
    _write(value)
    return 0


def _loudest(data: bytes) -> int:
    samples = array("h")
    samples.frombytes(data[: len(data) - len(data) % 2])
    return max((abs(value) for value in samples), default=0)


def _trim_leading_silence(data: bytes) -> bytes:
    samples = array("h")
    samples.frombytes(data[: len(data) - len(data) % 2])
    for index, value in enumerate(samples):
        if abs(value) >= QUIET_LEVEL:
            start = max(0, index - 160)  # keep 10 ms before the first sound
            return data[start * 2:]
    return b""


class Peer:
    def __init__(self, loop: asyncio.AbstractEventLoop) -> None:
        self.loop = loop
        self.pc: Any = None
        self.dc: Any = None
        self.speaking = False
        self.buffer_events = False  # the server sends output_audio_buffer.* (WebRTC GA does)
        self.response_done = True
        self.preroll: Deque[Tuple[float, bytes]] = deque()  # (arrived, PCM) while nothing is playing
        self.preroll_bytes = 0
        self.muted = False  # after drop_audio: keep nothing until the next response is created
        self.epoch = 0  # response.created count: which response the audio belongs to
        self.frame_at: Optional[float] = None  # when the last frame came, and in which epoch
        self.frame_epoch = 0
        self.skip = 0  # frames still to drop: an earlier response's, held by the jitter buffer over a pause
        self.pending = bytearray()
        self.quiet = 0
        self.stop_handle: Optional[asyncio.TimerHandle] = None
        self.pumps: list = []

    # ------------------------------------------------------------------ signaling
    async def offer(self) -> None:
        from aiortc import RTCConfiguration, RTCPeerConnection  # noqa: PLC0415
        from aiortc.mediastreams import AudioStreamTrack  # noqa: PLC0415

        if self.pc is not None:
            raise RuntimeError("offer twice")
        allow_large_messages()
        self.pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))  # like the R1: host candidates only
        self.pc.addTrack(AudioStreamTrack())  # silence: turns are sent as text
        self.dc = self.pc.createDataChannel("oai-events", ordered=True)

        @self.dc.on("open")
        def _open() -> None:
            _write({"ev": "open"})

        @self.dc.on("message")
        def _message(message: Any) -> None:
            self.on_event(message)

        @self.dc.on("close")
        def _closed() -> None:
            _write({"ev": "state", "state": "channel_closed"})

        @self.pc.on("track")
        def _track(track: Any) -> None:
            if track.kind == "audio":
                self.pumps.append(asyncio.ensure_future(self.pump(track)))

        @self.pc.on("connectionstatechange")
        async def _state() -> None:
            _write({"ev": "state", "state": str(self.pc.connectionState)})

        offer = await self.pc.createOffer()
        await self.pc.setLocalDescription(offer)  # aiortc gathers every candidate here
        _write({"ev": "offer", "sdp": self.pc.localDescription.sdp})

    async def answer(self, sdp: str) -> None:
        from aiortc import RTCSessionDescription  # noqa: PLC0415

        if self.pc is None or not isinstance(sdp, str) or not sdp.startswith("v=0"):
            raise RuntimeError("bad answer")
        await self.pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="answer"))

    def send(self, event: Any) -> None:
        if self.dc is None or self.dc.readyState != "open":
            _write({"ev": "error", "code": "not_open"})
            return
        self.dc.send(json.dumps(event, separators=(",", ":"), ensure_ascii=False))

    # ------------------------------------------------------------------ server events
    def on_event(self, message: Any) -> None:
        if isinstance(message, bytes):
            try:
                message = message.decode("utf-8")
            except UnicodeDecodeError:
                return
        try:
            event = json.loads(message)
        except ValueError:
            return
        if not isinstance(event, dict):
            return
        kind = event.get("type")
        if kind == "output_audio_buffer.started":
            self.buffer_events = True
            self.start_speaking()
        elif kind == "output_audio_buffer.stopped":
            self.buffer_events = True
            self.stop_speaking(TAIL_SECONDS)
        elif kind == "output_audio_buffer.cleared":
            self.buffer_events = True
            self.stop_speaking(0.0)
            self.clear_preroll()  # what was cleared never plays
        elif kind == "response.created":
            self.response_done = False
            self.muted = False
            self.epoch += 1
            if not self.speaking:  # anything kept so far came before this response: never its start
                self.clear_preroll()
        elif kind == "response.output_audio_transcript.delta" and not self.buffer_events:
            self.start_speaking()  # a server without buffer events: the transcript says the reply has begun
        elif kind == "response.done":
            self.response_done = True
            if not self.buffer_events and self.speaking:
                self.loop.call_later(1.5, self._quiet_deadline)
        _write({"ev": "event", "event": event})

    # ------------------------------------------------------------------ audio
    async def pump(self, track: Any) -> None:
        import av  # noqa: PLC0415
        from aiortc.mediastreams import MediaStreamError  # noqa: PLC0415

        resampler = av.AudioResampler(format="s16", layout="mono", rate=OUT_RATE)
        while True:
            try:
                frame = await track.recv()
            except MediaStreamError:
                return
            except Exception:  # noqa: BLE001
                return
            now = time.monotonic()
            if self.frame_at is not None and now - self.frame_at >= RTP_PAUSE_SECONDS and \
                    self.frame_epoch != self.epoch:
                # RTP paused and a new response began meanwhile: the first frames now are what aiortc's jitter
                # buffer kept from before the pause (the earlier reply), never this one's.
                self.skip = JITTER_HELD_FRAMES
            self.frame_at, self.frame_epoch = now, self.epoch
            if self.skip:
                self.skip -= 1
                continue
            try:
                frames = resampler.resample(frame)
            except Exception:  # noqa: BLE001 - one bad frame never stops the reply
                continue
            for item in frames:
                self.feed(bytes(item.planes[0])[: item.samples * 2])

    def feed(self, data: bytes) -> None:
        if not data:
            return
        if not self.speaking:
            if self.muted:
                return
            self.preroll.append((time.monotonic(), data))
            self.preroll_bytes += len(data)
            while self.preroll and self.preroll_bytes - len(self.preroll[0][1]) >= PREROLL_BYTES:
                self.preroll_bytes -= len(self.preroll.popleft()[1])
            return
        self.pending += data
        if not self.buffer_events and self.response_done:
            self.quiet = self.quiet + len(data) if _loudest(data) < QUIET_LEVEL else 0
            if self.quiet >= QUIET_END_BYTES:
                self.finish_speaking()
                return
        self._flush(full_only=True)

    def _flush(self, *, full_only: bool) -> None:
        while len(self.pending) >= FRAME_BYTES or (not full_only and self.pending):
            chunk = bytes(self.pending[:FRAME_BYTES])
            del self.pending[:FRAME_BYTES]
            _write({"ev": "audio", "pcm": base64.b64encode(chunk).decode("ascii")})

    def start_speaking(self) -> None:
        if self.stop_handle is not None:
            self.stop_handle.cancel()
            self.stop_handle = None
        if self.speaking:
            return
        self.speaking = True
        self.quiet = 0
        fresh = time.monotonic() - PREROLL_MAX_AGE
        kept = b"".join(data for arrived, data in self.preroll if arrived >= fresh)[-PREROLL_BYTES:]
        self.pending = bytearray(_trim_leading_silence(kept[len(kept) % 2:]))
        self.clear_preroll()
        self._flush(full_only=True)

    def clear_preroll(self) -> None:
        self.preroll.clear()
        self.preroll_bytes = 0

    def stop_speaking(self, tail: float) -> None:
        if not self.speaking:
            _write({"ev": "audio_end"})
            return
        if self.stop_handle is not None:
            self.stop_handle.cancel()
        self.stop_handle = self.loop.call_later(tail, self.finish_speaking) if tail > 0 else None
        if tail <= 0:
            self.finish_speaking()

    def finish_speaking(self) -> None:
        self.stop_handle = None
        if not self.speaking:
            return
        self.speaking = False
        self._flush(full_only=False)
        self.quiet = 0
        _write({"ev": "audio_end"})

    def _quiet_deadline(self) -> None:
        if self.speaking and not self.buffer_events and self.response_done and self.quiet >= QUIET_END_BYTES // 2:
            self.finish_speaking()
        elif self.speaking and not self.buffer_events and self.response_done:
            self.loop.call_later(0.5, self._quiet_deadline)

    def drop_audio(self) -> None:
        if self.stop_handle is not None:
            self.stop_handle.cancel()
            self.stop_handle = None
        was = self.speaking
        self.speaking = False
        self.pending = bytearray()
        self.clear_preroll()
        self.muted = True  # the cut-off reply's audio still in flight is never kept for the next one
        if was:
            _write({"ev": "audio_end"})

    async def close(self) -> None:
        for pump in self.pumps:
            pump.cancel()
        if self.pc is not None:
            try:
                await asyncio.wait_for(self.pc.close(), timeout=3.0)
            except Exception:  # noqa: BLE001
                pass


async def serve() -> int:
    loop = asyncio.get_running_loop()
    lines: "asyncio.Queue[Optional[bytes]]" = asyncio.Queue()

    def read() -> None:
        # The raw descriptor, not sys.stdin's buffer: a daemon thread blocked in os.read holds no buffer lock, so
        # the interpreter can exit while it waits.
        pending = b""
        try:
            while True:
                chunk = os.read(0, 65536)
                if not chunk:
                    break
                pending += chunk
                while b"\n" in pending:
                    raw, pending = pending.split(b"\n", 1)
                    loop.call_soon_threadsafe(lines.put_nowait, raw)
                if len(pending) > MAX_LINE:
                    break
        except OSError:
            pass
        finally:
            loop.call_soon_threadsafe(lines.put_nowait, None)

    threading.Thread(target=read, name="stdin", daemon=True).start()
    peer = Peer(loop)
    _write({"ev": "ready"})
    try:
        while True:
            raw = await lines.get()
            if raw is None or len(raw) > MAX_LINE:
                break
            try:
                message = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, ValueError):
                continue
            if not isinstance(message, dict):
                continue
            op = message.get("op")
            try:
                if op == "offer":
                    await peer.offer()
                elif op == "answer":
                    await peer.answer(message.get("sdp"))
                elif op == "send":
                    peer.send(message.get("event"))
                elif op == "drop_audio":
                    peer.drop_audio()
                elif op == "close":
                    break
            except Exception as error:  # noqa: BLE001 - reported by kind only, never by content
                _write({"ev": "error", "code": f"{op}_failed", "kind": type(error).__name__})
    finally:
        await peer.close()
        _write({"ev": "closed"})
    return 0


def main(argv: Optional[list] = None) -> int:
    args = sys.argv[1:] if argv is None else argv
    if "--check" in args:
        return check()
    if sys.version_info < MIN_PYTHON:
        _write({"ev": "error", "code": "python_too_old"})
        return 1
    for name in ("aiortc", "aioice", "av", "asyncio"):
        logging.getLogger(name).setLevel(logging.CRITICAL)
    logging.getLogger().addHandler(logging.NullHandler())
    try:
        import av  # noqa: PLC0415

        av.logging.set_level(None)
    except Exception:  # noqa: BLE001
        pass
    try:
        code = asyncio.run(serve())
    except KeyboardInterrupt:
        code = 0
    sys.stdout.flush()
    os._exit(code)  # no interpreter teardown racing the stdin thread or aiortc's own threads


if __name__ == "__main__":
    sys.exit(main())
