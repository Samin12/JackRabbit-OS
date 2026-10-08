import Foundation

/// Every failure the bridge client reports.
public enum BridgeError: Error, Sendable, Equatable, LocalizedError {
    /// No bridge paired on this device.
    case notPaired
    /// The bridge rejected the token (revoked or replaced): pair again.
    case unauthorized
    /// The bridge answered with its error envelope `{error:{code,message,retryable}}`.
    case server(status: Int, code: String, message: String, retryable: Bool)
    /// No host answered (Mac asleep, other network, bridge stopped).
    case unreachable(String)
    /// The answer could not be read.
    case invalidResponse(String)
    case cancelled

    public var errorDescription: String? {
        switch self {
        case .notPaired: "Pair SamRabbit with your Mac first."
        case .unauthorized: "Your Mac no longer accepts this iPhone. Pair again in Settings."
        case .server(_, _, let message, _): message.isEmpty ? "The Mac couldn't do that." : message
        case .unreachable: "Can't reach your Mac. Is it awake and on the same network?"
        case .invalidResponse: "Your Mac sent an answer SamRabbit doesn't understand."
        case .cancelled: "Cancelled."
        }
    }

    /// The bridge's error code (`screen_locked`, `t3_not_connected`, ...), if any.
    public var code: String? {
        switch self {
        case .server(_, let code, _, _): code
        case .unauthorized: "unauthorized"
        case .notPaired: "not_paired"
        default: nil
        }
    }

    /// The approval or question this answered is no longer the open one (answered elsewhere, or T3
    /// moved on to a new request): refresh and let the person look again.
    public var isStaleRequest: Bool { code == "t3_request_not_pending" }

    /// The Mac answered, but T3 Code behind it did not (not running, not installed, not paired with
    /// the bridge): only the task areas are affected, the rest of the Mac works.
    public var isTaskServiceDown: Bool {
        guard case .server(_, let code, _, _) = self else { return false }
        return Self.taskServiceDownCodes.contains(code)
    }

    static let taskServiceDownCodes: Set<String> = [
        "t3_unavailable", "t3_app_missing", "t3_not_connected", "t3_unauthorized", "t3_pairing_rejected",
        "t3_cli_failed", "t3_cli_timeout", "t3_request_failed", "t3_bad_answer", "t3_url_invalid", "t3_dev_copy",
    ]

    public var isRetryable: Bool {
        switch self {
        case .server(_, _, _, let retryable): retryable
        case .unreachable: true
        default: false
        }
    }

    /// Short words for a status line.
    public var shortDescription: String {
        switch self {
        case .notPaired: "Not paired"
        case .unauthorized: "Pair again"
        case .unreachable: "Mac unreachable"
        case .server(_, let code, _, _):
            code == "screen_locked" ? "Screen locked"
                : isStaleRequest ? "That request changed"
                : isTaskServiceDown ? "T3 not connected" : "Mac error"
        case .invalidResponse: "Unexpected answer"
        case .cancelled: "Cancelled"
        }
    }

    static func from(status: Int, data: Data) -> BridgeError {
        if let envelope = try? JSONDecoder().decode(JSONValue.self, from: data) {
            let error = envelope["error"]
            let code = error["code"].string ?? error.string ?? "http_\(status)"
            let message = error["message"].string ?? envelope["message"].string ?? ""
            if status == 401 { return .unauthorized }
            return .server(status: status, code: code, message: message,
                           retryable: error["retryable"].bool ?? (status >= 500))
        }
        if status == 401 { return .unauthorized }
        return .server(status: status, code: "http_\(status)", message: "", retryable: status >= 500)
    }
}

extension BridgeError {
    /// Runs `body` and returns its value or the `BridgeError` it failed with (any other error counts
    /// as unreachable), so calls made side by side can fail on their own.
    public static func capture<T: Sendable>(_ body: @Sendable () async throws -> T) async -> Result<T, BridgeError> {
        do {
            return .success(try await body())
        } catch let error as BridgeError {
            return .failure(error)
        } catch is CancellationError {
            return .failure(.cancelled)
        } catch {
            return .failure(.unreachable(error.localizedDescription))
        }
    }
}
