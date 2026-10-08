import Foundation
import Synchronization

/// The paired bridge: its addresses and names (App Group, `pairing.json`) plus the token
/// (Keychain). Every target gets its `BridgeClient` here: one per pairing, reused for every
/// request (a client owns its `URLSession`s), and rebuilt only when the addresses or the token change.
///
/// On the iPhone it also keeps the Apple Watch's own child token (`provisionWatch`), and performs
/// the requests the watch relays through the phone with that token (`performRelayed`).
public final class BridgeAccount: Sendable {
    public static let shared = BridgeAccount()

    public let container: SharedContainer
    private let secrets: SecretStore
    /// Another route to the bridge for this account's clients (the Apple Watch: through the iPhone).
    public let relay: (any BridgeRelay)?
    private let clients = Mutex<[Slot: CachedClient]>([:])
    private static let pairingFile = "pairing.json"
    private static let tokenAccount = "mobileToken"
    private static let watchTokenAccount = "watchToken"
    private static let watchContextFile = "watch-context.json"

    private enum Slot: Hashable { case own, watch }

    private struct CachedClient {
        var hosts: [BridgeHost]
        var token: String
        var client: BridgeClient
    }

    public init(container: SharedContainer = .shared, secrets: SecretStore = KeychainStore.shared,
                relay: (any BridgeRelay)? = nil) {
        self.container = container
        self.secrets = secrets
        self.relay = relay
    }

    public var pairing: BridgePairing? { container.load(BridgePairing.self, from: Self.pairingFile) }

    public var isPaired: Bool { pairing != nil && token != nil }

    /// The mobile token. Only the app itself should read it (to provision the Apple Watch).
    public var token: String? { secrets.secret(for: Self.tokenAccount) }

    /// The client for the paired bridge (the same instance while the pairing is unchanged), or
    /// `nil` when not paired. Requests set their own timeouts; the client's default is 12 s.
    public func client() -> BridgeClient? {
        guard let pairing, let token, !pairing.hosts.isEmpty else { return nil }
        return cachedClient(.own, hosts: pairing.hosts, token: token, relay: relay)
    }

    /// The client or `BridgeError.notPaired`.
    public func requireClient() throws -> BridgeClient {
        guard let client = client() else { throw BridgeError.notPaired }
        return client
    }

    private func cachedClient(_ slot: Slot, hosts: [BridgeHost], token: String, relay: (any BridgeRelay)?) -> BridgeClient {
        clients.withLock { cache in
            if let cached = cache[slot], cached.token == token, cached.hosts == hosts { return cached.client }
            let client = BridgeClient(hosts: hosts, token: token, relay: relay) { [weak self] host in
                self?.promote(host)
            }
            cache[slot] = CachedClient(hosts: hosts, token: token, client: client)
            return client
        }
    }

    /// Stores a new pairing (replacing any previous one).
    @discardableResult
    public func save(_ pairing: BridgePairing, token: String) -> Bool {
        guard secrets.setSecret(token, for: Self.tokenAccount) else { return false }
        container.save(pairing, as: Self.pairingFile)
        return true
    }

    /// Forgets the bridge on this device only: the token, the pairing and the watch's token (the
    /// Apple Watch, when the iPhone said it unpaired; `unpair()` also tells the bridge).
    public func forget() {
        secrets.setSecret(nil, for: Self.tokenAccount)
        secrets.setSecret(nil, for: Self.watchTokenAccount)
        container.remove(Self.pairingFile)
        container.remove(Self.watchContextFile)
        clients.withLock { $0.removeAll() }
    }

    /// Unpairs: asks the bridge to forget this device (`POST /v1/mobile/unpair`; it revokes the
    /// iPhone's watch with it), then forgets everything here whether or not the Mac answered.
    /// Returns true when the bridge confirmed.
    @discardableResult
    public func unpair(timeout: TimeInterval = 6) async -> Bool {
        var confirmed = false
        if let client = client() {
            do {
                try await client.unpair(timeout: timeout)
                confirmed = true
            } catch BridgeError.unauthorized {
                confirmed = true // revoked already
            } catch {
                kitLog.notice("unpair: the bridge did not answer; forgetting it here anyway")
            }
        }
        forget()
        return confirmed
    }

    /// Moves the address that answered to the front, so the next launch tries it first.
    public func promote(_ host: BridgeHost) {
        guard var pairing, let index = pairing.hosts.firstIndex(of: host), index > 0 else { return }
        pairing.hosts.remove(at: index)
        pairing.hosts.insert(host, at: 0)
        container.save(pairing, as: Self.pairingFile)
        // The client already prefers that address: keep it rather than building a new one.
        let hosts = pairing.hosts
        clients.withLock { cache in
            for (slot, cached) in cache where Set(cached.hosts) == Set(hosts) { cache[slot]?.hosts = hosts }
        }
    }

    /// Replaces the bridge addresses (Settings > Bridge host).
    public func updateHosts(_ hosts: [BridgeHost]) {
        guard var pairing, !hosts.isEmpty else { return }
        pairing.hosts = hosts
        container.save(pairing, as: Self.pairingFile)
    }

    // MARK: - The Apple Watch (the iPhone's side)

    /// The child token this iPhone issued for its Apple Watch (`POST /v1/mobile/devices/child`).
    public var watchToken: String? { secrets.secret(for: Self.watchTokenAccount) }

    /// What was last sent to the watch (without the token, which stays in the Keychain).
    public var watchContext: WatchContext? { container.load(WatchContext.self, from: Self.watchContextFile) }

    /// The context to send the Apple Watch: the bridge addresses and the watch's own child token.
    ///
    /// The token is issued the first time, and again with `reissue` (after re-pairing the phone, or
    /// when the watch says the Mac refused its token) or when the bridge addresses changed. The
    /// bridge replaces this phone's earlier watch token of the same name when it issues a new one;
    /// a still older one (issued before the phone re-paired) is revoked here with its own token.
    public func provisionWatch(reissue: Bool = false) async throws -> WatchContext {
        guard let pairing else { throw BridgeError.notPaired }
        let client = try requireClient()
        let previous = watchToken
        if !reissue, let previous, var context = watchContext,
           let first = context.hosts.first, pairing.hosts.contains(first) {
            context.hosts = pairing.hosts
            context.token = previous
            return context
        }
        let child = try await client.childDevice(name: WatchContext.watchName, platform: .watchos)
        guard secrets.setSecret(child.token, for: Self.watchTokenAccount) else {
            throw BridgeError.invalidResponse("The watch token could not be stored on this iPhone.")
        }
        let context = WatchContext(hosts: pairing.hosts, token: child.token, deviceId: child.deviceId,
                                   bridgeName: pairing.bridgeName)
        var stored = context
        stored.token = ""
        container.save(stored, as: Self.watchContextFile)
        if let previous, previous != child.token {
            let old = BridgeClient(hosts: pairing.hosts, token: previous, timeout: 6)
            _ = try? await old.unpair(timeout: 6) // 401 when the bridge replaced it already
        }
        return context
    }

    /// Forgets the watch's token on this iPhone (the watch was told to unpair).
    public func forgetWatch() {
        secrets.setSecret(nil, for: Self.watchTokenAccount)
        container.remove(Self.watchContextFile)
        clients.withLock { $0[.watch] = nil }
    }

    /// The client the phone uses for the watch's relayed requests: the phone's addresses with the
    /// watch's own token. `nil` until a watch token was issued.
    public func watchClient() -> BridgeClient? {
        guard let pairing, let watchToken, !watchToken.isEmpty, !pairing.hosts.isEmpty else { return nil }
        return cachedClient(.watch, hosts: pairing.hosts, token: watchToken, relay: nil)
    }

    /// Performs a request the Apple Watch relayed through this iPhone, with the watch's own token
    /// (never the phone's): a watch revoked on the Mac gets 401 here just as directly. Only
    /// `WatchRelay.Request.allowed` requests are performed. Status 0: the Mac could not be reached.
    public func performRelayed(_ request: WatchRelay.Request) async -> WatchRelay.Response {
        guard request.allowed else {
            return .refusal(403, "relay_forbidden", "The iPhone doesn't pass that request on.")
        }
        guard isPaired else { return .refusal(409, "not_paired", "Pair the iPhone first.") }
        guard let client = watchClient() else {
            return .refusal(401, "unauthorized", "Reconnect your watch through the iPhone.")
        }
        do {
            let (status, body) = try await client.raw(request.bridgeRequest)
            return WatchRelay.Response(status: status, body: body)
        } catch {
            return WatchRelay.Response(status: 0, body: Data())
        }
    }
}

/// Pairs with a bridge: tries each address of the link in order (LAN, then Tailscale) and keeps
/// the one that answered first in the saved pairing.
public enum Pairer {
    public static func pair(link: PairLink, deviceName: String, platform: DevicePlatform = .ios,
                            account: BridgeAccount = .shared, timeout: TimeInterval = 8) async throws -> BridgePairing {
        var lastError: Error = BridgeError.unreachable("no address")
        for (index, host) in link.hosts.enumerated() {
            let client = BridgeClient(hosts: [host], token: nil, timeout: timeout)
            do {
                let answer = try await client.pair(code: link.code, deviceName: deviceName, platform: platform)
                var hosts = link.hosts
                hosts.remove(at: index)
                hosts.insert(host, at: 0)
                let pairing = BridgePairing(hosts: hosts, deviceId: answer.deviceId,
                                            bridgeName: answer.bridgeName ?? link.name,
                                            bridgeVersion: answer.bridgeVersion, deviceName: deviceName)
                guard account.save(pairing, token: answer.token) else {
                    throw BridgeError.invalidResponse("The token could not be stored on this device.")
                }
                return pairing
            } catch let error as BridgeError {
                // Only a connection problem moves on; the bridge's own answer (bad code) is final.
                guard case .unreachable = error else { throw error }
                lastError = error
            }
        }
        throw lastError
    }
}

/// The last summary, shared with the widgets (`summary.json` in the App Group).
///
/// `publish` is how the apps hand a fresh summary to the widgets and complications: it reloads
/// their timelines only when what they show changed (WidgetKit budgets reloads; the iPhone
/// refreshes every 20 s and the watch every 30 s, mostly with nothing new).
public final class SummaryCache: Sendable {
    public struct Entry: Codable, Sendable, Equatable {
        public var summary: MobileSummary
        public var savedAt: Date
    }

    /// What the widgets were last reloaded for (`generatedAt` cleared).
    struct Reload: Codable, Sendable, Equatable {
        var summary: MobileSummary
        var at: Date
    }

    public static let shared = SummaryCache()
    private let container: SharedContainer
    private static let file = "summary.json"
    private static let reloadFile = "widgets-reloaded.json"
    /// Reload anyway after this long, so the faces never say "As of …" (45 min) while the app is
    /// fetching fresh copies of the same summary.
    static let refreshFacesAfter: TimeInterval = 30 * 60

    public init(container: SharedContainer = .shared) { self.container = container }

    public func load() -> Entry? { container.load(Entry.self, from: Self.file) }

    /// Saves without reloading anything (the widget and complication timelines, which are the reload).
    public func save(_ summary: MobileSummary, at date: Date = .now) {
        container.save(Entry(summary: summary, savedAt: date), as: Self.file)
    }

    /// Saves a fresh summary and reloads the widget timelines when what it says differs from what
    /// they were last reloaded for (`generatedAt` does not count), or that was a while ago.
    /// Returns true when it reloaded.
    @discardableResult
    public func publish(_ summary: MobileSummary, at date: Date = .now,
                        reload: () -> Void = SamRabbitActions.reloadWidgets) -> Bool {
        save(summary, at: date)
        var content = summary
        content.generatedAt = nil
        if let last = container.load(Reload.self, from: Self.reloadFile), last.summary == content,
           date.timeIntervalSince(last.at) < Self.refreshFacesAfter {
            return false
        }
        container.save(Reload(summary: content, at: date), as: Self.reloadFile)
        reload()
        return true
    }

    public func clear() {
        container.remove(Self.file)
        container.remove(Self.reloadFile)
    }
}
