import Foundation
import Observation
import os
import SamRabbitKit
import SwiftUI
import WatchKit

private let conversationLog = Logger(subsystem: "com.samrabbit.mobile.watchkitapp", category: "conversation")

/// The watch's always-on conversation with SamRabbit: listens, sends what was said to the Mac, plays the answer
/// as it streams in, and listens again. The rules are `ConversationMachine` (SamRabbitKit, unit-tested); this
/// carries out its effects with the audio graph (`AudioGraph`), the speech detector and the bridge client, and
/// feeds it back what happened.
///
/// * Turns stream (`application/x-samrabbit-stream`): PCM frames are played as they arrive, `say.delta` is the live
///   caption. An older bridge or the Claude fallback answers in one piece; a reply without audio is spoken by the
///   watch itself.
/// * Directly to the Mac, or through the iPhone (`TurnRelay`) when the Mac can't be reached directly.
/// * Announcements are polled about every 20 s while the conversation is open and played between turns.
/// * Nothing heard, said, or any audio is ever logged.
@MainActor
@Observable
final class ConversationEngine {
    /// What the conversation is doing (the page shows `machine.phase`, `machine.live`, `machine.exchanges`).
    private(set) var machine: ConversationMachine
    /// 0...1 for the orb: the microphone's level while listening, the reply's while speaking.
    private(set) var level: Double {
        get { access(keyPath: \.level); return storedLevel }
        set {
            // Only changes the orb can show (and none in UI tests: `-SamRabbitStillOrb`, so the app goes idle).
            guard !Self.stillOrb, abs(newValue - storedLevel) > 0.015 || (newValue == 0 && storedLevel != 0) else { return }
            withMutation(keyPath: \.level) { storedLevel = newValue }
        }
    }
    @ObservationIgnored private var storedLevel: Double = 0
    @ObservationIgnored private static let stillOrb = UserDefaults.standard.bool(forKey: "SamRabbitStillOrb")

    @ObservationIgnored private let account: BridgeAccount
    @ObservationIgnored private let link: PhoneLink
    @ObservationIgnored private let graph: AudioGraph
    @ObservationIgnored private var detector = SpeechDetector()
    @ObservationIgnored private var micOpen = false
    @ObservationIgnored private var turnTask: Task<Void, Never>?
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var tickTask: Task<Void, Never>?
    @ObservationIgnored private var levelTask: Task<Void, Never>?
    @ObservationIgnored private var cursor: String?
    /// Called when the Mac refused the watch's token (the status shows Reconnect).
    @ObservationIgnored var onUnauthorized: (@MainActor () -> Void)?
    /// Called after each turn (the route may have changed, a task may have started).
    @ObservationIgnored var onTurnFinished: (@MainActor () -> Void)?

    init(account: BridgeAccount, link: PhoneLink) {
        self.account = account
        self.link = link
        let idle = UserDefaults.standard.object(forKey: "SamRabbitIdleSeconds") == nil
            ? 120 : max(5, UserDefaults.standard.double(forKey: "SamRabbitIdleSeconds"))
        machine = ConversationMachine(idleTimeout: idle)
        let input = Self.makeInput()
        graph = AudioGraph(input: input, muted: Self.muted(input))
        graph.onSamples = { [weak self] in self?.heard($0) }
        graph.onPlaying = { [weak self] in self?.handle(.playing) }
        graph.onDrained = { [weak self] in self?.handle(.drained) }
        graph.onInterrupted = { [weak self] in self?.handle(.interrupted) }
    }

    /// The microphone; in the simulator never the Mac's own: a debug build's made-up voice
    /// (`-SamRabbitVoiceFixture`), or silence.
    static func makeInput() -> ConversationInput {
        #if DEBUG
        if let fixture = ConversationFixture.fromLaunchArguments() { return fixture }
        #endif
        #if targetEnvironment(simulator)
        return SilentInput()
        #else
        return MicrophoneInput()
        #endif
    }

    /// The simulator plays nothing out loud unless `-SamRabbitAudioOut on`.
    static func muted(_ input: ConversationInput) -> Bool {
        let setting = UserDefaults.standard.string(forKey: "SamRabbitAudioOut")
        #if targetEnvironment(simulator)
        return setting != "on"
        #else
        return setting == "mute"
        #endif
    }

    var phase: ConversationMachine.Phase { machine.phase }
    var active: Bool { machine.phase.inConversation }

    // MARK: - What the app asks for

    /// Opens a conversation (the app opened, the Action Button, Siri, a complication), or continues the one that
    /// was paused a moment ago. While one is open: the Action Button acts like a tap.
    func open() {
        if active { return }
        handle(.start)
    }

    /// The orb (or the Action Button while talking): talk, send now, or barge in.
    func tap() { handle(.tap) }

    func stop() { handle(.stop) }

    /// The wrist came up or the app came back to the front.
    func resume() { handle(.resume) }

    /// The app left the screen (the Digital Crown, another app): finish the reply, then pause.
    func leave() { handle(.background) }

    // MARK: - The machine

    private func handle(_ event: ConversationMachine.Event) {
        let before = machine.phase
        let effects = machine.handle(event)
        if machine.phase != before {
            conversationLog.info("\(before.rawValue, privacy: .public) -> \(self.machine.phase.rawValue, privacy: .public)")
        }
        for effect in effects { perform(effect) }
    }

    private func perform(_ effect: ConversationMachine.Effect) {
        switch effect {
        case .startAudio:
            Task {
                do {
                    try await graph.start()
                    startTimers()
                    handle(.audioReady)
                } catch let failure as AudioGraph.StartFailure {
                    handle(.audioFailed(failure.message))
                } catch {
                    handle(.audioFailed("The microphone didn't start"))
                }
            }
        case .stopAudio:
            micOpen = false
            graph.stop()
            stopTimers()
            level = 0
        case .warmUp(let conversationId):
            warmUp(conversationId)
        case .openMic:
            detector.reset(ignoring: 0.3) // the reply's echo
            micOpen = true
            graph.input.opened()
        case .closeMic:
            micOpen = false
            detector.reset()
        case .flushMic:
            for event in detector.flush() { detected(event) }
        case .send(let request):
            send(request)
        case .cancel(let turnId, let conversationId):
            // The machine ignores the old turn from now on. Tell the Mac first (it ends the reply cleanly,
            // `done{interrupted}`), then drop the stream; playback stopped already.
            let task = turnTask
            turnTask = nil
            if let conversationId, let client = account.client() {
                Task {
                    _ = try? await client.assistantCancel(conversationId: conversationId, timeout: 3)
                    task?.cancel()
                }
            } else {
                task?.cancel()
            }
            conversationLog.info("turn cancelled \(turnId.prefix(8), privacy: .public)")
        case .stopPlayback:
            graph.stopPlayback()
        case .speak(let text):
            graph.speak(text)
        case .play(let clip):
            if !graph.play(clip: clip) { handle(.drained) }
        case .haptic(let haptic):
            WKInterfaceDevice.current().play(Self.haptic(haptic))
        case .end(let conversationId):
            if let client = account.client() {
                Task { _ = try? await client.assistantEnd(conversationId: conversationId) }
            }
        case .announcements(let on):
            on ? startPolling() : stopPolling()
        }
    }

    private static func haptic(_ haptic: ConversationMachine.Haptic) -> WKHapticType {
        switch haptic {
        case .start: .start
        case .click: .click
        case .stop: .stop
        case .failure: .failure
        case .retry: .retry
        case .notification: .notification
        }
    }

    // MARK: - Listening

    private func heard(_ samples: [Int16]) {
        guard micOpen else { return }
        for event in detector.feed(samples) { detected(event) }
        if machine.phase == .listening || machine.phase == .hearing { level = detector.level }
    }

    private func detected(_ event: SpeechDetector.Event) {
        switch event {
        case .speechStarted: handle(.speechStarted)
        case .discarded: handle(.discarded)
        case .utterance(let samples):
            micOpen = false
            handle(.utterance(WAV.encode(samples: samples)))
        }
    }

    // MARK: - Turns

    private func warmUp(_ conversationId: String?) {
        guard let client = account.client() else {
            handle(.warmUpFailed(.unauthorized))
            return
        }
        Task {
            switch await BridgeError.capture({ try await client.assistantSession(conversationId: conversationId) }) {
            case .success(let session):
                handle(.session(session))
            case .failure(let error):
                // The warm-up is optional: a bridge without it (404, the Claude fallback's first build) is fine.
                if case .server(404, _, _, _) = error { return }
                if let failure = Self.failure(error) { handle(.warmUpFailed(failure)) }
            }
        }
    }

    private func send(_ request: ConversationMachine.TurnRequest) {
        turnTask?.cancel()
        let id = request.turnId
        guard let client = account.client() else {
            handle(.turn(id, .failed(.unauthorized)))
            return
        }
        let turn = AssistantTurnRequest(turnId: id, conversationId: request.conversationId, input: request.input,
                                        language: VoiceFormat.languageTag(), deviceTime: .now, stream: true)
        level = 0
        turnTask = Task { [weak self] in
            do {
                for try await item in client.assistantTurn(turn) {
                    if Task.isCancelled { return }
                    self?.receive(item, turn: id)
                }
            } catch {
                guard !Task.isCancelled, let self else { return }
                if let failure = Self.failure(error) { self.handle(.turn(id, .failed(failure))) }
            }
            self?.onTurnFinished?()
        }
    }

    private func receive(_ item: AssistantStreamItem, turn id: String) {
        guard machine.currentTurnId == id else { return }
        switch item {
        case .audio(let pcm):
            graph.schedule(pcm: pcm)
        case .clip(let clip):
            graph.play(clip: clip)
        case .event(let event):
            switch event {
            case .heard(let text): handle(.turn(id, .heard(text)))
            case .sayDelta(let text): handle(.turn(id, .sayDelta(text)))
            case .sayDone(let text): handle(.turn(id, .sayDone(text)))
            case .action(let action): handle(.turn(id, .action(action)))
            case .card(let card): handle(.turn(id, .card(card)))
            case .done(let done): handle(.turn(id, .done(done)))
            case .error(let code, let message): handle(.turn(id, .failed(.failed(code: code, message: message))))
            case .unknown: break
            }
        }
    }

    /// What a failure means to the conversation (nil: cancelled, nothing to say).
    static func failure(_ error: Error) -> ConversationMachine.Failure? {
        switch error {
        case let error as BridgeError:
            switch error {
            case .cancelled: return nil
            case .unreachable: return .unreachable
            case .unauthorized, .notPaired: return .unauthorized
            case .invalidResponse: return .failed(code: "invalid_response", message: "")
            case .server(let status, let code, let message, _):
                switch code {
                case "assistant_unavailable", "assistant_missing", "transcribe_unavailable", "transcribe_permission":
                    return .unavailable(code)
                case "assistant_busy": return .busy
                default: return status == 404 && code != "conversation_not_found" && code != "announcement_not_found"
                    ? .outdated : .failed(code: code, message: message)
                }
            }
        case is CancellationError:
            return nil
        case AssistantStreamError.truncated:
            return .failed(code: "stream_truncated", message: "")
        default:
            return .failed(code: "failed", message: "")
        }
    }

    // MARK: - Announcements and the clock

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            while !Task.isCancelled {
                await self?.poll()
                try? await Task.sleep(for: .seconds(20))
            }
        }
    }

    private func stopPolling() {
        pollTask?.cancel()
        pollTask = nil
    }

    private func poll() async {
        guard active, let conversationId = machine.conversationId, let client = account.client() else { return }
        guard let page = try? await client.assistantAnnouncements(conversationId: conversationId, since: cursor) else { return }
        if let next = page.cursor { cursor = next }
        if !page.items.isEmpty { handle(.announcements(page.items)) }
    }

    private func startTimers() {
        tickTask?.cancel()
        tickTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                self?.handle(.tick)
            }
        }
        levelTask?.cancel()
        levelTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(66))
                guard let self else { return }
                switch self.machine.phase {
                case .speaking: self.level = self.level * 0.4 + self.graph.playbackLevel() * 0.6
                case .thinking, .idle, .starting: if self.level > 0.001 { self.level *= 0.6 }
                case .listening, .hearing: break
                }
            }
        }
    }

    private func stopTimers() {
        tickTask?.cancel()
        tickTask = nil
        levelTask?.cancel()
        levelTask = nil
        cursor = nil
    }
}
