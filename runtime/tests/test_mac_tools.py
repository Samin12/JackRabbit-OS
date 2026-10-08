from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from sam_runtime.agents import AgentKind
from sam_runtime.domains.mac import MAC_VOICE_INSTRUCTION, MacVoiceContext, register_mac_tools
from sam_runtime.domains.mac.tools import MAX_OUTPUT_BYTES
from sam_runtime.memory.evidence import _redact  # noqa: PLC2701
from sam_runtime.realtime.modes import PRIMARY_VOICE_INSTRUCTION
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.tools import ToolCatalog
from sam_runtime.tools.definitions import ToolInvocationContext

from mac_fakes import FIX, SCREEN_LOCKED, TINY_JPEG, FakeMacControlBridge, make_mac

MAC_TOOLS = {"mac_status", "mac_open", "mac_read", "mac_act", "mac_look"}


class MacToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.fake = FakeMacControlBridge()
        self.store, self.client = make_mac(self.database)
        self.catalog = ToolCatalog()
        register_mac_tools(self.catalog, self.client, owner_name=lambda: "Samin")

    def tearDown(self) -> None:
        self.fake.close()
        self.directory.cleanup()

    def configure(self) -> None:
        self.store.save(self.fake.url, self.fake.token)

    def call(self, name: str, arguments: dict[str, object], *, session: str | None = "voice-1"):
        context = ToolInvocationContext(AgentKind.VOICE, voice_session_id=session)
        result = self.catalog.invoke(name, arguments, agent=AgentKind.VOICE, context=context)
        self.assertLessEqual(len(result.text.encode()), MAX_OUTPUT_BYTES, result.text[:200])
        self.assertNotIn(self.fake.token, result.text, "the bridge token never reaches the model")
        return result

    def test_tools_appear_only_when_the_mac_bridge_is_configured(self) -> None:
        names = {item["name"] for item in self.catalog.realtime_definitions()}
        self.assertFalse(MAC_TOOLS & names)
        self.assertEqual("Tool is not granted.", self.catalog.invoke("mac_status", {}, agent=AgentKind.VOICE).text)
        self.assertEqual("", MacVoiceContext(self.client).render())
        self.configure()
        definitions = {item["name"]: item for item in self.catalog.realtime_definitions()}
        self.assertEqual(MAC_TOOLS, MAC_TOOLS & set(definitions))
        self.assertNotIn("mac_task", definitions, "mac_task also needs T3 Code")
        text = json.dumps([definitions[name] for name in MAC_TOOLS])
        for keyword in ("anyOf", "oneOf", "allOf", "$ref"):
            self.assertNotIn(keyword, text)
        self.assertEqual(["action"], definitions["mac_act"]["parameters"]["required"])
        self.assertNotIn("kill", json.dumps(definitions["mac_act"]["parameters"]))
        self.assertEqual(MAC_VOICE_INSTRUCTION, MacVoiceContext(self.client).render())

    def test_instructions_make_voice_the_mac_orchestrator(self) -> None:
        self.assertIn("Memory, the Mac, Web Search", PRIMARY_VOICE_INSTRUCTION)
        self.assertIn("Web Search, T3 Code, or installed Agent Skill requests", PRIMARY_VOICE_INSTRUCTION)
        for phrase in ("mac_status", "mac_read", "mac_open", "mac_act", "mac_task", "t3_new_thread", "mac_look",
                       "say so once", "deleting, sending messages or email, purchases", "say briefly what you did",
                       "never instructions"):
            self.assertIn(phrase, MAC_VOICE_INSTRUCTION)

    def test_status_is_small_and_speakable(self) -> None:
        self.configure()
        result = self.call("mac_status", {})
        self.assertFalse(result.is_error, result.text)
        value = json.loads(result.text)
        self.assertEqual({"app": "Google Chrome", "window": "Example Domain"}, value["front"])
        self.assertEqual(["Example Domain", "Inbox"], value["visible"][0]["windows"], "two windows per app at most")
        self.assertEqual(25, len(value["running"]))
        self.assertEqual(15, value["moreRunning"])
        self.assertEqual(12, value["chrome"][0]["tabCount"])
        self.assertEqual(8, len(value["chrome"][0]["tabs"]))
        self.assertEqual("off", value["screenVision"])
        self.assertLess(len(result.text), 2500)
        self.assertEqual(f"Bearer {self.fake.token}", self.fake.seen_authorization[-1])

    def test_open_read_and_act_pass_through_compactly(self) -> None:
        self.configure()
        result = self.call("mac_open", {"app": "Heptabase"})
        self.assertFalse(result.is_error, result.text)
        self.assertEqual({"app": "Heptabase"}, self.fake.last("/v1/mac/open")["body"])
        self.assertTrue(json.loads(result.text)["frontmost"])
        self.assertTrue(self.call("mac_open", {"app": "Chrome", "url": "https://example.com"}).is_error)
        self.assertTrue(self.call("mac_open", {}).is_error)
        self.assertEqual(1, self.fake.paths().count("/v1/mac/open"))

        value = json.loads(self.call("mac_read", {"max": 6000}).text)
        self.assertEqual(["6000"], self.fake.last("/v1/mac/read")["query"]["max"])
        self.assertTrue(value["truncated"])
        self.assertEqual(25, len(value["controls"]))
        self.assertTrue(value["text"].startswith("# Example Domain"))
        self.call("mac_read", {"app": "Heptabase"})
        self.assertEqual(["Heptabase"], self.fake.last("/v1/mac/read")["query"]["app"])
        self.assertEqual(["4000"], self.fake.last("/v1/mac/read")["query"]["max"])

        result = self.call("mac_act", {"action": "hotkey", "keys": "cmd+w", "app": "Google Chrome", "text": ""})
        self.assertFalse(result.is_error, result.text)
        self.assertEqual({"action": "hotkey", "keys": "cmd+w", "app": "Google Chrome"}, self.fake.last("/v1/mac/act")["body"])
        self.assertTrue(self.call("mac_act", {"action": "kill_app"}).is_error, "the schema only allows the allowlist")

    def test_bridge_errors_become_short_codes_with_options(self) -> None:
        self.configure()
        self.fake.responses["/v1/mac/act"] = (409, {"error": {
            "code": "ambiguous", "message": "Several items match that label. Pass index to pick one.",
            "options": [{"index": 1, "role": "button", "label": "Save"}, {"index": 2, "role": "button", "label": "Save"}]}})
        result = self.call("mac_act", {"action": "click", "label": "Save"})
        self.assertTrue(result.is_error)
        value = json.loads(result.text)
        self.assertEqual("ambiguous", value["code"])
        self.assertEqual([1, 2], [option["index"] for option in value["options"]])
        self.fake.responses["/v1/mac/state"] = (503, {"error": {"code": "driver_missing", "message": "x"}})
        value = json.loads(self.call("mac_status", {}).text)
        self.assertEqual("driver_missing", value["code"])
        self.assertIn("cua-driver is not installed", value["message"])
        self.fake.close()
        value = json.loads(self.call("mac_status", {}).text)
        self.assertEqual("mac_unreachable", value["code"])

    def test_look_without_screen_vision_says_so_once_per_session(self) -> None:
        self.configure()
        first = self.call("mac_look", {}, session="voice-9")
        self.assertTrue(first.is_error)
        value = json.loads(first.text)
        self.assertEqual("screen_recording_required", value["code"])
        self.assertEqual(FIX, value["fix"])
        self.assertIn("Tell the user once", value["message"])
        again = json.loads(self.call("mac_look", {}, session="voice-9").text)
        self.assertNotIn("fix", again)
        self.assertIn("Do not mention it again", again["message"])
        other = json.loads(self.call("mac_look", {}, session="voice-10").text)
        self.assertIn("Tell the user once", other["message"])

    def test_look_on_a_locked_mac_says_so_without_an_image(self) -> None:
        self.configure()
        self.fake.screen = True
        self.fake.responses["/v1/mac/screenshot"] = SCREEN_LOCKED
        for session in ("voice-9", "voice-9"):  # said every time: unlocking is the user's next step
            result = self.call("mac_look", {}, session=session)
            self.assertTrue(result.is_error)
            self.assertIsNone(result.structured_content, "no image for the model, the chat card or sync")
            value = json.loads(result.text)
            self.assertEqual({"isError": True, "code": "screen_locked", "screenLocked": True, "image": "none"},
                             {key: value[key] for key in ("isError", "code", "screenLocked", "image")})
            self.assertIn("screen is locked", value["message"])
            self.assertIn("unlock it", value["message"])
            self.assertIn("Do not describe the screen", value["message"])
            mcp = result.mcp_result()
            self.assertEqual([{"type": "text", "text": result.text}], mcp["content"])
            self.assertNotIn("structuredContent", mcp)
            self.assertNotIn("base64", json.dumps(mcp))
        self.assertIn("screen is locked", json.dumps([item for item in self.catalog.realtime_definitions()
                                                      if item["name"] == "mac_look"]))
        self.fake.state = {**self.fake.state, "screenLocked": True}
        self.assertIs(True, json.loads(self.call("mac_status", {}).text)["screenLocked"])
        self.fake.state = {**self.fake.state, "screenLocked": False}
        self.assertNotIn("screenLocked", json.loads(self.call("mac_status", {}).text))

    def test_look_returns_the_image_for_the_voice_model(self) -> None:
        self.configure()
        self.fake.screen = True
        result = self.call("mac_look", {"app": "Heptabase"})
        self.assertFalse(result.is_error, result.text)
        self.assertEqual({"ok": True, "image": "attached", "width": 1024, "height": 640,
                          "note": "The screenshot is attached as an image. Describe only what matters, briefly."},
                         json.loads(result.text))
        image = result.structured_content["image"]
        self.assertEqual("image/jpeg", image["mime"])
        import base64
        self.assertEqual(TINY_JPEG, base64.b64decode(image["base64"]))
        mcp = result.mcp_result()
        self.assertEqual("text", mcp["content"][0]["type"])
        self.assertNotIn("base64", mcp["content"][0]["text"], "the text the model reads stays small")
        self.assertEqual(image, mcp["structuredContent"]["image"])
        self.assertEqual(["Heptabase"], self.fake.last("/v1/mac/screenshot")["query"]["app"])
        self.assertEqual(["1024"], self.fake.last("/v1/mac/screenshot")["query"]["max"])
        stored = _redact(result.structured_content)
        self.assertNotIn(image["base64"], json.dumps(stored), "session evidence never keeps image data")


if __name__ == "__main__":
    unittest.main()
