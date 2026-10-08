"""Where a new T3 thread goes when the voice orchestrator starts one.

Shared by ``t3_new_thread`` and ``mac_task``. In order:

1. an explicit project the user named (fuzzy title match; an unknown name stays an error);
2. a project whose title, folder name or repository name appears in the request;
3. general computer or life tasks go to the **orchestration project** (``mac_task`` always counts
   as one);
4. otherwise (coding work, or nothing to go on) the most recently active non-scratch project.

The orchestration project is a non-secret setting in ``provider_settings``. Unset (or pointing at
a project that is gone), it defaults to T3's own agent workspace project (a folder like
``~/.t3/<agent>/workspace``: the user's general-purpose agent, not a code repository), else the
most recently active non-scratch project.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import re
import sqlite3

from sam_runtime.storage.database import RuntimeDatabase

from .service import T3InvalidRequest, T3Service

SETTING_KEY = "t3.orchestration_project_id"
SETTING = "setting"
DEFAULT = "default"
NAMED = "named"
MENTIONED = "mentioned"
ORCHESTRATION = "orchestration"
RECENT = "recent"

_AGENT_WORKSPACE = re.compile(r"/\.t3/(?!scratch(?:/|$))[^/]+/workspace/?$")
_WORD = re.compile(r"[a-z0-9]+")
# Folder and filler words that must never pull a request into a project by themselves.
_GENERIC = frozenset({
    "workspace", "project", "projects", "home", "documents", "document", "desktop", "downloads", "download",
    "scratch", "code", "src", "app", "apps", "music", "pictures", "movies", "library", "users", "user", "tmp",
    "notes", "files", "folder", "work", "test", "tests", "new", "main", "default", "repo", "repos", "web",
})
_CODING = re.compile(
    r"\b(code|coding|codebase|bug|bugs|debug\w*|refactor\w*|repo|repos|repository|commit\w*|branch\w*|merge\w*|"
    r"rebase|pull request|pr|prs|tests?|unit tests?|test suite|build|builds|compile\w*|deploy\w*|function|functions|"
    r"class|classes|module|modules|implement\w*|api|apis|endpoint\w*|lint\w*|typescript|javascript|python|swift|"
    r"kotlin|java|rust|golang|react|css|html|sql|schema|migration\w*|stack ?trace|exception|crash\w*|npm|pnpm|"
    r"gradle|xcode|git|github|ci|pipeline|docker|backend|frontend|unit|regression|typecheck\w*|stack)\b")
_GENERAL = re.compile(
    r"\b(open|close|quit|launch|browser|chrome|safari|aside|website|web ?site|web ?page|tab|tabs|youtube|google|"
    r"search for|look up|find me|email|emails|e-mail|mail|inbox|gmail|calendar|meeting|meetings|schedule|"
    r"remind\w*|message|messages|imessage|slack|whatsapp|telegram|discord|zoom|spotify|music|play|playlist|"
    r"heptabase|notion|journal|book|booking|buy|order|purchase|shop\w*|flight|hotel|restaurant|trip|travel|"
    r"downloads|folder|folders|desktop|screenshot|organi[sz]e|clean up|summari[sz]e|research|draft|reply|"
    r"tweet|invoice|spreadsheet|excel|slides|deck|pdf|photo|photos|video|videos|wifi|bluetooth|volume|"
    r"computer|mac)\b")


@dataclass(frozen=True, slots=True)
class Placement:
    project: dict[str, object]
    reason: str

    @property
    def project_id(self) -> str:
        return str(self.project["id"])

    @property
    def title(self) -> str:
        return str(self.project.get("title") or "Project")


class OrchestrationSetting:
    """The chosen orchestration project id (non-secret, one ``provider_settings`` row)."""

    def __init__(self, database: RuntimeDatabase) -> None:
        self._database = database

    def get(self) -> str | None:
        try:
            with self._database.connect() as connection:
                row = connection.execute("SELECT setting_value FROM provider_settings WHERE setting_key = ?",
                                         (SETTING_KEY,)).fetchone()
        except sqlite3.Error:
            return None
        value = str(row[0]).strip() if row and row[0] is not None else ""
        return value or None

    def set(self, project_id: str | None) -> None:
        now = datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")
        with self._database.connect() as connection:
            if project_id:
                connection.execute(
                    "INSERT INTO provider_settings(setting_key, setting_value, updated_at) VALUES (?, ?, ?) "
                    "ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value, "
                    "updated_at = excluded.updated_at",
                    (SETTING_KEY, project_id, now),
                )
            else:
                connection.execute("DELETE FROM provider_settings WHERE setting_key = ?", (SETTING_KEY,))
            connection.commit()


def words(text: object) -> list[str]:
    return _WORD.findall(str(text or "").lower().replace("’", "'"))


def is_general_task(text: str) -> bool:
    """A computer or life task rather than coding work (more general signals than coding ones)."""
    lowered = " ".join(words(text))
    general = len(_GENERAL.findall(lowered))
    coding = len(_CODING.findall(lowered))
    return general > coding


def _names(record: dict[str, object]) -> list[str]:
    names = [str(record.get("title") or "")]
    root = str(record.get("workspaceRoot") or "").rstrip("/")
    if root:
        names.append(root.rsplit("/", 1)[-1])
    identity = record.get("repository")
    if isinstance(identity, dict):
        for key in ("name", "repo", "repository", "displayName"):
            if isinstance(identity.get(key), str):
                names.append(identity[key])
        for key in ("remote", "url", "remoteUrl", "originUrl"):
            value = identity.get(key)
            if isinstance(value, str) and value.strip():
                names.append(value.rstrip("/").rsplit("/", 1)[-1].removesuffix(".git"))
    result = []
    for name in names:
        phrase = " ".join(words(name))
        if len(phrase.replace(" ", "")) >= 3 and phrase not in _GENERIC and phrase not in result:
            result.append(phrase)
    return result


def mentioned_project(text: str, records: list[dict[str, object]]) -> dict[str, object] | None:
    """The project whose name appears in ``text`` as whole words (longest name wins, then recency)."""
    haystack = " " + " ".join(words(text)) + " "
    best: tuple[int, dict[str, object]] | None = None
    for record in records:
        if record.get("scratch"):
            continue
        for phrase in _names(record):
            if f" {phrase} " in haystack and (best is None or len(phrase) > best[0]):
                best = (len(phrase), record)
    return best[1] if best else None


class ProjectPlacement:
    def __init__(self, service: T3Service, setting: OrchestrationSetting) -> None:
        self._service = service
        self._setting = setting

    # ------------------------------------------------------------------ orchestration project
    def orchestration(self, records: list[dict[str, object]] | None = None) -> tuple[dict[str, object] | None, str]:
        records = self._service.project_records() if records is None else records
        chosen = self._setting.get()
        if chosen:
            for record in records:
                if record["id"] == chosen and not record.get("scratch"):
                    return record, SETTING
        usable = [record for record in records if not record.get("scratch")]
        for record in usable:
            if _AGENT_WORKSPACE.search(str(record.get("workspaceRoot") or "")):
                return record, DEFAULT
        if usable:
            return usable[0], DEFAULT
        return (records[0] if records else None), DEFAULT

    def management_view(self) -> dict[str, object]:
        """From the in-memory snapshot only (never waits on the Mac)."""
        records = self._service.project_records()
        record, source = self.orchestration(records)
        return {
            "projectId": record["id"] if record else self._setting.get(),
            "projectTitle": record["title"] if record else None,
            "source": source,
            "projects": [{"id": item["id"], "title": item["title"]} for item in records if not item.get("scratch")],
            "rules": "Named project, then a project the request mentions, then general computer tasks go to the "
                     "orchestration project, otherwise the most recently active project.",
        }

    def set_orchestration(self, project_id: object) -> dict[str, object]:
        if project_id is None or (isinstance(project_id, str) and not project_id.strip()):
            self._setting.set(None)
            return self.management_view()
        if not isinstance(project_id, str):
            raise T3InvalidRequest("orchestrationProjectId must be a project id or null.")
        self._service.ensure_snapshot(required=True)
        wanted = project_id.strip()
        for record in self._service.project_records():
            if record["id"] == wanted:
                if record.get("scratch"):
                    raise T3InvalidRequest("The scratch project cannot be the orchestration project.")
                self._setting.set(wanted)
                return self.management_view()
        raise T3InvalidRequest("That project was not found in T3 Code.")

    # ------------------------------------------------------------------ choosing
    def choose(self, request: str, *, project: str | None = None, computer: bool = False) -> Placement:
        self._service.ensure_snapshot(required=True)
        records = self._service.project_records()
        if not records:
            raise T3InvalidRequest("T3 Code has no projects yet. Add one on the Mac first.")
        if project and project.strip():
            named = self._service.resolve_project(project=project)
            for record in records:
                if record["id"] == str(named["id"]):
                    return Placement(record, NAMED)
            return Placement({"id": str(named["id"]), "title": str(named.get("title") or "Project")}, NAMED)
        mentioned = mentioned_project(request, records)
        if mentioned is not None:
            return Placement(mentioned, MENTIONED)
        if computer or is_general_task(request):
            record, _ = self.orchestration(records)
            if record is not None:
                return Placement(record, ORCHESTRATION)
        for record in records:
            if not record.get("scratch"):
                return Placement(record, RECENT)
        return Placement(records[0], RECENT)
