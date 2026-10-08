"""install.sh / uninstall.sh against a throwaway home (launchctl skipped)."""

from __future__ import annotations

import os
from pathlib import Path
import plistlib
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent


@unittest.skipUnless(sys.platform == "darwin", "the installer targets macOS")
class InstallTest(unittest.TestCase):
    def run_script(self, name: str, *args: str) -> str:
        env = {**os.environ, "SAMRABBIT_HOME": self.home, "SAMRABBIT_SKIP_LAUNCHCTL": "1"}
        done = subprocess.run([str(ROOT / name), *args], env=env, capture_output=True, text=True, timeout=60,
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

        self.assertEqual(["--host", "0.0.0.0", "--port", "3780", "--token-file", str(token_file),
                          "--sync-dir", str(sync_dir), "--desktop-token-file", str(desktop_token_file),
                          "--cli", "auto"],
                         plist["ProgramArguments"][3:], "the installed bridge, and only it, uses the real CLI")
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
        self.assertTrue(token_file.exists(), "token kept without --purge")
        self.assertTrue(desktop_token_file.exists())
        self.run_script("uninstall.sh", "--purge")
        self.assertFalse(token_file.exists())
        self.assertFalse(desktop_token_file.exists())
        self.assertEqual("kept", (sync_dir / "conversations.db").read_text(), "synced conversations are never deleted")

    def test_install_rejects_a_bad_port(self) -> None:
        env = {**os.environ, "SAMRABBIT_HOME": self.home, "SAMRABBIT_SKIP_LAUNCHCTL": "1"}
        done = subprocess.run([str(ROOT / "install.sh"), "--port", "80; rm -rf /"], env=env, capture_output=True,
                              text=True, timeout=30)
        self.assertNotEqual(0, done.returncode)
        self.assertFalse(Path(self.home, "Library/LaunchAgents/com.samrabbit.bridge.plist").exists())


if __name__ == "__main__":
    unittest.main()
