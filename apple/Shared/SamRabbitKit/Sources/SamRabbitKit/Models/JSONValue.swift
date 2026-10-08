import Foundation

/// Any JSON value. Used for the open-ended parts of the mobile API (sync timeline events, generated
/// cards, Mac state details) so new bridge fields never break decoding.
public enum JSONValue: Sendable, Hashable, Codable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .null: try container.encodeNil()
        case .bool(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        }
    }

    /// The member `key` of an object (`.null` otherwise).
    public subscript(key: String) -> JSONValue {
        if case .object(let members) = self { return members[key] ?? .null }
        return .null
    }

    /// The element at `index` of an array (`.null` otherwise).
    public subscript(index: Int) -> JSONValue {
        if case .array(let items) = self, items.indices.contains(index) { return items[index] }
        return .null
    }

    /// Text for strings; numbers and booleans are not converted (`nil`).
    public var string: String? {
        if case .string(let value) = self { return value }
        return nil
    }

    /// Non-empty text, or `nil`.
    public var text: String? {
        guard let value = string?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
        return value
    }

    public var double: Double? {
        switch self {
        case .number(let value): return value
        case .string(let value): return Double(value)
        default: return nil
        }
    }

    public var int: Int? { double.flatMap { $0.isFinite ? Int(exactly: $0.rounded()) : nil } }

    public var bool: Bool? {
        if case .bool(let value) = self { return value }
        return nil
    }

    public var array: [JSONValue]? {
        if case .array(let items) = self { return items }
        return nil
    }

    public var object: [String: JSONValue]? {
        if case .object(let members) = self { return members }
        return nil
    }

    public var isNull: Bool {
        if case .null = self { return true }
        return false
    }

    /// A date from ISO-8601 text or epoch milliseconds / seconds.
    public var date: Date? {
        switch self {
        case .number(let value): return BridgeDates.date(fromNumber: value)
        case .string(let value): return BridgeDates.date(from: value)
        default: return nil
        }
    }
}

extension JSONValue: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral, ExpressibleByFloatLiteral, ExpressibleByNilLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int) { self = .number(Double(value)) }
    public init(floatLiteral value: Double) { self = .number(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
    public init(dictionaryLiteral elements: (String, JSONValue)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, last in last }))
    }
    public init(nilLiteral: ()) { self = .null }
}
