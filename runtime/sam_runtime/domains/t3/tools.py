"""Voice tools for orchestrating T3 Code threads (tool set ``t3``).

Fast by design: no prepare/confirm round trip. Safety comes from the tool
descriptions (restate an approval before accepting, never invent ids) and from
the runtime: requests are validated against the thread's live pending list.
Outputs are compact JSON text under ~4 KB so they stay quick to speak.
"""

from __future__ import annotations

from collections.abc import Callable
from datetime import UTC, datetime
import json
import re

from sam_runtime.agents import AudienceResource, AudienceResourceKind
from sam_runtime.tools import ToolCatalog, ToolDefinition, ToolInvocationResult

from .client import T3Error, T3Unavailable
from .matching import match_item
from .placement import ProjectPlacement
from .service import (
    T3DispatchFailed,
    T3InvalidRequest,
    T3NotConnected,
    T3ReauthRequired,
    T3RequestNotPending,
    T3Service,
    T3ThreadNotFound,
)
from .status import ERROR, NEEDS_YOU, WORKING, condense, last_assistant_text, parse_time


T3_TOOL_SET = AudienceResource(AudienceResourceKind.DOMAIN_TOOL_SET, "t3")
MAX_OUTPUT_BYTES = 3800
_UUID = re.compile(r"^[0-9a-fA-F-]{36}$")

_THREAD = {"type": "string", "description": "Thread title as the user said it, or an id from t3_list_threads or the T3 status context. Never invent ids."}

T3_TOOL_SPECS: tuple[tuple[str, str, str, dict[str, object]], ...] = (
    (
        "t3_list_threads",
        "List the user's T3 Code coding threads on their Mac with status: needs approval, has a question, "
        "working, failed, or done. filter: needs-you (waiting on the user), working (running now), recent "
        "(latest activity first), all (default: needs-you first, then working, then done). Summarize briefly: "
        "counts first, then at most three titles. Never read ids aloud.",
        "read",
        {
            "type": "object",
            "properties": {
                "filter": {"type": "string", "enum": ["needs-you", "working", "recent", "all"]},
                "limit": {"type": "integer", "minimum": 1, "maximum": 12},
            },
            "required": [],
            "additionalProperties": False,
        },
    ),
    (
        "t3_read_thread",
        "Read one T3 Code thread: its latest assistant message (condensed) plus any pending approvals or "
        "questions with their requestIds. Use before answering an approval or question. Summarize in one or "
        "two sentences; do not read code or ids aloud.",
        "read",
        {
            "type": "object",
            "properties": {
                "thread": _THREAD,
                "messages": {"type": "integer", "minimum": 1, "maximum": 5, "description": "How many recent messages to include (default 1)."},
            },
            "required": ["thread"],
            "additionalProperties": False,
        },
    ),
    (
        "t3_new_thread",
        "Start a new T3 Code coding thread on the user's Mac and send its first message immediately. Call it "
        "right away when the user asks for new coding work in T3 (for example 'have T3 fix the login bug' or "
        "'start a T3 thread to add dark mode'). prompt is "
        "the user's request in their own words (fix only obvious transcription slips). title is optional, 3 to "
        "6 words. project is optional: pass it only when the user names a project; otherwise the project is picked "
        "from the request (a project it mentions; general computer or life tasks go to the orchestration project; "
        "coding work to the most recently active project). Then confirm in one short sentence that names the "
        "project.",
        "external_write",
        {
            "type": "object",
            "properties": {
                "prompt": {"type": "string"},
                "title": {"type": "string"},
                "project": {"type": "string", "description": "Project name as the user said it."},
            },
            "required": ["prompt"],
            "additionalProperties": False,
        },
    ),
    (
        "t3_send_message",
        "Send a follow-up message to an existing T3 Code thread to continue or steer it. If the thread is "
        "working, the message is queued for it. text is the user's message in their own words.",
        "external_write",
        {
            "type": "object",
            "properties": {"thread": _THREAD, "text": {"type": "string"}},
            "required": ["thread", "text"],
            "additionalProperties": False,
        },
    ),
    (
        "t3_respond",
        "Answer a pending approval or question in a T3 Code thread. For an approval pass decision: accept "
        "(approve once), acceptForSession (always allow for this session), decline, or cancel. Before accepting, "
        "restate in a few words what the agent wants to do (from t3_read_thread or the T3 update) and get a "
        "clear yes; decline and cancel need no restatement. For a question pass answer (one question) or "
        "answers (one per question, in the order t3_read_thread listed them), using an option label or the "
        "user's words. requestId may be omitted when exactly one request is pending. Never invent ids.",
        "external_write",
        {
            "type": "object",
            "properties": {
                "thread": _THREAD,
                "requestId": {"type": "string"},
                "decision": {"type": "string", "enum": ["accept", "acceptForSession", "decline", "cancel"]},
                "answer": {"type": "string", "description": "Answer to the only pending question."},
                "answers": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "One answer per question in order; for a multi-select question join choices with ' | '.",
                },
            },
            "required": ["thread"],
            "additionalProperties": False,
        },
    ),
    (
        "t3_stop",
        "Stop (interrupt) the running turn of a T3 Code thread when the user says stop, cancel, or halt it.",
        "external_write",
        {
            "type": "object",
            "properties": {"thread": _THREAD},
            "required": ["thread"],
            "additionalProperties": False,
        },
    ),
)


def register_t3_tools(catalog: ToolCatalog, service: T3Service, placement: ProjectPlacement | None = None) -> None:
    handlers = T3ToolHandlers(service, placement=placement)
    for name, description, effect, schema in T3_TOOL_SPECS:
        catalog.register(ToolDefinition(
            tool_id=f"builtin.t3.{name.removeprefix('t3_').replace('_', '-')}.v1",
            name=name,
            description=description,
            input_schema=schema,
            handler=lambda arguments, tool=name: handlers.invoke(tool, arguments),
            effect_class=effect,
            audience_resource=T3_TOOL_SET,
            available_to=lambda _agent: service.connected(),
        ))


def ago(value: object, now: datetime | None = None) -> str:
    stamp = parse_time(value)
    if stamp is None:
        return ""
    seconds = max(0.0, ((now or datetime.now(UTC)) - stamp).total_seconds())
    if seconds < 60:
        return "just now"
    if seconds < 3600:
        minutes = int(seconds // 60)
        return f"{minutes} min ago"
    if seconds < 86_400:
        hours = int(seconds // 3600)
        return f"{hours} h ago"
    days = int(seconds // 86_400)
    return "yesterday" if days == 1 else f"{days} days ago"


class T3ToolHandlers:
    def __init__(self, service: T3Service, *, now: Callable[[], datetime] = lambda: datetime.now(UTC),
                 placement: ProjectPlacement | None = None) -> None:
        self._service = service
        self._now = now
        self._placement = placement

    def invoke(self, name: str, arguments: dict[str, object]) -> ToolInvocationResult:
        try:
            if name == "t3_list_threads":
                value = self._list(arguments)
            elif name == "t3_read_thread":
                value = self._read(arguments)
            elif name == "t3_new_thread":
                value = self._new(arguments)
            elif name == "t3_send_message":
                value = self._send(arguments)
            elif name == "t3_respond":
                value = self._respond(arguments)
            elif name == "t3_stop":
                value = self._stop(arguments)
            else:
                return _failure("That T3 tool is unavailable.")
        except T3NotConnected:
            return _failure("T3 Code is not connected. The user can pair it in Management > Connections.")
        except T3ReauthRequired:
            return _failure("T3 Code needs to be paired again from Management > Connections.")
        except T3ThreadNotFound:
            return _failure("That thread was not found. List threads to get the right one.")
        except T3RequestNotPending as error:
            return _failure(f"{error} List the thread again to see what is waiting.")
        except T3InvalidRequest as error:
            return _failure(str(error))
        except T3DispatchFailed as error:
            return _failure(f"{error} Try again in a moment.")
        except T3Unavailable:
            return _failure("T3 Code on the Mac is unreachable right now. Check that the Mac is awake.")
        except T3Error:
            return _failure("T3 Code could not complete that request.")
        text = _fit(value)
        return ToolInvocationResult(text)

    # -- thread resolution ----------------------------------------------------
    def _resolve(self, query: object) -> dict[str, object]:
        text = " ".join(str(query or "").split())
        if not text:
            raise T3InvalidRequest("Say which thread.")
        self._service.ensure_snapshot(required=True)
        summaries = self._service.summaries()
        result = match_item(text, summaries)
        if result.item is not None:
            return result.item
        if _UUID.match(text):
            view = self._service.thread_view(text, turns=1)
            return dict(view["thread"])
        if result.ambiguous:
            options = "; ".join(f"“{item['title']}” in {item['projectTitle']} (id {item['id']})" for item in result.candidates)
            raise T3InvalidRequest(f"Several threads could match: {options}. Ask the user which one.")
        if result.candidates:
            options = "; ".join(f"“{item['title']}” (id {item['id']})" for item in result.candidates)
            raise T3InvalidRequest(f"No thread clearly matches “{text}”. Closest: {options}. Ask the user which one.")
        raise T3InvalidRequest(f"No thread matches “{text}”. List threads to find it.")

    def _brief(self, item: dict[str, object]) -> dict[str, object]:
        return {
            "id": item["id"],
            "title": item["title"],
            "project": item["projectTitle"],
            "status": item["status"],
            "label": item["statusLabel"],
            "when": ago(item.get("updatedAt"), self._now()),
            **({"unread": True} if item.get("unread") else {}),
        }

    # -- tools ------------------------------------------------------------------
    def _list(self, arguments: dict[str, object]) -> dict[str, object]:
        if not self._service.connected():
            raise T3NotConnected("T3 Code is not connected.")
        self._service.ensure_snapshot(required=True)
        view = self._service.threads_view(limit=100)
        threads = list(view["threads"])
        mode = str(arguments.get("filter") or "all")
        limit = int(arguments.get("limit") or 6)
        if mode == "needs-you":
            threads = [item for item in threads if item["status"] in NEEDS_YOU or item["status"] == ERROR]
        elif mode == "working":
            threads = [item for item in threads if item["status"] == WORKING]
        elif mode == "recent":
            floor = datetime.min.replace(tzinfo=UTC)
            threads.sort(key=lambda item: parse_time(item.get("updatedAt")) or floor, reverse=True)
        shown = threads[:limit]
        result: dict[str, object] = {
            "counts": view["counts"],
            "filter": mode,
            "threads": [self._brief(item) for item in shown],
        }
        if len(threads) > len(shown):
            result["more"] = len(threads) - len(shown)
        if not shown:
            result["note"] = {
                "needs-you": "Nothing is waiting on the user.",
                "working": "Nothing is running right now.",
            }.get(mode, "No threads yet.")
        return result

    def _read(self, arguments: dict[str, object]) -> dict[str, object]:
        item = self._resolve(arguments.get("thread"))
        count = int(arguments.get("messages") or 1)
        view = self._service.thread_view(str(item["id"]), turns=max(1, min(3, count)))
        messages = view["messages"]
        last = last_assistant_text({"messages": messages})
        result: dict[str, object] = {
            "thread": self._brief(view["thread"]),
            "lastAssistant": condense(last, 1500) or None,
        }
        if count > 1:
            result["recent"] = [
                {"role": message["role"], "text": condense(message["text"], 400)}
                for message in messages[-count:]
            ]
        pending = view["pending"]
        if pending["approvals"]:
            result["approvals"] = [
                {
                    "requestId": approval["requestId"],
                    "kind": approval["kind"],
                    "detail": condense(approval.get("detail"), 300) or None,
                    "decisions": [option["decision"] for option in approval["options"]],
                }
                for approval in pending["approvals"]
            ]
        if pending["inputs"]:
            result["questions"] = [
                {
                    "requestId": request["requestId"],
                    "questions": [
                        {
                            "id": question["id"],
                            "question": condense(question["question"], 300),
                            "options": [condense(option, 80) for option in question["options"][:6]],
                            **({"allowCustom": True} if question["allowCustom"] else {}),
                            **({"multiSelect": True} if question["multiSelect"] else {}),
                        }
                        for question in request["questions"][:4]
                    ],
                }
                for request in pending["inputs"]
            ]
        if view.get("activeTurnId"):
            result["working"] = True
        return result

    def _new(self, arguments: dict[str, object]) -> dict[str, object]:
        prompt = str(arguments.get("prompt") or "")
        project = _text(arguments.get("project"))
        placed = None
        if self._placement is not None and project is None and prompt.strip():
            if not self._service.connected():
                raise T3NotConnected("T3 Code is not connected.")
            placed = self._placement.choose(prompt)
        created = self._service.create_thread(
            prompt,
            title=_text(arguments.get("title")),
            project=project if placed is None else None,
            project_id=placed.project_id if placed is not None else None,
        )
        result: dict[str, object] = {
            "ok": True,
            "threadId": created["threadId"],
            "title": created["title"],
            "project": created["projectTitle"],
            "model": created.get("model"),
            "status": "started",
        }
        if placed is not None:
            result["placement"] = placed.reason
        return result

    def _send(self, arguments: dict[str, object]) -> dict[str, object]:
        item = self._resolve(arguments.get("thread"))
        sent = self._service.send_message(str(item["id"]), str(arguments.get("text") or ""))
        result: dict[str, object] = {"ok": True, "threadId": item["id"], "title": item["title"], "project": item["projectTitle"]}
        if sent.get("wasWorking"):
            result["note"] = "The thread was working; the message is queued for it."
        return result

    def _respond(self, arguments: dict[str, object]) -> dict[str, object]:
        item = self._resolve(arguments.get("thread"))
        thread_id = str(item["id"])
        pending = self._service.pending(thread_id)
        request_id = _text(arguments.get("requestId"))
        decision = _text(arguments.get("decision"))
        answer = _text(arguments.get("answer"))
        ordered = [str(entry) for entry in arguments.get("answers") or [] if str(entry).strip()]
        approvals = {approval.request_id: approval for approval in pending.approvals}
        inputs = {request.request_id: request for request in pending.inputs}
        if request_id is None:
            if decision and not (answer or ordered):
                candidates = list(approvals)
            elif (answer or ordered) and not decision:
                candidates = list(inputs)
            else:
                candidates = list(approvals) + list(inputs)
            if not candidates:
                raise T3RequestNotPending(f"Nothing is waiting on “{item['title']}”.")
            if len(candidates) > 1:
                raise T3InvalidRequest(f"Several requests are pending ({', '.join(candidates)}). Read the thread and pass requestId.")
            request_id = candidates[0]
        if request_id in approvals:
            if not decision:
                raise T3InvalidRequest("Pass decision: accept, acceptForSession, decline, or cancel.")
            self._service.respond_approval(thread_id, request_id, decision)
            return {"ok": True, "threadId": thread_id, "title": item["title"], "requestId": request_id, "decision": decision}
        if request_id in inputs:
            request = inputs[request_id]
            if not ordered and answer is not None:
                ordered = [answer]
            if not ordered:
                raise T3InvalidRequest("Pass the user's answer.")
            if len(ordered) != len(request.questions):
                raise T3InvalidRequest(f"This request has {len(request.questions)} questions; pass one answer per question, in order.")
            answers: dict[str, object] = {}
            for question, value in zip(request.questions, ordered):
                parts = [part.strip() for part in value.split("|") if part.strip()]
                answers[question.question_id] = parts if question.multi_select and len(parts) > 1 else value
            sent = self._service.respond_input(thread_id, request_id, answers)
            return {"ok": True, "threadId": thread_id, "title": item["title"], "requestId": request_id, "answers": sent["answers"]}
        raise T3RequestNotPending("That request is no longer pending.")

    def _stop(self, arguments: dict[str, object]) -> dict[str, object]:
        item = self._resolve(arguments.get("thread"))
        stopped = self._service.interrupt(str(item["id"]))
        result: dict[str, object] = {"ok": True, "threadId": item["id"], "title": item["title"], "wasWorking": bool(stopped.get("wasWorking"))}
        if not stopped.get("wasWorking"):
            result["note"] = "It was not working, so nothing needed stopping."
        return result


def _text(value: object) -> str | None:
    return value.strip() if isinstance(value, str) and value.strip() else None


def _failure(message: str) -> ToolInvocationResult:
    return ToolInvocationResult(message, is_error=True)


def _dump(value: object) -> str:
    return json.dumps(value, separators=(",", ":"), ensure_ascii=False)


def _fit(value: dict[str, object]) -> str:
    """Shrink long text fields until the JSON is under MAX_OUTPUT_BYTES."""
    text = _dump(value)
    if len(text.encode()) <= MAX_OUTPUT_BYTES:
        return text
    for limit in (900, 600, 300, 160):
        if isinstance(value.get("lastAssistant"), str):
            value["lastAssistant"] = condense(value["lastAssistant"], limit)
        for message in value.get("recent", []) or []:
            message["text"] = condense(message["text"], max(80, limit // 3))
        for approval in value.get("approvals", []) or []:
            approval["detail"] = condense(approval.get("detail"), max(80, limit // 3)) or None
        for request in value.get("questions", []) or []:
            for question in request.get("questions", []):
                question["question"] = condense(question["question"], max(80, limit // 3))
        text = _dump(value)
        if len(text.encode()) <= MAX_OUTPUT_BYTES:
            return text
    while isinstance(value.get("threads"), list) and len(value["threads"]) > 1 and len(text.encode()) > MAX_OUTPUT_BYTES:
        value["threads"].pop()
        value["more"] = int(value.get("more") or 0) + 1
        text = _dump(value)
    if len(text.encode()) > MAX_OUTPUT_BYTES:
        value.pop("recent", None)
        text = _dump(value)
    return text
