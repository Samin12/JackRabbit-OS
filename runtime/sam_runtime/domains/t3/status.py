"""Pure T3 shell/thread interpretation: status, labels, ordering, pending requests.

Status mirrors T3's own sidebar (``resolveSidebarThreadStatus``) plus one
addition: an unsettled thread whose latest turn errored reads as ``error``.
Settled threads are demoted: an error the user already settled reads as done.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, datetime


NEEDS_APPROVAL = "needs-approval"
NEEDS_INPUT = "needs-input"
WORKING = "working"
ERROR = "error"
DONE = "done"
STATUS_ORDER = {NEEDS_APPROVAL: 0, NEEDS_INPUT: 1, WORKING: 2, ERROR: 3, DONE: 4}
NEEDS_YOU = frozenset({NEEDS_APPROVAL, NEEDS_INPUT})
DEFAULT_APPROVAL_OPTIONS = (
    {"decision": "accept", "label": "Approve"},
    {"decision": "acceptForSession", "label": "Always allow this session"},
    {"decision": "decline", "label": "Decline"},
    {"decision": "cancel", "label": "Cancel"},
)
_STALE_APPROVAL = (
    "stale pending approval request",
    "unknown pending approval request",
    "unknown pending permission request",
    "unknown pending codex approval request",
)
_STALE_INPUT = (
    "stale pending user-input request",
    "unknown pending user-input request",
    "unknown pending user input request",
    "unknown pending codex user input request",
)
_REQUEST_KIND_BY_TYPE = {
    "command_execution_approval": "command",
    "exec_command_approval": "command",
    "dynamic_tool_call": "command",
    "file_read_approval": "file-read",
    "file_change_approval": "file-change",
    "apply_patch_approval": "file-change",
    "mcp_elicitation_approval": "mcp-elicitation",
    "permission_approval": "permission",
}
_REQUEST_KINDS = frozenset({"command", "file-read", "file-change", "mcp-elicitation", "permission"})


def _dict(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def _text(value: object) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def parse_time(value: object) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


def is_settled(thread: dict[str, object]) -> bool:
    return thread.get("settledAt") is not None


def is_snoozed(thread: dict[str, object], now: datetime) -> bool:
    until = parse_time(thread.get("snoozedUntil"))
    return until is not None and until > now


def raw_status(thread: dict[str, object]) -> str:
    """Status before settlement demotion (T3 sidebar order + latest-turn error)."""
    session = _dict(thread.get("session"))
    latest = _dict(thread.get("latestTurn"))
    session_status = session.get("status")
    if thread.get("hasPendingApprovals") is True:
        return NEEDS_APPROVAL
    if thread.get("hasPendingUserInput") is True:
        return NEEDS_INPUT
    if session_status in {"running", "starting"}:
        return WORKING
    if session_status == "error":
        return ERROR
    if thread.get("backgroundLiveness") in {"working", "monitoring"}:
        return WORKING
    if latest.get("state") == "error":
        return ERROR
    return DONE


ACTIVITY_ACTIVE = "active"
ACTIVITY_BACKGROUND = "background"
ACTIVITY_IDLE = "idle"
_LIVE_SESSION = frozenset({"running", "starting"})
_BACKGROUND_LIVE = frozenset({"working", "monitoring"})


def foreground_active(thread: dict[str, object]) -> bool:
    """A turn is running or starting, or a request is waiting on the user."""
    return (
        thread.get("hasPendingApprovals") is True
        or thread.get("hasPendingUserInput") is True
        or _dict(thread.get("session")).get("status") in _LIVE_SESSION
    )


def activity_level(threads: Iterable[object]) -> str:
    """How busy T3 is, for the shell poll cadence (not for display).

    ``active``: some thread runs a turn or waits on the user. ``background``: threads are only
    background-live (``backgroundLiveness`` working/monitoring with no running session and
    nothing pending), e.g. the parent session of long background tasks, which shows as
    "Background work" but produces no turn to announce. ``idle``: nothing working or pending.
    """
    level = ACTIVITY_IDLE
    for thread in threads:
        if not isinstance(thread, dict):
            continue
        if foreground_active(thread):
            return ACTIVITY_ACTIVE
        if thread.get("backgroundLiveness") in _BACKGROUND_LIVE:
            level = ACTIVITY_BACKGROUND
    return level


def thread_status(thread: dict[str, object]) -> str:
    status = raw_status(thread)
    if status == ERROR and is_settled(thread):
        return DONE
    return status


def status_label(thread: dict[str, object], status: str) -> str:
    session = _dict(thread.get("session"))
    latest = _dict(thread.get("latestTurn"))
    if status == NEEDS_APPROVAL:
        return "Needs approval"
    if status == NEEDS_INPUT:
        return "Has a question"
    if status == WORKING:
        progress = _dict(thread.get("planProgress"))
        total = progress.get("totalSteps")
        done = progress.get("completedSteps")
        if session.get("status") == "starting":
            return "Starting"
        if session.get("status") != "running" and thread.get("backgroundLiveness") == "monitoring":
            return "Monitoring"
        if session.get("status") != "running" and thread.get("backgroundLiveness") == "working":
            return "Background work"
        if isinstance(total, int) and isinstance(done, int) and total > 0:
            return f"Step {min(done + 1, total)} of {total}"
        return "Working"
    if status == ERROR:
        return "Failed"
    if not latest:
        return "New"
    if latest.get("state") == "interrupted":
        return "Stopped"
    return "Done"


def plan_phase(thread: dict[str, object]) -> tuple[str | None, float | None]:
    progress = _dict(thread.get("planProgress"))
    step = _text(progress.get("step"))
    total = progress.get("totalSteps")
    done = progress.get("completedSteps")
    fraction = None
    if isinstance(total, int) and isinstance(done, int) and total > 0:
        fraction = round(max(0.0, min(1.0, done / total)), 3)
    return (step[:80] if step else None), fraction


def activity_time(thread: dict[str, object]) -> str:
    """When something last happened in the thread (not settle/rename bookkeeping)."""
    latest = _dict(thread.get("latestTurn"))
    candidates = [
        latest.get("completedAt"),
        latest.get("startedAt"),
        latest.get("requestedAt"),
        thread.get("latestUserMessageAt"),
        thread.get("createdAt"),
    ]
    best: tuple[datetime, str] | None = None
    for value in candidates:
        parsed = parse_time(value)
        if parsed is not None and (best is None or parsed > best[0]):
            best = (parsed, str(value))
    if best is not None:
        return best[1]
    return str(thread.get("updatedAt") or "")


def summary_time(thread: dict[str, object], status: str) -> str:
    if status in NEEDS_YOU:
        return str(thread.get("updatedAt") or activity_time(thread))
    return activity_time(thread)


def completed_at(thread: dict[str, object]) -> str | None:
    latest = _dict(thread.get("latestTurn"))
    if latest.get("state") in {"completed", "error", "interrupted"}:
        value = latest.get("completedAt")
        return str(value) if value else None
    return None


def unread_marker(thread: dict[str, object], status: str) -> str | None:
    """Value compared against the stored seen marker; ``None`` means never unread."""
    if status == WORKING:
        return None
    if status in NEEDS_YOU:
        return str(thread.get("updatedAt") or "") or None
    return completed_at(thread)


def seen_marker(thread: dict[str, object]) -> str:
    """Marker stored when the user views a thread (covers both done and needs-you)."""
    values = [value for value in (completed_at(thread), thread.get("updatedAt")) if value]
    parsed = [(parse_time(value), str(value)) for value in values]
    parsed = [item for item in parsed if item[0] is not None]
    if not parsed:
        return ""
    return max(parsed, key=lambda item: item[0])[1]


def is_unread(marker: str | None, seen: str | None) -> bool:
    if not marker:
        return False
    if not seen:
        return True
    marker_time, seen_time = parse_time(marker), parse_time(seen)
    if marker_time is None or seen_time is None:
        return marker != seen
    return marker_time > seen_time


def project_titles(shell: dict[str, object]) -> dict[str, str]:
    return {
        str(project.get("id")): str(project.get("title") or "Project")
        for project in shell.get("projects", []) or []
        if isinstance(project, dict) and project.get("id")
    }


def summarize(thread: dict[str, object], projects: dict[str, str], *, seen: str | None) -> dict[str, object]:
    status = thread_status(thread)
    phase, progress = plan_phase(thread)
    model = _dict(thread.get("modelSelection")).get("model")
    project_id = str(thread.get("projectId") or "")
    return {
        "id": str(thread.get("id") or ""),
        "projectId": project_id,
        "projectTitle": projects.get(project_id, "Project"),
        "title": str(thread.get("title") or "Untitled thread"),
        "status": status,
        "statusLabel": status_label(thread, status),
        "updatedAt": summary_time(thread, status),
        "completedAt": completed_at(thread),
        "unread": is_unread(unread_marker(thread, status), seen),
        "model": str(model) if isinstance(model, str) and model else None,
        "phase": phase if status == WORKING else None,
        "progress": progress if status == WORKING else None,
    }


def order_key(thread: dict[str, object], summary: dict[str, object], now: datetime) -> tuple[object, ...]:
    """Actionable first, then unsettled done, then settled/snoozed; most recent first."""
    status = str(summary["status"])
    shelved = 1 if status in {DONE, ERROR} and (is_settled(thread) or is_snoozed(thread, now)) else 0
    stamp = parse_time(summary.get("updatedAt"))
    epoch = -(stamp.timestamp() if stamp else 0.0)
    return (shelved, STATUS_ORDER.get(status, 9), epoch, str(summary["id"]))


def counts(summaries: list[dict[str, object]]) -> dict[str, int]:
    result = {"needsYou": 0, "working": 0, "done": 0, "error": 0}
    for item in summaries:
        status = item.get("status")
        if status in NEEDS_YOU:
            result["needsYou"] += 1
        elif status == WORKING:
            result["working"] += 1
        elif status == ERROR:
            result["error"] += 1
        else:
            result["done"] += 1
    return result


@dataclass(frozen=True, slots=True)
class PendingApproval:
    request_id: str
    kind: str
    detail: str | None
    options: tuple[dict[str, str], ...]
    created_at: str

    def view(self) -> dict[str, object]:
        return {
            "requestId": self.request_id,
            "kind": self.kind,
            "detail": self.detail,
            "options": [dict(option) for option in self.options],
        }


@dataclass(frozen=True, slots=True)
class PendingQuestion:
    question_id: str
    header: str
    question: str
    options: tuple[str, ...]
    allow_custom: bool
    multi_select: bool
    # What T3 expects back for each option: ``option.value ?? option.label``
    # (apps/web pendingUserInput.ts). Parallel to ``options``; labels are for display.
    values: tuple[str, ...] = ()

    def answer_value(self, index: int) -> str:
        return self.values[index] if index < len(self.values) else self.options[index]

    def view(self) -> dict[str, object]:
        return {
            "id": self.question_id,
            "header": self.header,
            "question": self.question,
            "options": list(self.options),
            "allowCustom": self.allow_custom,
            "multiSelect": self.multi_select,
        }


@dataclass(frozen=True, slots=True)
class PendingInput:
    request_id: str
    questions: tuple[PendingQuestion, ...]
    dismissible: bool
    created_at: str

    def view(self) -> dict[str, object]:
        return {"requestId": self.request_id, "questions": [item.view() for item in self.questions]}


@dataclass(frozen=True, slots=True)
class PendingRequests:
    approvals: tuple[PendingApproval, ...] = ()
    inputs: tuple[PendingInput, ...] = ()

    def view(self) -> dict[str, object]:
        return {
            "approvals": [item.view() for item in self.approvals],
            "inputs": [item.view() for item in self.inputs],
        }

    @property
    def count(self) -> int:
        return len(self.approvals) + len(self.inputs)


def _questions(value: object) -> tuple[PendingQuestion, ...]:
    if not isinstance(value, list):
        return ()
    result = []
    for raw in value:
        if not isinstance(raw, dict) or not isinstance(raw.get("options"), list):
            continue
        kept = [
            option for option in raw["options"]
            if isinstance(option, dict) and isinstance(option.get("label"), str)
        ]
        options = tuple(str(option["label"]) for option in kept)
        values = tuple(
            str(option["value"]) if isinstance(option.get("value"), str) else str(option["label"])
            for option in kept
        )
        allow_custom = raw.get("allowCustomAnswer") is not False
        if not options and not allow_custom:
            continue
        if not all(isinstance(raw.get(key), str) for key in ("id", "header", "question")):
            continue
        result.append(PendingQuestion(
            question_id=str(raw["id"]),
            header=str(raw["header"]),
            question=str(raw["question"]),
            options=options,
            allow_custom=allow_custom,
            multi_select=raw.get("multiSelect") is True,
            values=values,
        ))
    return tuple(result)


def pending_requests(activities: object) -> PendingRequests:
    """Mirror of T3 ``derivePendingRequests`` (packages/client-runtime/src/pendingRequests.ts)."""
    approvals: dict[str, PendingApproval] = {}
    inputs: dict[str, PendingInput] = {}
    closed_approvals: set[str] = set()
    closed_inputs: set[str] = set()
    for activity in activities if isinstance(activities, list) else []:
        if not isinstance(activity, dict):
            continue
        kind = activity.get("kind")
        payload = _dict(activity.get("payload"))
        request_id = payload.get("requestId")
        if not isinstance(request_id, str) or not request_id:
            continue
        created = str(activity.get("createdAt") or "")
        detail_lower = str(payload.get("detail") or "").lower()
        if kind == "approval.requested":
            if request_id in closed_approvals or payload.get("requestType") in {"tool_user_input", "auth_tokens_refresh"}:
                continue
            request_kind = payload.get("requestKind")
            if request_kind not in _REQUEST_KINDS:
                request_kind = _REQUEST_KIND_BY_TYPE.get(str(payload.get("requestType")), "command")
            options = tuple(
                {"decision": str(option["decision"]), "label": str(option["label"])}
                for option in payload.get("options", []) or []
                if isinstance(option, dict) and isinstance(option.get("decision"), str) and isinstance(option.get("label"), str)
            )
            approvals[request_id] = PendingApproval(
                request_id=request_id,
                kind=str(request_kind),
                detail=_text(payload.get("detail")),
                options=options or DEFAULT_APPROVAL_OPTIONS,
                created_at=created,
            )
        elif kind == "user-input.requested":
            if request_id in closed_inputs:
                continue
            questions = _questions(payload.get("questions"))
            if not questions:
                continue
            inputs[request_id] = PendingInput(
                request_id=request_id,
                questions=questions,
                dismissible=payload.get("responseMode") == "message",
                created_at=created,
            )
        elif kind == "approval.resolved" or (
            kind == "provider.approval.respond.failed" and any(item in detail_lower for item in _STALE_APPROVAL)
        ):
            closed_approvals.add(request_id)
            approvals.pop(request_id, None)
        elif kind == "user-input.resolved" or (
            kind == "provider.user-input.respond.failed" and any(item in detail_lower for item in _STALE_INPUT)
        ):
            closed_inputs.add(request_id)
            inputs.pop(request_id, None)
    return PendingRequests(
        approvals=tuple(sorted(approvals.values(), key=lambda item: item.created_at)),
        inputs=tuple(sorted(inputs.values(), key=lambda item: item.created_at)),
    )


def last_assistant_text(thread_detail: dict[str, object]) -> str | None:
    messages = _dict(thread_detail.get("thread")).get("messages") or thread_detail.get("messages")
    for message in reversed(messages if isinstance(messages, list) else []):
        if isinstance(message, dict) and message.get("role") == "assistant":
            text = _text(message.get("text"))
            if text:
                return text
    return None


def condense(text: str | None, limit: int) -> str:
    """Collapse whitespace and trim to ``limit`` characters at a word boundary."""
    if not text:
        return ""
    flat = " ".join(str(text).replace("```", " ").split())
    if len(flat) <= limit:
        return flat
    cut = flat[: max(1, limit - 1)]
    space = cut.rfind(" ")
    if space > limit * 0.6:
        cut = cut[:space]
    return cut.rstrip(" ,;:-") + "…"
