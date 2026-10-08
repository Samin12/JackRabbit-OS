"""Conversation sync on the bridge: store, dedupe, drafts, blobs, the auth matrix, SSE and screenshots.

Run: python3 -m unittest discover -s companion/mac-bridge/tests
"""

from __future__ import annotations

import base64
import hashlib
import http.client
import io
import json
import logging
import os
from pathlib import Path
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from typing import Any, Dict, List, Optional, Tuple

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import samrabbit_bridge as bridge  # noqa: E402
import samrabbit_mac as mac  # noqa: E402
import samrabbit_sync as sync  # noqa: E402

TOKEN = "test-token-" + "s" * 32
DESKTOP = "desktop-token-" + "d" * 32
SECRET_WORDS = "my private conversation sentence 5521"
CONV = "c_0123456789abcdef0123"
JPEG = b"\xff\xd8\xff\xe0" + b"fake-jpeg-bytes" * 40 + b"\xff\xd9"


def event(seq: int, kind: str = "message.user", conversation: str = CONV, **fields: Any) -> Dict[str, Any]:
    value: Dict[str, Any] = {"id": f"{conversation}:{seq}", "seq": seq, "type": kind, "conversationId": conversation,
                             "sessionId": "a" * 24, "at": 1_760_000_000_000 + seq * 1000}
    value.update(fields)
    return value


class StoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / "sync"
        self.store = sync.SyncStore(str(self.root))
        self.addCleanup(self.store.close)

    def test_folders_and_database_are_private(self) -> None:
        self.assertEqual(0o700, stat.S_IMODE(self.root.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE((self.root / "blobs").stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((self.root / "conversations.db").stat().st_mode))
        mode = self.store._db.execute("PRAGMA journal_mode").fetchone()[0]  # noqa: SLF001
        self.assertEqual("wal", mode)

    def test_insert_or_ignore_by_event_id_and_invalid_events_are_rejected(self) -> None:
        first = self.store.insert_events([event(1, text="hello"), event(2, "message.assistant.done", text="hi",
                                                                         messageId="a_1")], "r1")
        self.assertEqual((2, 0, 0), (first["accepted"], first["duplicates"], first["rejected"]))
        again = self.store.insert_events([event(1, text="hello"), event(3, text="more"), {"id": "x"}, "nope",
                                          event(4, "Bad Type"), event(5, conversation="../etc")], "r1")
        self.assertEqual((1, 1, 4), (again["accepted"], again["duplicates"], again["rejected"]))
        self.assertEqual(3, again["cursor"])
        rows = self.store.events_after(0, 100)
        self.assertEqual([1, 2, 3], [number for number, _ in rows])
        self.assertEqual([f"{CONV}:1", f"{CONV}:2", f"{CONV}:3"], [json.loads(payload)["id"] for _, payload in rows])

    def test_conversation_summary_title_preview_count_and_live(self) -> None:
        self.store.insert_events([event(1, "conversation.started"), event(2, text="  What is on my   screen? "),
                                  event(3, "message.assistant.done", text="Chrome and Heptabase.", messageId="a_1"),
                                  event(4, "tool.completed", tool="mac_status")], "r1")
        (summary,) = self.store.conversations(limit=10, before=None, query="")
        self.assertEqual("What is on my screen?", summary["title"])
        self.assertEqual("Chrome and Heptabase.", summary["preview"])
        self.assertEqual(2, summary["messageCount"])
        self.assertEqual(1_760_000_001_000, summary["startedAt"])
        self.assertEqual(1_760_000_004_000, summary["lastAt"])
        self.assertFalse(summary["live"], "a conversation silent for 20 minutes is not live")
        now = int(time.time() * 1000)
        self.store.insert_events([event(10, "message.user", text="still here", at=now)], "r1")
        self.assertTrue(self.store.conversation(CONV)["live"])
        self.store.insert_events([event(11, "conversation.ended", at=now + 1)], "r1")
        ended = self.store.conversation(CONV)
        self.assertFalse(ended["live"])
        self.assertEqual(now + 1, ended["endedAt"])
        self.store.insert_events([event(9, "message.user", text="late retry", at=now - 5)], "r1")
        self.assertFalse(self.store.conversation(CONV)["live"], "an out-of-order retry does not reopen it")

    def test_session_only_conversation_uses_the_finalized_transcript(self) -> None:
        conversation = "s_" + "b" * 24
        now = int(time.time() * 1000)
        self.store.insert_events([{"id": f"rt:{'b' * 24}:call_1", "type": "tool.completed",
                                   "conversationId": conversation, "at": now, "tool": "mac_status"}], "r1")
        self.assertTrue(self.store.conversation(conversation)["live"])
        self.store.insert_events([{"id": f"rt:{'b' * 24}:finalized", "type": "session.finalized",
                                   "conversationId": conversation, "at": now + 10, "entries": [
                                       {"role": "user", "text": "Open my journal", "at": now - 100},
                                       {"role": "assistant", "text": "Done, it is open.", "at": now - 50}]}], "r1")
        summary = self.store.conversation(conversation)
        self.assertEqual(("Open my journal", "Done, it is open.", 2, False),
                         (summary["title"], summary["preview"], summary["messageCount"], summary["live"]))

    def test_assistant_drafts_keep_only_the_newest_and_vanish_with_the_final_message(self) -> None:
        drafts = [event(seq, sync.DELTA, text="Hel"[:seq] + "...", messageId="a_7") for seq in (1, 2, 3)]
        result = self.store.insert_events(drafts, "r1")
        self.assertEqual(3, result["accepted"])
        kinds = [json.loads(payload)["seq"] for _, payload in self.store.events_after(0, 10)]
        self.assertEqual([3], kinds, "older drafts are superseded")
        older = self.store.insert_events([event(2, sync.DELTA, text="He", messageId="a_7")], "r1")
        self.assertEqual(1, older["duplicates"], "a resent older draft is ignored")
        self.store.insert_events([event(4, "message.assistant.done", text="Hello.", messageId="a_7")], "r1")
        late = self.store.insert_events([event(5, sync.DELTA, text="Hello", messageId="a_7")], "r1")
        self.assertEqual(1, late["duplicates"], "a draft after the final message is ignored")
        rows = [json.loads(payload) for _, payload in self.store.events_after(0, 10)]
        self.assertEqual(["message.assistant.done"], [row["type"] for row in rows])
        self.assertEqual(1, self.store.conversation(CONV)["messageCount"])

    def test_one_bad_event_never_fails_the_batch(self) -> None:
        original = self.store._touch  # noqa: SLF001

        def touch(event: Dict[str, Any], cursor: int, device: str) -> None:
            if event.get("text") == "explode":
                raise TypeError("simulated")
            original(event, cursor, device)

        self.store._touch = touch  # type: ignore[method-assign]  # noqa: SLF001
        result = self.store.insert_events([event(1, text="before"), event(2, text="explode"), event(3, text="after"),
                                           event(4, "session.finalized", entries=5),
                                           {**event(5, text="huge numbers", at=10 ** 30), "seq": 2 ** 70}], "r1")
        self.assertEqual((4, 1), (result["accepted"], result["rejected"]))
        stored = {json.loads(payload)["id"]: json.loads(payload) for _, payload in self.store.events_after(0, 10)}
        self.assertEqual({f"{CONV}:{n}" for n in (1, 3, 4, 5)}, set(stored), "the explosion rolled back alone")
        self.assertNotIn("seq", stored[f"{CONV}:5"])
        self.assertLess(stored[f"{CONV}:5"]["at"], 10 ** 15)
        self.assertEqual(3, self.store.conversation(CONV)["messageCount"])

    def test_search_matches_titles_and_message_text(self) -> None:
        other = "c_ffffffffffffffffffff"
        self.store.insert_events([event(1, text="plan the trip to Lisbon"),
                                  event(2, "message.assistant.done", text="Flights are cheap in May",
                                        messageId="a_1"),
                                  event(1, conversation=other, text="groceries 100% done_now")], "r1")
        self.assertEqual([CONV], [c["conversationId"] for c in self.store.conversations(limit=5, before=None,
                                                                                         query="cheap in may")])
        self.assertEqual([other], [c["conversationId"] for c in self.store.conversations(limit=5, before=None,
                                                                                          query="100% done_")])
        self.assertEqual([], self.store.conversations(limit=5, before=None, query="100%x"))
        newest_first = [c["conversationId"] for c in self.store.conversations(limit=5, before=None, query="")]
        self.assertEqual([CONV, other], newest_first)
        self.assertEqual([other], [c["conversationId"] for c in self.store.conversations(
            limit=5, before=1_760_000_002_000, query="")])

    def test_blobs_are_content_addressed_private_and_idempotent(self) -> None:
        blob_id, created = self.store.put_blob(JPEG, "image/jpeg", CONV)
        self.assertEqual("sha256:" + hashlib.sha256(JPEG).hexdigest(), blob_id)
        self.assertTrue(created)
        self.assertEqual((blob_id, False), self.store.put_blob(JPEG, "image/jpeg"))
        path, mime, size = self.store.blob(blob_id[7:])
        self.assertEqual(("image/jpeg", len(JPEG)), (mime, size))
        self.assertEqual(JPEG, Path(path).read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(os.stat(path).st_mode))
        self.assertEqual(0o700, stat.S_IMODE(os.stat(os.path.dirname(path)).st_mode))
        with self.assertRaises(sync.SyncError) as caught:
            self.store.put_blob(JPEG, "image/jpeg", expected_hex="0" * 64)
        self.assertEqual("hash_mismatch", caught.exception.code)
        with self.assertRaises(sync.SyncError):
            self.store.put_blob(b"", "image/jpeg")
        with self.assertRaises(sync.SyncError):
            self.store.put_blob(b"x", "not a mime")

    def test_module_functions_are_safe_without_a_running_service(self) -> None:
        self.assertIsNone(sync._default())  # noqa: SLF001
        self.assertIsNone(sync.record_local_event(CONV, {"type": "ui.generating"}))
        self.assertIsNone(sync.put_blob(JPEG, "image/png"))
        service = sync.SyncService(str(Path(self.tmp.name) / "svc"), str(Path(self.tmp.name) / "missing-token"))
        try:
            self.assertTrue(sync.available())
            blob_id = sync.put_blob(b"\x89PNG....", "image/png")
            self.assertTrue(blob_id.startswith("sha256:"))
            event_id = sync.record_local_event(CONV, {"type": "ui.generated", "artifactId": "art_1", "title": "Chart",
                                                      "imageBlobId": blob_id})
            self.assertTrue(event_id.startswith("mac:"))
            self.assertIsNone(sync.record_local_event("bad id!", {"type": "ui.generated"}))
            self.assertIsNone(sync.record_local_event(CONV, {"type": "Not A Type"}))
            ((_, payload),) = service.store.events_after(0, 10)
            stored = json.loads(payload)
            self.assertEqual(("mac", "ui.generated", CONV), (stored["origin"], stored["type"], stored["conversationId"]))
            self.assertEqual("▣ Chart", service.store.conversation(CONV)["preview"])
        finally:
            service.close()
        self.assertFalse(sync.available())


class SyncHttpTest(unittest.TestCase):
    host = "127.0.0.1"

    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.token_file = root / "bridge-token"
        self.token_file.write_text(TOKEN + "\n")
        self.token_file.chmod(0o600)
        self.desktop_file = root / "desktop-token"
        self.desktop_file.write_text(DESKTOP + "\n")
        self.desktop_file.chmod(0o600)
        self.log = io.StringIO()
        handler = logging.StreamHandler(self.log)
        bridge._LOG.addHandler(handler)  # noqa: SLF001
        bridge._LOG.setLevel(logging.INFO)  # noqa: SLF001
        self.addCleanup(bridge._LOG.removeHandler, handler)  # noqa: SLF001
        self.control = FakeControl()
        self.server = bridge.make_server(self.host, 0, token_file=str(self.token_file), cli_timeout=2.0,
                                         cli=str(root / "no-heptabase"), mac_control=self.control,
                                         sync_dir=str(root / "sync"), desktop_token_file=str(self.desktop_file))
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.thread.start()
        self.addCleanup(self._stop)
        self.port = self.server.server_address[1]

    def _stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()

    def request(self, method: str, path: str, body: Any = None, *, bearer: Optional[str] = TOKEN,
                desktop: Optional[str] = None, cookie: Optional[str] = None, headers: Optional[Dict[str, str]] = None,
                raw: Optional[bytes] = None, address: str = "127.0.0.1") -> Tuple[int, Dict[str, str], bytes]:
        connection = http.client.HTTPConnection(address, self.port, timeout=10)
        sent = dict(headers or {})
        if bearer is not None:
            sent["Authorization"] = "Bearer " + bearer
        if desktop is not None:
            sent[sync.DESKTOP_HEADER] = desktop
        if cookie is not None:
            sent["Cookie"] = f"theme=dark; {sync.DESKTOP_COOKIE}={cookie}"
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        if data is not None and "Content-Type" not in sent:
            sent["Content-Type"] = "application/json"
        connection.request(method, path, body=data, headers=sent)
        response = connection.getresponse()
        payload = response.read()
        result = (response.status, {key.lower(): value for key, value in response.getheaders()}, payload)
        connection.close()
        return result

    def call(self, method: str, path: str, body: Any = None, **options: Any) -> Tuple[int, Dict[str, Any]]:
        status, _headers, payload = self.request(method, path, body, **options)
        return status, json.loads(payload or b"{}")

    def desktop_get(self, path: str, **options: Any) -> Tuple[int, Dict[str, Any]]:
        return self.call("GET", path, bearer=None, desktop=DESKTOP, **options)

    def post_events(self, events: List[Dict[str, Any]]) -> Dict[str, Any]:
        status, value = self.call("POST", "/v1/sync/events", {"device": "r1", "events": events})
        self.assertEqual(200, status, value)
        return value

    # ------------------------------------------------------------------ hop 2 (R1 runtime -> bridge)

    def test_events_are_accepted_once_and_report_the_cursor(self) -> None:
        value = self.post_events([event(1, text="hello"), event(2, text="again")])
        self.assertEqual({"accepted": 2, "duplicates": 0, "rejected": 0, "cursor": 2}, value)
        value = self.post_events([event(2, text="again"), event(3, text="third")])
        self.assertEqual({"accepted": 1, "duplicates": 1, "rejected": 0, "cursor": 3}, value)

    def test_the_events_route_takes_256_kb_and_refuses_more(self) -> None:
        big = [event(index, text="x" * 60_000) for index in range(1, 5)]  # ~240 KB: over the old 64 KB limit
        value = self.post_events(big)
        self.assertEqual(4, value["accepted"])
        status, value = self.call("POST", "/v1/sync/events", raw=b"{" + b" " * (sync.MAX_EVENTS_BODY_BYTES + 1) + b"}")
        self.assertEqual((413, "body_too_large"), (status, value["error"]["code"]))
        status, value = self.call("POST", "/v1/sync/events", raw=b"[]")
        self.assertEqual((400, "invalid_json"), (status, value["error"]["code"]))
        status, value = self.call("POST", "/v1/sync/events", {"events": "nope"})
        self.assertEqual((400, "invalid_events"), (status, value["error"]["code"]))
        status, value = self.call("GET", "/v1/sync/events")
        self.assertEqual(405, status)

    def test_blob_put_verifies_the_hash_and_is_idempotent(self) -> None:
        digest = hashlib.sha256(JPEG).hexdigest()
        headers = {"Content-Type": "image/jpeg"}
        status, value = self.call("PUT", "/v1/sync/blobs/" + digest, raw=JPEG, headers=headers)
        self.assertEqual((200, True, "sha256:" + digest), (status, value["created"], value["blobId"]))
        status, value = self.call("PUT", "/v1/sync/blobs/sha256%3A" + digest, raw=JPEG, headers=headers)
        self.assertEqual((200, False), (status, value["created"]))
        status, value = self.call("PUT", "/v1/sync/blobs/" + "0" * 64, raw=JPEG, headers=headers)
        self.assertEqual((400, "hash_mismatch"), (status, value["error"]["code"]))
        status, value = self.call("PUT", "/v1/sync/blobs/xyz", raw=JPEG, headers=headers)
        self.assertEqual((400, "invalid_blob_id"), (status, value["error"]["code"]))
        large = b"x" * (sync.MAX_BLOB_BODY_BYTES + 1)
        status, value = self.call("PUT", "/v1/sync/blobs/" + hashlib.sha256(large).hexdigest(), raw=large,
                                  headers=headers)
        self.assertEqual((413, "body_too_large"), (status, value["error"]["code"]))
        status, headers_out, data = self.request("GET", "/v1/sync/blobs/" + digest, bearer=None, desktop=DESKTOP)
        self.assertEqual((200, JPEG, "image/jpeg"), (status, data, headers_out["content-type"]))
        self.assertIn("immutable", headers_out["cache-control"])
        self.assertEqual("nosniff", headers_out["x-content-type-options"])
        status, value = self.desktop_get("/v1/sync/blobs/" + "1" * 64)
        self.assertEqual((404, "blob_not_found"), (status, value["error"]["code"]))

    # ------------------------------------------------------------------ the auth matrix

    def test_r1_routes_need_the_bridge_token_and_never_take_the_desktop_token(self) -> None:
        digest = hashlib.sha256(JPEG).hexdigest()
        for method, path, raw in (("POST", "/v1/sync/events", b'{"events":[]}'), ("PUT", "/v1/sync/blobs/" + digest, JPEG),
                                  ("GET", "/v1/sync/nope", None)):
            for options in ({"bearer": None}, {"bearer": "wrong-" + "t" * 40}, {"bearer": DESKTOP},
                            {"bearer": None, "desktop": DESKTOP}, {"bearer": None, "cookie": DESKTOP}):
                with self.subTest(path=path, options=options):
                    status, value = self.call(method, path, raw=raw, **options)
                    self.assertEqual((401, "unauthorized"), (status, value["error"]["code"]))
        self.assertEqual(404, self.call("GET", "/v1/sync/nope")[0])
        self.assertEqual(0, self.server.sync.store.latest_cursor())

    def test_desktop_routes_need_loopback_and_the_desktop_token(self) -> None:
        self.post_events([event(1, text="hello")])
        paths = ("/v1/sync/conversations", f"/v1/sync/conversations/{CONV}", f"/v1/sync/conversations/{CONV}/events",
                 "/v1/sync/status", "/v1/sync/blobs/" + "a" * 64)
        for path in paths:
            for options in ({}, {"desktop": "wrong-" + "d" * 40}, {"desktop": TOKEN}):
                with self.subTest(path=path, options=options):
                    status, value = self.call("GET", path, bearer=TOKEN, **options)
                    self.assertEqual((401, "unauthorized"), (status, value["error"]["code"]), "the R1 token is not enough")
            with self.subTest(path=path, ok="header"):
                self.assertIn(self.desktop_get(path)[0], (200, 404))
            with self.subTest(path=path, ok="cookie"):
                self.assertIn(self.call("GET", path, bearer=None, cookie=DESKTOP)[0], (200, 404))
        for headers in ({"Origin": "https://evil.example"}, {"Origin": "null"}, {"Host": "evil.example:3780"}):
            with self.subTest(headers=headers):
                status, value = self.desktop_get("/v1/sync/conversations", headers=headers)
                self.assertEqual((403, "forbidden"), (status, value["error"]["code"]))
        status, _ = self.desktop_get("/v1/sync/conversations", headers={"Origin": f"http://127.0.0.1:{self.port}"})
        self.assertEqual(200, status)
        self.desktop_file.unlink()
        status, value = self.desktop_get("/v1/sync/conversations")
        self.assertEqual((503, "desktop_token_missing"), (status, value["error"]["code"]))

    def test_peer_rules_lan_is_refused_for_the_desktop_and_public_for_everything(self) -> None:
        service = self.server.sync

        def fake(address: str, **headers: str) -> SimpleNamespace:
            return SimpleNamespace(client_address=(address, 50000), headers=headers, server=self.server)

        self.assertIsNone(service.desktop_denied(fake("127.0.0.1", **{sync.DESKTOP_HEADER: DESKTOP})))
        self.assertIsNone(service.desktop_denied(fake("::1", Cookie=f"{sync.DESKTOP_COOKIE}={DESKTOP}")))
        for address in ("192.168.1.183", "10.0.0.5", "fe80::1%en0", "8.8.8.8", "::ffff:192.168.1.9"):
            denied = service.desktop_denied(fake(address, **{sync.DESKTOP_HEADER: DESKTOP}))
            self.assertEqual((403, "forbidden"), (denied.status, denied.code), address)
        self.assertIsNone(sync.desktop_request_denied(fake("127.0.0.1", **{sync.DESKTOP_HEADER: DESKTOP})),
                          "the module-level rule (for other bridge modules) is the same")
        self.assertEqual(403, sync.desktop_request_denied(fake("192.168.1.183", **{sync.DESKTOP_HEADER: DESKTOP})).status)
        self.assertIsNone(service.device_denied(fake("192.168.1.186", Authorization="Bearer " + TOKEN)))
        self.assertIs(bridge.client_allowed, service._peer_allowed, "one LAN rule for /v1/mac/* and /v1/sync/*")  # noqa: SLF001
        for address in ("8.8.8.8", "100.64.0.1", "2001:4860::8888"):
            denied = service.device_denied(fake(address, Authorization="Bearer " + TOKEN))
            self.assertEqual((403, "forbidden"), (denied.status, denied.code), address)

    # ------------------------------------------------------------------ hop 3 (desktop app)

    def test_conversations_and_events_with_cursor_paging(self) -> None:
        now = int(time.time() * 1000)
        other = "c_eeeeeeeeeeeeeeeeeeee"
        self.post_events([event(1, "conversation.started", at=now), event(2, text="first question", at=now + 1),
                          event(3, "message.assistant.done", text="first answer", messageId="a_1", at=now + 2),
                          event(1, conversation=other, text="older chat", at=now - 60_000)])
        status, value = self.desktop_get("/v1/sync/conversations?limit=10")
        self.assertEqual(200, status)
        self.assertEqual(4, value["cursor"])
        first, second = value["conversations"]
        self.assertEqual((CONV, "first question", "first answer", True, 2),
                         (first["conversationId"], first["title"], first["preview"], first["live"],
                          first["messageCount"]))
        self.assertEqual(other, second["conversationId"])
        status, value = self.desktop_get("/v1/sync/conversations?limit=1")
        self.assertEqual(now + 2, value["nextBefore"])
        status, value = self.desktop_get(f"/v1/sync/conversations?q=older")
        self.assertEqual([other], [item["conversationId"] for item in value["conversations"]])
        status, value = self.desktop_get(f"/v1/sync/conversations/{CONV}/events?limit=2")
        self.assertEqual((200, True, 2), (status, value["more"], value["cursor"]))
        self.assertEqual([1, 2], [item["cursor"] for item in value["events"]])
        self.assertEqual("first question", value["events"][1]["text"])
        status, value = self.desktop_get(f"/v1/sync/conversations/{CONV}/events?after=2")
        self.assertEqual((False, 4), (value["more"], value["cursor"]), "the cursor moves past other conversations")
        self.assertEqual(["message.assistant.done"], [item["type"] for item in value["events"]])
        status, value = self.desktop_get(f"/v1/sync/conversations/{CONV}/events?after=4")
        self.assertEqual(([], 4), (value["events"], value["cursor"]))
        status, value = self.desktop_get("/v1/sync/conversations/c_00000000000000000000/events")
        self.assertEqual((404, "conversation_not_found"), (status, value["error"]["code"]))
        status, value = self.desktop_get("/v1/sync/conversations?after=x&limit=abc")
        self.assertEqual((400, "invalid_query"), (status, value["error"]["code"]))
        status, value = self.desktop_get(f"/v1/sync/conversations/{CONV}")
        self.assertEqual("first question", value["conversation"]["title"])
        status, value = self.desktop_get("/v1/sync/status")
        self.assertEqual((2, 4, 4), (value["conversations"], value["events"], value["cursor"]))

    def test_sse_stream_delivers_new_events_resumes_and_sends_heartbeats(self) -> None:
        self.post_events([event(1, text="before the stream")])
        old_beat = sync.HEARTBEAT_SECONDS
        sync.HEARTBEAT_SECONDS = 0.6
        self.addCleanup(setattr, sync, "HEARTBEAT_SECONDS", old_beat)
        stream = SseClient(self.port, "/v1/sync/stream", {sync.DESKTOP_HEADER: DESKTOP})
        self.addCleanup(stream.close)
        self.assertEqual(200, stream.status)
        self.assertIn("text/event-stream", stream.headers["content-type"])
        self.assertIn(": ready 1", stream.read_until(lambda text: ": ready" in text))
        self.post_events([event(2, text="live one"), event(3, "message.assistant.done", text="live answer",
                                                         messageId="a_9")])
        text = stream.read_until(lambda value: value.count("event: sync") >= 2)
        frames = [frame for frame in text.split("\n\n") if "event: sync" in frame]
        ids = [line[4:] for frame in frames for line in frame.splitlines() if line.startswith("id: ")]
        self.assertEqual(["2", "3"], ids)
        data = [json.loads(line[6:]) for frame in frames for line in frame.splitlines() if line.startswith("data: ")]
        self.assertEqual(([2, 3], "live one"), ([item["cursor"] for item in data], data[0]["text"]))
        self.assertIn(": heartbeat", stream.read_until(lambda value: ": heartbeat" in value, timeout=5))
        stream.close()
        resumed = SseClient(self.port, "/v1/sync/stream", {sync.DESKTOP_HEADER: DESKTOP, "Last-Event-ID": "1"})
        self.addCleanup(resumed.close)
        text = resumed.read_until(lambda value: value.count("event: sync") >= 2)
        self.assertIn("id: 2\n", text)
        self.assertNotIn("id: 1\n", text)
        resumed.close()
        replay = SseClient(self.port, "/v1/sync/stream?after=0", {sync.DESKTOP_HEADER: DESKTOP})
        self.addCleanup(replay.close)
        self.assertIn("before the stream", replay.read_until(lambda value: "before the stream" in value))
        unauthorized = SseClient(self.port, "/v1/sync/stream", {})
        self.addCleanup(unauthorized.close)
        self.assertEqual(401, unauthorized.status)

    def test_too_many_streams_are_refused(self) -> None:
        streams = [SseClient(self.port, "/v1/sync/stream", {sync.DESKTOP_HEADER: DESKTOP})
                   for _ in range(sync.MAX_STREAMS)]
        for stream in streams:
            self.addCleanup(stream.close)
            stream.read_until(lambda text: ": ready" in text)
        status, value = self.desktop_get("/v1/sync/stream")
        self.assertEqual((503, "too_many_streams"), (status, value["error"]["code"]))

    def test_a_closed_stream_frees_its_slot_at_once(self) -> None:
        streams = [SseClient(self.port, "/v1/sync/stream", {sync.DESKTOP_HEADER: DESKTOP})
                   for _ in range(sync.MAX_STREAMS)]
        for stream in streams:
            self.addCleanup(stream.close)
            stream.read_until(lambda text: ": ready" in text)
        streams[0].close()  # the desktop app reloads: well before the next heartbeat (15 s)
        deadline = time.monotonic() + 5
        while True:
            again = SseClient(self.port, "/v1/sync/stream", {sync.DESKTOP_HEADER: DESKTOP})
            self.addCleanup(again.close)
            if again.status == 200 or time.monotonic() > deadline:
                break
            again.close()
            time.sleep(0.2)
        self.assertEqual(200, again.status, "the closed stream still held its slot")

    def test_a_cursor_from_a_replaced_store_starts_over(self) -> None:
        self.post_events([event(1, text="first"), event(2, text="second")])
        status, value = self.desktop_get(f"/v1/sync/conversations/{CONV}/events?after=999")
        self.assertEqual((200, ["first", "second"], 2), (status, [item["text"] for item in value["events"]],
                                                          value["cursor"]))
        stream = SseClient(self.port, "/v1/sync/stream?after=999", {sync.DESKTOP_HEADER: DESKTOP})
        self.addCleanup(stream.close)
        self.assertIn(": ready 2", stream.read_until(lambda text: ": ready" in text))
        self.post_events([event(3, text="third")])
        self.assertIn("id: 3\n", stream.read_until(lambda text: "event: sync" in text))

    def test_too_many_query_fields_are_a_bad_request(self) -> None:
        status, value = self.desktop_get("/v1/sync/conversations?" + "&".join(f"k{n}=1" for n in range(9)))
        self.assertEqual((400, "invalid_query"), (status, value["error"]["code"]))
        self.assertNotIn("Traceback", self.log.getvalue())

    # ------------------------------------------------------------------ Mac screenshots

    def test_a_screenshot_is_kept_as_a_blob_and_filed_in_the_conversation(self) -> None:
        status, value = self.call("GET", f"/v1/mac/screenshot?max=1024&conversation={CONV}")
        self.assertEqual(200, status, value)
        digest = hashlib.sha256(JPEG).hexdigest()
        self.assertEqual("sha256:" + digest, value["blobId"])
        self.assertTrue(value["imageEventId"].startswith("mac:"))
        self.assertEqual(base64.b64encode(JPEG).decode(), value["base64"], "the R1 still gets the image itself")
        status, value = self.desktop_get(f"/v1/sync/conversations/{CONV}/events")
        (image,) = value["events"]
        self.assertEqual(("image", "mac_screenshot", "sha256:" + digest, "mac", 1024, 640),
                         (image["type"], image["source"], image["blobId"], image["origin"], image["width"],
                          image["height"]))
        status, value = self.call("GET", "/v1/mac/screenshot")
        self.assertNotIn("blobId", value, "no conversation (image sync off on the R1): nothing is kept")
        self.assertNotIn("imageEventId", value)
        self.assertEqual(1, self.server.sync.store.counts()["blobs"])
        status, _headers, data = self.request("GET", "/v1/sync/blobs/" + digest, bearer=None, desktop=DESKTOP)
        self.assertEqual((200, JPEG), (status, data))

    def test_a_locked_mac_files_no_screenshot_in_the_conversation(self) -> None:
        self.post_events([event(1, text="what is on my screen")])
        self.control.locked = True
        status, value = self.call("GET", f"/v1/mac/screenshot?max=1024&conversation={CONV}")
        self.assertEqual((409, "screen_locked"), (status, value["error"]["code"]))
        self.assertNotIn("blobId", value)
        self.assertNotIn("imageEventId", value)
        self.assertEqual(0, self.server.sync.store.counts()["blobs"], "no black picture is kept")
        status, value = self.desktop_get(f"/v1/sync/conversations/{CONV}/events")
        self.assertEqual(200, status, value)
        self.assertEqual(["message.user"], [item["type"] for item in value["events"]],
                         "and no image event reaches the desktop app")

    # ------------------------------------------------------------------ privacy

    def test_logs_never_contain_conversation_text_ids_or_tokens(self) -> None:
        self.post_events([event(1, text=SECRET_WORDS)])
        self.desktop_get(f"/v1/sync/conversations/{CONV}/events")
        self.desktop_get("/v1/sync/conversations?q=" + SECRET_WORDS.replace(" ", "+"))
        self.call("GET", "/v1/sync/conversations", bearer=None, desktop="wrong-" + SECRET_WORDS.replace(" ", "-"))
        self.call("PUT", "/v1/sync/blobs/" + "0" * 64, raw=SECRET_WORDS.encode(), headers={"Content-Type": "text/plain"})
        text = self.log.getvalue()
        self.assertIn("POST /v1/sync/events 200", text)
        self.assertIn("GET /v1/sync/conversations/{id}/events 200", text)
        self.assertIn("PUT /v1/sync/blobs/{sha256} 400", text)
        for secret in ("5521", "private conversation", CONV, TOKEN[:12], DESKTOP[:12]):
            self.assertNotIn(secret, text)

    def test_health_reports_sync(self) -> None:
        status, value = self.call("GET", "/health")
        self.assertEqual(200, status)
        self.assertEqual({"available": True, "version": 1, "desktopToken": True}, value["sync"])


@unittest.skipUnless(sys.platform == "darwin", "uses the Mac's LAN address")
class LanPeerTest(SyncHttpTest):
    """The real server on 0.0.0.0, reached through this Mac's LAN address: the R1 routes work,
    the desktop routes refuse a LAN peer even with the right desktop token."""

    host = "0.0.0.0"

    def setUp(self) -> None:
        self.lan = _lan_address()
        if not self.lan:
            self.skipTest("no LAN address")
        super().setUp()

    def test_lan_peer_matrix(self) -> None:
        status, value = self.call("POST", "/v1/sync/events", {"events": [event(1, text="hi")]}, address=self.lan)
        self.assertEqual((200, 1), (status, value["accepted"]))
        status, value = self.call("GET", "/v1/sync/conversations", bearer=None, desktop=DESKTOP, address=self.lan,
                                  headers={"Host": "127.0.0.1"})
        self.assertEqual((403, "forbidden"), (status, value["error"]["code"]))
        status, _value = self.call("GET", "/v1/sync/conversations", bearer=None, desktop=DESKTOP)
        self.assertEqual(200, status)

    # The inherited tests run once in SyncHttpTest; here only the LAN matrix.
    for _name in [name for name in dir(SyncHttpTest) if name.startswith("test_")]:
        locals()[_name] = None
    del _name


def _lan_address() -> Optional[str]:
    for interface in ("en0", "en1"):
        try:
            done = subprocess.run(["ipconfig", "getifaddr", interface], capture_output=True, text=True, timeout=5)
        except (OSError, subprocess.SubprocessError):
            continue
        address = done.stdout.strip()
        if done.returncode == 0 and address:
            return address
    return None


class FakeControl:
    """Duck-typed MacControl: a fixed screenshot (or the locked-screen refusal while ``locked``)."""

    locked = False

    class _Driver:
        @staticmethod
        def executable() -> Optional[str]:
            return None

    driver = _Driver()

    def capabilities(self) -> Dict[str, Any]:
        return {"driver": {"available": False}, "features": {"screenshot": True}}

    def screenshot(self, app: Optional[str], max_side: Optional[int]) -> Dict[str, Any]:
        if self.locked:
            raise mac.MacError(409, "screen_locked", mac.SCREEN_LOCKED_MESSAGE, retryable=True,
                               details={"screenLocked": True})
        return {"mime": "image/jpeg", "base64": base64.b64encode(JPEG).decode(), "width": 1024, "height": 640,
                "bytes": len(JPEG)}

    def close(self) -> None:
        pass


class SseClient:
    def __init__(self, port: int, path: str, headers: Dict[str, str]) -> None:
        self.connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        self.connection.request("GET", path, headers=headers)
        self.response = self.connection.getresponse()
        self.status = self.response.status
        self.headers = {key.lower(): value for key, value in self.response.getheaders()}
        self.buffer = ""

    def read_until(self, predicate, timeout: float = 5.0) -> str:  # noqa: ANN001
        deadline = time.monotonic() + timeout
        sock = self.response.fp.raw._sock if hasattr(self.response.fp, "raw") else None  # noqa: SLF001
        while not predicate(self.buffer):
            if time.monotonic() > deadline:
                raise AssertionError(f"stream timed out; got {self.buffer[-300:]!r}")
            if sock is not None:
                sock.settimeout(max(0.1, deadline - time.monotonic()))
            try:
                chunk = self.response.read1(4096) if hasattr(self.response, "read1") else self.response.read(1)
            except socket.timeout:
                continue
            if not chunk:
                break
            self.buffer += chunk.decode("utf-8")
        return self.buffer

    def close(self) -> None:
        try:  # the response holds its own reference to the socket: close both, or the fd stays open
            self.response.close()
            self.connection.close()
        except OSError:
            pass


if __name__ == "__main__":
    unittest.main()
