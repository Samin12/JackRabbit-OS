"""In-process stand-in for the generated-UI half of the SamRabbit Mac bridge.

``FakeGeneratedUiBridge`` answers ``POST /v1/ui/generate`` (idempotent per requestId, like the real bridge),
``GET /v1/ui/artifacts/<id>`` and ``/image`` behind a bearer token, records every request, and lets tests move
an artifact to ready or failed.
"""

from __future__ import annotations

import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
import secrets
import threading
from urllib.parse import urlsplit

from mac_fakes import TINY_JPEG

_ARTIFACT = re.compile(r"^/v1/ui/artifacts/(ui_[0-9a-f]{24})(/image)?$")


def artifact_id_for(request_id: str) -> str:
    return "ui_" + hashlib.sha256(("samrabbit-ui:" + request_id).encode()).hexdigest()[:24]


class FakeGeneratedUiBridge:
    def __init__(self, token: str | None = None) -> None:
        self.token = token or "bridge-" + secrets.token_urlsafe(24)
        self.requests: list[dict[str, object]] = []
        self.artifacts: dict[str, dict[str, object]] = {}
        self.generate_response: tuple[int, dict[str, object]] | None = None
        self.image = TINY_JPEG
        self.lock = threading.Lock()
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), _handler(self))
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self._thread.start()
        self.url = f"http://127.0.0.1:{self._server.server_address[1]}"

    def close(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def ready(self, artifact_id: str, *, title: str = "Meetings this week",
              summary: str = "Thursday is the busiest day with 6 meetings.") -> None:
        with self.lock:
            self.artifacts[artifact_id].update({"status": "ready", "title": title, "summary": summary,
                                                "imageBlobId": "sha256:" + "a" * 64, "width": 960, "height": 1024})

    def fail(self, artifact_id: str, code: str = "generation_timeout") -> None:
        with self.lock:
            self.artifacts[artifact_id].update({"status": "failed", "error": code,
                                                "errorMessage": "Making the visual took too long."})

    def generate_bodies(self) -> list[dict[str, object]]:
        with self.lock:
            return [item["body"] for item in self.requests if item["path"] == "/v1/ui/generate"]

    def paths(self) -> list[str]:
        with self.lock:
            return [str(item["path"]) for item in self.requests]


def _handler(fake: FakeGeneratedUiBridge) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args) -> None:  # noqa: ANN002
            pass

        def _send(self, status: int, body: bytes, content_type: str) -> None:
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _json(self, status: int, payload: dict[str, object]) -> None:
            self._send(status, json.dumps(payload).encode(), "application/json")

        def _serve(self, method: str) -> None:
            path = urlsplit(self.path).path
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            with fake.lock:
                fake.requests.append({"method": method, "path": path, "body": json.loads(raw) if raw else None,
                                      "authorization": self.headers.get("Authorization", "")})
            if self.headers.get("Authorization", "") != f"Bearer {fake.token}":
                self._json(401, {"error": {"code": "unauthorized", "message": "A valid bridge token is required."}})
                return
            if path == "/v1/ui/generate" and method == "POST":
                if fake.generate_response is not None:
                    self._json(*fake.generate_response)
                    return
                body = json.loads(raw or b"{}")
                artifact_id = artifact_id_for(str(body.get("requestId")))
                with fake.lock:
                    existing = fake.artifacts.get(artifact_id)
                    if existing is None:
                        fake.artifacts[artifact_id] = {"artifactId": artifact_id, "status": "generating",
                                                       "title": "", "summary": ""}
                        status = 202
                    else:
                        status = 202 if existing["status"] == "generating" else 200
                    view = dict(fake.artifacts[artifact_id])
                self._json(status, {"artifactId": artifact_id, "status": view["status"]})
                return
            match = _ARTIFACT.match(path)
            if match and method == "GET":
                with fake.lock:
                    artifact = dict(fake.artifacts.get(match.group(1)) or {})
                if not artifact:
                    self._json(404, {"error": {"code": "artifact_not_found", "message": "No such visual."}})
                elif match.group(2):
                    if artifact["status"] != "ready":
                        self._json(409, {"error": {"code": "image_not_ready", "message": "Not ready.",
                                                   "retryable": True}})
                    else:
                        self._send(200, fake.image, "image/jpeg")
                else:
                    self._json(200, artifact)
                return
            self._json(404, {"error": {"code": "not_found", "message": "Not found."}})

        def do_GET(self) -> None:  # noqa: N802
            self._serve("GET")

        def do_POST(self) -> None:  # noqa: N802
            self._serve("POST")

    return Handler
