import Foundation

/// The paired bridge: its addresses and names (App Group, `pairing.json`) plus the token
/// (Keychain). Every target creates its `BridgeClient` here.
public final class BridgeAccount: Sendable {
    public static let shared = BridgeAccount()

    public let container: SharedContainer
    private let secrets: SecretStore
    private static let pairingFile = "pairing.json"
    private static let tokenAccount = "mobileToken"

    public init(container: SharedContainer = .shared, secrets: SecretStore = KeychainStore.shared) {
        self.container = container
        self.secrets = secrets
    }

    public var pairing: BridgePairing? { container.load(BridgePairing.self, from: Self.pairingFile) }

    public var isPaired: Bool { pairing != nil && token != nil }

    /// The mobile token. Only the app itself should read it (to provision the Apple Watch).
    public var token: String? { secrets.secret(for: Self.tokenAccount) }

    /// A client for the paired bridge, or `nil` when not paired.
    public func client(timeout: TimeInterval = 12) -> BridgeClient? {
        guard let pairing, let token, !pairing.hosts.isEmpty else { return nil }
        return BridgeClient(hosts: pairing.hosts, token: token, timeout: timeout) { [weak self] host in
            self?.promote(host)
        }
    }

    /// A client or `BridgeError.notPaired`.
    public func requireClient(timeout: TimeInterval = 12) throws -> BridgeClient {
        guard let client = client(timeout: timeout) else { throw BridgeError.notPaired }
        return client
    }

    /// Stores a new pairing (replacing any previous one).
    @discardableResult
    public func save(_ pairing: BridgePairing, token: String) -> Bool {
        guard secrets.setSecret(token, for: Self.tokenAccount) else { return false }
        container.save(pairing, as: Self.pairingFile)
        return true
    }

    /// Forgets the bridge (the token is deleted; the bridge itself can revoke the device too).
    public func unpair() {
        secrets.setSecret(nil, for: Self.tokenAccount)
        container.remove(Self.pairingFile)
    }

    /// Moves the address that answered to the front, so the next launch tries it first.
    public func promote(_ host: BridgeHost) {
        guard var pairing, let index = pairing.hosts.firstIndex(of: host), index > 0 else { return }
        pairing.hosts.remove(at: index)
        pairing.hosts.insert(host, at: 0)
        container.save(pairing, as: Self.pairingFile)
    }

    /// Replaces the bridge addresses (Settings > Bridge host).
    public func updateHosts(_ hosts: [BridgeHost]) {
        guard var pairing, !hosts.isEmpty else { return }
        pairing.hosts = hosts
        container.save(pairing, as: Self.pairingFile)
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
public final class SummaryCache: Sendable {
    public struct Entry: Codable, Sendable, Equatable {
        public var summary: MobileSummary
        public var savedAt: Date
    }

    public static let shared = SummaryCache()
    private let container: SharedContainer
    private static let file = "summary.json"

    public init(container: SharedContainer = .shared) { self.container = container }

    public func load() -> Entry? { container.load(Entry.self, from: Self.file) }

    public func save(_ summary: MobileSummary, at date: Date = .now) {
        container.save(Entry(summary: summary, savedAt: date), as: Self.file)
    }

    public func clear() { container.remove(Self.file) }
}
