#if os(macOS)
import Darwin
import Foundation
import Synchronization
import Testing
@testable import SamRabbitKit

/// The iPhone's half of the watch relay, in process: every request goes through the same
/// `WatchRelay` message encoding as `WCSession.sendMessage` and is performed with the phone's
/// own client (`BridgeClient.raw`), exactly like `iOS/App/WatchLink.swift`.
final class PhoneRelayStandIn: BridgeRelay, @unchecked Sendable {
    struct State {
        var prefersRelay = false
        var down = false
        var answerZero = false
        var relayed: [String] = []
        var direct: [Bool] = []
    }

    let phone: BridgeClient?
    let state = Mutex(State())

    init(phone: BridgeClient?) { self.phone = phone }

    var prefersRelay: Bool { state.withLock { $0.prefersRelay } }

    func noteDirectRoute(worked: Bool) { state.withLock { $0.direct.append(worked) } }

    func relay(_ request: BridgeRequest) async throws -> (status: Int, body: Data) {
        let (down, zero) = state.withLock { ($0.down, $0.answerZero) }
        if down { throw BridgeError.unreachable("phone not reachable") }
        guard let decoded = WatchRelay.Request(message: WatchRelay.Request(request).message), decoded.allowed else {
            return (400, Data())
        }
        state.withLock { $0.relayed.append("\(decoded.method) \(decoded.path)") }
        if zero { return (0, Data()) }
        let answer: WatchRelay.Response
        do {
            let (status, body) = try await phone!.raw(decoded.bridgeRequest)
            answer = WatchRelay.Response(status: status, body: body)
        } catch {
            answer = WatchRelay.Response(status: 0, body: Data())
        }
        let received = WatchRelay.Response(message: answer.message)!
        return (received.status, received.body)
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
    /// The phone paired with a fake bridge, and a watch client whose only address is `watchHost`.
    func setUp(watchHost: BridgeHost? = nil) async throws -> (FakeBridgeProcess, PhoneRelayStandIn, BridgeClient) {
        let bridge = try FakeBridgeProcess()
        let (phone, _) = try await bridge.pairedClient()
        let relay = PhoneRelayStandIn(phone: phone)
        let child = try await phone.childDevice(name: "Apple Watch")
        let account = BridgeAccount(container: .temporary(), secrets: MemorySecretStore(), relay: relay)
        let pairing = BridgePairing(hosts: [watchHost ?? bridge.host], deviceId: child.deviceId,
                                    bridgeName: child.bridgeName, bridgeVersion: nil, deviceName: "Apple Watch")
        #expect(account.save(pairing, token: child.token))
        return (bridge, relay, try account.requireClient(timeout: 2))
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
        try await watch.respond(threadId: "t_deploy24", approve: true)
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
}
#endif
