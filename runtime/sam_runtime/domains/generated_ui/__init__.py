"""Generated UIs from Voice: the Mac bridge designs a visual with Claude Code; the R1 shows the picture."""

from .client import GeneratedUiClient, GeneratedUiFailure
from .tools import GENERATED_UI_TOOL_SET, GeneratedUiToolHandlers, register_generated_ui_tools
from .voice import GENERATED_UI_VOICE_INSTRUCTION, GeneratedUiVoiceContext
from .watcher import ArtifactWatcher

__all__ = [
    "GENERATED_UI_TOOL_SET",
    "GENERATED_UI_VOICE_INSTRUCTION",
    "ArtifactWatcher",
    "GeneratedUiClient",
    "GeneratedUiFailure",
    "GeneratedUiToolHandlers",
    "GeneratedUiVoiceContext",
    "register_generated_ui_tools",
]
