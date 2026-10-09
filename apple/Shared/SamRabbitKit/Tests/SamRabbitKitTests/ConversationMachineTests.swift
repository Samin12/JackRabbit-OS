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

    /// The warm-up can fail while the audio is still coming up (the microphone prompt, Siri still holding it): it
    /// waits for the audio, then is said (there is no player before), then the conversation ends.
    @Test func aWarmUpThatFailsBeforeTheAudioRunsIsSaidOnceItDoes() {
        var m = Self.machine()
        _ = m.handle(.start, at: t0)
        #expect(m.handle(.warmUpFailed(.unauthorized), at: t0).isEmpty)
        #expect(m.handle(.warmUpFailed(.unreachable), at: t0).isEmpty) // the first one is said
        #expect(m.phase == .starting)
        #expect(m.exchanges.isEmpty)
        #expect(m.handle(.audioReady, at: t0 + 3) ==
                [.closeMic, .speak("Your watch needs to reconnect. Open SamRabbit on your iPhone."), .haptic(.failure)])
        #expect(m.phase == .thinking)
        #expect(m.exchanges.last?.say == "Your watch needs to reconnect. Open SamRabbit on your iPhone.")
        _ = m.handle(.playing, at: t0 + 3)
        #expect(m.phase == .speaking)
        #expect(m.handle(.drained, at: t0 + 6) == [.stopPlayback, .closeMic, .stopAudio, .announcements(false)])
        #expect(m.phase == .idle)
        #expect(m.problem == "Reconnect through your iPhone")
        #expect(m.handle(.audioReady, at: t0 + 7).isEmpty)
    }

    /// Stop (or the Crown) while the audio comes up, with a failed warm-up waiting: it ends at once, says nothing,
    /// and the next conversation starts clean.
    @Test func stoppedWhileTheAudioStartsNothingIsSaidLater() {
        var m = Self.machine()
        _ = m.handle(.start, at: t0)
        #expect(m.handle(.warmUpFailed(.unavailable("chatgpt_not_connected")), at: t0).isEmpty)
        #expect(m.handle(.stop, at: t0 + 1) == [.stopPlayback, .closeMic, .stopAudio, .announcements(false), .haptic(.stop)])
        #expect(m.phase == .idle)
        #expect(m.problem == nil)
        // The audio that was coming up must not be taken as ready (the engine stops it; the machine ignores it).
        #expect(m.handle(.audioReady, at: t0 + 2).isEmpty)
        #expect(m.phase == .idle)
        #expect(m.handle(.start, at: t0 + 3) == [.startAudio, .warmUp(conversationId: nil)])
        #expect(m.handle(.audioReady, at: t0 + 3) == [.openMic, .haptic(.start), .announcements(true)])
        #expect(m.phase == .listening)
        #expect(m.exchanges.isEmpty)

        // The Crown while starting: paused; back within the window it starts clean as well.
        var crown = Self.machine()
        _ = crown.handle(.start, at: t0)
        _ = crown.handle(.warmUpFailed(.unreachable), at: t0)
        #expect(crown.handle(.background, at: t0 + 1) == [.stopPlayback, .closeMic, .stopAudio, .announcements(false)])
        #expect(crown.handle(.resume, at: t0 + 30) == [.startAudio, .warmUp(conversationId: nil)])
        #expect(crown.handle(.audioReady, at: t0 + 31) == [.openMic, .haptic(.start), .announcements(true)])

        // The microphone failing while a failed warm-up waits: ends with the microphone's problem, nothing said.
        var denied = Self.machine()
        _ = denied.handle(.start, at: t0)
        _ = denied.handle(.warmUpFailed(.unreachable), at: t0)
        #expect(denied.handle(.audioFailed("Microphone is off"), at: t0 + 1) ==
                [.stopPlayback, .closeMic, .stopAudio, .announcements(false), .haptic(.failure)])
        #expect(denied.problem == "Microphone is off")
    }

    @Test func endsAfterTwoQuietMinutes() {
        var m = listening()
        #expect(m.handle(.tick, at: t0 + 119).isEmpty)
        // A turn that heard words pushes it back (counted from when its reply finished).
        _ = m.handle(.utterance(wav), at: t0 + 100)
        _ = m.handle(.turn("t1", .heard("What's next?")), at: t0 + 101)
        _ = m.handle(.turn("t1", .sayDone("The review.")), at: t0 + 101)
        _ = m.handle(done("t1"), at: t0 + 101)
        #expect(m.handle(.drained, at: t0 + 103).isEmpty == false)
        #expect(m.lastActivity == t0 + 103)
        #expect(m.handle(.tick, at: t0 + 222).isEmpty)
        let effects = m.handle(.tick, at: t0 + 223)
        #expect(effects == [.stopPlayback, .closeMic, .stopAudio, .announcements(false), .end(conversationId: "wc_1"),
                            .haptic(.stop)])
        #expect(m.phase == .idle)
        // Not while a reply plays.
        var busy = listening()
        _ = busy.handle(.utterance(wav), at: t0)
        #expect(busy.handle(.tick, at: t0 + 500).isEmpty)
    }

    /// A café, a car, a TV: speech that is too short, and turns where the Mac heard no words, don't keep the
    /// conversation open. It ends about two minutes after the last words, even if the tick never sees it listening.
    @Test func noiseDoesNotKeepTheConversationOpen() {
        var m = listening()
        // Blips that start "speech" and are dropped.
        for second in stride(from: 10, through: 110, by: 20) {
            _ = m.handle(.speechStarted, at: t0 + TimeInterval(second))
            #expect(m.handle(.discarded, at: t0 + TimeInterval(second) + 0.4).isEmpty)
        }
        #expect(m.lastActivity == t0)
        // Noise long enough to be sent: the Mac hears nothing (an empty transcript), the watch listens again.
        _ = m.handle(.speechStarted, at: t0 + 112)
        _ = m.handle(.utterance(wav), at: t0 + 114)
        _ = m.handle(.turn("t1", .heard("")), at: t0 + 115)
        #expect(m.handle(done("t1"), at: t0 + 115) == [.openMic])
        #expect(m.lastActivity == t0)
        // More noise turns after the two minutes: the first one that finishes then ends it (the tick may only ever
        // see "hearing" or "thinking" in a noisy room).
        _ = m.handle(.speechStarted, at: t0 + 116)
        _ = m.handle(.utterance(wav), at: t0 + 130)
        _ = m.handle(.turn("t2", .heard("  ")), at: t0 + 131)
        #expect(m.handle(done("t2"), at: t0 + 131) == [.stopPlayback, .closeMic, .stopAudio, .announcements(false),
                                                        .end(conversationId: "wc_1"), .haptic(.stop)])
        #expect(m.phase == .idle)

        // Speech dropped as too short after the two minutes ends it at once too.
        var blips = listening()
        _ = blips.handle(.speechStarted, at: t0 + 125)
        #expect(blips.handle(.discarded, at: t0 + 125.3).contains(.end(conversationId: "wc_1")))

        // A microphone that stops delivering mid-speech can't keep it "hearing" forever.
        var stuck = listening()
        _ = stuck.handle(.speechStarted, at: t0 + 1)
        #expect(stuck.handle(.tick, at: t0 + 30).isEmpty)
        #expect(stuck.handle(.tick, at: t0 + 41) == [.openMic])
        #expect(stuck.phase == .listening)
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

    /// The bridge counts an announcement as told once the watch has it: one that waits is never dropped.
    @Test func announcementsAreNeverDropped() {
        let item = Announcement(id: "11", say: "“Deploy” needs your approval.", kind: "needs_you")
        // Queued while someone spoke; the speech was too short: it plays once the watch listens again.
        var m = listening()
        _ = m.handle(.speechStarted, at: t0)
        #expect(m.handle(.announcements([item]), at: t0).isEmpty)
        #expect(m.handle(.discarded, at: t0 + 0.3) ==
                [.closeMic, .send(.init(turnId: "t1", conversationId: "wc_1", input: .announce("11"))),
                 .haptic(.notification)])
        _ = m.handle(.playing, at: t0 + 1)
        _ = m.handle(done("t1"), at: t0 + 1)
        #expect(m.handle(.drained, at: t0 + 3) == [.openMic])

        // Queued during a reply, then a barge-in: said once the conversation has listened quietly for a moment.
        var barge = listening()
        _ = barge.handle(.utterance(wav), at: t0)
        _ = barge.handle(.playing, at: t0)
        _ = barge.handle(.announcements([item]), at: t0 + 0.5)
        _ = barge.handle(.tap, at: t0 + 1)
        #expect(barge.handle(.tick, at: t0 + 2).isEmpty) // he may be about to talk
        #expect(barge.handle(.tick, at: t0 + 3).contains(.send(.init(turnId: "t2", conversationId: "wc_1",
                                                                       input: .announce("11")))))

        // Waiting when the two quiet minutes are up: said instead of ending.
        var quiet = listening()
        _ = quiet.handle(.speechStarted, at: t0 + 119)
        _ = quiet.handle(.announcements([item]), at: t0 + 119.5)
        let effects = quiet.handle(.discarded, at: t0 + 120.2)
        #expect(effects.contains(.send(.init(turnId: "t1", conversationId: "wc_1", input: .announce("11")))))
        #expect(quiet.phase == .thinking)

        // Waiting when he stops: kept, and said first in the next conversation.
        var stopped = listening()
        _ = stopped.handle(.utterance(wav), at: t0)
        _ = stopped.handle(.announcements([item]), at: t0 + 1)
        _ = stopped.handle(.stop, at: t0 + 2)
        #expect(stopped.waitingAnnouncements == 1)
        // A poll that was still on its way when it ended: kept too.
        let late = Announcement(id: "12", say: "“Report” finished.", kind: "done")
        #expect(stopped.handle(.announcements([late]), at: t0 + 3).isEmpty)
        #expect(stopped.waitingAnnouncements == 2)
        _ = stopped.handle(.start, at: t0 + 600)
        let ready = stopped.handle(.audioReady, at: t0 + 601)
        #expect(ready == [.openMic, .haptic(.start), .announcements(true), .closeMic,
                          .send(.init(turnId: "t2", conversationId: nil, input: .announce("11"))), .haptic(.notification)])
        // The new conversation doesn't take them again from its own polls.
        #expect(stopped.handle(.announcements([item, late]), at: t0 + 602).isEmpty)
        #expect(stopped.waitingAnnouncements == 1)

        // The Mac ends the conversation while one waits: said first, then it ends.
        var bye = listening()
        _ = bye.handle(.utterance(wav), at: t0)
        _ = bye.handle(.announcements([item]), at: t0)
        _ = bye.handle(.playing, at: t0)
        _ = bye.handle(done("t1", end: true), at: t0)
        #expect(bye.handle(.drained, at: t0 + 2).contains(.send(.init(turnId: "t2", conversationId: "wc_1",
                                                                        input: .announce("11")))))
        _ = bye.handle(.playing, at: t0 + 2)
        _ = bye.handle(done("t2"), at: t0 + 3)
        #expect(bye.handle(.drained, at: t0 + 4) == [.stopPlayback, .closeMic, .stopAudio, .announcements(false),
                                                       .haptic(.stop)])
        #expect(bye.phase == .idle)
    }

    /// An announcement's own clip the watch can't read (an MP3 from the Claude path): its words are said instead.
    @Test func anAnnouncementClipThatCannotBeReadIsSpoken() {
        var m = listening()
        let clip = AssistantAudio(mime: "audio/mpeg", data: Data([0x49, 0x44, 0x33]))
        let item = Announcement(id: "21", say: "Deploy needs you.", audio: clip, kind: "needs_you")
        #expect(m.handle(.announcements([item]), at: t0) == [.closeMic, .haptic(.notification), .play(clip)])
        #expect(m.handle(.clipFailed, at: t0) == [.speak("Deploy needs you.")])
        _ = m.handle(.playing, at: t0)
        #expect(m.phase == .speaking)
        #expect(m.handle(.drained, at: t0 + 2) == [.openMic])
        #expect(m.exchanges.last?.say == "Deploy needs you.")
        // Nothing to say: just listen again.
        let silent = Announcement(id: "22", say: "", audio: clip)
        _ = m.handle(.announcements([silent]), at: t0 + 3)
        #expect(m.handle(.clipFailed, at: t0 + 3) == [.openMic])
        // Not a clip that plays: ignored.
        #expect(m.handle(.clipFailed, at: t0 + 4).isEmpty)
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

    /// The Crown during a reply, then straight back to the app: the conversation goes on (listening after the
    /// reply), it doesn't end.
    @Test func comingBackWhileTheReplyPlaysKeepsTheConversation() {
        for back in [ConversationMachine.Event.start, .resume] {
            var m = listening()
            _ = m.handle(.utterance(wav), at: t0)
            _ = m.handle(.playing, at: t0)
            #expect(m.handle(.background, at: t0 + 1).isEmpty)
            #expect(m.handle(back, at: t0 + 2).isEmpty)
            _ = m.handle(done("t1"), at: t0 + 2)
            #expect(m.handle(.drained, at: t0 + 3) == [.openMic], "\(back)")
            #expect(m.phase == .listening)
        }
        // While thinking too.
        var thinking = listening()
        _ = thinking.handle(.utterance(wav), at: t0)
        _ = thinking.handle(.background, at: t0 + 1)
        _ = thinking.handle(.start, at: t0 + 2)
        _ = thinking.handle(.turn("t1", .sayDone("Sure.")), at: t0 + 3)
        #expect(thinking.handle(done("t1"), at: t0 + 3) == [.speak("Sure.")])
        #expect(thinking.handle(.drained, at: t0 + 4) == [.openMic])
        // A fatal failure being said still ends it.
        var fatal = listening()
        _ = fatal.handle(.utterance(wav), at: t0)
        _ = fatal.handle(.turn("t1", .failed(.unreachable)), at: t0)
        _ = fatal.handle(.background, at: t0 + 1)
        _ = fatal.handle(.start, at: t0 + 1.5)
        #expect(fatal.handle(.drained, at: t0 + 2).contains(.stopAudio))
        #expect(fatal.phase == .idle)
    }

    /// A finished reply whose playback never reports the end (a lost route): it doesn't stay on "Speaking".
    @Test func aReplyThatNeverDrainsStillEnds() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        _ = m.handle(.playing, at: t0)
        _ = m.handle(done("t1"), at: t0 + 1)
        #expect(m.handle(.tick, at: t0 + 30).isEmpty)
        #expect(m.handle(.tick, at: t0 + 61) == [.stopPlayback, .openMic])
        #expect(m.phase == .listening)
        // A reply still streaming is not cut.
        var streaming = listening()
        _ = streaming.handle(.utterance(wav), at: t0)
        _ = streaming.handle(.playing, at: t0)
        #expect(streaming.handle(.tick, at: t0 + 300).isEmpty)
    }

    /// Error codes from a refusal or from an `error` event in the stream, to what the conversation does.
    @Test func bridgeErrorCodesMapToFailures() {
        typealias F = ConversationMachine.Failure
        #expect(F(code: "assistant_unavailable") == .unavailable("assistant_unavailable"))
        #expect(F(code: "transcribe_permission", status: 503) == .unavailable("transcribe_permission"))
        #expect(F(code: "unauthorized") == .unauthorized)
        #expect(F(code: "whatever", status: 401) == .unauthorized)
        #expect(F(code: "assistant_busy", status: 409) == .busy)
        #expect(F(code: "not_found", status: 404) == .outdated)
        #expect(F(code: "conversation_not_found", status: 404) == .failed(code: "conversation_not_found", message: ""))
        #expect(F(code: "assistant_timeout", message: "slow", status: 504) == .failed(code: "assistant_timeout", message: "slow"))
        #expect(F(code: "assistant_unavailable").isFatal)
        #expect(!F(code: "assistant_interrupted").isFatal)
        // Tools may have acted before the voice dropped: never "try again".
        #expect(F(code: "assistant_interrupted").spoken == "Sorry, I got cut off partway through that.")
    }

    /// A fatal `error` event mid-stream (the assistant went away on the Mac): said, then the conversation ends.
    @Test func aFatalErrorInTheStreamEndsTheConversation() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        _ = m.handle(.playing, at: t0)
        let effects = m.handle(.turn("t1", .failed(ConversationMachine.Failure(code: "assistant_unavailable"))), at: t0)
        #expect(effects == [.stopPlayback, .closeMic, .speak("The assistant isn't available on your Mac right now."),
                            .haptic(.failure)])
        // The caption says it too, and the exchange is marked failed.
        #expect(m.exchanges.last?.say == "The assistant isn't available on your Mac right now.")
        #expect(m.exchanges.last?.failed == true)
        // The bridge's `done` after its error is ignored.
        #expect(m.handle(done("t1"), at: t0).isEmpty)
        _ = m.handle(.playing, at: t0)
        #expect(m.handle(.drained, at: t0 + 3) == [.stopPlayback, .closeMic, .stopAudio, .announcements(false)])
        #expect(m.problem == "Assistant unavailable on the Mac")
    }

    /// The Mac forgot the conversation (it restarted): said, and the next turn starts a new one.
    @Test func aConversationTheMacForgotStartsANewOne() {
        var m = listening()
        _ = m.handle(.utterance(wav), at: t0)
        let effects = m.handle(.turn("t1", .failed(.failed(code: "conversation_not_found", message: ""))), at: t0)
        #expect(effects == [.closeMic, .speak("Sorry, I lost our conversation. Say that again?"), .haptic(.failure)])
        #expect(m.handle(.drained, at: t0 + 2) == [.openMic])
        #expect(m.handle(.utterance(wav), at: t0 + 3).contains(.send(.init(turnId: "t2", conversationId: nil,
                                                                             input: .audio(wav)))))
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
