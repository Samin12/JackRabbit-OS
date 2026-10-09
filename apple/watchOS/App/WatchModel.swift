import Foundation
import Observation
import SamRabbitKit
import SwiftUI
import WatchKit

/// The watch's pages, top to bottom (Digital Crown or swipe).
enum WatchPage: String, CaseIterable, Hashable {
    case status, needs, working, upnext, quick

    /// `samrabbit://tab/<name>` from complications (also accepts the iPhone's names).
    init?(link: String) {
        switch link.lowercased() {
        case "status", "home", "ask": self = .status
        case "needs", "needsyou", "tasks": self = .needs
        case "working": self = .working
        case "upnext", "next", "calendar": self = .upnext
        case "quick", "actions": self = .quick
        default: return nil
        }
    }
}

/// A short result line shown after an action ("Approved", "Blocked 2:10 – 2:40 PM").
struct WatchBanner: Equatable, Identifiable {
    enum Style { case success, failure }
    let id = UUID()
    var style: Style
    var title: String
    var detail: String?
}

/// Everything the watch shows and does. One instance, on the main actor.
@MainActor
@Observable
final class WatchModel {
    let account: BridgeAccount
    let actions: SamRabbitActions
    let link: PhoneLink

    var page: WatchPage = .status
    var paired: Bool
    var summary: MobileSummary?
    var summaryDate: Date?
    /// The last thread list that loaded (kept while T3 Code is not answering).
    var threads: [TaskThread] = []
    /// The Mac itself did not answer (or refused the token).
    var lastError: BridgeError?
    /// The thread list failed while the Mac answered (T3 Code not running, ...).
    var tasksError: BridgeError?
    var route: PhoneLink.Route = .direct
    var refreshing = false
    var connecting = false
    /// Thread ids (and "block" / "note" / "ask") with a request in flight.
    var busy: Set<String> = []
    var banner: WatchBanner?
    /// The voice capture on screen (the only way the watch takes text).
    var voice: VoiceRequest?

    private var loop: Task<Void, Never>?
    private var bannerTask: Task<Void, Never>?
    private var pairingObserver: (any NSObjectProtocol)?
    private var routeObserver: (any NSObjectProtocol)?
    /// The app is in front (between `start()` and `stop()`).
    private var active = false

    init(link: PhoneLink = .shared) {
        self.link = link
        // Same storage as `BridgeAccount.shared` (where PhoneLink saves the pairing), plus the
        // iPhone as the relay for every request.
        account = BridgeAccount(relay: link)
        actions = SamRabbitActions(account: account)
        paired = account.isPaired
        if let cached = SummaryCache.shared.load() {
            summary = cached.summary
            summaryDate = cached.savedAt
        }
        if let raw = UserDefaults.standard.string(forKey: "SamRabbitPage"), let page = WatchPage(link: raw) {
            self.page = page
        }
        pairingObserver = NotificationCenter.default.addObserver(forName: .samRabbitPairingChanged, object: nil,
                                                                 queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.pairingChanged() }
        }
        // The Action Button's control (or Siri) ran its intent in this app while it is in front: no
        // scene phase change follows, so follow its route now. Otherwise `start()` does.
        routeObserver = NotificationCenter.default.addObserver(forName: PendingRoute.didChange, object: nil,
                                                               queue: .main) { [weak self] _ in
            MainActor.assumeIsolated {
                guard let self, self.active else { return }
                self.consumePendingRoute()
            }
        }
    }

    // MARK: - Derived state

    var reachable: Bool { lastError == nil && summary != nil }

    var mood: OrbMood { paired && !rejected ? OrbMood.from(summary, reachable: reachable || isFresh) : .offline }

    /// The cached summary is recent enough to trust while a refresh fails.
    var isFresh: Bool { summaryDate.map { -$0.timeIntervalSinceNow < 120 } ?? false }

    /// The Mac refused the watch's token (revoked from the Mac): reconnect through the iPhone.
    var rejected: Bool { lastError == .unauthorized }

    /// T3 Code is not answering while the Mac does: only the task pages say so.
    var tasksProblem: BridgeError? { lastError == nil ? tasksError : nil }

    var needsYou: [TaskThread] {
        let list = threads.isEmpty ? (summary?.t3.threads ?? []) : threads
        return list.filter(\.status.needsYou)
    }

    var working: [TaskThread] {
        let list = threads.isEmpty ? (summary?.t3.threads ?? []) : threads
        return list.filter { $0.status == .working }
    }

    var needsYouCount: Int { threads.isEmpty ? (summary?.t3.needsYou ?? 0) : needsYou.count }
    var workingCount: Int { threads.isEmpty ? (summary?.t3.working ?? 0) : working.count }

    func events(now: Date = .now) -> [CalendarEvent] {
        (summary?.calendar.next ?? []).filter { ($0.endsAt ?? $0.startsAt ?? .distantFuture) > now }
    }

    // MARK: - Refresh

    /// The summary and the thread list, each with its own error: T3 Code not running (the list
    /// answers 503) leaves the orb, Up next and the complications fresh.
    func refresh() async {
        guard paired, let client = account.client() else { return }
        refreshing = true
        defer { refreshing = false }
        async let summaryCall = BridgeError.capture { try await client.summary(timeout: 6) }
        async let threadsCall = BridgeError.capture { try await client.threads(timeout: 8) }
        let (summaryResult, threadsResult) = await (summaryCall, threadsCall)
        switch summaryResult {
        case .success(let fresh):
            summary = fresh
            summaryDate = .now
            lastError = nil
            // The complications reload only when what they show changed (not every 30 s).
            SummaryCache.shared.publish(fresh)
        case .failure(let error):
            if error != .cancelled { lastError = error }
        }
        switch threadsResult {
        case .success(let list):
            threads = list
            tasksError = nil
        case .failure(let error):
            if error != .cancelled { tasksError = error }
        }
        route = link.lastRoute
    }

    /// Refreshes every 30 seconds while the app is in front, after following a route an intent left.
    func start() {
        active = true
        consumePendingRoute()
        loop?.cancel()
        loop = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }

    func stop() {
        active = false
        loop?.cancel()
        loop = nil
    }

    private func pairingChanged() {
        let nowPaired = account.isPaired
        defer { paired = nowPaired }
        if !nowPaired {
            summary = nil
            threads = []
            lastError = nil
            tasksError = nil
            return
        }
        lastError = nil
        Task { await refresh() }
    }

    /// Asks the iPhone for the pairing (or for a new token after the Mac rejected the old one).
    func connect(reissue: Bool = false) async {
        connecting = true
        defer { connecting = false }
        let ok = await link.requestContext(reissue: reissue)
        paired = account.isPaired
        if ok {
            lastError = nil
            await refresh()
        } else {
            show(.failure, link.phoneReachable ? "Pair your iPhone first" : "iPhone not reachable")
        }
    }

    // MARK: - Voice

    /// Opens the voice capture for `purpose` (it starts listening by itself). `after` runs once the
    /// words were sent.
    func listen(_ purpose: VoicePurpose, after: (@MainActor () async -> Void)? = nil) {
        guard paired else { return }
        if rejected {
            show(.failure, "Watch removed", detail: "Reconnect through your iPhone.")
            return
        }
        guard !busy.contains(purpose.busyKey) else { return }
        voice = VoiceRequest(purpose: purpose, after: after)
    }

    /// Ask by voice without a tap: the status page, then the voice capture, already listening. This is
    /// what the Watch Ultra's Action Button (the "Ask SamRabbit" control) and Siri ("Ask SamRabbit") run.
    func askByVoice() async {
        // Jump to the status page without the paging animation, then let it settle (right after launch
        // the interface takes a moment) before the capture covers it.
        var jump = Transaction()
        jump.disablesAnimations = true
        withTransaction(jump) { page = .status }
        guard paired, !rejected, voice == nil else { return }
        try? await Task.sleep(for: .milliseconds(400))
        listen(.ask)
    }

    /// Performs what a voice capture was for, with its words. `sent` runs as soon as the bridge took it
    /// (before the refresh), so the capture can close. Returns false when it failed (`banner` says why).
    func send(_ purpose: VoicePurpose, _ text: String, sent: (@MainActor () -> Void)? = nil) async -> Bool {
        switch purpose {
        case .ask: await ask(text, sent: sent)
        case .reply(let thread): await reply(thread, text, sent: sent)
        case .answer(let thread, let pending): await answer(thread, text, pending: pending, sent: sent)
        case .note: await note(text, sent: sent)
        }
    }

    // MARK: - Actions

    @discardableResult
    func ask(_ text: String, sent: (@MainActor () -> Void)? = nil) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return await run("ask", sent: sent) {
            let created = try await self.actions.ask(trimmed)
            let title = created.title ?? Formatting.clip(trimmed, 40)
            self.show(.success, "Started", detail: created.projectName.map { "“\(title)” · \($0)" } ?? "“\(title)”")
        }
    }

    /// Approves or denies exactly the request the card shows (`pending.requestId`); the pages only
    /// offer it when the card knows its request.
    func approve(_ thread: TaskThread, _ approve: Bool, pending: PendingAction? = nil) async {
        guard let requestId = (pending ?? thread.pending)?.requestId else {
            show(.failure, "Open the task", detail: "Check what it asks first.")
            return
        }
        await run(thread.threadId) {
            try await self.actions.approve(threadId: thread.threadId, requestId: requestId, approve)
            self.drop(thread.threadId)
            self.show(.success, approve ? "Approved" : "Denied", detail: thread.title)
        }
    }

    /// Answers exactly the question the card shows (see `approve`).
    @discardableResult
    func answer(_ thread: TaskThread, _ text: String, pending: PendingAction? = nil,
                sent: (@MainActor () -> Void)? = nil) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        guard let requestId = (pending ?? thread.pending)?.requestId else {
            show(.failure, "Open the task", detail: "Check what it asks first.")
            return false
        }
        return await run(thread.threadId, sent: sent) {
            try await self.actions.answer(threadId: thread.threadId, requestId: requestId, trimmed)
            self.drop(thread.threadId)
            self.show(.success, "Answer sent", detail: thread.title)
        }
    }

    /// A message into the thread (a reply while it waits for approval, or a follow-up).
    @discardableResult
    func reply(_ thread: TaskThread, _ text: String, sent: (@MainActor () -> Void)? = nil) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return await run(thread.threadId, sent: sent) {
            try await self.account.requireClient().sendMessage(threadId: thread.threadId, text: trimmed)
            self.show(.success, "Reply sent", detail: thread.title)
        }
    }

    func stop(_ thread: TaskThread) async {
        await run(thread.threadId) {
            try await self.account.requireClient().stop(threadId: thread.threadId)
            self.show(.success, "Stopped", detail: thread.title)
        }
    }

    func block(minutes: Int = 30) async {
        await run("block") {
            let result = try await self.actions.block(minutes: minutes)
            let range = result.event.map { Formatting.range($0.startsAt, $0.endsAt) } ?? "from now"
            self.show(.success, "Blocked \(minutes) min", detail: result.dryRun ? "\(range) · test copy" : range)
        }
    }

    @discardableResult
    func note(_ text: String, sent: (@MainActor () -> Void)? = nil) async -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return await run("note", sent: sent) {
            let result = try await self.actions.note(trimmed)
            self.show(.success, result.state == "queued" ? "Note queued" : "Added to journal",
                      detail: Formatting.clip(trimmed, 50))
        }
    }

    /// Runs an action with the busy flag, haptics, a banner on failure and a refresh after (after a
    /// refusal from the bridge too, so a card that went stale is replaced by what is true now).
    /// `sent` runs right after the bridge took it, before the refresh. Returns whether it worked.
    @discardableResult
    private func run(_ key: String, sent: (@MainActor () -> Void)? = nil,
                     _ body: @escaping @MainActor () async throws -> Void) async -> Bool {
        guard !busy.contains(key) else {
            if sent != nil { show(.failure, "Still sending", detail: "Wait a moment.") }
            return false
        }
        busy.insert(key)
        defer { busy.remove(key) }
        do {
            try await body()
            WKInterfaceDevice.current().play(.success)
            sent?()
            route = link.lastRoute
            await refresh()
            return true
        } catch let error as BridgeError {
            if error == .cancelled { return false }
            WKInterfaceDevice.current().play(.failure)
            if error == .unauthorized { lastError = .unauthorized }
            if error.isStaleRequest {
                // Answered elsewhere, or T3 asked something new: never act on the old card.
                drop(key)
                show(.failure, "Request changed", detail: "Check it again.")
            } else {
                show(.failure, error.shortDescription, detail: error.watchDetail)
            }
            if case .server = error {
                route = link.lastRoute
                await refresh()
            }
            return false
        } catch {
            WKInterfaceDevice.current().play(.failure)
            show(.failure, "Something went wrong")
            return false
        }
    }

    /// Removes a thread from what needs you right away (the refresh confirms).
    private func drop(_ threadId: String) {
        threads.removeAll { $0.threadId == threadId && $0.status.needsYou }
    }

    func show(_ style: WatchBanner.Style, _ title: String, detail: String? = nil) {
        let banner = WatchBanner(style: style, title: title, detail: detail)
        self.banner = banner
        bannerTask?.cancel()
        bannerTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(style == .success ? 3.5 : 6))
            guard !Task.isCancelled, self?.banner?.id == banner.id else { return }
            withAnimation { self?.banner = nil }
        }
    }

    // MARK: - Links

    /// A route left by the watch's intents (`OpenSamRabbitWatchIntent`) in the App Group.
    func consumePendingRoute() {
        guard let url = PendingRoute.take(from: account.container.defaults) else { return }
        handle(url: url, fromApp: true)
    }

    /// `samrabbit://tab/<page>`, `samrabbit://ask`, `samrabbit://thread/<id>`. `fromApp`: the route
    /// came from the watch's own intent; only then does `ask?listen=1` open the voice capture by itself.
    func handle(url: URL, fromApp: Bool = false) {
        guard url.scheme?.lowercased() == SamRabbit.urlScheme else { return }
        if case .compose(.ask, listen: true)? = AppLink(url: url, fromApp: fromApp) {
            Task { await askByVoice() }
            return
        }
        let target = (url.host ?? "").lowercased()
        let first = url.path.split(separator: "/").first.map(String.init) ?? ""
        switch target {
        case "tab": if let page = WatchPage(link: first) { self.page = page }
        case "thread", "task": page = needsYou.contains { $0.threadId == first } ? .needs : .working
        case "block", "note": page = .quick
        default: if let page = WatchPage(link: target) { self.page = page }
        }
    }
}

extension BridgeError {
    /// One short sentence for the watch.
    var watchDetail: String? {
        switch self {
        case .unreachable: "Mac and iPhone are out of reach."
        case .unauthorized: "Reconnect through your iPhone."
        case .notPaired: "Pair SamRabbit on your iPhone."
        case .server(_, _, let message, _): message.isEmpty ? nil : message
        default: nil
        }
    }
}
