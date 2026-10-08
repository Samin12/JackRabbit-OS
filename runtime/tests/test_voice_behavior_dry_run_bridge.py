"""A dry-run Mac bridge (a test or dev copy) never makes the R1 say a journal entry was sent.

The real case: test bridges on other ports wrote junk blocks into the real journal. Those copies now default to a
dry-run Heptabase CLI that keeps appends in memory; this checks the other half: a runtime paired with such a bridge
sees ``dryRun`` (in /health and on every journal answer), keeps the entries queued, and never reports "sent".

The companion bridge here runs with ``cli="dry-run"`` given explicitly, so no Heptabase CLI is ever run.
"""

from __future__ import annotations

from pathlib import Path
import sys
import tempfile
import threading
import unittest

from datetime import datetime
from zoneinfo import ZoneInfo

from heptabase_bridge_fakes import FakeMacBridge
from heptabase_fakes import FakeClock, JournalHarness

from sam_runtime.domains.heptabase_journal import HeptabaseError, is_private_host
from sam_runtime.domains.heptabase_journal.client import HttpTransport

NY = ZoneInfo("America/New_York")


def _harness() -> JournalHarness:
    return JournalHarness(clock=FakeClock(datetime(2026, 10, 7, 17, 42, tzinfo=NY).timestamp()),
                          bridge_transport=HttpTransport(timeout=3.0, allow_http=is_private_host))


def _companion_dir() -> Path | None:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "companion" / "mac-bridge"
        if (candidate / "samrabbit_bridge.py").is_file():
            return candidate
    return None


@unittest.skipIf(_companion_dir() is None, "companion/mac-bridge is not in this checkout")
class DryRunCompanionBridgeTest(unittest.TestCase):
    def setUp(self) -> None:
        companion = _companion_dir()
        assert companion is not None
        sys.path.insert(0, str(companion))
        self.addCleanup(sys.path.remove, str(companion))
        import samrabbit_bridge  # noqa: PLC0415

        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.token = "companion-" + "d" * 32
        token_file = root / "bridge-token"
        token_file.write_text(self.token)
        token_file.chmod(0o600)
        self.server = samrabbit_bridge.make_server("127.0.0.1", 0, token_file=str(token_file), cli="dry-run",
                                                   cli_timeout=5.0, driver=str(root / "no-driver"),
                                                   composio=str(root / "no-composio"))
        self.assertIsInstance(self.server.cli, samrabbit_bridge.DryRunHeptabaseCli, "nothing can reach Heptabase")
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.h = _harness()
        self.addCleanup(self.h.close)

    def test_entries_stay_queued_and_the_card_says_dry_run(self) -> None:
        view = self.h.service.configure_bridge(self.url, self.token)
        self.assertEqual((False, "bridge_dry_run"), (view["bridge"]["appReachable"], view["bridge"]["lastError"]))
        result = self.h.service.record_note("What's on my calendar this afternoon?")
        self.assertEqual("queued", result["state"], "never 'sent' through a dry-run bridge")
        self.assertEqual(["pending"], [str(row["state"]) for row in self.h.rows()])
        self.h.clock.advance(3600)
        self.h.service.drain()
        self.assertNotIn("sent", [str(row["state"]) for row in self.h.rows()])
        self.assertEqual("bridge_dry_run", self.h.service.management_view()["bridge"]["lastError"])
        with self.assertRaises(HeptabaseError) as caught:
            self.h.service.read_journal("today")
        self.assertEqual("bridge_dry_run", caught.exception.code, "its memory is not the user's journal")
        self.assertIsNone(self.h.service.device_status().get("lastSentAt"))


class DryRunAnswerTest(unittest.TestCase):
    """Any bridge answer marked dryRun (even from an older bridge with a 'reachable' app) is not a write."""

    def setUp(self) -> None:
        self.h = _harness()
        self.addCleanup(self.h.close)
        self.bridge = FakeMacBridge()
        self.addCleanup(self.bridge.close)

    def test_a_dry_run_append_answer_is_not_sent(self) -> None:
        self.h.service.configure_bridge(self.bridge.url, self.bridge.token)
        self.bridge.dry_run_answers = True
        self.assertEqual("queued", self.h.service.record_note("only in the bridge's memory")["state"])
        self.assertEqual(["pending"], [str(row["state"]) for row in self.h.rows()])
        self.assertEqual("bridge_dry_run", self.h.service.management_view()["bridge"]["lastError"])


if __name__ == "__main__":
    unittest.main()
