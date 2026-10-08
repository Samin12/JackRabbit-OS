"""A failed tool call is told as a failure, never as "still running in the background".

The real case: calendar_create_event on the read-only iCal subscription answered "This calendar does not allow
create operations." and the voice model told the user "The calendar action is still running in the background".
"""

from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from mac_fakes import FakeMacControlBridge, make_mac

from sam_runtime.agents import AgentKind
from sam_runtime.domains.mac import register_mac_tools
from sam_runtime.mcp.server import PROTOCOL_VERSION, VOICE_TOOL_FAILURE_NOTE, LocalMcpServer
from sam_runtime.realtime.modes import (
    CALENDAR_WINDOW_INSTRUCTION,
    GOAL_INTAKE_INSTRUCTION,
    PRIMARY_VOICE_INSTRUCTION,
    TOOL_RESULT_HONESTY_INSTRUCTION,
)
from sam_runtime.tools import ToolCatalog, ToolInvocationResult
from sam_runtime.tools.calendar import CalendarToolPackage
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools.genui import GENUI_VOICE_INSTRUCTION

READ_ONLY = "This calendar does not allow create operations."


class _ReadOnlyCalendar:
    def invoke_tool(self, name, context, arguments):
        if name == "calendar_create_event":
            return ToolInvocationResult(READ_ONLY, is_error=True)
        return ToolInvocationResult("[]", {"result": []})


def _server(agent: AgentKind, catalog: ToolCatalog | None = None) -> tuple[LocalMcpServer, str]:
    if catalog is None:
        catalog = ToolCatalog()
        CalendarToolPackage(_ReadOnlyCalendar()).register(catalog)
    server = LocalMcpServer(lambda: {"status": "ready"}, catalog=catalog, agent=agent)
    initialized = server.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
        "protocolVersion": PROTOCOL_VERSION, "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}},
        session_id=None, protocol_version=None)
    return server, str(initialized.session_id)


def _call(server: LocalMcpServer, session: str, name: str, arguments: dict[str, object]) -> dict[str, object]:
    answer = server.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                            "params": {"name": name, "arguments": arguments}},
                           session_id=session, protocol_version=PROTOCOL_VERSION, voice_session_id="voice-1",
                           tool_call_id="call-1")
    return answer.payload["result"]


class ToolFailureHonestyTest(unittest.TestCase):
    def test_a_failed_voice_tool_result_carries_the_host_note(self) -> None:
        server, session = _server(AgentKind.VOICE)
        result = _call(server, session, "calendar_create_event", {
            "calendarAccountId": "ics-subscription", "title": "Research Session",
            "startsAt": "2026-10-08T10:30:00-04:00", "endsAt": "2026-10-08T11:00:00-04:00",
            "timezone": "America/New_York"})
        self.assertTrue(result["isError"])
        self.assertEqual(READ_ONLY, result["content"][0]["text"], "the reason comes first, unchanged")
        self.assertEqual({"type": "text", "text": VOICE_TOOL_FAILURE_NOTE}, result["content"][1])
        for phrase in ("failed", "did not complete", "nothing is still running in the background",
                       "did not work and why"):
            self.assertIn(phrase, VOICE_TOOL_FAILURE_NOTE)

    def test_successes_and_other_agents_are_unchanged(self) -> None:
        server, session = _server(AgentKind.VOICE)
        ok = _call(server, session, "calendar_list_upcoming", {})
        self.assertFalse(ok["isError"])
        self.assertEqual(1, len(ok["content"]))
        denied = _call(server, session, "not_a_tool", {})
        self.assertTrue(denied["isError"])
        self.assertEqual(VOICE_TOOL_FAILURE_NOTE, denied["content"][-1]["text"])
        text_server, text_session = _server(AgentKind.TEXT)
        text_result = _call(text_server, text_session, "calendar_create_event",
                            {"calendarAccountId": "x", "title": "y", "startsAt": "2026-10-08T10:30:00-04:00"})
        self.assertTrue(text_result["isError"])
        self.assertEqual(1, len(text_result["content"]), "only the Voice model gets the spoken-reply note")

    def test_a_failure_that_says_what_to_tell_the_user_gets_no_host_note(self) -> None:
        # mac_look without screen vision: "tell the user once", then "do not mention it again". The generic note
        # ("tell the user plainly that it did not work") would contradict the second one.
        with tempfile.TemporaryDirectory() as directory:
            database = RuntimeDatabase(Path(directory) / "runtime.sqlite3")
            database.migrate()
            fake = FakeMacControlBridge()
            self.addCleanup(fake.close)
            store, client = make_mac(database, fake)
            catalog = ToolCatalog()
            register_mac_tools(catalog, client)
            server, session = _server(AgentKind.VOICE, catalog)
            first = _call(server, session, "mac_look", {})
            again = _call(server, session, "mac_look", {})
        for result in (first, again):
            self.assertTrue(result["isError"])
            self.assertEqual(1, len(result["content"]), "only the tool's own instruction")
            self.assertNotIn(VOICE_TOOL_FAILURE_NOTE, json.dumps(result))
        self.assertIn("Tell the user once", json.loads(first["content"][0]["text"])["message"])
        self.assertIn("Do not mention it again", json.loads(again["content"][0]["text"])["message"])

    def test_the_flag_is_only_for_results_that_carry_an_instruction(self) -> None:
        self.assertFalse(ToolInvocationResult("x", is_error=True).model_note, "the note stays the default")
        catalog = ToolCatalog()

        class _Own:
            def invoke_tool(self, name, context, arguments):
                return ToolInvocationResult('{"message":"Say: the calendar is read-only."}', is_error=True,
                                            model_note=True)

        CalendarToolPackage(_Own()).register(catalog)
        server, session = _server(AgentKind.VOICE, catalog)
        result = _call(server, session, "calendar_create_event", {"title": "y", "startsAt": "now"})
        self.assertEqual(1, len(result["content"]))

    def test_the_primary_voice_instruction_demands_honest_failures(self) -> None:
        self.assertIn(TOOL_RESULT_HONESTY_INSTRUCTION, PRIMARY_VOICE_INSTRUCTION)
        for phrase in ("isError true", "did not happen", "did not work and why", "still running",
                       "in the background unless its result says so", "status started"):
            self.assertIn(phrase, TOOL_RESULT_HONESTY_INSTRUCTION)
        self.assertIn(CALENDAR_WINDOW_INSTRUCTION, PRIMARY_VOICE_INSTRUCTION)
        self.assertIn("withinMinutes", CALENDAR_WINDOW_INSTRUCTION)
        self.assertTrue(PRIMARY_VOICE_INSTRUCTION.endswith(GENUI_VOICE_INSTRUCTION), "card guidance stays last")
        self.assertNotIn("ui_generate", PRIMARY_VOICE_INSTRUCTION)
        self.assertNotIn(TOOL_RESULT_HONESTY_INSTRUCTION, GOAL_INTAKE_INSTRUCTION)


if __name__ == "__main__":
    unittest.main()
