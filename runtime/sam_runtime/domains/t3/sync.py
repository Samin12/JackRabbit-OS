"""Background T3 shell poller.

Polls every 2 s while any thread is working or needs the user, otherwise every
5 s. Network failures back off (5, 10, 20, 40, 60 s). A rejected credential
stops polling until the user pairs again. Any write or pairing wakes the
poller immediately so screens see the change within a moment.
"""

from __future__ import annotations

import threading

from sam_runtime.core.logging import runtime_logger

from .client import T3Error
from .service import T3NotConnected, T3ReauthRequired, T3Service


ACTIVE_INTERVAL = 2.0
IDLE_INTERVAL = 5.0
MAX_BACKOFF = 60.0
UNPAIRED_WAIT = 30.0


class T3SyncWorker:
    def __init__(
        self,
        service: T3Service,
        *,
        active_interval: float = ACTIVE_INTERVAL,
        idle_interval: float = IDLE_INTERVAL,
        max_backoff: float = MAX_BACKOFF,
    ) -> None:
        self._service = service
        self._active_interval = active_interval
        self._idle_interval = idle_interval
        self._max_backoff = max_backoff
        self._stop = threading.Event()
        self._wake = threading.Event()
        self._thread: threading.Thread | None = None
        self._failures = 0
        self._log = runtime_logger()
        service.set_wake(self.wake)

    def start(self) -> None:
        if self._thread is not None:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._run, name="sam-t3-sync", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        thread = self._thread
        self._thread = None
        if thread is not None:
            thread.join(timeout=3.0)

    def wake(self) -> None:
        self._wake.set()

    def _run(self) -> None:
        while not self._stop.is_set():
            delay = self.step()
            self._wake.wait(timeout=delay)
            self._wake.clear()

    def step(self) -> float:
        """Run one poll and return how long to wait before the next one."""
        if not self._service.has_credential() or self._service.needs_reauth():
            self._failures = 0
            return UNPAIRED_WAIT
        try:
            active = self._service.sync_once()
        except T3ReauthRequired:
            self._log.warning("t3.sync.reauth_required")
            return UNPAIRED_WAIT
        except T3NotConnected:
            return UNPAIRED_WAIT
        except T3Error as error:
            self._failures += 1
            if self._failures in {1, 3} or self._failures % 10 == 0:
                self._log.warning("t3.sync.failed", extra={"error": type(error).__name__, "failures": self._failures})
            return min(self._max_backoff, 5.0 * (2 ** min(self._failures - 1, 6)))
        except Exception:
            self._failures += 1
            self._log.exception("t3.sync.crashed")
            return min(self._max_backoff, 5.0 * (2 ** min(self._failures - 1, 6)))
        self._failures = 0
        return self._active_interval if active else self._idle_interval
