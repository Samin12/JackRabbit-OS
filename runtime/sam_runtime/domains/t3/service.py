"""T3 Code domain service: pairing, cached shell snapshot, views, and commands.

One T3 server per R1. The runtime keeps an in-memory snapshot of the T3
shell (refreshed by ``T3SyncWorker``) so device routes and voice tools answer
instantly; thread details are fetched on demand with a short cache.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import replace
from datetime import UTC, datetime, timedelta
import json
import sqlite3
import threading
import time
from uuid import uuid4

from sam_runtime.core.logging import runtime_logger
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.storage.announcements import AnnouncementRepository

from .client import (
    T3Endpoint,
    T3Error,
    T3HttpClient,
    T3RequestError,
    T3Unauthorized,
    T3Unavailable,
    normalize_pairing_code,
    parse_server_url,
)
from .commands import (
    DEFAULT_INTERACTION_MODE,
    DEFAULT_MODEL_SELECTION,
    DEFAULT_RUNTIME_MODE,
    MAX_PROMPT_CHARS,
    RUNTIME_MODES,
    T3CommandBuilder,
    title_from_prompt,
)
from .matching import match_item
from .repository import T3ConnectionRecord, T3Repository
from .status import (
    DONE,
    ERROR,
    NEEDS_APPROVAL,
    NEEDS_INPUT,
    NEEDS_YOU,
    WORKING,
    PendingInput,
    PendingQuestion,
    PendingRequests,
    activity_time,
    condense,
    counts,
    last_assistant_text,
    order_key,
    parse_time,
    pending_requests,
    project_titles,
    seen_marker,
    summarize,
)
from .transitions import ERROR_EVENT, FINISHED, NEEDS_APPROVAL_EVENT, NEEDS_INPUT_EVENT, T3TransitionTracker, Transition


DEFAULT_SERVER_URL = "http://192.168.1.183:3773"
REAUTH_DETAIL = "T3 Code no longer accepts this R1. Pair again from Management > Connections."
EXPIRED_DETAIL = "T3 access expired. Pair again from Management > Connections."
MAX_MESSAGE_CHARS = 6000
MAX_MESSAGES = 40
LAST_MESSAGE_CHARS = 400
_DETAIL_TTL = 1.5
_PERSIST_SYNC_EVERY = 60.0
_FAIL_FAST_SECONDS = 5.0
_TURN_START_GRACE = 45.0
_APPROVAL_PHRASES = {
    "command": "wants approval to run a command",
    "file-change": "wants approval to edit files",
    "file-read": "wants approval to read files",
    "permission": "is asking for a permission",
    "mcp-elicitation": "wants approval for a tool request",
}

ClientFactory = Callable[[T3Endpoint, "str | None"], T3HttpClient]


class T3NotConnected(T3Error):
    code = "t3_not_connected"


class T3ReauthRequired(T3Error):
    code = "t3_reauth_required"


class T3ThreadNotFound(T3Error):
    code = "t3_thread_not_found"


class T3InvalidRequest(T3Error, ValueError):
    code = "invalid_request"


class T3RequestNotPending(T3Error):
    code = "t3_request_not_pending"


class T3DispatchFailed(T3Error):
    code = "t3_dispatch_failed"

    def __init__(self, message: str, thread_id: str | None = None) -> None:
        super().__init__(message)
        self.thread_id = thread_id


def _utcnow() -> datetime:
    return datetime.now(UTC)


def _iso(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _quoted(title: str) -> str:
    return "“" + " ".join(title.split())[:80] + "”"


def _is_scratch(project: dict[str, object]) -> bool:
    root = str(project.get("workspaceRoot") or "").rstrip("/")
    return root.endswith("/.t3/scratch") or str(project.get("title") or "").strip().lower() == "no project"


class T3Service:
    def __init__(
        self,
        repository: T3Repository,
        envelopes: ConnectionCredentialEnvelopes,
        *,
        announcements: AnnouncementRepository | None = None,
        client_factory: ClientFactory | None = None,
        resolver: Callable[[str], tuple[str, ...]] | None = None,
        commands: T3CommandBuilder | None = None,
        clock: Callable[[], datetime] = _utcnow,
    ) -> None:
        self._repository = repository
        self._envelopes = envelopes
        self._announcements = announcements
        self._client_factory: ClientFactory = client_factory or (lambda endpoint, token: T3HttpClient(endpoint, token))
        self._resolver = resolver
        self._commands = commands or T3CommandBuilder()
        self._clock = clock
        self._log = runtime_logger()
        self._lock = threading.RLock()
        self._sync_lock = threading.Lock()
        self._wake: Callable[[], None] = lambda: None
        self._loaded = False
        self._record: T3ConnectionRecord | None = None
        self._token: str | None = None
        self._threads: dict[str, dict[str, object]] = {}
        self._projects: list[dict[str, object]] = []
        self._snapshot_sequence = -1
        self._seen: dict[str, str] = {}
        self._summaries: list[dict[str, object]] = []
        self._project_views: list[dict[str, object]] = []
        self._has_snapshot = False
        self._revision = 0
        self._fingerprint: str | None = None
        self._revision_at = _iso(clock())
        self._last_sync_at: str | None = None
        self._last_sync_persisted = 0.0
        self._tracker = T3TransitionTracker()
        self._detail_cache: dict[tuple[str, int], tuple[float, dict[str, object]]] = {}
        self._down_until = 0.0
        # thread id -> (monotonic time, latest turn id before) for turns this R1 just
        # requested; T3 still reports the previous turn until the provider starts.
        self._turn_requests: dict[str, tuple[float, str | None]] = {}

    # ------------------------------------------------------------------ state
    def set_wake(self, wake: Callable[[], None]) -> None:
        self._wake = wake

    def _load(self) -> None:
        if self._loaded:
            return
        record = self._repository.get()
        seen = self._repository.seen_markers() if record is not None else {}
        self._record = record
        self._token = None
        self._seen = seen
        self._loaded = True
        if record is not None:
            self._last_sync_at = record.last_sync_at

    def _effective_health(self, record: T3ConnectionRecord) -> tuple[str, str | None]:
        expires = parse_time(record.expires_at)
        if expires is not None and expires <= self._clock():
            return "reauth", EXPIRED_DETAIL
        return record.health_state, record.health_detail

    def _current(self) -> T3ConnectionRecord | None:
        """The stored connection, or None if storage is not ready (never raises)."""
        with self._lock:
            try:
                self._load()
            except sqlite3.Error:
                return None
            return self._record

    def has_credential(self) -> bool:
        return self._current() is not None

    def connected(self) -> bool:
        """Paired and not waiting for re-pairing (the server may still be briefly unreachable)."""
        record = self._current()
        return record is not None and self._effective_health(record)[0] != "reauth"

    def needs_reauth(self) -> bool:
        record = self._current()
        return record is not None and self._effective_health(record)[0] == "reauth"

    def has_active(self) -> bool:
        with self._lock:
            return any(item["status"] in NEEDS_YOU or item["status"] == WORKING for item in self._summaries)

    def status_view(self) -> dict[str, object]:
        record = self._current()
        with self._lock:
            if record is None:
                return {
                    "connected": False, "serverUrl": None, "label": None, "healthState": "unconfigured",
                    "detail": None, "expiresAt": None, "lastSyncAt": None,
                }
            health, detail = self._effective_health(record)
            if health == "ready":
                detail = self._expiry_hint(record) or detail
            return {
                "connected": health != "reauth",
                "serverUrl": record.server_url,
                "label": record.environment_label,
                "healthState": health,
                "detail": detail,
                "expiresAt": record.expires_at,
                "lastSyncAt": self._last_sync_at,
            }

    def management_view(self) -> dict[str, object]:
        view = self.status_view()
        with self._lock:
            record = self._record
            view.update({
                "credentialPresent": record is not None,
                "defaultServerUrl": DEFAULT_SERVER_URL,
                "scopes": record.scopes.split() if record is not None and record.scopes else [],
                "counts": counts(self._summaries) if record is not None and self._has_snapshot else None,
                "threadCount": len(self._summaries) if record is not None and self._has_snapshot else None,
            })
        return view

    def _expiry_hint(self, record: T3ConnectionRecord) -> str | None:
        expires = parse_time(record.expires_at)
        if expires is None:
            return None
        remaining = expires - self._clock()
        if remaining <= timedelta(days=3):
            days = max(0, remaining.days)
            when = "today" if days == 0 else ("in 1 day" if days == 1 else f"in {days} days")
            return f"Access expires {when}. Pair again soon."
        return None

    # ---------------------------------------------------------------- pairing
    def connect(self, server_url: object, pairing_code: object) -> dict[str, object]:
        code, link_url = normalize_pairing_code(pairing_code)
        raw_url = str(server_url or "").strip() or link_url or DEFAULT_SERVER_URL
        endpoint = parse_server_url(raw_url, **({"resolver": self._resolver} if self._resolver else {}))
        anonymous = self._client_factory(endpoint, None)
        environment_label: str | None = None
        try:
            descriptor = anonymous.descriptor()
            label = descriptor.get("label")
            environment_label = str(label).strip()[:80] if isinstance(label, str) and label.strip() else None
        except T3Unavailable:
            raise
        except T3Error:
            environment_label = None
        exchanged = anonymous.exchange_pairing_code(code)
        token = str(exchanged["access_token"])
        authed = self._client_factory(endpoint, token)
        expires_at: str | None = None
        scopes = str(exchanged.get("scope") or "")
        try:
            session = authed.session_state()
            if isinstance(session.get("expiresAt"), str):
                expires_at = str(session["expiresAt"])
            if isinstance(session.get("scopes"), list):
                scopes = " ".join(str(item) for item in session["scopes"])
        except T3Unavailable:
            pass
        if expires_at is None and isinstance(exchanged.get("expires_in"), (int, float)):
            expires_at = _iso(self._clock() + timedelta(seconds=float(exchanged["expires_in"])))
        shell = authed.shell()
        connection_id = str(uuid4())
        envelope = self._envelopes.seal(connection_id, json.dumps({"token": token}, separators=(",", ":")))
        with self._sync_lock:
            record = self._repository.replace(
                connection_id=connection_id,
                server_url=endpoint.url,
                environment_label=environment_label,
                scopes=scopes,
                expires_at=expires_at,
                envelope=envelope,
            )
            with self._lock:
                self._loaded = True
                self._reset_state()
                self._record = record
                self._token = token
            transitions = self._apply_shell(shell)
        del transitions
        self._log.info("t3.connected", extra={"server": endpoint.url})
        self._wake()
        return self.management_view()

    def disconnect(self) -> dict[str, object]:
        with self._sync_lock:
            self._repository.remove()
            with self._lock:
                self._loaded = True
                self._reset_state()
        self._log.info("t3.disconnected")
        self._wake()
        view = self.management_view()
        view["revokeHint"] = "To also revoke this R1 on the Mac, open T3 Code > Settings > Connections."
        return view

    def _reset_state(self) -> None:
        self._record = None
        self._token = None
        self._threads = {}
        self._projects = []
        self._snapshot_sequence = -1
        self._seen = {}
        self._summaries = []
        self._project_views = []
        self._has_snapshot = False
        self._last_sync_at = None
        self._tracker.reset()
        self._detail_cache.clear()
        self._turn_requests.clear()
        self._recompute()

    # ------------------------------------------------------------- transport
    def _client(self, *, probe: bool = False) -> tuple[T3ConnectionRecord, T3HttpClient]:
        """Client for the paired server. Fails fast for a few seconds after a network
        failure (unless ``probe``), so screens polling an asleep Mac never pile up."""
        with self._lock:
            self._load()
            record = self._record
            if record is None:
                raise T3NotConnected("T3 Code is not connected.")
            health, _ = self._effective_health(record)
            if health == "reauth":
                raise T3ReauthRequired(EXPIRED_DETAIL if record.health_state != "reauth" else REAUTH_DETAIL)
            if not probe and time.monotonic() < self._down_until:
                raise T3Unavailable("T3 Code was unreachable moments ago.")
            if self._token is None:
                envelope = self._repository.envelope(record.connection_id)
                try:
                    opened = json.loads(self._envelopes.open(record.connection_id, envelope or ""))
                    self._token = str(opened["token"])
                except Exception:
                    raise T3NotConnected("The T3 Code credential is unavailable. Pair again.") from None
            endpoint = parse_server_url(record.server_url, verify_host=False)
            return record, self._client_factory(endpoint, self._token)

    def _guard(self, record: T3ConnectionRecord, call: Callable[[], object]) -> object:
        try:
            value = call()
        except T3Unauthorized:
            self._set_health(record, "reauth", REAUTH_DETAIL)
            raise T3ReauthRequired(REAUTH_DETAIL) from None
        except T3Unavailable:
            with self._lock:
                self._down_until = time.monotonic() + _FAIL_FAST_SECONDS
            raise
        with self._lock:
            self._down_until = 0.0
        return value

    def _set_health(self, record: T3ConnectionRecord, state: str, detail: str | None) -> None:
        with self._lock:
            current = self._record
            if current is None or current.connection_id != record.connection_id:
                return
            if current.health_state == state and current.health_detail == detail:
                return
            self._record = replace(current, health_state=state, health_detail=detail)
        try:
            self._repository.set_health(record.connection_id, state, detail)
        except Exception:
            self._log.warning("t3.health.persist_failed")

    # ------------------------------------------------------------------ sync
    def sync_once(self, *, probe: bool = True) -> bool:
        """Fetch the shell once, update the snapshot, announce transitions. Returns has_active."""
        with self._sync_lock:
            record, client = self._client(probe=probe)
            try:
                shell = self._guard(record, client.shell)
            except T3ReauthRequired:
                raise
            except T3Error:
                self._set_health(record, "failed", f"Cannot reach T3 Code at {record.server_url}.")
                raise
            transitions = self._apply_shell(shell)
            self._set_health(record, "ready", None)
            self._announce(record, client, transitions)
        return self.has_active()

    def ensure_snapshot(self) -> None:
        """Load the first snapshot on demand (route/tool called before the poller ran)."""
        with self._lock:
            ready = self._has_snapshot
        if not ready:
            try:
                self.sync_once(probe=False)
            except T3Error:
                pass

    def _apply_shell(self, shell: dict[str, object]) -> list[Transition]:
        sequence = shell.get("snapshotSequence")
        threads = [
            thread for thread in shell.get("threads", []) or []
            if isinstance(thread, dict) and thread.get("id") and thread.get("archivedAt") is None
        ]
        projects = [project for project in shell.get("projects", []) or [] if isinstance(project, dict) and project.get("id")]
        with self._lock:
            record = self._record
            if record is None:
                return []
            if isinstance(sequence, int):
                self._snapshot_sequence = sequence
            self._threads = {str(thread["id"]): thread for thread in threads}
            self._projects = projects
            self._has_snapshot = True
            if record.baseline_pending:
                markers = {str(thread["id"]): seen_marker(thread) for thread in threads}
                markers = {key: value for key, value in markers.items() if value}
                self._repository.finish_baseline(record.connection_id, markers)
                self._seen.update(markers)
                self._record = replace(record, baseline_pending=False)
            transitions = self._tracker.observe(threads)
            now = self._clock()
            self._last_sync_at = _iso(now)
            persist = time.monotonic() - self._last_sync_persisted >= _PERSIST_SYNC_EVERY
            if persist:
                self._last_sync_persisted = time.monotonic()
            self._recompute()
        if persist:
            try:
                self._repository.mark_synced(record.connection_id, self._last_sync_at or _iso(now))
            except Exception:
                self._log.warning("t3.sync.persist_failed")
        return transitions

    def _recompute(self) -> None:
        now = self._clock()
        titles = project_titles({"projects": self._projects})
        pairs = []
        for thread_id, thread in self._threads.items():
            summary = summarize(thread, titles, seen=self._seen.get(thread_id))
            pairs.append((order_key(thread, summary, now), summary))
        pairs.sort(key=lambda pair: pair[0])
        self._summaries = [summary for _, summary in pairs]
        recency: dict[str, datetime] = {}
        for thread in self._threads.values():
            stamp = parse_time(activity_time(thread))
            project_id = str(thread.get("projectId") or "")
            if stamp is not None and (project_id not in recency or stamp > recency[project_id]):
                recency[project_id] = stamp
        floor = datetime.min.replace(tzinfo=UTC)
        ordered = sorted(self._projects, key=lambda project: recency.get(str(project["id"]), floor), reverse=True)
        self._project_views = [{"id": str(project["id"]), "title": str(project.get("title") or "Project")} for project in ordered]
        fingerprint = json.dumps(
            [self._record is not None, self._summaries, self._project_views],
            sort_keys=True,
            separators=(",", ":"),
        )
        if fingerprint != self._fingerprint:
            self._fingerprint = fingerprint
            self._revision += 1
            self._revision_at = _iso(now)

    # --------------------------------------------------------- announcements
    def _announce(self, record: T3ConnectionRecord, client: T3HttpClient, transitions: list[Transition]) -> None:
        if self._announcements is None or not transitions:
            return
        titles = project_titles({"projects": self._projects})
        for transition in transitions:
            try:
                self._announce_one(record, client, transition, titles)
            except T3ReauthRequired:
                return
            except Exception:
                self._log.warning("t3.announce.failed", extra={"event": transition.event})

    def _announce_one(
        self,
        record: T3ConnectionRecord,
        client: T3HttpClient,
        transition: Transition,
        titles: dict[str, str],
    ) -> None:
        thread = transition.thread
        thread_id = transition.thread_id
        title = str(thread.get("title") or "Untitled thread")
        project_id = str(thread.get("projectId") or "")
        project_title = titles.get(project_id, "T3 Code")
        where = f"{_quoted(title)} in {project_title}"
        base = {
            "threadId": thread_id,
            "title": title,
            "projectId": project_id,
            "projectTitle": project_title,
            "turnId": transition.turn_id,
        }
        assert self._announcements is not None
        if transition.event == FINISHED:
            if not self._tracker.claim((thread_id, transition.turn_id or "", DONE)):
                return
            detail = self._detail_quiet(record, client, thread_id, 1)
            last = condense(last_assistant_text(detail), LAST_MESSAGE_CHARS) if detail else ""
            self._announcements.publish(
                "t3.thread.finished", title, f"{where} finished.",
                {**base, "status": DONE, "lastMessage": last},
            )
            return
        if transition.event == ERROR_EVENT:
            if not self._tracker.claim((thread_id, transition.turn_id or "", ERROR)):
                return
            session = thread.get("session") if isinstance(thread.get("session"), dict) else {}
            detail = self._detail_quiet(record, client, thread_id, 1)
            last = condense(last_assistant_text(detail), LAST_MESSAGE_CHARS) if detail else ""
            error_text = condense(str(session.get("lastError") or ""), 200)
            self._announcements.publish(
                "t3.thread.error", title, f"{where} hit an error.",
                {**base, "status": ERROR, "error": error_text or None, "lastMessage": last},
            )
            return
        detail = self._detail_quiet(record, client, thread_id, 1)
        pending = pending_requests((detail or {}).get("thread", {}).get("activities")) if detail else PendingRequests()
        if detail and pending.count == 0:
            wider = self._detail_quiet(record, client, thread_id, 5)
            if wider:
                detail, pending = wider, pending_requests(wider.get("thread", {}).get("activities"))
        last = condense(last_assistant_text(detail), LAST_MESSAGE_CHARS) if detail else ""
        if transition.event == NEEDS_APPROVAL_EVENT:
            approvals = pending.approvals or ()
            if not approvals:
                if self._tracker.claim((thread_id, transition.turn_id or "", NEEDS_APPROVAL)):
                    self._announcements.publish(
                        "t3.thread.needs_approval", title, f"{where} needs your approval.",
                        {**base, "status": NEEDS_APPROVAL, "requestId": None, "lastMessage": last},
                    )
                return
            for approval in approvals:
                if not self._tracker.claim((thread_id, approval.request_id, NEEDS_APPROVAL)):
                    continue
                phrase = _APPROVAL_PHRASES.get(approval.kind, "needs your approval")
                self._announcements.publish(
                    "t3.thread.needs_approval", title, f"{where} {phrase}.",
                    {
                        **base, "status": NEEDS_APPROVAL, "requestId": approval.request_id,
                        "requestKind": approval.kind, "detail": condense(approval.detail, 200) or None,
                        "lastMessage": last,
                    },
                )
            return
        if transition.event == NEEDS_INPUT_EVENT:
            inputs = pending.inputs or ()
            if not inputs:
                if self._tracker.claim((thread_id, transition.turn_id or "", NEEDS_INPUT)):
                    self._announcements.publish(
                        "t3.thread.needs_input", title, f"{where} has a question for you.",
                        {**base, "status": NEEDS_INPUT, "requestId": None, "lastMessage": last},
                    )
                return
            for request in inputs:
                if not self._tracker.claim((thread_id, request.request_id, NEEDS_INPUT)):
                    continue
                question = request.questions[0].question if request.questions else ""
                short = condense(question, 120)
                text = f"{where} asks: {short}" if short and len(question) <= 120 else f"{where} has a question for you."
                if text and text[-1] not in ".?!":
                    text += "?" if short else "."
                self._announcements.publish(
                    "t3.thread.needs_input", title, text,
                    {
                        **base, "status": NEEDS_INPUT, "requestId": request.request_id,
                        "question": condense(question, 300) or None, "lastMessage": last,
                    },
                )

    def _detail_quiet(self, record: T3ConnectionRecord, client: T3HttpClient, thread_id: str, turns: int) -> dict[str, object] | None:
        try:
            value = self._guard(record, lambda: client.thread(thread_id, turn_limit=turns))
        except T3ReauthRequired:
            raise
        except T3Error:
            return None
        return value if isinstance(value, dict) else None

    # ------------------------------------------------------------------ views
    def threads_view(self, limit: int = 40) -> dict[str, object]:
        limit = max(1, min(100, int(limit)))
        if self.connected():
            self.ensure_snapshot()
        with self._lock:
            connected = self._record is not None and self._effective_health(self._record)[0] != "reauth"
            return {
                "connected": connected,
                "revision": self._revision,
                "updatedAt": self._revision_at,
                "counts": counts(self._summaries) if connected else {"needsYou": 0, "working": 0, "done": 0, "error": 0},
                "projects": [dict(item) for item in self._project_views] if connected else [],
                "threads": [dict(item) for item in self._summaries[:limit]] if connected else [],
            }

    def summaries(self) -> list[dict[str, object]]:
        with self._lock:
            return [dict(item) for item in self._summaries]

    def projects(self) -> list[dict[str, object]]:
        with self._lock:
            return [dict(item) for item in self._project_views]

    def summary(self, thread_id: str) -> dict[str, object] | None:
        with self._lock:
            for item in self._summaries:
                if item["id"] == thread_id:
                    return dict(item)
        return None

    def thread_detail(self, thread_id: str, *, turns: int = 3, fresh: bool = False) -> dict[str, object]:
        record, client = self._client()
        turns = max(1, min(10, int(turns)))
        key = (thread_id, turns)
        if not fresh:
            with self._lock:
                cached = self._detail_cache.get(key)
            if cached is not None and time.monotonic() - cached[0] < _DETAIL_TTL:
                return cached[1]
        try:
            detail = self._guard(record, lambda: client.thread(thread_id, turn_limit=turns))
        except T3RequestError as error:
            if error.status == 404:
                raise T3ThreadNotFound("That T3 thread was not found.") from None
            raise
        assert isinstance(detail, dict)
        with self._lock:
            if len(self._detail_cache) > 24:
                self._detail_cache.clear()
            self._detail_cache[key] = (time.monotonic(), detail)
        return detail

    def _invalidate(self, thread_id: str) -> None:
        with self._lock:
            for key in [key for key in self._detail_cache if key[0] == thread_id]:
                self._detail_cache.pop(key, None)

    def _note_turn_requested(self, thread_id: str, previous_turn_id: object) -> None:
        with self._lock:
            self._turn_requests[thread_id] = (time.monotonic(), str(previous_turn_id) if previous_turn_id else None)
            if len(self._turn_requests) > 32:
                oldest = min(self._turn_requests, key=lambda key: self._turn_requests[key][0])
                self._turn_requests.pop(oldest, None)

    def _awaiting_turn_start(self, thread_id: str, thread: dict[str, object]) -> bool:
        """True while a turn this R1 requested has not shown up in ``thread`` yet."""
        with self._lock:
            requested = self._turn_requests.get(thread_id)
            if requested is None:
                return False
            at, previous_turn = requested
            session = thread.get("session") if isinstance(thread.get("session"), dict) else {}
            latest = thread.get("latestTurn") if isinstance(thread.get("latestTurn"), dict) else {}
            turn_id = str(latest.get("turnId")) if latest.get("turnId") else None
            started = session.get("status") in {"running", "starting"} or turn_id != previous_turn
            if started or time.monotonic() - at > _TURN_START_GRACE:
                self._turn_requests.pop(thread_id, None)
                return False
            return True

    def _summary_from_detail(self, thread: dict[str, object], pending: PendingRequests) -> dict[str, object]:
        shell_like = dict(thread)
        shell_like["hasPendingApprovals"] = bool(pending.approvals)
        shell_like["hasPendingUserInput"] = bool(pending.inputs)
        with self._lock:
            titles = project_titles({"projects": self._projects})
            seen = self._seen.get(str(thread.get("id")))
        return summarize(shell_like, titles, seen=seen)

    def thread_view(self, thread_id: str, *, turns: int = 3) -> dict[str, object]:
        detail = self.thread_detail(thread_id, turns=turns)
        thread = detail.get("thread") if isinstance(detail.get("thread"), dict) else {}
        pending = pending_requests(thread.get("activities"))
        summary = self.summary(thread_id) or self._summary_from_detail(thread, pending)
        messages = []
        for message in thread.get("messages", []) or []:
            if not isinstance(message, dict) or message.get("role") not in {"user", "assistant"}:
                continue
            text = str(message.get("text") or "")
            if len(text) > MAX_MESSAGE_CHARS:
                text = text[: MAX_MESSAGE_CHARS - 1] + "…"
            if not text.strip() and not message.get("streaming"):
                continue
            messages.append({
                "id": str(message.get("id") or ""),
                "role": str(message["role"]),
                "text": text,
                "createdAt": str(message.get("createdAt") or ""),
                "streaming": message.get("streaming") is True,
            })
        session = thread.get("session") if isinstance(thread.get("session"), dict) else {}
        active = session.get("activeTurnId") if session.get("status") in {"running", "starting"} else None
        return {
            "thread": summary,
            "messages": messages[-MAX_MESSAGES:],
            "pending": pending.view(),
            "activeTurnId": str(active) if active else None,
        }

    def pending(self, thread_id: str) -> PendingRequests:
        detail = self.thread_detail(thread_id, turns=1, fresh=True)
        pending = pending_requests(detail.get("thread", {}).get("activities"))
        if pending.count == 0:
            summary = self.summary(thread_id)
            if summary is not None and summary["status"] in NEEDS_YOU:
                wider = self.thread_detail(thread_id, turns=5, fresh=True)
                pending = pending_requests(wider.get("thread", {}).get("activities"))
        return pending

    def live_view(self, thread_id: str) -> dict[str, object]:
        detail = self.thread_detail(thread_id, turns=1)
        thread = detail.get("thread") if isinstance(detail.get("thread"), dict) else {}
        activities = thread.get("activities") if isinstance(thread.get("activities"), list) else []
        pending = pending_requests(activities)
        summary = self.summary(thread_id) or self._summary_from_detail(thread, pending)
        status = str(summary["status"])
        status_label = str(summary["statusLabel"])
        if status in {DONE, ERROR} and self._awaiting_turn_start(thread_id, thread):
            # Still showing the previous turn: a terminal snapshot now would make the
            # live card stop polling before the requested turn even starts.
            status, status_label = WORKING, "Starting"
        live_status = {WORKING: "active", NEEDS_APPROVAL: "warn", NEEDS_INPUT: "warn", ERROR: "error"}.get(status, "ok")
        latest = thread.get("latestTurn") if isinstance(thread.get("latestTurn"), dict) else None
        if status == DONE and not latest:
            live_status = "idle"
        # Terminal only for a finished turn (genui contract: ok/error); a thread with
        # no turn yet ("idle") may still start.
        terminal = live_status in {"ok", "error"}
        parts = [str(summary.get("projectTitle") or "")]
        if summary.get("model"):
            parts.append(str(summary["model"]))
        parts.append(status_label)
        if status == WORKING and latest and latest.get("startedAt"):
            started = parse_time(latest.get("startedAt"))
            if started is not None:
                minutes = int(max(0.0, (self._clock() - started).total_seconds()) // 60)
                parts.append(f"{minutes}m" if minutes else "<1m")
        if status == NEEDS_APPROVAL and pending.approvals:
            phase = condense(pending.approvals[0].detail or "Approval needed", 60)
        elif status == NEEDS_INPUT and pending.inputs and pending.inputs[0].questions:
            phase = condense(pending.inputs[0].questions[0].question, 60)
        else:
            phase = summary.get("phase") or status_label
        turn_id = latest.get("turnId") if latest else None
        items = _activity_items([item for item in activities if isinstance(item, dict) and (turn_id is None or item.get("turnId") in {turn_id, None})])
        return {
            "status": live_status,
            "terminal": terminal,
            "subtitle": " · ".join(part for part in parts if part),
            "phase": phase,
            "progress": summary.get("progress"),
            "items": items,
            "text": condense(last_assistant_text(detail), 280),
            "updatedAt": str(thread.get("updatedAt") or summary.get("updatedAt") or ""),
            "nextPollMs": 3000 if live_status == "active" else (5000 if live_status == "warn" else 20000),
        }

    # --------------------------------------------------------------- commands
    def _known_thread(self, thread_id: str) -> dict[str, object]:
        thread_id = str(thread_id or "").strip()
        if not thread_id:
            raise T3InvalidRequest("A thread id is required.")
        self.ensure_snapshot()
        with self._lock:
            thread = self._threads.get(thread_id)
        if thread is not None:
            return thread
        detail = self.thread_detail(thread_id, turns=1)
        thread = detail.get("thread")
        if not isinstance(thread, dict):
            raise T3ThreadNotFound("That T3 thread was not found.")
        return thread

    def _dispatch(self, record: T3ConnectionRecord, client: T3HttpClient, command: dict[str, object]) -> dict[str, object]:
        try:
            value = self._guard(record, lambda: client.dispatch(command))
        except T3Unavailable:
            value = self._guard(record, lambda: client.dispatch(command))
        return value if isinstance(value, dict) else {}

    def resolve_project(self, project_id: str | None = None, project: str | None = None) -> dict[str, object]:
        self.ensure_snapshot()
        with self._lock:
            projects = list(self._projects)
            order = [item["id"] for item in self._project_views]
        if not projects:
            raise T3InvalidRequest("T3 Code has no projects yet. Add one on the Mac first.")
        by_id = {str(item["id"]): item for item in projects}
        ordered = [by_id[str(item)] for item in order if str(item) in by_id]
        if project_id:
            found = by_id.get(str(project_id).strip())
            if found is None:
                raise T3InvalidRequest("That project was not found in T3 Code.")
            return found
        if project and str(project).strip():
            result = match_item(str(project), ordered)
            if result.item is not None:
                return result.item
            names = ", ".join(str(item.get("title")) for item in (result.candidates or ordered[:4]))
            raise T3InvalidRequest(f"No single project matches “{project}”. Projects: {names}.")
        for item in ordered:
            if not _is_scratch(item):
                return item
        return ordered[0]

    def create_thread(
        self,
        text: str,
        *,
        title: str | None = None,
        project_id: str | None = None,
        project: str | None = None,
        runtime_mode: str | None = None,
    ) -> dict[str, object]:
        prompt = str(text or "").strip()
        if not prompt:
            raise T3InvalidRequest("Say what the new thread should do.")
        if len(prompt) > MAX_PROMPT_CHARS:
            raise T3InvalidRequest("That prompt is too long.")
        if runtime_mode is not None and runtime_mode not in RUNTIME_MODES:
            raise T3InvalidRequest("runtimeMode is invalid.")
        record, client = self._client()
        chosen = self.resolve_project(project_id, project)
        chosen_id = str(chosen["id"])
        with self._lock:
            siblings = [thread for thread in self._threads.values() if str(thread.get("projectId")) == chosen_id]
        floor = datetime.min.replace(tzinfo=UTC)
        siblings.sort(key=lambda thread: parse_time(activity_time(thread)) or floor, reverse=True)
        recent = siblings[0] if siblings else None
        selection = None
        if recent is not None and isinstance(recent.get("modelSelection"), dict) and recent["modelSelection"].get("model"):
            selection = dict(recent["modelSelection"])
        elif isinstance(chosen.get("defaultModelSelection"), dict) and chosen["defaultModelSelection"].get("model"):
            selection = dict(chosen["defaultModelSelection"])
        selection = selection or dict(DEFAULT_MODEL_SELECTION)
        mode = runtime_mode or (str(recent.get("runtimeMode")) if recent is not None and recent.get("runtimeMode") in RUNTIME_MODES else DEFAULT_RUNTIME_MODE)
        name = " ".join(str(title or "").split())[:80] or title_from_prompt(prompt)
        thread_id = self._commands.new_id()
        create = self._commands.create_thread(
            thread_id=thread_id,
            project_id=chosen_id,
            title=name,
            model_selection=selection,
            runtime_mode=mode,
            interaction_mode=DEFAULT_INTERACTION_MODE,
        )
        try:
            self._dispatch(record, client, create)
        except T3ReauthRequired:
            raise
        except T3Error as error:
            raise T3DispatchFailed(f"T3 Code could not create the thread ({type(error).__name__}).") from None
        turn = self._commands.turn_start(
            thread_id=thread_id,
            text=prompt,
            runtime_mode=mode,
            interaction_mode=DEFAULT_INTERACTION_MODE,
            model_selection=selection,
            title_seed=name,
        )
        try:
            self._dispatch(record, client, turn)
        except T3ReauthRequired:
            raise
        except T3Error as error:
            raise T3DispatchFailed(
                f"The thread was created but the first message failed ({type(error).__name__}).", thread_id
            ) from None
        self._note_turn_requested(thread_id, None)
        self._log.info("t3.thread.created", extra={"project": chosen_id})
        self._wake()
        return {
            "threadId": thread_id,
            "title": name,
            "projectId": chosen_id,
            "projectTitle": str(chosen.get("title") or "Project"),
            "runtimeMode": mode,
            "model": selection.get("model"),
        }

    def send_message(self, thread_id: str, text: str) -> dict[str, object]:
        body = str(text or "").strip()
        if not body:
            raise T3InvalidRequest("The message is empty.")
        if len(body) > MAX_PROMPT_CHARS:
            raise T3InvalidRequest("That message is too long.")
        record, client = self._client()
        thread = self._known_thread(thread_id)
        mode = str(thread.get("runtimeMode")) if thread.get("runtimeMode") in RUNTIME_MODES else DEFAULT_RUNTIME_MODE
        interaction = str(thread.get("interactionMode") or DEFAULT_INTERACTION_MODE)
        command = self._commands.turn_start(thread_id=str(thread["id"]), text=body, runtime_mode=mode, interaction_mode=interaction)
        self._dispatch_or_fail(record, client, command)
        session = thread.get("session") if isinstance(thread.get("session"), dict) else {}
        was_working = session.get("status") in {"running", "starting"}
        if not was_working:
            latest = thread.get("latestTurn") if isinstance(thread.get("latestTurn"), dict) else {}
            self._note_turn_requested(str(thread["id"]), latest.get("turnId"))
        self._invalidate(str(thread["id"]))
        self._wake()
        return {"ok": True, "threadId": str(thread["id"]), "title": str(thread.get("title") or ""), "wasWorking": was_working}

    def respond_approval(self, thread_id: str, request_id: str, decision: str) -> dict[str, object]:
        decision = str(decision or "").strip()
        if decision not in {"accept", "acceptForSession", "acceptAlways", "decline", "cancel"}:
            raise T3InvalidRequest("decision must be accept, acceptForSession, decline, or cancel.")
        record, client = self._client()
        pending = self.pending(thread_id)
        approval = next((item for item in pending.approvals if item.request_id == request_id), None)
        if approval is None:
            raise T3RequestNotPending("That approval is no longer pending.")
        allowed = {option["decision"] for option in approval.options}
        if decision not in allowed:
            raise T3InvalidRequest("That decision is not offered for this approval.")
        command = self._commands.approval_respond(thread_id=thread_id, request_id=request_id, decision=decision)
        self._dispatch_or_fail(record, client, command)
        self._invalidate(thread_id)
        self._wake()
        return {"ok": True, "threadId": thread_id, "requestId": request_id, "decision": decision}

    def respond_input(self, thread_id: str, request_id: str, answers: object) -> dict[str, object]:
        if not isinstance(answers, dict) or not answers:
            raise T3InvalidRequest("answers must map each question id to an answer.")
        record, client = self._client()
        pending = self.pending(thread_id)
        request = next((item for item in pending.inputs if item.request_id == request_id), None)
        if request is None:
            raise T3RequestNotPending("That question is no longer pending.")
        resolved = resolve_answers(request, answers)
        command = self._commands.user_input_respond(thread_id=thread_id, request_id=request_id, answers=resolved)
        self._dispatch_or_fail(record, client, command)
        self._invalidate(thread_id)
        self._wake()
        return {"ok": True, "threadId": thread_id, "requestId": request_id, "answers": resolved}

    def interrupt(self, thread_id: str) -> dict[str, object]:
        record, client = self._client()
        thread = self._known_thread(thread_id)
        session = thread.get("session") if isinstance(thread.get("session"), dict) else {}
        running = session.get("status") in {"running", "starting"}
        background = thread.get("backgroundLiveness") in {"working", "monitoring"}
        if not running and not background:
            return {"ok": True, "threadId": str(thread["id"]), "wasWorking": False}
        turn_id = session.get("activeTurnId") if running else None
        command = self._commands.interrupt(thread_id=str(thread["id"]), turn_id=str(turn_id) if turn_id else None)
        self._dispatch_or_fail(record, client, command)
        self._invalidate(str(thread["id"]))
        self._wake()
        return {"ok": True, "threadId": str(thread["id"]), "wasWorking": True}

    def archive(self, thread_id: str) -> dict[str, object]:
        """Archive one thread (used for cleanup of test threads; not exposed to voice)."""
        record, client = self._client()
        self._dispatch_or_fail(record, client, self._commands.archive(thread_id=thread_id))
        self._wake()
        return {"ok": True, "threadId": thread_id}

    def _dispatch_or_fail(self, record: T3ConnectionRecord, client: T3HttpClient, command: dict[str, object]) -> None:
        try:
            self._dispatch(record, client, command)
        except T3ReauthRequired:
            raise
        except T3Error as error:
            raise T3DispatchFailed(f"T3 Code did not accept the command ({type(error).__name__}).") from None

    def mark_seen(self, thread_id: str) -> dict[str, object]:
        with self._lock:
            self._load()
            if self._record is None:
                raise T3NotConnected("T3 Code is not connected.")
            thread = self._threads.get(thread_id)
        marker = seen_marker(thread) if thread is not None else ""
        marker = marker or _iso(self._clock())
        self._repository.mark_seen({thread_id: marker})
        with self._lock:
            self._seen[thread_id] = marker
            self._recompute()
        return {"ok": True}


def resolve_answers(request: PendingInput, answers: dict[str, object]) -> dict[str, object]:
    """Map spoken/tapped answers onto option labels; free text only when allowed."""
    resolved: dict[str, object] = {}
    message_mode = request.dismissible
    for question in request.questions:
        raw = answers.get(question.question_id)
        if raw is None and len(request.questions) == 1 and len(answers) == 1:
            raw = next(iter(answers.values()))
        if raw is None:
            raise T3InvalidRequest(f"Answer the question “{condense(question.question, 80)}”.")
        values = raw if isinstance(raw, list) else [raw]
        picked = [_pick_option(question, str(value)) for value in values if str(value).strip()]
        if not picked:
            raise T3InvalidRequest(f"Answer the question “{condense(question.question, 80)}”.")
        if question.multi_select and not message_mode:
            resolved[question.question_id] = picked
        else:
            resolved[question.question_id] = ", ".join(picked) if len(picked) > 1 else picked[0]
    return resolved


def _pick_option(question: PendingQuestion, value: str) -> str:
    """The value T3 expects for one answer: ``option.value ?? option.label``, or free text."""
    text = " ".join(value.split())
    options = list(question.options)
    lowered = text.lower().strip(" .!")
    for index, option in enumerate(options):
        if option.lower() == lowered or question.answer_value(index).lower() == lowered:
            return question.answer_value(index)
    # "2" means the second option, unless the options are themselves numbers.
    numeric_labels = any(option.strip().isdigit() for option in options)
    if lowered.isdigit() and not numeric_labels and 1 <= int(lowered) <= len(options):
        return question.answer_value(int(lowered) - 1)
    ordinals = {"first": 0, "second": 1, "third": 2, "fourth": 3, "fifth": 4}
    for word, index in ordinals.items():
        if lowered in {word, f"the {word}", f"{word} one", f"the {word} one", f"{word} option", f"the {word} option"} and index < len(options):
            return question.answer_value(index)
    # A shortened label ("sqlite" for "Use SQLite") picks that option.
    shortened = [index for index, option in enumerate(options) if lowered and lowered in option.lower()]
    if len(shortened) == 1:
        return question.answer_value(shortened[0])
    # An answer that merely contains a label ("yes, but skip the tests") is the
    # user's own words: send it whole when free text is allowed.
    if question.allow_custom:
        return text
    containing = [index for index, option in enumerate(options) if option.lower() in lowered]
    if len(containing) == 1:
        return question.answer_value(containing[0])
    choices = "; ".join(options)
    raise T3InvalidRequest(f"Choose one of: {choices}.")


def _activity_items(activities: list[dict[str, object]]) -> list[dict[str, object]]:
    """The four most recent tool/task steps as live-card rows."""
    rows: dict[str, dict[str, object]] = {}
    order: list[str] = []
    for activity in activities:
        kind = str(activity.get("kind") or "")
        if not (kind.startswith("tool.") or kind.startswith("task.")):
            continue
        payload = activity.get("payload") if isinstance(activity.get("payload"), dict) else {}
        key = str(payload.get("toolCallId") or payload.get("taskId") or activity.get("id") or "")
        raw_status = str(payload.get("status") or "")
        if kind.endswith(".completed") or raw_status in {"completed", "done"}:
            status = "ok"
        elif raw_status in {"failed", "error"}:
            status = "error"
        else:
            status = "active"
        title = condense(str(payload.get("title") or activity.get("summary") or "Step"), 48)
        detail = str(payload.get("detail") or "")
        if detail.lower() in {title.lower(), "bash: {}"}:
            detail = ""
        row = {"title": title, "detail": condense(detail, 64) or None, "status": status, "trailing": None}
        if key not in rows:
            order.append(key)
        else:
            order.remove(key)
            order.append(key)
        rows[key] = row
    return [rows[key] for key in order[-4:]]
