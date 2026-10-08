import Foundation

/// What the iPhone sends the Apple Watch in WatchConnectivity's `applicationContext`: the bridge
/// addresses and a child token issued for the watch (`POST /v1/mobile/devices/child`), revocable on
/// its own from the Mac.
public struct WatchContext: Codable, Sendable, Equatable {
    public var hosts: [BridgeHost]
    public var token: String
    public var deviceId: String
    public var bridgeName: String?
    public var issuedAt: Date

    public init(hosts: [BridgeHost], token: String, deviceId: String, bridgeName: String?, issuedAt: Date = .now) {
        self.hosts = hosts
        self.token = token
        self.deviceId = deviceId
        self.bridgeName = bridgeName
        self.issuedAt = issuedAt
    }

    /// The `applicationContext` key that carries the encoded context.
    public static let key = "samrabbit.watchContext"
    /// Sent instead when the phone unpaired (the watch should forget its token).
    public static let unpairedKey = "samrabbit.unpaired"
    /// A `sendMessage` from the watch asking for its context now (on launch without a pairing).
    /// The phone replies with `applicationContext` (or `unpairedKey`). With the value
    /// `reissueValue` the phone first issues a new child token (the old one was rejected and the
    /// person asked to reconnect).
    public static let requestKey = "samrabbit.contextRequest"
    public static let reissueValue = "reissue"

    public var applicationContext: [String: Any] {
        guard let data = try? BridgeJSON.encoder().encode(self) else { return [:] }
        return [Self.key: data]
    }

    public init?(applicationContext: [String: Any]) {
        guard let data = applicationContext[Self.key] as? Data,
              let value = try? BridgeJSON.decode(WatchContext.self, from: data) else { return nil }
        self = value
    }
}

/// A bridge request relayed through the iPhone (`WCSession.sendMessage`) when the watch cannot
/// reach the Mac directly. The phone performs it with its own token and answers with the status
/// and body, so the watch decodes exactly what the bridge said.
public enum WatchRelay {
    public static let requestKey = "samrabbit.relay"
    public static let responseKey = "samrabbit.relayResponse"

    public struct Request: Codable, Sendable, Equatable {
        public var method: String
        public var path: String
        public var query: [[String]]
        public var body: Data?
        public var accept: String
        public var timeout: TimeInterval?

        public init(_ request: BridgeRequest) {
            method = request.method
            path = request.path
            query = request.query.map { [$0.name, $0.value ?? ""] }
            body = request.body
            accept = request.accept
            timeout = request.timeout
        }

        public var bridgeRequest: BridgeRequest {
            BridgeRequest(method: method, path: path,
                          query: query.compactMap { $0.count == 2 ? URLQueryItem(name: $0[0], value: $0[1]) : nil },
                          body: body, authorized: true, accept: accept, timeout: timeout)
        }

        /// Only the mobile API may be relayed.
        public var allowed: Bool {
            path.hasPrefix("/v1/mobile/") && !path.contains("..") && ["GET", "POST", "DELETE"].contains(method)
                && !path.hasPrefix("/v1/mobile/pair") && !path.hasPrefix("/v1/mobile/devices")
        }

        public var message: [String: Any] {
            guard let data = try? JSONEncoder().encode(self) else { return [:] }
            return [WatchRelay.requestKey: data]
        }

        public init?(message: [String: Any]) {
            guard let data = message[WatchRelay.requestKey] as? Data,
                  let value = try? JSONDecoder().decode(Request.self, from: data) else { return nil }
            self = value
        }
    }

    public struct Response: Codable, Sendable, Equatable {
        /// The bridge's HTTP status, or 0 when the phone could not reach the Mac either.
        public var status: Int
        public var body: Data

        public init(status: Int, body: Data) {
            self.status = status
            self.body = body
        }

        public var message: [String: Any] {
            guard let data = try? JSONEncoder().encode(self) else { return [:] }
            return [WatchRelay.responseKey: data]
        }

        public init?(message: [String: Any]) {
            guard let data = message[WatchRelay.responseKey] as? Data,
                  let value = try? JSONDecoder().decode(Response.self, from: data) else { return nil }
            self = value
        }
    }
}

extension BridgeClient {
    /// Performs any request and returns the bridge's status and body, without throwing for HTTP
    /// errors (the phone's side of `WatchRelay`). Throws only when no address answered.
    public func raw(_ request: BridgeRequest) async throws -> (status: Int, body: Data) {
        do {
            let body = try await data(request)
            return (200, body)
        } catch let error as BridgeError {
            switch error {
            case .server(let status, let code, let message, let retryable):
                let envelope: JSONValue = ["error": ["code": .string(code), "message": .string(message),
                                                     "retryable": .bool(retryable)]]
                return (status, (try? JSONEncoder().encode(envelope)) ?? Data())
            case .unauthorized:
                return (401, Data(#"{"error":{"code":"unauthorized","message":"Pair again.","retryable":false}}"#.utf8))
            default:
                throw error
            }
        }
    }
}

/// Another route to the bridge, used by `BridgeClient` when no address can be connected to. The
/// Apple Watch implements it with `WCSession.sendMessage` (the iPhone performs the request with
/// its own token and answers with `WatchRelay.Response`).
public protocol BridgeRelay: Sendable {
    /// True when requests should go through the relay first (the direct route failed a moment ago).
    var prefersRelay: Bool { get }
    /// Called after each direct attempt: `true` when the bridge answered, `false` when it could not be reached.
    func noteDirectRoute(worked: Bool)
    /// Performs the request through the relay and returns the bridge's status and body (status 0:
    /// the relay could not reach the bridge either). Throws `BridgeError.unreachable` only when the
    /// relay itself is down and nothing was sent.
    func relay(_ request: BridgeRequest) async throws -> (status: Int, body: Data)
}
