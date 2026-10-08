"""Deterministic activity lines from recorded tool evidence (no model involved).

``VoiceToolEvidenceRecorder`` stores one ``role="tool"`` transcript row per tool
call: ``{"tool", "arguments", "result", "isError"}`` with key-based redaction.
Only successful *write* outcomes produce a line; reads, journal tools and
unknown tools produce nothing.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import tzinfo
import json

from .localtime import local_datetime, parse_iso_epoch

_MAX_DETAIL = 120
_WEEKDAYS = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
_MONTHS = ("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")


@dataclass(frozen=True, slots=True)
class ActivityLine:
    at: float | None
    text: str


def _short(value: object, limit: int = _MAX_DETAIL) -> str:
    text = " ".join(str(value or "").split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def _quoted(value: object) -> str:
    return f'"{_short(value)}"'


def _result(payload: dict[str, object]) -> dict[str, object]:
    value = payload.get("result")
    if isinstance(value, dict) and isinstance(value.get("result"), dict):
        return value["result"]
    return value if isinstance(value, dict) else {}


def _when(starts_at: object, all_day: object, zone: tzinfo) -> str:
    """'Thu 3:00 PM' (or 'Thu Oct 8, all day') in the user's zone."""
    epoch = parse_iso_epoch(starts_at)
    if epoch is None:
        return ""
    local = local_datetime(epoch, zone)
    day = _WEEKDAYS[local.weekday()]
    if all_day is True:
        return f"{day} {_MONTHS[local.month - 1]} {local.day}, all day"
    hour = local.hour % 12 or 12
    return f"{day} {hour}:{local.minute:02d} {'AM' if local.hour < 12 else 'PM'}"


def activity_lines(entries: Iterable[object], zone: tzinfo) -> list[ActivityLine]:
    """Map evidence rows (``TranscriptEntry`` with role 'tool') to factual lines."""
    prepared: dict[str, tuple[str, dict[str, object], dict[str, object]]] = {}
    drafts: dict[str, dict[str, object]] = {}
    lines: list[ActivityLine] = []
    for entry in entries:
        if getattr(entry, "role", None) != "tool":
            continue
        event_type = str(getattr(entry, "event_type", ""))
        if not event_type.endswith(".completed"):
            continue
        try:
            payload = json.loads(str(getattr(entry, "text_content", "")))
        except (TypeError, ValueError):
            continue  # truncated or foreign evidence
        if not isinstance(payload, dict) or payload.get("isError"):
            continue
        tool = str(payload.get("tool", ""))
        arguments = payload.get("arguments") if isinstance(payload.get("arguments"), dict) else {}
        result = _result(payload)
        at = parse_iso_epoch(getattr(entry, "created_at", None))
        text = _line(tool, arguments, result, prepared, drafts, zone)
        if text:
            lines.append(ActivityLine(at, text))
    return lines


def _line(
    tool: str,
    arguments: dict[str, object],
    result: dict[str, object],
    prepared: dict[str, tuple[str, dict[str, object], dict[str, object]]],
    drafts: dict[str, dict[str, object]],
    zone: tzinfo,
) -> str | None:
    if tool.startswith(("tasks_", "calendar_")) and tool not in ("tasks_confirm_action", "calendar_confirm_action"):
        action_id = result.get("actionId")
        if isinstance(action_id, str) and result.get("confirmationRequired", True):
            prepared[action_id] = (tool, arguments, result)
        return None
    if tool == "tasks_confirm_action" and result.get("state") == "completed":
        prep = prepared.get(str(result.get("actionId", "")))
        operation = str(prep[2].get("operation", "")) if prep else ""
        task = result.get("task") if isinstance(result.get("task"), dict) else {}
        proposed = prep[2].get("proposed") if prep and isinstance(prep[2].get("proposed"), dict) else {}
        text = task.get("text") or proposed.get("text")
        if not operation:
            operation = "complete" if task.get("status") == "completed" else ("remove" if not task else "")
        if operation == "add" and text:
            return f"Task added: {_quoted(text)}"
        if operation == "edit" and text:
            return f"Task edited: {_quoted(text)}"
        if operation == "complete":
            return f"Task done: {_quoted(text)}" if text else "Task done"
        if operation == "remove":
            return "Task removed"
        return None
    if tool == "calendar_confirm_action" and result.get("state") == "completed":
        prep = prepared.get(str(result.get("actionId", "")))
        if prep is None:
            return "Calendar: change applied"
        operation = str(prep[2].get("operation", ""))
        details = prep[2].get("payload") if isinstance(prep[2].get("payload"), dict) else {}
        title = details.get("title")
        if operation == "create":
            when = _when(details.get("startsAt"), details.get("allDay"), zone)
            label = f"Calendar: added {_quoted(title)}" if title else "Calendar: added an event"
            return f"{label} {when}".rstrip()
        if operation == "update":
            return f"Calendar: updated {_quoted(title)}" if title else "Calendar: updated an event"
        if operation == "delete":
            return f"Calendar: deleted {_quoted(title)}" if title else "Calendar: deleted an event"
        return None
    if tool == "email_compose":
        draft_id = result.get("draftId")
        if isinstance(draft_id, str):
            drafts[draft_id] = arguments
        return None
    if tool == "email_send_pending" and result.get("state") in ("sent", "sent_unfiled"):
        draft = drafts.get(str(result.get("draftId", "")), {})
        recipients = draft.get("to") if isinstance(draft.get("to"), list) else []
        count = len(recipients)
        target = f" to {count} recipient{'s' if count != 1 else ''}" if count else ""
        subject = draft.get("subject")
        return f"Mail: sent {_quoted(subject)}{target}" if subject else f"Mail: sent a message{target}"
    if tool == "goal_start" and result.get("runId"):
        request = arguments.get("originalRequest") or arguments.get("objective")
        return f"Started background task {_quoted(request)}" if request else "Started a background task"
    if tool == "t3_new_thread":
        title = arguments.get("title") or arguments.get("text")
        return f"T3: started thread {_quoted(_short(title, 60))}" if title else "T3: started a thread"
    if tool == "t3_send_message":
        return "T3: sent a message to a thread"
    if tool == "t3_respond":
        return "T3: answered a thread request"
    if tool == "t3_stop":
        return "T3: stopped a thread"
    return None
