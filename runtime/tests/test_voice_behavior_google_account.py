"""Google links opened in Chrome go to the user's own Google account, and "my calendar" means Google Calendar.

The real case: "open my calendar in Chrome" opened the macOS Calendar app; then https://calendar.google.com opened
Chrome's first account (/u/0) instead of samin@aianswer.us, and an agent spent 1.5 minutes switching accounts.
"""

from __future__ import annotations

from datetime import UTC, datetime
import json
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from uuid import uuid4

from sam_runtime.agents import AgentKind
from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.api.http_server import RuntimeHttpServer
from sam_runtime.api.mac_routes import MacRoutes
from sam_runtime.domains.calendar import CalendarAccountConfiguration, CalendarEvent, CalendarRepository
from sam_runtime.domains.mac import MAC_VOICE_INSTRUCTION, GoogleAccountSetting, register_mac_tools, with_google_account
from sam_runtime.domains.mac.google_account import normalize_email
from sam_runtime.security.pairing import PairingAuthority
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.storage.lifecycle_repository import LifecycleRepository
from sam_runtime.tools import ToolCatalog
from sam_runtime.tools.definitions import ToolInvocationContext

from mac_fakes import FakeMacControlBridge, make_mac

EMAIL = "samin@aianswer.us"
LOCAL_TOKEN = "g" * 43
ORIGIN = "https://r1.local:8443"


class GoogleLinkTest(unittest.TestCase):
    def test_google_links_get_the_account(self) -> None:
        cases = {
            "https://calendar.google.com": f"https://calendar.google.com/calendar/r?authuser={EMAIL}",
            "https://calendar.google.com/": f"https://calendar.google.com/calendar/r?authuser={EMAIL}",
            "https://calendar.google.com/calendar/r/week?tab=mc":
                f"https://calendar.google.com/calendar/r/week?tab=mc&authuser={EMAIL}",
            "https://mail.google.com/mail/u/0/#inbox": f"https://mail.google.com/mail/?authuser={EMAIL}#inbox",
            "https://calendar.google.com/calendar/u/0/r": f"https://calendar.google.com/calendar/r?authuser={EMAIL}",
            "https://drive.google.com/drive/my-drive": f"https://drive.google.com/drive/my-drive?authuser={EMAIL}",
            "https://docs.google.com/document/d/abc/edit": f"https://docs.google.com/document/d/abc/edit?authuser={EMAIL}",
            "https://meet.google.com/abc-defg-hij": f"https://meet.google.com/abc-defg-hij?authuser={EMAIL}",
            "HTTPS://Calendar.Google.com/calendar/r": f"https://Calendar.Google.com/calendar/r?authuser={EMAIL}",
        }
        for url, expected in cases.items():
            with self.subTest(url=url):
                self.assertEqual(expected, with_google_account(url, EMAIL))

    def test_an_account_number_is_replaced_by_the_users_email(self) -> None:
        # authuser=N and /u/N pick "the Nth account signed in to this Chrome", which is often not the user's.
        cases = {
            "https://calendar.google.com/calendar/r?authuser=1": f"https://calendar.google.com/calendar/r?authuser={EMAIL}",
            "https://calendar.google.com/calendar/r?authuser=0": f"https://calendar.google.com/calendar/r?authuser={EMAIL}",
            "https://calendar.google.com/calendar/r/week?authuser=2&tab=mc":
                f"https://calendar.google.com/calendar/r/week?tab=mc&authuser={EMAIL}",
            "https://mail.google.com/mail/u/1/?authuser=1#inbox": f"https://mail.google.com/mail/?authuser={EMAIL}#inbox",
            "https://mail.google.com/mail/u/3/#inbox": f"https://mail.google.com/mail/?authuser={EMAIL}#inbox",
            "https://docs.google.com/document/u/2/d/abc/edit?usp=sharing":
                f"https://docs.google.com/document/d/abc/edit?usp=sharing&authuser={EMAIL}",
            "https://drive.google.com/drive/u/1/my-drive?AUTHUSER=1":
                f"https://drive.google.com/drive/my-drive?authuser={EMAIL}",
        }
        for url, expected in cases.items():
            with self.subTest(url=url):
                self.assertEqual(expected, with_google_account(url, EMAIL))

    def test_other_links_and_chosen_accounts_are_left_alone(self) -> None:
        for url in ("https://calendar.google.com/calendar/r?authuser=other@example.com",
                    "https://calendar.google.com/calendar/u/1/r?authuser=other%40example.com",
                    "https://mail.google.com/mail/?AuthUser=other@example.com",
                    "https://www.google.com/search?q=calendar", "https://example.com/calendar",
                    "https://calendar.google.com.evil.example/", "ftp://calendar.google.com/"):
            with self.subTest(url=url):
                self.assertEqual(url, with_google_account(url, EMAIL))
        self.assertEqual("https://calendar.google.com", with_google_account("https://calendar.google.com", None))
        self.assertEqual("https://calendar.google.com", with_google_account("https://calendar.google.com", "nope"))

    def test_email_normalization(self) -> None:
        self.assertEqual(EMAIL, normalize_email(" Samin@AIAnswer.us "))
        self.assertEqual(EMAIL, normalize_email("mailto:samin@aianswer.us"))
        for bad in (None, "", "Samin's calendar", "a@b", "a b@example.com", 42):
            self.assertIsNone(normalize_email(bad))


class GoogleAccountSettingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.calendars = CalendarRepository(self.database)
        self.setting = GoogleAccountSetting(self.database)

    def subscribe(self, label: str, calendar_name: str | None = None) -> str:
        account = self.calendars.create_account(CalendarAccountConfiguration(
            str(uuid4()), "ics_subscription", label, "https://calendar.google.com/calendar/ical/x/private-y/basic.ics",
            None), None).configuration.account_id
        if calendar_name:
            now = datetime.now(UTC).isoformat()
            self.calendars.replace_account_events(account, (CalendarEvent(
                str(uuid4()), account, "p1", "", "Focus", now, None, "UTC", False, None, calendar_name, None, None,
                "confirmed", False, None, now),))
        return account

    def test_default_comes_from_the_ical_subscription_label(self) -> None:
        self.assertEqual((None, None), self.setting.resolve())
        self.subscribe(EMAIL)
        self.assertEqual((EMAIL, "calendar"), self.setting.resolve())
        self.assertEqual({"email": EMAIL, "source": "calendar", "fromCalendar": EMAIL}, self.setting.view())

    def test_or_from_the_feeds_calendar_name(self) -> None:
        self.subscribe("Work calendar", calendar_name=EMAIL)
        self.assertEqual(EMAIL, self.setting.email())

    def test_the_setting_wins_and_clearing_returns_to_the_calendar(self) -> None:
        self.subscribe(EMAIL)
        self.assertEqual("me@example.com", self.setting.set(" Me@Example.com "))
        self.assertEqual(("me@example.com", "setting"), self.setting.resolve())
        self.assertIsNone(self.setting.set(""))
        self.assertEqual((EMAIL, "calendar"), self.setting.resolve())
        with self.assertRaises(ValueError):
            self.setting.set("not an email")


class MacOpenGoogleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.fake = FakeMacControlBridge()
        self.addCleanup(self.fake.close)
        self.store, self.client = make_mac(self.database, self.fake)
        self.setting = GoogleAccountSetting(self.database)
        self.catalog = ToolCatalog()
        register_mac_tools(self.catalog, self.client, google_account=self.setting.email)

    def open(self, arguments: dict[str, object]):
        context = ToolInvocationContext(AgentKind.VOICE, voice_session_id="voice-1")
        return self.catalog.invoke("mac_open", arguments, agent=AgentKind.VOICE, context=context)

    def test_google_calendar_opens_in_the_users_account(self) -> None:
        self.setting.set(EMAIL)
        result = self.open({"url": "https://calendar.google.com"})
        self.assertFalse(result.is_error, result.text)
        self.assertEqual({"url": f"https://calendar.google.com/calendar/r?authuser={EMAIL}"},
                         self.fake.last("/v1/mac/open")["body"])
        self.assertEqual(EMAIL, json.loads(result.text)["googleAccount"])

    def test_other_links_apps_and_no_account_pass_through(self) -> None:
        self.setting.set(EMAIL)
        result = self.open({"url": "https://example.com/"})
        self.assertEqual({"url": "https://example.com/"}, self.fake.last("/v1/mac/open")["body"])
        self.assertNotIn("googleAccount", json.loads(result.text))
        self.open({"app": "Calendar"})
        self.assertEqual({"app": "Calendar"}, self.fake.last("/v1/mac/open")["body"])
        self.setting.set(None)
        self.open({"url": "https://calendar.google.com/calendar/r"})
        self.assertEqual({"url": "https://calendar.google.com/calendar/r"}, self.fake.last("/v1/mac/open")["body"])

    def test_voice_guidance_means_google_calendar_in_chrome(self) -> None:
        definition = {item["name"]: item for item in self.catalog.realtime_definitions()}["mac_open"]
        for text in (MAC_VOICE_INSTRUCTION, definition["description"]):
            with self.subTest(text=text[:40]):
                self.assertIn("https://calendar.google.com/calendar/r", text)
                self.assertIn("Calendar app", text)
                self.assertIn("own Google account", text)


class GoogleAccountRouteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        self.database.migrate()
        self.store, self.mac = make_mac(self.database)
        self.setting = GoogleAccountSetting(self.database)
        CalendarRepository(self.database).create_account(CalendarAccountConfiguration(
            str(uuid4()), "ics_subscription", EMAIL, "https://example.com/basic.ics", None), None)
        self.pairing = PairingAuthority()
        lifecycle = LifecycleRepository(self.database)
        lifecycle.record_start()
        self.server = RuntimeHttpServer(
            host="127.0.0.1", port=0, token=LOCAL_TOKEN, health=lambda: {"status": "ready"}, lifecycle=lifecycle,
            events=RuntimeEventStream(), pairing=self.pairing, mac=MacRoutes(self.mac, google_account=self.setting),
        )
        self.server.start()
        self.addCleanup(self.server.stop)
        self.base = f"http://127.0.0.1:{self.server.port}"

    def call(self, method: str, body: object | None = None, *, browser: object | None = None,
             csrf: bool = True) -> tuple[int, dict[str, object]]:
        headers = {"Content-Type": "application/json", "Authorization": f"Bearer {LOCAL_TOKEN}"}
        if browser is not None:
            headers.update({"Cookie": f"sam_session={browser.token}", "X-SAM-Forwarded-Origin": ORIGIN, "Origin": ORIGIN})
            if csrf:
                headers["X-CSRF-Token"] = browser.csrf_token
        data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
        request = Request(self.base + "/v1/management/mac", data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=20) as response:
                raw, status = response.read().decode(), response.status
        except HTTPError as error:
            raw, status = error.read().decode(), error.code
        return status, json.loads(raw) if raw else {}

    def test_the_mac_card_shows_and_saves_the_google_account(self) -> None:
        browser = self.pairing.pair(self.pairing.current_code().value, ORIGIN, ORIGIN)
        status, view = self.call("GET", browser=browser)
        self.assertEqual(200, status, view)
        self.assertEqual({"email": EMAIL, "source": "calendar", "fromCalendar": EMAIL}, view["googleAccount"])
        self.assertIn(self.call("POST", {"googleAccount": "me@example.com"})[0], (401, 403), "needs a session")
        self.assertEqual(403, self.call("POST", {"googleAccount": "me@example.com"}, browser=browser, csrf=False)[0])
        status, value = self.call("POST", {"googleAccount": "Me@Example.com"}, browser=browser)
        self.assertEqual((200, "me@example.com", "setting"),
                         (status, value["googleAccount"]["email"], value["googleAccount"]["source"]))
        for bad in ({"googleAccount": "nope"}, {"googleAccount": 7}, {}):
            with self.subTest(body=bad):
                self.assertEqual(400, self.call("POST", bad, browser=browser)[0])
        status, value = self.call("POST", {"googleAccount": None}, browser=browser)
        self.assertEqual((200, EMAIL, "calendar"),
                         (status, value["googleAccount"]["email"], value["googleAccount"]["source"]))


if __name__ == "__main__":
    unittest.main()
