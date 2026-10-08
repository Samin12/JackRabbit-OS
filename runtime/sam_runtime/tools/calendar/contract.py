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
            "their start and end times. Use this for today, tomorrow, this week or any date: list, "
            "then keep only the events in that range. startsLocal/endsLocal are in the user's time "
            "zone and startsInMinutes counts from now: for \"the next 30 minutes\" keep only events "
            "with startsInMinutes <= 30 (negative = already started) and say so when there are none.",
            "read",
            _schema({"limit": {"type": "integer", "minimum": 1, "maximum": 50}}),
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
