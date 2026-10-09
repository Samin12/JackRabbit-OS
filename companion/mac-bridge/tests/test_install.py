"""install.sh / uninstall.sh against a throwaway home (launchctl skipped)."""

from __future__ import annotations

import json
import os
from pathlib import Path
import plistlib
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(HERE))

DESKTOP = "desktop-token-" + "p" * 32
BRIDGE = "bridge-token-" + "q" * 32
# Installs skip the Swift build of the transcription helper (it has its own tests below).
NO_BUILD = {"SAMRABBIT_SKIP_TRANSCRIBE_BUILD": "1"}


def _swift_problem() -> str:
    if sys.platform != "darwin" or not shutil.which("xcrun") or not os.path.exists("/usr/bin/say"):
        return "needs macOS with Xcode or the Command Line Tools"
    found = subprocess.run(["xcrun", "--sdk", "macosx", "--find", "swiftc"], capture_output=True, timeout=30)
    return "" if found.returncode == 0 else "no Swift compiler"


SWIFT_PROBLEM = _swift_problem()


@unittest.skipUnless(sys.platform == "darwin", "the installer targets macOS")
class InstallTest(unittest.TestCase):
    def run_script(self, name: str, *args: str, env: dict | None = None) -> str:
        env = {**os.environ, "SAMRABBIT_HOME": self.home, "SAMRABBIT_SKIP_LAUNCHCTL": "1", **NO_BUILD, **(env or {})}
        done = subprocess.run([str(ROOT / name), *args], env=env, capture_output=True, text=True, timeout=300,
                              check=True)
        return done.stdout + done.stderr

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.home = self.tmp.name

    def test_install_is_idempotent_private_and_never_prints_the_token(self) -> None:
        output = self.run_script("install.sh")
        token_file = Path(self.home, ".config/samrabbit/bridge-token")
        token = token_file.read_text().strip()
        self.assertGreaterEqual(len(token), 40)
        self.assertEqual(0o600, stat.S_IMODE(token_file.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(token_file.parent.stat().st_mode))
        self.assertNotIn(token, output)
        plist_path = Path(self.home, "Library/LaunchAgents/com.samrabbit.bridge.plist")
        with plist_path.open("rb") as handle:
            plist = plistlib.load(handle)
        self.assertEqual("com.samrabbit.bridge", plist["Label"])
        self.assertTrue(plist["RunAtLoad"] and plist["KeepAlive"])
        self.assertIn("/opt/homebrew/bin", plist["EnvironmentVariables"]["PATH"].split(":"))
        script = Path(plist["ProgramArguments"][2])
        self.assertTrue(script.is_file())
        self.assertTrue(str(script).startswith(self.home), "the agent runs an installed copy, not the checkout")
        self.assertTrue((script.parent / "samrabbit_mac.py").is_file(), "the Mac-control module is installed too")
        self.assertTrue((script.parent / "samrabbit_sync.py").is_file(), "the conversation-sync module is installed too")
        desktop_token_file = Path(self.home, ".config/samrabbit/desktop-token")
        desktop_token = desktop_token_file.read_text().strip()
        self.assertGreaterEqual(len(desktop_token), 40)
        self.assertNotEqual(token, desktop_token, "the desktop app has its own token")
        self.assertEqual(0o600, stat.S_IMODE(desktop_token_file.stat().st_mode))
        self.assertNotIn(desktop_token, output)
        sync_dir = Path(self.home, "Library/Application Support/SamRabbit/sync")
        self.assertEqual(0o700, stat.S_IMODE(sync_dir.stat().st_mode))
        self.assertTrue((script.parent / "samrabbit_genui.py").is_file(), "the generative-UI module is installed too")
        for asset in ("skill.md", "bridge.js", "samrabbit.css", "ogui-theme.css", "ogui-svg-classes.css",
                      "ogui-form-styles.css", "ogui-importmap.html", "LICENSE-OpenGenerativeUI"):
            self.assertTrue((script.parent / "genui" / asset).is_file(), asset)

        self.assertTrue((script.parent / "samrabbit_app.py").is_file(), "the desktop web UI module is installed too")

        config = token_file.parent
        self.assertEqual(["--host", "0.0.0.0", "--port", "3780", "--token-file", str(token_file),
                          "--sync-dir", str(sync_dir), "--desktop-token-file", str(desktop_token_file),
                          "--cli", "auto", "--mobile-devices-file", str(config / "mobile-devices.json"),
                          "--t3-token-file", str(config / "t3-token")],
                         plist["ProgramArguments"][3:], "the installed bridge, and only it, uses the real CLI")
        for module in ("samrabbit_mobile.py", "samrabbit_t3.py", "samrabbit_transcribe.py", "samrabbit_installed.py"):
            self.assertTrue((script.parent / module).is_file(), module)
        # The install marker names the installed folder: that (in the account's own home) is what makes a copy the
        # installed one. This throwaway home is not the account's, so the copy here is a dev copy, and says so.
        import samrabbit_installed as installed

        marker = script.parent / ".samrabbit-installed"
        self.assertEqual(str(script.parent) + "\n", marker.read_text())
        self.assertEqual(0o644, stat.S_IMODE(marker.stat().st_mode))
        self.assertTrue(installed.is_installed_copy(str(script.parent), home=self.home, environ={"HOME": self.home}),
                        "the marker install.sh writes is the one the installed copy is recognised by")
        self.assertFalse(installed.is_installed_copy(str(script.parent)))
        self.assertIn("bridge copy: dev (not_the_install_folder)", output)
        self.assertIn("transcription: off (build skipped: SAMRABBIT_SKIP_TRANSCRIBE_BUILD=1)", output)
        self.assertIn("skipping the T3 pairing (test install)", output, "a test install never pairs with T3")
        self.assertFalse((config / "t3-token").exists())
        self.assertTrue(plist["StandardErrorPath"].endswith("Library/Logs/samrabbit-bridge.log"))

        (sync_dir / "conversations.db").write_text("kept")
        output = self.run_script("install.sh", "--port", "3791")
        self.assertIn("keeping the existing bridge token", output)
        self.assertEqual(token, token_file.read_text().strip())
        self.assertEqual(desktop_token, desktop_token_file.read_text().strip(), "the desktop token is kept too")
        with plist_path.open("rb") as handle:
            self.assertIn("3791", plistlib.load(handle)["ProgramArguments"])

        self.run_script("uninstall.sh")
        self.assertFalse(plist_path.exists())
        self.assertFalse(script.exists())
        self.assertFalse(marker.exists(), "the marker goes with the installed folder")
        self.assertTrue(token_file.exists(), "token kept without --purge")
        self.assertTrue(desktop_token_file.exists())
        self.run_script("uninstall.sh", "--purge")
        self.assertFalse(token_file.exists())
        self.assertFalse(desktop_token_file.exists())
        self.assertEqual("kept", (sync_dir / "conversations.db").read_text(), "synced conversations are never deleted")

    def test_install_records_the_composio_cli_for_the_agent(self) -> None:
        fake = Path(self.home, "tools", "composio")
        fake.parent.mkdir()
        fake.write_text("#!/bin/sh\nexit 0\n")
        fake.chmod(0o755)
        self.run_script("install.sh", env={"SAMRABBIT_COMPOSIO": str(fake)})
        plist_path = Path(self.home, "Library/LaunchAgents/com.samrabbit.bridge.plist")
        with plist_path.open("rb") as handle:
            plist = plistlib.load(handle)
        self.assertEqual(str(fake), plist["EnvironmentVariables"]["SAMRABBIT_COMPOSIO"],
                         "a LaunchAgent's PATH has no ~/.local/bin, so the absolute path is recorded")
        script = Path(plist["ProgramArguments"][2])
        self.assertTrue((script.parent / "samrabbit_calendar.py").is_file(), "the calendar module is installed too")
        # A path that is not runnable is not recorded (the bridge then searches the usual folders itself).
        output = self.run_script("install.sh", env={"SAMRABBIT_COMPOSIO": str(fake) + "-missing",
                                                    "PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "HOME": self.home})
        with plist_path.open("rb") as handle:
            self.assertNotIn("SAMRABBIT_COMPOSIO", plistlib.load(handle)["EnvironmentVariables"])
        self.assertIn("Composio CLI was not found", output)

    def test_install_records_the_google_account_and_orchestration_project(self) -> None:
        plist_path = Path(self.home, "Library/LaunchAgents/com.samrabbit.bridge.plist")

        def environment() -> dict:
            with plist_path.open("rb") as handle:
                return plistlib.load(handle)["EnvironmentVariables"]

        base = {key: value for key, value in os.environ.items()  # the runner's own settings must not leak in
                if key not in ("SAMRABBIT_GOOGLE_ACCOUNT", "SAMRABBIT_T3_ORCHESTRATION_PROJECT")}

        def install(*args: str, env: dict | None = None) -> str:
            merged = {**base, "SAMRABBIT_HOME": self.home, "SAMRABBIT_SKIP_LAUNCHCTL": "1", **NO_BUILD, **(env or {})}
            done = subprocess.run([str(ROOT / "install.sh"), *args], env=merged, capture_output=True, text=True,
                                  timeout=60, check=True)
            return done.stdout + done.stderr

        install()
        self.assertNotIn("SAMRABBIT_GOOGLE_ACCOUNT", environment())
        install("--google-account", "samin@example.com", "--t3-orchestration-project", "proj-123")
        self.assertEqual(("samin@example.com", "proj-123"), (environment()["SAMRABBIT_GOOGLE_ACCOUNT"],
                                                             environment()["SAMRABBIT_T3_ORCHESTRATION_PROJECT"]))
        install()
        self.assertEqual("samin@example.com", environment()["SAMRABBIT_GOOGLE_ACCOUNT"], "kept on a later run")
        self.assertEqual("proj-123", environment()["SAMRABBIT_T3_ORCHESTRATION_PROJECT"])
        output = install("--google-account", "not an email")
        self.assertIn("SAMRABBIT_GOOGLE_ACCOUNT is not valid", output)
        self.assertNotIn("SAMRABBIT_GOOGLE_ACCOUNT", environment())
        install(env={"SAMRABBIT_T3_ORCHESTRATION_PROJECT": ""})
        self.assertNotIn("SAMRABBIT_T3_ORCHESTRATION_PROJECT", environment(), "an empty value removes it")

    def test_install_pairs_with_t3_once_and_never_prints_the_token(self) -> None:
        from fake_t3 import FakeT3

        state = Path(self.home, "t3-state")
        state.mkdir()
        fake = FakeT3(str(state))
        self.addCleanup(fake.close)
        cli = Path(self.home, "t3-cli")
        cli.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{HERE / "fake_t3_cli.py"}" --state "{state}" "$@"\n')
        cli.chmod(0o755)
        env = {"SAMRABBIT_T3_CLI": str(cli), "SAMRABBIT_T3_URL": fake.url}
        output = self.run_script("install.sh", env=env)
        self.assertIn("T3 paired (expires 2026-11-07)", output)
        token_file = Path(self.home, ".config/samrabbit/t3-token")
        self.assertEqual(0o600, stat.S_IMODE(token_file.stat().st_mode))
        token = json.loads(token_file.read_text())["token"]
        self.assertNotIn(token, output)
        self.assertEqual("SamRabbit bridge", fake.exchanges[0]["client_label"])
        output = self.run_script("install.sh", env=env)
        self.assertEqual(1, len((state / "cli-calls.jsonl").read_text().splitlines()), "paired once, then kept")
        (state / "cli-mode").write_text("fail")
        token_file.unlink()
        output = self.run_script("install.sh", env=env)
        self.assertIn("warning: T3 not paired: t3_cli_failed", output, "a failed pairing never fails the install")
        self.run_script("uninstall.sh", "--purge")
        self.assertFalse(Path(self.home, ".config/samrabbit/mobile-devices.json").exists())

    def test_pair_phone_prints_a_code_from_the_running_bridge(self) -> None:
        import samrabbit_bridge as bridge
        import samrabbit_mobile as mobile

        config = Path(self.home, ".config/samrabbit")
        config.mkdir(parents=True)
        for name, value in (("desktop-token", DESKTOP), ("bridge-token", BRIDGE)):
            (config / name).write_text(value + "\n")
            (config / name).chmod(0o600)
        server = bridge.make_server("127.0.0.1", 0, token_file=str(config / "bridge-token"),
                                    desktop_token_file=str(config / "desktop-token"), driver="/nonexistent/driver",
                                    mobile_devices_file=str(config / "mobile-devices.json"),
                                    mobile_hosts=["192.168.1.99"])
        thread = threading.Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        port = str(server.server_address[1])
        done = subprocess.run([str(ROOT / "pair-phone.sh"), "--port", port],
                              env={**os.environ, "SAMRABBIT_HOME": self.home}, capture_output=True, text=True,
                              timeout=60)
        self.assertEqual(0, done.returncode, done.stderr)
        code_line = next(line for line in done.stdout.splitlines() if line.strip().startswith("Code:"))
        code = "".join(code_line.split(":", 1)[1].split("(")[0].split())
        self.assertEqual(8, len(code))
        self.assertTrue(set(code) <= set(mobile.CODE_ALPHABET))
        self.assertIn(f"Host:  192.168.1.99:{port}", done.stdout)
        self.assertIn(f"samrabbit://pair?h=192.168.1.99:{port}&c={code}&n=", done.stdout)
        self.assertTrue(server.mobile.codes.redeem(code), "the printed code pairs")
        done = subprocess.run([str(ROOT / "pair-phone.sh"), "--port", "1"],
                              env={**os.environ, "SAMRABBIT_HOME": self.home}, capture_output=True, text=True,
                              timeout=60)
        self.assertNotEqual(0, done.returncode)
        self.assertNotIn(DESKTOP, done.stdout + done.stderr)

    # ------------------------------------------------------------------ the transcription helper
    def helper_path(self) -> Path:
        return Path(self.home, "Library/Application Support/SamRabbit/bridge/samrabbit-transcribe")

    def fake_swiftc(self, *, builds: bool = True) -> Path:
        """A stand-in compiler: writes a helper that is tests/fake_transcribe.py (state in <home>/voice)."""
        state = Path(self.home, "voice")
        state.mkdir(exist_ok=True)
        compiler = Path(self.home, "fake-swiftc")
        helper = f'#!/bin/sh\\nexec "{sys.executable}" "{HERE / "fake_transcribe.py"}" --state "{state}" "$@"\\n'
        compiler.write_text("#!/bin/sh\n"
                            "[ \"$1\" = --version ] && { echo 'fake swiftc 1.0'; exit 0; }\n"
                            + ("" if builds else "echo 'error: no such module Speech' >&2; exit 1\n")
                            + "out=''\nwhile [ $# -gt 0 ]; do\n"
                            "  if [ \"$1\" = -o ]; then out=$2; shift 2; else shift; fi\ndone\n"
                            f"printf '{helper}' > \"$out\"\nchmod 755 \"$out\"\n")
        compiler.chmod(0o755)
        return compiler

    def voice_calls(self, flag: str) -> list:
        path = Path(self.home, "voice", "calls.jsonl")
        calls = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        return [call for call in calls if flag in call["args"]]

    @unittest.skipIf(bool(SWIFT_PROBLEM), SWIFT_PROBLEM)
    def test_install_builds_the_transcription_helper_once(self) -> None:
        output = self.run_script("install.sh", env={"SAMRABBIT_SKIP_TRANSCRIBE_BUILD": "0"})
        self.assertIn("built the transcription helper", output)
        helper = self.helper_path()
        self.assertEqual(0o755, stat.S_IMODE(helper.stat().st_mode))
        line = next(line for line in output.splitlines() if line.startswith("transcription: "))
        self.assertRegex(line, r"^transcription: (on \(SpeechTranscriber, en-US, a test clip took [0-9.]+ s\)|off \(.+\))$")
        self.assertTrue(line.startswith("transcription: on"), "this Mac can transcribe: " + line)
        version = subprocess.run([str(helper), "--version"], capture_output=True, text=True, timeout=30)
        self.assertEqual({"ok": True, "version": "1"}, json.loads(version.stdout))
        signature = subprocess.run(["codesign", "-dv", str(helper)], capture_output=True, text=True, timeout=30)
        self.assertIn("Identifier=com.samrabbit.transcribe", signature.stderr)
        self.assertIn("Info.plist entries=", signature.stderr, "the usage description is embedded")
        inode = helper.stat().st_ino
        output = self.run_script("install.sh", env={"SAMRABBIT_SKIP_TRANSCRIBE_BUILD": "0"})
        self.assertIn("transcription helper is up to date", output)
        self.assertEqual(inode, helper.stat().st_ino, "not rebuilt")
        Path(str(helper) + ".build").unlink()  # as if the source had changed
        output = self.run_script("install.sh", env={"SAMRABBIT_SKIP_TRANSCRIBE_BUILD": "0"})
        self.assertIn("built the transcription helper", output)
        self.assertNotEqual(inode, helper.stat().st_ino, "replaced by a new file, never rewritten in place")
        self.run_script("uninstall.sh")
        self.assertFalse(helper.exists())

    def test_a_failed_helper_build_never_fails_the_install(self) -> None:
        compiler = self.fake_swiftc(builds=False)
        output = self.run_script("install.sh", env={"SAMRABBIT_SKIP_TRANSCRIBE_BUILD": "0",
                                                    "SAMRABBIT_SWIFTC": str(compiler)})
        self.assertIn("warning: the transcription helper did not build", output)
        self.assertIn("error: no such module Speech", output, "the compiler's last lines are shown")
        self.assertIn("transcription: off (the helper did not build; it needs the macOS 26 SDK or newer)", output)
        self.assertFalse(self.helper_path().exists())
        self.assertTrue(Path(self.home, "Library/LaunchAgents/com.samrabbit.bridge.plist").exists())

    def test_install_reports_the_model_and_downloads_it_only_when_allowed(self) -> None:
        compiler = self.fake_swiftc()
        env = {"SAMRABBIT_SKIP_TRANSCRIBE_BUILD": "0", "SAMRABBIT_SWIFTC": str(compiler)}
        Path(self.home, "voice", "check").write_text("model_missing")
        output = self.run_script("install.sh", env=env)
        self.assertIn("built the transcription helper", output)
        self.assertIn("transcription: off (model_missing)", output)
        self.assertEqual([], self.voice_calls("--prepare"), "a test install downloads nothing")
        output = self.run_script("install.sh", env={**env, "SAMRABBIT_TRANSCRIBE_PREPARE": "1"})
        self.assertIn("transcription helper is up to date", output)
        self.assertIn("downloading the speech model for en-US", output)
        self.assertEqual([["--prepare"]], [call["args"][:1] for call in self.voice_calls("--prepare")])
        self.assertRegex(output, r"transcription: on \(SpeechTranscriber, en-US, a test clip took [0-9.]+ s\)")
        [clip] = self.voice_calls("--file")
        self.assertTrue(clip["file"].endswith("check.wav") and clip["size"] > 1000, "a real clip from say -o")
        self.assertFalse(Path(clip["file"]).exists(), "the test clip is deleted")
        Path(self.home, "voice", "mode").write_text("permission")
        output = self.run_script("install.sh", env=env)
        self.assertIn("transcription: off (a test clip failed: transcribe_permission)", output)

    def test_install_rejects_a_bad_port(self) -> None:
        env = {**os.environ, "SAMRABBIT_HOME": self.home, "SAMRABBIT_SKIP_LAUNCHCTL": "1"}
        done = subprocess.run([str(ROOT / "install.sh"), "--port", "80; rm -rf /"], env=env, capture_output=True,
                              text=True, timeout=30)
        self.assertNotEqual(0, done.returncode)
        self.assertFalse(Path(self.home, "Library/LaunchAgents/com.samrabbit.bridge.plist").exists())


if __name__ == "__main__":
    unittest.main()
