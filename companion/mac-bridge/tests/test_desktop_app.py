"""The desktop web UI route (/app/) of the bridge: loopback + desktop token, static files only.

Run: python3 -m unittest discover -s companion/mac-bridge/tests
"""

from __future__ import annotations

import io
import logging
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from typing import Dict, Optional, Tuple

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import samrabbit_app as app  # noqa: E402
import samrabbit_bridge as bridge  # noqa: E402

BRIDGE_TOKEN = "bridge-token-" + "b" * 32
DESKTOP_TOKEN = "desktop-token-" + "d" * 32


class DesktopAppRouteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.web = root / "web"
        (self.web / "assets").mkdir(parents=True)
        (self.web / "index.html").write_text("<!doctype html><title>SamRabbit</title>")
        (self.web / "app.js").write_text("export const ok = true;\n")
        (self.web / "styles.css").write_text("body{}\n")
        (self.web / "assets" / "orb.svg").write_text("<svg xmlns='http://www.w3.org/2000/svg'/>")
        (self.web / ".secret.js").write_text("hidden")
        (self.web / "notes.md").write_text("not served")
        (root / "outside.js").write_text("outside")
        os.symlink(root / "outside.js", self.web / "escape.js")
        self.bridge_token = root / "bridge-token"
        self.bridge_token.write_text(BRIDGE_TOKEN + "\n")
        self.bridge_token.chmod(0o600)
        self.desktop_token = root / "desktop-token"
        self.desktop_token.write_text(DESKTOP_TOKEN + "\n")
        self.desktop_token.chmod(0o600)
        self.old_site = app._SITE  # noqa: SLF001
        app._SITE = app.AppSite(str(self.web), str(self.desktop_token))  # noqa: SLF001
        self.addCleanup(self._restore_site)
        self.log = io.StringIO()
        handler = logging.StreamHandler(self.log)
        bridge._LOG.addHandler(handler)  # noqa: SLF001
        bridge._LOG.setLevel(logging.INFO)  # noqa: SLF001
        self.addCleanup(bridge._LOG.removeHandler, handler)  # noqa: SLF001
        self.server = bridge.make_server("127.0.0.1", 0, token_file=str(self.bridge_token), cli_timeout=2.0)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop)
        self.port = self.server.server_address[1]

    def _restore_site(self) -> None:
        app._SITE = self.old_site  # noqa: SLF001

    def _stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def get(self, path: str, *, header: Optional[str] = DESKTOP_TOKEN, cookie: Optional[str] = None,
            bearer: Optional[str] = None, method: str = "GET") -> Tuple[int, Dict[str, str], bytes]:
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {}
        if header is not None:
            headers["X-SamRabbit-Desktop"] = header
        if cookie is not None:
            headers["Cookie"] = cookie
        if bearer is not None:
            headers["Authorization"] = "Bearer " + bearer
        connection.request(method, path, headers=headers)
        response = connection.getresponse()
        body = response.read()
        result = (response.status, {key.lower(): value for key, value in response.getheaders()}, body)
        connection.close()
        return result

    def test_index_needs_the_desktop_token_by_header_or_cookie(self) -> None:
        status, headers, body = self.get("/app/", header=None)
        self.assertEqual(401, status)
        self.assertIn("text/html", headers["content-type"])
        status, headers, body = self.get("/app/")
        self.assertEqual(200, status)
        self.assertEqual("text/html; charset=utf-8", headers["content-type"])
        self.assertIn(b"SamRabbit", body)
        self.assertIn("frame-ancestors 'none'", headers["content-security-policy"])
        self.assertEqual("nosniff", headers["x-content-type-options"])
        status, _, _ = self.get("/app/", header=None, cookie="other=1; sr_desktop=" + DESKTOP_TOKEN)
        self.assertEqual(200, status)
        status, _, _ = self.get("/app/", header=None, cookie='sr_desktop="' + DESKTOP_TOKEN + '"')
        self.assertEqual(200, status)
        status, _, _ = self.get("/app/", header="wrong-token-" + "w" * 30)
        self.assertEqual(401, status)

    def test_the_bridge_bearer_token_is_not_a_desktop_token(self) -> None:
        status, _, _ = self.get("/app/", header=None, bearer=BRIDGE_TOKEN)
        self.assertEqual(401, status)
        status, _, _ = self.get("/health", header=DESKTOP_TOKEN)
        self.assertEqual(401, status, "the desktop token must not open the bridge's LAN routes")

    def test_content_types_and_redirect(self) -> None:
        status, headers, body = self.get("/app/app.js")
        self.assertEqual((200, "text/javascript; charset=utf-8"), (status, headers["content-type"]))
        self.assertNotIn("content-security-policy", headers)
        self.assertEqual("text/css; charset=utf-8", self.get("/app/styles.css")[1]["content-type"])
        self.assertEqual("image/svg+xml", self.get("/app/assets/orb.svg")[1]["content-type"])
        self.assertEqual("no-cache", self.get("/app/app.js")[1]["cache-control"])
        status, headers, _ = self.get("/app?c=1")
        self.assertEqual(308, status)
        self.assertEqual("/app/?c=1", headers["location"])
        status, _, body = self.get("/app/index.html?ignored=1")
        self.assertEqual(200, status)

    def test_no_traversal_dotfiles_symlink_escapes_or_unknown_types(self) -> None:
        for path in ("/app/../samrabbit_bridge.py", "/app/%2e%2e/outside.js", "/app/..%2foutside.js",
                     "/app/assets/../../outside.js", "/app/.secret.js", "/app/%2esecret.js", "/app/escape.js",
                     "/app/notes.md", "/app/missing.js", "/app/assets", "/app/app.js%00.png",
                     "/app/%ff.js", "/app/..%5coutside.js", "/app/C:/x.js"):
            status, _, body = self.get(path)
            self.assertIn(status, (404,), path)
            self.assertNotIn(b"outside", body, path)
            self.assertNotIn(b"hidden", body, path)

    def test_only_get_is_allowed(self) -> None:
        status, headers, _ = self.get("/app/", method="POST")
        self.assertEqual(405, status)
        self.assertEqual("GET", headers["allow"])

    def test_missing_token_file_or_web_dir_is_a_clear_503(self) -> None:
        self.desktop_token.unlink()
        status, _, body = self.get("/app/")
        self.assertEqual(503, status)
        self.assertIn(b"install.sh", body)
        self.desktop_token.write_text(DESKTOP_TOKEN + "\n")
        self.desktop_token.chmod(0o644)
        self.assertEqual(200, self.get("/app/")[0])
        self.assertEqual(0o600, self.desktop_token.stat().st_mode & 0o777, "permissions tightened")
        app._SITE = app.AppSite(str(Path(self.tmp.name) / "nowhere"), str(self.desktop_token))  # noqa: SLF001
        self.assertEqual(503, self.get("/app/")[0])

    def test_lan_peers_are_refused(self) -> None:
        for address in ("192.168.1.20", "10.0.0.4", "fe80::1", "8.8.8.8"):
            self.assertFalse(app.is_loopback(address), address)
        for address in ("127.0.0.1", "127.8.0.1", "::1", "::ffff:127.0.0.1"):
            self.assertTrue(app.is_loopback(address), address)

        class FakeHandler:
            client_address = ("192.168.1.20", 5555)
            path = "/app/"
            headers = {"X-SamRabbit-Desktop": DESKTOP_TOKEN}

            def __init__(self) -> None:
                self.status = None
                self.wfile = io.BytesIO()

            def send_response(self, status: int) -> None:
                self.status = status

            def send_header(self, name: str, value: str) -> None:
                pass

            def end_headers(self) -> None:
                pass

        fake = FakeHandler()
        self.assertEqual(403, app.site().serve(fake, "GET"))
        self.assertNotIn(b"SamRabbit</title>", fake.wfile.getvalue().split(b"<body>")[-1])

    def test_logs_never_contain_the_token_or_file_paths(self) -> None:
        self.get("/app/assets/orb.svg")
        self.get("/app/", header="nope-" + "n" * 20)
        log = self.log.getvalue()
        self.assertIn("GET /app 200", log)
        self.assertIn("GET /app 401", log)
        self.assertNotIn(DESKTOP_TOKEN, log)
        self.assertNotIn("orb.svg", log)

    def test_resolve_file_rejects_paths_outside_the_folder(self) -> None:
        self.assertEqual(os.path.realpath(self.web / "index.html"), app.resolve_file(str(self.web), "/app/"))
        with self.assertRaises(app.NotFound):
            app.resolve_file(str(self.web), "/app/escape.js")
        with self.assertRaises(app.NotFound):
            app.resolve_file(str(self.web), "/app/%2e%2e/%2e%2e/etc/passwd")


if __name__ == "__main__":
    unittest.main()
