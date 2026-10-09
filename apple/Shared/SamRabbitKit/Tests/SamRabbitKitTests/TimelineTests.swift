import Foundation
import Testing
@testable import SamRabbitKit

@Suite("Conversation timeline")
struct TimelineTests {
    func event(_ type: String, _ fields: [String: JSONValue] = [:], id: String? = nil, at: Double = 1_791_480_000_000) -> SyncEvent {
        var raw: [String: JSONValue] = ["type": .string(type), "conversationId": "c_test", "at": .number(at)]
        if let id { raw["id"] = .string(id) }
        for (key, value) in fields { raw[key] = value }
        return SyncEvent(.object(raw))
    }

    @Test func streamingDraftsAreReplacedInPlace() {
        var timeline = ConversationTimeline(conversationId: "c_test")
        timeline.apply(event("message.user", ["text": "Hi"], id: "1"))
        timeline.apply(event("message.assistant.delta", ["messageId": "m1", "seq": 0, "text": "Hel"], id: "2", at: 1_791_480_001_000))
        timeline.apply(event("message.assistant.delta", ["messageId": "m1", "seq": 1, "text": "Hello th"], id: "3", at: 1_791_480_001_300))
        // a stale draft is ignored
        let changed1 = timeline.apply(event("message.assistant.delta", ["messageId": "m1", "seq": 0, "text": "Hel"], id: "4"))
        #expect(!changed1)
        var items = timeline.sorted
        #expect(items.count == 2)
        #expect(items[1].content == .assistant(text: "Hello th", streaming: true, interrupted: false))
        timeline.apply(event("message.assistant.done", ["messageId": "m1", "text": "Hello there"], id: "5", at: 1_791_480_002_000))
        items = timeline.sorted
        #expect(items[1].content == .assistant(text: "Hello there", streaming: false, interrupted: false))
        // a late draft after done changes nothing
        let changed2 = timeline.apply(event("message.assistant.delta", ["messageId": "m1", "seq": 9, "text": "Hel"], id: "6"))
        #expect(!changed2)
        // the same event twice is applied once
        let changed3 = timeline.apply(event("message.user", ["text": "Hi"], id: "1"))
        #expect(!changed3)
    }

    @Test func generatedUIsNeverDowngrade() {
        var timeline = ConversationTimeline(conversationId: "c_test")
        timeline.apply(event("ui.generating", ["artifactId": "ui_1", "prompt": "chart"], id: "1"))
        timeline.apply(event("ui.generated", ["artifactId": "ui_1", "title": "Chart", "imageBlobId": "sha256:aa",
                                               "width": 960, "height": 620], id: "2"))
        let changed4 = timeline.apply(event("ui.generating", ["artifactId": "ui_1"], id: "3"))
        #expect(!changed4)
        guard case .generatedUI(let item) = timeline.sorted.first?.content else {
            Issue.record("expected a generated UI")
            return
        }
        #expect(item.status == .ready)
        #expect(item.title == "Chart")
        #expect(item.prompt == "chart")
        #expect(item.imageBlobId == "sha256:aa")
    }

    @Test func cardsUpdateAndDismiss() {
        var timeline = ConversationTimeline(conversationId: "c_test")
        timeline.apply(event("card.shown", ["card": ["id": "k", "title": "One", "body": [["type": "text", "text": "x"]]]], id: "1"))
        timeline.apply(event("card.updated", ["card": ["id": "k", "title": "Two", "accent": "mint"]], id: "2"))
        timeline.apply(event("card.dismissed", ["cardId": "k"], id: "3"))
        #expect(timeline.sorted.count == 1)
        guard case .card(let card, let dismissed, let updates) = timeline.sorted[0].content else {
            Issue.record("expected a card")
            return
        }
        #expect(card.title == "Two")
        #expect(card.accent == "mint")
        #expect(dismissed)
        #expect(updates == 1)
    }

    @Test func imagesToolsAndSystemLines() {
        var timeline = ConversationTimeline(conversationId: "c_test")
        timeline.apply(event("tool.call", ["tool": "mac_look", "toolCallId": "t1"], id: "1", at: 1791480001000))
        timeline.apply(event("tool.completed", ["tool": "mac_look", "toolCallId": "t1", "blobId": "sha256:bb"], id: "2", at: 1791480002000))
        timeline.apply(event("image", ["blobId": "sha256:bb", "source": "mac_screenshot"], id: "3", at: 1791480003000)) // same blob: once
        timeline.apply(event("tool.call", ["tool": "show_card"], id: "4", at: 1791480004000)) // card tools are hidden
        timeline.apply(event("host.note", ["text": "[Journal] Added."], id: "5", at: 1791480005000))
        timeline.apply(event("host.t3_update", ["text": "[T3 update] “Fix” finished. Last message: All good", "status": "done"], id: "6", at: 1791480006000))
        timeline.apply(event("event.of.another.conversation", ["conversationId": "c_other"], id: "7", at: 1791480007000))
        let kinds = timeline.sorted.map { item -> String in
            switch item.content {
            case .tool: "tool"
            case .image(let image): "image:\(image.source)"
            case .system(let kind, _, let text): "\(kind):\(text)"
            case .t3(let update): "t3:\(update.status.rawValue):\(update.lastMessage ?? "")"
            default: "other"
            }
        }
        #expect(kinds == ["tool", "image:mac_screenshot", "note:Added.", "t3:done:All good"])
    }

    @Test func sessionEndClosesStreamingBubbles() {
        var timeline = ConversationTimeline(conversationId: "c_test")
        timeline.apply(event("message.assistant.delta", ["messageId": "m", "seq": 0, "text": "Half"], id: "1"))
        timeline.apply(event("session.ended", ["reason": "network_lost"], id: "2"))
        let contents = timeline.sorted.map(\.content)
        #expect(contents.contains(.assistant(text: "Half", streaming: false, interrupted: false)))
        #expect(contents.contains(.divider(label: "Connection dropped", variant: "warn")))
    }
}
