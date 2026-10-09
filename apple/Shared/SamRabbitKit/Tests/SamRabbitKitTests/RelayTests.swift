#if os(macOS)
import Darwin
import Foundation
import Synchronization
import Testing
@testable import SamRabbitKit

/// The iPhone's half of the watch relay, in process: every request goes through the same
/// `WatchRelay` message encoding as `WCSession.sendMessage` and is performed by the phone's
/// `BridgeAccount.performRelayed` (with the watch's own child token), exactly like
/// `iOS/App/WatchLink.swift`.
final class PhoneRelayStandIn: BridgeRelay, @unchecked Sendable {
    struct State {
        var prefersRelay = false
        var down = false
        var answerZero = false
        var relayed: [String] = []
        var bodies: [String] = []
        var direct: [Bool] = []
    }

    let phone: BridgeAccount
    let state = Mutex(State())

    init(phone: BridgeAccount) { self.phone = phone }

    var prefersRelay: Bool { state.withLock { $0.prefersRelay } }

    func noteDirectRoute(worked: Bool) { state.withLock { $0.direct.append(worked) } }

    func relay(_ request: BridgeRequest) async throws -> (status: Int, body: Data) {
        let (down, zero) = state.withLock { ($0.down, $0.answerZero) }
        if down { throw BridgeError.unreachable("phone not reachable") }
        guard let decoded = WatchRelay.Request(message: WatchRelay.Request(request).message) else { return (400, Data()) }
        state.withLock {
            $0.relayed.append("\(decoded.method) \(decoded.path)")
            $0.bodies.append(decoded.body.map { String(decoding: $0, as: UTF8.self) } ?? "")
        }
        if zero { return (0, Data()) }
        let answer = await phone.performRelayed(decoded)
        let received = WatchRelay.Response(message: answer.message)!
        return (received.status, received.body)
    }

    /// The watch's voice relay, like `PhoneLink` + `WatchLink`: the recording in chunks through the
    /// `sendMessage` dictionaries, put together by the phone's `VoiceRelay.Assembler`, uploaded with the
    /// watch's own token.
    func relayTranscription(_ audio: Data, contentType: String, language: String?) async throws -> (status: Int, body: Data) {
        let (down, zero) = state.withLock { ($0.down, $0.answerZero) }
        if down { throw BridgeError.unreachable("phone not reachable") }
        let assembler = VoiceRelay.Assembler()
        let chunks = VoiceRelay.chunks(of: audio, contentType: contentType, language: language)
        state.withLock { $0.relayed.append("VOICE \(chunks.count) chunks") }
        for chunk in chunks {
            guard let received = VoiceRelay.Chunk(message: chunk.message) else { return (400, Data()) }
            switch assembler.add(received) {
            case .waiting(let count):
                guard count == chunk.seq + 1, !chunk.isLast else { return (400, Data()) }
            case .refused(let response):
                return (response.status, response.body)
            case .complete(let upload):
                if zero { return (0, Data()) }
                let answer = await phone.performRelayedTranscription(upload)
                let reply = WatchRelay.Response(message: answer.message)!
                return (reply.status, reply.body)
            }
        }
        return (0, Data())
    }
}

/// A TCP port that accepts connections and never answers (a Mac that hangs), or a closed port.
final class SilentPort: @unchecked Sendable {
    let fd: Int32
    let port: Int

    init(listening: Bool) throws {
        let socketFD = socket(AF_INET, SOCK_STREAM, 0)
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = 0
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(socketFD, $0, length) }
        }
        guard bound == 0 else { throw BridgeError.unreachable("bind") }
        _ = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(socketFD, $0, &length) }
        }
        if listening {
            guard listen(socketFD, 8) == 0 else { throw BridgeError.unreachable("listen") }
            fd = socketFD
        } else {
            close(socketFD) // nothing listens there: connections are refused
            fd = -1
        }
        port = Int(UInt16(bigEndian: address.sin_port))
    }

    var host: BridgeHost { BridgeHost(host: "127.0.0.1", port: port) }

    deinit { if fd >= 0 { close(fd) } }
}

@Suite("Watch relay through the iPhone", .serialized)
struct RelayTests {
    /// The phone paired with a fake bridge and provisioned its watch (child token); a watch client
    /// whose only address is `watchHost`.
    func setUp(watchHost: BridgeHost? = nil) async throws -> (FakeBridgeProcess, PhoneRelayStandIn, BridgeClient) {
        let (bridge, relay, watch, _) = try await setUpWithContext(watchHost: watchHost)
        return (bridge, relay, watch)
    }

    func setUpWithContext(watchHost: BridgeHost? = nil) async throws
        -> (FakeBridgeProcess, PhoneRelayStandIn, BridgeClient, WatchContext) {
        let bridge = try FakeBridgeProcess()
        let (_, phone) = try await bridge.pairedClient()
        let relay = PhoneRelayStandIn(phone: phone)
        let context = try await phone.provisionWatch()
        let account = BridgeAccount(container: .temporary(), secrets: MemorySecretStore(), relay: relay)
        let pairing = BridgePairing(hosts: [watchHost ?? bridge.host], deviceId: context.deviceId,
                                    bridgeName: context.bridgeName, bridgeVersion: nil, deviceName: "Apple Watch")
        #expect(account.save(pairing, token: context.token))
        return (bridge, relay, try account.requireClient(), context)
    }

    @Test func directWhenTheMacAnswers() async throws {
        let (bridge, relay, watch) = try await setUp()
        let summary = try await watch.summary()
        #expect(summary.t3.needsYou == 2)
        #expect(relay.state.withLock { $0.relayed.isEmpty })
        #expect(relay.state.withLock { $0.direct } == [true])
        _ = bridge
    }

    @Test func relaysWhenNoAddressAnswers() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, relay, watch) = try await setUp(watchHost: closed.host)
        let summary = try await watch.summary()
        #expect(summary.mac.name == "Samin's MacBook Pro")
        // POSTs that never connected are relayed too, once.
        try await watch.respond(threadId: "t_deploy24", requestId: "req_deploy_1", approve: true)
        // The request id travels through the phone unchanged.
        let sent = try JSONDecoder().decode(JSONValue.self, from: Data(relay.state.withLock { $0.bodies[1] }.utf8))
        #expect(sent["requestId"].string == "req_deploy_1")
        #expect(sent["decision"].string == "approve")
        let threads = try await watch.threads(filter: .needsYou)
        #expect(!threads.contains { $0.threadId == "t_deploy24" && $0.status == .needsApproval })
        #expect(relay.state.withLock { $0.relayed } == [
            "GET /v1/mobile/summary", "POST /v1/mobile/t3/threads/t_deploy24/respond", "GET /v1/mobile/t3/threads",
        ])
        #expect(relay.state.withLock { $0.direct } == [false, false, false])
        _ = bridge
    }

    @Test func prefersTheRelayRightAfterADirectFailure() async throws {
        let (bridge, relay, watch) = try await setUp()
        relay.state.withLock { $0.prefersRelay = true }
        _ = try await watch.summary()
        #expect(relay.state.withLock { $0.relayed } == ["GET /v1/mobile/summary"])
        #expect(relay.state.withLock { $0.direct }.isEmpty)
        // The relay is down (phone out of range): straight to the Mac.
        relay.state.withLock { $0.down = true }
        _ = try await watch.summary()
        #expect(relay.state.withLock { $0.direct } == [true])
        _ = bridge
    }

    @Test func relayedErrorsKeepTheBridgeEnvelope() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, _, watch) = try await setUp(watchHost: closed.host)
        await #expect(throws: BridgeError.server(status: 404, code: "thread_not_found", message: "No such task.",
                                                 retryable: false)) {
            _ = try await watch.thread("nope")
        }
        _ = bridge
    }

    @Test func aStaleApprovalRelayedThroughThePhoneIsRefused() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, _, watch) = try await setUp(watchHost: closed.host)
        _ = try await bridge.control("rerequest", ["threadId": "t_deploy24"])
        await #expect { try await watch.respond(threadId: "t_deploy24", requestId: "req_deploy_1", approve: true) }
            throws: { ($0 as? BridgeError)?.isStaleRequest == true }
    }

    @Test func aRelayThatCouldNotReachTheMacIsNotRetriedDirectly() async throws {
        let (bridge, relay, watch) = try await setUp()
        relay.state.withLock {
            $0.prefersRelay = true
            $0.answerZero = true
        }
        await #expect(throws: BridgeError.unreachable("relay")) {
            _ = try await watch.block(minutes: 30)
        }
        #expect(relay.state.withLock { $0.direct }.isEmpty)
        _ = bridge
    }

    @Test func nothingWorksWhenBothRoutesAreDown() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, relay, watch) = try await setUp(watchHost: closed.host)
        relay.state.withLock { $0.down = true }
        await #expect {
            _ = try await watch.summary()
        } throws: { error in
            if case .unreachable = error as? BridgeError { return true }
            return false
        }
        _ = bridge
    }

    @Test func aPostThatMayHaveArrivedIsNeverSentTwice() async throws {
        let hanging = try SilentPort(listening: true)
        let (bridge, relay, watch) = try await setUp(watchHost: hanging.host)
        // The Mac accepted the connection but never answered: the task may exist already.
        await #expect {
            _ = try await watch.json(BridgeRequest.post("/v1/mobile/t3/threads", body: ["text": .string("Draft the memo")],
                                                        timeout: 1)) as CreatedThread
        } throws: { error in
            if case .unreachable = error as? BridgeError { return true }
            return false
        }
        #expect(relay.state.withLock { $0.relayed }.isEmpty)
        // A read is safe to repeat, so it goes through the phone.
        let summary: MobileSummary = try await watch.json(.get("/v1/mobile/summary", timeout: 1))
        #expect(summary.t3.working == 2)
        #expect(relay.state.withLock { $0.relayed } == ["GET /v1/mobile/summary"])
        _ = bridge
    }

    /// The phone performs relayed requests with the watch's own child token, never its own: once
    /// the watch is revoked on the Mac, its relayed requests fail with 401 like direct ones, while
    /// the phone itself keeps working.
    @Test func relayedRequestsUseTheWatchTokenAndStopWhenTheWatchIsRevoked() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, relay, watch, context) = try await setUpWithContext(watchHost: closed.host)
        #expect(relay.phone.watchToken == context.token)
        #expect(relay.phone.watchToken != relay.phone.token)
        #expect(try await watch.summary().t3.needsYou == 2) // through the phone
        try await bridge.revoke(context.deviceId)
        await #expect(throws: BridgeError.unauthorized) { _ = try await watch.summary() }
        await #expect(throws: BridgeError.unauthorized) {
            try await watch.respond(threadId: "t_deploy24", requestId: "req_deploy_1", approve: true)
        }
        // Refused before T3 saw it: the approval is still waiting.
        let phone = try relay.phone.requireClient()
        #expect(try await phone.thread("t_deploy24").thread.status == .needsApproval)
        #expect(try await phone.summary().t3.needsYou == 2)
        #expect(relay.state.withLock { $0.relayed }.count == 3)
    }

    /// A phone that has no watch token (never provisioned, or forgot it) refuses with 401 too, and
    /// pairing, unpairing and device management are never relayed.
    @Test func thePhoneRelaysOnlyTheWatchsOwnRequests() async throws {
        let bridge = try FakeBridgeProcess()
        let (_, phone) = try await bridge.pairedClient()
        let summary = WatchRelay.Request(.get("/v1/mobile/summary"))
        #expect(await phone.performRelayed(summary).status == 401) // no watch token yet
        let context = try await phone.provisionWatch()
        #expect(await phone.performRelayed(summary).status == 200)
        for refused in [BridgeRequest.post("/v1/mobile/unpair", body: [:]),
                        .post("/v1/mobile/devices/child", body: ["name": "Apple Watch"]),
                        .get("/v1/mobile/devices"), .post("/v1/mobile/pair", body: ["code": "SAMRABBT"]),
                        .post("/v1/mobile/pairing/start", body: [:]), .get("/v1/mobile/stream"),
                        .get("/v1/mobile/t3/../devices"), .get("/v1/mobile/t3%2F..%2Fdevices"), .get("/health")] {
            let request = WatchRelay.Request(refused)
            #expect(!request.allowed, "\(refused.method) \(refused.path)")
            let answer = await phone.performRelayed(request)
            #expect(answer.status == 403)
            #expect(String(decoding: answer.body, as: UTF8.self).contains("relay_forbidden"))
        }
        // The watch (and the phone) are still paired: the refused unpair never reached the Mac.
        #expect(try await BridgeClient(hosts: [bridge.host], token: context.token).summary().t3.available)
        #expect(try await phone.requireClient().summary().t3.available)
        phone.forgetWatch()
        #expect(await phone.performRelayed(summary).status == 401)
    }
}
#endif
