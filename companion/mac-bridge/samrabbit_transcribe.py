"""Speech to text on the Mac for the SamRabbit iPhone and Apple Watch apps (``POST /v1/mobile/transcribe``).

The watch (or the phone) records a short clip and sends it as the raw request body; the bridge answers with the
words: ``{text, durationMs, engine, locale}``. The words come from ``samrabbit-transcribe``, a small Swift helper
(``transcribe/samrabbit_transcribe.swift``) that ``install.sh`` compiles into the installed bridge folder. It uses
macOS's own on-device SpeechAnalyzer + SpeechTranscriber (macOS 26+): nothing leaves the Mac, no microphone is used
and there is no permission prompt (the input is a file).

* Body: ``audio/mp4`` / ``audio/x-m4a`` (an m4a, e.g. AAC from ``AVAudioRecorder``), ``audio/wav`` or ``audio/aac``
  (ADTS); at most 2 MiB and 90 s. The container is checked from the bytes (``ftyp`` at offset 4, ``RIFF....WAVE``
  or an ADTS frame) and its length read from the header before the helper runs.
* The audio is written to a private temp file (0600, in its own 0700 folder) only while the helper runs (at most
  45 s), then deleted. Neither the audio nor the words are ever logged or kept.
* Errors (the bridge's ``{error: {code, message, retryable}}``, with ``reason`` where it helps):
  ``transcribe_unavailable`` (503: no helper, no speech transcriber on this Mac, or the language's model is not on
  the Mac yet, which the bridge then downloads in the background), ``transcribe_permission`` (503: Speech
  Recognition is turned off for it in System Settings), ``transcribe_failed`` (502, or 504 when it took too long),
  ``transcribe_busy`` (503), ``unsupported_audio`` (415), ``audio_too_long`` / ``body_too_large`` (413),
  ``no_speech`` (422), ``unsupported_language`` (422) and ``invalid_lang`` (400).

Stdlib only, Python 3.9.
"""

from __future__ import annotations

import json
import logging
import os
import re
import shutil
import signal
import struct
import subprocess
import tempfile
import threading
import time
from typing import Any, Callable, Dict, List, Optional, Tuple

_HERE = os.path.dirname(os.path.abspath(__file__))
_LOG = logging.getLogger("samrabbit-bridge.transcribe")

HELPER_NAME = "samrabbit-transcribe"
MAX_AUDIO_BYTES = 2 * 1024 * 1024
MAX_AUDIO_SECONDS = 90
LENGTH_SLACK_SECONDS = 0.5  # AAC priming and padding
HELPER_TIMEOUT_SECONDS = 45.0
CHECK_TIMEOUT_SECONDS = 10.0
CHECK_CACHE_SECONDS = 10 * 60.0
PREPARE_TIMEOUT_SECONDS = 15 * 60.0
PREPARE_RETRY_SECONDS = 30 * 60.0
MAX_CONCURRENT = 2
BUSY_WAIT_SECONDS = 10.0
MAX_HELPER_OUTPUT = 256 * 1024
MAX_TEXT_CHARS = 8000
DEFAULT_LANGUAGE = "en-US"
ENGINES = ("SpeechTranscriber", "DictationTranscriber")

# Content-Type (without parameters) -> what the bytes must be. The four the API documents, plus common spellings.
CONTENT_TYPES: Dict[str, str] = {
    "audio/mp4": "mp4", "audio/x-m4a": "mp4", "audio/m4a": "mp4",
    "audio/wav": "wav", "audio/x-wav": "wav", "audio/wave": "wav", "audio/vnd.wave": "wav",
    "audio/aac": "aac", "audio/x-aac": "aac", "audio/aacp": "aac",
}
_SUFFIX = {"mp4": ".m4a", "wav": ".wav", "aac": ".aac"}
_ADTS_RATES = (96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
_LANGUAGE = re.compile(r"^[A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{1,8}){0,3}$")
_CONTROL = re.compile(r"[\x00-\x1f\x7f]")
_ERROR_DETAIL = re.compile(r"^[A-Za-z][A-Za-z0-9._]{0,63}#-?[0-9]{1,12}$")  # e.g. SFSpeechErrorDomain#2

# Helper answer code -> (HTTP status, message, retryable)
_FAILURES: Dict[str, Tuple[int, str, bool]] = {
    "transcribe_unavailable": (503, "Transcription is not available on the Mac.", False),
    "transcribe_permission": (503, "Speech Recognition is turned off for SamRabbit on the Mac (System Settings > "
                                   "Privacy & Security > Speech Recognition).", False),
    "unsupported_language": (422, "The Mac cannot transcribe that language.", False),
    "unsupported_audio": (415, "The Mac could not read that recording.", False),
    "audio_too_long": (413, f"Recordings can be at most {MAX_AUDIO_SECONDS} seconds.", False),
    "no_speech": (422, "No speech was heard in the recording.", False),
    "transcribe_failed": (502, "The Mac could not transcribe the recording.", True),
}
_UNAVAILABLE_MESSAGES = {
    "helper_missing": "Transcription is not installed on the Mac. Run companion/mac-bridge/install.sh again (it "
                      "needs Xcode or the Command Line Tools).",
    "speech_unavailable": "This Mac has no on-device speech transcriber (it needs macOS 26 or newer).",
    "model_missing": "The speech model for that language is not on the Mac yet.",
    "model_downloading": "The Mac is downloading the speech model for that language. Try again in a minute.",
    "insufficient_resources": "The Mac is too busy to transcribe right now. Try again in a moment.",
}


class TranscribeError(Exception):
    def __init__(self, status: int, code: str, message: str, *, retryable: bool = False,
                 reason: Optional[str] = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.retryable = retryable
        self.reason = reason

    def payload(self) -> Dict[str, Any]:
        error: Dict[str, Any] = {"code": self.code, "message": self.message, "retryable": self.retryable}
        if self.reason:
            error["reason"] = self.reason
        return {"error": error}


def unavailable(reason: str, *, retryable: bool = False) -> TranscribeError:
    return TranscribeError(503, "transcribe_unavailable", _UNAVAILABLE_MESSAGES.get(
        reason, _FAILURES["transcribe_unavailable"][1]), retryable=retryable, reason=reason)


# --------------------------------------------------------------------------- request checks


def content_kind(value: Optional[str]) -> str:
    """The container a Content-Type promises (``mp4``, ``wav`` or ``aac``); 415 for anything else."""
    kind = CONTENT_TYPES.get(str(value or "").split(";", 1)[0].strip().lower())
    if kind is None:
        raise TranscribeError(415, "unsupported_audio", "Send the recording as audio/mp4, audio/x-m4a, audio/wav or "
                              "audio/aac.")
    return kind


def normalize_language(value: Optional[str]) -> str:
    """``?lang=`` as a BCP 47 tag (``en-US``); the default when there is none."""
    if value is None or value == "":
        return DEFAULT_LANGUAGE
    if not _LANGUAGE.match(value):
        raise TranscribeError(400, "invalid_lang", "lang must be a language tag such as en-US.")
    return value.replace("_", "-")


def _id3_length(data: bytes) -> int:
    """The size of a leading ID3v2 tag (some AAC files have one before the first frame)."""
    if len(data) < 10 or data[:3] != b"ID3":
        return 0
    size = 0
    for byte in data[6:10]:
        if byte & 0x80:
            return 0
        size = (size << 7) | byte
    return 10 + size + (10 if data[5] & 0x10 else 0)


def _adts_at(data: bytes, offset: int) -> bool:
    return len(data) >= offset + 7 and data[offset] == 0xFF and (data[offset + 1] & 0xF6) == 0xF0


def sniff(data: bytes) -> Optional[str]:
    """``mp4`` (``ftyp`` box first), ``wav`` (RIFF/WAVE) or ``aac`` (ADTS), from the bytes alone."""
    if len(data) >= 12 and data[4:8] == b"ftyp":
        return "mp4"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WAVE":
        return "wav"
    if _adts_at(data, _id3_length(data)):
        return "aac"
    return None


def _wav_seconds(data: bytes) -> Optional[float]:
    position, byte_rate = 12, 0
    while position + 8 <= len(data):
        chunk, size = data[position:position + 4], struct.unpack("<I", data[position + 4:position + 8])[0]
        body = position + 8
        if chunk == b"fmt " and size >= 16 and body + 16 <= len(data):
            byte_rate = struct.unpack("<I", data[body + 8:body + 12])[0]
        elif chunk == b"data":
            if not byte_rate:
                return None
            return min(size, len(data) - body) / byte_rate  # a streaming writer may leave the size unset
        position = body + size + (size & 1)
    return None


def _mp4_boxes(data: bytes, start: int, end: int):  # type: ignore[no-untyped-def]
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        header = 8
        if size == 1:
            if position + 16 > end:
                return
            size = struct.unpack(">Q", data[position + 8:position + 16])[0]
            header = 16
        elif size == 0:
            size = end - position
        if size < header:
            return
        yield kind, position + header, min(position + size, end)
        position += size


def _mp4_seconds(data: bytes) -> Optional[float]:
    for kind, start, end in _mp4_boxes(data, 0, len(data)):
        if kind != b"moov":
            continue
        for inner, body, stop in _mp4_boxes(data, start, end):
            if inner != b"mvhd" or body + 20 > stop:
                continue
            if data[body] == 1:
                if body + 32 > stop:
                    return None
                scale, duration = struct.unpack(">IQ", data[body + 20:body + 32])
                unknown = 0xFFFFFFFFFFFFFFFF
            else:
                scale, duration = struct.unpack(">II", data[body + 12:body + 20])
                unknown = 0xFFFFFFFF
            return duration / scale if scale and duration != unknown else None
    return None


def _aac_seconds(data: bytes) -> Optional[float]:
    position, samples, rate = _id3_length(data), 0, 0
    while _adts_at(data, position):
        index = (data[position + 2] >> 2) & 0x0F
        if index >= len(_ADTS_RATES):
            return None
        rate = _ADTS_RATES[index]
        length = ((data[position + 3] & 0x03) << 11) | (data[position + 4] << 3) | (data[position + 5] >> 5)
        if length < 7:
            break
        samples += 1024 * ((data[position + 6] & 0x03) + 1)
        position += length
    return samples / rate if rate else None


def audio_seconds(kind: str, data: bytes) -> Optional[float]:
    """The recording's length from its header (None when the header doesn't say; the helper checks again)."""
    try:
        return {"wav": _wav_seconds, "mp4": _mp4_seconds, "aac": _aac_seconds}[kind](data)
    except (KeyError, struct.error, ZeroDivisionError):
        return None


def check_audio(data: bytes, declared: str) -> Tuple[str, Optional[float]]:
    """``(container, seconds)`` for a body; 415 when it is not audio we read, 413 when it is too long. A container
    other than the declared one is accepted (an m4a sent as audio/aac is still an m4a); the bytes decide."""
    kind = sniff(data)
    if kind is None:
        raise TranscribeError(415, "unsupported_audio", "The body is not an m4a, WAV or AAC recording.")
    seconds = audio_seconds(kind, data)
    if seconds is not None and seconds > MAX_AUDIO_SECONDS + LENGTH_SLACK_SECONDS:
        raise TranscribeError(413, "audio_too_long", _FAILURES["audio_too_long"][1])
    if declared != kind:
        _LOG.info("transcription body is %s, sent as %s", kind, declared)
    return kind, seconds


# --------------------------------------------------------------------------- the helper


def _child_env() -> Dict[str, str]:
    """A small, predictable environment (no inherited tokens or tool settings)."""
    env = {key: os.environ[key] for key in ("HOME", "USER", "LOGNAME", "LANG", "LC_ALL", "TMPDIR")
           if os.environ.get(key)}
    env["PATH"] = "/usr/bin:/bin:/usr/sbin:/sbin"
    return env


def _kill_group(process: "subprocess.Popen[bytes]") -> None:
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except OSError:
        try:
            process.kill()
        except OSError:
            pass


def default_helper() -> str:
    """Next to the bridge script: install.sh builds it into the installed bridge folder."""
    return os.path.join(_HERE, HELPER_NAME)


class Transcriber:
    """Runs the helper: ``status()`` for ``/health`` (cached), ``transcribe(data, kind, language)`` for the route."""

    def __init__(self, helper: Optional[str] = None, *, timeout: float = HELPER_TIMEOUT_SECONDS,
                 check_timeout: float = CHECK_TIMEOUT_SECONDS, prepare_timeout: float = PREPARE_TIMEOUT_SECONDS,
                 max_concurrent: int = MAX_CONCURRENT, busy_wait: float = BUSY_WAIT_SECONDS,
                 monotonic: Callable[[], float] = time.monotonic) -> None:
        self.helper = os.path.expanduser(helper) if helper else default_helper()
        self._timeout = min(float(timeout), HELPER_TIMEOUT_SECONDS)
        self._check_timeout = check_timeout
        self._prepare_timeout = prepare_timeout
        self._busy_wait = busy_wait
        self._monotonic = monotonic
        self._slots = threading.BoundedSemaphore(max(1, int(max_concurrent)))
        self._lock = threading.Lock()  # the state below
        self._check_lock = threading.Lock()  # one --check at a time (never held while answering a recording)
        self._status: Optional[Tuple[float, Dict[str, Any]]] = None
        self._preparing: Optional[str] = None
        self._prepare_process: Optional["subprocess.Popen[bytes]"] = None
        self._prepared_at: Dict[str, float] = {}
        self._running: Dict[str, Optional["subprocess.Popen[bytes]"]] = {}  # private folder -> its helper
        self._closed = False

    def executable(self) -> Optional[str]:
        path = self.helper
        return path if os.path.isfile(path) and os.access(path, os.X_OK) else None

    def close(self) -> None:
        """Stops a model download and any transcription still running, and removes their recordings."""
        with self._lock:
            self._closed = True
            processes = [self._prepare_process, *self._running.values()]
            folders = list(self._running)
        for process in processes:
            if process is not None and process.poll() is None:
                _kill_group(process)
        for folder in folders:
            shutil.rmtree(folder, ignore_errors=True)

    # ------------------------------------------------------------------ running it
    def _start(self, args: List[str], cwd: str) -> "subprocess.Popen[bytes]":
        executable = self.executable()
        if executable is None:
            raise unavailable("helper_missing")
        try:
            # stderr is dropped unread: whatever a framework might print there never reaches the log.
            return subprocess.Popen([executable, *args], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, env=_child_env(), cwd=cwd, start_new_session=True)
        except OSError:
            raise unavailable("helper_missing") from None

    def _finish(self, process: "subprocess.Popen[bytes]", timeout: float) -> Optional[Dict[str, Any]]:
        """The helper's one JSON object (None when it printed none); TimeoutExpired is re-raised after the kill."""
        try:
            out, _ = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            _kill_group(process)
            process.communicate()
            raise
        if len(out) > MAX_HELPER_OUTPUT:
            return None
        lines = [line for line in out.decode("utf-8", errors="replace").splitlines() if line.strip()]
        try:
            value = json.loads(lines[-1]) if lines else None
        except ValueError:
            return None
        return value if isinstance(value, dict) else None

    def _run(self, args: List[str], timeout: float) -> Tuple[Optional[Dict[str, Any]], int]:
        process = self._start(args, tempfile.gettempdir())
        value = self._finish(process, timeout)
        return value, process.returncode

    # ------------------------------------------------------------------ /health
    def status(self, *, refresh: bool = False) -> Dict[str, Any]:
        """``{available, engine?, locale?, reason?}`` for the default language, checked at most every 10 minutes."""
        if self.executable() is None:
            return {"available": False, "reason": "helper_missing"}
        value = None if refresh else self._cached()
        if value is None:
            with self._check_lock:
                value = None if refresh else self._cached()  # another request may just have checked
                if value is None:
                    value = self._check()
                    with self._lock:
                        self._status = (self._monotonic(), value)
        value = dict(value)
        if not value.get("available") and self._preparing:
            value["reason"] = "model_downloading"
        return value

    def _cached(self) -> Optional[Dict[str, Any]]:
        with self._lock:
            cached = self._status
        return cached[1] if cached is not None and self._monotonic() - cached[0] < CHECK_CACHE_SECONDS else None

    def _check(self) -> Dict[str, Any]:
        try:
            answer, _status = self._run(["--check", "--locale", DEFAULT_LANGUAGE], self._check_timeout)
        except subprocess.TimeoutExpired:
            return {"available": False, "reason": "check_timeout"}
        except TranscribeError as error:
            return {"available": False, "reason": error.reason or error.code}
        if not answer or answer.get("ok") is not True:
            return {"available": False, "reason": "check_failed"}
        value: Dict[str, Any] = {"available": answer.get("available") is True}
        if answer.get("engine") in ENGINES:
            value["engine"] = answer["engine"]
        if isinstance(answer.get("locale"), str) and _LANGUAGE.match(answer["locale"]):
            value["locale"] = answer["locale"]
        if not value["available"]:
            reason = answer.get("reason")
            value["reason"] = reason if isinstance(reason, str) and re.match(r"^[a-z_]{1,40}$", reason) \
                else "unavailable"
        return value

    def _remember(self, value: Optional[Dict[str, Any]]) -> None:
        with self._lock:
            self._status = (self._monotonic(), value) if value is not None else None

    # ------------------------------------------------------------------ the model
    def _prepare_later(self, language: str) -> bool:
        """Download the language's model in the background (once at a time, at most every 30 minutes)."""
        with self._lock:
            if self._closed or self._preparing is not None:
                return self._preparing == language
            last = self._prepared_at.get(language)
            if last is not None and self._monotonic() - last < PREPARE_RETRY_SECONDS:
                return False
            self._preparing = language
        threading.Thread(target=self._prepare, args=(language,), name="samrabbit-transcribe-model",
                         daemon=True).start()
        return True

    def _prepare(self, language: str) -> None:
        outcome = "failed"
        try:
            process = self._start(["--prepare", "--locale", language], tempfile.gettempdir())
            with self._lock:
                self._prepare_process = process
            answer = self._finish(process, self._prepare_timeout)
            outcome = "ready" if answer and answer.get("ok") is True else "failed"
        except subprocess.TimeoutExpired:
            outcome = "timed out"
        except Exception:  # noqa: BLE001 - a background download must never take the bridge down
            outcome = "failed"
        finally:
            with self._lock:
                self._preparing = None
                self._prepare_process = None
                self._prepared_at[language] = self._monotonic()
            self._remember(None)
        _LOG.info("speech model download %s", outcome)

    # ------------------------------------------------------------------ the route
    def transcribe(self, data: bytes, kind: str, language: str = DEFAULT_LANGUAGE) -> Dict[str, Any]:
        """``{text, durationMs, engine, locale}`` for a checked body (``check_audio``); TranscribeError otherwise."""
        if self.executable() is None:
            raise unavailable("helper_missing")
        if not self._slots.acquire(timeout=self._busy_wait):
            raise TranscribeError(503, "transcribe_busy", "The Mac is already transcribing. Try again in a moment.",
                                  retryable=True)
        try:
            folder = tempfile.mkdtemp(prefix="samrabbit-voice-")  # 0700
            with self._lock:
                self._running[folder] = None
            try:
                handle, path = tempfile.mkstemp(prefix="clip-", suffix=_SUFFIX.get(kind, ".audio"), dir=folder)
                try:
                    os.fchmod(handle, 0o600)
                    with os.fdopen(handle, "wb") as file:
                        file.write(data)
                except BaseException:
                    try:
                        os.close(handle)
                    except OSError:
                        pass
                    raise
                process = self._start(["--file", path, "--locale", language, "--max-seconds",
                                       str(MAX_AUDIO_SECONDS)], folder)
                with self._lock:
                    self._running[folder] = process
                try:
                    answer = self._finish(process, self._timeout)
                except subprocess.TimeoutExpired:
                    raise TranscribeError(504, "transcribe_failed", "Transcribing took too long on the Mac.",
                                          retryable=True) from None
            finally:
                with self._lock:
                    self._running.pop(folder, None)
                shutil.rmtree(folder, ignore_errors=True)  # the audio never outlives the request
        finally:
            self._slots.release()
        return self._answer(answer, process.returncode, language)

    def _answer(self, answer: Optional[Dict[str, Any]], status: int, language: str) -> Dict[str, Any]:
        if answer is not None and answer.get("ok") is True and isinstance(answer.get("text"), str):
            text = " ".join(_CONTROL.sub(" ", answer["text"]).split())[:MAX_TEXT_CHARS]
            if not text:
                raise TranscribeError(422, "no_speech", _FAILURES["no_speech"][1])
            duration = answer.get("durationMs")
            engine = answer.get("engine") if answer.get("engine") in ENGINES else "SpeechTranscriber"
            locale = answer.get("locale") if isinstance(answer.get("locale"), str) and \
                _LANGUAGE.match(answer["locale"]) else language
            if language == DEFAULT_LANGUAGE:
                self._remember({"available": True, "engine": engine, "locale": locale})
            return {"text": text, "durationMs": int(duration) if isinstance(duration, (int, float)) and
                    not isinstance(duration, bool) and duration >= 0 else 0, "engine": engine, "locale": locale}
        code = answer.get("code") if answer is not None and answer.get("ok") is False else None
        if code not in _FAILURES:
            _LOG.warning("transcription helper failed (exit %s)", status)
            raise TranscribeError(502, "transcribe_failed", "The transcription helper failed on the Mac.",
                                  retryable=True)
        detail = answer.get("detail") if isinstance(answer.get("detail"), str) else ""
        if code == "transcribe_unavailable":
            reason = detail if detail in _UNAVAILABLE_MESSAGES else None
            if reason == "model_missing":
                if self._prepare_later(language):
                    raise unavailable("model_downloading", retryable=True)
                raise unavailable("model_missing")
            if language == DEFAULT_LANGUAGE:
                self._remember(None)  # /health checks again
            if reason == "insufficient_resources":
                raise unavailable(reason, retryable=True)
            raise unavailable(reason or "speech_unavailable")
        if code == "transcribe_permission":
            self._remember(None)
        if code == "transcribe_failed":  # the detail is logged only as an error domain and number
            _LOG.warning("transcription failed (%s)", detail if _ERROR_DETAIL.match(detail) else "no detail")
        http, message, retryable = _FAILURES[code]
        raise TranscribeError(http, code, message, retryable=retryable)
