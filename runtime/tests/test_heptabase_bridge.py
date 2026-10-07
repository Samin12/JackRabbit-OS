"""Mac-bridge transport for the Heptabase journal, against a fake bridge (and once against
the real companion bridge driving a fake ``heptabase`` CLI)."""

from __future__ import annotations

from datetime import datetime
import io
import json
import logging
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo

from heptabase_bridge_fakes import FakeMacBridge
from heptabase_fakes import FakeClock, JournalHarness

from sam_runtime.agents import AgentKind
from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.api.heptabase_routes import HeptabaseRoutes
from sam_runtime.api.http_server import RuntimeHttpServer
from sam_runtime.domains.heptabase_journal import (AuthorizationError, BRIDGE_CONNECTION_ID, HeptabaseJournalService,
                                                   is_private_host, normalize_bridge_url, register_journal_tools)
from sam_runtime.domains.heptabase_journal.bridge import valid_bridge_token
from sam_runtime.domains.heptabase_journal.client import HttpTransport
from sam_runtime.security.credentials import ConnectionCredentialEnvelopes
from sam_runtime.security.pairing import PairingAuthority
from sam_runtime.storage.lifecycle_repository import LifecycleRepository
from sam_runtime.tools import ToolCatalog

NY = ZoneInfo("America/New_York")
LOCAL_TOKEN = "t" * 43
ORIGIN = "https://192.168.1.186:8443"


def _ny(*parts: int) -> float:
    return datetime(*parts, tzinfo=NY).timestamp()


def _harness() -> JournalHarness:
    return JournalHarness(clock=FakeClock(_ny(2026, 10, 7, 17, 42)),
                          bridge_transport=HttpTransport(timeout=3.0, allow_http=is_private_host))


class BridgeUrlTest(unittest.TestCase):
    def test_only_private_lan_or_loopback_http_urls_are_accepted(self) -> None:
        accepted = {
            "http://192.168.1.183:3780": "http://192.168.1.183:3780",
            " http://192.168.1.183:3780/ ": "http://192.168.1.183:3780",
            "192.168.1.183": "http://192.168.1.183:3780",
            "192.168.1.183:4000": "http://192.168.1.183:4000",
            "https://10.0.0.5:3780": "https://10.0.0.5:3780",
            "http://172.31.255.1:3780": "http://172.31.255.1:3780",
            "http://127.0.0.1:3780": "http://127.0.0.1:3780",
            "http://localhost:3780": "http://localhost:3780",
            "http://[fd00::12]:3780": "http://[fd00::12]:3780",
        }
        for value, expected in accepted.items():
            with self.subTest(value=value):
                self.assertEqual(expected, normalize_bridge_url(value))
        rejected = ("http://8.8.8.8:3780", "http://172.32.0.1:3780", "http://100.64.1.1:3780",
                    "http://mac.example.com:3780", "http://studio.local:3780", "ftp://192.168.1.183",
                    "http://user:pw@192.168.1.183:3780", "http://192.168.1.183:3780/path",
                    "http://192.168.1.183:3780?x=1", "http://192.168.1.183:99999", "", "   ", None, 42,
                    "http://[2001:db8::1]:3780", "http://0.0.0.0:3780", "http://" + "1" * 400)
        for value in rejected:
            with self.subTest(value=value):
                with self.assertRaises(AuthorizationError):
                    normalize_bridge_url(value)

    def test_token_shape(self) -> None:
        self.assertEqual("a" * 43, valid_bridge_token("  " + "a" * 43 + "\n"))
        for value in ("", "short", "has space in the middle of it", "x" * 600, None, 5, "tab\tinside-token-xxxxxx"):
            with self.subTest(value=value):
                with self.assertRaises(AuthorizationError):
                    valid_bridge_token(value)


class BridgeTransportTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = _harness()
        self.addCleanup(self.h.close)
        self.bridge = FakeMacBridge()
        self.addCleanup(self.bridge.close)
        self.log = io.StringIO()
        handler = logging.StreamHandler(self.log)
        logger = logging.getLogger("sam-runtime")
        logger.addHandler(handler)
        self.addCleanup(logger.removeHandler, handler)

    def connect(self) -> dict[str, object]:
        return self.h.service.configure_bridge(self.bridge.url, self.bridge.token)

    def states(self) -> list[str]:
        return [str(row["state"]) for row in self.h.rows()]

    # ------------------------------------------------------------------ configuration

    def test_configure_validates_through_health_and_seals_the_token(self) -> None:
        service = self.h.service
        with self.assertRaises(AuthorizationError) as caught:
            service.configure_bridge(self.bridge.url, "wrong-token-" + "w" * 20)
        self.assertEqual("bridge_unauthorized", caught.exception.code)
        with self.assertRaises(AuthorizationError) as caught:
            service.configure_bridge("http://127.0.0.1:9", self.bridge.token)
        self.assertEqual(("bridge_unreachable", 502), (caught.exception.code, caught.exception.status))
        self.bridge.service = "something-else"
        with self.assertRaises(AuthorizationError) as caught:
            service.configure_bridge(self.bridge.url, self.bridge.token)
        self.assertEqual("not_a_bridge", caught.exception.code)
        self.assertFalse(service.connected(), "failed attempts leave nothing configured")
        self.bridge.service = "samrabbit-bridge"

        view = self.connect()
        self.assertEqual((True, "mac", "connected", False), (view["connected"], view["mode"], view["state"],
                                                             view["needsReconnect"]))
        self.assertEqual({"configured": True, "url": self.bridge.url, "reachable": True, "appReachable": True,
                          "cliVersion": "0.7.0", "lastError": None},
                         {key: view["bridge"][key] for key in ("configured", "url", "reachable", "appReachable",
                                                               "cliVersion", "lastError")})
        self.assertEqual({"connected": False, "state": "disconnected"}, view["oauth"])
        self.assertIn(f"connection:{BRIDGE_CONNECTION_ID}:credential", self.h.bridge.sealed, "sealed by the Keystore")
        self.assertIsNotNone(self.h.credentials.get_envelope(BRIDGE_CONNECTION_ID))
        status = service.device_status()
        self.assertEqual((True, "mac", False), (status["connected"], status["mode"], status["needsReconnect"]))
        self.assertNotIn(self.bridge.token, json.dumps(view) + json.dumps(status))

    def test_token_is_never_stored_in_plaintext_or_logged(self) -> None:
        self.connect()
        self.h.service.record_note("a quiet note")
        self.bridge.failures.append("unauthorized")
        self.h.service.record_note("another note")
        with self.h.database.connect() as connection:
            dump = "\n".join(connection.iterdump())
        self.assertIn(self.bridge.url, dump, "the URL is stored non-secretly")
        self.assertNotIn(self.bridge.token, dump)
        self.assertNotIn(self.bridge.token, self.log.getvalue())
        self.assertNotIn("quiet note", self.log.getvalue())

    def test_a_rewritten_url_never_receives_the_token(self) -> None:
        self.connect()
        other = FakeMacBridge(token=self.bridge.token)
        self.addCleanup(other.close)
        with self.h.database.connect() as connection:
            connection.execute("UPDATE provider_settings SET setting_value = ? WHERE setting_key = ?",
                               (other.url, "heptabase.bridge.url"))
            connection.commit()
        service = HeptabaseJournalService(  # a fresh process reading the tampered row
            database=self.h.database, credentials=self.h.credentials,
            envelopes=ConnectionCredentialEnvelopes(self.h.bridge), clock=self.h.clock,
            bridge_transport=HttpTransport(timeout=3.0, allow_http=is_private_host), deliver_timeout=1.0,
        )
        self.assertEqual("queued", service.record_note("must stay on the R1")["state"])
        self.assertEqual([], [header for header in other.seen_authorization if header])
        self.assertEqual("bridge_token_unavailable", service.management_view()["bridge"]["lastError"])

    # ------------------------------------------------------------------ delivery

    def test_notes_and_reads_go_through_the_bridge(self) -> None:
        self.connect()
        result = self.h.service.record_note("I think the orb should pulse slower when it's listening.")
        self.assertEqual(("sent", "2026-10-07"), (result["state"], result["date"]))
        self.assertEqual([("2026-10-07", "**17:42** I think the orb should pulse slower when it's listening.")],
                         self.bridge.appends())
        self.assertEqual([], self.h.fake.appends(), "nothing went to the OAuth/MCP endpoint")
        read = self.h.service.read_journal("today")
        self.assertEqual({"date": "2026-10-07", "text": "17:42 I think the orb should pulse slower when it's listening.",
                          "truncated": False, "empty": False}, read)
        self.assertIsNotNone(self.h.service.management_view()["bridge"]["lastOkAt"])

    def test_app_not_running_queues_then_retries_with_backoff(self) -> None:
        self.connect()
        self.bridge.failures.append("app_down")
        result = self.h.service.record_note("written while Heptabase was closed")
        self.assertEqual("queued", result["state"])
        self.assertEqual(["pending"], self.states())
        view = self.h.service.management_view()
        self.assertEqual((False, "heptabase_app_unavailable"),
                         (view["bridge"]["appReachable"], view["bridge"]["lastError"]))
        self.assertEqual(0, self.h.service.drain(), "backing off")
        self.h.clock.advance(31)
        self.h.service.drain()
        self.assertEqual(["sent"], self.states())
        self.assertEqual(1, len(self.bridge.appends()))
        self.assertTrue(self.h.service.management_view()["bridge"]["appReachable"])

    def test_new_activity_retries_a_day_stalled_by_the_mac(self) -> None:
        self.connect()
        self.bridge.failures.extend(["app_down"])
        self.h.service.record_note("first")
        self.h.clock.advance(5)  # well inside the 30 s backoff
        second = self.h.service.record_note("second")
        self.assertEqual("sent", second["state"], "the user's activity retried the stalled head at once")
        self.assertEqual([("2026-10-07", "**17:42** first\n\n**17:42** second")], self.bridge.appends(),
                         "in order, one batch")

    def test_uncertain_writes_are_read_back_before_any_resend(self) -> None:
        self.connect()
        for mode in ("timeout_after", "reset_after"):
            with self.subTest(mode=mode):
                before = len(self.bridge.appends())
                self.bridge.failures.append(mode)
                self.h.service.record_note(f"landed despite {mode}")
                self.assertEqual("uncertain", self.h.rows()[-1]["state"])
                self.h.clock.advance(31)
                self.h.service.drain()
                self.assertEqual("sent", self.h.rows()[-1]["state"])
                self.assertEqual(before + 1, len(self.bridge.appends()), "found by fingerprint: no duplicate")
                self.assertIn(("GET", "/v1/heptabase/journal/read"), self.bridge.calls)

    def test_unknown_outcome_without_a_write_is_resent_once(self) -> None:
        self.connect()
        self.bridge.failures.append("error_unknown")
        self.h.service.record_note("did it land?")
        self.assertEqual(["uncertain"], self.states())
        self.h.clock.advance(31)
        self.h.service.drain()
        self.assertEqual(["sent"], self.states())
        self.assertEqual([("2026-10-07", "**17:42** did it land?")], self.bridge.appends())

    def test_rejected_content_falls_back_to_plain_paragraphs(self) -> None:
        self.connect()
        self.bridge.failures.append("reject_rich")
        self.h.service.record_note("formatting refused")
        self.h.service.drain()
        self.assertEqual(["sent"], self.states())
        self.assertEqual([("2026-10-07", "17:42 formatting refused")], self.bridge.appends())

    def test_rotated_bridge_token_pauses_until_reconnected(self) -> None:
        self.connect()
        old = self.bridge.token
        self.bridge.token = "rotated-" + "r" * 30
        self.assertEqual("queued", self.h.service.record_note("kept safe")["state"])
        self.assertEqual("bridge_unauthorized", self.h.service.management_view()["bridge"]["lastError"])
        self.assertEqual(["pending"], self.states())
        with self.assertRaises(AuthorizationError):
            self.h.service.configure_bridge(self.bridge.url, old)
        self.h.service.configure_bridge(self.bridge.url, self.bridge.token)
        self.h.service.drain()
        self.assertEqual(["sent"], self.states())

    def test_check_reports_the_app_and_expedites_waiting_entries(self) -> None:
        self.connect()
        self.bridge.failures.append("app_down")
        self.h.service.record_note("waiting for the app")
        self.bridge.app_reachable = False
        view = self.h.service.check_bridge()
        self.assertEqual((True, False), (view["bridge"]["reachable"], view["bridge"]["appReachable"]))
        self.assertEqual(["pending"], self.states())
        self.bridge.app_reachable = True
        self.h.service.check_bridge()
        self.h.service.drain()
        self.assertEqual(["sent"], self.states(), "sent without waiting out the backoff")
        self.bridge.close()
        view = self.h.service.check_bridge()
        self.assertEqual((False, "bridge_unreachable"), (view["bridge"]["reachable"], view["bridge"]["lastError"]))

    def test_bridge_is_preferred_and_oauth_remains_the_fallback(self) -> None:
        self.h.connect()  # OAuth grant against the fake Heptabase MCP server
        self.connect()
        self.assertEqual("mac", self.h.service.mode())
        self.h.service.record_note("via the Mac")
        self.assertEqual([("2026-10-07", "**17:42** via the Mac")], self.bridge.appends())
        self.assertEqual([], self.h.fake.appends())
        view = self.h.service.disconnect_bridge()
        self.assertEqual(("oauth", True, False), (view["mode"], view["connected"], view["bridge"]["configured"]))
        self.assertIsNone(self.h.credentials.get_envelope(BRIDGE_CONNECTION_ID))
        self.h.clock.advance(60)
        self.h.service.record_note("via OAuth")
        self.assertEqual([("2026-10-07", "**17:43** via OAuth")], self.h.fake.appends())
        self.h.service.disconnect()
        self.assertEqual((None, False), (self.h.service.mode(), self.h.service.connected()))

    def test_oauth_reconnect_problems_do_not_pause_the_bridge(self) -> None:
        self.h.connect()
        self.connect()
        self.h.service._settings.set_reconnect_required("grant gone")  # noqa: SLF001
        view = self.h.service.management_view()
        self.assertEqual(("mac", False, False), (view["mode"], view["needsReconnect"], view["queue"]["paused"]))
        self.assertEqual("sent", self.h.service.record_note("still flowing")["state"])

    def test_voice_tools_become_available_with_the_bridge(self) -> None:
        catalog = ToolCatalog()
        register_journal_tools(catalog, self.h.service)
        names = lambda: {item["name"] for item in catalog.mcp_definitions(AgentKind.VOICE)}  # noqa: E731
        self.assertFalse({"journal_add", "journal_read"} & names())
        self.connect()
        self.assertEqual({"journal_add", "journal_read", "journal_pause"}, names())
        self.h.service.disconnect_bridge()
        self.assertFalse({"journal_add", "journal_read"} & names())

    def test_test_entry_is_one_factual_activity_line_per_day(self) -> None:
        self.connect()
        first = self.h.service.write_test_entry()
        self.assertEqual({"recorded": True, "state": "sent", "date": "2026-10-07", "mode": "mac",
                          "text": "R1 journal connected through the Mac", "duplicate": False},
                         {key: first[key] for key in ("recorded", "state", "date", "mode", "text", "duplicate")})
        again = self.h.service.write_test_entry()
        self.assertEqual((True, first["entryId"]), (again["duplicate"], again["entryId"]))
        self.assertEqual([("2026-10-07", '- 17:42 <hepta-color type="text" color="gray">'
                                         "↳ R1 journal connected through the Mac</hepta-color>")],
                         self.bridge.appends())
        self.assertIn("17:42 ↳ R1 journal connected through the Mac", self.h.service.read_journal()["text"])


class BridgeRoutesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = _harness()
        self.addCleanup(self.h.close)
        self.bridge = FakeMacBridge()
        self.addCleanup(self.bridge.close)
        self.pairing = PairingAuthority()
        self.server = RuntimeHttpServer(
            host="127.0.0.1", port=0, token=LOCAL_TOKEN, health=lambda: {"status": "ready"},
            lifecycle=LifecycleRepository(self.h.database), events=RuntimeEventStream(), pairing=self.pairing,
            sessions=self.h.sessions, heptabase=HeptabaseRoutes(self.h.service),
        )
        self.server.start()
        self.addCleanup(self.server.stop)
        self.base = f"http://127.0.0.1:{self.server.port}"
        self.cookie = ""
        self.csrf = ""

    def call(self, method: str, path: str, body: dict[str, object] | None = None, *, browser: bool = False,
             csrf: bool = True) -> tuple[int, bytes]:
        headers = {"Content-Type": "application/json", "Authorization": "Bearer " + LOCAL_TOKEN}
        if browser:
            headers.update({"X-SAM-Forwarded-Origin": ORIGIN, "Origin": ORIGIN, "Cookie": self.cookie})
            if csrf and self.csrf:
                headers["X-CSRF-Token"] = self.csrf
        data = json.dumps(body).encode() if body is not None else (b"{}" if method == "POST" else None)
        request = Request(self.base + path, data=data, method=method, headers=headers)
        try:
            with urlopen(request, timeout=15) as response:
                return response.status, response.read()
        except HTTPError as error:
            return error.code, error.read()

    def json_call(self, *args, **kwargs) -> tuple[int, dict[str, object]]:
        status, raw = self.call(*args, **kwargs)
        return status, json.loads(raw or b"{}")

    def pair(self) -> None:
        code = self.pairing.current_code().value
        request = Request(self.base + "/v1/management/pair", data=json.dumps({"code": code}).encode(), method="POST",
                          headers={"Content-Type": "application/json", "Authorization": "Bearer " + LOCAL_TOKEN,
                                   "X-SAM-Forwarded-Origin": ORIGIN, "Origin": ORIGIN})
        with urlopen(request, timeout=10) as response:
            self.cookie = response.headers["Set-Cookie"].split(";", 1)[0]
            self.csrf = json.loads(response.read())["csrfToken"]

    def test_bridge_routes_require_a_paired_session_and_csrf(self) -> None:
        body = {"bridgeUrl": self.bridge.url, "token": self.bridge.token}
        for path in ("/v1/management/heptabase/bridge", "/v1/management/heptabase/bridge/check",
                     "/v1/management/heptabase/bridge/disconnect", "/v1/management/heptabase/test-entry"):
            with self.subTest(path=path):
                self.assertEqual(403, self.call("POST", path, body, browser=True)[0])
        self.pair()
        self.assertEqual(403, self.call("POST", "/v1/management/heptabase/bridge", body, browser=True, csrf=False)[0])
        self.assertFalse(self.h.service.connected())

    def test_connect_status_test_entry_and_disconnect_over_http(self) -> None:
        self.pair()
        status, value = self.json_call("POST", "/v1/management/heptabase/test-entry", browser=True)
        self.assertEqual((409, "heptabase_not_connected"), (status, value["error"]["code"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/bridge",
                                       {"bridgeUrl": "http://8.8.8.8:3780", "token": self.bridge.token}, browser=True)
        self.assertEqual((400, "bridge_url_not_local"), (status, value["error"]["code"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/bridge",
                                       {"bridgeUrl": self.bridge.url, "token": "nope-" + "n" * 20}, browser=True)
        self.assertEqual((400, "bridge_unauthorized"), (status, value["error"]["code"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/bridge",
                                       {"bridgeUrl": self.bridge.url, "token": self.bridge.token, "extra": 1},
                                       browser=True)
        self.assertEqual((400, "invalid_request"), (status, value["error"]["code"]))

        status, raw = self.call("POST", "/v1/management/heptabase/bridge",
                                {"bridgeUrl": self.bridge.url, "token": self.bridge.token}, browser=True)
        self.assertEqual(200, status)
        self.assertNotIn(self.bridge.token.encode(), raw)
        view = json.loads(raw)
        self.assertEqual(("mac", True), (view["mode"], view["connected"]))

        status, raw = self.call("GET", "/v1/management/heptabase", browser=True)
        self.assertEqual(200, status)
        self.assertNotIn(self.bridge.token.encode(), raw)
        self.assertEqual("mac", json.loads(raw)["mode"])
        status, value = self.json_call("GET", "/v1/journal/status")
        self.assertEqual((200, True, "mac"), (status, value["connected"], value["mode"]))

        status, value = self.json_call("POST", "/v1/journal/notes", {"text": "typed on the R1"})
        self.assertEqual((200, "sent"), (status, value["state"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/test-entry", browser=True)
        self.assertEqual((200, "sent", "R1 journal connected through the Mac"),
                         (status, value["state"], value["text"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/test-entry", browser=True)
        self.assertEqual((200, True), (status, value["duplicate"]))
        self.assertEqual(2, len(self.bridge.appends()), "one note plus exactly one test line")

        status, value = self.json_call("POST", "/v1/management/heptabase/bridge/check", browser=True)
        self.assertEqual((200, True), (status, value["bridge"]["appReachable"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/bridge/disconnect", browser=True)
        self.assertEqual((200, False, None), (status, value["connected"], value["mode"]))
        status, value = self.json_call("POST", "/v1/management/heptabase/bridge/check", browser=True)
        self.assertEqual((409, "bridge_not_configured"), (status, value["error"]["code"]))


def _companion_dir() -> Path | None:
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "companion" / "mac-bridge"
        if (candidate / "samrabbit_bridge.py").is_file():
            return candidate
    return None


@unittest.skipIf(_companion_dir() is None, "companion/mac-bridge is not in this checkout")
class RealCompanionBridgeTest(unittest.TestCase):
    """The runtime against the actual companion bridge, which drives a fake heptabase CLI."""

    def setUp(self) -> None:
        companion = _companion_dir()
        assert companion is not None
        sys.path.insert(0, str(companion))
        self.addCleanup(sys.path.remove, str(companion))
        import samrabbit_bridge  # noqa: PLC0415

        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        (root / "state" / "docs").mkdir(parents=True)
        cli = root / "heptabase"
        cli.write_text(f'#!/bin/sh\nexec "{sys.executable}" "{companion / "tests" / "fake_heptabase.py"}" "$@"\n')
        cli.chmod(0o755)
        self.token = "companion-" + "c" * 32
        token_file = root / "bridge-token"
        token_file.write_text(self.token)
        token_file.chmod(0o600)
        previous = os.environ.get("FAKE_HEPTABASE_DIR")
        os.environ["FAKE_HEPTABASE_DIR"] = str(root / "state")
        self.addCleanup(lambda: os.environ.pop("FAKE_HEPTABASE_DIR") if previous is None
                        else os.environ.__setitem__("FAKE_HEPTABASE_DIR", previous))
        self.state = root / "state"
        self.server = samrabbit_bridge.make_server("127.0.0.1", 0, token_file=str(token_file), cli=str(cli),
                                                   cli_timeout=5.0)
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.h = _harness()
        self.addCleanup(self.h.close)

    def test_end_to_end_append_read_and_app_down(self) -> None:
        url = f"http://127.0.0.1:{self.server.server_address[1]}"
        view = self.h.service.configure_bridge(url, self.token)
        self.assertEqual(("mac", True, "0.7.0"), (view["mode"], view["bridge"]["appReachable"],
                                                  view["bridge"]["cliVersion"]))
        self.assertEqual("sent", self.h.service.write_test_entry()["state"])
        journal = json.loads((self.state / "journal.json").read_text())
        self.assertEqual({"2026-10-07": ['- 17:42 <hepta-color type="text" color="gray">'
                                         "↳ R1 journal connected through the Mac</hepta-color>"]}, journal)
        (self.state / "mode").write_text("down")
        self.assertEqual("queued", self.h.service.record_note("while the app is closed")["state"])
        self.assertEqual("heptabase_app_unavailable", self.h.service.management_view()["bridge"]["lastError"])
        (self.state / "mode").write_text("ok")
        self.h.service.check_bridge()
        self.h.service.drain()
        self.assertEqual(["sent", "sent"], sorted(str(row["state"]) for row in self.h.rows()))
        self.assertIn("while the app is closed", self.h.service.read_journal()["text"])


if __name__ == "__main__":
    unittest.main()
