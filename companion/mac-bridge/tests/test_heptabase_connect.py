"""heptabase-connect.py: argument checks and certificate pinning (no network)."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "heptabase-connect.py"

_spec = importlib.util.spec_from_file_location("heptabase_connect", SCRIPT)
assert _spec and _spec.loader
helper = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(helper)


class HeptabaseConnectTest(unittest.TestCase):
    def test_help_runs_without_network(self) -> None:
        done = subprocess.run([sys.executable, str(SCRIPT), "--help"], capture_output=True, text=True, timeout=30)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("--pairing-code", done.stdout)

    def test_r1_origin_must_be_https(self) -> None:
        for origin in ("http://192.168.1.186:8443", "https://192.168.1.186:8443/management", "192.168.1.186"):
            with self.assertRaises(helper.HelperError, msg=origin):
                helper.R1Client(origin, pin_file=None, insecure=True, repin=False)
        client = helper.R1Client("https://192.168.1.186", pin_file=None, insecure=True, repin=False)
        self.assertEqual("https://192.168.1.186:8443", client.origin)

    def test_redirect_must_be_loopback(self) -> None:
        client = helper.R1Client("https://192.168.1.186:8443", pin_file=None, insecure=True, repin=False)
        for redirect in ("https://127.0.0.1:53682/callback", "http://192.168.1.20:53682/callback",
                         "http://127.0.0.1/callback"):
            with self.assertRaises(helper.HelperError, msg=redirect):
                helper.listen_for_callback(redirect, "state", client, timeout=0.1)

    def test_certificate_is_pinned_on_first_use_and_a_change_is_refused(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            pin_file = Path(tmp, "pins.json")
            client = helper.R1Client("https://192.168.1.186:8443", pin_file=pin_file, insecure=False, repin=False)
            client._check_pin(b"first certificate")
            pins = json.loads(pin_file.read_text())
            self.assertEqual(["192.168.1.186:8443"], list(pins))
            self.assertEqual(0o600, pin_file.stat().st_mode & 0o777)
            client._check_pin(b"first certificate")
            with self.assertRaises(helper.HelperError):
                client._check_pin(b"another certificate")
            repinning = helper.R1Client("https://192.168.1.186:8443", pin_file=pin_file, insecure=False, repin=True)
            repinning._check_pin(b"another certificate")
            self.assertNotEqual(pins, json.loads(pin_file.read_text()))


if __name__ == "__main__":
    unittest.main()
