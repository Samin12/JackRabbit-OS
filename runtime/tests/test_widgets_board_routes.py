"""Device routes the Cards widget board depends on: one-tap task completion and calendar state."""
from __future__ import annotations

from datetime import UTC, datetime, timedelta
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from uuid import uuid4

from sam_runtime.api.calendar_routes import CalendarRoutes
from sam_runtime.api.routes import RuntimeRoutes
from sam_runtime.api.task_routes import TaskRoutes
from sam_runtime.domains.calendar import CalendarAccountConfiguration, CalendarEvent, CalendarRepository
from sam_runtime.domains.tasks import TaskRepository
from sam_runtime.storage.database import RuntimeDatabase


class TaskCompleteRouteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        self.database = RuntimeDatabase(Path(self.temp.name) / "runtime.sqlite3")
        self.database.migrate()
        self.repository = TaskRepository(self.database)
        self.routes = TaskRoutes(self.repository)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def add(self, text: str) -> str:
        task = self.repository.execute({"taskId": None, "operation": "add", "payload": {"text": text}})
        assert task is not None
        return task.task_id

    def test_complete_open_task_uses_domain_completion(self) -> None:
        keep = self.add("Buy oat milk")
        done = self.add("Call the dentist")
        request = _Request(f"/v1/tasks/{done}/complete", body=b"{}")
        self.assertTrue(self.routes.handle_post(request))
        self.assertEqual(200, request.status)
        self.assertTrue(request.payload["changed"])
        self.assertEqual({"taskId": done, "text": "Call the dentist", "status": "completed"}, request.payload["task"])
        stored = self.repository.get(done)
        assert stored is not None
        self.assertEqual("completed", stored.status)
        self.assertIsNotNone(stored.completed_at)
        active = _Request("/v1/tasks/active")
        self.assertTrue(self.routes.handle_get(active))
        self.assertEqual([keep], [item["taskId"] for item in active.payload["tasks"]])

    def test_complete_is_idempotent(self) -> None:
        task_id = self.add("Water plants")
        first = _Request(f"/v1/tasks/{task_id}/complete", body=b"{}")
        self.routes.handle_post(first)
        completed_at = self.repository.get(task_id).completed_at
        second = _Request(f"/v1/tasks/{task_id}/complete")
        self.assertTrue(self.routes.handle_post(second))
        self.assertEqual(200, second.status)
        self.assertFalse(second.payload["changed"])
        self.assertEqual("completed", second.payload["task"]["status"])
        self.assertEqual(completed_at, self.repository.get(task_id).completed_at)

    def test_unknown_task_is_not_found(self) -> None:
        request = _Request(f"/v1/tasks/{uuid4()}/complete")
        self.assertTrue(self.routes.handle_post(request))
        self.assertEqual(404, request.status)
        self.assertEqual("task_not_found", request.payload["error"]["code"])
        empty = _Request("/v1/tasks//complete")
        self.assertTrue(self.routes.handle_post(empty))
        self.assertEqual(404, empty.status)

    def test_invalid_body_is_rejected_without_mutation(self) -> None:
        task_id = self.add("Stretch")
        request = _Request(f"/v1/tasks/{task_id}/complete", body=b"[1]")
        self.assertTrue(self.routes.handle_post(request))
        self.assertEqual(400, request.status)
        self.assertEqual("open", self.repository.get(task_id).status)

    def test_other_task_paths_are_not_claimed(self) -> None:
        task_id = self.add("Read")
        for path in ("/v1/tasks/active", f"/v1/tasks/{task_id}", f"/v1/tasks/{task_id}/remove",
                     f"/v1/tasks/{task_id}/complete/extra", "/v1/calendar/upcoming"):
            request = _Request(path)
            self.assertFalse(self.routes.handle_post(request), path)
            self.assertEqual(0, request.status, path)
        self.assertEqual("open", self.repository.get(task_id).status)

    def test_runtime_router_dispatches_task_completion(self) -> None:
        task_id = self.add("Pack charger")
        router = RuntimeRoutes(
            health=lambda: {}, lifecycle=None, events=None, pairing=None, providers=None,
            text_runner=None, subscription=None, mcp=None, profile=None, sessions=None,
            memory=None, restart_request=None, tasks=self.routes,
        )
        request = _Request(f"/v1/tasks/{task_id}/complete", body=b"{}")
        router.handle_post(request)
        self.assertEqual(200, request.status)
        self.assertEqual("completed", self.repository.get(task_id).status)


class CalendarUpcomingStateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        database = RuntimeDatabase(Path(self.temp.name) / "runtime.sqlite3")
        database.migrate()
        self.repository = CalendarRepository(database)
        self.routes = CalendarRoutes(self.repository, service=None)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def upcoming(self) -> dict[str, object]:
        request = _Request("/v1/calendar/upcoming")
        self.assertTrue(self.routes.handle_get(request, None))
        self.assertEqual(200, request.status)
        return request.payload

    def test_unconfigured_calendar_is_distinguishable_from_empty(self) -> None:
        self.assertEqual({"events": [], "configured": False}, self.upcoming())
        account = self.repository.create_account(CalendarAccountConfiguration(
            str(uuid4()), "ics_subscription", "Home", "https://example.com/home.ics", None), None)
        self.assertEqual({"events": [], "configured": True}, self.upcoming())
        now = datetime.now(UTC)
        account_id = account.configuration.account_id
        self.repository.replace_account_events(account_id, (CalendarEvent(
            str(uuid4()), account_id, "standup", "", "Standup", (now + timedelta(hours=1)).isoformat(),
            (now + timedelta(hours=2)).isoformat(), "UTC", False, "Zoom", "Work", None, None,
            "confirmed", False, None, now.isoformat()),))
        payload = self.upcoming()
        self.assertTrue(payload["configured"])
        self.assertEqual(["Standup"], [item["title"] for item in payload["events"]])
        self.assertEqual("Work", payload["events"][0]["calendar"])


class AllDayGraceTest(unittest.TestCase):
    """All-day events are UTC midnight of a floating date; New York evenings must still see today's."""

    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        database = RuntimeDatabase(Path(self.temp.name) / "runtime.sqlite3")
        database.migrate()
        self.repository = CalendarRepository(database)
        account = self.repository.create_account(CalendarAccountConfiguration(
            str(uuid4()), "ics_subscription", "Family", "https://example.com/family.ics", None), None)
        self.account_id = account.configuration.account_id
        stamp = datetime(2026, 10, 7, tzinfo=UTC).isoformat()
        self.repository.replace_account_events(self.account_id, (
            CalendarEvent(str(uuid4()), self.account_id, "birthday", "", "Birthday",
                          "2026-10-07T00:00:00+00:00", "2026-10-08T00:00:00+00:00", "UTC", True, None,
                          "Family", None, None, "confirmed", False, None, stamp),
            CalendarEvent(str(uuid4()), self.account_id, "dinner", "", "Dinner",
                          "2026-10-07T22:00:00+00:00", "2026-10-07T23:30:00+00:00", "UTC", False, None,
                          "Family", None, None, "confirmed", False, None, stamp),
        ))

    def tearDown(self) -> None:
        self.temp.cleanup()

    def titles(self, now: str, grace: int) -> list[str]:
        return [item.title for item in self.repository.upcoming_events(now, all_day_grace_hours=grace)]

    def test_device_projection_keeps_todays_all_day_event_through_the_local_evening(self) -> None:
        nine_pm_new_york = "2026-10-08T01:00:00+00:00"
        self.assertEqual([], self.titles(nine_pm_new_york, 0))  # previous behaviour: gone at 8 PM local
        self.assertEqual(["Birthday"], self.titles(nine_pm_new_york, 14))
        self.assertEqual([], self.titles("2026-10-08T14:00:01+00:00", 14))

    def test_timed_events_are_unaffected_by_the_grace(self) -> None:
        self.assertEqual(["Birthday", "Dinner"], self.titles("2026-10-07T21:00:00+00:00", 14))
        self.assertEqual(["Birthday"], self.titles("2026-10-07T23:31:00+00:00", 14))


class _Request:
    def __init__(self, path: str, *, body: bytes = b"") -> None:
        self.path = path
        self.body = body
        self.headers = {"Content-Length": str(len(body))}
        self.status = 0
        self.payload: dict[str, object] = {}

    def request_json(self, *, max_bytes: int = 4096):
        if len(self.body) > max_bytes:
            self.respond_json(400, {"error": {"code": "invalid_request", "message": "Request body is invalid."}})
            return None
        value = json.loads(self.body or b"{}")
        if not isinstance(value, dict):
            self.respond_json(400, {"error": {"code": "invalid_json", "message": "Request body must be an object."}})
            return None
        return value

    def respond_json(self, status: int, payload: dict[str, object], *, headers=None) -> None:
        del headers
        self.status = status
        self.payload = payload


if __name__ == "__main__":
    unittest.main()
