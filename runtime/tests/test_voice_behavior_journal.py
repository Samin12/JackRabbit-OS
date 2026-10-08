"""The journal holds only what the user explicitly asks to add: no "R1 voice · HH:MM" block per session by default.

Every voice session used to be journaled at its end (auto_sessions on by default). It is now off unless the user
turns on "Record every voice conversation in my journal", and journal_add is only for explicit requests.
"""

from __future__ import annotations

from pathlib import Path
import unittest

from sam_runtime.domains.heptabase_journal import register_journal_tools
from sam_runtime.domains.heptabase_journal.settings import JournalSettings
from sam_runtime.tools import ToolCatalog

from heptabase_fakes import JournalHarness

USER = "conversation.item.input_audio_transcription.completed"
WEB = Path(__file__).resolve().parents[2] / "web" / "management"


class JournalExplicitOnlyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = JournalHarness()
        self.addCleanup(self.h.close)

    def test_a_new_install_does_not_journal_voice_sessions(self) -> None:
        self.assertFalse(JournalSettings().auto_sessions)
        self.assertFalse(self.h.service.settings().auto_sessions, "the fresh database row defaults to off too")
        self.h.connect()
        self.assertFalse(self.h.service.device_status()["autoSessions"])
        at = int(self.h.clock() * 1000)
        entries = [{"role": "user", "eventType": USER, "text": "What's on my calendar this afternoon?", "at": at}]
        self.assertEqual({"journaled": False, "reason": "auto_sessions_off"},
                         self.h.service.finalize_session("voice-1", entries))
        self.assertEqual([], [row for row in self.h.rows() if row["kind"] == "session"])
        # Turned on by the user, it works as before.
        self.h.service.save_settings({"autoSessions": True})
        self.assertTrue(self.h.service.finalize_session("voice-2", entries)["journaled"])

    def test_journal_add_is_only_for_explicit_requests(self) -> None:
        self.h.connect()
        catalog = ToolCatalog()
        register_journal_tools(catalog, self.h.service)
        tools = {item["name"]: item for item in catalog.realtime_definitions()}
        add = tools["journal_add"]["description"]
        for phrase in ("ONLY when the user explicitly asks", "add, save, write, put or note",
                       "not as a fallback or workaround when another action fails", "a calendar event",
                       "not for complaints, comments or questions about the journal", "verbatim"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, add)
        self.assertIn("Record every voice conversation in my journal", tools["journal_pause"]["description"])

    def test_the_settings_page_has_a_clear_toggle(self) -> None:
        script = (WEB / "heptabase.js").read_text(encoding="utf-8")
        self.assertIn('toggle("autoSessions","Record every voice conversation in my journal"', script)
        self.assertIn("Off (default)", script)


if __name__ == "__main__":
    unittest.main()
