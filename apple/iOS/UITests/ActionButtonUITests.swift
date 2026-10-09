import XCTest

/// Presses the simulator's Action Button (`XCUIDevice.press(.action)`) and checks that SamRabbit
/// opens at Ask and starts dictation by itself, then sends a task to the fake bridge. (The Simulator
/// has no speech recognizer: dictation starts, then stops with "stopped before it heard anything".)
///
/// Opt-in, because it needs the simulator's Action Button set to the SamRabbit control first
/// (Settings > Action Button > Controls > Choose a Control… > SamRabbit > Ask SamRabbit) and it
/// turns the microphone on for a moment:
///
///     TEST_RUNNER_SAMRABBIT_ACTION_BUTTON=1 xcodebuild test -scheme SamRabbit \
///       -destination 'platform=iOS Simulator,name=iPhone 18 Pro' -only-testing:SamRabbitUITests
///
/// Needs `apple/dev/fake_bridge.py` on 127.0.0.1:3799 with the simulator paired to it. Screenshots
/// are attached as `ios-action-button-…`.
@MainActor
final class ActionButtonUITests: XCTestCase {
    private let app = XCUIApplication(bundleIdentifier: "com.samrabbit.mobile")
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")

    override func setUp() async throws {
        continueAfterFailure = false
    }

    func testActionButtonOpensAskListening() throws {
        guard ProcessInfo.processInfo.environment["SAMRABBIT_ACTION_BUTTON"] == "1" else {
            throw XCTSkip("Set TEST_RUNNER_SAMRABBIT_ACTION_BUTTON=1 after setting the simulator's Action Button.")
        }
        guard XCUIDevice.shared.hasHardwareButton(.action) else { throw XCTSkip("This simulator has no Action Button.") }

        // From the Home Screen, with SamRabbit not running: the cold start.
        app.terminate()
        XCUIDevice.shared.press(.home)
        wait(1.5)
        XCUIDevice.shared.press(.action)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "the Action Button opens SamRabbit")

        // The first time, iOS asks for speech recognition and the microphone: dictation already started.
        allowPermissionAlerts(timeout: 8)
        XCTAssertTrue(app.staticTexts["Ask SamRabbit"].waitForExistence(timeout: 10), "the Ask sheet")
        XCTAssertTrue(dictationStarted(timeout: 10), "dictation starts without a tap")
        snap("ios-action-button-1-ask-dictation")

        // Type instead (the simulator's microphone hears the room: clear whatever it caught) and start the task.
        stopDictation()
        let field = app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
        field.tap()
        let heard = (field.value as? String) ?? ""
        if !heard.isEmpty, heard != "What should SamRabbit do?" {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: heard.count + 2))
        }
        field.typeText("Draft the release notes for 2.4")
        app.buttons["Start"].tap()
        XCTAssertTrue(app.staticTexts["Task started"].waitForExistence(timeout: 15))
        snap("ios-action-button-2-task-started")

        // Pressed again while SamRabbit is in front: the intent runs in the app, and Ask opens listening.
        wait(3)
        XCUIDevice.shared.press(.action)
        XCTAssertTrue(app.staticTexts["Ask SamRabbit"].waitForExistence(timeout: 10), "Ask opens while the app is open too")
        XCTAssertTrue(dictationStarted(timeout: 10), "and dictation starts again")
        snap("ios-action-button-3-pressed-in-app")
        stopDictation()
        if app.buttons["Close"].exists { app.buttons["Close"].tap() }
    }

    /// Dictation started by itself: it is listening, or (in the Simulator, which has no speech
    /// recognizer) it started and stopped with "Dictation stopped before it heard anything".
    private func dictationStarted(timeout: TimeInterval) -> Bool {
        let listening = app.staticTexts["Listening… say what to do, then tap Start."]
        let stopped = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Dictation stopped before")).firstMatch
        let deadline = Date.now.addingTimeInterval(timeout)
        while Date.now < deadline {
            if listening.exists || app.buttons["Stop dictation"].exists || stopped.exists { return true }
            wait(0.2)
        }
        return false
    }

    /// Stops dictation, or closes the note that it stopped by itself.
    private func stopDictation() {
        if app.buttons["Stop dictation"].exists { app.buttons["Stop dictation"].tap() }
        let stopped = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Dictation stopped before")).firstMatch
        if stopped.waitForExistence(timeout: 2) {
            app.staticTexts["Ask SamRabbit"].tap() // outside the popover
            wait(0.6)
        }
    }

    // MARK: - Helpers

    /// Taps Allow / OK on the system's permission alerts (shown over the app, or by SpringBoard)
    /// while they show up.
    private func allowPermissionAlerts(timeout: TimeInterval) {
        let deadline = Date.now.addingTimeInterval(timeout)
        while Date.now < deadline {
            let alert = [app.alerts.firstMatch, springboard.alerts.firstMatch].first { $0.waitForExistence(timeout: 0.5) }
            guard let alert else { continue }
            if let title = ["Allow", "OK", "Allow While Using App"].first(where: { alert.buttons[$0].exists }) {
                snap("ios-action-button-0-permission")
                alert.buttons[title].tap()
            }
            wait(0.8)
        }
    }

    private func wait(_ seconds: TimeInterval) { Thread.sleep(forTimeInterval: seconds) }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
