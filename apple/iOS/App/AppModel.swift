import Foundation
import Observation
import SamRabbitKit
import SwiftUI
import UIKit

/// The tabs of the iPhone app.
enum AppTab: String, Hashable, CaseIterable {
    case home, chats, tasks, mac, settings
}

/// Sheets the app can present from anywhere (quick actions, widgets, Siri, deep links).
enum AppSheet: Identifiable, Equatable {
    case ask(prefill: String)
    case note
    case generate(prefill: String)
    case openOnMac
    case newTask
    case pair(PairLink)
    case manualPair

    var id: String {
        switch self {
        case .ask: "ask"
        case .note: "note"
        case .generate: "generate"
        case .openOnMac: "open"
        case .newTask: "newTask"
        case .pair: "pair"
        case .manualPair: "manualPair"
        }
    }
}

/// A short confirmation or error shown over the app.
struct Toast: Equatable, Identifiable {
    enum Style { case success, failure, info }
    let id = UUID()
    var style: Style
    var title: String
    var detail: String?
}

/// Everything the app shows and does. One instance, on the main actor.
@MainActor
@Observable
final class AppModel {
    let account: BridgeAccount
    let actions: SamRabbitActions

    var pairing: BridgePairing?
    var summary: MobileSummary?
    var summaryDate: Date?
    var lastError: BridgeError?
    var refreshing = false
    var threads: [TaskThread] = []
    var projects: [TaskProject] = []

    var tab: AppTab = .home
    var homePath = NavigationPath()
    var chatsPath = NavigationPath()
    var tasksPath = NavigationPath()
    var macPath = NavigationPath()
    var sheet: AppSheet?
    var toast: Toast?
    /// Asks the Mac tab to take a screenshot when it appears (widget / Siri "Screenshot my Mac").
    var pendingScreenshot = false

    /// Approve / deny / answer in flight, by thread id.
    var busyThreads: Set<String> = []

    private var refreshLoop: Task<Void, Never>?
    let notifications = NotificationController()
    let liveActivities = LiveActivityController()

    init(account: BridgeAccount = .shared) {
        self.account = account
        actions = SamRabbitActions(account: account)
        pairing = account.isPaired ? account.pairing : nil
        if let cached = SummaryCache.shared.load() {
            summary = cached.summary
            summaryDate = cached.savedAt
        }
    }

    var isPaired: Bool { pairing != nil }

    /// The Mac answered on the last refresh.
    var reachable: Bool { lastError == nil && summary != nil }

    var orbMood: OrbMood { isPaired ? OrbMood.from(summary, reachable: reachable) : .offline }

    var client: BridgeClient? { account.client() }

    // MARK: - Refresh

    /// Refreshes the summary and the thread list (and runs the notification / Live Activity checks).
    func refresh(quiet: Bool = true) async {
        guard isPaired, let client else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            async let summaryCall = client.summary()
            async let threadsCall = client.threads()
            let (fresh, list) = try await (summaryCall, threadsCall)
            summary = fresh
            summaryDate = .now
            threads = list
            lastError = nil
            SummaryCache.shared.save(fresh)
            SamRabbitActions.reloadWidgets()
            await notifications.check(threads: list)
            await liveActivities.update(with: list)
        } catch let error as BridgeError {
            if error == .cancelled { return }
            lastError = error
            if error == .unauthorized { handleUnauthorized() }
            if !quiet { show(error) }
        } catch {
            lastError = .unreachable(error.localizedDescription)
        }
    }

    /// Refreshes every 20 seconds while the app is active.
    func startRefreshing() {
        refreshLoop?.cancel()
        refreshLoop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(for: .seconds(20))
            }
        }
    }

    func stopRefreshing() {
        refreshLoop?.cancel()
        refreshLoop = nil
    }

    func loadProjects() async {
        guard let client, projects.isEmpty else { return }
        projects = (try? await client.projects()) ?? []
    }

    // MARK: - Pairing

    func pair(with link: PairLink) async -> Bool {
        do {
            let pairing = try await Pairer.pair(link: link, deviceName: UIDevice.current.name, platform: .ios,
                                                account: account)
            self.pairing = pairing
            lastError = nil
            show(.success, "Paired with \(pairing.displayName)")
            Haptics.success()
            await refresh()
            startRefreshing()
            await notifications.requestAuthorization()
            await WatchLink.shared.provision(reissue: true)
            return true
        } catch let error as BridgeError {
            Haptics.error()
            if case .server(_, let code, _, _) = error, code == "invalid_code" {
                show(.failure, "That code didn't work", detail: "Codes work once and expire after 10 minutes. Make a new one on your Mac.")
            } else {
                show(error)
            }
            return false
        } catch {
            show(.failure, "Pairing failed")
            return false
        }
    }

    func unpair() {
        WatchLink.shared.reset()
        account.unpair()
        SummaryCache.shared.clear()
        pairing = nil
        summary = nil
        threads = []
        lastError = nil
        stopRefreshing()
        SamRabbitActions.reloadWidgets()
        Task { await liveActivities.endAll() }
    }

    private func handleUnauthorized() {
        show(.failure, "Pair again", detail: "Your Mac no longer accepts this iPhone.")
    }

    // MARK: - Actions

    func ask(_ text: String, projectId: String? = nil) async -> Bool {
        do {
            let created = try await actions.ask(text, projectId: projectId)
            Haptics.success()
            show(.success, "Task started", detail: created.projectName.map { "In \($0)" })
            await liveActivities.start(threadId: created.threadId, title: created.title ?? Formatting.clip(text, 60),
                                       projectName: created.projectName ?? "T3")
            await refresh()
            return true
        } catch {
            fail(error)
            return false
        }
    }

    func note(_ text: String) async -> Bool {
        do {
            let result = try await actions.note(text)
            Haptics.success()
            show(.success, result.state == "queued" ? "Note queued for your journal" : "Added to today's journal")
            return true
        } catch {
            fail(error)
            return false
        }
    }

    func block(minutes: Int = 30) async {
        do {
            let result = try await actions.block(minutes: minutes)
            Haptics.success()
            let range = result.event.map { Formatting.range($0.startsAt, $0.endsAt) } ?? "now"
            show(.success, "Blocked \(minutes) min", detail: result.dryRun ? "\(range) (test copy: not written)" : range)
            await refresh()
        } catch {
            fail(error)
        }
    }

    func approve(_ thread: TaskThread, _ approve: Bool) async {
        busyThreads.insert(thread.threadId)
        defer { busyThreads.remove(thread.threadId) }
        do {
            try await actions.approve(threadId: thread.threadId, approve)
            Haptics.success()
            show(.success, approve ? "Approved" : "Denied", detail: thread.title)
            await refresh()
        } catch {
            fail(error)
        }
    }

    func answer(_ thread: TaskThread, _ text: String) async -> Bool {
        busyThreads.insert(thread.threadId)
        defer { busyThreads.remove(thread.threadId) }
        do {
            try await actions.answer(threadId: thread.threadId, text)
            Haptics.success()
            show(.success, "Answer sent", detail: thread.title)
            await refresh()
            return true
        } catch {
            fail(error)
            return false
        }
    }

    func reply(to threadId: String, _ text: String) async -> Bool {
        guard let client else { return false }
        do {
            try await client.sendMessage(threadId: threadId, text: text)
            Haptics.success()
            await refresh()
            return true
        } catch {
            fail(error)
            return false
        }
    }

    func stop(_ threadId: String) async {
        guard let client else { return }
        do {
            try await client.stop(threadId: threadId)
            Haptics.success()
            show(.info, "Stopped")
            await refresh()
        } catch {
            fail(error)
        }
    }

    func openOnMac(app: String? = nil, url: String? = nil) async -> Bool {
        do {
            let result = try await actions.openOnMac(app: app, url: url)
            Haptics.success()
            show(.success, "Opened on your Mac", detail: result.app ?? result.url ?? app ?? url)
            return true
        } catch {
            fail(error)
            return false
        }
    }

    // MARK: - Navigation

    /// `samrabbit://pair?…`, `ask`, `note`, `thread/<id>`, `conversation/<id>`, `tab/<name>`, `mac/screenshot`.
    func handle(url: URL) {
        guard url.scheme?.lowercased() == SamRabbit.urlScheme else { return }
        if let link = PairLink(url: url) {
            sheet = .pair(link)
            return
        }
        let target = (url.host ?? "").lowercased()
        let parts = url.path.split(separator: "/").map(String.init)
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let text = query.first { $0.name == "text" }?.value ?? ""
        switch target {
        case "pair":
            tab = .settings
            sheet = .manualPair
        case "ask":
            sheet = .ask(prefill: text)
        case "note":
            sheet = .note
        case "generate":
            sheet = .generate(prefill: text)
        case "block":
            Task { await block(minutes: Int(query.first { $0.name == "minutes" }?.value ?? "") ?? 30) }
        case "thread", "task":
            tab = .tasks
            tasksPath = NavigationPath()
            if let id = parts.first { tasksPath.append(ThreadRoute(threadId: id)) }
        case "conversation", "chat":
            tab = .chats
            chatsPath = NavigationPath()
            if let id = parts.first { chatsPath.append(ConversationRoute(conversationId: id, title: nil)) }
        case "mac":
            tab = .mac
            if parts.first == "screenshot" { pendingScreenshot = true }
        case "tab":
            if let name = parts.first, let value = AppTab(rawValue: name) { tab = value }
        default:
            break
        }
    }

    /// Routes left by intents and widgets that ran outside the app (App Group).
    func consumePendingRoute() {
        let defaults = account.container.defaults
        guard let raw = defaults.string(forKey: PendingRoute.key), let url = URL(string: raw) else { return }
        defaults.removeObject(forKey: PendingRoute.key)
        handle(url: url)
    }

    // MARK: - Toasts

    func show(_ style: Toast.Style, _ title: String, detail: String? = nil) {
        toast = Toast(style: style, title: title, detail: detail)
    }

    func show(_ error: BridgeError) {
        show(.failure, error.shortDescription, detail: error.errorDescription)
    }

    func fail(_ error: Error) {
        Haptics.error()
        if let bridge = error as? BridgeError {
            if bridge == .cancelled { return }
            if bridge == .unauthorized { handleUnauthorized() }
            show(bridge)
        } else {
            show(.failure, "Something went wrong", detail: error.localizedDescription)
        }
    }
}

struct ThreadRoute: Hashable {
    var threadId: String
}

struct ConversationRoute: Hashable {
    var conversationId: String
    var title: String?
}

enum Haptics {
    @MainActor static func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    @MainActor static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
    @MainActor static func tap() { UIImpactFeedbackGenerator(style: .soft).impactOccurred() }
}
