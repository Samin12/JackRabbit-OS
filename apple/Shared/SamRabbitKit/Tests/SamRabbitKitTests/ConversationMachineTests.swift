import Foundation
import Synchronization
import Testing
@testable import SamRabbitKit

/// The watch's conversation rules (`ConversationMachine`), event by event.
@Suite("Conversation")
struct ConversationMachineTests {
    /// Turn ids t1, t2, ... in order.
    static func machine(idle: TimeInterval = 120) -> ConversationMachine {
        let counter = Mutex(0)
        return ConversationMachine(idleTimeout: idle) {
            counter.withLock { value in
                value += 1
                return "t\(value)"
            }
        }
    }

    let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    let wav = WAV.encode(pcm: Data(count: 32_000))

    func done(_ id: String, end: Bool = false, interrupted: Bool = false, conversation: String = "wc_1") -> ConversationMachine.Event {
        .turn(id, .done(AssistantDone(conversationId: conversation, turnId: id, expectReply: !end, endConversation: end,
                                      interrupted: interrupted, brain: .realtime)))
    }

    /// Started, warmed up, listening.
    func listening() -> ConversationMachine {
        var m = Self.machine()
        #expect(m.handle(.start, at: t0) == [.startAudio, .warmUp(conversationId: nil)])
        #expect(m.phase == .starting)
        #expect(m.handle(.audioReady, at: t0) == [.openMic, .haptic(.start), .announcements(true)])
        #expect(m.handle(.session(AssistantSession(conversationId: "wc_1", brain: .realtime)), at: t0).isEmpty)
        #expect(m.phase == .listening)
        #expect(m.conversationId == "wc_1")
        return m
    }

    @Test func listensThinksSpeaksAndListensAgain() {
        var m = listening()
        #expect(m.handle(.speechStarted, at: t0).isEmpty)
        #expect(m.phase == .hearing)
        let sent = m.handle(.utterance(wav), at: t0)
        #expect(sent == [.closeMic, .send(.init(turnId: "t1", conversationId: "wc_1", input: .audio(wav))), .haptic(.click)])
        #expect(m.phase == .thinking)
        #expect(m.currentTurnId == "t1")
        #expect(m.handle(.turn("t1", .heard("What's next?")), at: t0).isEmpty)
        #expect(m.handle(.turn("t1", .sayDelta("Next is")), at: t0).isEmpty)
        #expect(m.live?.say == "Next is")
        #expect(m.handle(.playing, at: t0).isEmpty) // the first frame plays
        #expect(m.phase == .speaking)
        _ = m.handle(.turn("t1", .sayDelta(" the review.")), at: t0)
        _ = m.handle(.turn("t1", .sayDone("Next is the review.")), at: t0)
        _ = m.handle(.turn("t1", .action(AssistantAction(kind: "t3.started", title: "Notes"))), at: t0)
        _ = m.handle(.turn("t1", .card(AssistantCard(title: "Up next", body: "Review at 3"))), at: t0)
        // The stream ended while audio still plays: wait for it.
        #expect(m.handle(done("t1"), at: t0).isEmpty)
        #expect(m.phase == .speaking)
        #expect(m.expectReply)
        // Played out: listen again by itself.
        #expect(m.handle(.drained, at: t0 + 4) == [.openMic])
        #expect(m.phase == .listening)
        #expect(m.lastActivity == t0 + 4)
        let exchange = m.exchanges.last
        #expect(exchange?.heard == "What's next?")
        #expect(exchange?.say == "Next is the review.")
        #expect(exchange?.actions.count == 1)
        #expect(exchange?.cards == [AssistantCard(title: "Up next", body: "Review at 3")])
    }

    /// Audio that arrives slower than it plays runs dry in between: that isn't the end of the reply.
    @Test func aGapInTheAudioIsNotTheEnd() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        _ = m.handle(.playing, at: t0)
        #expect(m.handle(.drained, at: t0).isEmpty)
        #expect(m.phase == .speaking)
        _ = m.handle(.playing, at: t0)
        _ = m.handle(.drained, at: t0)
        #expect(m.handle(done("t1"), at: t0) == [.openMic]) // ran dry and the stream is done
    }

    @Test func aReplyWithoutAudioIsSpokenByTheWatch() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        _ = m.handle(.turn("t1", .sayDone("It's sunny.")), at: t0)
        #expect(m.handle(done("t1"), at: t0) == [.speak("It's sunny.")])
        _ = m.handle(.playing, at: t0)
        #expect(m.phase == .speaking)
        #expect(m.handle(.drained, at: t0) == [.openMic])
    }

    @Test func nothingHeardKeepsListening() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        #expect(m.handle(done("t1"), at: t0) == [.openMic])
        #expect(m.phase == .listening)
        // A cough: speech that was too short.
        _ = m.handle(.speechStarted, at: t0)
        #expect(m.handle(.discarded, at: t0).isEmpty)
        #expect(m.phase == .listening)
    }

    @Test func theMacCanEndTheConversation() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        _ = m.handle(.playing, at: t0)
        _ = m.handle(done("t1", end: true), at: t0)
        let effects = m.handle(.drained, at: t0)
        #expect(effects == [.stopPlayback, .closeMic, .stopAudio, .announcements(false), .haptic(.stop)])
        #expect(m.phase == .idle)
        #expect(m.problem == nil)
    }

    @Test func aTapWhileSpeakingBargesIn() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        _ = m.handle(.playing, at: t0)
        let effects = m.handle(.tap, at: t0 + 1)
        #expect(effects == [.cancel(turnId: "t1", conversationId: "wc_1"), .stopPlayback, .openMic, .haptic(.click)])
        #expect(m.phase == .listening)
        #expect(m.exchanges.last?.interrupted == true)
        // What the cancelled turn still sends is ignored.
        #expect(m.handle(.turn("t1", .sayDelta("more")), at: t0).isEmpty)
        #expect(m.handle(done("t1", interrupted: true), at: t0).isEmpty)
        #expect(m.phase == .listening)
        // The next utterance is a new turn in the same conversation.
        let next = m.handle(.utterance(wav), at: t0)
        #expect(next.contains(.send(.init(turnId: "t2", conversationId: "wc_1", input: .audio(wav)))))
    }

    @Test func aTapWhileThinkingCancelsAndAWhileHearingSendsNow() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        #expect(m.handle(.tap, at: t0) == [.cancel(turnId: "t1", conversationId: "wc_1"), .stopPlayback, .openMic,
                                            .haptic(.click)])
        _ = m.handle(.speechStarted, at: t0)
        #expect(m.handle(.tap, at: t0) == [.flushMic])
        // Listening with nobody speaking: a tap does nothing.
        _ = m.handle(.discarded, at: t0)
        #expect(m.handle(.tap, at: t0).isEmpty)
    }

    /// A Mac out of reach: the watch says so with its own voice and a haptic, then the conversation ends.
    @Test func aMacOutOfReachIsSaidThenItEnds() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        let effects = m.handle(.turn("t1", .failed(.unreachable)), at: t0)
        #expect(effects == [.closeMic, .speak("I can't reach your Mac right now."), .haptic(.failure)])
        _ = m.handle(.playing, at: t0)
        let ended = m.handle(.drained, at: t0)
        #expect(ended == [.stopPlayback, .closeMic, .stopAudio, .announcements(false)]) // no /end: the Mac is away
        #expect(m.phase == .idle)
        #expect(m.problem == "Can't reach your Mac")
        #expect(m.exchanges.last?.failed == true)
    }

    @Test func aFailedTurnIsSaidAndTheConversationGoesOn() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        let effects = m.handle(.turn("t1", .failed(.failed(code: "assistant_timeout", message: ""))), at: t0)
        #expect(effects == [.closeMic, .speak("Sorry, that didn't work. Try again."), .haptic(.failure)])
        _ = m.handle(.playing, at: t0)
        #expect(m.handle(.drained, at: t0) == [.openMic])
        #expect(m.phase == .listening)
    }

    @Test func aWarmUpThatCannotReachTheMacSaysSoAtOnce() {
        var m = Self.machine()
        _ = m.handle(.start, at: t0)
        _ = m.handle(.audioReady, at: t0)
        // An older bridge without the session route: no matter.
        #expect(m.handle(.warmUpFailed(.failed(code: "not_found", message: "")), at: t0).isEmpty)
        let effects = m.handle(.warmUpFailed(.unreachable), at: t0)
        #expect(effects == [.closeMic, .speak("I can't reach your Mac right now."), .haptic(.failure)])
        _ = m.handle(.drained, at: t0) // the voice failed too: still ends
        #expect(m.phase == .idle)
        #expect(m.problem == "Can't reach your Mac")
    }

    @Test func endsAfterTwoQuietMinutes() {
        var m = listening()
        #expect(m.handle(.tick, at: t0 + 119).isEmpty)
        // Speech pushes it back.
        _ = m.handle(.speechStarted, at: t0 + 100)
        _ = m.handle(.discarded, at: t0 + 101)
        #expect(m.handle(.tick, at: t0 + 200).isEmpty)
        let effects = m.handle(.tick, at: t0 + 221)
        #expect(effects == [.stopPlayback, .closeMic, .stopAudio, .announcements(false), .end(conversationId: "wc_1"),
                            .haptic(.stop)])
        #expect(m.phase == .idle)
        // Not while a reply plays.
        var busy = listening()
        _ = busy.handle(.utterance(wav), at: t0)
        #expect(busy.handle(.tick, at: t0 + 500).isEmpty)
    }

    @Test func stopEndsAtOnceAndTellsTheMac() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        let effects = m.handle(.stop, at: t0)
        #expect(effects == [.cancel(turnId: "t1", conversationId: "wc_1"), .stopPlayback, .closeMic, .stopAudio,
                            .announcements(false), .end(conversationId: "wc_1"), .haptic(.stop)])
        #expect(m.phase == .idle)
        #expect(m.handle(.stop, at: t0).isEmpty)
        // A new conversation afterwards starts fresh.
        #expect(m.handle(.tap, at: t0 + 1) == [.startAudio, .warmUp(conversationId: nil)])
        #expect(m.exchanges.isEmpty)
    }

    @Test func announcementsWaitForAPauseThenPlay() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        let done = Announcement(id: "7", say: "“Fix login” finished.", kind: "done", threadId: "t_1")
        #expect(m.handle(.announcements([done]), at: t0).isEmpty) // a reply is on its way
        #expect(m.waitingAnnouncements == 1)
        _ = m.handle(.playing, at: t0)
        _ = m.handle(self.done("t1"), at: t0)
        // Played out: the announcement is next, as a turn in the conversation's voice.
        let effects = m.handle(.drained, at: t0)
        #expect(effects == [.closeMic, .send(.init(turnId: "t2", conversationId: "wc_1", input: .announce("7"))),
                            .haptic(.notification)])
        #expect(m.exchanges.last?.kind == .announcement)
        #expect(m.exchanges.last?.say == "“Fix login” finished.")
        _ = m.handle(.playing, at: t0)
        _ = m.handle(self.done("t2"), at: t0)
        #expect(m.handle(.drained, at: t0) == [.openMic])
        // The same announcement again is ignored; one with its own clip plays it.
        #expect(m.handle(.announcements([done]), at: t0).isEmpty)
        let clip = AssistantAudio(mime: "audio/mpeg", data: Data([1, 2, 3]))
        let withAudio = Announcement(id: "8", say: "Deploy needs you.", audio: clip, kind: "needs_you")
        #expect(m.handle(.announcements([withAudio]), at: t0) == [.closeMic, .haptic(.notification), .play(clip)])
        _ = m.handle(.playing, at: t0)
        #expect(m.handle(.drained, at: t0) == [.openMic])
    }

    @Test func anAnnouncementWhoseTurnFailsIsSaidByTheWatch() {
        var m = listening()
        let item = Announcement(id: "9", say: "Deploy failed.", kind: "error")
        _ = m.handle(.announcements([item]), at: t0)
        #expect(m.handle(.turn("t1", .failed(.outdated)), at: t0) == [.speak("Deploy failed.")])
        _ = m.handle(.playing, at: t0)
        #expect(m.handle(.drained, at: t0) == [.openMic])
        #expect(m.phase == .listening)
    }

    /// Wrist down keeps going; the Crown (the app leaves) finishes the reply and pauses; back within three minutes
    /// it continues the same conversation.
    @Test func leavingFinishesTheReplyAndComingBackContinues() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        _ = m.handle(.playing, at: t0)
        #expect(m.handle(.background, at: t0).isEmpty)
        _ = m.handle(done("t1"), at: t0)
        let paused = m.handle(.drained, at: t0)
        #expect(paused == [.stopPlayback, .closeMic, .stopAudio, .announcements(false)])
        #expect(m.phase == .idle)
        #expect(m.handle(.resume, at: t0 + 60) == [.startAudio, .warmUp(conversationId: "wc_1")])
        #expect(m.exchanges.count == 1)
        _ = m.handle(.audioReady, at: t0 + 60)
        // Left while listening: pauses now; too late to continue after the window.
        _ = m.handle(.background, at: t0 + 61)
        #expect(m.phase == .idle)
        #expect(m.handle(.resume, at: t0 + 61 + 200).isEmpty)
        // Opening the app again then starts a new one.
        #expect(m.handle(.start, at: t0 + 61 + 200) == [.startAudio, .warmUp(conversationId: nil)])
    }

    @Test func aCallOrSiriPausesTheConversation() {
        var m = listening()
        let effects = m.handle(.interrupted, at: t0)
        #expect(effects == [.stopPlayback, .closeMic, .stopAudio, .announcements(false), .haptic(.retry)])
        #expect(m.problem == "Paused. Tap to talk.")
        // A tap goes on with the same conversation.
        #expect(m.handle(.tap, at: t0 + 10) == [.startAudio, .warmUp(conversationId: "wc_1")])
        #expect(m.problem == nil)
    }

    @Test func theMicrophoneFailingEndsIt() {
        var m = Self.machine()
        _ = m.handle(.start, at: t0)
        let effects = m.handle(.audioFailed("Microphone is off"), at: t0)
        #expect(effects == [.stopPlayback, .closeMic, .stopAudio, .announcements(false), .haptic(.failure)])
        #expect(m.problem == "Microphone is off")
        #expect(m.handle(.audioReady, at: t0).isEmpty)
    }

    @Test func theFirstTurnNamesTheConversationWhenTheWarmUpDidNot() {
        var m = Self.machine()
        _ = m.handle(.start, at: t0)
        _ = m.handle(.audioReady, at: t0)
        let sent = m.handle(.utterance(wav), at: t0)
        #expect(sent.contains(.send(.init(turnId: "t1", conversationId: nil, input: .audio(wav)))))
        _ = m.handle(done("t1", conversation: "wc_new"), at: t0)
        #expect(m.conversationId == "wc_new")
        #expect(m.brain == .realtime)
    }

    @Test func keepsTheLastTwelveExchanges() {
        var m = listening()
        for index in 1...15 {
            _ = m.handle(.utterance(wav), at: t0)
            _ = m.handle(.turn("t\(index)", .heard("q\(index)")), at: t0)
            _ = m.handle(done("t\(index)"), at: t0)
        }
        #expect(m.exchanges.count == 12)
        #expect(m.exchanges.first?.heard == "q4")
        #expect(m.exchanges.last?.heard == "q15")
    }
}
