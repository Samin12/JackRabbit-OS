"""A stand-in for the Composio CLI (``composio execute <slug> -d -``) used by the calendar tests.

It never talks to the network. State lives in ``<state dir>/state.json`` (the wrapper script passes the folder
as the first argument, because the bridge runs the CLI with a small environment):

* ``events``: ``{eventId: google event}`` (the fake Google calendar), ``next_id``: a counter for new ids;
* ``scripted``: ``{slug: {"stdout": str, "rc": int, "sleep": seconds}}`` answers printed instead;
* ``slow``: ``{slug: seconds}``.

Every call is appended to ``calls.jsonl``: ``{argv, slug, args, env, cwd, start, end}``.
"""

from __future__ import annotations

import json
import os
import sys
import time

STATE_DIR = sys.argv[1]
ARGV = sys.argv[2:]


def _state() -> dict:
    with open(os.path.join(STATE_DIR, "state.json"), encoding="utf-8") as handle:
        return json.load(handle)


def _save(state: dict) -> None:
    path = os.path.join(STATE_DIR, "state.json")
    with open(path + ".tmp", "w", encoding="utf-8") as handle:
        json.dump(state, handle)
    os.replace(path + ".tmp", path)


def _record(entry: dict) -> None:
    with open(os.path.join(STATE_DIR, "calls.jsonl"), "a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")


def _ok(data: dict) -> dict:
    return {"successful": True, "data": data, "error": None, "logId": "log_fake"}


def _not_found() -> dict:
    return {"successful": False, "data": {"message": "Not Found", "status_code": 404}, "error": "Not Found",
            "logId": "log_fake"}


def _part(value: object) -> str:
    if isinstance(value, dict):
        return str(value.get("dateTime") or (value.get("date", "") + "T00:00:00-04:00" if value.get("date") else ""))
    return ""


def _instant(value: str):
    from datetime import datetime, timezone

    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def _when(value: str, zone: str | None) -> dict:
    part = {"dateTime": value}
    if zone:
        part["timeZone"] = zone
    return part


def _tool(slug: str, args: dict, state: dict) -> dict:
    events = state.setdefault("events", {})
    if slug == "GOOGLECALENDAR_CREATE_EVENT":
        number = int(state.get("next_id", 1))
        state["next_id"] = number + 1
        event_id = f"fakeevent{number:04d}abc"
        event = {"id": event_id, "iCalUID": event_id + "@google.com", "status": "confirmed",
                 "summary": args.get("summary"), "description": args.get("description"),
                 "location": args.get("location"), "calendarId": args.get("calendar_id"),
                 "start": _when(args["start_datetime"], args.get("timezone")),
                 "end": _when(args["end_datetime"], args.get("timezone")),
                 "creator": {"email": "samin@aianswer.us"}, "organizer": {"email": "samin@aianswer.us", "self": True},
                 "htmlLink": "https://www.google.com/calendar/event?eid=fake"}
        events[event_id] = event
        return _ok({"response_data": event})
    if slug == "GOOGLECALENDAR_PATCH_EVENT":
        event_id = args.get("event_id", "")
        base = event_id.split("_", 1)[0]
        if event_id not in events and base not in events:
            return _not_found()
        event = dict(events.get(event_id) or {**events[base], "id": event_id, "recurringEventId": base})
        if "summary" in args:
            event["summary"] = args["summary"]
        if "start_time" in args:
            event["start"] = _when(args["start_time"], args.get("timezone"))
        if "end_time" in args:
            event["end"] = _when(args["end_time"], args.get("timezone"))
        for key in ("description", "location"):
            if key in args:
                event[key] = args[key]
        events[event_id] = event
        return _ok({"response_data": event})
    if slug == "GOOGLECALENDAR_DELETE_EVENT":
        event_id = args.get("event_id", "")
        base = event_id.split("_", 1)[0]
        if event_id in events:
            events.pop(event_id)
        elif base in events and "_" in event_id:
            state.setdefault("cancelled_instances", []).append(event_id)
        else:
            return _not_found()
        return _ok({})
    if slug == "GOOGLECALENDAR_EVENTS_LIST" and args.get("iCalUID") is None and args.get("timeMin"):
        # An agenda window (the mobile API): whole events overlapping [timeMin, timeMax), by start time.
        low, high = _instant(args["timeMin"]), _instant(args.get("timeMax") or "9999-12-31T00:00:00Z")
        found = []
        for event in events.values():
            start, end = _instant(_part(event.get("start"))), _instant(_part(event.get("end")))
            if start is not None and end is not None and end > low and start < high:
                found.append(event)
        found.sort(key=lambda item: _instant(_part(item.get("start"))))
        return _ok({"items": found[:int(args.get("maxResults") or 250)], "timeZone": args.get("timeZone")})
    if slug == "GOOGLECALENDAR_EVENTS_LIST":
        uid = args.get("iCalUID")
        items = [{"id": event["id"], "status": event.get("status", "confirmed"),
                  **({"recurringEventId": event["recurringEventId"]} if event.get("recurringEventId") else {})}
                 for event in events.values() if uid is None or event.get("iCalUID") == uid]
        return _ok({"items": items})
    return {"successful": False, "error": f"Tool {slug} not found", "slug": "ToolRouterV2_ToolNotFound"}


def main() -> int:
    if ARGV[:1] == ["--version"]:
        print("0.4.2")
        return 0
    if len(ARGV) != 4 or ARGV[0] != "execute" or ARGV[2:] != ["-d", "-"]:
        print("usage: composio execute <slug> -d -", file=sys.stderr)
        return 2
    slug = ARGV[1]
    raw = sys.stdin.read()
    args = json.loads(raw or "{}")
    state = _state()
    started = time.time()
    scripted = (state.get("scripted") or {}).get(slug)
    time.sleep(float((state.get("slow") or {}).get(slug, 0)))
    _record({"argv": ARGV, "slug": slug, "args": args, "env": sorted(os.environ), "cwd": os.getcwd(),
             "start": started})
    if scripted is not None:
        time.sleep(float(scripted.get("sleep", 0)))
        sys.stdout.write(scripted.get("stdout", ""))
        return int(scripted.get("rc", 0))
    result = _tool(slug, args, state)
    _save(state)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
