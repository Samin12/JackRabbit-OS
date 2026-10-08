#if os(macOS)
import Foundation
import Testing
@testable import SamRabbitKit

/// A fake bridge (apple/dev/fake_bridge.py) in its own process on a free port, for one test.
final class FakeBridgeProcess: @unchecked Sendable {
    let process = Process()
    let port: Int
    var host: BridgeHost { BridgeHost(host: "127.0.0.1", port: port) }
    var base: URL { URL(string: "http://127.0.0.1:\(port)")! }

    init() throws {
        let script = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("dev/fake_bridge.py")
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = ["-I", script.path, "--port", "0", "--print-port", "--quiet", "--state", "", "--no-chatter"]
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        try process.run()
        var buffer = Data()
        let deadline = Date.now.addingTimeInterval(10)
        var found: Int?
        while found == nil, Date.now < deadline {
            let chunk = output.fileHandleForReading.availableData
            if chunk.isEmpty { break }
            buffer.append(chunk)
            if let text = String(data: buffer, encoding: .utf8),
               let line = text.split(separator: "\n").first(where: { $0.hasPrefix("PORT ") }) {
                found = Int(line.dropFirst(5))
            }
        }
        guard let found else {
            process.terminate()
            throw BridgeError.unreachable("the fake bridge did not start")
        }
        port = found
    }

    deinit {
        if process.isRunning { process.terminate() }
    }

    /// `POST /__fake/<name>` (reset, settle, chatter).
    func control(_ name: String) async throws {
        var request = URLRequest(url: base.appendingPathComponent("__fake/\(name)"))
        request.httpMethod = "POST"
        _ = try await URLSession.shared.data(for: request)
    }

    func journal() async throws -> [String] {
        let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent("__fake/journal"))
        return try JSONDecoder().decode(JSONValue.self, from: data)["lines"].array?.compactMap(\.string) ?? []
    }

    /// Pairs and returns a ready client (state in a temporary App Group stand-in).
    func pairedClient() async throws -> (BridgeClient, BridgeAccount) {
        let account = BridgeAccount(container: .temporary(), secrets: MemorySecretStore())
        let link = PairLink(hosts: [host], code: "SAMRABBT", name: "Fake")
        _ = try await Pairer.pair(link: link, deviceName: "Test iPhone", account: account)
        return (try account.requireClient(), account)
    }
}

@Suite("API client against the fake bridge", .serialized)
struct FakeBridgeTests {
    @Test func pairsAndReadsTheSummary() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, account) = try await bridge.pairedClient()
        #expect(account.isPaired)
        #expect(account.pairing?.bridgeName == "Samin's MacBook Pro")
        #expect(account.pairing?.deviceName == "Test iPhone")
        let summary = try await client.summary()
        #expect(summary.mac.name == "Samin's MacBook Pro")
        #expect(summary.r1.live)
        #expect(summary.t3.needsYou == 2)
        #expect(summary.t3.working == 2)
        #expect(summary.t3.threads.first?.status == .needsApproval)
        #expect(!summary.calendar.next.isEmpty)
        #expect(summary.latestConversation?.conversationId == "c_focus0001")
        let health = try await client.health()
        #expect(health.ok && health.mobileAvailable)
    }

    @Test func wrongCodeAndBadTokenAreRefused() async throws {
        let bridge = try FakeBridgeProcess()
        let account = BridgeAccount(container: .temporary(), secrets: MemorySecretStore())
        await #expect(throws: BridgeError.self) {
            _ = try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "WRNGCDE2"), deviceName: "x", account: account)
        }
        #expect(!account.isPaired)
        let stranger = BridgeClient(hosts: [bridge.host], token: "srm_not-a-real-token")
        await #expect(throws: BridgeError.unauthorized) { _ = try await stranger.summary() }
        let unpaired = BridgeClient(hosts: [bridge.host], token: nil)
        await #expect(throws: BridgeError.notPaired) { _ = try await unpaired.summary() }
    }

    @Test func pairingTriesHostsInOrderAndRemembersTheOneThatAnswered() async throws {
        let bridge = try FakeBridgeProcess()
        let account = BridgeAccount(container: .temporary(), secrets: MemorySecretStore())
        let dead = BridgeHost(host: "127.0.0.1", port: 1) // refused at once
        let pairing = try await Pairer.pair(link: PairLink(hosts: [dead, bridge.host], code: "SAMRABBT"),
                                            deviceName: "x", account: account)
        #expect(pairing.hosts == [bridge.host, dead])
        // A client whose first address is dead fails over (GET) and promotes the live one.
        account.updateHosts([dead, bridge.host])
        let client = try account.requireClient()
        _ = try await client.summary()
        #expect(client.currentHost == bridge.host)
        #expect(account.pairing?.hosts.first == bridge.host)
    }

    @Test func tasksRespondAndProgress() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let needing = try await client.threads(filter: .needsYou)
        #expect(Set(needing.map(\.threadId)) == ["t_deploy24", "t_loginfix"])
        let question = try #require(needing.first { $0.pending?.kind == .question })
        #expect(question.pending?.options.map(\.value) == ["/home", "/pricing", "/dashboard"])

        let detail = try await client.thread("t_deploy24")
        #expect(detail.pending?.kind == .approval)
        #expect(detail.messages.count == 2)

        try await client.respond(threadId: "t_deploy24", approve: true)
        #expect(try await client.thread("t_deploy24").thread.status == .working)
        try await client.respond(threadId: "t_loginfix", answer: "/home")
        try await bridge.control("settle")
        #expect(try await client.thread("t_deploy24").thread.status == .done)
        #expect(try await client.thread("t_loginfix").thread.status == .done)

        let created = try await client.createThread(text: "fix the flaky swift test")
        #expect(created.projectName == "SamRabbit")
        #expect(try await client.thread(created.threadId).thread.status == .working)
        try await client.sendMessage(threadId: "t_weekly", text: "Include the bug count")
        try await client.stop(threadId: "t_calsync")
        #expect(try await client.thread("t_calsync").thread.status == .idle)
        await #expect(throws: BridgeError.self) { try await client.stop(threadId: "t_pricing") }
        let projects = try await client.projects()
        #expect(projects.map(\.name) == ["Assistant", "Website", "SamRabbit"])
        let all = try await client.threads()
        #expect(all.count == 8)
    }

    @Test func conversationsEventsAndTheTimeline() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let page = try await client.conversations()
        #expect(page.conversations.map(\.conversationId) == ["c_focus0001", "c_maccheck2", "c_morning03"])
        #expect(page.conversations.first?.live == true)
        let search = try await client.conversations(query: "screen")
        #expect(search.conversations.map(\.conversationId) == ["c_maccheck2"])

        let events = try await client.allEvents(conversationId: "c_focus0001")
        var timeline = ConversationTimeline(conversationId: "c_focus0001")
        timeline.apply(events.events)
        let generated = timeline.sorted.compactMap { item -> GeneratedUIItem? in
            if case .generatedUI(let ui) = item.content { return ui }
            return nil
        }
        let ui = try #require(generated.first)
        #expect(ui.status == .ready)
        let blob = try await client.blob(try #require(ui.imageBlobId))
        #expect(blob.starts(with: [0xFF, 0xD8])) // JPEG
        let artifact = try await client.artifact(ui.artifactId)
        #expect(artifact.title == "Focus this week")
        let html = try await client.artifactDocument(ui.artifactId)
        #expect(html.contains("<!DOCTYPE html>"))

        let mac = try await client.allEvents(conversationId: "c_maccheck2")
        var macTimeline = ConversationTimeline(conversationId: "c_maccheck2")
        macTimeline.apply(mac.events)
        #expect(macTimeline.sorted.contains { item in
            if case .image(let image) = item.content { return image.source == "mac_screenshot" }
            return false
        })
    }

    @Test func streamDeliversNewEvents() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let start = try await client.conversations().cursor
        let received = Task { () -> [String] in
            var types: [String] = []
            for try await item in client.stream(after: start) {
                if case .event(let event) = item {
                    types.append(event.type)
                    if event.type == "message.assistant.done" { break }
                }
            }
            return types
        }
        try await Task.sleep(for: .milliseconds(300))
        try await bridge.control("chatter")
        try await bridge.control("settle")
        let result = try await withThrowingTaskGroup(of: [String].self) { group in
            group.addTask { try await received.value }
            group.addTask {
                try await Task.sleep(for: .seconds(10))
                received.cancel()
                return []
            }
            let first = try await group.next() ?? []
            group.cancelAll()
            return first
        }
        #expect(result.first == "message.user")
        #expect(result.contains("message.assistant.delta"))
        #expect(result.last == "message.assistant.done")
    }

    @Test func generatedUIsCalendarJournalAndMac() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let id = try await client.generateUI(prompt: "release checklist for 2.4")
        #expect(try await client.artifact(id).status == .generating)
        await #expect(throws: BridgeError.self) { _ = try await client.artifactImage(id) }
        try await bridge.control("settle")
        let ready = try await client.waitForArtifact(id, timeout: 5, interval: 0.2)
        #expect(ready.status == .ready)
        #expect(ready.title == "Release 2.4 checklist")
        #expect(try await client.artifactImage(id).count > 10_000)

        let agenda = try await client.agenda(hours: 24)
        #expect(agenda.events.contains { $0.title == "Standup" })
        #expect(agenda.events.contains { $0.allDay })
        let block = try await client.block(minutes: 30)
        let event = try #require(block.event)
        #expect(event.title == "Focus")
        let length = try #require(event.endsAt).timeIntervalSince(try #require(event.startsAt))
        #expect(length == 1800)
        let custom = try await client.createEvent(title: "Plan", startsAt: .now.addingTimeInterval(3600),
                                                  endsAt: .now.addingTimeInterval(5400))
        #expect(custom.event?.title == "Plan")

        let note = try await client.addJournalNote("Felt focused today")
        #expect(note.recorded)
        let lines = try await bridge.journal()
        #expect(lines.count == 1)
        #expect(lines[0].hasSuffix("Felt focused today"))
        #expect(lines[0].hasPrefix("**"))

        let mac = try await client.macState()
        #expect(mac.front?.app == "T3 Code")
        #expect(try await client.openOnMac(app: "notes").app == "Notes")
        #expect(try await client.openOnMac(url: "https://example.com").opened == "url")
        await #expect(throws: BridgeError.self) { _ = try await client.openOnMac(app: "No Such App") }
        let shot = try await client.screenshot()
        #expect(shot.starts(with: [0xFF, 0xD8]))

        let child = try await client.childDevice(name: "Apple Watch")
        let watch = BridgeClient(hosts: [bridge.host], token: child.token)
        #expect(try await watch.summary().t3.available)
    }

    @Test func actionsUpdateTheSharedCacheAndTracker() async throws {
        let bridge = try FakeBridgeProcess()
        let (_, account) = try await bridge.pairedClient()
        let container = SharedContainer.temporary()
        let actions = SamRabbitActions(account: account, cache: SummaryCache(container: container),
                                       tracker: TaskTracker(container: container))
        let created = try await actions.ask("write the weekly update")
        #expect(actions.tracker.tasks.map(\.threadId) == [created.threadId])
        #expect(actions.cache.load()?.summary.t3.working == 3)
        try await bridge.control("settle")
        let summary = try await actions.refresh()
        let plan = AlertPlanner.plan(threads: summary.t3.threads + [try await account.requireClient().thread(created.threadId).thread],
                                     announced: [], tracked: actions.tracker.tasks)
        #expect(plan.alerts.contains { $0.kind == .finished && $0.threadId == created.threadId })
        #expect(plan.alerts.filter { $0.kind == .needsYou }.count == 2)
        let spoken = await actions.whatNeedsMe()
        #expect(spoken.hasPrefix("Two tasks need you"))
    }
}
#endif
