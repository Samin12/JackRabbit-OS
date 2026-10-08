import Foundation
import Testing
@testable import SamRabbitKit

@Suite("Pair links and hosts")
struct PairLinkTests {
    @Test func parsesTheDesktopLink() throws {
        let link = try #require(PairLink(string: "samrabbit://pair?h=192.168.1.183:3780,100.101.2.3:3780&c=K7MQ2ZXA&n=Samin%27s%20MacBook%20Pro"))
        #expect(link.hosts == [BridgeHost(host: "192.168.1.183", port: 3780), BridgeHost(host: "100.101.2.3", port: 3780)])
        #expect(link.hosts[1].isTailscale)
        #expect(!link.hosts[0].isTailscale)
        #expect(link.code == "K7MQ2ZXA")
        #expect(link.name == "Samin's MacBook Pro")
    }

    @Test func roundTripsThroughItsURL() throws {
        let link = PairLink(hosts: [BridgeHost(host: "10.0.0.5", port: 3780), BridgeHost(host: "fd7a::1", port: 3780)],
                            code: "SAMRABBT", name: "Mac & Co")
        let parsed = try #require(PairLink(url: link.url))
        #expect(parsed == link)
    }

    @Test func acceptsLenientCodesAndPlusSpaces() throws {
        let link = try #require(PairLink(string: "samrabbit://pair?c=samr-abbt&h=127.0.0.1:3799&n=Fake+Mac"))
        #expect(link.code == "SAMRABBT")
        #expect(link.name == "Fake Mac")
        let bare = try #require(PairLink(string: "SAMRABBIT://PAIR?h=mac.local&c=SAMRABBT"))
        #expect(bare.hosts == [BridgeHost(host: "mac.local", port: 3780)])
        #expect(bare.name == nil)
    }

    @Test(arguments: [
        "samrabbit://pair?h=1.2.3.4:3780&c=SAMRABB", // 7 characters
        "samrabbit://pair?h=1.2.3.4:3780&c=SAMRABBO", // O is ambiguous
        "samrabbit://pair?h=1.2.3.4:3780&c=SAMRABB1", // 1 is ambiguous
        "samrabbit://pair?h=1.2.3.4:3780&c=SAMRABBI", // I is ambiguous
        "samrabbit://pair?c=SAMRABBT", // no host
        "samrabbit://pair?h=1.2.3.4:99999&c=SAMRABBT", // bad port
        "samrabbit://ask?h=1.2.3.4&c=SAMRABBT", // not a pair link
        "https://pair?h=1.2.3.4&c=SAMRABBT", // wrong scheme
        "samrabbit://pair?h=bad host!&c=SAMRABBT",
    ])
    func rejectsBadLinks(_ text: String) {
        #expect(PairLink(string: text) == nil)
    }

    @Test func dropsDuplicateHosts() throws {
        let link = try #require(PairLink(string: "samrabbit://pair?h=1.2.3.4:3780,1.2.3.4:3780,1.2.3.4&c=SAMRABBT"))
        #expect(link.hosts.count == 1)
    }

    @Test(arguments: [
        ("192.168.1.183", "192.168.1.183:3780"),
        ("192.168.1.183:3781", "192.168.1.183:3781"),
        ("http://192.168.1.183:3780/health", "192.168.1.183:3780"),
        (" Mac.Local ", "mac.local:3780"),
        ("[fd7a:115c::5]:3780", "[fd7a:115c::5]:3780"),
    ])
    func hostParsing(input: String, expected: String) throws {
        let host = try #require(BridgeHost(parsing: input))
        #expect(host.description == expected)
        #expect(host.baseURL != nil)
    }

    @Test(arguments: ["", "ftp://x", "a:b:c", "host:0", "-host", "white space"])
    func badHosts(_ input: String) {
        #expect(BridgeHost(parsing: input) == nil)
    }

    @Test func codeHelpers() {
        #expect(PairingCode.normalize("k7mq 2zxa") == "K7MQ2ZXA")
        #expect(PairingCode.normalize("K7MQ-2ZX0") == nil)
        #expect(PairingCode.display("K7MQ2ZXA") == "K7MQ-2ZXA")
    }
}

@Suite("Server-sent events")
struct ServerSentEventTests {
    @Test func parsesTheBridgeStream() {
        var parser = ServerSentEventParser()
        let wire = "retry: 2000\n: ready 31\n\nid: 32\nevent: sync\ndata: {\"type\":\"message.user\",\"text\":\"hi\",\"cursor\":32}\n\n: heartbeat\n\n"
        let messages = parser.feed(wire)
        let items = messages.compactMap(SyncStreamItem.init)
        #expect(items.count == 3)
        #expect(items[0] == .ready(cursor: 31))
        guard case .event(let event) = items[1] else {
            Issue.record("expected an event")
            return
        }
        #expect(event.type == "message.user")
        #expect(event.cursor == 32)
        #expect(items[2] == .heartbeat)
    }

    @Test func handlesCRLFMultilineDataAndSplitChunks() {
        var parser = ServerSentEventParser()
        var messages: [ServerSentEvent] = []
        for chunk in ["id: 7\r\nevent: sync\r\ndata: {\"type\":", "\"x\",\r\ndata: \"cursor\":7}\r", "\n\r\n"] {
            messages += parser.feed(chunk)
        }
        #expect(messages.count == 1)
        #expect(messages[0].id == "7")
        #expect(messages[0].data == "{\"type\":\"x\",\n\"cursor\":7}")
    }

    @Test func idOnlyBecomesTheCursorWhenMissing() {
        var parser = ServerSentEventParser()
        let items = parser.feed("id: 99\nevent: sync\ndata: {\"type\":\"image\",\"blobId\":\"sha256:1\"}\n\n").compactMap(SyncStreamItem.init)
        guard case .event(let event)? = items.first else {
            Issue.record("expected an event")
            return
        }
        #expect(event.cursor == 99)
    }
}

@Suite("Notifications")
struct AlertPlannerTests {
    func thread(_ id: String, _ status: ThreadStatus, pending: String? = nil) -> TaskThread {
        TaskThread(threadId: id, title: "Task \(id)", status: status, summary: "summary",
                   pending: pending.map { PendingAction(kind: status == .needsApproval ? .approval : .question, text: $0) })
    }

    @Test func announcesNewNeedsYouOnce() {
        let threads = [thread("a", .needsApproval, pending: "Deploy?"), thread("b", .working)]
        let first = AlertPlanner.plan(threads: threads, announced: [], tracked: [])
        #expect(first.alerts.map(\.kind) == [.needsYou])
        #expect(first.alerts.first?.body == "Deploy?")
        let second = AlertPlanner.plan(threads: threads, announced: first.announced, tracked: [])
        #expect(second.alerts.isEmpty)
        // A new question on the same thread is new.
        let third = AlertPlanner.plan(threads: [thread("a", .needsApproval, pending: "Deploy prod?")],
                                      announced: second.announced, tracked: [])
        #expect(third.alerts.count == 1)
    }

    @Test func reportsTrackedTasksThatFinish() {
        let tracked = [TrackedTask(threadId: "x", title: "X", projectName: nil, startedAt: .now),
                       TrackedTask(threadId: "y", title: "Y", projectName: nil, startedAt: .now),
                       TrackedTask(threadId: "z", title: "Z", projectName: nil, startedAt: .now)]
        let plan = AlertPlanner.plan(threads: [thread("x", .done), thread("y", .working), thread("z", .error)],
                                     announced: [], tracked: tracked)
        #expect(plan.alerts.map(\.kind) == [.finished, .failed])
        #expect(plan.tracked.map(\.threadId) == ["y"])
    }

    @Test func trackerKeepsTasksInTheAppGroup() {
        let tracker = TaskTracker(container: .temporary())
        tracker.track(TrackedTask(threadId: "a", title: "A", projectName: "P", startedAt: .now))
        tracker.track(TrackedTask(threadId: "a", title: "A2", projectName: "P", startedAt: .now))
        #expect(tracker.tasks.map(\.title) == ["A2"])
        tracker.untrack("a")
        #expect(tracker.tasks.isEmpty)
    }
}
