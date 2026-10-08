import XCTest

/// A walkthrough of the Apple Watch app with real taps, against the fake bridge.
///
/// Needs: `apple/dev/fake_bridge.py` on 127.0.0.1:3799 (reset it first: `curl -X POST
/// 127.0.0.1:3799/__fake/reset`), the iPhone simulator paired with it and this watch simulator
/// paired with that iPhone (the watch gets its token from the phone). Skips itself when the watch
/// isn't paired. Every step attaches a screenshot (named `watch-…`).
@MainActor
final class WatchWalkthroughTests: XCTestCase {
    override func setUp() async throws {
        continueAfterFailure = false
    }

    private func launch(page: String, route: String? = nil) throws -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-SamRabbitPage", page]
        if let route { app.launchArguments += ["-SamRabbitRoute", route] }
        app.launch()
        if app.staticTexts["Pair on your iPhone"].waitForExistence(timeout: 3) {
            throw XCTSkip("The watch isn't paired (pair the iPhone simulator with the fake bridge first).")
        }
        return app
    }

    private func element(_ app: XCUIApplication, labelBeginsWith text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", text)).firstMatch
    }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Lets the data settle and animations finish before a screenshot.
    private func settle(_ seconds: TimeInterval = 1.2) {
        _ = XCUIApplication().wait(for: .runningForeground, timeout: seconds)
        Thread.sleep(forTimeInterval: seconds)
    }


    func test1_StatusPage() throws {
        let app = try launch(page: "status")
        XCTAssertTrue(app.buttons["Ask"].waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, labelBeginsWith: "2 need you").waitForExistence(timeout: 10))
        settle()
        snap("watch-1-status")
    }

    func test2_NeedsYouApproveAndAnswer() throws {
        let app = try launch(page: "needs")
        let approve = app.buttons["approve-t_deploy24"]
        XCTAssertTrue(approve.waitForExistence(timeout: 10))
        settle()
        snap("watch-2-needs-you")
        app.swipeUp()
        settle(0.8)
        snap("watch-2-needs-you-question")
        app.swipeDown()
        settle(0.8)
        approve.tap()
        XCTAssertTrue(element(app, labelBeginsWith: "Approved").waitForExistence(timeout: 15))
        snap("watch-2-needs-you-approved")
        // The question offers answers as buttons.
        let option = app.buttons["/home"]
        XCTAssertTrue(option.waitForExistence(timeout: 10))
        option.tap()
        XCTAssertTrue(element(app, labelBeginsWith: "Answer sent").waitForExistence(timeout: 15))
        settle(0.5)
        snap("watch-2-needs-you-answered")
    }

    func test3_WorkingAndThreadDetail() throws {
        let app = try launch(page: "working")
        let row = element(app, labelBeginsWith: "Weekly report from Linear")
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        settle()
        snap("watch-3-working")
        row.tap()
        XCTAssertTrue(app.buttons["Stop"].waitForExistence(timeout: 10) || app.buttons["Reply"].waitForExistence(timeout: 5))
        settle()
        snap("watch-3-thread-detail")
    }

    func test4_UpNext() throws {
        let app = try launch(page: "upnext")
        XCTAssertTrue(element(app, labelBeginsWith: "Lunch with Maya").waitForExistence(timeout: 10))
        settle()
        snap("watch-4-up-next")
    }

    func test5_QuickBlock() throws {
        let app = try launch(page: "quick")
        let block = app.buttons["block30"]
        XCTAssertTrue(block.waitForExistence(timeout: 10))
        settle()
        snap("watch-5-quick")
        block.tap()
        XCTAssertTrue(element(app, labelBeginsWith: "Blocked 30 min").waitForExistence(timeout: 20))
        snap("watch-5-quick-blocked")
    }

    /// Types into the system text input sheet (on a watch it opens with dictation; the simulator
    /// shows the keyboard) and submits it with Done.
    private func enterText(_ app: XCUIApplication, _ text: String, snapshot name: String) {
        let input = app.textViews.firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        snap("\(name)-input")
        input.typeText(text)
        settle(0.5)
        snap("\(name)-typed")
        let done = app.buttons["Done"]
        XCTAssertTrue(done.waitForExistence(timeout: 3))
        done.tap()
    }

    func test6_JournalNoteByDictation() throws {
        let app = try launch(page: "quick")
        let note = app.buttons["journalNote"]
        XCTAssertTrue(note.waitForExistence(timeout: 10))
        note.tap()
        enterText(app, "Walked the dog before standup", snapshot: "watch-6-journal-note")
        XCTAssertTrue(element(app, labelBeginsWith: "Added to journal").waitForExistence(timeout: 15))
        snap("watch-6-journal-note-added")
    }

    func test7_AskByDictation() throws {
        let app = try launch(page: "status")
        let ask = app.buttons["Ask"]
        XCTAssertTrue(ask.waitForExistence(timeout: 10))
        ask.tap()
        enterText(app, "Draft the release notes for 2.4", snapshot: "watch-7-ask")
        XCTAssertTrue(element(app, labelBeginsWith: "Started").waitForExistence(timeout: 20))
        settle(0.6)
        snap("watch-7-ask-started")
    }

    /// Every request goes through the iPhone (`-SamRabbitRoute phone`): the summary and an action.
    func test8_RelayThroughThePhone() throws {
        let app = try launch(page: "quick", route: "phone")
        let viaPhone = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "via iPhone"))
        XCTAssertTrue(viaPhone.firstMatch.waitForExistence(timeout: 15))
        settle()
        snap("watch-8-relay-quick")
        app.buttons["block30"].tap()
        XCTAssertTrue(element(app, labelBeginsWith: "Blocked 30 min").waitForExistence(timeout: 25))
        snap("watch-8-relay-blocked")
    }

    /// After the Mac revoked the watch's own token (the desktop app's device list), the watch
    /// offers Reconnect, which asks the iPhone for a new child token. Skips unless the token was
    /// revoked first (`DELETE /v1/mobile/devices/<watch id>` on the fake bridge).
    func test9_ReconnectAfterTheMacRevokedTheWatch() throws {
        let app = try launch(page: "status")
        let reconnect = app.buttons["Reconnect"]
        guard reconnect.waitForExistence(timeout: 12) else {
            throw XCTSkip("The watch's token is still accepted (revoke it on the fake bridge to run this).")
        }
        settle(0.5)
        snap("watch-9-revoked")
        reconnect.tap()
        XCTAssertTrue(app.buttons["Ask"].waitForExistence(timeout: 25))
        XCTAssertFalse(app.buttons["Reconnect"].exists)
        settle()
        snap("watch-9-reconnected")
    }
}
