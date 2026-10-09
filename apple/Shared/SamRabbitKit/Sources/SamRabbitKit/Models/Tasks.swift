import Foundation

/// A T3 Code thread's state as the mobile API reports it.
public enum ThreadStatus: String, Codable, Sendable, CaseIterable {
    case needsApproval = "needs_approval"
    case needsInput = "needs_input"
    case working
    case done
    case error
    case idle
    case unknown

    /// Accepts `needs_approval`, `needs-approval`, `needsApproval` and similar spellings.
    public init(raw: String?) {
        let key = (raw ?? "").lowercased().replacingOccurrences(of: "-", with: "_")
        switch key {
        case "needs_approval", "needsapproval", "approval": self = .needsApproval
        case "needs_input", "needsinput", "question", "input": self = .needsInput
        case "working", "running", "active", "busy": self = .working
        case "done", "finished", "completed", "complete", "ok": self = .done
        case "error", "failed", "failure": self = .error
        case "idle", "ready", "waiting": self = .idle
        default: self = .unknown
        }
    }

    public init(from decoder: Decoder) throws {
        self.init(raw: try? decoder.singleValueContainer().decode(String.self))
    }

    public var needsYou: Bool { self == .needsApproval || self == .needsInput }

    /// Short label for chips ("Needs approval", "Working", ...).
    public var label: String {
        switch self {
        case .needsApproval: "Needs approval"
        case .needsInput: "Has a question"
        case .working: "Working"
        case .done: "Done"
        case .error: "Error"
        case .idle: "Idle"
        case .unknown: "Unknown"
        }
    }

    /// Sort order: what needs you first, then work in progress, then the rest.
    public var rank: Int {
        switch self {
        case .needsApproval: 0
        case .needsInput: 1
        case .working: 2
        case .error: 3
        case .done: 4
        case .idle: 5
        case .unknown: 6
        }
    }
}

/// One choice offered by a pending approval or question.
public struct PendingOption: Codable, Sendable, Hashable, Identifiable {
    /// The value sent back (`decision` for approvals, the answer text for questions).
    public var value: String
    public var label: String
    public var id: String { value }

    public init(value: String, label: String? = nil) {
        self.value = value
        self.label = label ?? value
    }

    public init(from decoder: Decoder) throws {
        if let text = try? decoder.singleValueContainer().decode(String.self) {
            self.init(value: text)
            return
        }
        let c = try decoder.container(keyedBy: AnyKey.self)
        let label = c.text("label", "title", "text")
        let value = c.text("value", "decision", "id") ?? label ?? ""
        self.init(value: value, label: label)
    }
}

/// What a thread is waiting on: an approval (approve / deny) or a question (an answer).
///
/// `requestId` names the T3 request this card shows. Approve, Deny and Answer always send it, so the
/// bridge answers exactly that request, or refuses with 409 `t3_request_not_pending` when it was
/// answered elsewhere and T3 has asked something new in the meantime. Without an id (a summary
/// thread, which carries no pending details) nothing may be answered from the card: open the thread.
public struct PendingAction: Codable, Sendable, Equatable {
    public enum Kind: String, Codable, Sendable {
        case approval
        case question
        case unknown
    }

    public var kind: Kind
    public var text: String
    public var options: [PendingOption]
    /// The T3 request (`pending.requestId`).
    public var requestId: String?
    /// The question being asked (`pending.questionId`, else the first of `pending.questions`).
    public var questionId: String?

    public init(kind: Kind, text: String, options: [PendingOption] = [], requestId: String? = nil,
                questionId: String? = nil) {
        self.kind = kind
        self.text = text
        self.options = options
        self.requestId = requestId
        self.questionId = questionId
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        let raw = (c.text("kind", "type") ?? "").lowercased()
        kind = raw.hasPrefix("approv") ? .approval : (raw.hasPrefix("question") || raw == "input") ? .question : .unknown
        text = c.text("text", "question", "detail") ?? ""
        options = c.list(PendingOption.self, "options")
        requestId = c.text("requestId")
        questionId = c.text("questionId") ?? c.list(JSONValue.self, "questions").first?["id"].text
    }

    /// Approve / Deny / Answer can be sent from this card (it knows which request it shows).
    public var canRespond: Bool { requestId != nil }
}

/// A T3 Code thread (`/v1/mobile/t3/threads` items and `summary.t3.threads`).
public struct TaskThread: Codable, Sendable, Equatable, Identifiable {
    public var threadId: String
    public var title: String
    public var projectId: String?
    /// `projectName` in thread lists, `project` in the summary.
    public var projectName: String?
    public var status: ThreadStatus
    public var updatedAt: Date?
    public var summary: String?
    public var pending: PendingAction?

    public var id: String { threadId }

    public init(threadId: String, title: String, projectId: String? = nil, projectName: String? = nil,
                status: ThreadStatus, updatedAt: Date? = nil, summary: String? = nil, pending: PendingAction? = nil) {
        self.threadId = threadId
        self.title = title
        self.projectId = projectId
        self.projectName = projectName
        self.status = status
        self.updatedAt = updatedAt
        self.summary = summary
        self.pending = pending
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        guard let id = c.text("threadId", "id") else {
            throw DecodingError.keyNotFound(AnyKey("threadId"), .init(codingPath: decoder.codingPath,
                                                                      debugDescription: "thread without an id"))
        }
        threadId = id
        title = c.text("title") ?? "Untitled task"
        projectId = c.text("projectId")
        projectName = c.text("projectName", "project", "projectTitle")
        status = ThreadStatus(raw: c.text("status"))
        updatedAt = c.date("updatedAt")
        summary = c.text("summary", "lastMessage")
        pending = c.value(PendingAction.self, "pending")
        if let pending, pending.text.isEmpty, pending.kind == .unknown, pending.options.isEmpty { self.pending = nil }
    }
}

/// `GET /v1/mobile/t3/threads?filter=` -> `{threads:[...]}`
public struct ThreadList: Codable, Sendable, Equatable {
    public var threads: [TaskThread]

    public init(threads: [TaskThread]) { self.threads = threads }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        threads = c.list(TaskThread.self, "threads")
    }
}

/// The filters `GET /v1/mobile/t3/threads` understands.
public enum ThreadFilter: String, Sendable, CaseIterable {
    case needsYou = "needs_you"
    case working
    case recent
}

/// One message of a thread.
public struct ThreadMessage: Codable, Sendable, Equatable, Identifiable {
    public enum Role: String, Codable, Sendable {
        case user
        case assistant
        case tool
    }

    public var id: String
    public var role: Role
    public var text: String
    public var at: Date?

    public init(id: String = UUID().uuidString, role: Role, text: String, at: Date? = nil) {
        self.id = id
        self.role = role
        self.text = text
        self.at = at
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        role = Role(rawValue: (c.text("role") ?? "assistant").lowercased()) ?? .assistant
        text = c.text("text") ?? ""
        at = c.date("at", "createdAt")
        id = c.text("id") ?? "\(role.rawValue):\(at?.timeIntervalSince1970 ?? 0):\(text.hashValue)"
    }
}

/// `GET /v1/mobile/t3/threads/<id>` -> `{thread, messages, pending}`
public struct ThreadDetail: Codable, Sendable, Equatable {
    public var thread: TaskThread
    public var messages: [ThreadMessage]
    public var pending: PendingAction?

    public init(thread: TaskThread, messages: [ThreadMessage], pending: PendingAction?) {
        self.thread = thread
        self.messages = messages
        self.pending = pending
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        guard let thread = c.value(TaskThread.self, "thread") else {
            throw DecodingError.keyNotFound(AnyKey("thread"), .init(codingPath: decoder.codingPath,
                                                                    debugDescription: "missing thread"))
        }
        self.thread = thread
        messages = c.list(ThreadMessage.self, "messages")
        pending = c.value(PendingAction.self, "pending") ?? thread.pending
        if let pending, pending.text.isEmpty, pending.kind == .unknown { self.pending = nil }
    }
}

/// `POST /v1/mobile/t3/threads` -> `{threadId, title, projectName}`
public struct CreatedThread: Codable, Sendable, Equatable {
    public var threadId: String
    public var title: String?
    public var projectName: String?

    public init(threadId: String, title: String? = nil, projectName: String? = nil) {
        self.threadId = threadId
        self.title = title
        self.projectName = projectName
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        threadId = c.text("threadId", "id") ?? ""
        title = c.text("title")
        projectName = c.text("projectName", "project")
    }
}

/// `GET /v1/mobile/t3/projects` items.
public struct TaskProject: Codable, Sendable, Hashable, Identifiable {
    public var id: String
    public var name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        id = c.text("projectId", "id") ?? ""
        name = c.text("name", "title", "projectName") ?? id
    }
}

struct ProjectList: Decodable {
    var projects: [TaskProject]
    init(from decoder: Decoder) throws {
        if let list = try? decoder.singleValueContainer().decode([Lossy<TaskProject>].self) {
            projects = list.compactMap(\.value)
            return
        }
        let c = try decoder.container(keyedBy: AnyKey.self)
        projects = c.list(TaskProject.self, "projects")
    }
}
