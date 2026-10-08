from __future__ import annotations

import json
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.api.http_server import RuntimeHttpServer
from sam_runtime.api.mac_routes import MacRoutes
from sam_runtime.api.t3_routes import T3Routes
from sam_runtime.domains.t3.placement import OrchestrationSetting, ProjectPlacement
from sam_runtime.security.pairing import PairingAuthority
from sam_runtime.storage.lifecycle_repository import LifecycleRepository

from mac_fakes import FIX, FakeMacControlBridge, make_mac
from t3_fixtures import PAIRING_CODE, PROJECT_MAIN, PROJECT_SCRATCH, PROJECT_SIDE, FakeT3Server, make_service, thread

LOCAL_TOKEN = "l" * 43
ORIGIN = "https://r1.local:8443"


class MacRoutesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fake = FakeT3Server().__enter__()
        self.service, _, _, database, self.directory = make_service()
        self.mac_fake = FakeMacControlBridge()
        self.store, self.mac = make_mac(database)
        self.setting = OrchestrationSetting(database)
        self.placement = ProjectPlacement(self.service, self.setting)
        self.pairing = PairingAuthority()
        lifecycle = LifecycleRepository(database)
        lifecycle.record_start()
        self.server = RuntimeHttpServer(
            host="127.0.0.1", port=0, token=LOCAL_TOKEN, health=lambda: {"status": "ready"}, lifecycle=lifecycle,
            events=RuntimeEventStream(), pairing=self.pairing, t3=T3Routes(self.service, self.placement),
            mac=MacRoutes(self.mac),
        )
        self.server.start()
        self.base = f"http://127.0.0.1:{self.server.port}"
        self.bodies: list[str] = []

    def tearDown(self) -> None:
        self.server.stop()
        self.mac_fake.close()
        self.fake.__exit__()
        self.directory.cleanup()

    def call(self, method: str, path: str, body: object | None = None, *, bearer: bool = True,
             browser: object | None = None, csrf: bool = True) -> tuple[int, dict[str, object]]:
        headers = {"Content-Type": "application/json"}
        if bearer:
            headers["Authorization"] = f"Bearer {LOCAL_TOKEN}"
        if browser is not None:
            headers.update({"Cookie": f"sam_session={browser.token}", "X-SAM-Forwarded-Origin": ORIGIN, "Origin": ORIGIN})
            if csrf:
                headers["X-CSRF-Token"] = browser.csrf_token
        data = json.dumps(body).encode() if body is not None else (b"" if method == "POST" else None)
        request = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=20) as response:
                raw, status = response.read().decode(), response.status
        except HTTPError as error:
            raw, status = error.read().decode(), error.code
        self.bodies.append(raw)
        return status, json.loads(raw) if raw else {}

    def browser(self) -> object:
        return self.pairing.pair(self.pairing.current_code().value, ORIGIN, ORIGIN)

    def connect_t3(self) -> None:
        self.fake.set_threads([thread("o1", "Orbit work", project_id=PROJECT_SIDE)])
        self.service.connect(self.fake.url, PAIRING_CODE)

    def test_management_mac_needs_a_session_and_reports_capabilities(self) -> None:
        self.assertEqual(401, self.call("GET", "/v1/management/mac", bearer=False)[0])
        self.assertIn(self.call("GET", "/v1/management/mac")[0], (401, 403), "the device token alone is not enough")
        browser = self.browser()
        status, view = self.call("GET", "/v1/management/mac", browser=browser)
        self.assertEqual(200, status, view)
        self.assertFalse(view["configured"])
        self.store.save(self.mac_fake.url, self.mac_fake.token)
        status, view = self.call("GET", "/v1/management/mac", browser=browser)
        self.assertEqual(200, status, view)
        self.assertTrue(view["configured"] and view["reachable"])
        self.assertFalse(view["capabilities"]["permissions"]["screenRecording"])
        self.assertEqual(FIX, view["capabilities"]["screenRecordingFix"])
        self.mac_fake.close()
        status, view = self.call("GET", "/v1/management/mac", browser=browser)
        self.assertEqual((200, False, "mac_unreachable"), (status, view["reachable"], view["error"]))
        for body in self.bodies:
            self.assertNotIn(self.mac_fake.token, body)

    def test_orchestration_project_is_shown_and_changeable_on_the_t3_page(self) -> None:
        browser = self.browser()
        status, view = self.call("GET", "/v1/management/t3", browser=browser)
        self.assertEqual(200, status)
        self.assertIsNone(view["orchestration"], "nothing to pick before T3 is paired")
        self.assertEqual(409, self.call("POST", "/v1/management/t3/settings", {"orchestrationProjectId": PROJECT_MAIN},
                                        browser=browser)[0])
        self.connect_t3()
        status, view = self.call("GET", "/v1/management/t3", browser=browser)
        orchestration = view["orchestration"]
        self.assertEqual("default", orchestration["source"])
        self.assertEqual(PROJECT_SIDE, orchestration["projectId"], "no agent workspace: the most recent project")
        self.assertNotIn(PROJECT_SCRATCH, [item["id"] for item in orchestration["projects"]])
        self.assertEqual(403, self.call("POST", "/v1/management/t3/settings", {"orchestrationProjectId": PROJECT_MAIN},
                                        browser=browser, csrf=False)[0])
        self.assertEqual(401, self.call("POST", "/v1/management/t3/settings", {"orchestrationProjectId": PROJECT_MAIN},
                                        bearer=False)[0])
        status, view = self.call("POST", "/v1/management/t3/settings", {"orchestrationProjectId": PROJECT_MAIN},
                                 browser=browser)
        self.assertEqual(200, status, view)
        self.assertEqual((PROJECT_MAIN, "Workbench", "setting"), (view["orchestration"]["projectId"],
                                                                  view["orchestration"]["projectTitle"],
                                                                  view["orchestration"]["source"]))
        self.assertEqual(PROJECT_MAIN, self.setting.get())
        for bad in ({"orchestrationProjectId": PROJECT_SCRATCH}, {"orchestrationProjectId": "nope"},
                    {"orchestrationProjectId": 7}, {}):
            with self.subTest(body=bad):
                self.assertEqual(400, self.call("POST", "/v1/management/t3/settings", bad, browser=browser)[0])
        status, view = self.call("POST", "/v1/management/t3/settings", {"orchestrationProjectId": None}, browser=browser)
        self.assertEqual((200, "default"), (status, view["orchestration"]["source"]))
        self.assertIsNone(self.setting.get())


if __name__ == "__main__":
    unittest.main()
