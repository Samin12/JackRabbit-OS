from __future__ import annotations

import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest

from sam_runtime.agents import AgentKind
from sam_runtime.domains.generated_ui import (
    GENERATED_UI_VOICE_INSTRUCTION,
    ArtifactWatcher,
    GeneratedUiClient,
    GeneratedUiVoiceContext,
    register_generated_ui_tools,
)
from sam_runtime.domains.generated_ui.tools import SAY
from sam_runtime.realtime.modes import PRIMARY_VOICE_INSTRUCTION
from sam_runtime.storage.announcements import AnnouncementRepository
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools import ToolCatalog
from sam_runtime.tools.definitions import ToolInvocationContext

from generated_ui_fakes import FakeGeneratedUiBridge, artifact_id_for
from mac_fakes import make_mac


class GeneratedUiToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.fake = FakeGeneratedUiBridge()
        self.store, _mac = make_mac(self.database)
        self.client = GeneratedUiClient(self.store)
        self.announcements = AnnouncementRepository(self.database)
        self.watcher = ArtifactWatcher(self.client, self.announcements, self.database)
        self.lookups: list[str] = []
        self.catalog = ToolCatalog()
        self.handlers = register_generated_ui_tools(self.catalog, self.client, self.watcher)

    def tearDown(self) -> None:
        self.watcher.stop()
        self.fake.close()
        self.directory.cleanup()

    def configure(self) -> None:
        self.store.save(self.fake.url, self.fake.token)

    def call(self, arguments: dict[str, object], *, session: str | None = "a" * 24, call: str | None = "call_1"):
        context = ToolInvocationContext(AgentKind.VOICE, voice_session_id=session, tool_call_id=call)
        result = self.catalog.invoke("ui_generate", arguments, agent=AgentKind.VOICE, context=context)
        self.assertNotIn(self.fake.token, result.text, "the bridge token never reaches the model")
        return result

    def test_tool_appears_only_when_the_mac_bridge_is_configured(self) -> None:
        names = {item["name"] for item in self.catalog.realtime_definitions()}
        self.assertNotIn("ui_generate", names)
        self.assertEqual("Tool is not granted.", self.call({"request": "a chart"}).text)
        self.assertEqual("", GeneratedUiVoiceContext(self.client).render())
        self.configure()
        definitions = {item["name"]: item for item in self.catalog.realtime_definitions()}
        self.assertIn("ui_generate", definitions)
        parameters = definitions["ui_generate"]["parameters"]
        self.assertEqual(["request"], parameters["required"])
        self.assertEqual({"request", "data"}, set(parameters["properties"]))
        for keyword in ("anyOf", "oneOf", "allOf", "$ref"):
            self.assertNotIn(keyword, json.dumps(definitions["ui_generate"]))
        self.assertEqual(GENERATED_UI_VOICE_INSTRUCTION, GeneratedUiVoiceContext(self.client).render())
        self.assertEqual([], self.fake.paths(), "no network while listing tools or building instructions")

    def test_generate_returns_at_once_and_follows_the_artifact(self) -> None:
        self.configure()
        result = self.call({"request": "a bar chart of my week's meetings", "data": "Mon 3, Tue 5, Wed 2"})
        self.assertFalse(result.is_error, result.text)
        value = json.loads(result.text)
        request_id = "rt:" + "a" * 24 + ":call_1"
        self.assertEqual({"ok": True, "artifactId": artifact_id_for(request_id), "status": "generating", "say": SAY},
                         value)
        (body,) = self.fake.generate_bodies()
        self.assertEqual({"requestId": request_id, "prompt": "a bar chart of my week's meetings",
                          "data": "Mon 3, Tue 5, Wed 2", "conversationId": "a" * 24, "size": "r1"}, body)
        self.assertEqual([value["artifactId"]], self.watcher.pending())

    def test_a_retried_tool_call_makes_one_visual_and_one_announcement(self) -> None:
        self.configure()
        first = json.loads(self.call({"request": "a chart"}).text)
        second = json.loads(self.call({"request": "a chart"}).text)
        self.assertEqual(first["artifactId"], second["artifactId"])
        self.assertEqual(1, len(self.watcher.pending()))
        self.fake.ready(first["artifactId"])
        self.watcher._clock = lambda: 10**10  # noqa: SLF001 - everything is due
        self.watcher.poll_once()
        self.assertEqual(["ui.generated"], [item.kind for item in self.announcements.after(0)])
        self.assertFalse(self.watcher.track(first["artifactId"], conversation_id=None, voice_session_id=None))

    def test_conversation_id_prefers_the_context_then_the_sync_map_then_the_session(self) -> None:
        self.configure()
        handlers = register_generated_ui_tools(
            ToolCatalog(), self.client, self.watcher,
            conversation_lookup=lambda session: (self.lookups.append(session) or "c_" + "1" * 20),
        )
        context = SimpleNamespace(voice_session_id="b" * 24, tool_call_id="call_9", conversation_id="c_" + "2" * 20)
        self.assertEqual("c_" + "2" * 20, handlers.conversation_id(context))
        context = ToolInvocationContext(AgentKind.VOICE, voice_session_id="b" * 24, tool_call_id="call_9")
        self.assertEqual("c_" + "1" * 20, handlers.conversation_id(context))
        self.assertEqual(["b" * 24], self.lookups)
        handlers.generate({"request": "a chart"}, context)
        self.assertEqual("c_" + "1" * 20, self.fake.generate_bodies()[-1]["conversationId"])
        broken = register_generated_ui_tools(ToolCatalog(), self.client, self.watcher,
                                             conversation_lookup=lambda _session: 1 / 0)
        self.assertEqual("b" * 24, broken.conversation_id(context), "falls back to the voice session id")

    def test_calls_without_a_voice_session_still_work(self) -> None:
        self.configure()
        result = self.call({"request": "a diagram"}, session=None, call=None)
        self.assertFalse(result.is_error, result.text)
        body = self.fake.generate_bodies()[-1]
        self.assertTrue(str(body["requestId"]).startswith("rt:"))
        self.assertNotIn("conversationId", body)

    def test_failures_are_short_and_speakable(self) -> None:
        self.configure()
        result = self.call({"request": "   "})
        self.assertTrue(result.is_error)
        self.assertEqual("invalid_request", json.loads(result.text)["code"])
        self.fake.generate_response = (503, {"error": {"code": "genui_busy", "message": "busy", "retryable": True}})
        value = json.loads(self.call({"request": "a chart"}, call="call_2").text)
        self.assertEqual("genui_busy", value["code"])
        self.assertIn("several visuals", value["message"])
        self.fake.generate_response = None
        self.store.save("http://127.0.0.1:9", self.fake.token)  # nothing listens there
        value = json.loads(self.call({"request": "a chart"}, call="call_3").text)
        self.assertEqual("mac_unreachable", value["code"])
        self.store.save(self.fake.url, "wrong-token-" + "x" * 20)
        value = json.loads(self.call({"request": "a chart"}, call="call_4").text)
        self.assertEqual("mac_unauthorized", value["code"])
        self.assertEqual([], self.watcher.pending())

    def test_already_finished_artifacts_are_reported_honestly(self) -> None:
        self.configure()
        first = json.loads(self.call({"request": "a chart"}).text)
        self.fake.fail(first["artifactId"])
        result = self.call({"request": "a chart"})
        self.assertTrue(result.is_error)
        self.assertEqual("failed", json.loads(result.text)["status"])

    def test_instructions_explain_when_to_use_it(self) -> None:
        for phrase in ("ui_generate", "show_card", "data", "[Generated UI]", "one short line", "mac_look",
                       "chart", "diagram", "dashboard"):
            self.assertIn(phrase, GENERATED_UI_VOICE_INSTRUCTION)
        self.assertNotIn("ui_generate", PRIMARY_VOICE_INSTRUCTION, "the addendum appears only with the Mac bridge")


if __name__ == "__main__":
    unittest.main()
