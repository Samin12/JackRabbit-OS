"""Speech to text for the watch and the phone (``POST /v1/mobile/transcribe``, ``samrabbit_transcribe.py``).

Most tests drive a real bridge on a random loopback port with a fake helper (``tests/fake_transcribe.py``) and temp
tokens. ``RealHelperTest`` compiles the real Swift helper into a temp folder and transcribes a clip made with
``say -o`` (silent: it writes a file, nothing is played); it is skipped when this Mac can't build or run it.
Nothing here touches the live bridge, T3, Google Calendar, the Heptabase journal or a microphone.

Run: cd companion/mac-bridge && /usr/bin/python3 -m unittest tests.test_transcribe -q
"""

from __future__ import annotations

import hashlib
import io
import json
import os
from pathlib import Path
import random
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import wave
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import quote

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(HERE))

import samrabbit_transcribe as transcribe  # noqa: E402
from test_mobile import TOKEN, FakeHandler, MobileBase  # noqa: E402

WORDS = "Move the dentist to Friday."
CHILD_ENV = {"HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "TMPDIR", "PATH",
             "PWD", "SHLVL", "_", "OLDPWD", "__CF_USER_TEXT_ENCODING"}  # the last five: /bin/sh and macOS add them


# --------------------------------------------------------------------------- audio fixtures


def wav(seconds: float, rate: int = 16000, width: int = 2, *, noise: bool = False) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(width)
        out.setframerate(rate)
        frames = int(seconds * rate)
        if noise:
            generator = random.Random(7)
            out.writeframes(bytes(generator.randrange(256) for _ in range(frames * width)))
        else:
            out.writeframes(b"\x00" * frames * width)
    return buffer.getvalue()


def box(kind: bytes, payload: bytes) -> bytes:
    return struct.pack(">I4s", 8 + len(payload), kind) + payload


def m4a(seconds: float, *, scale: int = 600, version: int = 0, padding: int = 64, moov_last: bool = True) -> bytes:
    """A minimal MP4 audio file as AVAudioRecorder writes one (ftyp, mdat, then moov with the length in mvhd)."""
    if version == 1:
        mvhd = b"\x01\x00\x00\x00" + b"\x00" * 16 + struct.pack(">IQ", scale, int(seconds * scale)) + b"\x00" * 80
    else:
        mvhd = b"\x00\x00\x00\x00" + b"\x00" * 8 + struct.pack(">II", scale, int(seconds * scale)) + b"\x00" * 80
    ftyp = box(b"ftyp", b"M4A \x00\x00\x00\x00M4A mp42isom")
    moov = box(b"moov", box(b"mvhd", mvhd))
    mdat = box(b"mdat", b"\x00" * padding)
    return ftyp + (mdat + moov if moov_last else moov + mdat)


def adts(seconds: float, *, index: int = 8, rate: int = 16000, id3: bool = False) -> bytes:
    """ADTS AAC frames (16 kHz, mono, one raw block = 1024 samples each)."""
    payload = 10
    length = 7 + payload
    frame = bytes([0xFF, 0xF1, (1 << 6) | (index << 2), 0x40 | ((length >> 11) & 0x03), (length >> 3) & 0xFF,
                   ((length & 0x07) << 5) | 0x1F, 0xFC]) + b"\x11" * payload
    frames = int(round(seconds * rate / 1024))
    tag = b"ID3\x04\x00\x00\x00\x00\x00\x0a" + b"\x00" * 10 if id3 else b""
    return tag + frame * frames


# --------------------------------------------------------------------------- pure checks


class AudioChecksTest(unittest.TestCase):
    def test_sniff_reads_the_container_from_the_bytes(self) -> None:
        self.assertEqual("mp4", transcribe.sniff(m4a(2)))
        self.assertEqual("wav", transcribe.sniff(wav(0.5)))
        self.assertEqual("aac", transcribe.sniff(adts(1)))
        self.assertEqual("aac", transcribe.sniff(adts(1, id3=True)), "an ID3 tag before the first frame")
        for junk in (b"", b"RIFF", b"RIFF\x00\x00\x00\x00AVI LIST", b"\x00\x00\x00\x18ftypx"[:7], b"{\"text\": 1}",
                     b"ID3\x04\x00\x00\x00\x00\x00\x0a", b"\xff\xfb\x90\x64" + b"\x00" * 20, b"OggS" + b"\x00" * 20):
            with self.subTest(junk=junk[:12]):
                self.assertIsNone(transcribe.sniff(junk))

    def test_lengths_from_the_headers(self) -> None:
        self.assertAlmostEqual(2.5, transcribe.audio_seconds("wav", wav(2.5)), places=3)
        self.assertAlmostEqual(3.0, transcribe.audio_seconds("wav", wav(3.0, rate=8000, width=1)), places=3)
        self.assertAlmostEqual(12.5, transcribe.audio_seconds("mp4", m4a(12.5)), places=2)
        self.assertAlmostEqual(12.5, transcribe.audio_seconds("mp4", m4a(12.5, moov_last=False)), places=2)
        self.assertAlmostEqual(95.0, transcribe.audio_seconds("mp4", m4a(95, version=1, scale=16000)), places=2)
        self.assertAlmostEqual(4.0, transcribe.audio_seconds("aac", adts(4.0)), delta=0.07)
        self.assertAlmostEqual(4.0, transcribe.audio_seconds("aac", adts(4.0, id3=True)), delta=0.07)
        # a streaming writer that never filled in the data size: what arrived counts
        unfinished = bytearray(wav(1.0))
        unfinished[40:44] = struct.pack("<I", 0xFFFFFFFF)
        self.assertAlmostEqual(1.0, transcribe.audio_seconds("wav", bytes(unfinished)), places=3)
        self.assertIsNone(transcribe.audio_seconds("mp4", box(b"ftyp", b"M4A ") + box(b"mdat", b"\x00" * 9)))

    def test_broken_bytes_never_raise(self) -> None:
        generator = random.Random(3)
        samples = [wav(1.0), m4a(5), m4a(5, version=1), adts(2)]
        for sample in samples:
            for cut in range(0, len(sample), max(1, len(sample) // 40)):
                piece = sample[:cut]
                for kind in ("wav", "mp4", "aac"):
                    transcribe.audio_seconds(kind, piece)
            for _ in range(200):
                mutated = bytearray(sample)
                for _ in range(8):
                    mutated[generator.randrange(len(mutated))] = generator.randrange(256)
                kind = transcribe.sniff(bytes(mutated))
                if kind:
                    transcribe.audio_seconds(kind, bytes(mutated))

    def test_check_audio_limits(self) -> None:
        self.assertEqual("mp4", transcribe.check_audio(m4a(90.3), "mp4")[0], "half a second of AAC padding is fine")
        self.assertEqual("mp4", transcribe.check_audio(m4a(5), "aac")[0], "an m4a sent as audio/aac is an m4a")
        for body in (m4a(91), wav(91, rate=8000, width=1), adts(95)):
            with self.assertRaises(transcribe.TranscribeError) as caught:
                transcribe.check_audio(body, "mp4")
            self.assertEqual((413, "audio_too_long"), (caught.exception.status, caught.exception.code))
        with self.assertRaises(transcribe.TranscribeError) as caught:
            transcribe.check_audio(b"hello there, not audio", "wav")
        self.assertEqual((415, "unsupported_audio"), (caught.exception.status, caught.exception.code))

    def test_content_types_and_languages(self) -> None:
        for value, kind in (("audio/mp4", "mp4"), ("audio/x-m4a", "mp4"), ("Audio/MP4; codecs=mp4a.40.2", "mp4"),
                            ("audio/wav", "wav"), ("audio/x-wav", "wav"), ("audio/aac", "aac")):
            self.assertEqual(kind, transcribe.content_kind(value), value)
        for value in (None, "", "application/octet-stream", "application/json", "audio/mpeg", "audio/ogg", "text/plain"):
            with self.assertRaises(transcribe.TranscribeError) as caught:
                transcribe.content_kind(value)
            self.assertEqual(415, caught.exception.status)
        self.assertEqual("en-US", transcribe.normalize_language(None))
        self.assertEqual("en-GB", transcribe.normalize_language("en_GB"))
        self.assertEqual("fr", transcribe.normalize_language("fr"))
        self.assertEqual("zh-Hant-TW", transcribe.normalize_language("zh-Hant-TW"))
        for value in ("en US", "en-US;rm -rf", "../en", "e", "english-language-please-x", "--file"):
            with self.assertRaises(transcribe.TranscribeError) as caught:
                transcribe.normalize_language(value)
            self.assertEqual((400, "invalid_lang"), (caught.exception.status, caught.exception.code))


# --------------------------------------------------------------------------- the route, with a fake helper


class TranscribeBase(MobileBase):
    with_t3 = False
    helper_timeout = transcribe.HELPER_TIMEOUT_SECONDS
    check_timeout = 5.0
    max_concurrent = transcribe.MAX_CONCURRENT
    busy_wait = transcribe.BUSY_WAIT_SECONDS

    def make_transcriber(self, bin_dir: Path) -> Any:
        self.voice_dir = self.root / "voice"
        self.voice_dir.mkdir()
        helper = bin_dir / "samrabbit-transcribe"
        helper.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{HERE / "fake_transcribe.py"}" '
                          f'--state "{self.voice_dir}" "$@"\n')
        helper.chmod(0o755)
        return transcribe.Transcriber(str(helper), timeout=self.helper_timeout, check_timeout=self.check_timeout,
                                      max_concurrent=self.max_concurrent, busy_wait=self.busy_wait)

    def setUp(self) -> None:
        super().setUp()
        self.phone = self.pair()["token"]

    def fake(self, name: str, value: str) -> None:
        (self.voice_dir / name).write_text(value)

    def helper_calls(self, flag: str = "--file") -> List[Dict[str, Any]]:
        path = self.voice_dir / "calls.jsonl"
        calls = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        return [call for call in calls if flag in call["args"]]

    def post(self, body: bytes, content_type: str = "audio/mp4", query: str = "",
             token: Optional[str] = None) -> Tuple[int, Dict[str, Any]]:
        status, _headers, raw = self.request("POST", "/v1/mobile/transcribe" + query, raw=body,
                                             token=token or self.phone, headers={"Content-Type": content_type})
        return status, json.loads(raw) if raw else {}

    def serve(self, headers: Dict[str, str], body: bytes = b"", address: str = "127.0.0.1") -> FakeHandler:
        handler = FakeHandler(address, "/v1/mobile/transcribe", {"Authorization": "Bearer " + self.phone,
                                                                  "Content-Type": "audio/mp4", **headers}, body)
        self.service.serve(handler, "POST", "/v1/mobile/transcribe")
        return handler

    def assert_gone(self, call: Dict[str, Any]) -> None:
        self.assertFalse(Path(call["file"]).exists(), "the recording is deleted")
        self.assertFalse(Path(call["file"]).parent.exists(), "and its private folder")


class TranscribeRouteTest(TranscribeBase):
    def test_a_recording_becomes_words_and_leaves_nothing_behind(self) -> None:
        body = m4a(6.4, padding=4000)
        status, value = self.post(body)
        self.assertEqual(200, status, value)
        self.assertEqual({"text": WORDS, "durationMs": 6358, "engine": "SpeechTranscriber", "locale": "en-US"}, value)
        [call] = self.helper_calls()
        self.assertEqual(["--file", call["file"], "--locale", "en-US", "--max-seconds", "90"], call["args"])
        self.assertTrue(call["file"].endswith(".m4a"))
        self.assertEqual(0o600, call["fileMode"], "a private temp file")
        self.assertEqual(0o700, call["dirMode"], "in a private folder")
        self.assertEqual((len(body), hashlib.sha256(body).hexdigest()), (call["size"], call["sha256"]),
                         "the helper got exactly the body")
        self.assertEqual(Path(call["file"]).parent.resolve(), Path(call["cwd"]).resolve(),
                         "the helper runs inside the private folder")
        self.assert_gone(call)
        self.assertNotIn("SAMRABBIT_TEST_SECRET", call["env"], "no inherited secrets")
        self.assertEqual(set(), set(call["env"]) - CHILD_ENV, "a small environment")
        log = self.log.getvalue()
        self.assertIn("POST /v1/mobile/transcribe 200", log)
        for secret in (WORDS, "dentist", self.phone, hashlib.sha256(body).hexdigest()):
            self.assertNotIn(secret, log)

    def test_languages_and_content_types(self) -> None:
        status, value = self.post(m4a(3), query="?lang=fr-FR")
        self.assertEqual((200, "fr-FR"), (status, value["locale"]))
        self.assertEqual("fr-FR", self.helper_calls()[-1]["args"][3])
        self.assertEqual("en-GB", self.post(m4a(3), query="?lang=en_GB")[1]["locale"])
        cases = (("audio/x-m4a", m4a(2), ".m4a"), ("audio/mp4; codecs=mp4a.40.2", m4a(2), ".m4a"),
                 ("audio/wav", wav(1.5), ".wav"), ("audio/x-wav", wav(1.5), ".wav"), ("audio/aac", adts(2), ".aac"),
                 ("audio/aac", m4a(2), ".m4a"))  # the bytes decide the container
        for content_type, body, suffix in cases:
            with self.subTest(content_type=content_type, suffix=suffix):
                status, value = self.post(body, content_type)
                self.assertEqual(200, status, value)
                call = self.helper_calls()[-1]
                self.assertTrue(call["file"].endswith(suffix), call["file"])
                self.assert_gone(call)

    def test_bad_requests_never_reach_the_helper(self) -> None:
        cases = (
            ("text/plain", m4a(2), "", 415, "unsupported_audio"),
            ("application/json", m4a(2), "", 415, "unsupported_audio"),
            ("audio/mpeg", m4a(2), "", 415, "unsupported_audio"),
            ("audio/mp4", b"definitely not a recording", "", 415, "unsupported_audio"),
            ("audio/wav", b"RIFF\x00\x00\x00\x00AVI LIST", "", 415, "unsupported_audio"),
            ("audio/mp4", m4a(2), "?lang=" + quote("en US"), 400, "invalid_lang"),
            ("audio/mp4", m4a(2), "?lang=" + quote("en;--file=/etc/passwd"), 400, "invalid_lang"),
            ("audio/mp4", b"", "", 400, "invalid_audio"),
            ("audio/mp4", m4a(91), "", 413, "audio_too_long"),
            ("audio/wav", wav(91, rate=8000, width=1), "", 413, "audio_too_long"),
            ("audio/aac", adts(95), "", 413, "audio_too_long"),
        )
        for content_type, body, query, status, code in cases:
            with self.subTest(content_type=content_type, query=query, code=code, size=len(body)):
                answer = self.post(body, content_type, query)
                self.assertEqual((status, code), (answer[0], answer[1]["error"]["code"]), answer)
        self.assertEqual([], self.helper_calls(), "nothing ran")
        self.assertEqual(200, self.post(wav(90.3, rate=8000, width=1), "audio/wav")[0], "90 s and a little is fine")

    def test_size_framing_and_peer_rules(self) -> None:
        limit = transcribe.MAX_AUDIO_BYTES
        handler = self.serve({"Content-Length": str(limit + 1)})
        self.assertEqual((413, "body_too_large"), (handler.status, handler.json()["error"]["code"]))
        self.assertEqual(0, handler.rfile.tell(), "a body over the limit is never read")
        handler = self.serve({"Transfer-Encoding": "chunked"})
        self.assertEqual((411, "length_required"), (handler.status, handler.json()["error"]["code"]))
        handler = self.serve({"Content-Length": "5000"}, m4a(2)[:100])
        error = handler.json()["error"]
        self.assertEqual((400, "invalid_audio", True), (handler.status, error["code"], error["retryable"]))
        handler = self.serve({}, m4a(2), address="8.8.8.8")
        self.assertEqual((403, "forbidden"), (handler.status, handler.json()["error"]["code"]))
        handler = self.serve({"Authorization": "Bearer nope-" + "x" * 30}, m4a(2), address="100.101.102.103")
        self.assertEqual(401, handler.status, "a Tailscale peer still needs a mobile token")
        self.assertEqual([], self.helper_calls())
        big = m4a(60, padding=limit - len(m4a(60, padding=0)))
        self.assertEqual(limit, len(big))
        handler = self.serve({}, big, address="100.101.102.103")
        self.assertEqual(200, handler.status, handler.json())
        self.assertEqual(limit, self.helper_calls()[-1]["size"], "exactly 2 MiB is accepted")

    def test_helper_failures_have_clear_codes(self) -> None:
        cases = (
            ("permission", 503, "transcribe_permission", False, None),
            ("unavailable", 503, "transcribe_unavailable", False, "speech_unavailable"),
            ("insufficient", 503, "transcribe_unavailable", True, "insufficient_resources"),
            ("failed", 502, "transcribe_failed", True, None),
            ("crash", 502, "transcribe_failed", True, None),
            ("garbage", 502, "transcribe_failed", True, None),
            ("no_speech", 422, "no_speech", False, None),
            ("too_long", 413, "audio_too_long", False, None),
            ("language", 422, "unsupported_language", False, None),
            ("audio", 415, "unsupported_audio", False, None),
        )
        for mode, status, code, retryable, reason in cases:
            with self.subTest(mode=mode):
                self.fake("mode", mode)
                answer, value = self.post(m4a(4))
                self.assertEqual(status, answer, value)
                error = value["error"]
                self.assertEqual((code, retryable), (error["code"], error["retryable"]))
                self.assertEqual(reason, error.get("reason"))
                self.assertTrue(error["message"])
                self.assertNotIn("helper message", error["message"], "the bridge's own words, not the helper's")
                self.assert_gone(self.helper_calls()[-1])
        log = self.log.getvalue()
        self.assertNotIn("secret words", log)
        self.assertIn("transcription helper failed (exit 134)", log)
        self.fake("text", "  Line one\n\tline two  \x00 ")
        self.fake("mode", "ok")
        self.assertEqual("Line one line two", self.post(m4a(2))[1]["text"], "whitespace and controls collapsed")
        self.fake("text", "word " * 4000)
        self.assertEqual(transcribe.MAX_TEXT_CHARS, len(self.post(m4a(2))[1]["text"]))
        self.fake("text", "   ")
        self.assertEqual("no_speech", self.post(m4a(2))[1]["error"]["code"])

    def test_health_reports_transcription(self) -> None:
        health = self.call("GET", "/health", token=TOKEN)[1]
        self.assertEqual({"available": True, "engine": "SpeechTranscriber", "locale": "en-US"},
                         health["mobile"]["transcribe"])
        self.server._health = None  # noqa: SLF001
        self.call("GET", "/health", token=TOKEN)
        self.assertEqual(1, len(self.helper_calls("--check")), "checked once, then cached")
        self.assertEqual(["--check", "--locale", "en-US"], self.helper_calls("--check")[0]["args"])
        for check, expected in (
                ("model_missing", {"available": False, "engine": "SpeechTranscriber", "locale": "en-US",
                                   "reason": "model_missing"}),
                ("speech_unavailable", {"available": False, "reason": "speech_unavailable"}),
                ("garbage", {"available": False, "reason": "check_failed"}),
                ("available", {"available": True, "engine": "SpeechTranscriber", "locale": "en-US"})):
            with self.subTest(check=check):
                self.fake("check", check)
                self.assertEqual(expected, self.transcriber.status(refresh=True))
        self.fake("check", "model_missing")
        self.transcriber.status(refresh=True)
        self.assertEqual(200, self.post(m4a(2))[0])
        self.assertTrue(self.transcriber.status()["available"], "a transcription that worked counts as a check")

    def test_a_missing_model_downloads_in_the_background(self) -> None:
        self.fake("mode", "model_missing")
        self.fake("check", "model_missing")
        self.fake("prepare", "slow")
        status, value = self.post(m4a(3))
        self.assertEqual((503, "transcribe_unavailable", "model_downloading", True),
                         (status, value["error"]["code"], value["error"]["reason"], value["error"]["retryable"]))
        self.assertEqual("model_downloading", self.transcriber.status(refresh=True)["reason"])
        again = self.post(m4a(3))
        self.assertEqual("model_downloading", again[1]["error"]["reason"], "one download at a time")
        self.wait_for(lambda: self.transcriber._preparing is None)  # noqa: SLF001
        self.assertEqual(1, len(self.helper_calls("--prepare")))
        self.assertEqual(["--prepare", "--locale", "en-US"], self.helper_calls("--prepare")[0]["args"])
        self.assertEqual(200, self.post(m4a(3))[0], "ready once the model is there")
        self.assertTrue(self.transcriber.status()["available"])
        self.assertIn("speech model download ready", self.log.getvalue())

    def test_a_failed_download_is_not_retried_at_once(self) -> None:
        self.fake("mode", "model_missing")
        self.fake("prepare", "fail")
        self.assertEqual("model_downloading", self.post(m4a(3), query="?lang=de-DE")[1]["error"]["reason"])
        self.wait_for(lambda: self.transcriber._preparing is None)  # noqa: SLF001
        status, value = self.post(m4a(3), query="?lang=de-DE")
        self.assertEqual((503, "model_missing", False),
                         (status, value["error"]["reason"], value["error"]["retryable"]))
        self.assertEqual(1, len(self.helper_calls("--prepare")), "at most one try every 30 minutes")
        self.assertIn("speech model download failed", self.log.getvalue())

    def test_closing_stops_a_running_transcription_and_removes_its_recording(self) -> None:
        self.fake("mode", "hang")
        results: List[Tuple[int, Dict[str, Any]]] = []
        thread = threading.Thread(target=lambda: results.append(self.post(m4a(3))))
        thread.start()
        self.wait_for(lambda: bool(self.helper_calls()))
        call = self.helper_calls()[-1]
        self.assertTrue(Path(call["file"]).exists(), "the recording exists while the helper runs")
        started = time.monotonic()
        self.transcriber.close()  # what the bridge does when it stops
        thread.join(timeout=10)
        self.assertLess(time.monotonic() - started, 5, "the helper was stopped, not waited for")
        self.assertEqual(502, results[0][0])
        self.assert_gone(call)

    def wait_for(self, condition: Any, seconds: float = 15.0) -> None:
        deadline = time.monotonic() + seconds
        while not condition():
            if time.monotonic() > deadline:
                self.fail("timed out")
            time.sleep(0.05)


class TranscribeLimitsTest(TranscribeBase):
    helper_timeout = 1.0
    check_timeout = 1.0
    max_concurrent = 1
    busy_wait = 0.2

    def test_a_helper_that_hangs_is_stopped(self) -> None:
        self.fake("mode", "hang")
        started = time.monotonic()
        status, value = self.post(m4a(3))
        self.assertLess(time.monotonic() - started, 10)
        self.assertEqual((504, "transcribe_failed", True),
                         (status, value["error"]["code"], value["error"]["retryable"]))
        self.assert_gone(self.helper_calls()[-1])
        self.fake("check", "hang")
        self.assertEqual({"available": False, "reason": "check_timeout"}, self.transcriber.status(refresh=True))

    def test_one_at_a_time_then_busy(self) -> None:
        self.fake("mode", "slow")
        self.fake("delay", "0.6")  # longer than the wait for a free slot, shorter than the helper timeout
        results: List[Tuple[int, Dict[str, Any]]] = []
        threads = [threading.Thread(target=lambda: results.append(self.post(m4a(3)))) for _ in range(2)]
        for thread in threads:
            thread.start()
            time.sleep(0.1)
        for thread in threads:
            thread.join(timeout=20)
        self.assertEqual([200, 503], sorted(status for status, _value in results))
        busy = next(value for status, value in results if status == 503)["error"]
        self.assertEqual(("transcribe_busy", True), (busy["code"], busy["retryable"]))


class NoHelperTest(MobileBase):
    with_t3 = False

    def test_without_the_helper_the_route_says_so(self) -> None:
        phone = self.pair()["token"]
        status, _headers, raw = self.request("POST", "/v1/mobile/transcribe", raw=m4a(2), token=phone,
                                             headers={"Content-Type": "audio/mp4"})
        error = json.loads(raw)["error"]
        self.assertEqual((503, "transcribe_unavailable", "helper_missing"), (status, error["code"], error["reason"]))
        self.assertIn("install.sh", error["message"])


class Python39Test(unittest.TestCase):
    def test_the_module_parses_as_python_3_9_and_imports_isolated(self) -> None:
        import ast

        ast.parse((ROOT / "samrabbit_transcribe.py").read_text(), feature_version=(3, 9))
        # As the LaunchAgent runs it: the macOS system Python, isolated mode, from another folder.
        code = ("import sys; sys.path.append(sys.argv[1]); import samrabbit_mobile; "
                "print(samrabbit_mobile._transcribe is not None)")
        done = subprocess.run(["/usr/bin/python3", "-I", "-c", code, str(ROOT)], capture_output=True, text=True,
                              timeout=60, cwd=tempfile.gettempdir())
        self.assertEqual("True", done.stdout.strip(), done.stderr)


# --------------------------------------------------------------------------- the real Swift helper


def _can_build() -> Optional[str]:
    if sys.platform != "darwin" or not shutil.which("xcrun") or not os.path.exists("/usr/bin/say"):
        return "needs macOS with Xcode or the Command Line Tools"
    found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "swiftc"], capture_output=True, timeout=30)
    return None if found.returncode == 0 else "no Swift compiler"


_BUILD_PROBLEM = _can_build()


@unittest.skipIf(_BUILD_PROBLEM is not None, _BUILD_PROBLEM or "")
class RealHelperTest(MobileBase):
    """The real helper on this Mac: built like install.sh builds it, fed clips made with ``say -o``."""

    with_t3 = False
    build: Optional[tempfile.TemporaryDirectory] = None
    helper = ""
    unusable = ""

    @classmethod
    def setUpClass(cls) -> None:
        cls.build = tempfile.TemporaryDirectory()
        folder = Path(cls.build.name)
        source = ROOT / "transcribe"
        cls.helper = str(folder / "samrabbit-transcribe")
        done = subprocess.run(["xcrun", "--sdk", "macosx", "swiftc", "-O", "-parse-as-library",
                               str(source / "samrabbit_transcribe.swift"), "-o", cls.helper, "-Xlinker", "-sectcreate",
                               "-Xlinker", "__TEXT", "-Xlinker", "__info_plist", "-Xlinker", str(source / "Info.plist")],
                              capture_output=True, text=True, timeout=300)
        if done.returncode != 0:
            raise AssertionError("the helper did not build:\n" + done.stderr[-3000:])
        subprocess.run(["codesign", "-s", "-", "-f", "-i", "com.samrabbit.transcribe", cls.helper],
                       capture_output=True, timeout=60)
        check = json.loads(subprocess.run([cls.helper, "--check"], capture_output=True, text=True,
                                          timeout=60).stdout)
        if not check.get("available"):
            cls.unusable = f"transcription is not available on this Mac ({check.get('reason')})"
        say = ["/usr/bin/say", "-o", str(folder / "clip.wav"), "--data-format=LEI16@16000",
               "Remind me to call the dentist on Friday afternoon."]
        subprocess.run(say, check=True, capture_output=True, timeout=60)
        subprocess.run(["/usr/bin/afconvert", "-f", "m4af", "-d", "aac@16000", "-c", "1", "-b", "24000",
                        str(folder / "clip.wav"), str(folder / "clip.m4a")], check=True, capture_output=True,
                       timeout=60)
        (folder / "silence.wav").write_bytes(wav(2.0))

    @classmethod
    def tearDownClass(cls) -> None:
        if cls.build is not None:
            cls.build.cleanup()

    def make_transcriber(self, bin_dir: Path) -> Any:
        return transcribe.Transcriber(self.helper)

    def setUp(self) -> None:
        if self.unusable:
            self.skipTest(self.unusable)
        super().setUp()
        self.phone = self.pair()["token"]

    def post(self, path: Path, content_type: str) -> Tuple[int, Dict[str, Any]]:
        status, _headers, raw = self.request("POST", "/v1/mobile/transcribe", raw=path.read_bytes(), token=self.phone,
                                             headers={"Content-Type": content_type})
        return status, json.loads(raw)

    def test_a_watch_style_m4a_and_a_wav_are_transcribed_on_this_mac(self) -> None:
        folder = Path(self.helper).parent
        for name, content_type in (("clip.m4a", "audio/mp4"), ("clip.wav", "audio/wav")):
            with self.subTest(name=name):
                started = time.monotonic()
                status, value = self.post(folder / name, content_type)
                self.assertEqual(200, status, value)
                words = value["text"].lower()
                for word in ("remind", "dentist", "friday"):
                    self.assertIn(word, words)
                self.assertEqual(("SpeechTranscriber", "en-US"), (value["engine"], value["locale"]))
                self.assertGreater(value["durationMs"], 1000)
                self.assertLess(time.monotonic() - started, 30)
        self.assertNotIn("dentist", self.log.getvalue().lower())

    def test_silence_and_unreadable_audio(self) -> None:
        folder = Path(self.helper).parent
        status, value = self.post(folder / "silence.wav", "audio/wav")
        self.assertEqual((422, "no_speech"), (status, value["error"]["code"]))
        broken = folder / "broken.m4a"
        broken.write_bytes(box(b"ftyp", b"M4A \x00\x00\x00\x00M4A mp42isom") + b"\x00garbage" * 200)
        status, value = self.post(broken, "audio/mp4")
        self.assertEqual((415, "unsupported_audio"), (status, value["error"]["code"]))

    def test_health_says_on(self) -> None:
        value = self.service.health()["transcribe"]
        self.assertEqual({"available": True, "engine": "SpeechTranscriber", "locale": "en-US"}, value)


if __name__ == "__main__":
    unittest.main()
