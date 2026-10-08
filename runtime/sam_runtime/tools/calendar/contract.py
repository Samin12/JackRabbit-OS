from __future__ import annotations

from dataclasses import dataclass


CALENDAR_PACKAGE_VERSION = 1


@dataclass(frozen=True, slots=True)
class CalendarToolContract:
    name: str
    description: str
    effect_class: str
    input_schema: dict[str, object]


def contracts() -> tuple[CalendarToolContract, ...]:
    account = {"calendarAccountId": {"type": "string"}}
    event = {**account, "eventId": {"type": "string"}}
    return (
        CalendarToolContract(
            "calendar_list_upcoming",
            "List upcoming events from the local synchronized Calendar service, soonest first, with "
            "their start and end times. For any time window ('the next 30 minutes', 'the next hour', "
            "'this afternoon', 'until 3', 'tomorrow morning') pass the window: withinMinutes (from now), "
            "or from and to (ISO 8601 with the UTC offset from the [Clock] note, e.g. "
            "2026-10-08T12:00:00-04:00). The answer then holds only the events in that window, with local "
            "times (localStart) and a window label: tell the user exactly those, say plainly when there are "
            "none, and never present nextAfterWindow or any other event as inside the window. Without a "
            "window it lists the next events (for today, tomorrow or this week prefer from and to).",
            "read",
            _schema({
                "limit": {"type": "integer", "minimum": 1, "maximum": 50},
                "withinMinutes": {"type": "integer", "minimum": 1, "maximum": 10080,
                                  "description": "Window from now, e.g. 30 for 'the next 30 minutes'."},
                "from": {"type": "string", "description": "Window start, ISO 8601 (default now)."},
                "to": {"type": "string", "description": "Window end, ISO 8601."},
                "timezone": {"type": "string", "description": "IANA zone for local times (default America/New_York)."},
            }),
        ),
        CalendarToolContract(
            "calendar_search",
            "Search upcoming events in the local synchronized Calendar service by words in their "
            "title, location or description. Not for dates: for today, tomorrow or a date use "
            "calendar_list_upcoming.",
            "read",
            _schema({"query": {"type": "string"}, "limit": {"type": "integer", "minimum": 1, "maximum": 50}}, ("query",)),
        ),
        CalendarToolContract(
            "calendar_read_event",
            "Read one synchronized Calendar event by its stable event ID.",
            "read",
            _schema({"eventId": {"type": "string"}}, ("eventId",)),
        ),
        CalendarToolContract(
            "calendar_create_event",
            "Prepare an event for a selected calendar. The calendar may be read-only. Review the exact event with the user before confirmation.",
            "external_write",
            _schema({**account, **_event_fields()}, ("calendarAccountId", "title", "startsAt")),
        ),
        CalendarToolContract(
            "calendar_update_event",
            "Prepare changes to an existing event. The calendar or event may be read-only. Review the exact changes with the user before confirmation.",
            "external_write",
            _schema({**event, **_event_fields()}, ("calendarAccountId", "eventId")),
        ),
        CalendarToolContract(
            "calendar_delete_event",
            "Prepare deletion of an existing event. The calendar or event may be read-only. Deletion requires explicit user confirmation.",
            "external_write",
            _schema(event, ("calendarAccountId", "eventId")),
        ),
        CalendarToolContract(
            "calendar_confirm_action",
            "Execute one unchanged reviewed Calendar action only after the user explicitly approves it within ten minutes.",
            "external_write",
            _schema({"actionId": {"type": "string"}, "contentHash": {"type": "string"}}, ("actionId", "contentHash")),
        ),
    )


def _event_fields() -> dict[str, object]:
    return {
        "title": {"type": "string"}, "startsAt": {"type": "string"},
        "endsAt": {"type": "string"}, "timezone": {"type": "string"},
        "allDay": {"type": "boolean"}, "location": {"type": "string"},
        "description": {"type": "string"},
    }


def _schema(
    properties: dict[str, object],
    required: tuple[str, ...] = (),
) -> dict[str, object]:
    return {
        "type": "object", "properties": properties,
        "required": list(required), "additionalProperties": False,
    }
