from __future__ import annotations

from dataclasses import replace
import json
from pathlib import Path
import tempfile
import unittest
from urllib.request import Request, urlopen

from heptabase_fakes import FakeBridge

from sam_runtime.agents import AgentAudience
from sam_runtime.application import RuntimeApplication
from sam_runtime.config import RuntimeConfig
from sam_runtime.domains.heptabase_journal import JOURNAL_TOOL_SET
from sam_runtime.storage.migrations import LATEST_VERSION

TOKEN = "a" * 43


class _Bridge(FakeBridge):
    def hasOpenAiPlatformKey(self) -> bool:  # noqa: N802
        return False

    def getOpenAiPlatformKey(self):  # noqa: N802
        return None

    def putOpenAiPlatformKey(self, value: str) -> None:  # noqa: N802
        pass

    def deleteOpenAiPlatformKey(self) -> None:  # noqa: N802
        pass

    def hasOpenAiSubscriptionTokens(self) -> bool:  # noqa: N802
        return False

    def getOpenAiSubscriptionTokens(self):  # noqa: N802
        return None

    def putOpenAiSubscriptionTokens(self, value: str) -> None:  # noqa: N802
        pass

    def deleteOpenAiSubscriptionTokens(self) -> None:  # noqa: N802
        pass


class HeptabaseApplicationWiringTest(unittest.TestCase):
    def test_runtime_boots_with_journal_routes_tools_and_voice_audience(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            config = replace(RuntimeConfig.create(directory, TOKEN), local_api_port=0)
            config.prepare_directories()
            application = RuntimeApplication(config, credential_bridge=_Bridge())
            application.start()
            try:
                self.assertEqual(LATEST_VERSION, application.health()["database"]["migrationVersion"])
                self.assertGreaterEqual(LATEST_VERSION, 45)
                binding = application._audience_router.binding_for(JOURNAL_TOOL_SET)  # noqa: SLF001
                self.assertEqual(AgentAudience.VOICE, binding.audience)
                names = {item["name"] for item in application._tools.realtime_definitions()}  # noqa: SLF001
                self.assertFalse({"journal_add", "journal_read", "journal_pause"} & names, "hidden until connected")
                port = application._server.port  # noqa: SLF001
                request = Request(f"http://127.0.0.1:{port}/v1/journal/status",
                                  headers={"Authorization": "Bearer " + TOKEN})
                with urlopen(request, timeout=5) as response:
                    status = json.loads(response.read())
                self.assertEqual(False, status["connected"])
                self.assertFalse(status["autoSessions"], "a new R1 journals only what the user asks to add")
            finally:
                application.stop()
            self.assertTrue(Path(directory, "data", "resono.sqlite3").exists())


if __name__ == "__main__":
    unittest.main()
