import Foundation

/// Native view of the bridge's desktop sync API, independent of the web view (which may be closed):
/// it follows `GET /v1/sync/stream` (SSE) for notifications and polls the conversation list for the
/// menu bar status. Reconnects with backoff; resumes the stream with `?after=<last SSE id>`.
final class SyncMonitor: NSObject, URLSessionDataDelegate {
    enum StreamState: Equatable {
        case connecting
        case live
        case down(Int)  // HTTP status, or 0 when the bridge is unreachable
    }

    var onEvent: (([String: Any]) -> Void)?
    var onStreamState: ((StreamState) -> Void)?

    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 75  // the bridge sends heartbeats well within this
        configuration.timeoutIntervalForResource = 7 * 24 * 3600
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        return URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
    }()
    private var task: URLSessionDataTask?
    private var buffer = Data()
    private var pendingId: String?
    private var pendingData: [String] = []
    private var cursor: String?
    private var attempt = 0
    private var retryTimer: Timer?
    private var stopped = true
    private(set) var state: StreamState = .connecting {
        didSet { if state != oldValue { onStreamState?(state) } }
    }

    func start() {
        stopped = false
        connect()
    }

    func stop() {
        stopped = true
        retryTimer?.invalidate()
        task?.cancel()
        task = nil
    }

    func reconnectNow() {
        retryTimer?.invalidate()
        task?.cancel()
        task = nil
        attempt = 0
        connect()
    }

    private func connect() {
        guard !stopped else { return }
        let query = cursor.map { [URLQueryItem(name: "after", value: $0)] } ?? []
        guard var request = Config.request(Config.api("/v1/sync/stream", query: query), timeout: 75) else {
            state = .down(401)
            scheduleRetry()
            return
        }
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        buffer.removeAll(keepingCapacity: true)
        pendingId = nil
        pendingData.removeAll()
        let task = session.dataTask(with: request)
        self.task = task
        task.resume()
    }

    private func scheduleRetry() {
        guard !stopped else { return }
        let delays: [TimeInterval] = [1, 2, 4, 8, 15, 30]
        let delay = delays[min(attempt, delays.count - 1)]
        attempt += 1
        retryTimer?.invalidate()
        retryTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in self?.connect() }
    }

    // MARK: URLSessionDataDelegate (delegate queue = main)

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard dataTask === task else { return completionHandler(.cancel) }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 200 {
            if state != .live { Log.write("sync stream connected") }
            attempt = 0
            state = .live
            completionHandler(.allow)
        } else {
            if state != .down(status) { Log.write("sync stream unavailable (HTTP \(status))") }
            state = .down(status)
            completionHandler(.cancel)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard dataTask === task else { return }
        buffer.append(data)
        while let newline = buffer.firstIndex(of: 0x0A) {
            var line = buffer[buffer.startIndex..<newline]
            if line.last == 0x0D { line = line.dropLast() }
            buffer.removeSubrange(buffer.startIndex...newline)
            handleLine(String(decoding: line, as: UTF8.self))
        }
        if buffer.count > 4_000_000 { buffer.removeAll() }  // a runaway line; drop it
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard task === self.task else { return }
        self.task = nil
        if case .live = state {
            Log.write("sync stream closed; reconnecting")
            state = .connecting
        } else if case .connecting = state, error != nil {
            Log.write("sync stream: bridge not reachable")
            state = .down(0)
        }
        scheduleRetry()
    }

    private func handleLine(_ line: String) {
        if line.isEmpty {
            dispatch()
            return
        }
        if line.hasPrefix(":") { return }  // heartbeat / comment
        let field: Substring
        var value: Substring
        if let colon = line.firstIndex(of: ":") {
            field = line[line.startIndex..<colon]
            value = line[line.index(after: colon)...]
            if value.first == " " { value = value.dropFirst() }
        } else {
            field = Substring(line)
            value = ""
        }
        switch field {
        case "id": pendingId = String(value)
        case "data": pendingData.append(String(value))
        default: break
        }
    }

    private func dispatch() {
        defer {
            pendingId = nil
            pendingData.removeAll()
        }
        if let id = pendingId, !id.isEmpty { cursor = id }
        guard !pendingData.isEmpty,
              let object = try? JSONSerialization.jsonObject(with: Data(pendingData.joined(separator: "\n").utf8)),
              var event = object as? [String: Any] else { return }
        if event["type"] == nil, let inner = event["event"] as? [String: Any] {
            if let innerCursor = event["cursor"] { cursor = cursor ?? "\(innerCursor)" }
            event = inner
        } else if pendingId == nil, let innerCursor = event["cursor"] {
            cursor = "\(innerCursor)"
        }
        onEvent?(event)
    }
}

/// One row of `GET /v1/sync/conversations`.
struct ConversationSummary: Identifiable, Equatable {
    let id: String
    let title: String
    let lastAt: Date
    let live: Bool

    static func parseDate(_ value: Any?) -> Date? {
        if let number = value as? NSNumber {
            let raw = number.doubleValue
            return Date(timeIntervalSince1970: raw > 1e11 ? raw / 1000 : raw)
        }
        if let text = value as? String {
            if let raw = Double(text) { return parseDate(NSNumber(value: raw)) }
            let formatter = ISO8601DateFormatter()
            formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = formatter.date(from: text) { return date }
            formatter.formatOptions = [.withInternetDateTime]
            return formatter.date(from: text)
        }
        return nil
    }

    init?(_ json: [String: Any]) {
        guard let id = (json["conversationId"] ?? json["id"]) as? String, !id.isEmpty else { return nil }
        self.id = id
        let title = (json["title"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        self.title = title.isEmpty ? "New conversation" : title
        lastAt = Self.parseDate(json["lastAt"]) ?? Self.parseDate(json["startedAt"]) ?? Date.distantPast
        live = json["live"] as? Bool ?? false
    }
}

enum SyncAPI {
    /// GET /v1/sync/conversations?limit=… → summaries, or the HTTP status (0 = unreachable).
    static func conversations(limit: Int = 8) async -> Result<[ConversationSummary], SyncFailure> {
        guard let request = Config.request(Config.api("/v1/sync/conversations",
                                                      query: [URLQueryItem(name: "limit", value: String(limit))])) else {
            return .failure(SyncFailure(status: 401))
        }
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 401, let text = String(data: data.prefix(2048), encoding: .utf8), text.contains("bridge token") {
                return .failure(SyncFailure(status: 404))  // a bridge without the sync module (bearer-only routes)
            }
            guard status == 200 else { return .failure(SyncFailure(status: status)) }
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            let rows = json?["conversations"] as? [[String: Any]] ?? []
            return .success(rows.compactMap(ConversationSummary.init))
        } catch {
            return .failure(SyncFailure(status: 0))
        }
    }
}

struct SyncFailure: Error, Equatable {
    let status: Int
}
