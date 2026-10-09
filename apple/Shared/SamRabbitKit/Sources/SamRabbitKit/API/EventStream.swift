import Foundation

/// One Server-Sent Events message.
public struct ServerSentEvent: Sendable, Equatable {
    public var id: String?
    public var event: String
    public var data: String
    /// A comment line (`: heartbeat`, `: ready 42`); `data` is empty then.
    public var comment: String?
    /// Reconnection time in milliseconds (64-bit like every millisecond value).
    public var retry: Int64?

    public init(id: String? = nil, event: String = "message", data: String = "", comment: String? = nil,
                retry: Int64? = nil) {
        self.id = id
        self.event = event
        self.data = data
        self.comment = comment
        self.retry = retry
    }
}

/// A byte-at-a-time SSE parser (WHATWG rules: `\n`, `\r\n` or `\r` line ends, `field: value`,
/// an empty line dispatches). Comments are reported too, so heartbeats keep a stream "alive".
public struct ServerSentEventParser: Sendable {
    private var line: [UInt8] = []
    private var lastWasCR = false
    private var eventType = ""
    private var dataLines: [String] = []
    private var lastId: String?
    private var retry: Int64?

    public init() {}

    /// Feeds one byte; returns the messages completed by it (usually none).
    public mutating func feed(_ byte: UInt8) -> [ServerSentEvent] {
        if byte == 0x0A, lastWasCR {
            lastWasCR = false
            return []
        }
        lastWasCR = byte == 0x0D
        guard byte == 0x0A || byte == 0x0D else {
            if line.count < 1_048_576 { line.append(byte) }
            return []
        }
        let text = String(decoding: line, as: UTF8.self)
        line.removeAll(keepingCapacity: true)
        return process(text)
    }

    /// Feeds a whole chunk (tests, buffered readers).
    public mutating func feed(_ text: String) -> [ServerSentEvent] {
        var out: [ServerSentEvent] = []
        for byte in text.utf8 { out += feed(byte) }
        return out
    }

    private mutating func process(_ text: String) -> [ServerSentEvent] {
        if text.isEmpty {
            defer {
                eventType = ""
                dataLines.removeAll()
                retry = nil
            }
            guard !dataLines.isEmpty else { return [] }
            return [ServerSentEvent(id: lastId, event: eventType.isEmpty ? "message" : eventType,
                                    data: dataLines.joined(separator: "\n"), retry: retry)]
        }
        if text.hasPrefix(":") {
            let comment = String(text.dropFirst()).trimmingCharacters(in: .whitespaces)
            return [ServerSentEvent(id: lastId, event: "comment", comment: comment)]
        }
        let field: String
        var value: String
        if let colon = text.firstIndex(of: ":") {
            field = String(text[..<colon])
            value = String(text[text.index(after: colon)...])
            if value.hasPrefix(" ") { value.removeFirst() }
        } else {
            field = text
            value = ""
        }
        switch field {
        case "event": eventType = value
        case "data": dataLines.append(value)
        case "id": if !value.contains("\0") { lastId = value }
        case "retry": retry = Int64(value)
        default: break
        }
        return []
    }
}

/// What `/v1/mobile/stream` sends: a ready marker with the starting cursor, sync events and heartbeats.
public enum SyncStreamItem: Sendable, Equatable {
    case ready(cursor: Int64?)
    case event(SyncEvent)
    case heartbeat

    init?(_ message: ServerSentEvent) {
        if let comment = message.comment {
            if comment.hasPrefix("ready") {
                self = .ready(cursor: Int64(comment.dropFirst(5).trimmingCharacters(in: .whitespaces)))
            } else {
                self = .heartbeat
            }
            return
        }
        guard message.event == "sync" || message.event == "message",
              let raw = try? JSONDecoder().decode(JSONValue.self, from: Data(message.data.utf8)) else { return nil }
        // The desktop API sometimes wraps an event as {event:{...}}.
        var event = raw
        if raw["type"].isNull, raw["event"].object != nil {
            event = raw["event"]
            if var members = event.object, members["cursor"] == nil, let cursor = raw["cursor"].double {
                members["cursor"] = .number(cursor)
                event = .object(members)
            }
        }
        if var members = event.object, members["cursor"] == nil, let id = message.id.flatMap(Double.init) {
            members["cursor"] = .number(id)
            event = .object(members)
        }
        self = .event(SyncEvent(event))
    }
}

/// The live conversation feed: keeps one `/v1/mobile/stream` open, reconnecting with backoff from
/// the last cursor, until the consuming task is cancelled.
public struct LiveSyncFeed: Sendable {
    public enum Update: Sendable, Equatable {
        case connected(cursor: Int64?)
        case event(SyncEvent)
        case disconnected(String)
    }

    private let client: BridgeClient

    public init(client: BridgeClient) { self.client = client }

    public func updates(after start: Int64?) -> AsyncStream<Update> {
        let client = self.client
        return AsyncStream { continuation in
            let task = Task {
                var cursor = start
                var delay: Double = 1
                while !Task.isCancelled {
                    do {
                        for try await item in client.stream(after: cursor) {
                            switch item {
                            case .ready(let ready):
                                delay = 1
                                if cursor == nil { cursor = ready }
                                continuation.yield(.connected(cursor: ready))
                            case .event(let event):
                                if let value = event.cursor { cursor = max(cursor ?? 0, value) }
                                continuation.yield(.event(event))
                            case .heartbeat:
                                break
                            }
                        }
                        continuation.yield(.disconnected("closed"))
                    } catch {
                        let bridgeError = error as? BridgeError
                        if bridgeError == .cancelled || Task.isCancelled { break }
                        continuation.yield(.disconnected(bridgeError?.shortDescription ?? "offline"))
                        if bridgeError == .unauthorized || bridgeError == .notPaired { break }
                    }
                    try? await Task.sleep(for: .seconds(delay))
                    delay = min(delay * 2, 30)
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}
