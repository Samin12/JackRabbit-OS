from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from sam_runtime.api.events import RuntimeEventStream
from sam_runtime.api.generated_ui_routes import GeneratedUiRoutes
from sam_runtime.api.http_server import RuntimeHttpServer
from sam_runtime.domains.generated_ui import GeneratedUiClient
from sam_runtime.security.pairing import PairingAuthority
from sam_runtime.storage.database import RuntimeDatabase
from sam_runtime.storage.lifecycle_repository import LifecycleRepository

from generated_ui_fakes import FakeGeneratedUiBridge, artifact_id_for
from mac_fakes import TINY_JPEG, make_mac

LOCAL_TOKEN = "l" * 43


class GeneratedUiRoutesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory()
        database = RuntimeDatabase(Path(self.directory.name) / "runtime.sqlite3")
        database.migrate()
        self.fake = FakeGeneratedUiBridge()
        self.store, _mac = make_mac(database)
        self.client = GeneratedUiClient(self.store)
        lifecycle = LifecycleRepository(database)
        lifecycle.record_start()
        self.server = RuntimeHttpServer(
            host="127.0.0.1", port=0, token=LOCAL_TOKEN, health=lambda: {"status": "ready"}, lifecycle=lifecycle,
            events=RuntimeEventStream(), pairing=PairingAuthority(), generated_ui=GeneratedUiRoutes(self.client),
        )
        self.server.start()
        self.base = f"http://127.0.0.1:{self.server.port}"

    def tearDown(self) -> None:
        self.server.stop()
        self.fake.close()
        self.directory.cleanup()

    def get(self, path: str, *, headers: dict[str, str] | None = None, bearer: bool = True
            ) -> tuple[int, str, bytes]:
        merged = {"Authorization": f"Bearer {LOCAL_TOKEN}"} if bearer else {}
        merged.update(headers or {})
        try:
            with urlopen(Request(self.base + path, headers=merged), timeout=10) as response:
                return response.status, response.headers.get("Content-Type", ""), response.read()
        except HTTPError as error:
            return error.code, error.headers.get("Content-Type", ""), error.read()

    def test_image_is_proxied_for_the_device(self) -> None:
        self.store.save(self.fake.url, self.fake.token)
        artifact_id = str(self.client.generate(request_id="rt:r:1", prompt="a chart", data=None,
                                               conversation_id=None)["artifactId"])
        path = f"/v1/ui/artifacts/{artifact_id}/image"
        status, _type, body = self.get(path)
        self.assertEqual(409, status)
        self.assertEqual("image_not_ready", json.loads(body)["error"]["code"])
        self.fake.ready(artifact_id)
        status, content_type, body = self.get(path)
        self.assertEqual((200, "image/jpeg", TINY_JPEG), (status, content_type, body))
        self.assertEqual(artifact_id_for("rt:r:1"), artifact_id)
        self.assertEqual(f"Bearer {self.fake.token}", self.fake.requests[-1]["authorization"])

    def test_device_only_and_bearer_required(self) -> None:
        self.store.save(self.fake.url, self.fake.token)
        path = "/v1/ui/artifacts/ui_" + "1" * 24 + "/image"
        status, _type, _body = self.get(path, bearer=False)
        self.assertEqual(401, status)
        status, _type, body = self.get(path, headers={"X-SAM-Forwarded-Origin": "https://r1.local:8443"})
        self.assertEqual((403, "device_only"), (status, json.loads(body)["error"]["code"]))
        status, _type, body = self.get(path)
        self.assertEqual((404, "artifact_not_found"), (status, json.loads(body)["error"]["code"]))
        status, _type, _body = self.get("/v1/ui/artifacts/not-an-id/image")
        self.assertEqual(404, status)
        self.assertEqual(1, len(self.fake.requests), "malformed ids never reach the Mac")

    def test_without_a_mac_bridge(self) -> None:
        status, _type, body = self.get("/v1/ui/artifacts/ui_" + "1" * 24 + "/image")
        self.assertEqual((503, "mac_not_configured"), (status, json.loads(body)["error"]["code"]))

    def test_a_non_image_answer_is_refused(self) -> None:
        self.store.save(self.fake.url, self.fake.token)
        artifact_id = str(self.client.generate(request_id="rt:r:2", prompt="a chart", data=None,
                                               conversation_id=None)["artifactId"])
        self.fake.ready(artifact_id)
        self.fake.image = b"<html>not a picture</html>"
        status, _type, body = self.get(f"/v1/ui/artifacts/{artifact_id}/image")
        self.assertEqual((502, "bad_image"), (status, json.loads(body)["error"]["code"]))


if __name__ == "__main__":
    unittest.main()
