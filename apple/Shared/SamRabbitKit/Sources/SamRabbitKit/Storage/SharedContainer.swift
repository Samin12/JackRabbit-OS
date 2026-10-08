import Foundation
import os

let kitLog = Logger(subsystem: "com.samrabbit.mobile", category: "kit")

/// The App Group (`group.com.samrabbit.mobile`) shared by the app, its widgets and intents: a
/// `UserDefaults` suite for small values and a folder for files. Falls back to the process's own
/// storage when the group is unavailable (unit tests, unsigned builds).
public final class SharedContainer: Sendable {
    public static let shared = SharedContainer(appGroup: SamRabbit.appGroup)

    public let appGroup: String
    public let directory: URL
    /// True when the real App Group container is in use (so widgets see the same data).
    public let isShared: Bool
    private let suiteName: String?

    public init(appGroup: String, directory: URL? = nil) {
        self.appGroup = appGroup
        if let directory {
            self.directory = directory
            isShared = false
            suiteName = nil
        } else if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) {
            self.directory = url.appendingPathComponent("SamRabbit", isDirectory: true)
            isShared = true
            suiteName = appGroup
        } else {
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
            self.directory = base.appendingPathComponent("SamRabbit", isDirectory: true)
            isShared = false
            suiteName = nil
            kitLog.notice("App Group unavailable; using local storage")
        }
        try? FileManager.default.createDirectory(at: self.directory, withIntermediateDirectories: true)
    }

    /// A test container in its own temporary folder.
    public static func temporary() -> SharedContainer {
        let folder = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("samrabbit-test-\(UUID().uuidString)", isDirectory: true)
        return SharedContainer(appGroup: "test", directory: folder)
    }

    public var defaults: UserDefaults {
        if let suiteName, let suite = UserDefaults(suiteName: suiteName) { return suite }
        if suiteName == nil, !isShared, directory.path.contains("samrabbit-test-") {
            return UserDefaults(suiteName: "samrabbit.test.\(directory.lastPathComponent)") ?? .standard
        }
        return .standard
    }

    public func url(_ name: String) -> URL { directory.appendingPathComponent(name) }

    /// Writes atomically; files are protected until the first unlock (widgets read them while locked).
    public func write(_ data: Data, to name: String) throws {
        #if os(iOS) || os(watchOS)
        try data.write(to: url(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: url(name), options: [.atomic])
        #endif
    }

    public func read(_ name: String) -> Data? { try? Data(contentsOf: url(name)) }

    public func remove(_ name: String) { try? FileManager.default.removeItem(at: url(name)) }

    public func save<T: Encodable>(_ value: T, as name: String) {
        do {
            try write(BridgeJSON.encoder().encode(value), to: name)
        } catch {
            kitLog.error("could not save \(name, privacy: .public)")
        }
    }

    public func load<T: Decodable>(_ type: T.Type, from name: String) -> T? {
        guard let data = read(name) else { return nil }
        return try? BridgeJSON.decode(type, from: data)
    }
}
