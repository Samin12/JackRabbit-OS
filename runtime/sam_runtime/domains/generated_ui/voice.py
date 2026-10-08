"""Voice guidance for generated visuals (appended to the primary voice instructions while the Mac bridge is set up)."""

from __future__ import annotations

from .client import GeneratedUiClient

GENERATED_UI_VOICE_INSTRUCTION = (
    "Visuals: with the Mac connected you can make real visuals with ui_generate: charts and graphs, diagrams and "
    "flows, small dashboards, timelines, comparisons and visual explainers, designed on the Mac and shown on the "
    "R1 screen and in the desktop app. Use ui_generate whenever the user asks to see, chart, graph, plot, draw, "
    "map out or visualize something, or when numbers or a structure are much clearer as a picture. Put every fact "
    "and number it needs into data; the designer sees nothing else. Keep show_card for quick small cards (timers, "
    "a short list, a status, the next meeting). After calling ui_generate, say one short line (for example "
    "\"Drawing that now.\") and carry on; do not describe the visual before it exists. When a message starting "
    "with [Generated UI] arrives with the picture, describe it in one or two sentences with the key takeaway; do "
    "not read every number. If it fails, say so in a few words and offer a card instead. Screenshots from "
    "mac_look also appear in the chat."
)


class GeneratedUiVoiceContext:
    """The visuals addendum, only while the Mac bridge is configured (no network at session start)."""

    def __init__(self, client: GeneratedUiClient) -> None:
        self._client = client

    def render(self) -> str:
        try:
            return GENERATED_UI_VOICE_INSTRUCTION if self._client.configured() else ""
        except Exception:
            return ""
