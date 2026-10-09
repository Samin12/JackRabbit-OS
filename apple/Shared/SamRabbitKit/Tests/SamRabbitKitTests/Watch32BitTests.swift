import Foundation
import Testing
@testable import SamRabbitKit

/// The Apple Watch hardware is arm64_32: `Int` is 32 bits there, while every simulator (and this Mac) is 64-bit. A
/// value above 2^31 in an `Int` is fine in every test run here and a crash (`Int(someDouble)` traps) or a silently
/// lost value on the wrist. These checks make that visible on a 64-bit run:
///
/// * `Int32Range.violations(in:)` walks a decoded model and lists every `Int` that holds a value a 32-bit `Int`
///   can't: such a field must be `Int64` (epoch milliseconds, cursors, sequence numbers, byte counts, durations).
/// * The integer helpers are generic: the tests run them at `Int32`, the watch's `Int`.
/// * Numbers too big even for 64 bits (1e19) trapped the old `Int(Double)` code on every platform, so those tests
///   would have crashed here too.
enum Int32Range {
    /// "path: value" of every `Int` in `value` (recursively) outside `Int32`'s range.
    static func violations(in value: Any, path: String = "", depth: Int = 0) -> [String] {
        guard depth < 24 else { return [] }
        if let int = value as? Int {
            return Int32(exactly: int) == nil ? ["\(path.isEmpty ? "value" : path): \(int)"] : []
        }
        // Explicitly 64-bit (or floating point, or a date): allowed to be large.
        if value is Int64 || value is UInt64 || value is Double || value is Date || value is Data || value is String {
            return []
        }
        let mirror = Mirror(reflecting: value)
        var found: [String] = []
        for (index, child) in mirror.children.enumerated() {
            let name = child.label ?? "[\(index)]"
            found += violations(in: child.value, path: path.isEmpty ? name : "\(path).\(name)", depth: depth + 1)
        }
        return found
    }
}

/// What the bridge really sends: epoch milliseconds in the sync store (`at`, `lastAt`, `startedAt`, `endedAt`,
/// `nextBefore`), sequence numbers up to 2^53, millisecond durations.
private let nowMs: Int64 = 1_791_484_999_000

@Suite("32-bit Apple Watch (arm64_32)")
struct Watch32BitTests {
    @Test func theAuditCatchesA64BitValueInAnInt() {
        struct Old { var nextBefore: Int?; var cursor: Int64 }
        // Only meaningful where `Int` is 64-bit (here); on the watch such a value can't exist in an `Int`.
        #if _pointerBitWidth(_64)
        #expect(Int32Range.violations(in: Old(nextBefore: Int(nowMs), cursor: nowMs)) == ["nextBefore: 1791484999000"])
        #endif
        #expect(Int32Range.violations(in: Old(nextBefore: 40, cursor: nowMs)).isEmpty)
    }

    @Test func numbersBecomeIntegersWithoutTrapping() {
        // At the watch's `Int` width.
        #expect(JSONNumbers.exact(Double(nowMs), as: Int32.self) == nil)
        #expect(JSONNumbers.exact(2_147_483_647, as: Int32.self) == .max)
        #expect(JSONNumbers.exact(2_147_483_648, as: Int32.self) == nil)
        #expect(JSONNumbers.exact(-2_147_483_648, as: Int32.self) == .min)
        #expect(JSONNumbers.exact(Double(nowMs), as: Int64.self) == nowMs)
        #expect(JSONNumbers.exact(.nan, as: Int64.self) == nil)
        #expect(JSONNumbers.exact(.infinity, as: Int32.self) == nil)
        #expect(JSONNumbers.exact(1e19, as: Int64.self) == nil)
        #expect(JSONNumbers.clamped(Double(nowMs), as: Int32.self) == .max)
        #expect(JSONNumbers.clamped(-1e300, as: Int32.self) == .min)
        #expect(JSONNumbers.clamped(1e19, as: Int64.self) == .max)
        #expect(JSONNumbers.clamped(.nan, as: Int32.self) == 0)
        #expect(JSONNumbers.text(Double(nowMs)) == "1791484999000")
        #expect(JSONNumbers.text(1.5) == "1.5")
        #expect(JSONNumbers.text(1e30) == "1e+30")
        let value: JSONValue = .number(Double(nowMs))
        #expect(value.integer(Int32.self) == nil)
        #expect(value.int64 == nowMs)
        #expect(JSONValue.string("1791484999000").int64 == nowMs)
    }

    /// `R1 conversations`: every millisecond field and the paging cursor, decoded into models whose `Int`s all fit
    /// 32 bits (the old `nextBefore: Int?` was nil on the watch: no older page ever loaded).
    @Test func conversationsWithMillisecondTimestamps() throws {
        let json = """
        {"conversations":[{"conversationId":"c_1","title":"Focus","startedAt":\(nowMs - 60_000),"lastAt":\(nowMs),
          "endedAt":\(nowMs),"live":false,"messageCount":12,"preview":"Done","device":"r1","cursor":9007199254740991}],
         "cursor":9007199254740991,"nextBefore":\(nowMs)}
        """
        let page = try BridgeJSON.decode(ConversationPage.self, from: Data(json.utf8))
        #expect(page.nextBefore == nowMs)
        #expect(page.cursor == 9_007_199_254_740_991)
        let conversation = try #require(page.conversations.first)
        #expect(conversation.cursor == 9_007_199_254_740_991)
        #expect(conversation.lastAt == Date(timeIntervalSince1970: Double(nowMs) / 1000))
        #expect(conversation.messageCount == 12)
        #expect(Int32Range.violations(in: page).isEmpty, "\(Int32Range.violations(in: page))")
    }

    /// Sync events: ids built from `at` (epoch ms) and numeric ids used `String(Int(value))`, which traps on the
    /// watch. Sequence numbers go up to 2^53.
    @Test func syncEventsWithMillisecondTimestampsAndBigSequences() throws {
        let json = """
        {"events":[
          {"type":"message.assistant.delta","conversationId":"c_1","messageId":"m1","seq":4503599627370496,
           "text":"Hel","at":\(nowMs),"cursor":3000000000},
          {"type":"message.assistant.delta","conversationId":"c_1","messageId":"m1","seq":4503599627370497,
           "text":"Hello","at":\(nowMs + 40),"cursor":3000000001},
          {"type":"message.user","conversationId":"c_1","text":"Hi","at":\(nowMs - 1000),"cursor":3000000002},
          {"type":"host.note","id":4294967296,"conversationId":"c_1","text":"note","at":\(nowMs)}],
         "cursor":3000000002,"more":false}
        """
        let page = try BridgeJSON.decode(EventPage.self, from: Data(json.utf8))
        #expect(page.cursor == 3_000_000_002)
        #expect(page.events[0].seq == 4_503_599_627_370_496)
        #expect(page.events[0].cursor == 3_000_000_000)
        #expect(page.events[2].id == "message.user:\(nowMs - 1000)::")
        #expect(page.events[3].id == "4294967296")
        #expect(Int32Range.violations(in: page).isEmpty)
        // The timeline keeps the 64-bit cursor, and the later delta replaces the earlier one (by `seq`).
        var timeline = ConversationTimeline(conversationId: "c_1")
        timeline.apply(page.events)
        #expect(timeline.cursor == 3_000_000_002)
        let assistant = timeline.sorted.compactMap { item -> String? in
            if case .assistant(let text, _, _) = item.content { text } else { nil }
        }
        #expect(assistant == ["Hello"])
        #expect(Int32Range.violations(in: timeline).isEmpty)
        // A stale delta (lower seq) after it is ignored.
        let stale = timeline.apply(SyncEvent(["type": "message.assistant.delta", "conversationId": "c_1", "messageId": "m1",
                                              "seq": .number(4_503_599_627_370_495), "text": "He", "at": .number(Double(nowMs))]))
        #expect(!stale)
    }

    /// The SSE feed's `ready <cursor>` and its event ids.
    @Test func theLiveFeedKeeps64BitCursors() {
        #expect(SyncStreamItem(ServerSentEvent(comment: "ready 3000000000")) == .ready(cursor: 3_000_000_000))
        let event = SyncStreamItem(ServerSentEvent(id: "3000000005", event: "sync", data: #"{"type":"message.user","text":"x"}"#))
        guard case .event(let sync)? = event else {
            Issue.record("not an event")
            return
        }
        #expect(sync.cursor == 3_000_000_005)
    }

    /// The summary the widgets, complications and the watch read every 30 s: R1's `lastSeenAt` and the latest
    /// conversation's `lastAt` are epoch ms.
    @Test func theSummaryWithMillisecondTimestamps() throws {
        let json = """
        {"generatedAt":"2026-10-08T18:44:19Z","mac":{"name":"Mac","online":true},
         "r1":{"lastSeenAt":\(nowMs),"live":true,"liveConversationId":"c_1"},
         "t3":{"available":true,"needsYou":2,"working":1,"threads":[]},
         "calendar":{"available":true,"next":[{"title":"Standup","startsAt":\(nowMs + 600_000),"endsAt":\(nowMs + 1_500_000)}]},
         "latestConversation":{"conversationId":"c_1","lastAt":\(nowMs),"preview":"Done"},
         "journal":{"available":true},"transcribe":{"available":true},
         "assistant":{"available":true,"brain":"realtime","model":"gpt-realtime-2.1","chatgpt":{"connected":true}}}
        """
        let summary = try BridgeJSON.decode(MobileSummary.self, from: Data(json.utf8))
        #expect(summary.r1.lastSeenAt == Date(timeIntervalSince1970: Double(nowMs) / 1000))
        #expect(summary.calendar.next.first?.startsAt == Date(timeIntervalSince1970: Double(nowMs + 600_000) / 1000))
        #expect(summary.t3.needsYou == 2)
        #expect(Int32Range.violations(in: summary).isEmpty)
    }

    /// Counts and pixel sizes stay `Int`: a value that doesn't fit (a broken or hostile payload) is the default, never
    /// a crash. 1e19 doesn't fit even 64 bits: the old `Int(Double)` code trapped on this Mac too.
    @Test func countsThatDontFitAreDefaultsNotCrashes() throws {
        let page = try BridgeJSON.decode(ConversationPage.self, from: Data("""
            {"conversations":[{"conversationId":"c_1","messageCount":1e19,"cursor":1e19}],"cursor":-1e19,"nextBefore":1e300}
            """.utf8))
        #expect(page.conversations.first?.messageCount == 0)
        #expect(page.conversations.first?.cursor == 0)
        #expect(page.cursor == 0)
        #expect(page.nextBefore == nil)
        // 3e9 fits a 64-bit `Int`; on the watch (32-bit) the same field is the default, not a trap.
        let counted = try BridgeJSON.decode(ConversationSummary.self, from: Data(#"{"id":"c","messageCount":3000000000}"#.utf8))
        #if _pointerBitWidth(_64)
        #expect(Int64(counted.messageCount) == 3_000_000_000)
        #else
        #expect(counted.messageCount == 0)
        #endif
        let transcript = try BridgeJSON.decode(Transcript.self, from: Data(#"{"text":"hi","durationMs":3000000000}"#.utf8))
        #expect(transcript.durationMs == 3_000_000_000)
        let huge = try BridgeJSON.decode(Transcript.self, from: Data(#"{"text":"hi","durationMs":1e19}"#.utf8))
        #expect(huge.durationMs == 0)
        let artifact = try BridgeJSON.decode(GeneratedArtifact.self, from: Data(#"{"artifactId":"a","width":1e19,"height":640}"#.utf8))
        #expect(artifact.width == nil && artifact.height == 640)
    }

    /// The assistant's millisecond timings and announcement ids/cursors.
    @Test func assistantTimingsAndAnnouncementIds() throws {
        let done = try AssistantEvent(json: Data("""
            {"type":"done","conversationId":"wc_1","turnId":"t","expectReply":true,"endConversation":false,"brain":"claude",
             "timings":{"stt":3000000000,"agent":3000000000,"tts":3000000000}}
            """.utf8))
        guard case .done(let value) = done else {
            Issue.record("not done")
            return
        }
        #expect(value.timings.stt == 3_000_000_000)
        #expect(value.timings.total == 9_000_000_000)
        #expect(Int32Range.violations(in: value).isEmpty)
        let page = try BridgeJSON.decode(AnnouncementPage.self, from: Data("""
            {"items":[{"id":4294967297,"say":"Deploy finished.","audio":null,"kind":"done","threadId":"t1"}],
             "cursor":\(nowMs)}
            """.utf8))
        #expect(page.items.first?.id == "4294967297")
        #expect(page.cursor == "\(nowMs)")
        #expect(Int32Range.violations(in: page).isEmpty)
    }

    /// A frame length with the top bit set: `Int(byte) << 24` is negative on a 32-bit `Int`, and the slice with a
    /// negative length trapped. Now it is read as a `UInt32` and refused.
    @Test func frameLengthsAreReadAsUnsigned() {
        #expect(AssistantStream.length(0xFF, 0xFF, 0xFF, 0xFF) == UInt32.max)
        #expect(AssistantStream.length(0x80, 0, 0, 1) == 0x8000_0001)
        #expect(AssistantStream.length(0, 0, 0x0C, 0x80) == 3200)
        for header: [UInt8] in [[0x41, 0xFF, 0xFF, 0xFF, 0xFF], [0x4A, 0x80, 0, 0, 0], [0x41, 0, 0x40, 0, 1]] {
            var parser = AssistantStreamParser()
            #expect(throws: AssistantStreamError.self) { _ = try parser.feed(Data(header) + Data(count: 16)) }
        }
        // The largest allowed frame still parses.
        var parser = AssistantStreamParser()
        let frame = AssistantStream.frame(type: AssistantStream.audio, Data(count: AssistantStream.maxPayload))
        #expect((try? parser.feed(frame))?.count == 1)
    }

    /// WAV sizes are UInt32 on the wire: a streamed size of 0xFFFFFFFF, and sizes larger than the data.
    @Test func wavSizesAreUnsigned() throws {
        var wav = WAV.encode(pcm: Data(count: 3200))
        wav.replaceSubrange(40..<44, with: [0xFF, 0xFF, 0xFF, 0xFF])
        #expect(try WAV.decode(wav).pcm.count == 3200)
        var odd = WAV.encode(pcm: Data(count: 3200))
        odd.replaceSubrange(16..<20, with: [0xF0, 0xFF, 0xFF, 0xFF]) // a "fmt " chunk that claims 4 GB
        #expect(throws: WAV.Problem.self) { _ = try WAV.decode(odd) }
    }

    /// "in N min" from a date years away (a bad timestamp) used to convert seconds / 60 into an `Int`.
    @Test func farAwayDatesFormatWithoutTrapping() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        #expect(Formatting.until(now.addingTimeInterval(25 * 60), now: now) == "in 25 min")
        #expect(Formatting.until(now.addingTimeInterval(2 * 3600 + 600), now: now) == "in 2 h 10 min")
        #expect(!Formatting.until(Date(timeIntervalSince1970: 1e15), now: now).isEmpty)
        #expect(!Formatting.until(.distantFuture, now: now).isEmpty)
    }
}

@Suite("Playback jitter buffer")
struct PlaybackJitterBufferTests {
    let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    /// 100 ms of 16 kHz PCM16 (what the realtime bridge sends per frame).
    let frame = Data(count: 3200)

    @Test func waitsForTwoHundredMillisecondsThenPassesThrough() {
        var buffer = PlaybackJitterBuffer()
        #expect(buffer.add(frame, at: t0).isEmpty)
        #expect(buffer.isHolding && abs(buffer.heldSeconds - 0.1) < 0.001)
        #expect(buffer.deadline == t0.addingTimeInterval(0.35))
        #expect(buffer.add(frame, at: t0 + 0.1) == [frame, frame])
        #expect(buffer.primed && !buffer.isHolding)
        #expect(buffer.add(frame, at: t0 + 0.2) == [frame]) // straight through now
        #expect(buffer.add(Data(), at: t0 + 0.2).isEmpty)
    }

    @Test func aSlowStreamStartsAfterTheLongestWait() {
        var buffer = PlaybackJitterBuffer()
        #expect(buffer.add(frame, at: t0).isEmpty)
        #expect(buffer.tick(at: t0 + 0.2).isEmpty)
        #expect(buffer.tick(at: t0 + 0.35) == [frame])
        #expect(buffer.primed)
        // Or a frame arriving after the deadline releases everything.
        var late = PlaybackJitterBuffer()
        _ = late.add(frame, at: t0)
        #expect(late.add(frame, at: t0 + 0.5) == [frame, frame])
    }

    @Test func theEndOfTheStreamPlaysWhatWaitsAndAShortReplyIsNotLost() {
        var buffer = PlaybackJitterBuffer()
        let short = Data(count: 1600) // 50 ms: a reply shorter than the buffer
        #expect(buffer.add(short, at: t0).isEmpty)
        #expect(buffer.finish() == [short])
        #expect(!buffer.primed && !buffer.isHolding)
        #expect(buffer.finish().isEmpty)
    }

    @Test func runningDryBuffersAgainAndResetDrops() {
        var buffer = PlaybackJitterBuffer()
        _ = buffer.add(frame, at: t0)
        _ = buffer.add(frame, at: t0 + 0.1)
        buffer.underrun()
        #expect(!buffer.primed)
        #expect(buffer.add(frame, at: t0 + 1).isEmpty)
        buffer.underrun() // something waits: not an underrun of the buffer
        #expect(buffer.isHolding)
        buffer.reset()
        #expect(!buffer.isHolding && buffer.deadline == nil && !buffer.primed)
    }
}

@Suite("Turn timeouts")
struct TurnTimeoutTests {
    /// The stream may be quiet for a long tool call (the bridge pings about every 15 s, an older one not at all):
    /// 90 s before the watch gives up; the status line must still come within 50 s, so a Mac out of reach falls back
    /// to the iPhone without the whole wait.
    @Test func streamsMayStayQuietForNinetySeconds() {
        let streamed = AssistantTurnRequest(turnId: "t", conversationId: nil, input: .text("hi"))
        #expect(streamed.timeout == 90)
        #expect(streamed.headTimeout == 50)
        #expect(streamed.bridgeRequest.timeout == 90)
        let buffered = AssistantTurnRequest(turnId: "t", conversationId: nil, input: .text("hi"), stream: false)
        #expect(buffered.timeout == 90 && buffered.headTimeout == 90)
    }

    @Test func pingsAreSkipped() throws {
        var parser = AssistantStreamParser()
        let body = AssistantStream.frame(.heard("Draft it")) + AssistantStream.frame(.unknown("ping"))
            + AssistantStream.audioFrame(Data(count: 320)) + AssistantStream.frame(.unknown("ping"))
            + AssistantStream.frame(.unknown("something.new"))
        let items = try parser.items(body)
        #expect(items == [.event(.heard("Draft it")), .audio(Data(count: 320)), .event(.unknown("something.new"))])
    }
}
