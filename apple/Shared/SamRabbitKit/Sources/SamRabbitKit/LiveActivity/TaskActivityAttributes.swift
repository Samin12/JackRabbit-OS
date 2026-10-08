#if os(iOS) && canImport(ActivityKit)
import ActivityKit
import Foundation

/// The "Task running" Live Activity for a T3 task started from the phone. The app starts it and
/// updates it while it runs (foreground polling and background refresh); the widget extension
/// draws it on the Lock Screen and in the Dynamic Island.
public struct TaskActivityAttributes: ActivityAttributes {
    public struct ContentState: Codable, Hashable, Sendable {
        public var status: ThreadStatus
        public var detail: String
        public var updatedAt: Date

        public init(status: ThreadStatus, detail: String, updatedAt: Date = .now) {
            self.status = status
            self.detail = detail
            self.updatedAt = updatedAt
        }
    }

    public var threadId: String
    public var title: String
    public var projectName: String
    public var startedAt: Date

    public init(threadId: String, title: String, projectName: String, startedAt: Date = .now) {
        self.threadId = threadId
        self.title = title
        self.projectName = projectName
        self.startedAt = startedAt
    }
}
#endif
