"""The single conversation-sync sender thread (``sam-conversation-sync``).

``step()`` does at most one network action and returns True when it did work. The loop drains
until idle, then sleeps until the next retry is due (``next_due()``), ``idle_seconds`` at most,
or until ``wake()`` (new events, the Mac answering again). ``drain()`` and ``wait_until()``
run steps inline for tests.
"""

from __future__ import annotations

from collections.abc import Callable
import threading
import time

from sam_runtime.core.logging import runtime_logger

_LOG = runtime_logger()
BACKOFF_SECONDS = (2, 5, 15, 60, 300)


def backoff_seconds(failures: int) -> int:
    return BACKOFF_SECONDS[min(max(failures, 1), len(BACKOFF_SECONDS)) - 1]


class ConversationSyncWorker:
    def __init__(self, step: Callable[[], bool], next_due: Callable[[], float | None], *,
                 idle_seconds: float = 30.0, clock: Callable[[], float] = time.time) -> None:
        self._step = step
        self._next_due = next_due
        self._idle = idle_seconds
        self._clock = clock
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._changed = threading.Condition()
        self._inline = threading.Lock()
        self._thread: threading.Thread | None = None

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="sam-conversation-sync", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        if self._thread is not None:
            self._thread.join(timeout=5)
        self._thread = None

    def wake(self) -> None:
        self._wake.set()

    def drain(self, limit: int = 100) -> int:
        done = 0
        with self._inline:
            while done < limit and self._safe_step():
                done += 1
        return done

    def wait_until(self, predicate: Callable[[], bool], timeout: float) -> bool:
        if not self.running:
            deadline = time.monotonic() + timeout
            with self._inline:
                while not predicate() and time.monotonic() < deadline:
                    if not self._safe_step():
                        break
            return predicate()
        self.wake()
        with self._changed:
            return self._changed.wait_for(predicate, timeout)

    def _safe_step(self) -> bool:
        try:
            did = self._step()
        except Exception:
            _LOG.exception("conversation_sync.step_failed")
            did = False
        with self._changed:
            self._changed.notify_all()
        return did

    def _sleep_seconds(self) -> float:
        try:
            due = self._next_due()
        except Exception:
            due = None
        if due is None:
            return self._idle
        return max(0.05, min(self._idle, due - self._clock()))

    def _run(self) -> None:
        while not self._stop.is_set():
            self._wake.clear()  # before draining, so a wake() during the drain is never lost
            while not self._stop.is_set() and self._safe_step():
                pass
            self._wake.wait(self._sleep_seconds())
