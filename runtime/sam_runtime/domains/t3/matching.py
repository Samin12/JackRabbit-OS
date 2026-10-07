"""Forgiving thread/project lookup for voice: ids, id prefixes, or spoken titles."""

from __future__ import annotations

from dataclasses import dataclass
from difflib import SequenceMatcher
import re


_WORD = re.compile(r"[a-z0-9]+")
_STOP = frozenset({"the", "a", "an", "thread", "chat", "task", "one", "my", "that", "about", "on", "for", "in", "of", "to", "project"})
_LATEST = frozenset({"latest", "last", "newest", "recent", "most recent", "current", "that one", "it", "this one", "this"})


@dataclass(frozen=True, slots=True)
class MatchResult:
    item: dict[str, object] | None
    candidates: tuple[dict[str, object], ...] = ()

    @property
    def ambiguous(self) -> bool:
        return self.item is None and len(self.candidates) > 1


def _words(text: str) -> list[str]:
    return [word for word in _WORD.findall(text.lower()) if word not in _STOP]


def _score(query: str, title: str) -> float:
    query_words = _words(query)
    title_words = _words(title)
    if not query_words or not title_words:
        return 0.0
    hits = 0.0
    for word in query_words:
        if word in title_words:
            hits += 1.0
        elif any(candidate.startswith(word) or word.startswith(candidate) for candidate in title_words if min(len(candidate), len(word)) >= 3):
            hits += 0.75
    coverage = hits / len(query_words)
    ratio = SequenceMatcher(None, " ".join(query_words), " ".join(title_words)).ratio()
    return 0.75 * coverage + 0.25 * ratio


def match_item(
    query: str,
    items: list[dict[str, object]],
    *,
    title_key: str = "title",
    id_key: str = "id",
) -> MatchResult:
    """Resolve ``query`` against ``items`` (already ordered by priority/recency)."""
    text = " ".join(str(query or "").split())
    if not text or not items:
        return MatchResult(None)
    lowered = text.lower()
    for item in items:
        if str(item.get(id_key, "")).lower() == lowered:
            return MatchResult(item)
    compact = lowered.replace(" ", "")
    if len(compact) >= 6 and re.fullmatch(r"[0-9a-f-]+", compact):
        prefixed = [item for item in items if str(item.get(id_key, "")).lower().startswith(compact)]
        if len(prefixed) == 1:
            return MatchResult(prefixed[0])
        if len(prefixed) > 1:
            return MatchResult(None, tuple(prefixed[:3]))
    exact = [item for item in items if " ".join(str(item.get(title_key, "")).split()).lower() == lowered]
    if len(exact) == 1:
        return MatchResult(exact[0])
    if len(exact) > 1:
        return MatchResult(exact[0], tuple(exact[:3]))
    if lowered.strip(" .!?") in _LATEST:
        return MatchResult(items[0])
    scored = sorted(
        ((score, index, item) for index, item in enumerate(items) if (score := _score(text, str(item.get(title_key, "")))) > 0),
        key=lambda entry: (-entry[0], entry[1]),
    )
    if not scored or scored[0][0] < 0.45:
        return MatchResult(None, tuple(entry[2] for entry in scored[:3]))
    best = scored[0]
    if len(scored) > 1 and scored[1][0] >= best[0] - 0.08 and scored[1][0] >= 0.45:
        return MatchResult(None, tuple(entry[2] for entry in scored[:3]))
    return MatchResult(best[2])
