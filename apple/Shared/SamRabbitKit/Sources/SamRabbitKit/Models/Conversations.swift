import Foundation

/// One R1 conversation in the bridge's sync store (`GET /v1/mobile/conversations`).
public struct ConversationSummary: Codable, Sendable, Equatable, Identifiable {
    public var conversationId: String
    public var title: String?
    public var startedAt: Date?
    public var lastAt: Date?
    public var endedAt: Date?
    public var live: Bool
    public var messageCount: Int
    public var preview: String?
    public var device: String?
    public var cursor: Int

    public var id: String { conversationId }

    /// A title for display ("Conversation" when the store has none yet).
    public var displayTitle: String { title ?? "Conversation" }

    public init(conversationId: String, title: String? = nil, startedAt: Date? = nil, lastAt: Date? = nil,
                endedAt: Date? = nil, live: Bool = false, messageCount: Int = 0, preview: String? = nil,
                device: String? = nil, cursor: Int = 0) {
        self.conversationId = conversationId
        self.title = title
        self.startedAt = startedAt
        self.lastAt = lastAt
        self.endedAt = endedAt
        self.live = live
        self.messageCount = messageCount
        self.preview = preview
        self.device = device
        self.cursor = cursor
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        guard let id = c.text("conversationId", "id") else {
            throw DecodingError.keyNotFound(AnyKey("conversationId"),
                                            .init(codingPath: decoder.codingPath, debugDescription: "no id"))
        }
        conversationId = id
        title = c.text("title")
        startedAt = c.date("startedAt")
        lastAt = c.date("lastAt")
        endedAt = c.date("endedAt")
        live = c.bool("live")
        messageCount = c.int("messageCount")
        preview = c.text("preview")
        device = c.text("device")
        cursor = c.int("cursor")
    }
}

/// `GET /v1/mobile/conversations` -> `{conversations, cursor, nextBefore?}`
public struct ConversationPage: Codable, Sendable, Equatable {
    public var conversations: [ConversationSummary]
    public var cursor: Int
    /// Pass as `before` for the next (older) page; `nil` on the last page.
    public var nextBefore: Int?

    public init(conversations: [ConversationSummary], cursor: Int = 0, nextBefore: Int? = nil) {
        self.conversations = conversations
        self.cursor = cursor
        self.nextBefore = nextBefore
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        conversations = c.list(ConversationSummary.self, "conversations")
        cursor = c.int("cursor")
        nextBefore = c.optionalInt("nextBefore")
    }
}

/// One sync event (the same JSON the desktop app receives). The open-ended payload stays a
/// `JSONValue`; `ConversationTimeline` turns events into display items.
public struct SyncEvent: Codable, Sendable, Equatable, Identifiable {
    public var raw: JSONValue

    public init(_ raw: JSONValue) { self.raw = raw }

    public init(from decoder: Decoder) throws {
        raw = try JSONValue(from: decoder)
    }

    public func encode(to encoder: Encoder) throws {
        try raw.encode(to: encoder)
    }

    public var type: String { raw["type"].string ?? "" }
    public var cursor: Int? { raw["cursor"].int }
    public var at: Date? { raw["at"].date }

    /// `conversationId`, or the `c_<id>:` prefix of the event id.
    public var conversationId: String? {
        if let value = raw["conversationId"].text { return value }
        if let id = raw["id"].string, id.hasPrefix("c_"), let colon = id.firstIndex(of: ":") {
            return String(id[..<colon])
        }
        return nil
    }

    /// The event id, or a stable fallback built from its fields.
    public var id: String {
        if let value = raw["id"].string, !value.isEmpty { return value }
        if let value = raw["id"].double { return String(Int(value)) }
        let seq = raw["seq"].int.map(String.init) ?? ""
        let extra = raw["messageId"].string ?? raw["artifactId"].string ?? ""
        let at = raw["at"].double.map { String(Int($0)) } ?? raw["at"].string ?? ""
        return "\(type):\(at):\(seq):\(extra)"
    }
}

/// `GET /v1/mobile/conversations/<id>/events?after=` -> `{events, cursor, more}`
public struct EventPage: Codable, Sendable, Equatable {
    public var events: [SyncEvent]
    public var cursor: Int
    public var more: Bool

    public init(events: [SyncEvent], cursor: Int, more: Bool = false) {
        self.events = events
        self.cursor = cursor
        self.more = more
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        events = c.list(SyncEvent.self, "events")
        cursor = c.int("cursor")
        more = c.bool("more")
    }
}
