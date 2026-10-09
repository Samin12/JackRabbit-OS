import Foundation
import os
import SamRabbitKit
import Synchronization
import WatchConnectivity

private let log = Logger(subsystem: "com.samrabbit.mobile", category: "watch")

/// The iPhone's half of the Apple Watch link (`watchOS/App/PhoneLink.swift` is the other half):
/// * provisions the watch with the bridge addresses and its own child token
///   (`POST /v1/mobile/devices/child`, revocable from the Mac; `BridgeAccount.provisionWatch`)
///   through `applicationContext`, and answers the watch's "send it now" request
///   (`WatchContext.requestKey`) with the same context;
/// * relays bridge requests the watch sends with `sendMessage` when it cannot reach the Mac itself,
///   performed with the watch's own token, never the phone's (`BridgeAccount.performRelayed`;
///   `WatchRelay` in SamRabbitKit describes both messages);
/// * passes on the watch's voice recordings the same way: they arrive in chunks (`VoiceRelay`), are put
///   back together here and uploaded for transcription with the watch's token
///   (`BridgeAccount.performRelayedTranscription`); the reply to the last chunk is the bridge's answer.
final class WatchLink: NSObject, WCSessionDelegate, @unchecked Sendable {
    static let shared = WatchLink()

    private let account = BridgeAccount.shared
    /// The watch's recordings while their chunks arrive.
    private let recordings = VoiceRelay.Assembler()
    /// The last provisioning run: runs never overlap (two at once would issue two child tokens).
    private let lastRun = Mutex<Task<Provisioning, Never>?>(nil)

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
    /// `reissue`: after re-pairing the phone, or when the watch says the Mac rejected its token; the
    /// old watch token stops working).
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
        guard account.isPaired else {
            try? session.updateApplicationContext([WatchContext.unpairedKey: true])
            return .unpaired
        }
        let context: WatchContext
        do {
            context = try await account.provisionWatch(reissue: reissue)
        } catch {
            log.notice("watch token not issued")
            return .failed
        }
        do {
            try session.updateApplicationContext(context.applicationContext)
        } catch {
            log.notice("watch context not sent")
        }
        return .sent(context)
    }

    /// Forgets the watch's token here and tells the watch (on unpair: the bridge revoked it with the phone).
    func reset() {
        account.forgetWatch()
        recordings.reset()
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
        if let chunk = VoiceRelay.Chunk(message: message) {
            switch recordings.add(chunk) {
            case .waiting: reply([VoiceRelay.ackKey: chunk.seq])
            case .refused(let response): reply(response.message)
            case .complete(let upload):
                // With the watch's own token, like every relayed request.
                Task { reply(await account.performRelayedTranscription(upload).message) }
            }
            return
        }
        guard let request = WatchRelay.Request(message: message) else {
            reply(WatchRelay.Response(status: 400, body: Data()).message)
            return
        }
        // With the watch's own token: a watch revoked on the Mac is refused here too (401).
        Task { reply(await account.performRelayed(request).message) }
    }
}
