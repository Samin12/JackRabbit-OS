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
            "window it lists the next events (for today, tomorrow or this week prefer from and to). Every "
            "event has startsLocal/endsLocal in the user's time zone and startsInMinutes from now "
            "(negative = already started).",
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
            "Add an event. Needs a title, a start (startsAt) and an end (endsAt or durationMinutes); ask "
            "for whatever is missing. Times without an offset are the user's local time; \"for the next "
            "30 minutes\" is startsAt \"now\" with durationMinutes 30. calendarAccountId is optional "
            "(default: the user's Google Calendar). On Google Calendar it is added right away: confirm "
            "briefly from the result (title, local time). If the result has confirmationRequired, review "
            "it with the user and call calendar_confirm_action after they approve. On an error, say it "
            "failed; nothing keeps running in the background.",
            "external_write",
            _schema({**account, **_event_fields()}, ("title", "startsAt")),
        ),
        CalendarToolContract(
            "calendar_update_event",
            "Move or change an event (eventId from calendar_list_upcoming or calendar_search). To move "
            "it give the new startsAt (its length is kept) and/or endsAt or durationMinutes. On Google "
            "Calendar the change is made right away (for a repeating event only that occurrence): "
            "confirm briefly from the result. If the result has confirmationRequired, review it with the "
            "user and call calendar_confirm_action after they approve.",
            "external_write",
            _schema({**event, **_event_fields()}, ("eventId",)),
        ),
        CalendarToolContract(
            "calendar_delete_event",
            "Prepare deleting (cancelling) an event (for a repeating event only that occurrence). "
            "Deletion requires explicit user confirmation: say which event and when, and after they "
            "say yes call calendar_confirm_action with the returned actionId and contentHash.",
            "external_write",
            _schema(event, ("eventId",)),
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
        "durationMinutes": {"type": "integer", "minimum": 1, "maximum": 20160},
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
