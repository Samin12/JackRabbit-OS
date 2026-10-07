from __future__ import annotations

import unittest

from heptabase_fakes import JournalHarness

from sam_runtime.agents import AgentAudience, AgentAudienceRouter, AgentKind
from sam_runtime.domains.heptabase_journal import JOURNAL_TOOL_SET, NotConnected, register_journal_tools
from sam_runtime.domains.heptabase_journal.localtime import clock_label, journal_date, resolve_zone
from sam_runtime.domains.heptabase_journal.verbatim import strip_command, verify_words
from sam_runtime.domains.tasks import TaskRepository, TaskService
from sam_runtime.memory.evidence import VoiceToolEvidenceRecorder
from sam_runtime.storage.agent_audiences import AgentAudienceRepository
from sam_runtime.tools import ToolCatalog, ToolInvocationContext
from sam_runtime.tools.tasks import TasksToolPackage

USER = "conversation.item.input_audio_transcription.completed"
ASSISTANT = "response.output_audio_transcript.done"
ZONE = resolve_zone("America/New_York")


class VerbatimGuardTest(unittest.TestCase):
    def test_guard_records_only_the_users_words(self) -> None:
        cases = [
            ("the orb looks great today", "Add to my journal that the orb looks GREAT today!",
             ("the orb looks GREAT today!", "matched")),
            ("Orb brightness is too high", "Add to my journal that the orb is way too bright",
             ("the orb is way too bright", "utterance")),
            ("I finished the deck", "I finished the deck, add that to my journal",
             ("I finished the deck", "matched")),
            ("buy milk", "Note to self: buy milk", ("buy milk", "matched")),
            ("anything", "What's the weather tomorrow?", (None, "unverified")),
            ("the orb looks great", "add that to my journal", (None, "unverified")),
            ("hello", None, (None, "unverified")),
            ("What I said about the orb", "What's in my journal today?", (None, "unverified")),
        ]
        for text, utterance, expected in cases:
            with self.subTest(utterance=utterance):
                self.assertEqual(expected, verify_words(text, utterance))

    def test_strip_command_variants(self) -> None:
        for phrase, words in {
            "Hey Rabbit, can you put this in my journal: the demo went well.": "the demo went well.",
            "Please add a note to my journal saying I need to call mom": "I need to call mom",
            "Journal that I'm proud of today": "I'm proud of today",
            "Record in my diary that it rained": "it rained",
            "It rained all day. Save that to my journal please.": "It rained all day.",
        }.items():
            with self.subTest(phrase=phrase):
                self.assertEqual((words, True), strip_command(phrase))
        self.assertEqual(("just words", False), strip_command("just words"))


class JournalVoiceToolsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = JournalHarness()
        self.addCleanup(self.h.close)
        self.catalog = ToolCatalog()
        self.catalog.set_invocation_observer(VoiceToolEvidenceRecorder(self.h.sessions))
        register_journal_tools(self.catalog, self.h.service)
        TasksToolPackage(TaskService(TaskRepository(self.h.database))).register(self.catalog)
        self.today = journal_date(self.h.clock(), ZONE)

    def ctx(self, utterance: str | None, utterance_id: int, *, call: str = "call-1",
            session: str = "session-1") -> ToolInvocationContext:
        return ToolInvocationContext(AgentKind.VOICE, session, call, utterance, utterance_id)

    def add(self, text: str, utterance: str | None, utterance_id: int, **kwargs) -> dict[str, object]:
        result = self.catalog.invoke("journal_add", {"text": text}, context=self.ctx(utterance, utterance_id, **kwargs))
        return result.structured_content or {"text": result.text}

    def names(self, agent: AgentKind = AgentKind.VOICE) -> set[str]:
        return {item["name"] for item in self.catalog.mcp_definitions(agent)}

    def journal_text(self) -> str:
        return "\n".join(self.h.fake.journal.get(self.today, []))

    def test_tools_exist_only_for_voice_and_only_when_connected(self) -> None:
        journal = {"journal_add", "journal_read", "journal_pause"}
        self.assertFalse(journal & self.names())
        self.h.connect()
        self.assertEqual(journal, journal & self.names())
        self.assertFalse(journal & self.names(AgentKind.TEXT))
        self.h.service.disconnect()
        self.assertFalse(journal & self.names())

    def test_audience_binding_gates_the_tool_set(self) -> None:
        router = AgentAudienceRouter(AgentAudienceRepository(self.h.database))
        catalog = ToolCatalog(audience_router=router)
        register_journal_tools(catalog, self.h.service)
        self.h.connect()
        self.assertFalse(catalog.mcp_definitions(AgentKind.VOICE))
        router.set_audience(JOURNAL_TOOL_SET, AgentAudience.VOICE, changed_by="test", reason="test")
        self.assertEqual(3, len(catalog.mcp_definitions(AgentKind.VOICE)))

    def test_matched_words_come_from_the_transcript(self) -> None:
        self.h.connect()
        value = self.add("the orb looks great today", "Add to my journal that the orb looks GREAT today!", 1)
        self.assertEqual(("sent", True), (value["state"], value["recorded"]))
        label = clock_label(self.h.clock(), ZONE)
        self.assertEqual([(self.today, f"**{label}** the orb looks GREAT today!")], self.h.fake.appends())

    def test_paraphrase_is_replaced_by_the_users_words(self) -> None:
        self.h.connect()
        self.add("Orb brightness: too high (user feedback)", "Add to my journal that the orb is way too bright", 1)
        content = self.h.fake.appends()[0][1]
        self.assertTrue(content.endswith("** the orb is way too bright"), content)
        self.assertNotIn("feedback", content)

    def test_secrets_are_scrubbed_from_notes(self) -> None:
        self.h.connect()
        self.add("my stripe key is sk_live_51Habcdefghijklmn", "Journal that my stripe key is sk_live_51Habcdefghijklmn", 1)
        self.assertIn("my stripe key is \\[redacted\\]", self.h.fake.appends()[0][1])

    def test_duplicate_calls_record_once(self) -> None:
        self.h.connect()
        first = self.add("call the dentist", "Add to my journal: call the dentist", 4, call="a")
        second = self.add("call the dentist", "Add to my journal: call the dentist", 4, call="b")
        self.assertEqual(first["entryId"], second["entryId"])
        self.assertEqual(1, len(self.h.fake.appends()))

    def test_stale_utterance_is_held_then_verified_at_session_end(self) -> None:
        self.h.connect()
        value = self.add("I'm exhausted but happy", "What's the weather tomorrow?", 2)
        self.assertEqual(("queued", "pending"), (value["state"], value.get("verification")))
        self.assertEqual([], self.h.fake.appends(), "unverified text is never written")
        now_ms = int(self.h.clock() * 1000)
        result = self.h.service.finalize_session("session-1", [
            {"role": "user", "eventType": USER, "text": "What's the weather tomorrow?", "at": now_ms - 30_000},
            {"role": "user", "eventType": USER, "text": "Journal that I'm exhausted but happy.", "at": now_ms - 5_000},
        ])
        self.assertTrue(result["journaled"])
        self.h.service.drain()
        (_, content), = self.h.fake.appends()  # note + session block share the date: one batched call
        note_label = clock_label(now_ms / 1000 - 5, ZONE)
        session, note = content.split(f"\n\n**{note_label}** ")
        self.assertEqual("I'm exhausted but happy.", note)
        self.assertIn("What's the weather tomorrow?", session)
        self.assertNotIn("exhausted", session, "the journaled utterance is not repeated in the session block")

    def test_held_note_without_a_matching_utterance_is_dropped(self) -> None:
        self.h.connect()
        self.add("A beautifully summarized reflection on productivity", "Set a timer for ten minutes", 1)
        self.h.service.finalize_session("session-1", [
            {"role": "user", "eventType": USER, "text": "Set a timer for ten minutes", "at": int(self.h.clock() * 1000)},
        ])
        self.h.service.drain()
        self.assertFalse(any("reflection" in content for _, content in self.h.fake.appends()))
        self.assertEqual(0, self.h.service._outbox.counts()["held"])  # noqa: SLF001

    def test_double_called_held_note_is_written_once(self) -> None:
        self.h.connect()
        self.add("my left knee hurts", "Stop the timer", 2, call="a")
        self.add("My left knee hurts.", "Stop the timer", 2, call="b")
        self.h.service.finalize_session("session-1", [
            {"role": "user", "eventType": USER, "text": "Journal that my left knee hurts.", "at": int(self.h.clock() * 1000)},
        ])
        self.h.service.drain()
        joined = "\n".join(content for _, content in self.h.fake.appends())
        self.assertEqual(1, joined.count("my left knee hurts."))

    def test_reused_command_utterance_is_not_journaled_twice(self) -> None:
        self.h.connect()
        self.add("buy flowers", "Add to my journal that I should buy flowers", 3, call="a")
        second = self.add("Meeting moved to Friday", "Add to my journal that I should buy flowers", 3, call="b")
        self.assertEqual("pending", second.get("verification"))
        self.assertEqual(1, len(self.h.fake.appends()))

    def test_journal_read_is_bounded_scrubbed_and_plain(self) -> None:
        self.h.connect()
        self.h.fake.journal[self.today] = (
            ["What rabbit would be useful for", "", "key sk_live_51Habcdefghijklmnop", "ship less_blur"]
            + [f"- line {index} " + "x" * 60 for index in range(120)]
        )
        result = self.catalog.invoke("journal_read", {}, context=self.ctx("what did I journal today?", 1))
        value = result.structured_content
        self.assertFalse(result.is_error)
        self.assertEqual(self.today, value["date"])
        self.assertTrue(value["truncated"])
        self.assertLessEqual(len(value["text"].encode()), 4096)
        self.assertNotIn("sk_live", value["text"])
        self.assertIn("ship less_blur", value["text"], "escapes removed for speech")
        self.assertNotIn("\t", value["text"])
        bad = self.catalog.invoke("journal_read", {"date": "someday"}, context=self.ctx("x", 1))
        self.assertTrue(bad.is_error)

    def test_journal_add_after_disconnect_is_not_granted(self) -> None:
        self.h.connect()
        self.h.service.disconnect()
        result = self.catalog.invoke("journal_add", {"text": "hi"}, context=self.ctx("Journal that hi", 1))
        self.assertTrue(result.is_error)
        self.assertEqual("Tool is not granted.", result.text)
        with self.assertRaises(NotConnected):
            self.h.service.add_voice_note(self.ctx("Journal that hi", 1), "hi")

    def test_off_the_record_session_is_not_journaled(self) -> None:
        self.h.connect()
        paused = self.catalog.invoke("journal_pause", {"scope": "session"}, context=self.ctx("off the record", 1))
        self.assertEqual({"paused": True, "scope": "session"}, paused.structured_content)
        self.add("I love pizza", "Add to my journal that I love pizza", 2)
        result = self.h.service.finalize_session("session-1", [
            {"role": "user", "eventType": USER, "text": "Off the record please", "at": int(self.h.clock() * 1000)},
        ])
        self.assertEqual("off_the_record", result["reason"])
        self.h.service.drain()
        self.assertEqual(1, len(self.h.fake.appends()), "explicit note still recorded")
        self.assertFalse(any(content.startswith("**R1 voice") for _, content in self.h.fake.appends()))

    def test_session_block_with_times_actions_and_photo(self) -> None:
        self.h.connect()
        now = self.h.clock()
        start = now - 120
        self.catalog.invoke("tasks_add", {"text": "Call Devin"}, context=self.ctx("Add a task to call Devin", 1, call="t1"))
        prepared = self.h.sessions.entries("session-1")[-1]
        import json

        action = json.loads(prepared.text_content)["result"]["result"]
        self.catalog.invoke("tasks_confirm_action", {"actionId": action["actionId"], "contentHash": action["contentHash"]},
                            context=self.ctx("yes", 2, call="t2"))
        self.catalog.invoke("tasks_list", {}, context=self.ctx("what are my tasks", 3, call="t3"))
        entries = [
            {"role": "user", "eventType": USER, "text": "Add a task to call Devin", "at": int(start * 1000)},
            {"role": "assistant", "eventType": ASSISTANT, "text": "Should I add it?", "at": int(start * 1000) + 2000},
            {"role": "user", "eventType": USER, "text": "yes", "at": int(start * 1000) + 4000},
            {"role": "user", "eventType": "conversation.item.input_image.completed", "text": "[Image handoff: camera.jpg]",
             "at": int(now * 1000) + 1000},
        ]
        result = self.h.service.finalize_session("session-1", entries)
        self.assertTrue(result["journaled"])
        self.h.service.drain()
        (date, content), = self.h.fake.appends()
        self.assertEqual(journal_date(start, ZONE), date)
        self.assertTrue(content.startswith(f"**R1 voice · {clock_label(start, ZONE)}–"), content)
        self.assertIn(f"- {clock_label(start, ZONE)} Add a task to call Devin", content)
        self.assertIn('↳ Task added: "Call Devin"</hepta-color>', content)
        self.assertIn("↳ Shared a photo", content)
        self.assertNotIn("Should I add it", content, "assistant replies are off by default")
        self.assertNotIn("tasks_list", content)
        self.assertLess(content.index("Add a task"), content.index("Task added"))
        self.assertLess(content.index("Task added"), content.index("Shared a photo"))

    def test_assistant_replies_when_enabled(self) -> None:
        self.h.connect()
        self.h.service.save_settings({"includeAssistant": True})
        at = int(self.h.clock() * 1000)
        self.h.service.finalize_session("session-2", [
            {"role": "user", "eventType": USER, "text": "hello", "at": at},
            {"role": "assistant", "eventType": ASSISTANT, "text": "Hi there!", "at": at + 1000},
        ])
        self.h.service.drain()
        self.assertIn('<hepta-color type="text" color="gray">R1: Hi there!</hepta-color>', self.h.fake.appends()[0][1])

    def test_entries_without_times_use_finalize_time(self) -> None:
        self.h.connect()
        self.h.service.finalize_session("session-3", [
            {"role": "user", "eventType": USER, "text": "first thing"},
            {"role": "user", "eventType": USER, "text": "second thing", "at": "soon"},
        ])
        self.h.service.drain()
        content = self.h.fake.appends()[0][1]
        label = clock_label(self.h.clock(), ZONE)
        self.assertEqual(f"**R1 voice · {label}**\n\n- first thing\n\n- second thing", content)

    def test_finalize_is_deduplicated_and_respects_settings(self) -> None:
        self.h.connect()
        entries = [{"role": "user", "eventType": USER, "text": "once only", "at": int(self.h.clock() * 1000)}]
        self.assertFalse(self.h.service.finalize_session("session-4", entries)["duplicate"])
        self.assertTrue(self.h.service.finalize_session("session-4", entries)["duplicate"])
        self.h.service.drain()
        self.assertEqual(1, len(self.h.fake.appends()))
        self.h.service.save_settings({"autoSessions": False})
        self.assertEqual("auto_sessions_off", self.h.service.finalize_session("session-5", entries)["reason"])
        self.h.service.save_settings({"autoSessions": True})
        assistant_only = [{"role": "assistant", "eventType": ASSISTANT, "text": "nobody spoke",
                           "at": int(self.h.clock() * 1000)}]
        self.assertEqual("no_user_speech", self.h.service.finalize_session("session-8", assistant_only)["reason"])

    def test_not_connected_queues_nothing(self) -> None:
        result = self.h.service.finalize_session("session-6", [{"role": "user", "eventType": USER, "text": "hi"}])
        self.assertEqual("not_connected", result["reason"])
        self.assertEqual([], self.h.rows())

    def test_session_date_uses_the_local_start_time(self) -> None:
        self.h.connect()
        from datetime import datetime
        from zoneinfo import ZoneInfo

        late = datetime(2026, 10, 7, 23, 58, tzinfo=ZoneInfo("America/New_York")).timestamp()
        self.h.clock.now = late + 240
        self.h.service.finalize_session("session-7", [
            {"role": "user", "eventType": USER, "text": "almost midnight", "at": int(late * 1000)},
            {"role": "user", "eventType": USER, "text": "now it's tomorrow", "at": int((late + 180) * 1000)},
        ])
        self.h.service.drain()
        date, content = self.h.fake.appends()[0]
        self.assertEqual("2026-10-07", date)
        self.assertTrue(content.startswith("**R1 voice · 23:58–00:01**"), content)


if __name__ == "__main__":
    unittest.main()
