"""Builders for T3 ``POST /api/orchestration/dispatch`` commands.

Shapes follow ``ClientOrchestrationCommand`` in T3's contracts. Every command
gets a fresh UUIDv4 ``commandId`` (T3 dedupes retries by it). T3 overwrites
``createdAt`` with its own clock, so device clock skew is harmless.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
import uuid


DEFAULT_MODEL_SELECTION = {"instanceId": "claudeAgent", "model": "claude-opus-5-5"}
DEFAULT_RUNTIME_MODE = "full-access"
DEFAULT_INTERACTION_MODE = "default"
RUNTIME_MODES = frozenset({"approval-required", "auto-accept-edits", "auto", "full-access"})
APPROVAL_DECISIONS = frozenset({"accept", "acceptForSession", "acceptAlways", "decline", "cancel"})
MAX_PROMPT_CHARS = 120_000


def _iso_now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _uuid() -> str:
    return str(uuid.uuid4())


class T3CommandBuilder:
    def __init__(self, *, new_id: Callable[[], str] = _uuid, now: Callable[[], str] = _iso_now) -> None:
        self._new_id = new_id
        self._now = now

    def new_id(self) -> str:
        return self._new_id()

    def create_thread(
        self,
        *,
        thread_id: str,
        project_id: str,
        title: str,
        model_selection: dict[str, object],
        runtime_mode: str,
        interaction_mode: str = DEFAULT_INTERACTION_MODE,
    ) -> dict[str, object]:
        return {
            "type": "thread.create",
            "commandId": self._new_id(),
            "threadId": thread_id,
            "projectId": project_id,
            "title": title,
            "modelSelection": dict(model_selection),
            "runtimeMode": runtime_mode,
            "interactionMode": interaction_mode,
            "branch": None,
            "worktreePath": None,
            "createdAt": self._now(),
        }

    def turn_start(
        self,
        *,
        thread_id: str,
        text: str,
        runtime_mode: str,
        interaction_mode: str = DEFAULT_INTERACTION_MODE,
        model_selection: dict[str, object] | None = None,
        title_seed: str | None = None,
    ) -> dict[str, object]:
        command: dict[str, object] = {
            "type": "thread.turn.start",
            "commandId": self._new_id(),
            "threadId": thread_id,
            "message": {"messageId": self._new_id(), "role": "user", "text": text, "attachments": []},
        }
        if model_selection:
            command["modelSelection"] = dict(model_selection)
        if title_seed:
            command["titleSeed"] = title_seed
        command["runtimeMode"] = runtime_mode
        command["interactionMode"] = interaction_mode
        command["createdAt"] = self._now()
        return command

    def approval_respond(self, *, thread_id: str, request_id: str, decision: str) -> dict[str, object]:
        if decision not in APPROVAL_DECISIONS:
            raise ValueError("Approval decision is invalid.")
        return {
            "type": "thread.approval.respond",
            "commandId": self._new_id(),
            "threadId": thread_id,
            "requestId": request_id,
            "decision": decision,
            "createdAt": self._now(),
        }

    def user_input_respond(self, *, thread_id: str, request_id: str, answers: dict[str, object]) -> dict[str, object]:
        return {
            "type": "thread.user-input.respond",
            "commandId": self._new_id(),
            "threadId": thread_id,
            "requestId": request_id,
            "answers": dict(answers),
            "createdAt": self._now(),
        }

    def interrupt(self, *, thread_id: str, turn_id: str | None = None) -> dict[str, object]:
        command: dict[str, object] = {
            "type": "thread.turn.interrupt",
            "commandId": self._new_id(),
            "threadId": thread_id,
        }
        if turn_id:
            command["turnId"] = turn_id
        command["createdAt"] = self._now()
        return command

    def archive(self, *, thread_id: str) -> dict[str, object]:
        return {"type": "thread.archive", "commandId": self._new_id(), "threadId": thread_id}


def title_from_prompt(text: str, limit: int = 60) -> str:
    flat = " ".join(str(text).split())
    if len(flat) <= limit:
        return flat or "New thread"
    cut = flat[:limit]
    space = cut.rfind(" ")
    if space > limit * 0.5:
        cut = cut[:space]
    return cut.rstrip(" ,;:-.") + "…"
