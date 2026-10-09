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

    /// A whole number that fits `Int`, else nil. On the Apple Watch (arm64_32) `Int` is 32 bits: epoch
    /// milliseconds, cursors and sequence numbers never fit it there, read those with `int64`.
    public var int: Int? { integer(Int.self) }

    /// A whole number that fits 64 bits (epoch milliseconds, cursors, sequence numbers, byte counts).
    public var int64: Int64? { integer(Int64.self) }

    /// A whole number as `T` when it fits exactly (never traps: out of range or not finite is nil).
    public func integer<T: FixedWidthInteger>(_ type: T.Type) -> T? {
        double.flatMap { JSONNumbers.exact($0, as: type) }
    }

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

/// Numbers from JSON (always `Double` underneath) into integers without ever trapping. `Int(someDouble)` traps
/// when the value doesn't fit, and on the Apple Watch (arm64_32) `Int` is only 32 bits, so an epoch-milliseconds
/// timestamp (about 1.8e12) that is fine on every 64-bit simulator crashes the watch.
public enum JSONNumbers {
    /// `value` rounded to a whole number, when that fits `T` (nil when out of range, NaN or infinite).
    public static func exact<T: FixedWidthInteger>(_ value: Double, as type: T.Type = T.self) -> T? {
        guard value.isFinite else { return nil }
        return T(exactly: value.rounded())
    }

    /// `value` rounded, clamped into `T`'s range (NaN is 0).
    public static func clamped<T: FixedWidthInteger>(_ value: Double, as type: T.Type = T.self) -> T {
        guard !value.isNaN else { return 0 }
        let rounded = value.rounded()
        if rounded <= Double(T.min) { return T.min }
        // `Double(T.max)` rounds up for 64-bit types (2^63): anything at or above it is the max.
        if rounded >= Double(T.max) { return T.max }
        return T(rounded)
    }

    /// The digits of a whole number for ids and keys ("1791484999000"), or the number as written when it isn't
    /// whole or doesn't fit 64 bits.
    public static func text(_ value: Double) -> String {
        if let whole = exact(value, as: Int64.self), Double(whole) == value { return String(whole) }
        return String(value)
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
