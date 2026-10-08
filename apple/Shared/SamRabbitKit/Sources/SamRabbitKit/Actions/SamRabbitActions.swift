import Foundation
#if canImport(WidgetKit)
import WidgetKit
#endif

/// The actions every surface offers (app, widgets, App Intents, Siri, the watch), in one place so
/// they behave the same everywhere: each one calls the bridge, refreshes the shared summary and
/// reloads the widgets.
public struct SamRabbitActions: Sendable {
    public let account: BridgeAccount
    public let cache: SummaryCache
    public let tracker: TaskTracker

    public init(account: BridgeAccount = .shared, cache: SummaryCache = .shared, tracker: TaskTracker = .shared) {
        self.account = account
        self.cache = cache
        self.tracker = tracker
    }

    public static let shared = SamRabbitActions()

    /// A new T3 task with automatic placement (or in `projectId`). Tracked so the phone can say
    /// when it finishes.
    @discardableResult
    public func ask(_ text: String, projectId: String? = nil) async throws -> CreatedThread {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw BridgeError.server(status: 400, code: "empty", message: "Say what to do.", retryable: false) }
        let created = try await account.requireClient(timeout: 25).createThread(text: trimmed, projectId: projectId)
        tracker.track(TrackedTask(threadId: created.threadId, title: created.title ?? Formatting.clip(trimmed, 60),
                                  projectName: created.projectName, startedAt: .now))
        await refreshQuietly()
        return created
    }

    /// Blocks the calendar from now for `minutes` ("Focus" unless titled).
    @discardableResult
    public func block(minutes: Int, title: String? = nil) async throws -> CalendarWrite {
        let result = try await account.requireClient(timeout: 25).block(minutes: minutes, title: title)
        await refreshQuietly()
        return result
    }

    /// Appends the user's own words to today's Heptabase journal.
    @discardableResult
    public func note(_ text: String) async throws -> JournalWrite {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw BridgeError.server(status: 400, code: "empty", message: "The note is empty.", retryable: false) }
        return try await account.requireClient(timeout: 20).addJournalNote(trimmed)
    }

    @discardableResult
    public func openOnMac(app: String? = nil, url: String? = nil) async throws -> MacOpenResult {
        try await account.requireClient(timeout: 20).openOnMac(app: app, url: url)
    }

    public func approve(threadId: String, _ approve: Bool) async throws {
        try await account.requireClient().respond(threadId: threadId, approve: approve)
        await refreshQuietly()
    }

    public func answer(threadId: String, _ answer: String) async throws {
        try await account.requireClient().respond(threadId: threadId, answer: answer)
        await refreshQuietly()
    }

    /// Fetches the summary, caches it for the widgets and reloads them.
    @discardableResult
    public func refresh(timeout: TimeInterval = 10) async throws -> MobileSummary {
        let summary = try await account.requireClient(timeout: timeout).summary(timeout: timeout)
        cache.save(summary)
        Self.reloadWidgets()
        return summary
    }

    /// `refresh()` that never throws (after an action the action's own result matters).
    public func refreshQuietly() async {
        _ = try? await refresh()
    }

    /// The spoken "what needs me" sentence (fresh when the Mac answers, cached otherwise).
    public func whatNeedsMe() async -> String {
        if let summary = try? await refresh(timeout: 8) { return SpokenSummary.whatNeedsMe(summary) }
        if let cached = cache.load() {
            return SpokenSummary.whatNeedsMe(cached.summary) + " (As of \(Formatting.ago(cached.savedAt)).)"
        }
        return account.isPaired ? "I can't reach your Mac right now." : "SamRabbit isn't paired with your Mac yet."
    }

    public static func reloadWidgets() {
        #if canImport(WidgetKit) && !os(macOS)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }
}

/// A task started from this phone (so it can say when it finishes).
public struct TrackedTask: Codable, Sendable, Equatable, Identifiable {
    public var threadId: String
    public var title: String
    public var projectName: String?
    public var startedAt: Date
    public var lastStatus: ThreadStatus = .working
    public var id: String { threadId }

    public init(threadId: String, title: String, projectName: String?, startedAt: Date, lastStatus: ThreadStatus = .working) {
        self.threadId = threadId
        self.title = title
        self.projectName = projectName
        self.startedAt = startedAt
        self.lastStatus = lastStatus
    }
}

/// Tasks started from the phone and the needs-you items already announced (App Group).
public final class TaskTracker: Sendable {
    public static let shared = TaskTracker()
    private let container: SharedContainer
    private static let tasksFile = "tracked-tasks.json"
    private static let announcedFile = "announced.json"
    /// Tracked tasks are dropped after a day either way.
    static let maxAge: TimeInterval = 86_400

    public init(container: SharedContainer = .shared) { self.container = container }

    public var tasks: [TrackedTask] {
        (container.load([TrackedTask].self, from: Self.tasksFile) ?? []).filter { -$0.startedAt.timeIntervalSinceNow < Self.maxAge }
    }

    public func track(_ task: TrackedTask) {
        var list = tasks.filter { $0.threadId != task.threadId }
        list.append(task)
        container.save(Array(list.suffix(20)), as: Self.tasksFile)
    }

    public func update(_ list: [TrackedTask]) { container.save(list, as: Self.tasksFile) }

    public func untrack(_ threadId: String) { update(tasks.filter { $0.threadId != threadId }) }

    /// Keys of needs-you items that already raised a notification.
    public var announced: Set<String> { Set(container.load([String].self, from: Self.announcedFile) ?? []) }

    public func setAnnounced(_ keys: Set<String>) { container.save(Array(keys).sorted().suffix(200), as: Self.announcedFile) }
}

/// A local notification the app should post.
public struct PlannedAlert: Sendable, Equatable, Identifiable {
    public enum Kind: String, Sendable { case needsYou, finished, failed }
    public var id: String
    public var kind: Kind
    public var title: String
    public var body: String
    public var threadId: String
}

/// Decides which local notifications a fresh summary deserves (no push: the background refresh
/// and the foreground app both run this).
public enum AlertPlanner {
    public struct Plan: Sendable, Equatable {
        public var alerts: [PlannedAlert]
        public var announced: Set<String>
        public var tracked: [TrackedTask]
    }

    /// - Parameters:
    ///   - threads: the freshest thread states known (summary threads plus any looked up).
    ///   - announced: needs-you keys already notified.
    ///   - tracked: tasks started from the phone.
    public static func plan(threads: [TaskThread], announced: Set<String>, tracked: [TrackedTask]) -> Plan {
        var alerts: [PlannedAlert] = []
        var nextAnnounced = Set<String>()
        for thread in threads where thread.status.needsYou {
            let key = needsYouKey(thread)
            nextAnnounced.insert(key)
            guard !announced.contains(key) else { continue }
            let what = thread.status == .needsApproval ? "Needs your approval" : "Has a question for you"
            let detail = thread.pending?.text.isEmpty == false ? thread.pending!.text : (thread.summary ?? what)
            alerts.append(PlannedAlert(id: "needs:\(key)", kind: .needsYou, title: thread.title,
                                       body: Formatting.clip(detail, 160), threadId: thread.threadId))
        }
        // Keep announcing nothing twice while it still waits; forget keys that resolved.
        let byId = Dictionary(threads.map { ($0.threadId, $0) }, uniquingKeysWith: { first, _ in first })
        var remaining: [TrackedTask] = []
        for var task in tracked {
            guard let thread = byId[task.threadId] else {
                remaining.append(task)
                continue
            }
            if thread.status == .done || thread.status == .error {
                let failed = thread.status == .error
                alerts.append(PlannedAlert(id: "done:\(task.threadId)", kind: failed ? .failed : .finished,
                                           title: failed ? "Task failed" : "Task finished",
                                           body: "“\(Formatting.clip(thread.title, 70))”" +
                                               (thread.summary.map { " " + Formatting.clip($0, 110) } ?? ""),
                                           threadId: thread.threadId))
                continue
            }
            task.lastStatus = thread.status
            remaining.append(task)
        }
        return Plan(alerts: alerts, announced: nextAnnounced, tracked: remaining)
    }

    static func needsYouKey(_ thread: TaskThread) -> String {
        let text = thread.pending?.text ?? ""
        return "\(thread.threadId)|\(thread.status.rawValue)|\(text.prefix(60))"
    }
}
