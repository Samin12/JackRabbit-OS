"""Server-side verbatim guard for ``journal_add``.

The voice model passes ``text``; the runtime only ever records the *user's*
words, taken from the ASR transcript:

1. If ``text`` is (nearly) a substring of the utterance, record that span of the
   utterance (the user's punctuation and wording, not the model's).
2. Else, if the utterance itself is a journal command, record the utterance
   minus the command phrase ("add to my journal that ...").
3. Else the utterance is missing or stale (Realtime transcription can finish
   after the function call). The caller holds the note and verifies it against
   the full session transcript at session end; unmatched text is never written.
"""

from __future__ import annotations

from difflib import SequenceMatcher
import re
import unicodedata

from .format import clean_words, match_key

_FILLER = r"(?:(?:hey|hi|ok|okay|um+|uh+|so|alright|all right|and)\b[\s,]*)*(?:(?:r1|rabbit|sam|samrabbit)\b[\s,]*)?"
_POLITE = (
    r"(?:(?:please|can you|could you|would you|will you|go ahead and|i want you to|i'd like you to|"
    r"i would like you to|let's|lets)\s+)*"
)
_VERB = r"(?:add|put|save|record|note|write|log|jot|enter|store|keep|make a note|make an entry)"
_OBJECT = r"(?:\s+(?:this|that|it|the following|something|these|this one|a note|an entry|a journal entry|a line))?"
_DOWN = r"(?:\s+down)?"
_PREP = r"(?:\s+(?:to|in|into|on|onto|in to|inside|for))"
_DET = r"(?:\s+(?:my|the|today'?s|our))?"
_NOUN = r"\s+(?:heptabase\s+)?(?:journal|diary)(?:\s+(?:entry|for today|today))?"
_CONNECT = r"(?:\s*(?:that|saying that|saying|which says|reading)\b)?"
_TRAIL = r"[\s:,.;\-–—]*"

_PREFIX = re.compile(rf"^\s*{_FILLER}{_POLITE}{_VERB}{_DOWN}{_OBJECT}{_DOWN}{_PREP}{_DET}{_NOUN}{_CONNECT}{_TRAIL}",
                     re.IGNORECASE)
_PREFIX_SHORT = re.compile(rf"^\s*{_FILLER}{_POLITE}(?:journal|note to self|dear diary){_CONNECT}{_TRAIL}",
                           re.IGNORECASE)
_SUFFIX = re.compile(
    rf"[\s,;:\-–—]*(?:(?:and|please|so|can you|could you|now)\s+)*{_VERB}{_OBJECT}{_DOWN}{_PREP}{_DET}{_NOUN}"
    r"(?:\s+please)?[\s.!?]*$",
    re.IGNORECASE,
)
_INTENT = re.compile(r"\b(?:journal|diary|note to self)\b", re.IGNORECASE)
_EDGE_PUNCT = " \t,;:-–—"
_CLOSERS = ".!?\"”)’'"


def strip_command(text: str) -> tuple[str, bool]:
    """Remove a leading/trailing journal command phrase. Returns (words, stripped?)."""
    value = clean_words(text)
    stripped = False
    for pattern in (_PREFIX, _PREFIX_SHORT):
        match = pattern.match(value)
        if match and match.end() > 0 and match.group(0).strip():
            value = value[match.end():]
            stripped = True
            break
    match = _SUFFIX.search(value)
    if match and match.start() > 0:
        value = value[: match.start()]
        stripped = True
    elif match and match.start() == 0:
        value = ""
        stripped = True
    return value.strip(_EDGE_PUNCT), stripped


def _alnum_map(text: str) -> tuple[str, list[int]]:
    chars: list[str] = []
    positions: list[int] = []
    for index, char in enumerate(text):
        if char.isalnum():
            chars.append(char.lower())
            positions.append(index)
    return "".join(chars), positions


def locate_span(candidate: str, utterance: str) -> str | None:
    """Return the utterance span that the candidate (nearly) quotes, or None."""
    wanted = match_key(candidate)
    source = unicodedata.normalize("NFKC", clean_words(utterance))
    haystack, positions = _alnum_map(source)
    if len(wanted) < 2 or not haystack:
        return None
    found = haystack.find(wanted)
    if found >= 0:
        start, end = found, found + len(wanted)
    elif len(wanted) >= 12:
        matcher = SequenceMatcher(None, wanted, haystack, autojunk=False)
        blocks = [block for block in matcher.get_matching_blocks() if block.size >= 3]
        if not blocks or sum(block.size for block in blocks) < 0.85 * len(wanted):
            return None
        start, end = blocks[0].b, blocks[-1].b + blocks[-1].size
        if end - start > 1.25 * len(wanted) + 4:
            return None
    else:
        return None
    first, last = positions[start], positions[end - 1] + 1
    while first > 0 and (source[first - 1].isalnum()
                         or (source[first - 1] in "'’" and first > 1 and source[first - 2].isalnum())):
        first -= 1
    while last < len(source) and (source[last].isalnum() or (source[last] in "'’" and last + 1 < len(source)
                                                             and source[last + 1].isalnum())):
        last += 1
    while last < len(source) and source[last] in _CLOSERS:
        last += 1
    span = source[first:last].strip(_EDGE_PUNCT)
    return span or None


def verify_words(candidate: str, utterance: str | None) -> tuple[str | None, str]:
    """Return (user words, basis) with basis 'matched' | 'utterance' | 'unverified'."""
    if utterance and utterance.strip():
        span = locate_span(candidate, utterance)
        if span:
            words, _ = strip_command(span)
            if words:
                return words, "matched"
        if _INTENT.search(utterance):
            words, stripped = strip_command(utterance)
            if stripped and words:
                return words, "utterance"
    return None, "unverified"


def utterance_consumed(utterance: str, used_key: str | None) -> bool:
    """True when ``utterance`` is the one a recorded note came from (the voice
    header truncates utterances at 500 chars, so allow a long-prefix match)."""
    if not used_key:
        return False
    key = match_key(utterance)
    return key == used_key or (len(used_key) >= 20 and key.startswith(used_key))
