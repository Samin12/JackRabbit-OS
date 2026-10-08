import Foundation

/// One row of a conversation, as the desktop app shows it.
public struct TimelineItem: Sendable, Equatable, Identifiable {
    public enum Content: Sendable, Equatable {
        case user(text: String, origin: String?)
        case assistant(text: String, streaming: Bool, interrupted: Bool)
        case card(GenCard, dismissed: Bool, updates: Int)
        case generatedUI(GeneratedUIItem)
        case image(ImageItem)
        case tool(ToolItem)
        case t3(T3Update)
        /// `host.note`, `host.completion` and `ui.event` lines.
        case system(kind: String, title: String?, text: String)
        case divider(label: String, variant: String)
        case saved(summary: String, memoryCount: Int)
    }

    public let id: String
    public var at: Date
    public var order: Int
    public var version: Int
    public var content: Content
}

public struct GeneratedUIItem: Sendable, Equatable {
    public enum Status: String, Sendable { case generating, ready, failed }
    public var artifactId: String
    public var status: Status
    public var title: String
    public var summary: String
    public var prompt: String
    public var imageBlobId: String?
    public var error: String?
    public var width: Int
    public var height: Int
}

public struct ImageItem: Sendable, Equatable {
    public var blobId: String
    public var mime: String
    public var width: Int
    public var height: Int
    /// mac_screenshot, camera, tool, ...
    public var source: String
    public var caption: String?

    public var sourceLabel: String {
        switch source {
        case "mac_screenshot": "Mac screenshot"
        case "camera", "r1_camera": "R1 camera"
        case "tool": "Image"
        default: "Image"
        }
    }
}

public struct ToolItem: Sendable, Equatable {
    public var tool: String
    public var pending: Bool
    public var isError: Bool
    public var summary: String?
}

public struct T3Update: Sendable, Equatable {
    public var line: String
    public var lastMessage: String?
    public var threadId: String?
    public var title: String?
    public var projectTitle: String?
    public var status: ThreadStatus
}

/// Builds a conversation's timeline from its sync events (a port of the desktop app's
/// `web/store.js` `Timeline`): every event is applied at most once, streaming drafts are replaced
/// in place, cards update by id and generated UIs never downgrade from ready to generating.
public struct ConversationTimeline: Sendable {
    public private(set) var conversationId: String
    public private(set) var cursor: Int = 0
    public private(set) var ended = false
    private var seen = Set<String>()
    private var items: [String: TimelineItem] = [:]
    private var assistantSeq: [String: Int] = [:]
    private var openAssistant: String?
    private var nextOrder = 0

    private static let cardTools: Set<String> = ["show_card", "update_card", "dismiss_card"]
    private static let quietEnd =
        "^(user|stop|stopped|back|button|side[-_ ]?button|close|closed|ended|normal|idle|timeout|done|finished|hangup)$"

    public init(conversationId: String) { self.conversationId = conversationId }

    public var isEmpty: Bool { items.isEmpty }

    /// The visible items in time order.
    public var sorted: [TimelineItem] {
        items.values.filter { item in
            if case .tool(let tool) = item.content, tool.tool == "ui_generate", !tool.isError { return false }
            return true
        }.sorted { ($0.at, $0.order) < ($1.at, $1.order) }
    }

    /// Applies a page of events; returns true when anything changed.
    @discardableResult
    public mutating func apply(_ events: [SyncEvent]) -> Bool {
        var changed = false
        for event in events where apply(event) { changed = true }
        return changed
    }

    /// Applies one event of this conversation (others are ignored); returns true on a change.
    @discardableResult
    public mutating func apply(_ event: SyncEvent) -> Bool {
        if let cursor = event.cursor { self.cursor = max(self.cursor, cursor) }
        if let owner = event.conversationId, owner != conversationId { return false }
        let raw = event.raw
        let type = event.type
        guard !type.isEmpty else { return false }
        let id = event.id
        if seen.contains(id) { return false }
        let seq = raw["seq"].int
        let messageId = raw["messageId"].text
        if !(type == "message.assistant.delta" && messageId != nil && seq != nil) { seen.insert(id) }
        let at = event.at ?? .now

        switch type {
        case "message.user":
            return upsert("u:\(id)", at: at, .user(text: raw["text"].string ?? "", origin: raw["origin"].text))

        case "message.assistant.delta", "message.assistant.done", "message.assistant.interrupted":
            let key = messageId.map { "a:\($0)" } ?? openAssistant ?? "a:\(id)"
            var text = ""
            var done = false
            if case .assistant(let current, let streaming, _)? = items[key]?.content {
                text = current
                done = !streaming
            }
            if type == "message.assistant.delta" {
                if done { return false }
                if let seq, let last = assistantSeq[key], seq <= last { return false }
                if let value = raw["text"].string { text = value }
                if let seq { assistantSeq[key] = seq }
                openAssistant = messageId == nil ? key : nil
                return upsert(key, at: at, .assistant(text: text, streaming: true, interrupted: false), keepTime: true)
            }
            if let value = raw["text"].string, !value.isEmpty { text = value }
            if openAssistant == key { openAssistant = nil }
            let interrupted = type == "message.assistant.interrupted" || raw["interrupted"].bool == true
            return upsert(key, at: at, .assistant(text: text, streaming: false, interrupted: interrupted), keepTime: true)

        case "card.shown", "card.updated":
            guard let card = GenCard(raw["card"]) else { return false }
            let key = "card:\(card.id)"
            var updates = 0
            if case .card(_, _, let count)? = items[key]?.content { updates = count + (type == "card.updated" ? 1 : 0) }
            return upsert(key, at: at, .card(card, dismissed: card.state == "dismissed", updates: updates), keepTime: true)

        case "card.dismissed":
            var ids: [String] = []
            if let id = raw["card"]["id"].text { ids.append(id) }
            if let id = raw["cardId"].text { ids.append(id) }
            for list in ["ids", "dismissed", "cardIds"] { ids += (raw[list].array ?? []).compactMap(\.text) }
            var changed = false
            for cardId in ids {
                let key = "card:\(cardId)"
                if case .card(let card, false, let updates)? = items[key]?.content {
                    changed = upsert(key, at: at, .card(card, dismissed: true, updates: updates), keepTime: true) || changed
                }
            }
            return changed

        case "host.t3_update":
            let payload = raw["payload"]
            let (line, last) = Self.parseT3Update(raw["text"].string ?? "")
            let status = raw["status"].text ?? payload["status"].text
                ?? raw["kind"].text?.replacingOccurrences(of: "t3.thread.", with: "")
            return upsert("t3:\(id)", at: at, .t3(T3Update(
                line: line, lastMessage: payload["lastMessage"].text ?? (last.isEmpty ? nil : last),
                threadId: raw["threadId"].text ?? payload["threadId"].text,
                title: raw["title"].text ?? payload["title"].text,
                projectTitle: raw["projectTitle"].text ?? payload["projectTitle"].text,
                status: ThreadStatus(raw: status))))

        case "host.note", "host.completion", "ui.event":
            let kind = type == "host.note" ? "note" : type == "ui.event" ? "uievent" : "completion"
            let text = raw["text"].string ?? ""
            var body = kind == "completion" ? Self.parseCompletion(text) : Self.stripTag(text)
            if kind == "uievent", body.lowercased().hasPrefix("the user") { body = "You" + body.dropFirst(8) }
            guard !body.isEmpty else { return false }
            return upsert("s:\(id)", at: at, .system(kind: kind, title: raw["title"].text, text: body))

        case "image":
            guard let blobId = raw["blobId"].text, items["img:\(blobId)"] == nil else { return false }
            return upsert("img:\(blobId)", at: at, .image(ImageItem(
                blobId: blobId, mime: raw["mime"].text ?? "image/jpeg", width: raw["width"].int ?? 0,
                height: raw["height"].int ?? 0, source: raw["source"].text ?? "image", caption: raw["caption"].text)))

        case "tool.call", "tool.completed":
            let tool = raw["tool"].text ?? raw["name"].text ?? ""
            if Self.cardTools.contains(tool), raw["isError"].bool != true { return false }
            let key = "tool:\(raw["toolCallId"].text ?? id)"
            var completed = type == "tool.completed"
            if case .tool(let current)? = items[key]?.content, !current.pending { completed = true }
            let isError = raw["isError"].bool == true
            var changed = upsert(key, at: at, .tool(ToolItem(tool: tool, pending: !completed, isError: isError,
                                                              summary: Self.toolSummary(raw))), keepTime: true)
            if let blobId = raw["blobId"].text, items["img:\(blobId)"] == nil {
                changed = upsert("img:\(blobId)", at: at.addingTimeInterval(0.001), .image(ImageItem(
                    blobId: blobId, mime: raw["mime"].text ?? "image/jpeg", width: raw["width"].int ?? 0,
                    height: raw["height"].int ?? 0, source: tool == "mac_look" ? "mac_screenshot" : "tool",
                    caption: nil))) || changed
            }
            return changed

        case "ui.generating", "ui.generated", "ui.failed":
            guard let artifactId = raw["artifactId"].text else { return false }
            let key = "ui:\(artifactId)"
            let status: GeneratedUIItem.Status = type == "ui.generated" ? .ready : type == "ui.failed" ? .failed : .generating
            var item = GeneratedUIItem(artifactId: artifactId, status: .generating, title: "", summary: "", prompt: "",
                                       imageBlobId: nil, error: nil, width: 0, height: 0)
            if case .generatedUI(let current)? = items[key]?.content {
                if status == .generating, current.status != .generating { return false }
                item = current
            }
            item.status = status
            item.title = raw["title"].text ?? item.title
            item.summary = raw["summary"].text ?? item.summary
            item.prompt = raw["prompt"].text ?? item.prompt
            item.imageBlobId = raw["imageBlobId"].text ?? item.imageBlobId
            item.error = raw["error"].text ?? raw["error"]["message"].text ?? item.error
            item.width = raw["width"].int ?? item.width
            item.height = raw["height"].int ?? item.height
            return upsert(key, at: at, .generatedUI(item), keepTime: true)

        case "conversation.started":
            ended = false
            return upsert("d:\(id)", at: at, .divider(label: "Conversation started", variant: "start"))

        case "conversation.ended":
            ended = true
            closeStreaming()
            return upsert("d:\(id)", at: at, .divider(label: "Conversation ended", variant: "end"))

        case "session.connected":
            guard raw["reconnect"].bool == true else { return false }
            return upsert("d:\(id)", at: at, .divider(label: "Reconnected", variant: "reconnect"))

        case "session.ended":
            closeStreaming()
            let reason = raw["reason"].text ?? ""
            if reason.isEmpty || reason.range(of: Self.quietEnd, options: [.regularExpression, .caseInsensitive]) != nil {
                return true
            }
            let dropped = reason.range(of: "error|fail|drop|lost|network|provider", options: [.regularExpression, .caseInsensitive]) != nil
            return upsert("d:\(id)", at: at, .divider(label: dropped ? "Connection dropped" : "Session ended", variant: "warn"))

        case "session.finalized":
            return upsert("d:\(id)", at: at, .saved(summary: raw["summary"].string ?? "", memoryCount: raw["memoryCount"].int ?? 0))

        default:
            return false
        }
    }

    /// A dropped session never finishes its streaming bubble.
    private mutating func closeStreaming() {
        for (key, item) in items {
            if case .assistant(let text, true, let interrupted) = item.content {
                var copy = item
                copy.content = .assistant(text: text, streaming: false, interrupted: interrupted)
                copy.version += 1
                items[key] = copy
            }
        }
        openAssistant = nil
    }

    @discardableResult
    private mutating func upsert(_ key: String, at: Date, _ content: TimelineItem.Content, keepTime: Bool = false) -> Bool {
        if var item = items[key] {
            if item.content == content { return false }
            item.content = content
            if !keepTime { item.at = at }
            item.version += 1
            items[key] = item
        } else {
            items[key] = TimelineItem(id: key, at: at, order: nextOrder, version: 0, content: content)
            nextOrder += 1
        }
        return true
    }

    // MARK: - Text helpers (ported from store.js / format.js)

    static func between(_ raw: String, _ start: String, _ end: String) -> String? {
        guard let a = raw.range(of: start), let b = raw.range(of: end, range: a.upperBound..<raw.endIndex) else { return nil }
        return String(raw[a.upperBound..<b.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func stripTag(_ raw: String) -> String {
        raw.replacingOccurrences(of: #"^\s*\[[^\]]{1,40}\]\s*"#, with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// "[T3 update] …" envelopes: the line between the markers, split from "Last message:".
    public static func parseT3Update(_ raw: String) -> (line: String, lastMessage: String) {
        let inner = between(raw, "--- BEGIN T3 UPDATE ---", "--- END T3 UPDATE ---") ?? raw
        var line = inner.replacingOccurrences(of: #"^\s*\[T3 update\]\s*"#, with: "", options: [.regularExpression, .caseInsensitive])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        var last = ""
        if let marker = line.range(of: " Last message: ") {
            last = String(line[marker.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
            line = String(line[..<marker.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return (line, last)
    }

    public static func parseCompletion(_ raw: String) -> String {
        stripTag(between(raw, "--- BEGIN BACKGROUND RESULT DATA ---", "--- END BACKGROUND RESULT DATA ---") ?? raw)
    }

    static func toolSummary(_ raw: JSONValue) -> String? {
        let args = raw["arguments"]
        for key in ["app", "url", "query", "title", "text", "prompt"] {
            if let value = args[key].text { return String(value.prefix(80)) }
        }
        return nil
    }
}
