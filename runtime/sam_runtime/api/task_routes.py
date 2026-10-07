from __future__ import annotations
from typing import TYPE_CHECKING
from urllib.parse import unquote
from sam_runtime.domains.tasks import TaskRepository
if TYPE_CHECKING:
    from .routes import RouteRequest

class TaskRoutes:
    """Device-only projection for the native Tasks Card and the Cards widget board."""
    def __init__(self, repository: TaskRepository) -> None: self._repository = repository
    def handle_get(self, request: "RouteRequest") -> bool:
        path = request.path.split("?", 1)[0]
        if path == "/v1/tasks/active":
            request.respond_json(200, {"tasks": [_view(item) for item in self._repository.list(limit=100)]})
            return True
        if path.startswith("/v1/tasks/"):
            item = self._repository.get(path.rsplit("/", 1)[-1])
            if item is None: request.respond_json(404, {"error": {"code": "task_not_found", "message": "Task not found."}})
            else: request.respond_json(200, _view(item))
            return True
        return False

    def handle_post(self, request: "RouteRequest") -> bool:
        """POST /v1/tasks/{taskId}/complete: a direct on-device tap is the user's approval.

        Voice reviews writes with prepare/confirm because speech can be misheard; a tap on the
        task's own circle is explicit, so the widget board completes it through the same domain
        execution path that ``tasks_confirm_action`` uses for a reviewed ``complete``.
        """
        path = request.path.split("?", 1)[0]
        parts = path.split("/")
        if len(parts) != 5 or parts[1] != "v1" or parts[2] != "tasks" or parts[4] != "complete":
            return False
        task_id = unquote(parts[3]).strip()
        if not task_id:
            request.respond_json(404, {"error": {"code": "task_not_found", "message": "Task not found."}})
            return True
        if request.request_json(max_bytes=1024) is None:
            return True
        item = self._repository.get(task_id)
        if item is None:
            request.respond_json(404, {"error": {"code": "task_not_found", "message": "Task not found."}})
            return True
        if item.status != "open":
            request.respond_json(200, {"task": _view(item), "changed": False})
            return True
        completed = self._repository.execute({"taskId": item.task_id, "operation": "complete", "payload": {}})
        if completed is None:
            request.respond_json(404, {"error": {"code": "task_not_found", "message": "Task not found."}})
            return True
        request.respond_json(200, {"task": _view(completed), "changed": True})
        return True

def _view(item: object) -> dict[str, object]:
    return {"taskId": item.task_id, "text": item.text, "status": item.status}
