import XCTest

/// Sets the Apple Watch Ultra simulator's Action Button to the "Ask SamRabbit" control in the
/// watch's own Settings, then presses the button (`XCUIDevice.press(.action)`) and checks that
/// SamRabbit opens. Opt-in, because it changes the watch's Action Button:
///
///     TEST_RUNNER_SAMRABBIT_ACTION_BUTTON=1 xcodebuild test -scheme SamRabbitWatch \
///       -destination 'platform=watchOS Simulator,name=Apple Watch Ultra 4 (49mm)' \
///       -only-testing:SamRabbitWatchUITests/WatchActionButtonTests
///
/// `=press` only presses (the button already set). Only an Ultra has the button. Install the watch
/// app with `simctl install` and give watchOS a moment to register its control before the first
/// press: right after `xcodebuild test` installs it, chronod may still call the widget extension
/// unknown and the control fails ("“Ask SamRabbit” failed"). Screenshots: `watch-ultra-…`.
@MainActor
final class WatchActionButtonTests: XCTestCase {
    private let settings = XCUIApplication(bundleIdentifier: "com.apple.NanoSettings")
    private let app = XCUIApplication()

    override func setUp() async throws {
        continueAfterFailure = false
    }

    func testActionButtonRunsTheAskControl() throws {
        let mode = ProcessInfo.processInfo.environment["SAMRABBIT_ACTION_BUTTON"] ?? ""
        guard mode == "1" || mode == "press" else {
            throw XCTSkip("Set TEST_RUNNER_SAMRABBIT_ACTION_BUTTON=1 (it changes the watch's Action Button), or =press.")
        }
        guard XCUIDevice.shared.hasHardwareButton(.action) else {
            throw XCTSkip("This watch has no Action Button (use Apple Watch Ultra).")
        }
        if mode == "1" { try assignTheControl() }
        app.terminate()
        XCUIDevice.shared.press(.home)
        wait(1.5)
        XCUIDevice.shared.press(.action)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 15), "the Action Button opens SamRabbit")
        wait(2)
        snap("watch-ultra-7-pressed")
    }

    /// Settings > Action Button > Action: Control, Control: SamRabbit > Ask SamRabbit.
    private func assignTheControl() throws {
        // The app must have run once for watchOS to know its control.
        app.launch()
        wait(2)

        settings.launch()
        XCTAssertTrue(tap(settings, "Action Button"), "Settings > Action Button")
        snap("watch-ultra-1-action-button-settings")
        let assigned = settings.staticTexts["Ask SamRabbit"]
        guard !assigned.waitForExistence(timeout: 2) else {
            snap("watch-ultra-5-assigned")
            return
        }
        // Action: Control (watchOS 27 shows "Choose Action" until an action is set).
        if !(settings.staticTexts["Action"].exists && settings.staticTexts["Control"].exists) {
            XCTAssertTrue(tap(settings, "Action", required: false) || tap(settings, "Choose Action"), "Action")
            XCTAssertTrue(tap(settings, "Control"), "Control")
            snap("watch-ultra-2-action-control")
            if settings.buttons["Back"].exists { settings.buttons["Back"].tap(); wait(1.2) }
        }
        // Control: the picker lists controls by app (the row says "Configure" until one is set). The
        // watchOS 27 simulator's picker may list only the system's own controls.
        XCTAssertTrue(tap(settings, "Control"), "the Control row")
        snap("watch-ultra-3-control-picker")
        XCTAssertTrue(tap(settings, "Ask SamRabbit", anyElement: true), "the SamRabbit control is offered")
        wait(1)
        if !assigned.exists, settings.buttons["Back"].exists { settings.buttons["Back"].tap(); wait(1.2) }
        XCTAssertTrue(assigned.waitForExistence(timeout: 5), "Action Button > Control: Ask SamRabbit")
        snap("watch-ultra-5-assigned")
    }

    // MARK: - Helpers

    /// A settings row showing `title` and `value` ("Action, Control").
    private func row(_ target: XCUIApplication, _ title: String, value: String) -> XCUIElement {
        target.cells.matching(NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", title, value)).firstMatch
    }

    /// Taps the first cell or button labelled `label` (or the text inside one), scrolling down to find it.
    @discardableResult
    private func tap(_ target: XCUIApplication, _ label: String, required: Bool = true, anyElement: Bool = false) -> Bool {
        let match = NSPredicate(format: "label == %@ OR label BEGINSWITH %@", label, label + ",")
        var candidates = [target.cells.matching(match).firstMatch, target.buttons.matching(match).firstMatch,
                          target.staticTexts.matching(NSPredicate(format: "label == %@", label)).firstMatch]
        if anyElement { candidates.append(target.descendants(matching: .any).matching(match).firstMatch) }
        for attempt in 0..<(required ? 12 : 3) {
            _ = candidates[0].waitForExistence(timeout: attempt == 0 ? 2 : 0.5)
            if let hit = candidates.first(where: { $0.exists && $0.isHittable && $0.frame.minY > 40 }) {
                hit.tap()
                wait(1.2)
                return true
            }
            XCUIDevice.shared.rotateDigitalCrown(delta: 0.25) // scrolls down the list in front
            wait(0.6)
        }
        if required {
            let tree = XCTAttachment(string: target.debugDescription)
            tree.name = "tree-\(label)"
            tree.lifetime = .keepAlways
            add(tree)
            snap("watch-ultra-missing-\(label)")
        }
        return false
    }

    private func wait(_ seconds: TimeInterval) { Thread.sleep(forTimeInterval: seconds) }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
