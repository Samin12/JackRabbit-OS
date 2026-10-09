import XCTest

/// The watch's live conversation with SamRabbit, driven in the simulator against the fake bridge's assistant
/// (`/v1/mobile/assistant/*`, the streaming turn protocol).
///
/// The debug build's made-up voice (`-SamRabbitVoiceFixture speech`, `ConversationFixture`) stands in for the
/// microphone: after the microphone opens it "says" something for 1.6 s; everything after it is real (the speech
/// detector, the 16 kHz WAV, the streamed turn, the PCM frames scheduled on the player, the captions, barge-in,
/// announcements). The simulator plays nothing out loud (`AudioGraph` is muted there).
///
/// Needs `apple/dev/fake_bridge.py` on 127.0.0.1:3799, the iPhone simulator paired with it and this watch paired
/// with that iPhone (skips otherwise). Screenshots are attached as `watch-…` (exported to
/// ~/Movies/SamRabbit-tests/wave5). Synchronous on purpose (see `WatchVoiceTests`).
@MainActor
final class WatchConversationTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
        fake("assistant", ["mode": "ok", "delay": 0.35, "pace": 0.5, "clear": true])
    }

    override func tearDownWithError() throws {
        fake("assistant", ["mode": "ok", "delay": 0.35, "pace": 0.5])
    }

    // MARK: - Helpers

    private func launch(turns: Int = 1, lead: Double = 1.2, voice: String = "speech", idle: Int? = nil,
                        page: String? = nil, route: String? = nil, intent: String? = nil) throws -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-SamRabbitVoiceFixture", voice, "-SamRabbitFixtureTurns", String(turns),
                               "-SamRabbitFixtureLead", String(lead), "-SamRabbitStillOrb", "YES"]
        if let idle { app.launchArguments += ["-SamRabbitIdleSeconds", String(idle)] }
        if let page { app.launchArguments += ["-SamRabbitPage", page] }
        if let route { app.launchArguments += ["-SamRabbitRoute", route] }
        if let intent { app.launchArguments += ["-SamRabbitIntent", intent] }
        app.launch()
        if app.staticTexts["Pair on your iPhone"].waitForExistence(timeout: 3) {
            throw XCTSkip("The watch isn't paired (pair the iPhone simulator with the fake bridge first).")
        }
        return app
    }

    @discardableResult
    nonisolated private func fake(_ name: String, _ body: [String: Any] = [:]) -> [String: Any] {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:3799/__fake/\(name)")!)
        request.httpMethod = "POST"
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return exchange(request)
    }

    nonisolated private func fakeGet(_ name: String) -> [String: Any] {
        exchange(URLRequest(url: URL(string: "http://127.0.0.1:3799/__fake/\(name)")!))
    }

    nonisolated private func exchange(_ request: URLRequest) -> [String: Any] {
        nonisolated(unsafe) var answer: [String: Any] = [:]
        let done = DispatchSemaphore(value: 0)
        URLSession.shared.dataTask(with: request) { data, _, _ in
            answer = data.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] } ?? [:]
            done.signal()
        }.resume()
        _ = done.wait(timeout: .now() + 10)
        return answer
    }

    private var assistant: [String: Any] { fakeGet("assistant") }
    private var turns: [[String: Any]] { assistant["turns"] as? [[String: Any]] ?? [] }

    private func state(_ app: XCUIApplication) -> XCUIElement { app.staticTexts["assistant-state"] }

    /// Waits until the state line says one of `texts` (polling: the label changes in place; each query is slow
    /// while the orb animates, so short states are given alternatives). Returns the one it saw.
    @discardableResult
    private func waitFor(_ app: XCUIApplication, state texts: String..., timeout: TimeInterval = 15,
                         file: StaticString = #filePath, line: UInt = #line) -> String? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if state(app).exists {
                let label = state(app).label
                if let found = texts.first(where: { label.hasPrefix($0) }) { return found }
            }
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTFail("the state never said “\(texts.joined(separator: "” or “"))” (it says “\(state(app).exists ? state(app).label : "-")”)",
                file: file, line: line)
        return nil
    }

    private func label(_ app: XCUIApplication, contains text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    private func snap(_ name: String) {
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    private func pause(_ seconds: TimeInterval) { Thread.sleep(forTimeInterval: seconds) }

    // MARK: - Tests

    /// Opened normally, the app goes straight into a conversation: listening, hearing, thinking, speaking (the
    /// streamed reply with its live caption), then listening again by itself.
    func test1_OpensIntoALiveConversation() throws {
        fake("assistant", ["pace": 1.0])
        let app = try launch(turns: 1, lead: 6) // the launch checks take a few seconds
        waitFor(app, state: "Listening")
        snap("watch-1-listening")
        if waitFor(app, state: "Hearing you", "Thinking", "Speaking") == "Hearing you" { snap("watch-2-hearing") }
        waitFor(app, state: "Speaking", timeout: 20)
        XCTAssertTrue(app.staticTexts["assistant-heard"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["assistant-heard"].label, "Draft the release notes for build 2.4")
        XCTAssertTrue(app.staticTexts["assistant-say"].waitForExistence(timeout: 5))
        pause(1.2)
        snap("watch-3-speaking")
        XCTAssertTrue(app.staticTexts["assistant-say"].label.hasPrefix("Okay, I started"))
        waitFor(app, state: "Listening", timeout: 20)
        XCTAssertEqual(app.staticTexts["assistant-say"].label,
                       "Okay, I started the release notes for build 2.4 in Hermes. Anything else?")
        XCTAssertTrue(label(app, contains: "Release notes for build 2.4").exists, "the task it started")
        pause(0.5)
        snap("watch-4-listening-again")
        // As the bridge saw it: a warm-up, then one streamed utterance from the watch (16 kHz WAV).
        let sessions = assistant["sessions"] as? [[String: Any]] ?? []
        XCTAssertEqual(sessions.count, 1)
        let turn = try XCTUnwrap(turns.last)
        XCTAssertEqual(turn["kind"] as? String, "audio")
        XCTAssertEqual(turn["contentType"] as? String, "audio/wav")
        XCTAssertEqual(turn["stream"] as? Bool, true)
        XCTAssertEqual(turn["platform"] as? String, "watchos")
        XCTAssertEqual(turn["deviceTime"] as? Bool, true)
        XCTAssertEqual(turn["conversationId"] as? String, sessions.first?["conversationId"] as? String)
        let seconds = try XCTUnwrap(turn["seconds"] as? Double)
        XCTAssertTrue(seconds > 1.5 && seconds < 2.6, "1.6 s of voice with its pre-roll and tail: \(seconds)")
    }

    /// Tap the orb while it speaks: the reply stops (the Mac is told to cancel), and it listens.
    func test2_TapToInterrupt() throws {
        fake("assistant", ["pace": 1.0])
        let app = try launch(turns: 1, lead: 1.0)
        waitFor(app, state: "Speaking", timeout: 20)
        pause(0.8)
        app.buttons["assistant-orb"].tap()
        waitFor(app, state: "Listening", timeout: 5)
        pause(0.4)
        snap("watch-5-interrupted")
        let cancels = assistant["cancels"] as? [[String: Any]] ?? []
        XCTAssertEqual(cancels.last?["cancelled"] as? Bool, true, "the Mac stopped the reply")
        pause(1.5)
        XCTAssertEqual(turns.last?["interrupted"] as? Bool, true)
    }

    /// A reply without audio (the voice failed on the Mac): the watch says it with its own voice.
    func test3_TheWatchSpeaksWhenTheReplyHasNoAudio() throws {
        fake("assistant", ["mode": "noaudio", "say": "It's sunny and 21 degrees."])
        let app = try launch(turns: 1)
        waitFor(app, state: "Speaking", timeout: 20)
        XCTAssertEqual(app.staticTexts["assistant-say"].label, "It's sunny and 21 degrees.")
        snap("watch-6-local-voice")
        waitFor(app, state: "Listening", timeout: 15)
        fake("assistant", ["say": "Okay, I started the release notes for build 2.4 in Hermes. Anything else?"])
    }

    /// The Mac can't be reached: the watch says so out loud, then the conversation ends.
    func test4_CantReachTheMac() throws {
        fake("assistant", ["mode": "drop"])
        let app = try launch(turns: 1)
        waitFor(app, state: "Can't reach your Mac", timeout: 40)
        XCTAssertTrue(app.buttons["assistant-talk"].exists, "Talk to try again")
        pause(0.4)
        snap("watch-7-cant-reach-mac")
    }

    /// While a conversation is open, announcements (T3 events) are polled and spoken between turns.
    func test5_AnnouncementsBetweenTurns() throws {
        let app = try launch(turns: 0)
        waitFor(app, state: "Listening")
        fake("announce", ["say": "“Fix login redirect” in Hermes finished.", "kind": "done"])
        waitFor(app, state: "Heads up", timeout: 30)
        XCTAssertEqual(app.staticTexts["assistant-say"].label, "“Fix login redirect” in Hermes finished.")
        pause(0.8)
        snap("watch-8-announcement")
        waitFor(app, state: "Listening", timeout: 15)
        XCTAssertEqual(turns.last?["kind"] as? String, "announce", "said in the conversation's voice")
    }

    /// No speech for a while (`-SamRabbitIdleSeconds 6`; about 2 minutes normally): the conversation ends.
    func test6_EndsAfterAQuietWhile() throws {
        let app = try launch(turns: 0, idle: 6)
        waitFor(app, state: "Listening")
        waitFor(app, state: "Tap to talk", timeout: 15)
        XCTAssertTrue(app.buttons["assistant-talk"].exists)
        pause(0.5)
        snap("watch-9-ended-quiet")
        let ends = assistant["ends"] as? [[String: Any]] ?? []
        XCTAssertEqual(ends.last?["ended"] as? Bool, true, "the Mac was told")
        // Talk starts a new one.
        app.buttons["assistant-talk"].tap()
        waitFor(app, state: "Listening")
    }

    /// The Mac ends the conversation ("thanks, that's all").
    func test7_TheMacEndsIt() throws {
        fake("assistant", ["mode": "end"])
        let app = try launch(turns: 1)
        waitFor(app, state: "Speaking", timeout: 20)
        waitFor(app, state: "Tap to talk", timeout: 15)
        XCTAssertEqual(app.staticTexts["assistant-say"].label, "Okay. Talk soon.")
        snap("watch-10-ended-by-the-mac")
    }

    /// The Claude fallback (or an older bridge) answers in one piece with an encoded clip: played the same way.
    func test8_TheFallbackAnswersInOnePiece() throws {
        fake("assistant", ["mode": "buffered_clip"])
        let app = try launch(turns: 1)
        waitFor(app, state: "Speaking", timeout: 25)
        snap("watch-11-buffered-clip")
        waitFor(app, state: "Listening", timeout: 15)
        XCTAssertEqual(turns.last?["stream"] as? Bool, false)
    }

    /// The Action Button's control and Siri ("Ask SamRabbit") run `OpenSamRabbitWatchIntent`: the app opens on the
    /// conversation, listening (here from the Quick page; the simulator has no Action Button to press).
    func test9_ActionButtonOpensTheConversation() throws {
        let app = try launch(turns: 0, page: "quick", intent: "ask")
        waitFor(app, state: "Listening", timeout: 12)
        XCTAssertTrue(app.buttons["assistant-orb"].exists, "on the main page")
        snap("watch-12-action-button")
    }

    /// Through the iPhone (`-SamRabbitRoute phone`): the utterance goes up in chunks, the phone sends the turn with
    /// the watch's own token and hands the stream back piece by piece.
    func test10_ThroughTheIPhone() throws {
        let app = try launch(turns: 1, route: "phone")
        waitFor(app, state: "Speaking", timeout: 40)
        waitFor(app, state: "Listening", timeout: 25)
        XCTAssertTrue(app.staticTexts["assistant-say"].label.hasPrefix("Okay, I started"))
        snap("watch-13-through-the-iphone")
        let turn = try XCTUnwrap(turns.last)
        XCTAssertEqual(turn["platform"] as? String, "watchos", "the watch's own token, not the phone's")
        XCTAssertNotNil(turn["parentId"] as? String)
        XCTAssertEqual(turn["stream"] as? Bool, true)
    }

    /// Stop ends it at once and tells the Mac.
    func test11_Stop() throws {
        let app = try launch(turns: 0)
        waitFor(app, state: "Listening")
        let stops = app.buttons.matching(identifier: "assistant-stop")
        XCTAssertTrue(stops.firstMatch.waitForExistence(timeout: 5))
        snap("watch-14-stop-button")
        let stop = (0..<stops.count).map { stops.element(boundBy: $0) }.first { $0.isHittable } ?? stops.firstMatch
        stop.tap()
        waitFor(app, state: "Tap to talk", timeout: 5)
        let ends = assistant["ends"] as? [[String: Any]] ?? []
        XCTAssertEqual(ends.last?["ended"] as? Bool, true)
    }
}
