"""Control of the user's Mac from Voice through the SamRabbit Mac bridge (cua-driver on the Mac)."""

from .client import MacControlClient, MacFailure
from .google_account import GoogleAccountSetting, with_google_account
from .tools import MAC_TOOL_SET, MacToolHandlers, register_mac_tools
from .voice import MAC_VOICE_INSTRUCTION, MacVoiceContext

__all__ = [
    "GoogleAccountSetting",
    "MAC_TOOL_SET",
    "MAC_VOICE_INSTRUCTION",
    "MacControlClient",
    "MacFailure",
    "MacToolHandlers",
    "MacVoiceContext",
    "register_mac_tools",
    "with_google_account",
]
