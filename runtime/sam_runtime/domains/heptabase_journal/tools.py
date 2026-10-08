"""Three narrow Voice tools for the Heptabase journal (append-only, verbatim)."""

from __future__ import annotations

import json

from sam_runtime.agents import AudienceResource, AudienceResourceKind
from sam_runtime.agents.audience import AgentKind
from sam_runtime.core.logging import runtime_logger
from sam_runtime.tools import ToolCatalog, ToolDefinition, ToolInvocationContext, ToolInvocationResult

from .errors import HeptabaseError, NotConnected, ReconnectRequired
from .service import HeptabaseJournalService

JOURNAL_TOOL_SET = AudienceResource(AudienceResourceKind.DOMAIN_TOOL_SET, "journal")
JOURNAL_TOOL_NAMES = ("journal_add", "journal_read", "journal_pause")
_LOG = runtime_logger()

_ADD_DESCRIPTION = (
    "Add the user's own words to their Heptabase journal, verbatim. Call it ONLY when the user explicitly asks "
    "to add, save, write, put or note something in their journal (for example 'add to my journal that ...', "
    "'put this in my journal'). Never call it on your own: not as a fallback or workaround when another action "
    "fails or is unavailable (a calendar event, a reminder, a task), not to log what you did or what was said, "
    "and not for complaints, comments or questions about the journal (for example 'stop putting things in my "
    "journal' or 'what did I journal today?'). Pass exactly the words they want recorded, "
    "dropping only the command phrase (for example 'add to my journal that'). Never reword, summarize, "
    "translate, or add anything: the device checks the words against what the user actually said and "
    "records only their words. If they gave no content, ask what to add. state 'sent' means it is in "
    "the journal now; 'queued' means it will sync shortly. Confirm in a few words."
)
_READ_DESCRIPTION = (
    "Read the user's Heptabase journal for one day (default today) when they ask what they journaled. "
    "Returns at most 4 KB of plain text with secrets redacted. Answer briefly from it; never write."
)
_PAUSE_DESCRIPTION = (
    "Keep the current voice session off the record: nothing said in this session is auto-journaled (that only "
    "happens when the user turned on 'Record every voice conversation in my journal'; by default only what they "
    "explicitly ask to add is journaled). Use when the user says 'off the record', 'don't journal this', or "
    "similar. Explicit journal_add requests still work."
)


def _result(value: dict[str, object], *, error: bool = False) -> ToolInvocationResult:
    return ToolInvocationResult(json.dumps(value, separators=(",", ":"), ensure_ascii=False),
                                structured_content=value, is_error=error)


def _failure(error: Exception) -> ToolInvocationResult:
    if isinstance(error, ReconnectRequired):
        return _result({"recorded": False, "reason": "reconnect_required",
                        "message": "Heptabase needs to be reconnected in Settings."}, error=True)
    if isinstance(error, NotConnected):
        return _result({"recorded": False, "reason": "not_connected",
                        "message": "Heptabase is not connected."}, error=True)
    if isinstance(error, HeptabaseError):
        return _result({"recorded": False, "reason": error.code, "message": str(error)}, error=True)
    return _result({"recorded": False, "reason": "invalid_request", "message": str(error)}, error=True)


def register_journal_tools(catalog: ToolCatalog, service: HeptabaseJournalService) -> None:
    def available(agent: AgentKind) -> bool:
        return agent is AgentKind.VOICE and service.connected()

    def add(context: ToolInvocationContext, arguments: dict[str, object]) -> ToolInvocationResult:
        try:
            value = service.add_voice_note(context, str(arguments.get("text", "")))
        except (HeptabaseError, ValueError) as error:
            return _failure(error)
        except Exception:
            _LOG.exception("heptabase.tool.add_failed")
            return _result({"recorded": False, "reason": "unavailable",
                            "message": "The journal is unavailable right now."}, error=True)
        return _result(value, error=not value.get("recorded", False))

    def read(_context: ToolInvocationContext, arguments: dict[str, object]) -> ToolInvocationResult:
        try:
            value = service.read_journal(arguments.get("date") if isinstance(arguments.get("date"), str) else None)
        except (HeptabaseError, ValueError) as error:
            return _failure(error)
        except Exception:
            _LOG.exception("heptabase.tool.read_failed")
            return _result({"reason": "unavailable", "message": "The journal is unavailable right now."}, error=True)
        return _result(value)

    def pause(context: ToolInvocationContext, _arguments: dict[str, object]) -> ToolInvocationResult:
        value = service.pause_session(context.voice_session_id)
        return _result(value, error=not value.get("paused", False))

    def context_only(_arguments: dict[str, object]) -> ToolInvocationResult:
        return ToolInvocationResult("The journal requires a Voice invocation context.", is_error=True)

    catalog.register(ToolDefinition(
        tool_id="builtin.journal.add.v1", name="journal_add", description=_ADD_DESCRIPTION,
        input_schema={
            "type": "object",
            "properties": {"text": {"type": "string", "minLength": 1, "maxLength": 4000,
                                    "description": "The user's exact words to record."}},
            "required": ["text"], "additionalProperties": False,
        },
        handler=context_only, context_handler=add, effect_class="external_write",
        audience_resource=JOURNAL_TOOL_SET, available_to=available,
    ))
    catalog.register(ToolDefinition(
        tool_id="builtin.journal.read.v1", name="journal_read", description=_READ_DESCRIPTION,
        input_schema={
            "type": "object",
            "properties": {"date": {"type": "string", "maxLength": 10,
                                    "description": "'today' (default), 'yesterday', or YYYY-MM-DD."}},
            "additionalProperties": False,
        },
        handler=context_only, context_handler=read, effect_class="read",
        audience_resource=JOURNAL_TOOL_SET, available_to=available,
    ))
    catalog.register(ToolDefinition(
        tool_id="builtin.journal.pause.v1", name="journal_pause", description=_PAUSE_DESCRIPTION,
        input_schema={
            "type": "object",
            "properties": {"scope": {"type": "string", "enum": ["session"]}},
            "additionalProperties": False,
        },
        handler=context_only, context_handler=pause, effect_class="local_write",
        audience_resource=JOURNAL_TOOL_SET, available_to=available,
    ))
