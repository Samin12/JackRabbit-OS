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

    /// `POST /__fake/<name>` with a JSON body (rerequest, t3); returns the answer.
    @discardableResult
    func control(_ name: String, _ body: [String: JSONValue]) async throws -> JSONValue {
        var request = URLRequest(url: base.appendingPathComponent("__fake/\(name)"))
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(JSONValue.object(body))
        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }

    /// The desktop app's `DELETE /v1/mobile/devices/<id>` (loopback + desktop token): revokes a device.
    func revoke(_ deviceId: String) async throws {
        var request = URLRequest(url: base.appendingPathComponent("v1/mobile/devices/\(deviceId)"))
        request.httpMethod = "DELETE"
        request.setValue("fake-desktop-token", forHTTPHeaderField: "X-SamRabbit-Desktop")
        let (_, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw BridgeError.invalidResponse("revoke") }
    }

    /// The desktop app's `GET /v1/mobile/devices`: the paired devices' ids.
    func deviceIds() async throws -> Set<String> {
        var request = URLRequest(url: base.appendingPathComponent("v1/mobile/devices"))
        request.setValue("fake-desktop-token", forHTTPHeaderField: "X-SamRabbit-Desktop")
        let (data, _) = try await URLSession.shared.data(for: request)
        let devices = try JSONDecoder().decode(JSONValue.self, from: data)["devices"].array ?? []
        return Set(devices.compactMap { $0["deviceId"].string })
    }

    /// `GET /__fake/transcribe`: the mode and the recordings that arrived (size, type, lang, device).
    func transcribeState() async throws -> JSONValue {
        let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent("__fake/transcribe"))
        return try JSONDecoder().decode(JSONValue.self, from: data)
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

    /// A wrong or expired code (401 `invalid_code`) says so, distinct from a token the Mac stopped
    /// accepting (`.unauthorized`, also 401); ten wrong codes lock pairing (429).
    @Test func wrongCodeAndBadTokenAreRefused() async throws {
        let bridge = try FakeBridgeProcess()
        let account = BridgeAccount(container: .temporary(), secrets: MemorySecretStore())
        let wrong = await BridgeError.capture {
            try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "WRNGCDE2"), deviceName: "x", account: account)
        }
        guard case .failure(let error) = wrong else { Issue.record("a wrong code paired"); return }
        #expect(error != .unauthorized)
        #expect(error.code == "invalid_code")
        #expect(error.isPairingCodeRejected)
        #expect(error.errorDescription == "That code is wrong or expired — get a new one on your Mac.")
        #expect(error.shortDescription == "That code didn't work")
        #expect(!account.isPaired)

        let stranger = BridgeClient(hosts: [bridge.host], token: "srm_not-a-real-token")
        await #expect(throws: BridgeError.unauthorized) { _ = try await stranger.summary() }
        #expect(BridgeError.unauthorized.errorDescription != error.errorDescription)
        #expect(!BridgeError.unauthorized.isPairingCodeRejected)
        let unpaired = BridgeClient(hosts: [bridge.host], token: nil)
        await #expect(throws: BridgeError.notPaired) { _ = try await unpaired.summary() }

        // Nine more wrong codes, then even the right one is refused for a while.
        for _ in 0..<9 {
            _ = await BridgeError.capture {
                try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "WRNGCDE2"), deviceName: "x", account: account)
            }
        }
        let locked = await BridgeError.capture {
            try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "SAMRABBT"), deviceName: "x", account: account)
        }
        guard case .failure(let limit) = locked else { Issue.record("pairing was not rate limited"); return }
        #expect(limit.isPairingRateLimited)
        #expect(!limit.isPairingCodeRejected)
        #expect(limit.shortDescription == "Too many tries")
        #expect(limit.errorDescription?.hasPrefix("Too many wrong codes.") == true)
        #expect(!account.isPaired)
    }

    /// Unpair tells the Mac (`POST /v1/mobile/unpair`): the phone's and its watch's tokens stop
    /// working; the local pairing is gone either way, also when the Mac can't be reached.
    @Test func unpairRevokesThePhoneAndItsWatchOnTheMac() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, account) = try await bridge.pairedClient()
        let phoneToken = try #require(account.token)
        let watch = try await account.provisionWatch()
        #expect(try await bridge.deviceIds().count == 2)
        #expect(await account.unpair())
        #expect(!account.isPaired)
        #expect(account.pairing == nil && account.token == nil && account.watchToken == nil)
        #expect(account.client() == nil)
        #expect(try await bridge.deviceIds().isEmpty)
        await #expect(throws: BridgeError.unauthorized) {
            _ = try await BridgeClient(hosts: [bridge.host], token: phoneToken).summary()
        }
        await #expect(throws: BridgeError.unauthorized) {
            _ = try await BridgeClient(hosts: [bridge.host], token: watch.token).summary()
        }
        _ = client

        // The Mac is away: unpairing still forgets everything here.
        let (_, again) = try await bridge.pairedClient()
        again.updateHosts([BridgeHost(host: "127.0.0.1", port: 1)])
        #expect(await again.unpair(timeout: 2) == false)
        #expect(!again.isPaired && again.pairing == nil)
    }

    /// Provisioning the watch again replaces its token: the old one stops working on the Mac. Without
    /// `reissue` the same token is sent again (no new device).
    @Test func reprovisioningTheWatchReplacesItsOldToken() async throws {
        let bridge = try FakeBridgeProcess()
        let (_, account) = try await bridge.pairedClient()
        let first = try await account.provisionWatch()
        let same = try await account.provisionWatch()
        #expect(same.token == first.token)
        #expect(try await bridge.deviceIds().count == 2)
        let second = try await account.provisionWatch(reissue: true)
        #expect(second.token != first.token)
        #expect(account.watchToken == second.token)
        #expect(account.watchContext?.token.isEmpty == true) // the token itself stays in the secret store
        #expect(try await bridge.deviceIds() == [try #require(account.pairing?.deviceId), second.deviceId])
        await #expect(throws: BridgeError.unauthorized) {
            _ = try await BridgeClient(hosts: [bridge.host], token: first.token).summary()
        }
        #expect(try await BridgeClient(hosts: [bridge.host], token: second.token).summary().t3.available)

        // The phone pairs again (a new device on the Mac): its new watch token retires the old
        // watch's, which belonged to the old phone record.
        _ = try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "SAMRABBT"), deviceName: "Again", account: account)
        let third = try await account.provisionWatch(reissue: true)
        await #expect(throws: BridgeError.unauthorized) {
            _ = try await BridgeClient(hosts: [bridge.host], token: second.token).summary()
        }
        #expect(try await BridgeClient(hosts: [bridge.host], token: third.token).summary().t3.available)
        // Only an iPhone mints watch tokens.
        await #expect { _ = try await BridgeClient(hosts: [bridge.host], token: third.token).childDevice(name: "x") }
            throws: { ($0 as? BridgeError)?.code == "forbidden" }
    }

    /// One client per pairing: the same instance (and its URLSessions) for every request, a new one
    /// only when the token or the addresses change.
    @Test func theAccountReusesOneClient() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, account) = try await bridge.pairedClient()
        #expect(account.client() === client)
        #expect(try account.requireClient() === client)
        _ = try await client.summary()
        #expect(account.client() === client)
        let dead = BridgeHost(host: "127.0.0.1", port: 1)
        account.updateHosts([bridge.host, dead])
        let widened = try #require(account.client())
        #expect(widened !== client)
        #expect(account.client() === widened)
        // The client moving to the address that answered keeps the same instance.
        account.updateHosts([dead, bridge.host])
        let moving = try #require(account.client())
        _ = try await moving.summary()
        #expect(account.pairing?.hosts.first == bridge.host)
        #expect(account.client() === moving)
        _ = try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "SAMRABBT"), deviceName: "y", account: account)
        #expect(account.client() !== moving)
        _ = try await account.provisionWatch()
        let watch = try #require(account.watchClient())
        #expect(account.watchClient() === watch)
        #expect(watch !== account.client())
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
        #expect(question.pending?.requestId == "req_login_1")
        #expect(question.pending?.questionId == "q_landing")

        let detail = try await client.thread("t_deploy24")
        #expect(detail.pending?.kind == .approval)
        #expect(detail.pending?.requestId == "req_deploy_1")
        #expect(detail.messages.count == 2)

        try await client.respond(threadId: "t_deploy24", requestId: try #require(detail.pending?.requestId), approve: true)
        #expect(try await client.thread("t_deploy24").thread.status == .working)
        try await client.respond(threadId: "t_loginfix", requestId: "req_login_1", answer: "/home")
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

    /// Approval A was answered on another device and T3 asked approval B: the card still showing A
    /// must never approve B. The bridge refuses A's id with 409 `t3_request_not_pending`.
    @Test func aStaleCardNeverAnswersTheNextRequest() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let seen = try #require(try await client.threads(filter: .needsYou).first { $0.threadId == "t_deploy24" }?.pending)
        let next = try #require(try await bridge.control("rerequest", ["threadId": "t_deploy24"])["requestId"].string)
        #expect(next != seen.requestId)

        await #expect {
            try await client.respond(threadId: "t_deploy24", requestId: try #require(seen.requestId), approve: true)
        } throws: { error in
            (error as? BridgeError)?.isStaleRequest == true && (error as? BridgeError)?.shortDescription == "That request changed"
        }
        // B is still waiting, untouched, and shows its own id and text.
        let fresh = try await client.thread("t_deploy24")
        #expect(fresh.thread.status == .needsApproval)
        #expect(fresh.pending?.requestId == next)
        #expect(fresh.pending?.text.contains("production") == true)
        try await client.respond(threadId: "t_deploy24", requestId: next, approve: false)
        #expect(try await client.thread("t_deploy24").thread.status == .done)

        // The same for questions, and for a decision sent to a thread that only asks a question.
        let question = try #require(try await client.thread("t_loginfix").pending)
        await #expect { try await client.respond(threadId: "t_loginfix", requestId: try #require(question.requestId),
                                                 approve: true) } throws: { ($0 as? BridgeError)?.isStaleRequest == true }
        _ = try await bridge.control("rerequest", ["threadId": "t_loginfix"])
        await #expect { try await client.respond(threadId: "t_loginfix", requestId: try #require(question.requestId),
                                                 answer: "/home") } throws: { ($0 as? BridgeError)?.isStaleRequest == true }
        #expect(try await client.thread("t_loginfix").thread.status == .needsInput)
        // The shared actions send the id too.
        let account = try await bridge.pairedClient().1
        let actions = SamRabbitActions(account: account, cache: SummaryCache(container: .temporary()),
                                       tracker: TaskTracker(container: .temporary()))
        await #expect { try await actions.answer(threadId: "t_loginfix", requestId: try #require(question.requestId), "/home") }
            throws: { ($0 as? BridgeError)?.isStaleRequest == true }
        let current = try #require(try await client.thread("t_loginfix").pending?.requestId)
        try await actions.answer(threadId: "t_loginfix", requestId: current, "/pricing")
        #expect(try await client.thread("t_loginfix").thread.status == .working)
    }

    /// T3 Code not running: the summary still answers (t3.available = false), the thread list
    /// answers 503 `t3_unavailable`, which only the task areas show.
    @Test func t3DownLeavesTheSummaryWorking() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        try await bridge.control("t3", ["available": false])
        let summary = try await client.summary()
        #expect(!summary.t3.available)
        #expect(summary.mac.name == "Samin's MacBook Pro")
        #expect(!summary.calendar.next.isEmpty)
        let failure = await BridgeError.capture { try await client.threads() }
        guard case .failure(let error) = failure else { Issue.record("the thread list should fail"); return }
        #expect(error.code == "t3_unavailable")
        #expect(error.isTaskServiceDown)
        #expect(error.shortDescription == "T3 not connected")
        #expect(!BridgeError.unreachable("x").isTaskServiceDown)
        #expect(!BridgeError.server(status: 409, code: "t3_request_not_pending", message: "", retryable: false).isTaskServiceDown)
        let back = try await bridge.control("t3", ["available": true])
        #expect(back["available"].bool == true)
        #expect(try await client.threads().count == 7)
        #expect(try await client.summary().t3.available)
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

        let (status, body) = try await client.raw(.get("/v1/mobile/t3/threads/t_missing"))
        #expect(status == 404)
        #expect(String(decoding: body, as: UTF8.self).contains("thread_not_found"))
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
        // Summary threads carry no request ids: nothing in them is announced as needing you.
        let fromSummary = AlertPlanner.plan(threads: summary.t3.threads, announced: [:], tracked: [])
        #expect(fromSummary.alerts.isEmpty)
        let list = try await account.requireClient().threads()
        let plan = AlertPlanner.plan(threads: list + [try await account.requireClient().thread(created.threadId).thread],
                                     announced: [:], tracked: actions.tracker.tasks)
        #expect(plan.alerts.contains { $0.kind == .finished && $0.threadId == created.threadId })
        #expect(plan.alerts.filter { $0.kind == .needsYou }.count == 2)
        // The background refresh's list (needs-you filter + summary) agrees: nothing new to say.
        let waiting = try await account.requireClient().threads(filter: .needsYou)
        let background = AlertPlanner.plan(threads: waiting + summary.t3.threads, announced: plan.announced,
                                           tracked: plan.tracked)
        #expect(background.alerts.isEmpty)
        let spoken = await actions.whatNeedsMe()
        #expect(spoken.hasPrefix("Two tasks need you"))
    }
}
#endif
