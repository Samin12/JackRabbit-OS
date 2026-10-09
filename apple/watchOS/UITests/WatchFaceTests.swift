import XCTest

/// Puts the SamRabbit complications on real watch faces in the simulator and screenshots them.
/// Opt-in, because it edits the watch's faces:
///
///     TEST_RUNNER_SAMRABBIT_FACES=setup xcodebuild test ... -only-testing:SamRabbitWatchUITests/WatchFaceTests
///         adds Infograph, Modular and Activity Digital with SamRabbit in their slots, then screenshots them
///     TEST_RUNNER_SAMRABBIT_FACES=show  ...   only screenshots faces that already show SamRabbit
///
/// Faces: Infograph (corner + circular), Modular (rectangular + circular), Activity Digital
/// (inline + circular). Screenshots are attached as `watch-face-…`.
@MainActor
final class WatchFaceTests: XCTestCase {
    private let carousel = XCUIApplication(bundleIdentifier: "com.apple.Carousel")

    private struct FaceSpec {
        var name: String
        var slots: [(slot: String, widget: String)]
    }

    private let faces = [
        FaceSpec(name: "Infograph", slots: [("Top Left complication", "Needs You"),
                                            ("Sub-dial Right complication", "Needs You")]),
        FaceSpec(name: "Modular", slots: [("Middle complication", "Next Up"),
                                          ("Top Left complication", "Needs You")]),
        FaceSpec(name: "Activity Digital", slots: [("Bottom complication", "Needs You"),
                                                   ("Top Left complication", "Needs You")]),
    ]

    func testComplicationsOnFaces() throws {
        let mode = ProcessInfo.processInfo.environment["SAMRABBIT_FACES"] ?? ""
        guard mode == "setup" || mode == "show" else {
            throw XCTSkip("Set TEST_RUNNER_SAMRABBIT_FACES=setup (or show) to edit the watch faces.")
        }
        // Launching the app refreshes the shared summary and reloads the complications.
        XCUIApplication().launch()
        wait(3)
        for face in faces {
            if mode == "setup" {
                openLibrary()
                addFace(face.name)
                toComplicationsPage()
                for (slot, widget) in face.slots { XCTAssertTrue(assign(slot, widget), "\(face.name): \(slot)") }
                XCUIDevice.shared.press(.home)
                wait(2)
            }
            XCTAssertTrue(show(face.name), "\(face.name) with SamRabbit")
            wait(2)
            snap("watch-face-\(face.name.lowercased().replacingOccurrences(of: " ", with: "-"))")
        }
    }

    // MARK: - Steps

    private func wait(_ seconds: TimeInterval) { Thread.sleep(forTimeInterval: seconds) }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private var faceTitle: String {
        let title = carousel.staticTexts["Switcher Face Title"]
        return title.exists ? title.label : ""
    }

    /// From anywhere to the face library (long press on the face).
    private func openLibrary() {
        XCUIApplication().activate()
        wait(1)
        XCUIDevice.shared.press(.home)
        wait(1.5)
        carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 2.0)
        wait(2.5)
    }

    /// Makes the first face called `name` that shows a SamRabbit complication the current face.
    private func show(_ name: String) -> Bool {
        openLibrary()
        for _ in 0..<14 { carousel.swipeRight(); wait(0.5) }
        for _ in 0..<16 {
            let title = faceTitle
            if title == "New" { return false }
            if title == name {
                carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                wait(2.5)
                let ours = carousel.descendants(matching: .any)
                    .matching(NSPredicate(format: "label CONTAINS[c] %@", "need you")).firstMatch
                if ours.waitForExistence(timeout: 3) { return true }
                // A copy of the face without SamRabbit: back to the library, keep looking.
                carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 2.0)
                wait(2.5)
            }
            nextFace()
        }
        return false
    }

    /// One face to the right in the library (a slow drag snaps exactly one page).
    private func nextFace() {
        let right = carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.78, dy: 0.45))
        let left = carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.30, dy: 0.45))
        right.press(forDuration: 0.05, thenDragTo: left, withVelocity: 250, thenHoldForDuration: 0.1)
        wait(1.3)
    }

    private func addFace(_ name: String) {
        for _ in 0..<16 where !carousel.scrollViews["Add new face"].exists { carousel.swipeLeft(); wait(0.8) }
        carousel.scrollViews["Add new face"].tap()
        wait(2.5)
        let all = carousel.cells["All Watch Faces"]
        XCTAssertTrue(scrollIntoView(all))
        all.tap()
        wait(2)
        let face = carousel.cells[name]
        XCTAssertTrue(scrollIntoView(face), name)
        face.buttons["Add"].firstMatch.tap()
        wait(4)
    }

    private func toComplicationsPage() {
        for _ in 0..<5 {
            carousel.swipeLeft()
            wait(1.2)
            if carousel.staticTexts["COMPLICATIONS"].exists { return }
        }
    }

    /// Picks SamRabbit's `widget` for a slot of the face being edited.
    private func assign(_ slot: String, _ widget: String) -> Bool {
        let button = carousel.buttons[slot]
        guard button.waitForExistence(timeout: 5) else { return false }
        button.tap()
        wait(2)
        // A slot that already shows something opens that app's own list first.
        if carousel.buttons["BackButton"].exists, !carousel.staticTexts["Featured"].exists {
            carousel.buttons["BackButton"].tap()
            wait(1.5)
        }
        let group = carousel.cells["AppGroupCell -- SamRabbit"]
        guard scrollIntoView(group) else {
            carousel.buttons["Close"].tap()
            wait(1.5)
            return false
        }
        group.tap()
        wait(1.5)
        let cell = carousel.cells["ComplicationListCell -- \(widget)"]
        guard cell.waitForExistence(timeout: 5) else { return false }
        cell.tap()
        wait(2)
        return true
    }

    /// Slow drags (no momentum) until the element is fully on screen and at rest.
    private func scrollIntoView(_ element: XCUIElement) -> Bool {
        for _ in 0..<40 {
            if let frame = try? element.snapshot().frame {
                if frame.minY >= 40, frame.maxY <= 240 {
                    wait(1.2)
                    if let again = try? element.snapshot().frame, again == frame { return true }
                    continue
                }
                if frame.minY < 40 {
                    drag(down: true)
                    continue
                }
            }
            drag(down: false)
        }
        return false
    }

    private func drag(down: Bool) {
        let top = carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.42))
        let bottom = carousel.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.72))
        let (from, to) = down ? (top, bottom) : (bottom, top)
        from.press(forDuration: 0.05, thenDragTo: to, withVelocity: 150, thenHoldForDuration: 0.3)
        wait(0.9)
    }
}
