"""Generated UI ("screen cards") tools for the R1 Voice session.

The runtime registers ``show_card``, ``update_card`` and ``dismiss_card`` so they are part of
the session tool list (and survive ``voice_mode_switch`` session updates), but the R1 display
host executes them locally in the Voice page. If a call ever reaches the runtime, the APK is
older than this runtime, so the handler reports an error instead of pretending to draw.
"""
from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from ..agents.audience import AgentKind
from .catalog import ToolCatalog
from .definitions import ToolDefinition, ToolInvocationResult


GENUI_TOOL_NAMES = frozenset({"show_card", "update_card", "dismiss_card"})

GENUI_VOICE_INSTRUCTION = (
    "Screen cards: the R1 has a small 480x640 screen. Most replies need no card. Use show_card only "
    "when seeing beats hearing: 3+ items (options, steps, a checklist), numbers worth a glance "
    "(prices, totals, scores, times), a status or progress that will change (timers, background "
    "goals, coding threads, the next meeting), a short comparison, or weather. Never for small "
    "talk, a single fact, or to restate what you said. When you show a card, call show_card first, "
    "then speak one short sentence that points to it (\"Here are three options.\") and do not read "
    "the card aloud unless asked. Keep card text terse: titles up to 5 words, rows up to 6 words, "
    "no markdown or emoji. Reuse ids: update_card the existing card instead of showing a new one on "
    "the same topic, and dismiss_card cards that are finished. For timers always use show_card with "
    "live.type \"timer\" and durationSec; never track time yourself. After goal_start succeeds you may "
    "show a live \"background-run\" card with its runId. Messages starting with [UI event] or [Screen] "
    "describe what the user did or sees on the screen; use them as context and do not reply to them "
    "unless the user speaks."
)


@lru_cache(maxsize=1)
def genui_tool_definitions() -> tuple[dict[str, object], ...]:
    """The exact Realtime function definitions (source: genui_tools.json)."""
    payload = json.loads(Path(__file__).with_name("genui_tools.json").read_text(encoding="utf-8"))
    return tuple(payload)


def _display_host_only(_arguments: dict[str, object]) -> ToolInvocationResult:
    return ToolInvocationResult(
        "Cards are drawn by the R1 display host; this device build cannot show them.",
        is_error=True,
    )


def _voice_only(agent: AgentKind) -> bool:
    return agent is AgentKind.VOICE


def register_genui_tools(catalog: ToolCatalog) -> None:
    for item in genui_tool_definitions():
        name = str(item["name"])
        catalog.register(ToolDefinition(
            tool_id=f"builtin.genui.{name.replace('_', '-')}.v1",
            name=name,
            description=str(item["description"]),
            input_schema=dict(item["parameters"]),
            handler=_display_host_only,
            effect_class="display",
            available_to=_voice_only,
        ))
