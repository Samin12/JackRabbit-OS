#if os(macOS)
import Foundation
import Synchronization
import Testing
@testable import SamRabbitKit

extension FakeBridgeProcess {
    /// `GET /__fake/assistant`: the mode and what arrived (turns, sessions, cancels, ends).
    func assistantState() async throws -> JSONValue {
        let (data, _) = try await URLSession.shared.data(from: base.appendingPathComponent("__fake/assistant"))
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }
}

/// Reads a turn to the end.
func collect(_ stream: AsyncThrowingStream<AssistantStreamItem, Error>) async throws -> [AssistantStreamItem] {
    var items: [AssistantStreamItem] = []
    for try await item in stream { items.append(item) }
    return items
}

extension [AssistantStreamItem] {
    var heard: String? { compactMap { if case .event(.heard(let t)) = $0 { t } else { nil } }.first }
    var deltas: String { compactMap { if case .event(.sayDelta(let t)) = $0 { t } else { nil } }.joined() }
    var said: String? { compactMap { if case .event(.sayDone(let t)) = $0 { t } else { nil } }.last }
    var audioBytes: Int { reduce(0) { if case .audio(let d) = $1 { $0 + d.count } else { $0 } } }
    var done: AssistantDone? { compactMap { if case .event(.done(let d)) = $0 { d } else { nil } }.last }
    var actions: [AssistantAction] { compactMap { if case .event(.action(let a)) = $0 { a } else { nil } } }
    var cards: [AssistantCard] { compactMap { if case .event(.card(let c)) = $0 { c } else { nil } } }
}

/// One second of a voice-like tone as the watch sends it: 16 kHz mono 16-bit WAV.
let utteranceWAV = WAV.encode(samples: Signal.speech(1.2, dbfs: -22))

@Suite("Assistant against the fake bridge", .serialized)
struct AssistantBridgeTests {
    static let say = "Okay, I started the release notes for build 2.4 in Hermes. Anything else?"

    @Test func warmsUpAndStreamsATurnFromAnUtterance() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, account) = try await bridge.pairedClient()
        let summary = try await client.summary()
        #expect(summary.assistant == AssistantStatus(available: true, brain: .realtime, model: "gpt-realtime-2.1",
                                                     chatgptConnected: true))
        let session = try await client.assistantSession()
        #expect(session.brain == .realtime && session.ready)
        #expect(session.conversationId.hasPrefix("wc_"))
        let turn = AssistantTurnRequest(turnId: UUID().uuidString, conversationId: session.conversationId,
                                        input: .audio(utteranceWAV), language: "en-US")
        let items = try await collect(client.assistantTurn(turn))
        #expect(items.first == .event(.heard("Draft the release notes for build 2.4")))
        #expect(items.deltas == Self.say) // the live caption adds up to what was said
        #expect(items.said == Self.say)
        // About 4.3 s of 16 kHz PCM16 in 160 ms frames, before `say.done`.
        #expect(items.audioBytes == 137_600)
        let lastAudio = try #require(items.lastIndex { if case .audio = $0 { true } else { false } })
        #expect(lastAudio < items.firstIndex(of: .event(.sayDone(Self.say)))!)
        #expect(items.actions.map(\.kind) == ["t3.started"])
        let done = try #require(items.done)
        #expect(items.last == .event(.done(done)))
        #expect(done.conversationId == session.conversationId)
        #expect(done.turnId == turn.turnId)
        #expect(done.brain == .realtime && done.expectReply && !done.endConversation && !done.interrupted)
        #expect(done.timings.firstAudio != nil)
        // As the bridge saw it.
        let sent = try await bridge.assistantState()["turns"].array?.last
        #expect(sent?["kind"].string == "audio")
        #expect(sent?["contentType"].string == "audio/wav")
        #expect(sent?["bytes"].int == utteranceWAV.count)
        #expect(sent?["accept"].string == "application/x-samrabbit-stream")
        #expect(sent?["deviceTime"].bool == true)
        #expect(sent?["lang"].string == "en-US")
        #expect(sent?["stream"].bool == true)
        #expect(sent?["deviceId"].string == account.pairing?.deviceId)
    }

    @Test func answersInOnePieceWithoutTheStream() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let turn = AssistantTurnRequest(turnId: UUID().uuidString, conversationId: nil, input: .text("What's next on my calendar?"),
                                        stream: false)
        let items = try await collect(client.assistantTurn(turn))
        let say = "Next up is the design review at three. Want me to block time before it?"
        #expect(items.heard == "What's next on my calendar?")
        #expect(items.said == say)
        #expect(items.audioBytes > 100_000) // the WAV came back as PCM: plays like the stream
        #expect(items.cards == [AssistantCard(title: "Up next", body: "Design review at 3:00 PM")])
        #expect(items.done?.conversationId.hasPrefix("wc_") == true)
        #expect(try await bridge.assistantState()["turns"].array?.last?["stream"].bool == false)
    }

    /// The Claude fallback (and an older bridge) answers in one piece even when the stream was asked for, with an
    /// encoded clip: it comes through as `.clip`.
    @Test func theFallbackAnswersInOnePieceWithAClip() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        _ = try await bridge.control("assistant", ["mode": "buffered_clip", "delay": 0])
        let items = try await collect(client.assistantTurn(
            AssistantTurnRequest(turnId: UUID().uuidString, conversationId: nil, input: .audio(utteranceWAV))))
        let clip = items.compactMap { if case .clip(let audio) = $0 { audio } else { nil } }.first
        #expect(clip?.mime == "audio/mp4")
        #expect(clip?.data.prefix(8).suffix(4) == Data("ftyp".utf8))
        #expect(items.audioBytes == 0)
        #expect(items.done?.brain == .claude)
    }

    @Test func announcementsComeOnceAndAreSpokenAsATurn() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let session = try await client.assistantSession()
        #expect(try await client.assistantAnnouncements(conversationId: session.conversationId).items.isEmpty)
        _ = try await bridge.control("announce", ["say": "“Fix login redirect” in Hermes finished."])
        let page = try await client.assistantAnnouncements(conversationId: session.conversationId)
        let item = try #require(page.items.first)
        #expect(item.say == "“Fix login redirect” in Hermes finished.")
        #expect(item.kind == "done" && item.threadId == "t_login" && item.audio == nil)
        #expect(try await client.assistantAnnouncements(conversationId: session.conversationId, since: page.cursor)
            .items.isEmpty) // once per conversation
        let items = try await collect(client.assistantTurn(
            AssistantTurnRequest(turnId: UUID().uuidString, conversationId: session.conversationId, input: .announce(item.id))))
        #expect(items.said == item.say)
        #expect(items.audioBytes > 0)
        let sent = try await bridge.assistantState()["turns"].array?.last
        #expect(sent?["kind"].string == "announce")
        #expect(sent?["announce"].string == item.id)
        // An announcement with its own clip.
        _ = try await bridge.control("announce", ["say": "Deploy needs you.", "kind": "needs_you", "audio": true])
        let withClip = try await client.assistantAnnouncements(conversationId: session.conversationId).items.first
        #expect(withClip?.audio?.pcm16k?.isEmpty == false)
    }

    @Test func aRepeatedTurnGetsTheSameAnswer() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let turn = AssistantTurnRequest(turnId: UUID().uuidString, conversationId: nil, input: .audio(utteranceWAV), stream: false)
        let first = try await collect(client.assistantTurn(turn))
        let again = try await collect(client.assistantTurn(turn))
        #expect(first == again)
        let turns = try await bridge.assistantState()["turns"].array ?? []
        #expect(turns.map { $0["replay"].bool } == [false, true])
    }

    /// Barge-in: `POST /assistant/cancel` stops the reply; the stream ends with `done{interrupted: true}`.
    @Test func cancelStopsTheReply() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        _ = try await bridge.control("assistant", ["pace": 1, "delay": 0])
        let session = try await client.assistantSession()
        let turn = AssistantTurnRequest(turnId: UUID().uuidString, conversationId: session.conversationId, input: .audio(utteranceWAV))
        var items: [AssistantStreamItem] = []
        var cancelled = false
        for try await item in client.assistantTurn(turn) {
            items.append(item)
            if !cancelled, case .audio = item {
                cancelled = true
                try await client.assistantCancel(conversationId: session.conversationId)
            }
        }
        #expect(items.done?.interrupted == true)
        #expect(items.said == nil) // stopped before it was all said
        #expect(items.audioBytes < 137_600)
        #expect(try await bridge.assistantState()["cancels"].array?.last?["cancelled"].bool == true)
        try await client.assistantEnd(conversationId: session.conversationId)
        #expect(try await bridge.assistantState()["ends"].array?.last?["ended"].bool == true)
    }

    @Test func refusalsAndOddAnswers() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        func turn() -> AssistantTurnRequest {
            AssistantTurnRequest(turnId: UUID().uuidString, conversationId: nil, input: .audio(utteranceWAV))
        }
        _ = try await bridge.control("assistant", ["mode": "busy", "delay": 0])
        await #expect { _ = try await collect(client.assistantTurn(turn(), busyFor: 0.8)) } throws: {
            ($0 as? BridgeError)?.code == "assistant_busy"
        }
        #expect((try await bridge.assistantState()["turns"].array ?? []).isEmpty) // refused before it counted
        _ = try await bridge.control("assistant", ["mode": "unavailable"])
        #expect(try await client.summary().assistant?.available == false)
        await #expect { _ = try await collect(client.assistantTurn(turn())) } throws: {
            ($0 as? BridgeError)?.code == "assistant_unavailable"
        }
        _ = try await bridge.control("assistant", ["mode": "missing"])
        #expect(try await client.summary().assistant == nil)
        await #expect { _ = try await collect(client.assistantTurn(turn())) } throws: {
            if case .server(404, _, _, _) = $0 as? BridgeError { return true }
            return false
        }
        _ = try await bridge.control("assistant", ["mode": "noise"])
        let noise = try await collect(client.assistantTurn(turn()))
        #expect(noise.heard == "" && noise.audioBytes == 0 && noise.said == "")
        _ = try await bridge.control("assistant", ["mode": "error"])
        let failed = try await collect(client.assistantTurn(turn()))
        #expect(failed.last == .event(.error(code: "assistant_failed", message: "The realtime session failed.")))
        _ = try await bridge.control("assistant", ["mode": "end"])
        #expect(try await collect(client.assistantTurn(turn())).done?.endConversation == true)
        _ = try await bridge.control("assistant", ["mode": "drop"])
        await #expect { _ = try await collect(client.assistantTurn(turn())) } throws: {
            if case .unreachable = $0 as? BridgeError { return true }
            return false
        }
    }
}

/// The iPhone's half of the turn relay, in process: every message goes through the same dictionaries as
/// `WCSession.sendMessage` and is handled exactly like `iOS/App/WatchLink.swift` does (`VoiceRelay.Assembler` for the
/// recording, `TurnRelayHost` with the watch's own child token for the rest).
final class InProcessTurnLink: TurnRelayLink, @unchecked Sendable {
    let phone: BridgeAccount
    let host: TurnRelayHost
    let assembler = VoiceRelay.Assembler()
    let sent = Mutex<[String]>([])
    let active: Counts

    /// How often each turn held the phone awake minus how often it let go (0 once done).
    final class Counts: Sendable {
        let value = Mutex<[String: Int]>([:])
        func withLock<T>(_ body: (inout [String: Int]) -> T) -> T { value.withLock { body(&$0) } }
    }

    init(phone: BridgeAccount) {
        self.phone = phone
        let active = Counts()
        self.active = active
        host = TurnRelayHost { id, on in active.withLock { $0[id, default: 0] += on ? 1 : -1 } }
    }

    func send(_ outgoing: TurnRelay.Outgoing, timeout: TimeInterval) async throws -> TurnRelay.Answer? {
        let message = outgoing.dictionary
        if let chunk = VoiceRelay.Chunk(message: message) {
            sent.withLock { $0.append("chunk \(chunk.seq)/\(chunk.of)") }
            switch assembler.add(chunk) {
            case .waiting: return TurnRelay.Answer(dictionary: [VoiceRelay.ackKey: chunk.seq])
            case .refused(let response): return TurnRelay.Answer(dictionary: response.message)
            case .complete(let upload):
                guard upload.purpose == TurnRelay.purpose else { return nil }
                host.keep(upload)
                return TurnRelay.Answer(dictionary: [VoiceRelay.ackKey: chunk.seq])
            }
        }
        guard let turn = TurnRelay.Message(message: message) else { return nil }
        sent.withLock { $0.append(turn.op.rawValue) }
        return TurnRelay.Answer(dictionary: await host.handle(turn, client: phone.watchClient()))
    }
}

extension PhoneRelayStandIn {
    static let turnLinks = Mutex<[ObjectIdentifier: InProcessTurnLink]>([:])

    var turnLink: InProcessTurnLink {
        Self.turnLinks.withLock { links in
            if let link = links[ObjectIdentifier(self)] { return link }
            let link = InProcessTurnLink(phone: phone)
            links[ObjectIdentifier(self)] = link
            return link
        }
    }

    func relayAssistantTurn(_ turn: AssistantTurnRequest) async throws -> AssistantTurnResponse {
        if state.withLock({ $0.down }) { throw BridgeError.unreachable("phone not reachable") }
        state.withLock { $0.relayed.append("TURN \(turn.turnId.prefix(4))") }
        return try await TurnRelay.run(turn, over: turnLink)
    }
}

@Suite("Assistant turns through the iPhone", .serialized)
struct AssistantRelayTests {
    @Test func aTurnGoesThroughThePhoneWhenTheMacIsOutOfReach() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, relay, watch, context) = try await RelayTests().setUpWithContext(watchHost: closed.host)
        // 2.6 s of speech: 83 KB, three chunks.
        let wav = WAV.encode(samples: Signal.speech(2.6, dbfs: -22))
        let turn = AssistantTurnRequest(turnId: UUID().uuidString, conversationId: nil, input: .audio(wav), language: "en-US")
        let items = try await collect(watch.assistantTurn(turn))
        #expect(items.heard == "Draft the release notes for build 2.4")
        #expect(items.said == AssistantBridgeTests.say)
        #expect(items.audioBytes == 137_600) // every frame, in 40 KB pieces
        #expect(items.done?.turnId == turn.turnId)
        let link = relay.turnLink
        #expect(link.sent.withLock { $0.prefix(4) } == ["chunk 0/3", "chunk 1/3", "chunk 2/3", "start"])
        #expect(link.sent.withLock { $0.filter { $0 == "pull" }.count } >= 4)
        #expect(relay.state.withLock { $0.relayed }.count == 1)
        // With the watch's own child token, the recording intact.
        let sent = try await bridge.assistantState()["turns"].array?.last
        #expect(sent?["deviceId"].string == context.deviceId)
        #expect(sent?["platform"].string == "watchos")
        #expect(sent?["bytes"].int == wav.count)
        #expect(sent?["lang"].string == "en-US")
        // The phone stayed awake for it, then let go.
        #expect(link.active.withLock { $0[turn.turnId] } == 0)
        #expect(link.host.open == 0)
        // The warm-up and the announcements go through the phone too (the generic relay).
        let session = try await watch.assistantSession()
        #expect(try await watch.assistantAnnouncements(conversationId: session.conversationId).items.isEmpty)
    }

    @Test func aBargeInDropsTheTurnOnThePhone() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, relay, watch, _) = try await RelayTests().setUpWithContext(watchHost: closed.host)
        _ = try await bridge.control("assistant", ["pace": 1, "delay": 0])
        let turn = AssistantTurnRequest(turnId: UUID().uuidString, conversationId: nil, input: .text("Draft the notes"))
        for try await item in watch.assistantTurn(turn) {
            if case .audio = item { break } // the watch stops reading: barge-in
        }
        let link = relay.turnLink
        for _ in 0..<50 where link.host.open > 0 { try await Task.sleep(for: .milliseconds(50)) }
        #expect(link.host.open == 0)
        #expect(link.sent.withLock { $0.contains("cancel") })
        #expect(link.active.withLock { $0[turn.turnId] } == 0)
    }

    @Test func aRevokedWatchIsRefusedThroughThePhone() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, _, watch, context) = try await RelayTests().setUpWithContext(watchHost: closed.host)
        try await bridge.revoke(context.deviceId)
        await #expect(throws: BridgeError.unauthorized) {
            _ = try await collect(watch.assistantTurn(
                AssistantTurnRequest(turnId: UUID().uuidString, conversationId: nil, input: .text("hi"))))
        }
    }

    @Test func thePhoneWaitsForTheMacAndRefusesStrangers() async throws {
        let host = TurnRelayHost()
        // A turn the phone doesn't know: "couldn't reach the Mac".
        let (unknown, _) = await host.pull("nope", offset: 0, wait: 0)
        #expect(unknown.status == 0 && unknown.done)
        let refused = TurnRelay.Answer(dictionary: host.start(.init(op: .start, turnId: "../x", kind: "text", text: "hi"),
                                                              client: nil))
        #expect(refused.refusal?.status == 400)
        let noToken = TurnRelay.Answer(dictionary: host.start(.init(op: .start, turnId: "abc-1", kind: "text", text: "hi"),
                                                              client: nil))
        #expect(noToken.refusal?.status == 401)
        // An utterance whose recording never arrived.
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        let missing = TurnRelay.Answer(dictionary: host.start(.init(op: .start, turnId: "abc-2", kind: "audio"), client: client))
        #expect(missing.refusal?.status == 400)
        // A slow Mac: pulls wait and say "not yet" until it answers.
        _ = try await bridge.control("assistant", ["delay": 1.2])
        let started = TurnRelay.Answer(dictionary: host.start(.init(op: .start, turnId: "abc-3", kind: "text", text: "hi",
                                                                    stream: true), client: client))
        #expect(started.reply?.ok == true)
        let (waiting, piece) = await host.pull("abc-3", offset: 0, wait: 0.3)
        #expect(waiting.status == nil && piece.isEmpty && !waiting.done)
        var offset = 0
        var body = Data()
        var finished = false
        for _ in 0..<200 where !finished {
            let (reply, data) = await host.pull("abc-3", offset: offset, wait: 1.5)
            if reply.status != nil {
                #expect(reply.status == 200)
                #expect(reply.contentType == AssistantStream.contentType)
                #expect(data.count <= TurnRelay.pieceSize)
                body += data
                offset += data.count
                finished = reply.done
            }
        }
        var parser = AssistantStreamParser()
        #expect(try parser.items(body).last.map { if case .event(.done) = $0 { true } else { false } } == true)
        // Pulling the end again is safe.
        let (again, _) = await host.pull("abc-3", offset: offset, wait: 0)
        #expect(again.done && again.offset == offset)
    }
}
#endif
