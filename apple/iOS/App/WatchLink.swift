import Foundation
import os
import SamRabbitKit
import Synchronization
import WatchConnectivity

private let log = Logger(subsystem: "com.samrabbit.mobile", category: "watch")

/// The iPhone's half of the Apple Watch link (`watchOS/App/PhoneLink.swift` is the other half):
/// * provisions the watch with the bridge addresses and its own child token
///   (`POST /v1/mobile/devices/child`, revocable from the Mac) through `applicationContext`, and
///   answers the watch's "send it now" request (`WatchContext.requestKey`) with the same context;
/// * relays bridge requests the watch sends with `sendMessage` when it cannot reach the Mac itself
///   (`WatchRelay` in SamRabbitKit describes both messages).
final class WatchLink: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchLink()

    private let account = BridgeAccount.shared
    /// The last provisioning run: runs never overlap (two at once would issue two child tokens).
    private let lastRun = Mutex<Task<Provisioning, Never>?>(nil)
    private static let tokenAccount = "watchToken"
    private static let contextFile = "watch-context.json"

    func start() {
        guard WCSession.isSupported() else { return }
        let session = WCSession.default
        session.delegate = self
        session.activate()
    }

    /// What `provision` did.
    enum Provisioning: Sendable {
        case sent(WatchContext)
        case unpaired
        case failed
    }

    /// Sends the current pairing to the watch (issuing a child token the first time, or again with
    /// `reissue`: after re-pairing the phone, or when the watch says the Mac rejected its token).
    @discardableResult
    func provision(reissue: Bool = false) async -> Provisioning {
        let run = lastRun.withLock { last -> Task<Provisioning, Never> in
            let previous = last
            let next = Task {
                _ = await previous?.value
                return await self.provisionNow(reissue: reissue)
            }
            last = next
            return next
        }
        return await run.value
    }

    private func provisionNow(reissue: Bool) async -> Provisioning {
        guard WCSession.isSupported() else { return .failed }
        let session = WCSession.default
        guard session.activationState == .activated, session.isPaired, session.isWatchAppInstalled else { return .failed }
        guard let pairing = account.pairing, let client = account.client() else {
            try? session.updateApplicationContext([WatchContext.unpairedKey: true])
            return .unpaired
        }
        var context = account.container.load(WatchContext.self, from: Self.contextFile)
        let token = KeychainStore.shared.secret(for: Self.tokenAccount)
        if reissue || context == nil || token == nil
            || context?.hosts.first.map({ pairing.hosts.contains($0) }) == false {
            do {
                let child = try await client.childDevice(name: "Apple Watch", platform: .watchos)
                KeychainStore.shared.setSecret(child.token, for: Self.tokenAccount)
                context = WatchContext(hosts: pairing.hosts, token: child.token, deviceId: child.deviceId,
                                       bridgeName: pairing.bridgeName)
            } catch {
                log.notice("watch token not issued")
                return .failed
            }
        }
        guard var context, let secret = KeychainStore.shared.secret(for: Self.tokenAccount) else { return .failed }
        context.hosts = pairing.hosts
        context.token = secret
        var stored = context
        stored.token = "" // the token itself stays in the Keychain
        account.container.save(stored, as: Self.contextFile)
        do {
            try session.updateApplicationContext(context.applicationContext)
        } catch {
            log.notice("watch context not sent")
        }
        return .sent(context)
    }

    /// Forgets the watch's token (on unpair).
    func reset() {
        KeychainStore.shared.setSecret(nil, for: Self.tokenAccount)
        account.container.remove(Self.contextFile)
        if WCSession.isSupported(), WCSession.default.activationState == .activated {
            try? WCSession.default.updateApplicationContext([WatchContext.unpairedKey: true])
        }
    }

    // MARK: WCSessionDelegate

    func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        Task { await provision() }
    }

    func sessionWatchStateDidChange(_ session: WCSession) {
        Task { await provision() }
    }

    func sessionDidBecomeInactive(_ session: WCSession) {}

    func sessionDidDeactivate(_ session: WCSession) {
        WCSession.default.activate()
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any],
                 replyHandler: @escaping ([String: Any]) -> Void) {
        nonisolated(unsafe) let reply = replyHandler
        if let ask = message[WatchContext.requestKey] as? String {
            Task {
                switch await provision(reissue: ask == WatchContext.reissueValue) {
                case .sent(let context): reply(context.applicationContext)
                case .unpaired: reply([WatchContext.unpairedKey: true])
                case .failed: reply([:])
                }
            }
            return
        }
        guard let request = WatchRelay.Request(message: message), request.allowed else {
            reply(WatchRelay.Response(status: 400, body: Data()).message)
            return
        }
        Task {
            guard let client = account.client(timeout: 15) else {
                reply(WatchRelay.Response(status: 409, body: Data(#"{"error":{"code":"not_paired","message":"Pair the iPhone first.","retryable":false}}"#.utf8)).message)
                return
            }
            do {
                let (status, body) = try await client.raw(request.bridgeRequest)
                reply(WatchRelay.Response(status: status, body: body).message)
            } catch {
                reply(WatchRelay.Response(status: 0, body: Data()).message)
            }
        }
    }
}
