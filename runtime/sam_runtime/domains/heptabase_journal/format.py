"""Hepta Markdown rendering for journal entries.

Principle: the user's words are written verbatim, only escaped so Markdown and
Hepta tags cannot fire. Everything the R1 adds is a short factual label (time,
"R1 voice", a templated action line). There is no model-written prose here.
"""

from __future__ import annotations

from dataclasses import dataclass
import re
import unicodedata

MAX_CALL_BYTES = 16 * 1024
_SESSION_CHUNK_BYTES = 15 * 1024

_INLINE = re.compile(r"([\\`*_~\[\]<>|$])")
_ENTITY = re.compile(r"&(?=#?[A-Za-z0-9]+;)")
_ORDERED_START = re.compile(r"^(\d+)([.)])")
_BLOCK_START = frozenset("#>+-=")
# Formatting tags we emit (hepta-color) or a server might echo back; escaped
# user text (``\<b\>``) is never matched thanks to the look-behind.
_FORMAT_TAG = re.compile(
    r"(?<!\\)</?(?:hepta-[a-z-]+|span|font|mark|strong|em|b|i|u|s|del|code)(?:\s[^<>]*)?>",
    re.IGNORECASE,
)
_LINE_PREFIX = re.compile(r"^\d+\t")
_JOURNAL_HEADER = re.compile(r"^journal \[\d{4}-\d{2}-\d{2}\] \d+ lines?$")
_ESCAPED = re.compile(r"\\([!-/:-@\[-`{-~])")

# Value-pattern secret scrub (memory/evidence redaction is key-based only).
_SECRET_PATTERNS = (
    re.compile(r"\b(?:sk|rk|pk)_(?:live|test)_[A-Za-z0-9]{6,}"),
    re.compile(r"\bsk-(?:proj-|ant-)?[A-Za-z0-9_\-]{20,}"),
    re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\bAIza[0-9A-Za-z_\-]{35}"),
    re.compile(r"\bxox[abposr]-[A-Za-z0-9\-]{10,}"),
    re.compile(r"\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"),
    re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/\-]{16,}=*"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
)
_CODE_AFTER = re.compile(
    r"(?i)\b((?:verification|security|login|one[- ]time|2fa|auth|access|confirmation)?\s*"
    r"(?:code|pin|passcode|otp)\s*(?:is|was|=|:)?\s*)(\d(?:[\s-]?\d){3,7})\b"
)
_PASSWORD_AFTER = re.compile(r"(?i)\b(password\s*(?:is|was|=|:)\s*)([^\s,;!?]+)")
REDACTED = "[redacted]"


def scrub_secrets(text: str) -> str:
    value = text
    for pattern in _SECRET_PATTERNS:
        value = pattern.sub(REDACTED, value)
    value = _CODE_AFTER.sub(lambda match: match.group(1) + REDACTED, value)
    value = _PASSWORD_AFTER.sub(lambda match: match.group(1) + REDACTED, value)
    return value


def clean_words(text: str) -> str:
    """Normalize whitespace only (ASR output is one line; words are untouched)."""
    return " ".join(str(text).replace("\r", "\n").split())


def escape_verbatim(text: str, *, line_start: bool = False) -> str:
    """Backslash-escape Markdown / Hepta syntax so spoken text renders literally.

    Heptabase stores literal underscores as ``\\_`` (seen in read_journal_range),
    so CommonMark backslash escapes round-trip.
    """
    value = clean_words(text)
    value = _INLINE.sub(r"\\\1", value)
    value = _ENTITY.sub(r"\\&", value)
    value = value.replace("{{", "{\\{")
    if line_start and value:
        ordered = _ORDERED_START.match(value)
        if ordered:
            value = f"{ordered.group(1)}\\{ordered.group(2)}{value[ordered.end():]}"
        elif value[0] in _BLOCK_START:
            value = "\\" + value
    return value


def unescape_markdown(text: str) -> str:
    return _ESCAPED.sub(r"\1", text)


def match_key(text: str) -> str:
    """Normalized comparison key: tags, backslashes, punctuation and case removed."""
    value = unicodedata.normalize("NFKC", str(text))
    value = _FORMAT_TAG.sub(" ", value)
    value = value.replace("\\", "")
    return "".join(ch.lower() for ch in value if ch.isalnum())


def fingerprint(content: str) -> str:
    return match_key(content)[:48]


def journal_plain_text(raw: str) -> str:
    """Strip read_journal_range framing: day headers and ``<n>\\t`` line prefixes."""
    lines = []
    for line in str(raw).splitlines():
        if _JOURNAL_HEADER.match(line.strip()):
            continue
        lines.append(_LINE_PREFIX.sub("", line))
    return "\n".join(lines)


def journal_contains(raw_journal: str, entry_fingerprint: str) -> bool:
    if not entry_fingerprint:
        return False
    return entry_fingerprint in match_key(journal_plain_text(raw_journal))


@dataclass(frozen=True, slots=True)
class Rendered:
    content: str
    plain_content: str

    @property
    def size(self) -> int:
        return len(self.content.encode("utf-8"))


@dataclass(frozen=True, slots=True)
class SessionLine:
    """One line of a session block. ``kind`` is user | activity | assistant."""

    sort_at: float | None
    time_label: str | None
    kind: str
    text: str


# No look-behind for a backslash: escaped text always writes ``>`` as ``\>``, so it can never
# form either tag, while a look-behind would skip a closing tag after text ending in ``\\``.
_GRAY_SPAN = re.compile(r'<hepta-color type="text" color="gray">(.*?)</hepta-color>', re.DOTALL)


def cli_markdown(content: str) -> str:
    """Hepta Markdown -> the plain Markdown the desktop CLI accepts (Mac bridge).

    ``heptabase journal append`` parses CommonMark plus Heptabase's list/mention
    extensions but not ``<hepta-color>``; it would keep the tag as literal text.
    Gray action lines become italic instead. Inner text is already escaped, so
    the ``*`` delimiters cannot be closed early by the user's words.
    """
    return _GRAY_SPAN.sub(lambda match: f"*{match.group(1)}*" if match.group(1).strip() else "", content)


def _gray(text: str) -> str:
    return f'<hepta-color type="text" color="gray">{text}</hepta-color>'


def render_note(time_label: str, words: str) -> Rendered:
    """Explicit 'add this to my journal': one paragraph, the user's words only."""
    body = escape_verbatim(words)
    return Rendered(f"**{time_label}** {body}", f"{time_label} {body}")


def render_activity(time_label: str, text: str) -> Rendered:
    """Standalone factual action line (out-of-session events)."""
    body = escape_verbatim(text)
    return Rendered(f"- {time_label} {_gray('↳ ' + body)}", f"{time_label} ↳ {body}")


def session_header(start_label: str | None, end_label: str | None) -> str:
    if start_label and end_label and start_label != end_label:
        return f"R1 voice · {start_label}–{end_label}"
    if start_label or end_label:
        return f"R1 voice · {start_label or end_label}"
    return "R1 voice"


def _session_line(line: SessionLine) -> tuple[str, str]:
    prefix = f"{line.time_label} " if line.time_label else ""
    body = escape_verbatim(line.text, line_start=not prefix)
    if line.kind == "activity":
        return f"- {prefix}{_gray('↳ ' + body)}", f"{prefix}↳ {body}"
    if line.kind == "assistant":
        return f"- {prefix}{_gray('R1: ' + body)}", f"{prefix}R1: {body}"
    return f"- {prefix}{body}", f"{prefix}{body}"


def render_session(header: str, lines: list[SessionLine]) -> list[Rendered]:
    """Session block(s), blocks separated by blank lines (the user's own bullet style).

    Splits into several blocks when one would exceed the per-call budget.
    """
    rendered = [_session_line(line) for line in lines]
    chunks: list[list[tuple[str, str]]] = [[]]
    size = 0
    for item in rendered:
        item_size = len(item[0].encode("utf-8")) + 2
        if chunks[-1] and size + item_size > _SESSION_CHUNK_BYTES:
            chunks.append([])
            size = 0
        chunks[-1].append(item)
        size += item_size
    total = len(chunks)
    blocks = []
    for index, chunk in enumerate(chunks, start=1):
        title = header if total == 1 else f"{header} ({index}/{total})"
        rich = [f"**{escape_verbatim(title)}**", *[item[0] for item in chunk]]
        plain = [escape_verbatim(title), *[item[1] for item in chunk]]
        blocks.append(Rendered("\n\n".join(rich), "\n\n".join(plain)))
    return blocks
