import Foundation

/// A generated card from the R1 (`card.shown` / `card.updated` events; GenCardCodec JSON).
public struct GenCard: Sendable, Equatable, Identifiable {
    public enum Block: Sendable, Equatable {
        case text(String, style: String)
        case stat(value: String, label: String?, delta: String?, trend: String?)
        case keyValues([Pair], columns: Int)
        case list([Row])
        case checklist([Row])
        case progress(label: String?, value: Double?, steps: [String], step: Int?)
        case bars(values: [Double], labels: [String], highlight: Int?, unit: String?)
        case weather(condition: String, temp: String, high: String?, low: String?, place: String?)
        case timer(label: String?, endsAt: Date?, totalMs: Double?, done: Bool)
        case divider
    }

    public struct Pair: Sendable, Equatable, Identifiable {
        public var id: Int
        public var key: String
        public var value: String
    }

    public struct Row: Sendable, Equatable, Identifiable {
        public var id: Int
        public var title: String
        public var detail: String?
        public var trailing: String?
        public var checked: Bool
        public var status: String?
    }

    public var id: String
    public var title: String
    public var subtitle: String?
    public var eyebrow: String?
    /// One of blue, violet, cyan, mint, pink, amber, red, green.
    public var accent: String
    public var icon: String?
    public var state: String
    public var terminal: Bool
    public var live: Bool
    public var liveLines: [String]
    public var liveStatus: String?
    public var body: [Block]
    public var actions: [String]

    public init?(_ raw: JSONValue) {
        guard let id = raw["id"].text else { return nil }
        self.id = id
        title = raw["title"].text ?? ""
        subtitle = raw["subtitle"].text
        eyebrow = raw["eyebrow"].text
        accent = raw["accent"].text ?? "blue"
        icon = raw["icon"].text
        state = raw["state"].text ?? "active"
        terminal = raw["terminal"].bool ?? false
        live = raw["live"].object != nil
        liveLines = [raw["liveTitle"].text, raw["liveSubtitle"].text, raw["liveNote"].text].compactMap { $0 }
        liveStatus = raw["liveStatus"].text
        body = (raw["body"].array ?? []).compactMap(Self.block)
        actions = (raw["actions"].array ?? []).compactMap { $0["label"].text }
    }

    static func rows(_ raw: JSONValue) -> [Row] {
        (raw["items"].array ?? []).enumerated().map { index, item in
            Row(id: index, title: item["title"].text ?? item["text"].text ?? "", detail: item["detail"].text,
                trailing: item["trailing"].text, checked: item["checked"].bool ?? false, status: item["status"].text)
        }
    }

    static func block(_ raw: JSONValue) -> Block? {
        switch raw["type"].string {
        case "text":
            return raw["text"].text.map { .text($0, style: raw["style"].text ?? "body") }
        case "stat":
            return .stat(value: raw["value"].text ?? raw["value"].double.map { String(format: "%g", $0) } ?? "",
                         label: raw["label"].text, delta: raw["delta"].text, trend: raw["trend"].text)
        case "kv":
            let pairs = (raw["pairs"].array ?? []).enumerated().map {
                Pair(id: $0.offset, key: $0.element["k"].text ?? "", value: $0.element["v"].text ?? "")
            }
            return .keyValues(pairs, columns: raw["columns"].int == 2 ? 2 : 1)
        case "list":
            return .list(rows(raw))
        case "checklist":
            return .checklist(rows(raw))
        case "progress":
            return .progress(label: raw["label"].text, value: raw["indeterminate"].bool == true ? nil : raw["progress"].double,
                             steps: (raw["steps"].array ?? []).compactMap(\.text), step: raw["step"].int)
        case "bars":
            return .bars(values: (raw["values"].array ?? []).map { $0.double ?? 0 },
                         labels: (raw["labels"].array ?? []).map { $0.text ?? "" },
                         highlight: raw["highlight"].int, unit: raw["unit"].text)
        case "weather":
            return .weather(condition: raw["condition"].text ?? "cloudy", temp: raw["temp"].text ?? "",
                            high: raw["hi"].text, low: raw["lo"].text, place: raw["place"].text)
        case "timer":
            return .timer(label: raw["label"].text, endsAt: raw["endsAt"].date, totalMs: raw["totalMs"].double,
                          done: raw["done"].bool ?? false)
        case "divider":
            return .divider
        default:
            return nil
        }
    }
}
