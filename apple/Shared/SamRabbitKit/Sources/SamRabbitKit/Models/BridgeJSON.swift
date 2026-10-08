import Foundation

/// Dates in the mobile API arrive either as ISO-8601 text (T3, calendar, summary) or as epoch
/// milliseconds (the conversation sync store). Both are accepted everywhere.
public enum BridgeDates {
    private static let fractional = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
    private static let whole = Date.ISO8601FormatStyle()
    private static let localNoZone: Date.ISO8601FormatStyle = {
        var style = Date.ISO8601FormatStyle(timeZone: .current)
        style = style.year().month().day().time(includingFractionalSeconds: false)
        return style
    }()

    /// Numbers above 10^11 are milliseconds, smaller ones seconds.
    public static func date(fromNumber value: Double) -> Date? {
        guard value.isFinite, value > 0 else { return nil }
        return Date(timeIntervalSince1970: value > 100_000_000_000 ? value / 1000 : value)
    }

    public static func date(from text: String) -> Date? {
        let value = text.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return nil }
        if let number = Double(value) { return date(fromNumber: number) }
        if let date = try? fractional.parse(value) { return date }
        if let date = try? whole.parse(value) { return date }
        // Offsets without a colon ("+0000") or a space instead of "T".
        var normalized = value.replacingOccurrences(of: " ", with: "T")
        if let match = normalized.range(of: #"[+-]\d{4}$"#, options: .regularExpression) {
            let offset = String(normalized[match])
            normalized.replaceSubrange(match, with: offset.prefix(3) + ":" + offset.suffix(2))
        }
        if let date = try? fractional.parse(normalized) { return date }
        if let date = try? whole.parse(normalized) { return date }
        // A bare local date-time ("2026-10-08T14:00:00"): the user's own clock.
        if let date = try? localNoZone.parse(normalized) { return date }
        // A bare day ("2026-10-08", all-day events): local midnight.
        let parts = value.split(separator: "-").compactMap { Int($0) }
        if parts.count == 3, value.count == 10 {
            return Calendar.current.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
        }
        return nil
    }

    /// `2026-10-08T18:44:19Z` (what request bodies carry).
    public static func iso(_ date: Date) -> String { whole.format(date) }

    /// `2026-10-08T18:44:19.250Z` (the cache keeps every digit).
    public static func isoFractional(_ date: Date) -> String { fractional.format(date) }
}

/// The decoder and encoder used for every bridge payload.
public enum BridgeJSON {
    public static func decoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            if let number = try? container.decode(Double.self), let date = BridgeDates.date(fromNumber: number) {
                return date
            }
            let text = try container.decode(String.self)
            guard let date = BridgeDates.date(from: text) else {
                throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unrecognised date")
            }
            return date
        }
        return decoder
    }

    public static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(BridgeDates.isoFractional(date))
        }
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    public static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        try decoder().decode(type, from: data)
    }
}

/// Lenient reads: a missing, null or mistyped field becomes the default instead of failing the
/// whole payload. The bridge evolves independently of the app.
extension KeyedDecodingContainer {
    func lenient<T: Decodable>(_ type: T.Type, _ key: Key) -> T? {
        (try? decodeIfPresent(type, forKey: key)) ?? nil
    }

    func lenient<T: Decodable>(_ key: Key, default value: T) -> T {
        lenient(T.self, key) ?? value
    }

    /// The first present key among `keys`.
    func lenient<T: Decodable>(_ type: T.Type, any keys: [Key]) -> T? {
        for key in keys {
            if let value = lenient(type, key) { return value }
        }
        return nil
    }

    func string(_ key: Key) -> String? { lenient(String.self, key) }

    func date(_ key: Key) -> Date? {
        if let date = lenient(Date.self, key) { return date }
        return lenient(JSONValue.self, key)?.date
    }

    func bool(_ key: Key, default value: Bool = false) -> Bool {
        if let bool = lenient(Bool.self, key) { return bool }
        if let number = lenient(Double.self, key) { return number != 0 }
        return value
    }

    func int(_ key: Key, default value: Int = 0) -> Int {
        if let int = lenient(Int.self, key) { return int }
        if let number = lenient(Double.self, key), number.isFinite { return Int(number) }
        if let text = lenient(String.self, key), let int = Int(text) { return int }
        return value
    }

    /// An array whose undecodable elements are dropped rather than failing the array.
    func list<T: Decodable>(_ type: T.Type, _ key: Key) -> [T] {
        guard let wrapped = lenient([Lossy<T>].self, key) else { return [] }
        return wrapped.compactMap(\.value)
    }
}

/// One array element that may fail to decode.
struct Lossy<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws {
        value = try? T(from: decoder)
    }
}

/// Coding keys from any string (for payloads read with several alternative names).
struct AnyKey: CodingKey {
    var stringValue: String
    var intValue: Int? { nil }
    init(_ string: String) { stringValue = string }
    init?(stringValue: String) { self.stringValue = stringValue }
    init?(intValue: Int) { return nil }
}
