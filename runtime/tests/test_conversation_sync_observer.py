"""The tool observer (``tool.completed``, mac_look screenshots), the observer list, and session.finalized."""

from __future__ import annotations

import base64
import json
from types import SimpleNamespace
import unittest

from conversation_sync_fakes import TINY_JPEG, SyncHarness

from sam_runtime.agents import AgentKind
from sam_runtime.domains.conversation_sync import ConversationSyncObserver
from sam_runtime.domains.conversation_sync.events import blob_id_for
from sam_runtime.domains.mac import MacControlClient, register_mac_tools
from sam_runtime.memory.evidence import VoiceToolEvidenceRecorder
from sam_runtime.storage.sessions import SessionTranscriptRepository
from sam_runtime.tools import ToolCatalog, ToolDefinition, ToolInvocationResult
from sam_runtime.tools.definitions import ToolInvocationContext

from mac_fakes import FakeMacControlBridge

CONV = "c_0123456789abcdef0123"
SESSION = "cd" * 12
SCHEMA = {"type": "object", "properties": {"q": {"type": "string"}, "password": {"type": "string"}},
          "additionalProperties": True}


class ConversationSyncObserverTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = SyncHarness()
        self.addCleanup(self.h.close)
        self.service = self.h.service
        self.catalog = ToolCatalog()
        self.results: dict[str, ToolInvocationResult] = {}
        for name in ("echo", "mac_look"):
            self.catalog.register(ToolDefinition(
                tool_id=f"test.{name}.v1", name=name, description=name, input_schema=SCHEMA,
                handler=lambda arguments, tool=name: self.results[tool],
            ))
        self.catalog.add_invocation_observer(ConversationSyncObserver(self.service))
        self.service.link_call(CONV, SESSION)

    def invoke(self, name: str, arguments: dict[str, object], *, session: str | None = SESSION,
               call: str | None = "call_7") -> ToolInvocationResult:
        context = ToolInvocationContext(AgentKind.VOICE, voice_session_id=session, tool_call_id=call,
                                        user_utterance_id=3)
        return self.catalog.invoke(name, arguments, agent=AgentKind.VOICE, context=context)

    def test_tool_completed_is_redacted_bounded_and_idempotent_per_call(self) -> None:
        self.results["echo"] = ToolInvocationResult("é" * 5000 + " my password is hunter2")
        self.invoke("echo", {"q": "weather in Lisbon", "password": "hunter2", "nested": {"api_key": "k"}})
        (event,) = self.h.payloads()
        self.assertEqual(f"rt:{SESSION}:call_7", event["id"])
        self.assertEqual({"type": "tool.completed", "conversationId": CONV, "sessionId": SESSION, "origin": "model",
                          "tool": "echo", "isError": False, "toolCallId": "call_7", "utteranceId": 3,
                          "resultTruncated": True},
                         {key: event[key] for key in ("type", "conversationId", "sessionId", "origin", "tool",
                                                      "isError", "toolCallId", "utteranceId", "resultTruncated")})
        self.assertEqual({"q": "weather in Lisbon", "password": "[REDACTED]", "nested": {"api_key": "[REDACTED]"}},
                         event["arguments"])
        self.assertLessEqual(len(event["result"].encode("utf-8")), 4096)
        self.assertNotIn("hunter2", json.dumps(event))
        self.invoke("echo", {"q": "again"})
        self.assertEqual(1, len(self.h.rows()), "the same tool call id is one event")
        self.invoke("echo", {"q": "x"}, call=None)
        self.assertEqual(2, len(self.h.rows()), "no call id: a fresh id")

    def test_only_voice_sessions_and_only_when_enabled(self) -> None:
        self.results["echo"] = ToolInvocationResult("ok")
        self.invoke("echo", {}, session=None)
        self.catalog.invoke("echo", {}, agent=AgentKind.VOICE)
        self.service.save_settings({"includeTools": False})
        self.invoke("echo", {})
        self.service.save_settings({"includeTools": True, "enabled": False})
        self.invoke("echo", {})
        self.assertEqual([], self.h.rows())
        self.service.save_settings({"enabled": True})
        other = "ef" * 12
        self.invoke("echo", {}, session=other)
        self.assertEqual("s_" + other, self.h.payloads()[0]["conversationId"], "an unlinked session is its own conversation")

    def test_mac_look_without_a_bridge_blob_stores_the_image_and_an_image_event(self) -> None:
        self.results["mac_look"] = ToolInvocationResult(
            json.dumps({"ok": True, "image": "attached", "width": 1024, "height": 640}),
            structured_content={"image": {"mime": "image/jpeg", "base64": base64.b64encode(TINY_JPEG).decode()},
                                "width": 1024, "height": 640})
        self.invoke("mac_look", {})
        blob = blob_id_for(TINY_JPEG)
        tool, image = self.h.payloads()
        self.assertEqual(("tool.completed", blob), (tool["type"], tool["blobId"]))
        self.assertNotIn("base64", json.dumps(tool))
        self.assertEqual({"id": f"rt:{SESSION}:call_7:image", "type": "image", "source": "mac_screenshot",
                          "blobId": blob, "mime": "image/jpeg", "width": 1024, "height": 640, "bytes": len(TINY_JPEG)},
                         {key: image[key] for key in ("id", "type", "source", "blobId", "mime", "width", "height",
                                                      "bytes")})
        (row,) = self.h.blob_rows()
        self.assertEqual((blob, TINY_JPEG, CONV), (row["blob_id"], bytes(row["data"]), row["conversation_id"]))
        self.assertEqual([blob, blob], [row["blob_id"] for row in self.h.rows()], "both wait for the blob")

    def test_mac_look_with_a_bridge_blob_sends_no_bytes_and_no_second_image_event(self) -> None:
        blob = blob_id_for(TINY_JPEG)
        structured = {"image": {"mime": "image/jpeg", "base64": base64.b64encode(TINY_JPEG).decode()},
                      "width": 10, "height": 10, "blobId": blob, "imageEventId": "mac:1234"}
        self.results["mac_look"] = ToolInvocationResult("{}", structured_content=structured)
        self.invoke("mac_look", {})
        (tool,) = self.h.payloads()
        self.assertEqual(blob, tool["blobId"])
        self.assertEqual([], self.h.blob_rows())
        self.assertIsNone(self.h.rows()[0]["blob_id"], "nothing to wait for: the Mac has it")
        self.results["mac_look"] = ToolInvocationResult("{}", structured_content={**structured, "imageEventId": None})
        self.invoke("mac_look", {}, call="call_8")
        kinds = [item["type"] for item in self.h.payloads()]
        self.assertEqual(["tool.completed", "tool.completed", "image"], kinds, "the bridge did not file one")
        self.service.save_settings({"includeImages": False})
        self.results["mac_look"] = ToolInvocationResult("{}", structured_content={"image": structured["image"]})
        self.invoke("mac_look", {}, call="call_9")
        self.assertNotIn("blobId", self.h.payloads()[-1])
        self.assertEqual([], self.h.blob_rows())

    def test_observers_are_a_list_and_one_failing_never_stops_the_others_or_the_tool(self) -> None:
        sessions = SessionTranscriptRepository(self.h.database)
        catalog = ToolCatalog()
        catalog.register(ToolDefinition(tool_id="test.ok.v1", name="ok", description="ok", input_schema=SCHEMA,
                                        handler=lambda arguments: ToolInvocationResult("fine")))
        seen: list[str] = []
        catalog.set_invocation_observer(VoiceToolEvidenceRecorder(sessions))
        catalog.add_invocation_observer(lambda *args: (_ for _ in ()).throw(RuntimeError("boom")))
        catalog.add_invocation_observer(lambda context, name, arguments, result: seen.append(name))
        catalog.add_invocation_observer(ConversationSyncObserver(self.service))
        context = ToolInvocationContext(AgentKind.VOICE, voice_session_id=SESSION, tool_call_id="call_1")
        result = catalog.invoke("ok", {}, agent=AgentKind.VOICE, context=context)
        self.assertEqual(("fine", False), (result.text, result.is_error))
        self.assertEqual(["ok"], seen)
        self.assertEqual(["tool.ok.completed"], [entry.event_type for entry in sessions.entries(SESSION)])
        self.assertEqual(["tool.completed"], [item["type"] for item in self.h.payloads()])
        catalog.set_invocation_observer(None)
        catalog.invoke("ok", {}, agent=AgentKind.VOICE, context=context)
        self.assertEqual(["ok"], seen, "set_invocation_observer(None) clears the list")

    def test_session_finalized_carries_the_transcript_and_summary(self) -> None:
        entries = [{"role": "user", "eventType": "conversation.item.input_audio_transcription.completed",
                    "text": "remember my code is 1234", "at": 1_760_000_000_001},
                   {"role": "assistant", "eventType": "response.audio_transcript.done", "text": "Got it."},
                   {"role": "tool", "text": "ignored"}, "junk"]
        self.service.session_finalized(SESSION, entries, SimpleNamespace(summary="User shared a code.", memory_count=1))
        (event,) = self.h.payloads()
        self.assertEqual({"id": f"rt:{SESSION}:finalized", "type": "session.finalized", "conversationId": CONV,
                          "reviewed": True, "entryCount": 2, "memoryCount": 1, "summary": "User shared a code."},
                         {key: event[key] for key in ("id", "type", "conversationId", "reviewed", "entryCount",
                                                      "memoryCount", "summary")})
        self.assertEqual(1_760_000_000_001, event["entries"][0]["at"])
        self.assertEqual("remember my code is [redacted]", event["entries"][0]["text"])
        self.service.session_finalized(SESSION, entries, None)
        self.assertEqual(1, len(self.h.rows()), "one finalized event per session")
        self.service.save_settings({"includeAssistant": False})
        self.service.session_finalized("12" * 12, entries, None)
        last = self.h.payloads()[-1]
        self.assertEqual((False, ["user"]), (last["reviewed"], [item["role"] for item in last["entries"]]))
        self.service.session_finalized("bad id!", entries, None)  # never raises

    def test_mac_look_asks_the_bridge_to_file_the_screenshot_in_the_conversation(self) -> None:
        fake = FakeMacControlBridge()
        self.addCleanup(fake.close)
        fake.screen = True
        self.h.store.save(fake.url, fake.token)
        catalog = ToolCatalog()
        handlers = register_mac_tools(catalog, MacControlClient(self.h.store))
        handlers.set_screenshot_conversation(self.service.screenshot_conversation)
        context = ToolInvocationContext(AgentKind.VOICE, voice_session_id=SESSION, tool_call_id="call_1")
        result = catalog.invoke("mac_look", {}, agent=AgentKind.VOICE, context=context)
        self.assertFalse(result.is_error, result.text)
        self.assertEqual([CONV], fake.last("/v1/mac/screenshot")["query"]["conversation"])
        self.service.save_settings({"includeImages": False})
        catalog.invoke("mac_look", {}, agent=AgentKind.VOICE, context=context)
        self.assertNotIn("conversation", fake.last("/v1/mac/screenshot")["query"])
        self.assertIsNone(self.service.screenshot_conversation(None))


if __name__ == "__main__":
    unittest.main()
