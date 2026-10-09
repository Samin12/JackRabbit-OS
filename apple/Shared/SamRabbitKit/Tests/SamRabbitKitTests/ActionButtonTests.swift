import Foundation
import Testing
@testable import SamRabbitKit

/// The Action Button and the Ask controls: an App Intent leaves `samrabbit://ask?listen=1` in the
/// App Group, and the app opens Ask with dictation listening.
@Suite("Action Button routes")
struct ActionButtonTests {
    private func defaults() -> UserDefaults { SharedContainer.temporary().defaults }

    @Test func aRouteIsTakenOnce() throws {
        let store = defaults()
        PendingRoute.set(PendingRoute.askListening, in: store)
        #expect(PendingRoute.take(from: store)?.absoluteString == "samrabbit://ask?listen=1")
        #expect(PendingRoute.take(from: store) == nil)
    }

    @Test func settingARoutePostsDidChange() {
        let store = defaults()
        let center = NotificationCenter.default
        final class Flag: @unchecked Sendable { var raised = false }
        let flag = Flag()
        // Posted synchronously, on this thread.
        let observer = center.addObserver(forName: PendingRoute.didChange, object: nil, queue: nil) { _ in flag.raised = true }
        PendingRoute.set("samrabbit://tab/home", in: store)
        center.removeObserver(observer)
        #expect(flag.raised)
    }

    /// The intent ran but the app never came up: hours later the Ask sheet must not pop up listening.
    @Test func aStaleRouteIsDropped() {
        let store = defaults()
        let then = Date(timeIntervalSince1970: 1_800_000_000)
        PendingRoute.set(PendingRoute.askListening, in: store, now: then)
        #expect(PendingRoute.take(from: store, now: then.addingTimeInterval(PendingRoute.lifetime + 1)) == nil)
        #expect(store.object(forKey: PendingRoute.key) == nil, "dropped, not kept for later")
        PendingRoute.set(PendingRoute.askListening, in: store, now: then)
        #expect(PendingRoute.take(from: store, now: then.addingTimeInterval(30)) != nil)
    }

    @Test func olderPlainStringsAndForeignLinksAreHandled() {
        let store = defaults()
        store.set("samrabbit://mac/screenshot", forKey: PendingRoute.key)
        #expect(PendingRoute.take(from: store)?.absoluteString == "samrabbit://mac/screenshot")
        store.set(["url": "https://example.com", "at": Date.now.timeIntervalSince1970], forKey: PendingRoute.key)
        #expect(PendingRoute.take(from: store) == nil)
        store.set(["url": "samrabbit://ask"], forKey: PendingRoute.key)
        #expect(PendingRoute.take(from: store) == nil, "a dictionary without a time is not trusted")
    }

    /// The app's own intents may open Ask listening; a link from a web page or a message never turns
    /// the microphone on (it only opens the empty composer).
    @Test func onlyTheAppsOwnIntentsStartDictation() throws {
        let url = try #require(URL(string: PendingRoute.askListening))
        #expect(AppLink(url: url, fromApp: true) == .compose(.ask, listen: true))
        #expect(AppLink(url: url) == .compose(.ask, listen: false))
        #expect(AppLink(url: URL(string: "samrabbit://note?listen=true")!, fromApp: true) == .compose(.note, listen: true))
        #expect(AppLink(url: URL(string: "samrabbit://ask")!, fromApp: true) == .compose(.ask, listen: false))
        #expect(AppLink(url: URL(string: "samrabbit://ask?listen=0")!, fromApp: true) == .compose(.ask, listen: false))
        // Text still needs Confirm, whoever sent it.
        #expect(AppLink(url: URL(string: "samrabbit://ask?listen=1&text=Deploy")!, fromApp: true) ==
                .confirm(.ask(text: "Deploy")))
    }
}
