"""GenUI screen-card tools: schema validity, Voice-only availability, Realtime projection."""
from __future__ import annotations

import tempfile

from jsonschema import Draft202012Validator

from sam_runtime.agents.audience import AgentKind
from sam_runtime.realtime.modes import (
    GOAL_INTAKE_INSTRUCTION,
    PRIMARY_VOICE_INSTRUCTION,
    VoiceModeService,
    register_voice_mode_tool,
)
from sam_runtime.tools import ToolCatalog
from sam_runtime.tools.definitions import ToolInvocationContext
from sam_runtime.tools.genui import (
    GENUI_TOOL_NAMES,
    GENUI_VOICE_INSTRUCTION,
    genui_tool_definitions,
    register_genui_tools,
)

EXAMPLES = {
    "show_card": [
        {"id": "groceries", "eyebrow": "Shopping", "icon": "cart", "accent": "violet", "title": "Grocery list",
         "subtitle": "6 items", "body": [{"type": "checklist", "id": "items", "items": [
             {"text": "Oat milk", "checked": True}, {"text": "Eggs"}, {"text": "Spinach"}]}],
         "actions": [{"label": "Add item", "say": "Add something to the grocery list"},
                     {"label": "Done", "style": "primary", "dismiss": True}]},
        {"id": "timer-pasta", "eyebrow": "Timer", "icon": "timer", "accent": "amber", "title": "Pasta",
         "size": "compact", "live": {"type": "timer", "durationSec": 540},
         "actions": [{"label": "+1 min", "timer": "add1m"}, {"label": "Cancel", "style": "danger", "dismiss": True}]},
        {"id": "t3-login-fix", "eyebrow": "T3 Code", "icon": "code", "accent": "cyan", "title": "Fix login redirect",
         "live": {"type": "t3-thread", "threadId": "thr_8f2a"},
         "actions": [{"label": "Status", "say": "How is the login redirect thread going?"}]},
        {"id": "spend", "eyebrow": "Spending", "accent": "pink", "title": "This week",
         "body": [{"type": "stat", "value": "$412.80", "label": "Week to date", "delta": "18% vs last", "trend": "up"},
                  {"type": "bars", "values": [32, 58, 12, 140, 44, 96, 30], "labels": ["M", "T", "W", "T", "F", "S", "S"],
                   "highlight": 3}]},
        {"id": "weather-sf", "eyebrow": "Weather", "title": "San Francisco",
         "body": [{"type": "weather", "temp": "64°", "condition": "rain", "hi": "66°", "lo": "55°",
                   "hours": [{"t": "3p", "temp": "64°", "condition": "rain"}]}]},
    ],
    "update_card": [
        {"id": "groceries", "patch": [{"id": "items", "items": [{"text": "Oat milk", "checked": True}]}]},
        {"id": "spend", "subtitle": "Updated just now"},
    ],
    "dismiss_card": [{"id": "groceries"}, {"all": True}, {"all": True, "includeTimers": True}],
}


def _catalog() -> ToolCatalog:
    catalog = ToolCatalog()
    register_genui_tools(catalog)
    return catalog


def test_definitions_are_valid_draft_2020_12_and_accept_the_examples() -> None:
    definitions = {item["name"]: item for item in genui_tool_definitions()}
    assert set(definitions) == GENUI_TOOL_NAMES
    for name, item in definitions.items():
        assert item["type"] == "function"
        assert item["description"]
        Draft202012Validator.check_schema(item["parameters"])
        validator = Draft202012Validator(item["parameters"])
        for example in EXAMPLES[name]:
            validator.validate(example)


def test_show_card_schema_rejects_what_the_contract_forbids() -> None:
    show = {item["name"]: item for item in genui_tool_definitions()}["show_card"]["parameters"]
    validator = Draft202012Validator(show)
    assert not validator.is_valid({"title": "No id"})
    assert not validator.is_valid({"id": "a", "title": "x", "html": "<b>no</b>"})
    assert not validator.is_valid({"id": "a", "title": "x", "body": [{"type": "image"}]})
    assert not validator.is_valid({"id": "a", "title": "x", "actions": [{"label": "1", "say": "a"}] * 3})


def test_tools_are_voice_only() -> None:
    catalog = _catalog()
    voice = {item["name"] for item in catalog.realtime_definitions()}
    assert GENUI_TOOL_NAMES <= voice
    assert not GENUI_TOOL_NAMES & {item.name for item in catalog.definitions_for(AgentKind.TEXT)}
    assert not GENUI_TOOL_NAMES & {item["name"] for item in catalog.mcp_definitions(AgentKind.TEXT)}
    projection = {item["name"]: item for item in catalog.management_projection()}
    assert projection["show_card"]["voice"] is True
    assert projection["show_card"]["text"] is False
    assert projection["show_card"]["effect"] == "display"


def test_realtime_projection_is_the_exact_definition() -> None:
    catalog = _catalog()
    projected = {item["name"]: item for item in catalog.realtime_definitions()}
    for item in genui_tool_definitions():
        assert projected[item["name"]] == item


def test_runtime_execution_is_an_error_because_the_display_host_runs_them() -> None:
    catalog = _catalog()
    result = catalog.invoke("show_card", EXAMPLES["show_card"][0], agent=AgentKind.VOICE)
    assert result.is_error
    assert "display host" in result.text
    denied = catalog.invoke("dismiss_card", {"all": True}, agent=AgentKind.TEXT)
    assert denied.is_error and denied.text == "Tool is not granted."


def test_primary_voice_instruction_includes_the_card_guidance_and_goal_intake_does_not() -> None:
    assert PRIMARY_VOICE_INSTRUCTION.endswith(GENUI_VOICE_INSTRUCTION)
    assert "Use show_card only" in PRIMARY_VOICE_INSTRUCTION
    assert "[UI event]" in PRIMARY_VOICE_INSTRUCTION
    assert "show_card" not in GOAL_INTAKE_INSTRUCTION
    # The existing primary guidance is untouched.
    assert "word test is never evidence" in PRIMARY_VOICE_INSTRUCTION


def test_cards_survive_primary_mode_updates_and_leave_goal_intake() -> None:
    catalog = ToolCatalog()
    modes = VoiceModeService()
    catalog.set_invocation_authorizer(modes.allows)
    register_voice_mode_tool(catalog, modes)
    register_genui_tools(catalog)
    primary = lambda: catalog.realtime_definitions(exclude_names=frozenset({"goal_start"}))
    intake = lambda: catalog.realtime_definitions(include_names=frozenset({"voice_mode_switch", "goal_start"}))
    modes.open_session("voice-1", primary_instructions=PRIMARY_VOICE_INSTRUCTION,
                       primary_tools=primary, goal_intake_tools=intake)
    to_intake = modes.switch("voice-1", "goal_intake").provider_session_update["session"]
    assert not GENUI_TOOL_NAMES & {item["name"] for item in to_intake["tools"]}
    back = modes.switch("voice-1", "primary").provider_session_update["session"]
    assert GENUI_TOOL_NAMES <= {item["name"] for item in back["tools"]}
    assert GENUI_VOICE_INSTRUCTION in back["instructions"]
    allowed = catalog.invoke("show_card", EXAMPLES["show_card"][1], agent=AgentKind.VOICE,
                             context=ToolInvocationContext(AgentKind.VOICE, voice_session_id="voice-1"))
    assert "display host" in allowed.text


def test_application_registers_cards_in_the_session_tool_list() -> None:
    from sam_runtime.application import RuntimeApplication
    from sam_runtime.config import RuntimeConfig

    class _Bridge:
        def __getattr__(self, name):
            raise AttributeError(name)

    with tempfile.TemporaryDirectory() as root:
        app = RuntimeApplication(RuntimeConfig.create(root, "t" * 40), _Bridge())
        app._config.database_path.parent.mkdir(parents=True, exist_ok=True)
        app._database.migrate()
        names = {item["name"] for item in app._tools.realtime_definitions(exclude_names=frozenset({"goal_start"}))}
        assert GENUI_TOOL_NAMES <= names
