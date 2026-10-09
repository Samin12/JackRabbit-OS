import Foundation

/// The constants every SamRabbit Apple target shares.
public enum SamRabbit {
    public static let appGroup = "group.com.samrabbit.mobile"
    public static let urlScheme = "samrabbit"
    public static let defaultPort = 3780
    public static let keychainService = "com.samrabbit.mobile.bridge"
    public static let backgroundRefreshTask = "com.samrabbit.mobile.refresh"
    /// Pairing codes: 8 characters, uppercase letters and digits without I, O, 0 and 1.
    public static let pairingAlphabet = Set("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
    public static let pairingCodeLength = 8
}

/// A bridge address as `host:port` ("192.168.1.183:3780", "[fd7a::1]:3780", "mac.local:3780").
public struct BridgeHost: Sendable, Hashable, Codable, CustomStringConvertible {
    public var host: String
    public var port: Int

    public init(host: String, port: Int = SamRabbit.defaultPort) {
        self.host = host
        self.port = port
    }

    /// Parses what a person types or a QR code carries: `host`, `host:port`, `http://host:port/...`,
    /// `[v6]:port`. Returns `nil` for anything that is not a plausible host.
    public init?(parsing raw: String) {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, text.count <= 260 else { return nil }
        if let schemeRange = text.range(of: "://") {
            let scheme = text[..<schemeRange.lowerBound].lowercased()
            guard scheme == "http" || scheme == "https" else { return nil }
            text = String(text[schemeRange.upperBound...])
        }
        if let slash = text.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) {
            text = String(text[..<slash])
        }
        var host = text
        var port = SamRabbit.defaultPort
        if text.hasPrefix("[") {
            guard let close = text.firstIndex(of: "]") else { return nil }
            host = String(text[text.index(after: text.startIndex)..<close])
            let rest = text[text.index(after: close)...]
            if rest.hasPrefix(":") {
                guard let value = Int(rest.dropFirst()), (1...65535).contains(value) else { return nil }
                port = value
            } else if !rest.isEmpty {
                return nil
            }
            guard host.contains(":"), host.allSatisfy({ $0.isHexDigit || $0 == ":" || $0 == "." || $0 == "%" }) else {
                return nil
            }
        } else {
            let pieces = text.split(separator: ":", omittingEmptySubsequences: false)
            guard pieces.count <= 2 else { return nil }
            host = String(pieces[0])
            if pieces.count == 2 {
                guard let value = Int(pieces[1]), (1...65535).contains(value) else { return nil }
                port = value
            }
            let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: ".-_"))
            guard !host.isEmpty, host.unicodeScalars.allSatisfy(allowed.contains),
                  !host.hasPrefix("."), !host.hasPrefix("-") else { return nil }
        }
        self.host = host.lowercased()
        self.port = port
    }

    /// `host:port` (IPv6 hosts bracketed).
    public var description: String {
        host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)"
    }

    /// `http://host:port`
    public var baseURL: URL? { URL(string: "http://\(description)") }

    /// True for Tailscale's CGNAT range (100.64.0.0/10).
    public var isTailscale: Bool {
        let octets = host.split(separator: ".").compactMap { Int($0) }
        return octets.count == 4 && octets[0] == 100 && (64...127).contains(octets[1])
    }

    public init(from decoder: Decoder) throws {
        let text = try decoder.singleValueContainer().decode(String.self)
        guard let value = BridgeHost(parsing: text) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "bad host"))
        }
        self = value
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(description)
    }
}

/// A pairing code as typed or scanned: upper-cased, separators removed, validated.
public enum PairingCode {
    /// The normalized code, or `nil` when it cannot be a SamRabbit code.
    public static func normalize(_ raw: String) -> String? {
        let code = raw.uppercased().filter { !$0.isWhitespace && $0 != "-" && $0 != "·" && $0 != "." }
        guard code.count == SamRabbit.pairingCodeLength, code.allSatisfy(SamRabbit.pairingAlphabet.contains) else {
            return nil
        }
        return code
    }

    /// `ABCD-EFGH` for display.
    public static func display(_ code: String) -> String {
        guard code.count == 8 else { return code }
        return "\(code.prefix(4))-\(code.suffix(4))"
    }
}

/// The `samrabbit://pair?h=<host:port>[,<host2:port>]&c=<code>&n=<Mac name>` link from the desktop
/// app's QR code (also opened from the Camera app).
public struct PairLink: Sendable, Equatable {
    public var hosts: [BridgeHost]
    public var code: String
    public var name: String?

    public init(hosts: [BridgeHost], code: String, name: String? = nil) {
        self.hosts = hosts
        self.code = code
        self.name = name
    }

    public init?(url: URL) {
        guard url.scheme?.lowercased() == SamRabbit.urlScheme else { return nil }
        // samrabbit://pair?... has host "pair"; samrabbit:pair?... has path "pair".
        let target = (url.host ?? url.path).trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
        guard target == "pair", let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        var hostsValue: String?
        var codeValue: String?
        var nameValue: String?
        for item in components.queryItems ?? [] {
            let value = item.value?.replacingOccurrences(of: "+", with: " ")
            switch item.name {
            case "h": hostsValue = value
            case "c": codeValue = value
            case "n": nameValue = value
            default: break
            }
        }
        var seen = Set<BridgeHost>()
        let hosts = (hostsValue ?? "").split(separator: ",").compactMap { BridgeHost(parsing: String($0)) }
            .filter { seen.insert($0).inserted }
        guard !hosts.isEmpty, hosts.count <= 8, let code = codeValue.flatMap(PairingCode.normalize) else { return nil }
        self.hosts = hosts
        self.code = code
        let name = nameValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.name = (name?.isEmpty ?? true) ? nil : String(name!.prefix(80))
    }

    public init?(string: String) {
        guard let url = URL(string: string.trimmingCharacters(in: .whitespacesAndNewlines)) else { return nil }
        self.init(url: url)
    }

    /// The canonical link (what the desktop app encodes).
    public var url: URL {
        var components = URLComponents()
        components.scheme = SamRabbit.urlScheme
        components.host = "pair"
        var items = [URLQueryItem(name: "h", value: hosts.map(\.description).joined(separator: ",")),
                     URLQueryItem(name: "c", value: code)]
        if let name { items.append(URLQueryItem(name: "n", value: name)) }
        components.queryItems = items
        return components.url!
    }
}

/// `POST /v1/mobile/pair` -> `{token, deviceId, bridgeName, bridgeVersion}`
public struct PairResponse: Codable, Sendable, Equatable {
    public var token: String
    public var deviceId: String
    public var bridgeName: String?
    public var bridgeVersion: String?

    public init(token: String, deviceId: String, bridgeName: String? = nil, bridgeVersion: String? = nil) {
        self.token = token
        self.deviceId = deviceId
        self.bridgeName = bridgeName
        self.bridgeVersion = bridgeVersion
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        guard let token = c.text("token") else {
            throw DecodingError.keyNotFound(AnyKey("token"), .init(codingPath: [], debugDescription: "no token"))
        }
        self.token = token
        deviceId = c.text("deviceId") ?? ""
        bridgeName = c.text("bridgeName", "name")
        bridgeVersion = c.text("bridgeVersion", "version")
    }
}

/// The platforms the bridge knows.
public enum DevicePlatform: String, Codable, Sendable {
    case ios
    case watchos
}

/// Everything (except the token, which lives in the Keychain) the app keeps about its bridge.
public struct BridgePairing: Codable, Sendable, Equatable {
    /// In the order to try; the one that answered last is first.
    public var hosts: [BridgeHost]
    public var deviceId: String
    public var bridgeName: String?
    public var bridgeVersion: String?
    public var deviceName: String
    public var pairedAt: Date

    public init(hosts: [BridgeHost], deviceId: String, bridgeName: String?, bridgeVersion: String?,
                deviceName: String, pairedAt: Date = .now) {
        self.hosts = hosts
        self.deviceId = deviceId
        self.bridgeName = bridgeName
        self.bridgeVersion = bridgeVersion
        self.deviceName = deviceName
        self.pairedAt = pairedAt
    }

    /// "Samin's MacBook Pro" or the first host.
    public var displayName: String { bridgeName ?? hosts.first?.description ?? "Mac" }
}
