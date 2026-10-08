"""Asking for OpenGenUI / a generated UI means ui_generate (the Mac-made visual), not a native show_card.

The real case: "Can you make a card in this chat? I wanna test the OpenGenUI stuff." got a native show_card.
"""

from __future__ import annotations

import unittest

from sam_runtime.domains.generated_ui import GENERATED_UI_VOICE_INSTRUCTION
from sam_runtime.domains.generated_ui.tools import UI_GENERATE_SPEC
from sam_runtime.realtime.modes import PRIMARY_VOICE_INSTRUCTION
from sam_runtime.tools.genui import GENUI_VOICE_INSTRUCTION, genui_tool_definitions

TRIGGERS = ("OpenGenUI", "OpenGenerativeUI", "generative UI", "generated UI", "widget", "visual", "chart", "graph",
            "dashboard")


class GeneratedUiTriggerTest(unittest.TestCase):
    def test_the_visuals_addendum_routes_generated_ui_requests_to_ui_generate(self) -> None:
        for phrase in TRIGGERS:
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, GENERATED_UI_VOICE_INSTRUCTION)
        self.assertIn("call ui_generate, not show_card, even if they also say card", GENERATED_UI_VOICE_INSTRUCTION)
        self.assertIn("make a card to test the OpenGenUI stuff", GENERATED_UI_VOICE_INSTRUCTION)
        self.assertIn("Keep show_card for quick small cards (timers", GENERATED_UI_VOICE_INSTRUCTION)

    def test_the_ui_generate_tool_names_the_triggers(self) -> None:
        description = UI_GENERATE_SPEC[1]
        for phrase in ("OpenGenUI", "OpenGenerativeUI", "generative UI", "widget", "dashboard", "card"):
            with self.subTest(phrase=phrase):
                self.assertIn(phrase, description)

    def test_show_card_steps_aside_for_generated_uis(self) -> None:
        show = {item["name"]: item for item in genui_tool_definitions()}["show_card"]["description"]
        self.assertIn("Not for a generated UI", show)
        self.assertIn("use ui_generate if it is offered", show)
        self.assertIn("A generated UI is not a card", GENUI_VOICE_INSTRUCTION)
        self.assertIn("OpenGenUI", GENUI_VOICE_INSTRUCTION)
        # The card guidance is always in the primary instructions; ui_generate exists only with the Mac bridge.
        self.assertNotIn("ui_generate", PRIMARY_VOICE_INSTRUCTION)
        self.assertIn("timers always use show_card", GENUI_VOICE_INSTRUCTION)


if __name__ == "__main__":
    unittest.main()
