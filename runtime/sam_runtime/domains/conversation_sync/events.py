"""Conversation-sync event shapes: validation of R1 events, redaction, and runtime-made events.

Shared identifiers (CONTRACTS-WAVE3): ``conversationId`` is ``c_`` + 20 hex from the R1
(``s_<voiceSessionId>`` for a session the R1 never linked to a conversation), event ids are
``<conversationId>:<seq>`` (R1), ``rt:<voiceSessionId>:<toolCallId>`` (runtime) or
``mac:<uuid>`` (bridge), blob ids are ``sha256:<hex>`` of the bytes. Every event carries
``id, type, conversationId, at`` (epoch ms) and usually ``sessionId, seq, origin``.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
import hashlib
import json
import re

from sam_runtime.domains.heptabase_journal.format import scrub_secrets

CONVERSATION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{2,63}$")
SESSION_ID = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
EVENT_ID = re.compile(r"^[\x21-\x7e]{1,200}$")
EVENT_TYPE = re.compile(r"^[a-z][a-z0-9_]{0,31}(?:\.[a-z0-9_]{1,32}){0,4}$")
BLOB_ID = re.compile(r"^sha256:[0-9a-f]{64}$")
TOOL_CALL_ID = re.compile(r"^[A-Za-z0-9_.:-]{1,64}$")
_BASE64 = re.compile(r"^[A-Za-z0-9+/_-]+={0,2}$")
IMAGE_MIMES = frozenset({"image/jpeg", "image/png", "image/webp", "image/gif"})

MAX_EVENTS_PER_BATCH = 500
MAX_EVENT_BYTES = 64 * 1024
MAX_TOOL_RESULT_BYTES = 4096
MAX_TOOL_ARGUMENT_BYTES = 8192
MAX_FINALIZED_ENTRY_BYTES = 48 * 1024
DELTA = "message.assistant.delta"
DRAFT_ENDS = frozenset({"message.assistant.done", "message.assistant.interrupted"})

# Never scrubbed or rewritten: identifiers and enum-like fields.
_IDENTITY_KEYS = frozenset({
    "id", "type", "conversationId", "sessionId", "seq", "at", "origin", "blobId", "imageBlobId", "mime", "source",
    "messageId", "toolCallId", "eventType", "artifactId", "announcementId", "utteranceId", "role", "status",
    "reason", "width", "height", "bytes", "isError", "interrupted", "reconnect", "tool",
})
_SECRET_KEYS = frozenset({
    "password", "passcode", "secret", "token", "api_key", "apikey", "authorization", "credential",
    "private_key", "seed_phrase", "cvv", "access_token", "refresh_token", "bearer",
})


@dataclass(frozen=True, slots=True)
class OutgoingEvent:
    """One event ready for the outbox (payload already redacted and bounded)."""

    event_id: str
    conversation_id: str
    session_id: str | None
    seq: int | None
    kind: str
    payload: dict[str, object]
    event_at: int
    blob_id: str | None = None  # a blob this runtime holds and must send first
    coalesce_key: str | None = None

    def payload_json(self) -> str:
        return json.dumps(self.payload, separators=(",", ":"), ensure_ascii=False)


class Rejected(ValueError):
    """An event that is malformed (counted as rejected, never stored)."""


def blob_id_for(data: bytes) -> str:
    return "sha256:" + hashlib.sha256(data).hexdigest()


def valid_conversation_id(value: object) -> str | None:
    return value if isinstance(value, str) and CONVERSATION_ID.match(value) else None


def valid_session_id(value: object) -> str | None:
    return value if isinstance(value, str) and SESSION_ID.match(value) else None


def valid_blob_id(value: object) -> str | None:
    return value if isinstance(value, str) and BLOB_ID.match(value) else None


def valid_tool_call_id(value: object) -> str | None:
    return value if isinstance(value, str) and TOOL_CALL_ID.match(value) else None


def redact(value: object, *, scrub: bool, key: str | None = None) -> object:
    """Key-based secret redaction (always), inline image data dropped (always), and the
    journal's value-pattern scrub on free text (when ``scrub``)."""
    if isinstance(value, dict):
        result: dict[str, object] = {}
        for item_key, item in value.items():
            name = str(item_key)
            if name.lower() in _SECRET_KEYS:
                result[name] = "[REDACTED]"
            elif isinstance(item, str) and len(item) > 256 and (
                    name == "base64" or (name in ("data", "dataUrl") and _BASE64.match(item))):
                result[name] = f"[{len(item)} chars of binary data omitted]"
            else:
                result[name] = redact(item, scrub=scrub, key=name)
        return result
    if isinstance(value, (list, tuple)):
        return [redact(item, scrub=scrub, key=key) for item in value]
    if isinstance(value, str):
        if value.startswith("data:") and ";base64," in value[:64]:
            return f"[{len(value)} chars of binary data omitted]"
        if scrub and key not in _IDENTITY_KEYS:
            return scrub_secrets(value)
    return value


def clip_utf8(text: str, limit: int) -> tuple[str, bool]:
    encoded = text.encode("utf-8")
    if len(encoded) <= limit:
        return text, False
    return encoded[:limit].decode("utf-8", errors="ignore"), True


def _int(value: object) -> int | None:
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, float) and value.is_integer():
        return int(value)
    return None


def device_event(raw: object, *, conversation_id: str, session_id: str | None, now_ms: int,
                 scrub: bool, local_blob: Callable[[str], bool] = lambda _blob_id: False) -> OutgoingEvent:
    """Validate and normalize one event from the R1 (hop 1). Raises ``Rejected``."""
    if not isinstance(raw, dict):
        raise Rejected("not_an_object")
    event_id = raw.get("id")
    if not isinstance(event_id, str) or not EVENT_ID.match(event_id):
        raise Rejected("invalid_id")
    kind = raw.get("type")
    if not isinstance(kind, str) or not EVENT_TYPE.match(kind):
        raise Rejected("invalid_type")
    given = raw.get("conversationId", conversation_id)
    if given != conversation_id:
        raise Rejected("conversation_mismatch")
    event_session = raw.get("sessionId") or session_id
    if event_session is not None and not valid_session_id(event_session):
        raise Rejected("invalid_session")
    at = _int(raw.get("at"))
    if at is None or at <= 0:
        at = now_ms
    seq = _int(raw.get("seq"))
    payload = redact(raw, scrub=scrub)
    assert isinstance(payload, dict)
    payload.update({"id": event_id, "type": kind, "conversationId": conversation_id, "at": at})
    if event_session:
        payload["sessionId"] = event_session
    else:
        payload.pop("sessionId", None)
    payload.setdefault("origin", _default_origin(kind))
    blob_id = valid_blob_id(raw.get("blobId"))
    event = OutgoingEvent(
        event_id=event_id, conversation_id=conversation_id, session_id=event_session or None, seq=seq,
        kind=kind, payload=payload, event_at=at,
        blob_id=blob_id if blob_id is not None and local_blob(blob_id) else None,
        coalesce_key=_coalesce_key(conversation_id, kind, raw.get("messageId")),
    )
    if len(event.payload_json().encode("utf-8")) > MAX_EVENT_BYTES:
        raise Rejected("too_large")
    return event


def _coalesce_key(conversation_id: str, kind: str, message_id: object) -> str | None:
    """Assistant drafts carry the whole text so far: only the newest one (or the final
    message) of a message ever needs to go out."""
    if (kind == DELTA or kind in DRAFT_ENDS) and isinstance(message_id, str) and 0 < len(message_id) <= 128:
        return f"draft:{conversation_id}:{message_id}"
    return None


def _default_origin(kind: str) -> str:
    if kind == "message.user":
        return "user"
    if kind.startswith("message.assistant") or kind.startswith("card."):
        return "model"
    return "host"


def tool_event(*, conversation_id: str, session_id: str, tool_call_id: str, name: str,
               arguments: dict[str, object], text: str, is_error: bool, utterance_id: int | None,
               at: int, scrub: bool, blob_id: str | None = None, local_blob: bool = False) -> OutgoingEvent:
    redacted_arguments = redact(arguments, scrub=scrub)
    arguments_json = json.dumps(redacted_arguments, separators=(",", ":"), ensure_ascii=False)
    if len(arguments_json.encode("utf-8")) > MAX_TOOL_ARGUMENT_BYTES:
        redacted_arguments = {"_truncated": clip_utf8(arguments_json, MAX_TOOL_ARGUMENT_BYTES)[0]}
    result_text = scrub_secrets(text) if scrub else text
    result_text, truncated = clip_utf8(result_text, MAX_TOOL_RESULT_BYTES)
    payload: dict[str, object] = {
        "id": f"rt:{session_id}:{tool_call_id}", "type": "tool.completed", "conversationId": conversation_id,
        "sessionId": session_id, "at": at, "origin": "model", "tool": name, "arguments": redacted_arguments,
        "result": result_text, "isError": bool(is_error), "toolCallId": tool_call_id,
    }
    if truncated:
        payload["resultTruncated"] = True
    if utterance_id is not None:
        payload["utteranceId"] = utterance_id
    if blob_id is not None:
        payload["blobId"] = blob_id
    return OutgoingEvent(payload["id"], conversation_id, session_id, None, "tool.completed", payload, at,
                         blob_id=blob_id if local_blob else None)


def image_event(*, event_id: str, conversation_id: str, session_id: str | None, blob_id: str, mime: str,
                size: int | None, width: object, height: object, source: str, at: int,
                local_blob: bool, tool_call_id: str | None = None) -> OutgoingEvent:
    payload: dict[str, object] = {
        "id": event_id, "type": "image", "conversationId": conversation_id, "at": at, "origin": "host",
        "source": source, "blobId": blob_id, "mime": mime,
    }
    if session_id:
        payload["sessionId"] = session_id
    for key, value in (("width", width), ("height", height), ("bytes", size)):
        number = _int(value)
        if number is not None and number >= 0:
            payload[key] = number
    if tool_call_id:
        payload["toolCallId"] = tool_call_id
    return OutgoingEvent(event_id, conversation_id, session_id, None, "image", payload, at,
                         blob_id=blob_id if local_blob else None)


def finalized_event(*, conversation_id: str, session_id: str, entries: list[object], summary: str | None,
                    memory_count: int | None, reviewed: bool, at: int, scrub: bool,
                    include_assistant: bool) -> OutgoingEvent:
    """``session.finalized``: the provider session ended and its transcript reached memory.

    ``entries`` repeats the finalized transcript (user/assistant lines with the R1's ``at``) so a
    session whose live events never arrived (older R1 app, lost batch) is still readable on the
    Mac. Desktops should show them only for sessions without live ``message.*`` events."""
    kept: list[dict[str, object]] = []
    size = 0
    truncated = False
    for item in entries:
        if not isinstance(item, dict):
            continue
        role = str(item.get("role", "")).strip()
        text = str(item.get("text", "")).strip()
        if role not in ("user", "assistant") or not text or (role == "assistant" and not include_assistant):
            continue
        text = scrub_secrets(text) if scrub else text
        entry: dict[str, object] = {"role": role, "eventType": str(item.get("eventType", ""))[:96], "text": text}
        entry_at = _int(item.get("at"))
        if entry_at is not None and entry_at > 0:
            entry["at"] = entry_at
        cost = len(json.dumps(entry, ensure_ascii=False).encode("utf-8"))
        if size + cost > MAX_FINALIZED_ENTRY_BYTES:
            truncated = True
            break
        kept.append(entry)
        size += cost
    payload: dict[str, object] = {
        "id": f"rt:{session_id}:finalized", "type": "session.finalized", "conversationId": conversation_id,
        "sessionId": session_id, "at": at, "origin": "host", "reviewed": reviewed, "entryCount": len(kept),
        "entries": kept,
    }
    if truncated:
        payload["entriesTruncated"] = True
    if summary:
        payload["summary"] = clip_utf8(scrub_secrets(summary) if scrub else summary, 4000)[0]
    if memory_count is not None:
        payload["memoryCount"] = int(memory_count)
    return OutgoingEvent(payload["id"], conversation_id, session_id, None, "session.finalized", payload, at)
