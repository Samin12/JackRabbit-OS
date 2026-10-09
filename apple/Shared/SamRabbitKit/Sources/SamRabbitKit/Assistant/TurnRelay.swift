import Foundation
import Synchronization

/// An assistant turn through the iPhone, for when the watch can't reach the Mac itself. WatchConnectivity
/// messages are small and each one gets one reply, so the turn goes like this:
///
/// 1. An utterance goes up in `VoiceRelay` chunks of at most 40 KB (purpose `turn`, id = the turn id); the phone
///    acknowledges each and keeps the whole recording.
/// 2. `start`: the phone sends the turn to the Mac with the watch's own child token (never its own), asking for
///    the frame stream, exactly as the watch would.
/// 3. `pull {offset}`: the phone answers with the bridge's status and content type (once the Mac answered) and the
///    next piece of the body from `offset` (at most 40 KB, raw bytes under `dataKey`), waiting up to 1.5 s for more;
///    `done` once the body ended and the watch has it all. Pulling the same offset again is safe.
/// 4. `cancel`: the phone drops the turn (barge-in; the watch also asks the Mac to cancel).
///
/// A turn the Mac refused (409 "still answering the last thing") or couldn't be reached for is started again when
/// the watch retries it with the same id: the phone runs it again with the recording it kept, so the retry sends no
/// audio (unless the phone no longer has it: then the refusal says so and the watch sends it again).
///
/// The watch reads the pieces as if they came straight from the Mac, so a stream, a buffered JSON answer (an older
/// bridge, the Claude fallback) and an error envelope all come through unchanged.
public enum TurnRelay {
    public static let key = "samrabbit.turn"
    public static let replyKey = "samrabbit.turn.reply"
    public static let dataKey = "samrabbit.turn.data"
    /// `VoiceRelay.Chunk.purpose` of a turn's recording.
    public static let purpose = "turn"
    public static let pieceSize = 40 * 1024
    /// How long a pull waits on the phone for more of the body.
    public static let pullWait: TimeInterval = 1.5
    /// A turn nobody pulled for this long is dropped on the phone.
    static let expiry: TimeInterval = 120
    static let maxOpen = 4

    /// A watch -> phone message (JSON under `key`).
    public struct Message: Codable, Sendable, Equatable {
        public enum Op: String, Codable, Sendable { case start, pull, cancel }

        public var op: Op
        public var turnId: String
        public var conversationId: String?
        /// `audio` (the recording came in chunks), `text` or `announce`.
        public var kind: String?
        public var text: String?
        public var announce: String?
        public var language: String?
        public var deviceTime: Date?
        public var stream: Bool?
        public var offset: Int?

        public init(op: Op, turnId: String, conversationId: String? = nil, kind: String? = nil, text: String? = nil,
                    announce: String? = nil, language: String? = nil, deviceTime: Date? = nil, stream: Bool? = nil,
                    offset: Int? = nil) {
            self.op = op
            self.turnId = turnId
            self.conversationId = conversationId
            self.kind = kind
            self.text = text
            self.announce = announce
            self.language = language
            self.deviceTime = deviceTime
            self.stream = stream
            self.offset = offset
        }

        public static func start(_ turn: AssistantTurnRequest) -> Message {
            var message = Message(op: .start, turnId: turn.turnId, conversationId: turn.conversationId,
                                  language: turn.language, deviceTime: turn.deviceTime, stream: turn.stream)
            switch turn.input {
            case .audio: message.kind = "audio"
            case .text(let text):
                message.kind = "text"
                message.text = text
            case .announce(let id):
                message.kind = "announce"
                message.announce = id
            }
            return message
        }

        public static func pull(_ turnId: String, offset: Int) -> Message { Message(op: .pull, turnId: turnId, offset: offset) }
        public static func cancel(_ turnId: String) -> Message { Message(op: .cancel, turnId: turnId) }

        /// The turn a `start` describes; `audio` is the recording the chunks brought.
        public func turn(audio: Data?) -> AssistantTurnRequest? {
            let input: ConversationMachine.Input
            switch kind {
            case "audio":
                guard let audio, !audio.isEmpty else { return nil }
                input = .audio(audio)
            case "text":
                guard let text else { return nil }
                input = .text(text)
            case "announce":
                guard let announce, !announce.isEmpty else { return nil }
                input = .announce(announce)
            default:
                return nil
            }
            return AssistantTurnRequest(turnId: turnId, conversationId: conversationId, input: input, language: language,
                                        deviceTime: deviceTime ?? .now, stream: stream ?? true)
        }

        public var message: [String: Any] {
            guard let data = try? BridgeJSON.encoder().encode(self) else { return [:] }
            return [TurnRelay.key: data]
        }

        public init?(message: [String: Any]) {
            guard let data = message[TurnRelay.key] as? Data,
                  let value = try? BridgeJSON.decode(Message.self, from: data) else { return nil }
            self = value
        }

        /// Turn ids are uuids: the same rule as recording ids.
        var validId: Bool {
            (1...64).contains(turnId.count)
                && turnId.unicodeScalars.allSatisfy { $0.isASCII && (CharacterSet.alphanumerics.contains($0) || $0 == "-") }
        }
    }

    /// The phone's reply (JSON under `replyKey`, the body's piece under `dataKey`).
    public struct Reply: Codable, Sendable, Equatable {
        public var ok: Bool
        /// The bridge's status once the Mac answered (0: the phone could not reach it); nil while waiting.
        public var status: Int?
        public var contentType: String?
        /// Where the piece starts in the body.
        public var offset: Int
        /// The body ended and this reply holds the rest of it.
        public var done: Bool

        public init(ok: Bool = true, status: Int? = nil, contentType: String? = nil, offset: Int = 0, done: Bool = false) {
            self.ok = ok
            self.status = status
            self.contentType = contentType
            self.offset = offset
            self.done = done
        }

        public func message(data: Data = Data()) -> [String: Any] {
            guard let encoded = try? BridgeJSON.encoder().encode(self) else { return [:] }
            return [TurnRelay.replyKey: encoded, TurnRelay.dataKey: data]
        }
    }

    /// What the watch sends.
    public enum Outgoing: Sendable, Equatable {
        case chunk(VoiceRelay.Chunk)
        case message(Message)

        public var dictionary: [String: Any] {
            switch self {
            case .chunk(let chunk): chunk.message
            case .message(let message): message.message
            }
        }
    }

    /// What the phone answered (only the parts a turn reads).
    public struct Answer: Sendable, Equatable {
        /// A chunk the phone kept (its `seq`).
        public var ack: Int?
        public var reply: Reply?
        public var data: Data
        /// The phone's (or the bridge's) refusal: status and body.
        public var refusal: WatchRelay.Response?

        public init(ack: Int? = nil, reply: Reply? = nil, data: Data = Data(), refusal: WatchRelay.Response? = nil) {
            self.ack = ack
            self.reply = reply
            self.data = data
            self.refusal = refusal
        }

        public init(dictionary: [String: Any]) {
            ack = dictionary[VoiceRelay.ackKey] as? Int
            reply = (dictionary[TurnRelay.replyKey] as? Data).flatMap { try? BridgeJSON.decode(Reply.self, from: $0) }
            data = dictionary[TurnRelay.dataKey] as? Data ?? Data()
            refusal = WatchRelay.Response(message: dictionary)
        }
    }
}

/// The watch's way to the phone for turns (`PhoneLink` sends with `WCSession.sendMessage`).
public protocol TurnRelayLink: Sendable {
    /// Sends one message and returns the phone's answer, or nil when none came in `timeout` (the phone may have
    /// acted). Throws `BridgeError.unreachable` when the phone can't be reached at all (nothing was sent).
    func send(_ outgoing: TurnRelay.Outgoing, timeout: TimeInterval) async throws -> TurnRelay.Answer?
}

extension TurnRelay {
    /// Turns whose recording reached the phone lately (the watch's side): a retry of one (409 "still answering")
    /// starts it again without sending the audio a second time.
    static let delivered = Mutex<[String: Date]>([:])

    static func noteDelivered(_ turnId: String, now: Date = .now) {
        delivered.withLock { sent in
            sent = sent.filter { now.timeIntervalSince($0.value) < expiry }
            if sent.count >= 32, let oldest = sent.min(by: { $0.value < $1.value })?.key { sent[oldest] = nil }
            sent[turnId] = now
        }
    }

    static func wasDelivered(_ turnId: String, now: Date = .now) -> Bool {
        delivered.withLock { $0[turnId].map { now.timeIntervalSince($0) < expiry } ?? false }
    }

    /// The phone's refusal of a start whose recording it doesn't have (any more).
    static func missingRecording(_ refusal: WatchRelay.Response) -> Bool {
        refusal.status == 400 && BridgeError.from(status: 400, data: refusal.body).code == "invalid_audio"
    }

    /// The watch's side: sends the turn through the phone and returns the bridge's answer as it streams in.
    /// Status 0: the phone couldn't reach the Mac either.
    public static func run(_ turn: AssistantTurnRequest, over link: any TurnRelayLink) async throws -> AssistantTurnResponse {
        var started: Answer?
        if case .audio(let wav) = turn.input {
            if wasDelivered(turn.turnId) {
                // A retry: the phone kept the recording.
                guard let answer = try await link.send(.message(.start(turn)), timeout: 15) else {
                    throw BridgeError.unreachable("iPhone: the turn got no answer")
                }
                if let refusal = answer.refusal, missingRecording(refusal) {
                    started = nil // the phone dropped it meanwhile: send it again
                } else {
                    started = answer
                }
            }
            if started == nil {
                let chunks = VoiceRelay.chunks(of: wav, id: turn.turnId, contentType: WAV.contentType,
                                               language: turn.language, purpose: purpose)
                for chunk in chunks {
                    guard let answer = try await link.send(.chunk(chunk), timeout: 15) else {
                        throw BridgeError.unreachable("iPhone: the recording got no answer")
                    }
                    if let refusal = answer.refusal { return .whole(status: refusal.status, body: refusal.body) }
                    guard answer.ack == chunk.seq else { throw BridgeError.unreachable("iPhone: a piece got lost") }
                }
                noteDelivered(turn.turnId)
            }
        }
        if started == nil {
            guard let answer = try await link.send(.message(.start(turn)), timeout: 15) else {
                throw BridgeError.unreachable("iPhone: the turn got no answer")
            }
            started = answer
        }
        guard let started else { throw BridgeError.unreachable("iPhone: the turn got no answer") }
        if let refusal = started.refusal { return .whole(status: refusal.status, body: refusal.body) }
        guard started.reply?.ok == true else { throw BridgeError.unreachable("iPhone: the turn didn't start") }

        // Until the Mac answers, each pull waits on the phone (which gives the Mac `headTimeout`).
        var first: (reply: Reply, data: Data)?
        let deadline = Date.now.addingTimeInterval(turn.headTimeout + 10)
        var misses = 0
        while first == nil {
            try Task.checkCancellation()
            guard Date.now < deadline else {
                await cancel(turn.turnId, over: link)
                throw BridgeError.unreachable("iPhone: the Mac didn't answer")
            }
            guard let answer = try await link.send(.message(.pull(turn.turnId, offset: 0)), timeout: 10) else {
                misses += 1
                if misses >= 3 { throw BridgeError.unreachable("iPhone: stopped answering") }
                continue
            }
            if let refusal = answer.refusal { return .whole(status: refusal.status, body: refusal.body) }
            misses = 0
            if let reply = answer.reply, reply.status != nil { first = (reply, answer.data) }
        }
        guard let first, let status = first.reply.status else { throw BridgeError.unreachable("iPhone") }
        if status == 0 { return .whole(status: 0, body: Data()) }
        let body = AsyncThrowingStream<Data, Error> { continuation in
            let task = Task {
                var offset = first.data.count
                if !first.data.isEmpty { continuation.yield(first.data) }
                var done = first.reply.done
                var misses = 0
                do {
                    while !done {
                        try Task.checkCancellation()
                        guard let answer = try await link.send(.message(.pull(turn.turnId, offset: offset)), timeout: 10),
                              let reply = answer.reply else {
                            misses += 1
                            if misses >= 3 { throw BridgeError.unreachable("iPhone: stopped answering") }
                            continue
                        }
                        misses = 0
                        guard reply.offset == offset else { continue } // a late answer to an earlier pull
                        if !answer.data.isEmpty {
                            continuation.yield(answer.data)
                            offset += answer.data.count
                        }
                        done = reply.done
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error is CancellationError ? BridgeError.cancelled : error)
                }
            }
            continuation.onTermination = { reason in
                task.cancel()
                if case .cancelled = reason { Task { await cancel(turn.turnId, over: link) } }
            }
        }
        return AssistantTurnResponse(status: status, contentType: first.reply.contentType ?? "", body: body)
    }

    /// Tells the phone to drop a turn (best effort).
    public static func cancel(_ turnId: String, over link: any TurnRelayLink) async {
        _ = try? await link.send(.message(.cancel(turnId)), timeout: 5)
    }
}

/// The phone's side: keeps the recordings of turns, runs each turn against the Mac with the watch's own client and
/// hands its body out in pieces as the watch pulls. `activity(turnId, true/false)` brackets the time a turn needs
/// the phone awake (the iPhone app holds a background task for it): from `start` until the watch pulled the last
/// piece, cancelled, or stopped pulling for two minutes.
public final class TurnRelayHost: Sendable {
    private struct Turn {
        /// What was sent to the Mac (with the recording), to send again when the Mac refused it.
        var request: AssistantTurnRequest
        var status: Int?
        var contentType: String?
        var body = Data()
        var done = false
        var released = false
        var touched: Date
        var task: Task<Void, Never>?

        /// Over without a 2xx answer (409 still answering, the Mac out of reach, ...): a start again runs it again.
        var refused: Bool { done && !(200..<300).contains(status ?? 0) }
    }

    private struct State {
        var uploads: [String: (upload: VoiceRelay.Upload, at: Date)] = [:]
        var turns: [String: Turn] = [:]
    }

    private let state = Mutex(State())
    private let activity: (@Sendable (String, Bool) -> Void)?

    public init(activity: (@Sendable (_ turnId: String, _ active: Bool) -> Void)? = nil) {
        self.activity = activity
    }

    /// Turns that are running or not yet fully pulled.
    public var open: Int { state.withLock { $0.turns.values.filter { !$0.released }.count } }

    /// A turn's recording, put together from its chunks (`VoiceRelay.Upload.purpose == "turn"`).
    public func keep(_ upload: VoiceRelay.Upload, now: Date = .now) {
        state.withLock { state in
            state.uploads = state.uploads.filter { now.timeIntervalSince($0.value.at) < TurnRelay.expiry }
            state.uploads[upload.id] = (upload, now)
        }
    }

    /// Handles one message from the watch and returns the reply dictionary.
    public func handle(_ message: TurnRelay.Message, client: BridgeClient?) async -> [String: Any] {
        switch message.op {
        case .start:
            return start(message, client: client)
        case .pull:
            let (reply, data) = await pull(message.turnId, offset: message.offset ?? 0)
            return reply.message(data: data)
        case .cancel:
            cancel(message.turnId)
            return TurnRelay.Reply(ok: true, done: true).message()
        }
    }

    /// Starts the turn. A repeated start of a turn that is running (or answered) just says ok; one of a turn the Mac
    /// refused (409 still answering the last one) or couldn't be reached for runs it again: the watch retries a
    /// refusal with the same turn id, and must get the Mac's new answer, not the old refusal.
    public func start(_ message: TurnRelay.Message, client: BridgeClient?, now: Date = .now) -> [String: Any] {
        guard message.validId else {
            return WatchRelay.Response.refusal(400, "invalid_turn", "That isn't a turn id.").message
        }
        guard let client else {
            return WatchRelay.Response.refusal(401, "unauthorized", "Reconnect your watch through the iPhone.").message
        }
        sweep(now: now)
        enum Outcome { case started(AssistantTurnRequest), again, busy, missing, restarted(AssistantTurnRequest, held: Bool) }
        let outcome = state.withLock { state -> Outcome in
            if let existing = state.turns[message.turnId] {
                // A recording sent again (an older watch resends it with every retry) replaces the kept one.
                let audio = state.uploads.removeValue(forKey: message.turnId)?.upload.data
                guard existing.refused else { return .again }
                let request = message.turn(audio: audio ?? existing.request.audio) ?? existing.request
                state.turns[message.turnId] = Turn(request: request, touched: now)
                return .restarted(request, held: !existing.released)
            }
            guard state.turns.values.filter({ !$0.released }).count < TurnRelay.maxOpen else { return .busy }
            let audio = state.uploads.removeValue(forKey: message.turnId)?.upload.data
            guard let request = message.turn(audio: audio) else { return .missing }
            state.turns[message.turnId] = Turn(request: request, touched: now)
            return .started(request)
        }
        switch outcome {
        case .again:
            return TurnRelay.Reply(ok: true).message()
        case .restarted(let request, let held):
            if held { activity?(request.turnId, false) } // `run` holds the phone awake again
            run(request, client: client)
            return TurnRelay.Reply(ok: true).message()
        case .busy:
            return WatchRelay.Response.refusal(503, "relay_busy", "The iPhone is passing on other turns.").message
        case .missing:
            return WatchRelay.Response.refusal(400, "invalid_audio", "The recording didn't reach the iPhone.").message
        case .started(let request):
            run(request, client: client)
            return TurnRelay.Reply(ok: true).message()
        }
    }

    private func run(_ request: AssistantTurnRequest, client: BridgeClient) {
        let id = request.turnId
        activity?(id, true)
        let task = Task { [weak self] in
            do {
                let response = try await client.openDirect(request)
                self?.head(id, status: response.status, contentType: response.contentType)
                for try await piece in response.body { self?.append(id, piece) }
                self?.finish(id, status: nil)
            } catch {
                // Before the Mac answered: 0, the phone couldn't reach it. After: the body just ends (the watch
                // sees the stream end early).
                self?.finish(id, status: 0)
            }
        }
        state.withLock { $0.turns[id]?.task = task }
    }

    /// The next piece of the body from `offset`, waiting up to `wait` for one.
    public func pull(_ turnId: String, offset: Int, wait: TimeInterval = TurnRelay.pullWait) async -> (TurnRelay.Reply, Data) {
        let deadline = Date.now.addingTimeInterval(wait)
        while true {
            let answer = state.withLock { state -> (reply: TurnRelay.Reply, data: Data, release: Bool)? in
                guard var turn = state.turns[turnId] else {
                    return (TurnRelay.Reply(ok: false, status: 0, offset: offset, done: true), Data(), false)
                }
                turn.touched = .now
                defer { state.turns[turnId] = turn }
                guard let status = turn.status else { return nil }
                let start = min(max(0, offset), turn.body.count)
                let end = min(turn.body.count, start + TurnRelay.pieceSize)
                let base = turn.body.startIndex
                let piece = start < end ? Data(turn.body[(base + start)..<(base + end)]) : Data()
                let done = turn.done && end == turn.body.count
                if piece.isEmpty, !done { return nil }
                let release = done && !turn.released
                if release { turn.released = true }
                return (TurnRelay.Reply(ok: true, status: status, contentType: turn.contentType, offset: start,
                                        done: done), piece, release)
            }
            if let answer {
                if answer.release { activity?(turnId, false) }
                return (answer.reply, answer.data)
            }
            if Date.now >= deadline {
                let head = state.withLock { ($0.turns[turnId]?.status, $0.turns[turnId]?.contentType) }
                return (TurnRelay.Reply(ok: true, status: head.0, contentType: head.1, offset: offset, done: false), Data())
            }
            try? await Task.sleep(for: .milliseconds(40))
        }
    }

    /// Drops a turn (the watch barged in or gave up).
    public func cancel(_ turnId: String) {
        let (task, release) = state.withLock { state -> (Task<Void, Never>?, Bool) in
            state.uploads[turnId] = nil
            guard let turn = state.turns.removeValue(forKey: turnId) else { return (nil, false) }
            return (turn.task, !turn.released)
        }
        task?.cancel()
        if release { activity?(turnId, false) }
    }

    /// Forgets everything (the pairing changed).
    public func reset() {
        let (tasks, ids) = state.withLock { state -> ([Task<Void, Never>], [String]) in
            defer { state = State() }
            return (state.turns.values.compactMap(\.task), state.turns.filter { !$0.value.released }.map(\.key))
        }
        tasks.forEach { $0.cancel() }
        ids.forEach { activity?($0, false) }
    }

    /// Drops turns nobody pulled for a while (released ones after 30 s) and stale recordings.
    public func sweep(now: Date = .now) {
        let (tasks, released) = state.withLock { state -> ([Task<Void, Never>], [String]) in
            var tasks: [Task<Void, Never>] = []
            var released: [String] = []
            for (id, turn) in state.turns where now.timeIntervalSince(turn.touched) >= (turn.released ? 30 : TurnRelay.expiry) {
                state.turns[id] = nil
                if let task = turn.task { tasks.append(task) }
                if !turn.released { released.append(id) }
            }
            state.uploads = state.uploads.filter { now.timeIntervalSince($0.value.at) < TurnRelay.expiry }
            return (tasks, released)
        }
        tasks.forEach { $0.cancel() }
        released.forEach { activity?($0, false) }
    }

    // MARK: - Turn bookkeeping

    private func head(_ id: String, status: Int, contentType: String) {
        state.withLock {
            $0.turns[id]?.status = status
            $0.turns[id]?.contentType = contentType
        }
    }

    private func append(_ id: String, _ piece: Data) {
        state.withLock { $0.turns[id]?.body.append(piece) }
    }

    private func finish(_ id: String, status: Int?) {
        state.withLock { state in
            if state.turns[id]?.status == nil { state.turns[id]?.status = status ?? 0 }
            state.turns[id]?.done = true
        }
    }
}
