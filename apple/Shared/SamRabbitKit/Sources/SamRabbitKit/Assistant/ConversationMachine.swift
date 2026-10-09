import Foundation

/// The watch's always-on conversation with SamRabbit, as a pure state machine: the watch app feeds it what
/// happened (the microphone heard speech, a turn's stream said something, playback ran dry, a tap) and carries
/// out the effects it returns (open the microphone, send a turn, play, speak, cancel, end). No audio, network or
/// clock of its own, so every rule below is unit-tested.
///
///     idle -> starting -> listening -> hearing -> thinking -> speaking -> listening -> ...
///
/// * A conversation starts from the app's launch, the Action Button, Siri, a complication or a tap on the orb;
///   the Mac is warmed up at once (`warmUp`).
/// * Half duplex: the microphone is closed while a turn is on its way and while anything plays (no echo), and
///   reopened after (auto-continue).
/// * Barge-in: a tap while thinking or speaking cancels the turn on the Mac (`cancel`), stops playback and listens.
/// * A reply without audio is spoken by the watch itself (`speak`); a Mac that can't be reached is said out loud
///   ("I can't reach your Mac right now"), then the conversation ends.
/// * Announcements (T3 tasks that finished or need Samin) wait until the conversation is listening, then play:
///   their own clip if they have one, else as a `{"announce": id}` turn in the conversation's voice.
/// * It ends on Stop, when the Mac says so (`endConversation`), after `idleTimeout` (about 2 minutes) without
///   speech, or after the current reply when the app leaves the screen.
public struct ConversationMachine: Sendable {
    public enum Phase: String, Sendable, Equatable {
        /// No conversation.
        case idle
        /// The audio session and engine are coming up.
        case starting
        /// The microphone is open, waiting for speech.
        case listening
        /// Someone is speaking.
        case hearing
        /// A turn is on its way; nothing plays yet.
        case thinking
        /// A reply, an announcement or the watch's own voice is playing.
        case speaking

        public var inConversation: Bool { self != .idle }
    }

    public enum Haptic: String, Sendable, Equatable {
        case start, click, stop, failure, retry, notification
    }

    /// What a turn sends.
    public enum Input: Sendable, Equatable {
        /// An utterance: 16 kHz mono 16-bit WAV.
        case audio(Data)
        case text(String)
        /// Speak this announcement (`{"announce": id}`).
        case announce(String)
    }

    public struct TurnRequest: Sendable, Equatable {
        public var turnId: String
        public var conversationId: String?
        public var input: Input
    }

    /// What the watch app does for the machine.
    public enum Effect: Sendable, Equatable {
        /// Set up the audio session and the engine (microphone tap and player).
        case startAudio
        case stopAudio
        /// `POST /v1/mobile/assistant/session` (optional; the first turn starts the conversation otherwise).
        case warmUp(conversationId: String?)
        /// Feed the speech detector again, from scratch (after a reply: skip its echo).
        case openMic
        /// Stop feeding the speech detector (half duplex).
        case closeMic
        /// End the utterance in progress now (a tap while speaking).
        case flushMic
        case send(TurnRequest)
        /// `POST /v1/mobile/assistant/cancel` and drop the turn's stream.
        case cancel(turnId: String, conversationId: String?)
        case stopPlayback
        /// Say this with the watch's own voice.
        case speak(String)
        /// Play an announcement's own clip.
        case play(AssistantAudio)
        case haptic(Haptic)
        /// `POST /v1/mobile/assistant/end`
        case end(conversationId: String)
        /// Poll announcements (about every 20 s) while true.
        case announcements(Bool)
    }

    /// Why a turn (or the warm-up) failed.
    public enum Failure: Sendable, Equatable {
        /// Neither the Mac nor the iPhone could be reached.
        case unreachable
        /// The Mac refused the watch's token.
        case unauthorized
        /// The Mac can't run the assistant now (`assistant_unavailable`, with its reason).
        case unavailable(String?)
        /// The Mac's bridge has no assistant yet (404).
        case outdated
        /// Still answering the last turn (409 after retries).
        case busy
        /// Anything else (a timeout, an error event in the stream).
        case failed(code: String, message: String)

        /// After these the conversation ends.
        public var isFatal: Bool {
            switch self {
            case .unreachable, .unauthorized, .unavailable, .outdated: true
            case .busy, .failed: false
            }
        }

        /// What the watch says.
        public var spoken: String {
            switch self {
            case .unreachable: "I can't reach your Mac right now."
            case .unauthorized: "Your watch needs to reconnect. Open SamRabbit on your iPhone."
            case .unavailable: "The assistant isn't available on your Mac right now."
            case .outdated: "Update SamRabbit on your Mac to talk with me."
            case .busy: "One moment, I'm still on the last thing."
            case .failed: "Sorry, that didn't work. Try again."
            }
        }

        /// Shown under the orb once the conversation ended because of it.
        public var shown: String {
            switch self {
            case .unreachable: "Can't reach your Mac"
            case .unauthorized: "Reconnect through your iPhone"
            case .unavailable: "Assistant unavailable on the Mac"
            case .outdated: "Update SamRabbit on your Mac"
            case .busy: "Still answering"
            case .failed: "That didn't work"
            }
        }
    }

    /// What one turn's stream produced.
    public enum TurnEvent: Sendable, Equatable {
        case heard(String)
        case sayDelta(String)
        case sayDone(String)
        case action(AssistantAction)
        case card(AssistantCard)
        case done(AssistantDone)
        case failed(Failure)
    }

    public enum Event: Sendable, Equatable {
        /// Open a conversation (launch, Action Button, Siri, a complication, a tap on the idle orb).
        case start
        /// The audio engine runs.
        case audioReady
        /// The microphone or the engine couldn't start (the message is shown).
        case audioFailed(String)
        /// The warm-up answered.
        case session(AssistantSession)
        /// The warm-up failed (only a Mac out of reach, a refused watch or a missing assistant matter).
        case warmUpFailed(Failure)
        case speechStarted
        /// A finished utterance (WAV).
        case utterance(Data)
        /// Speech too short to send.
        case discarded
        case turn(String, TurnEvent)
        /// Something started playing (a reply's first frame, again after a gap, a clip, the watch's voice).
        case playing
        /// Everything scheduled has played.
        case drained
        /// The orb (or the Action Button again).
        case tap
        /// The Stop button.
        case stop
        /// About once a second while a conversation is open.
        case tick
        case announcements([Announcement])
        /// A call or Siri took the audio session.
        case interrupted
        /// The app left the screen (the Digital Crown): finish what plays, then end.
        case background
        /// The app is in front again (wrist raised, reopened).
        case resume
    }

    /// One exchange, for the captions and the scrollable history.
    public struct Exchange: Sendable, Equatable, Identifiable {
        public enum Kind: String, Sendable, Equatable { case utterance, announcement, notice }
        public var id: String
        public var kind: Kind
        public var heard: String = ""
        public var say: String = ""
        public var actions: [AssistantAction] = []
        public var cards: [AssistantCard] = []
        public var interrupted = false
        public var failed = false
    }

    private struct Turn: Sendable {
        enum Kind: Sendable, Equatable {
            /// A turn on the Mac (an utterance or an announcement).
            case bridge
            /// An announcement's own clip.
            case clip
            /// The watch's own voice (a failure, an announcement whose turn failed).
            case local
        }
        var id: String
        var kind: Kind
        var announcement: Announcement?
        /// Nothing more will be scheduled: running dry finishes the turn.
        var complete = false
        var gotAudio = false
        var done: AssistantDone?
        var say = ""
    }

    public private(set) var phase: Phase = .idle
    public private(set) var conversationId: String?
    public private(set) var brain: AssistantBrain = .unknown
    /// Why the last conversation ended badly (shown while idle), or nil.
    public private(set) var problem: String?
    public private(set) var exchanges: [Exchange] = []
    /// The last speech, reply or start: `idleTimeout` counts from here.
    public private(set) var lastActivity: Date = .distantPast
    public private(set) var expectReply = false
    public var idleTimeout: TimeInterval
    /// A conversation that stopped because the app left (or the audio was taken) is resumed within this long.
    public var resumeWindow: TimeInterval = 180
    public static let maxExchanges = 12

    private var current: Turn?
    private var queue: [Announcement] = []
    private var announced: Set<String> = []
    private var playing = false
    private var endAfterPlayback = false
    private var reachable = true
    /// The conversation stopped without being ended on purpose (background, interruption): `resume` continues it.
    private var resumable = false
    private var endedAt: Date?
    private let makeId: @Sendable () -> String

    public init(idleTimeout: TimeInterval = 120, makeId: @escaping @Sendable () -> String = { UUID().uuidString }) {
        self.idleTimeout = idleTimeout
        self.makeId = makeId
    }

    /// The turn whose stream the app should read (events of other turns are ignored).
    public var currentTurnId: String? { current?.kind == .bridge ? current?.id : nil }

    /// The exchange being heard or answered right now.
    public var live: Exchange? { current.flatMap { turn in exchanges.last { $0.id == turn.id } } }

    /// Announcements waiting for a pause.
    public var waitingAnnouncements: Int { queue.count }

    // MARK: - Events

    public mutating func handle(_ event: Event, at now: Date = .now) -> [Effect] {
        switch event {
        case .start:
            return start(at: now)
        case .audioReady:
            guard phase == .starting else { return [] }
            phase = .listening
            lastActivity = now
            return [.openMic, .haptic(.start), .announcements(true)]
        case .audioFailed(let message):
            guard phase.inConversation else { return [] }
            return finish(at: now, problem: message, haptic: .failure, tellMac: false)
        case .session(let session):
            if !session.conversationId.isEmpty, conversationId == nil { conversationId = session.conversationId }
            if session.brain != .unknown { brain = session.brain }
            return []
        case .warmUpFailed(let failure):
            // Only matters while nobody spoke yet; a turn reports its own failure.
            guard failure.isFatal, current == nil, phase == .listening || phase == .starting else { return [] }
            if phase == .starting { phase = .listening }
            return fail(failure, at: now)
        case .speechStarted:
            guard phase == .listening else { return [] }
            phase = .hearing
            lastActivity = now
            return []
        case .discarded:
            if phase == .hearing { phase = .listening }
            return []
        case .utterance(let wav):
            guard phase == .listening || phase == .hearing else { return [] }
            lastActivity = now
            return send(.audio(wav), kind: .utterance)
        case .turn(let id, let turnEvent):
            guard current?.id == id, current?.kind == .bridge else { return [] }
            return turn(turnEvent, at: now)
        case .playing:
            playing = true
            guard current != nil else { return [] }
            current?.gotAudio = true
            if phase == .thinking { phase = .speaking }
            return []
        case .drained:
            playing = false
            guard let current, phase == .speaking || phase == .thinking else { return [] }
            return current.complete ? finishTurn(at: now) : []
        case .tap:
            return tap(at: now)
        case .stop:
            guard phase.inConversation else { return [] }
            return finish(at: now, problem: nil, haptic: .stop, tellMac: true)
        case .tick:
            guard phase == .listening, now.timeIntervalSince(lastActivity) >= idleTimeout else { return [] }
            return finish(at: now, problem: nil, haptic: .stop, tellMac: true)
        case .announcements(let items):
            guard phase.inConversation else { return [] }
            for item in items where !announced.contains(item.id) {
                announced.insert(item.id)
                queue.append(item)
            }
            guard phase == .listening, current == nil, !queue.isEmpty else { return [] }
            return announce(queue.removeFirst())
        case .interrupted:
            guard phase.inConversation else { return [] }
            let effects = finish(at: now, problem: "Paused. Tap to talk.", haptic: .retry, tellMac: false)
            resumable = true
            return effects
        case .background:
            switch phase {
            case .idle: return []
            case .thinking, .speaking:
                endAfterPlayback = true
                resumable = true
                return []
            case .starting, .listening, .hearing:
                let effects = finish(at: now, problem: nil, haptic: nil, tellMac: false)
                resumable = true
                return effects
            }
        case .resume:
            guard phase == .idle, canContinue(at: now) else { return [] }
            return start(at: now)
        }
    }

    // MARK: - Conversation

    /// A conversation that stopped because the app left or the audio was taken, recently enough to go on with.
    private func canContinue(at now: Date) -> Bool {
        resumable && endedAt.map { now.timeIntervalSince($0) < resumeWindow } == true
    }

    /// Opens a conversation, or continues the one that was paused a moment ago (same id, same captions).
    private mutating func start(at now: Date) -> [Effect] {
        guard phase == .idle else { return [] }
        if !canContinue(at: now) {
            conversationId = nil
            brain = .unknown
            exchanges = []
            announced = []
        }
        phase = .starting
        problem = nil
        current = nil
        queue = []
        playing = false
        endAfterPlayback = false
        reachable = true
        resumable = false
        endedAt = nil
        expectReply = false
        lastActivity = now
        return [.startAudio, .warmUp(conversationId: conversationId)]
    }

    /// Ends the conversation: stops what plays and listens, tells the Mac (unless it can't be reached).
    private mutating func finish(at now: Date, problem: String?, haptic: Haptic?, tellMac: Bool) -> [Effect] {
        var effects: [Effect] = []
        if let current, current.kind == .bridge, current.done == nil {
            effects.append(.cancel(turnId: current.id, conversationId: conversationId))
        }
        effects += [.stopPlayback, .closeMic, .stopAudio, .announcements(false)]
        if tellMac, reachable, let conversationId { effects.append(.end(conversationId: conversationId)) }
        if let haptic { effects.append(.haptic(haptic)) }
        phase = .idle
        current = nil
        queue = []
        playing = false
        endAfterPlayback = false
        resumable = false
        endedAt = now
        self.problem = problem
        return effects
    }

    // MARK: - Turns

    private mutating func send(_ input: Input, kind: Exchange.Kind, announcement: Announcement? = nil) -> [Effect] {
        let id = makeId()
        current = Turn(id: id, kind: .bridge, announcement: announcement)
        playing = false
        phase = .thinking
        var exchange = Exchange(id: id, kind: kind)
        if let announcement { exchange.say = announcement.say }
        record(exchange)
        return [.closeMic, .send(TurnRequest(turnId: id, conversationId: conversationId, input: input)), .haptic(.click)]
    }

    private mutating func announce(_ item: Announcement) -> [Effect] {
        if let audio = item.audio {
            let id = makeId()
            current = Turn(id: id, kind: .clip, announcement: item, complete: true)
            playing = false
            phase = .thinking
            record(Exchange(id: id, kind: .announcement, say: item.say))
            return [.closeMic, .haptic(.notification), .play(audio)]
        }
        var effects = send(.announce(item.id), kind: .announcement, announcement: item)
        effects.removeAll { $0 == .haptic(.click) }
        effects.append(.haptic(.notification))
        return effects
    }

    private mutating func turn(_ event: TurnEvent, at now: Date) -> [Effect] {
        guard var turn = current else { return [] }
        switch event {
        case .heard(let text):
            update { $0.heard = text }
        case .sayDelta(let text):
            turn.say += text
            current = turn
            // An announcement's caption is its own line already.
            if turn.announcement == nil { update { $0.say = turn.say } }
        case .sayDone(let text):
            turn.say = text
            current = turn
            if !text.isEmpty { update { $0.say = text } }
        case .action(let action):
            update { $0.actions.append(action) }
        case .card(let card):
            update { $0.cards.append(card) }
        case .done(let done):
            turn.done = done
            turn.complete = true
            current = turn
            if !done.conversationId.isEmpty { conversationId = done.conversationId }
            if done.brain != .unknown { brain = done.brain }
            expectReply = done.expectReply
            if done.interrupted { update { $0.interrupted = true } }
            if turn.gotAudio { return playing ? [] : finishTurn(at: now) }
            let say = turn.say.isEmpty ? (live?.say ?? "") : turn.say
            if !done.interrupted, !say.isEmpty {
                // No audio from the Mac: the watch says it itself.
                current?.kind = .local
                return [.speak(say)]
            }
            // Nothing to say (noise, or cancelled): keep listening.
            return finishTurn(at: now)
        case .failed(let failure):
            return fail(failure, at: now)
        }
        return []
    }

    /// A failed turn (or warm-up): the watch says why. An announcement whose turn failed is said by the watch
    /// instead. After a fatal failure the conversation ends once that was said.
    private mutating func fail(_ failure: Failure, at now: Date) -> [Effect] {
        var effects: [Effect] = []
        if playing { effects.append(.stopPlayback) }
        playing = false
        if let item = current?.announcement {
            // The watch has the line itself: say it (the next turn finds out what is wrong with the Mac).
            current?.kind = .local
            current?.complete = true
            return effects + [.speak(item.say)]
        }
        if failure == .unreachable { reachable = false }
        update { $0.failed = true }
        let id = current?.id ?? makeId()
        if current == nil { record(Exchange(id: id, kind: .notice, say: failure.spoken, failed: true)) }
        current = Turn(id: id, kind: .local, complete: true)
        phase = .thinking
        if failure.isFatal {
            endAfterPlayback = true
            problem = failure.shown
        }
        return effects + [.closeMic, .speak(failure.spoken), .haptic(.failure)]
    }

    /// The current turn is over (played out, or nothing to play): end, announce, or listen again.
    private mutating func finishTurn(at now: Date) -> [Effect] {
        let done = current?.done
        current = nil
        playing = false
        if done?.endConversation == true {
            return finish(at: now, problem: nil, haptic: .stop, tellMac: false)
        }
        if endAfterPlayback {
            // A fatal failure was said, or the app left while this played.
            let keep = resumable
            let effects = finish(at: now, problem: problem, haptic: nil, tellMac: false)
            resumable = keep
            return effects
        }
        if !queue.isEmpty { return announce(queue.removeFirst()) }
        phase = .listening
        lastActivity = now
        return [.openMic]
    }

    private mutating func tap(at now: Date) -> [Effect] {
        switch phase {
        case .idle:
            return start(at: now)
        case .starting, .listening:
            return []
        case .hearing:
            return [.flushMic]
        case .thinking, .speaking:
            // Barge in: stop the reply and listen.
            if endAfterPlayback { return finish(at: now, problem: problem, haptic: .stop, tellMac: false) }
            var effects: [Effect] = []
            if let current, current.kind == .bridge, current.done == nil || playing {
                effects.append(.cancel(turnId: current.id, conversationId: conversationId))
            }
            update { $0.interrupted = true }
            current = nil
            playing = false
            phase = .listening
            lastActivity = now
            return effects + [.stopPlayback, .openMic, .haptic(.click)]
        }
    }

    // MARK: - Captions

    private mutating func record(_ exchange: Exchange) {
        exchanges.append(exchange)
        if exchanges.count > Self.maxExchanges { exchanges.removeFirst(exchanges.count - Self.maxExchanges) }
    }

    private mutating func update(_ change: (inout Exchange) -> Void) {
        guard let id = current?.id, let index = exchanges.lastIndex(where: { $0.id == id }) else { return }
        change(&exchanges[index])
    }
}
