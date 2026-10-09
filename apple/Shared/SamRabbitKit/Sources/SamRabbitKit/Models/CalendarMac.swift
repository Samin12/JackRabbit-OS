import Foundation

/// A Google Calendar event (`summary.calendar.next` and `/v1/mobile/calendar/agenda`).
public struct CalendarEvent: Codable, Sendable, Equatable, Identifiable {
    public var eventId: String?
    public var title: String
    public var startsAt: Date?
    public var endsAt: Date?
    public var allDay: Bool
    public var location: String?
    public var meetingUrl: URL?

    public var id: String { eventId ?? "\(title)@\(startsAt?.timeIntervalSince1970 ?? 0)" }

    public init(eventId: String? = nil, title: String, startsAt: Date?, endsAt: Date?, allDay: Bool = false,
                location: String? = nil, meetingUrl: URL? = nil) {
        self.eventId = eventId
        self.title = title
        self.startsAt = startsAt
        self.endsAt = endsAt
        self.allDay = allDay
        self.location = location
        self.meetingUrl = meetingUrl
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        eventId = c.text("eventId", "id")
        title = c.text("title", "summary") ?? "(No title)"
        startsAt = c.date("startsAt", "start")
        endsAt = c.date("endsAt", "end")
        allDay = c.bool("allDay")
        location = c.text("location")
        meetingUrl = c.text("meetingUrl", "hangoutLink").flatMap(URL.init(string:))
    }

    /// True while the event is happening.
    public func isNow(_ now: Date = .now) -> Bool {
        guard let start = startsAt, let end = endsAt else { return false }
        return start <= now && now < end
    }
}

/// `GET /v1/mobile/calendar/agenda?hours=` -> `{events:[...]}`
public struct Agenda: Codable, Sendable, Equatable {
    public var available: Bool
    public var events: [CalendarEvent]

    public init(available: Bool = true, events: [CalendarEvent]) {
        self.available = available
        self.events = events
    }

    public init(from decoder: Decoder) throws {
        if let list = try? decoder.singleValueContainer().decode([Lossy<CalendarEvent>].self) {
            available = true
            events = list.compactMap(\.value)
            return
        }
        let c = try decoder.container(keyedBy: AnyKey.self)
        available = c.bool("available", default: true)
        events = c.list(CalendarEvent.self, "events").isEmpty ? c.list(CalendarEvent.self, "items")
            : c.list(CalendarEvent.self, "events")
    }
}

/// The answer to `POST /v1/mobile/calendar/block` and `/calendar/events`.
public struct CalendarWrite: Codable, Sendable, Equatable {
    public var event: CalendarEvent?
    /// True when the bridge only pretended (a checkout copy: `calendar_dev_copy`).
    public var dryRun: Bool

    public init(event: CalendarEvent?, dryRun: Bool = false) {
        self.event = event
        self.dryRun = dryRun
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        event = c.value(CalendarEvent.self, "event") ?? (try? CalendarEvent(from: decoder))
        if event?.startsAt == nil { event = nil }
        dryRun = c.bool("dryRun") || c.text("mode") == "calendar_dev_copy"
    }
}

/// `POST /v1/mobile/journal` -> `{recorded, state, date}`
public struct JournalWrite: Codable, Sendable, Equatable {
    public var recorded: Bool
    /// "sent" (in Heptabase now) or "queued" (it will be).
    public var state: String?
    public var date: String?

    public init(recorded: Bool, state: String? = nil, date: String? = nil) {
        self.recorded = recorded
        self.state = state
        self.date = date
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        recorded = c.bool("recorded", default: c.bool("ok", default: true))
        state = c.text("state")
        date = c.text("date")
    }
}

/// `GET /v1/mobile/mac/state`: what is on the Mac right now.
public struct MacState: Codable, Sendable, Equatable {
    public struct Front: Codable, Sendable, Equatable {
        public var app: String?
        public var window: String?
    }

    public struct VisibleApp: Codable, Sendable, Equatable, Identifiable {
        public var app: String
        public var windows: [String]
        public var id: String { app }
    }

    public var name: String?
    public var online: Bool
    public var screenLocked: Bool?
    public var screenVision: Bool?
    public var front: Front?
    public var visible: [VisibleApp]
    public var running: [String]

    public init(name: String? = nil, online: Bool = true, screenLocked: Bool? = nil, screenVision: Bool? = nil,
                front: Front? = nil, visible: [VisibleApp] = [], running: [String] = []) {
        self.name = name
        self.online = online
        self.screenLocked = screenLocked
        self.screenVision = screenVision
        self.front = front
        self.visible = visible
        self.running = running
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        name = c.text("name", "computer")
        online = c.bool("online", default: true)
        screenLocked = c.optionalBool("screenLocked")
        screenVision = c.optionalBool("screenVision")
        front = c.value(Front.self, "front")
        visible = c.list(VisibleApp.self, "visible")
        running = c.list(String.self, "running")
    }
}

/// `POST /v1/mobile/mac/open` result.
public struct MacOpenResult: Codable, Sendable, Equatable {
    public var opened: String?
    public var app: String?
    public var url: String?

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        opened = c.text("opened")
        app = c.text("app")
        url = c.text("url")
    }

    public init(opened: String?, app: String?, url: String?) {
        self.opened = opened
        self.app = app
        self.url = url
    }
}

/// A generated UI (`/v1/mobile/ui/artifacts/<id>`).
public struct GeneratedArtifact: Codable, Sendable, Equatable, Identifiable {
    public enum Status: String, Codable, Sendable {
        case generating
        case ready
        case failed
    }

    public var artifactId: String
    public var status: Status
    public var title: String
    public var summary: String
    public var error: String?
    public var errorMessage: String?
    public var imageBlobId: String?
    public var width: Int?
    public var height: Int?
    public var conversationId: String?

    public var id: String { artifactId }

    public init(artifactId: String, status: Status, title: String = "", summary: String = "",
                error: String? = nil, errorMessage: String? = nil, imageBlobId: String? = nil,
                width: Int? = nil, height: Int? = nil, conversationId: String? = nil) {
        self.artifactId = artifactId
        self.status = status
        self.title = title
        self.summary = summary
        self.error = error
        self.errorMessage = errorMessage
        self.imageBlobId = imageBlobId
        self.width = width
        self.height = height
        self.conversationId = conversationId
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        artifactId = c.text("artifactId", "id") ?? ""
        status = Status(rawValue: c.text("status") ?? "") ?? .generating
        title = c.text("title") ?? ""
        summary = c.text("summary") ?? ""
        error = c.text("error")
        errorMessage = c.text("errorMessage")
        imageBlobId = c.text("imageBlobId")
        width = c.optionalInt("width")
        height = c.optionalInt("height")
        conversationId = c.text("conversationId")
    }
}

/// `GET /health` (only the parts the app shows).
public struct BridgeHealth: Codable, Sendable, Equatable {
    public var ok: Bool
    public var version: String?
    public var mobileAvailable: Bool
    public var t3Paired: Bool?

    public init(ok: Bool, version: String? = nil, mobileAvailable: Bool = true, t3Paired: Bool? = nil) {
        self.ok = ok
        self.version = version
        self.mobileAvailable = mobileAvailable
        self.t3Paired = t3Paired
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        ok = c.bool("ok", default: true)
        version = c.text("version", "bridgeVersion")
        let mobile = c.json("mobile")
        mobileAvailable = mobile["available"].bool ?? !mobile.isNull
        t3Paired = mobile["t3"]["paired"].bool
    }
}
