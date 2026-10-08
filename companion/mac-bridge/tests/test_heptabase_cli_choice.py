"""Which Heptabase CLI a bridge uses: only the installed LaunchAgent copy may reach the real journal.

A bridge run from a checkout (tests, dev bridges on other ports) defaults to a dry-run CLI, even when a
``heptabase`` executable is first on PATH, so a stray test can never write to the user's Heptabase journal.
A dry-run bridge says so (``/health`` ``dryRun``, ``cli.mode`` ``dryRun``, ``app.reachable`` false, and
``dryRun: true`` on every journal answer), so a runtime paired with it never reports an entry as sent.

These tests can never reach the real ``heptabase`` CLI: PATH holds only the recording fake and system folders
without a ``heptabase`` (checked in setUp), the ``/opt/homebrew/bin/heptabase`` fallback is pointed at a missing
file, and every test that may run a CLI first asserts that the resolved executable is the fake.

Run: python3 -m unittest discover -s companion/mac-bridge/tests
"""

from __future__ import annotations

import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))

import samrabbit_bridge as bridge  # noqa: E402

TOKEN = "test-token-" + "c" * 32


class CliChoiceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        # A "real" heptabase CLI first on PATH that records every call: a dry-run bridge must never run it.
        self.calls = root / "calls.jsonl"
        bin_dir = root / "bin"
        bin_dir.mkdir()
        self.path_cli = bin_dir / "heptabase"
        self.path_cli.write_text(
            "#!/bin/sh\n"
            f'echo "$*" >> "{self.calls}"\n'
            'if [ "$1" = "--version" ]; then echo 0.7.0; exit 0; fi\n'
            'if [ "$2" = "append" ]; then echo \'{"date":"2026-10-08","title":"Oct 8","contentMd5":"x"}\'; exit 0; fi\n'
            'echo \'{"date":"2026-10-08","title":"Oct 8","content":"{\\"type\\":\\"doc\\",\\"content\\":[]}"}\'\n')
        self.path_cli.chmod(0o755)
        # Hermetic PATH: the fake, then system folders that have no heptabase (never /opt/homebrew/bin).
        self.old_path = os.environ.get("PATH", "")
        os.environ["PATH"] = os.pathsep.join([str(bin_dir), "/usr/bin", "/bin", "/usr/sbin", "/sbin"])
        self.addCleanup(self._restore_path)
        # ... and the FALLBACK_CLI (/opt/homebrew/bin/heptabase) is a file that does not exist.
        self.old_fallback = bridge.FALLBACK_CLI
        bridge.FALLBACK_CLI = str(root / "no-such-heptabase")
        self.addCleanup(setattr, bridge, "FALLBACK_CLI", self.old_fallback)
        self.assertEqual(str(self.path_cli), shutil.which("heptabase"), "only the fake heptabase is on PATH")
        self.token_file = root / "bridge-token"
        self.token_file.write_text(TOKEN + "\n")
        self.token_file.chmod(0o600)

    def _restore_path(self) -> None:
        os.environ["PATH"] = self.old_path

    def start(self, **kwargs: object) -> str:
        server = bridge.make_server("127.0.0.1", 0, token_file=str(self.token_file), cli_timeout=5.0, **kwargs)
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()

        def stop() -> None:
            server.shutdown()
            server.server_close()

        self.addCleanup(stop)
        self.server = server
        return f"http://127.0.0.1:{server.server_address[1]}"

    def call(self, base: str, method: str, path: str, body: object = None) -> dict:
        data = json.dumps(body).encode() if body is not None else None
        request = Request(base + path, data=data, method=method,
                          headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"})
        with urlopen(request, timeout=10) as response:
            return json.loads(response.read().decode())

    def real_calls(self) -> list:
        return self.calls.read_text().splitlines() if self.calls.exists() else []

    def assert_runs_only_the_fake(self, cli: object) -> None:
        """Before anything runs a CLI: the one this bridge would run is the recording fake in the temp folder."""
        executable = cli.executable()  # type: ignore[attr-defined]
        self.assertEqual(str(self.path_cli), executable)
        self.assertTrue(Path(executable).resolve().is_relative_to(Path(self.tmp.name).resolve()))
        self.assertNotEqual(os.path.realpath(self.old_fallback), os.path.realpath(executable))

    def test_a_bridge_from_a_checkout_is_dry_run_even_with_heptabase_on_path(self) -> None:
        self.assertEqual(bridge.CLI_DRY_RUN, bridge.default_cli_choice(str(ROOT)))
        base = self.start()  # no cli: what a test or a dev bridge on another port gets
        self.assertIsInstance(self.server.cli, bridge.DryRunHeptabaseCli)
        health = self.call(base, "GET", "/health")
        self.assertEqual({"available": True, "version": "dry-run", "mode": "dryRun"}, health["cli"])
        self.assertIs(True, health["dryRun"])
        self.assertEqual({"reachable": False, "detail": "bridge_dry_run"}, health["app"],
                         "a dry run never reaches Heptabase, so the app is not reported reachable")
        written = self.call(base, "POST", "/v1/heptabase/journal/append",
                            {"date": "2026-10-08", "content": "What's on my calendar this afternoon?"})
        self.assertEqual("2026-10-08", written["date"])
        self.assertIs(True, written["dryRun"], "the answer says nothing reached Heptabase")
        read = self.call(base, "GET", "/v1/heptabase/journal/read?date=2026-10-08")
        self.assertEqual("What's on my calendar this afternoon?", read["text"])
        self.assertIs(True, read["dryRun"])
        self.assertEqual([], self.real_calls(), "the heptabase CLI on PATH was never run")

    def test_environment_and_flag_values(self) -> None:
        for choice in ("dry-run", " dry-run ", None, ""):
            with self.subTest(choice=choice):
                self.assertIsInstance(bridge.cli_for(choice, here=str(ROOT)), bridge.DryRunHeptabaseCli)
        auto = bridge.cli_for("auto", here=str(ROOT))
        self.assertIsInstance(auto, bridge.HeptabaseCli)
        self.assertEqual(str(self.path_cli), auto.executable(), "auto finds the CLI on PATH")
        explicit = bridge.cli_for(str(self.path_cli), here=str(ROOT))
        self.assertEqual(str(self.path_cli), explicit.executable(), "an explicit path is used as given")
        self.assertEqual("real", explicit.mode)

    def test_the_real_cli_is_used_only_when_asked_for(self) -> None:
        # "auto" resolves the CLI on PATH: the hermetic PATH and the missing fallback (setUp) leave only the fake,
        # and that is checked before the health check (which runs --version) and before the append.
        base = self.start(cli="auto")
        self.assertIsInstance(self.server.cli, bridge.HeptabaseCli)
        self.assert_runs_only_the_fake(self.server.cli)
        health = self.call(base, "GET", "/health")
        self.assertEqual(("real", False), (health["cli"]["mode"], health["dryRun"]))
        self.assert_runs_only_the_fake(self.server.cli)
        written = self.call(base, "POST", "/v1/heptabase/journal/append", {"date": "2026-10-08", "content": "Explicit."})
        self.assertNotIn("dryRun", written)
        self.assertTrue(any(line.startswith("journal append 2026-10-08") for line in self.real_calls()))

    def test_an_explicit_path_is_the_cli_that_runs(self) -> None:
        base = self.start(cli=str(self.path_cli))
        self.assert_runs_only_the_fake(self.server.cli)
        self.assertEqual("real", self.call(base, "GET", "/health")["cli"]["mode"])
        self.call(base, "POST", "/v1/heptabase/journal/append", {"date": "2026-10-08", "content": "By path."})
        self.assertTrue(any(line.startswith("journal append 2026-10-08") for line in self.real_calls()))

    def test_the_installed_copy_defaults_to_the_real_cli(self) -> None:
        home = Path(self.tmp.name, "home")
        installed = home / "Library/Application Support/SamRabbit/bridge"
        installed.mkdir(parents=True)
        old_home = os.environ.get("HOME")
        os.environ["HOME"] = str(home)
        try:
            self.assertEqual(bridge.CLI_AUTO, bridge.default_cli_choice(str(installed)))
            self.assertIsInstance(bridge.cli_for(None, here=str(installed)), bridge.HeptabaseCli)
            self.assertEqual(bridge.CLI_DRY_RUN, bridge.default_cli_choice(str(ROOT)))
            self.assertIsInstance(bridge.cli_for("dry-run", here=str(installed)), bridge.DryRunHeptabaseCli,
                                  "an explicit dry-run wins even for the installed copy")
        finally:
            if old_home is None:
                os.environ.pop("HOME", None)
            else:
                os.environ["HOME"] = old_home

    @unittest.skipUnless(sys.platform == "darwin", "the installer targets macOS")
    def test_install_sh_keeps_the_launch_agent_on_the_real_cli(self) -> None:
        home = Path(self.tmp.name, "install-home")
        home.mkdir()
        env = {**os.environ, "SAMRABBIT_HOME": str(home), "SAMRABBIT_SKIP_LAUNCHCTL": "1"}
        subprocess.run([str(ROOT / "install.sh")], env=env, capture_output=True, text=True, timeout=60, check=True)
        with (home / "Library/LaunchAgents/com.samrabbit.bridge.plist").open("rb") as handle:
            arguments = plistlib.load(handle)["ProgramArguments"]
        self.assertEqual("auto", arguments[arguments.index("--cli") + 1])
        self.assertTrue(arguments[2].startswith(str(home / "Library/Application Support/SamRabbit/bridge")))

    def test_main_reads_the_choice_from_the_environment(self) -> None:
        # --cli defaults to SAMRABBIT_HEPTABASE_CLI; parse only (no server): the parser lives in main().
        old = os.environ.get("SAMRABBIT_HEPTABASE_CLI")
        os.environ["SAMRABBIT_HEPTABASE_CLI"] = "dry-run"
        try:
            captured = {}

            def fake_make_server(*_args: object, **kwargs: object) -> object:
                captured.update(kwargs)
                raise ValueError("stop here")

            original = bridge.make_server
            bridge.make_server = fake_make_server  # type: ignore[assignment]
            try:
                self.assertEqual(2, bridge.main(["--port", "0", "--token-file", str(self.token_file)]))
            finally:
                bridge.make_server = original  # type: ignore[assignment]
            self.assertEqual("dry-run", captured["cli"])
        finally:
            if old is None:
                os.environ.pop("SAMRABBIT_HEPTABASE_CLI", None)
            else:
                os.environ["SAMRABBIT_HEPTABASE_CLI"] = old


if __name__ == "__main__":
    unittest.main()
