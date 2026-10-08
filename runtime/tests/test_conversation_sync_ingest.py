"""Hop 1 ingest (R1 app -> runtime): validation, dedupe, draft coalescing, settings and redaction."""

from __future__ import annotations

import json
import unittest

from conversation_sync_fakes import TINY_JPEG, SyncHarness

from sam_runtime.domains.conversation_sync.events import blob_id_for

CONV = "c_0123456789abcdef0123"
SESSION = "ab" * 12


def ev(seq: int, kind: str = "message.user", **fields: object) -> dict[str, object]:
    value: dict[str, object] = {"id": f"{CONV}:{seq}", "seq": seq, "type": kind, "at": 1_760_000_000_000 + seq}
    value.update(fields)
    return value


class ConversationSyncIngestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.h = SyncHarness()
        self.addCleanup(self.h.close)
        self.service = self.h.service

    def ingest(self, events: list[object], **body: object) -> dict[str, object]:
        return self.service.ingest_events({"conversationId": CONV, "sessionId": SESSION, "events": events, **body})

    def test_events_are_stored_once_by_id(self) -> None:
        first = self.ingest([ev(1, text="hello"), ev(2, "conversation.started")])
        self.assertEqual({"accepted": 2, "duplicates": 0, "rejected": 0, "skipped": 0, "syncing": True}, first)
        again = self.ingest([ev(1, text="hello"), ev(2, "conversation.started"), ev(3, text="new")])
        self.assertEqual((1, 2), (again["accepted"], again["duplicates"]))
        payloads = self.h.payloads()
        self.assertEqual([f"{CONV}:1", f"{CONV}:2", f"{CONV}:3"], [item["id"] for item in payloads])
        self.assertEqual({"conversationId": CONV, "sessionId": SESSION, "origin": "user"},
                         {key: payloads[0][key] for key in ("conversationId", "sessionId", "origin")})
        self.assertEqual("host", payloads[1]["origin"])
        self.assertEqual(CONV, self.service.conversation_for(SESSION), "the batch also links the session")

    def test_malformed_batches_raise_and_bad_events_are_counted(self) -> None:
        for body in ({"conversationId": "x", "events": []}, {"conversationId": CONV, "events": "nope"},
                     {"conversationId": CONV, "sessionId": "bad id!", "events": []},
                     {"conversationId": CONV, "events": [ev(1)] * 501}):
            with self.subTest(body=str(body)[:60]):
                with self.assertRaises(ValueError):
                    self.service.ingest_events(body)
        result = self.ingest([ev(1, text="ok"), "nope", {"id": "", "type": "message.user"}, ev(2, "Bad Type"),
                              ev(3, conversationId="c_other0000000000000000"), ev(4, text="x" * 70_000),
                              ev(5, sessionId="not valid!")])
        self.assertEqual((1, 6), (result["accepted"], result["rejected"]))

    def test_missing_at_uses_now_and_session_comes_from_the_batch(self) -> None:
        self.ingest([{"id": f"{CONV}:9", "type": "message.user", "text": "hi"}], sessionId="")
        (payload,) = self.h.payloads()
        self.assertEqual(int(self.h.clock.now * 1000), payload["at"])
        self.assertNotIn("sessionId", payload)

    def test_assistant_drafts_are_coalesced(self) -> None:
        self.ingest([ev(seq, "message.assistant.delta", messageId="a_1", text="draft " * seq) for seq in (1, 2, 3)])
        self.assertEqual([3], [item["seq"] for item in self.h.payloads()])
        older = self.ingest([ev(2, "message.assistant.delta", messageId="a_1", text="draft")])
        self.assertEqual(1, older["duplicates"])
        self.ingest([ev(4, "message.assistant.done", messageId="a_1", text="Final words.")])
        late = self.ingest([ev(5, "message.assistant.delta", messageId="a_1", text="Final")])
        self.assertEqual(1, late["duplicates"])
        self.assertEqual(["message.assistant.done"], [item["type"] for item in self.h.payloads()])
        self.ingest([ev(6, "message.assistant.delta", messageId="a_2", text="other message")])
        self.assertEqual(["message.assistant.done", "message.assistant.delta"],
                         [item["type"] for item in self.h.payloads()])

    def test_settings_filter_and_redact(self) -> None:
        self.ingest([ev(1, text="my password is hunter2 and the code is 4 8 1 5"),
                     ev(2, "card.shown", card={"title": "Keys", "token": "abc", "rows": ["sk-proj-" + "a" * 30]}),
                     ev(3, "host.note", text="Bearer abcdefghijklmnopqrstuvwxyz0123")])
        first, card, note = self.h.payloads()
        self.assertNotIn("hunter2", first["text"])
        self.assertNotIn("4 8 1 5", first["text"])
        self.assertEqual("[REDACTED]", card["card"]["token"])
        self.assertNotIn("sk-proj-", json.dumps(card))
        self.assertNotIn("abcdefghijklmnop", note["text"])
        self.service.save_settings({"redactSecrets": False, "includeAssistant": False, "includeImages": False})
        result = self.ingest([ev(4, text="password is hunter2"), ev(5, "message.assistant.done", text="hi",
                                                                       messageId="a_1"),
                              ev(6, "image", blobId=blob_id_for(TINY_JPEG), mime="image/jpeg", source="camera")])
        self.assertEqual((1, 2), (result["accepted"], result["skipped"]))
        self.assertIn("hunter2", self.h.payloads()[-1]["text"])
        with self.assertRaises(ValueError):
            self.service.save_settings({"enabled": "yes"})
        with self.assertRaises(ValueError):
            self.service.save_settings({"bogus": True})

    def test_nothing_is_kept_when_sync_is_off_or_the_mac_is_not_connected(self) -> None:
        self.service.save_settings({"enabled": False})
        self.assertEqual({"accepted": 0, "duplicates": 0, "rejected": 0, "skipped": 1, "syncing": False},
                         self.ingest([ev(1, text="hi")]))
        self.service.save_settings({"enabled": True})
        self.h.store.clear()
        self.assertFalse(self.ingest([ev(2, text="hi")])["syncing"])
        self.assertEqual([], self.h.rows())
        self.assertFalse(self.service.ingest_blob(TINY_JPEG, "image/jpeg", CONV)["stored"])

    def test_blobs_by_content_hash(self) -> None:
        result = self.service.ingest_blob(TINY_JPEG, "image/jpeg; charset=binary", CONV)
        self.assertEqual({"blobId": blob_id_for(TINY_JPEG), "bytes": len(TINY_JPEG), "mime": "image/jpeg",
                          "stored": True}, result)
        self.service.ingest_blob(TINY_JPEG, "image/jpeg")
        (row,) = self.h.blob_rows()
        self.assertEqual((CONV, "pending", TINY_JPEG), (row["conversation_id"], row["state"], bytes(row["data"])))
        for data, mime, conversation in ((TINY_JPEG, "text/html", None), (b"", "image/jpeg", None),
                                         (b"x" * (400 * 1024 + 1), "image/jpeg", None),
                                         (TINY_JPEG, "image/png", "bad id!")):
            with self.subTest(mime=mime, size=len(data)):
                with self.assertRaises(ValueError):
                    self.service.ingest_blob(data, mime, conversation)
        image = ev(7, "image", blobId=result["blobId"], mime="image/jpeg", source="camera")
        self.ingest([image])
        self.assertEqual(result["blobId"], self.h.rows()[-1]["blob_id"], "the event waits for its local blob")
        unknown = ev(8, "image", blobId="sha256:" + "0" * 64, mime="image/jpeg", source="camera")
        self.ingest([unknown])
        self.assertIsNone(self.h.rows()[-1]["blob_id"], "a blob this runtime never had is not waited on")

    def test_link_call_is_authoritative_and_unlinked_sessions_get_their_own_conversation(self) -> None:
        self.assertEqual("s_" + SESSION, self.service.conversation_for(SESSION))
        self.service.link_call(CONV, SESSION)
        self.assertEqual(CONV, self.service.conversation_for(SESSION))
        self.service.ingest_events({"conversationId": "c_ffffffffffffffffffff", "sessionId": SESSION, "events": []})
        self.assertEqual(CONV, self.service.conversation_for(SESSION), "a later batch does not re-link")
        self.service.link_call("bad!", "x" * 24)
        self.service.link_call(CONV, None)


if __name__ == "__main__":
    unittest.main()
