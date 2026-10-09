import Foundation
import Testing
@testable import SamRabbitKit

/// The streaming turn protocol shared with the Mac bridge (CONTRACTS-WAVE5): frames, events, the buffered answer,
/// announcements and the request's shape.
@Suite("Assistant protocol")
struct AssistantProtocolTests {
    static let done = AssistantDone(conversationId: "wc_1234", turnId: "t-1", expectReply: true, endConversation: false,
                                    interrupted: false, brain: .realtime,
                                    timings: AssistantTimings(stt: 180, firstAudio: 640, total: 2100))

    static var sampleStream: Data {
        var data = Data()
        data += AssistantStream.frame(.heard("What's next?"))
        data += AssistantStream.frame(.sayDelta("Next is"))
        data += AssistantStream.audioFrame(Data(repeating: 0x11, count: 5120))
        data += AssistantStream.frame(.sayDelta(" the review."))
        data += AssistantStream.audioFrame(Data(repeating: 0x22, count: 3200))
        data += AssistantStream.frame(.sayDone("Next is the review."))
        data += AssistantStream.frame(.action(AssistantAction(kind: "t3.started", title: "Notes", threadId: "t_9")))
        data += AssistantStream.frame(.card(AssistantCard(title: "Up next", body: "Review at 3")))
        data += AssistantStream.frame(.done(done))
        return data
    }

    static let expectedItems: [AssistantStreamItem] = [
        .event(.heard("What's next?")), .event(.sayDelta("Next is")), .audio(Data(repeating: 0x11, count: 5120)),
        .event(.sayDelta(" the review.")), .audio(Data(repeating: 0x22, count: 3200)),
        .event(.sayDone("Next is the review.")),
        .event(.action(AssistantAction(kind: "t3.started", title: "Notes", threadId: "t_9"))),
        .event(.card(AssistantCard(title: "Up next", body: "Review at 3"))), .event(.done(done)),
    ]

    @Test func aFrameIsTypeThenBigEndianLengthThenPayload() {
        let frame = AssistantStream.frame(type: 0x4A, Data("{\"type\":\"heard\",\"text\":\"hi\"}".utf8))
        #expect(frame[0] == UInt8(ascii: "J"))
        #expect(Array(frame[1..<5]) == [0, 0, 0, 28])
        #expect(frame.count == 5 + 28)
        let audio = AssistantStream.audioFrame(Data(count: 70_000))
        #expect(audio[0] == UInt8(ascii: "A"))
        #expect(Array(audio[1..<5]) == [0x00, 0x01, 0x11, 0x70]) // 70 000 = 0x011170
    }

    @Test func readsTheStreamWhole() throws {
        var parser = AssistantStreamParser()
        #expect(try parser.items(Self.sampleStream) == Self.expectedItems)
        #expect(parser.pending == 0)
    }

    /// The network splits the body anywhere: every split point gives the same items.
    @Test func readsTheStreamSplitAnywhere() throws {
        let stream = Self.sampleStream
        for split in stride(from: 1, to: stream.count, by: 37) {
            var parser = AssistantStreamParser()
            let items = try parser.items(stream.prefix(split)) + parser.items(stream.dropFirst(split))
            #expect(items == Self.expectedItems, "split at \(split)")
        }
        // One byte at a time.
        var parser = AssistantStreamParser()
        var items: [AssistantStreamItem] = []
        for byte in stream { items += try parser.items(Data([byte])) }
        #expect(items == Self.expectedItems)
    }

    @Test func skipsUnknownFramesAndEventsAndRefusesHugeOnes() throws {
        var parser = AssistantStreamParser()
        var data = AssistantStream.frame(type: UInt8(ascii: "X"), Data("future".utf8))
        data += AssistantStream.frame(type: AssistantStream.event, Data(#"{"type":"tool.progress","name":"t3"}"#.utf8))
        data += AssistantStream.frame(type: AssistantStream.event, Data("not json".utf8))
        data += AssistantStream.audioFrame(Data())
        data += AssistantStream.frame(.heard("ok"))
        #expect(try parser.items(data) == [.event(.unknown("tool.progress")), .event(.heard("ok"))])
        var broken = AssistantStreamParser()
        #expect(throws: AssistantStreamError.frameTooLarge(0x7FFF_FFFF)) {
            _ = try broken.feed(Data([0x41, 0x7F, 0xFF, 0xFF, 0xFF]))
        }
    }

    @Test func decodesEveryEventTheBridgeSends() throws {
        func event(_ json: String) throws -> AssistantEvent { try AssistantEvent(json: Data(json.utf8)) }
        #expect(try event(#"{"type":"heard","text":"Block an hour"}"#) == .heard("Block an hour"))
        #expect(try event(#"{"type":"say.delta","text":" Done"}"#) == .sayDelta(" Done"))
        #expect(try event(#"{"type":"say.done","text":"Done."}"#) == .sayDone("Done."))
        #expect(try event(#"{"type":"action","kind":"calendar.created","title":"Focus","eventId":"ev_1"}"#)
            == .action(AssistantAction(kind: "calendar.created", title: "Focus", eventId: "ev_1")))
        #expect(try event(#"{"type":"card","title":"Next","body":"Standup 9:30"}"#)
            == .card(AssistantCard(title: "Next", body: "Standup 9:30")))
        #expect(try event(#"{"type":"error","code":"assistant_timeout","message":"Too slow."}"#)
            == .error(code: "assistant_timeout", message: "Too slow."))
        let done = try event(#"""
            {"type":"done","conversationId":"wc_9","turnId":"t-9","expectReply":false,"endConversation":true,
             "interrupted":true,"brain":"claude","timings":{"stt":200,"firstAudio":null,"total":3100}}
            """#)
        #expect(done == .done(AssistantDone(conversationId: "wc_9", turnId: "t-9", expectReply: false, endConversation: true,
                                            interrupted: true, brain: .claude,
                                            timings: AssistantTimings(stt: 200, firstAudio: nil, total: 3100))))
        // Missing fields default; an unknown brain is .unknown.
        #expect(try event(#"{"type":"done","conversationId":"wc_9","turnId":"t"}"#)
            == .done(AssistantDone(conversationId: "wc_9", turnId: "t")))
        #expect(throws: AssistantStreamError.invalidEvent) { _ = try event("[1,2]") }
        // What the watch writes is what it reads.
        for value in Self.expectedItems.compactMap({ if case .event(let e) = $0 { e } else { nil } }) {
            #expect(try AssistantEvent(json: value.json) == value)
        }
    }

    /// The buffered answer (no stream Accept, the Claude fallback): a 16 kHz WAV plays like stream frames,
    /// anything else (MP3) as a whole clip.
    @Test func turnsTheBufferedAnswerIntoTheSameItems() throws {
        let pcm = PCM16.data((0..<1600).map { Int16($0 % 200) })
        let wav = WAV.encode(pcm: pcm)
        let json = """
            {"conversationId":"wc_5","turnId":"t-5","heard":"Hi","say":"Hello there.",
             "audio":{"mime":"audio/wav","b64":"\(wav.base64EncodedString())"},"expectReply":false,
             "endConversation":false,"actions":[{"kind":"t3.started","title":"Notes","threadId":"t_1"}],
             "timings":{"stt":100,"agent":800,"tts":300}}
            """
        let reply = try BridgeJSON.decode(AssistantReply.self, from: Data(json.utf8))
        #expect(reply.timings.total == 1200)
        #expect(reply.items == [
            .event(.heard("Hi")), .audio(pcm), .event(.sayDone("Hello there.")),
            .event(.action(AssistantAction(kind: "t3.started", title: "Notes", threadId: "t_1"))),
            .event(.done(AssistantDone(conversationId: "wc_5", turnId: "t-5", timings: reply.timings))),
        ])
        let mp3 = Data([0xFF, 0xFB, 0x90, 0x64, 1, 2, 3])
        let claude = try BridgeJSON.decode(AssistantReply.self, from: Data("""
            {"conversationId":"wc_5","turnId":"t-6","heard":"Hi","say":"Hey.","audio":{"mime":"audio/mpeg",
             "b64":"\(mp3.base64EncodedString())"},"expectReply":true,"endConversation":false,"actions":[]}
            """.utf8))
        #expect(claude.items[1] == .clip(AssistantAudio(mime: "audio/mpeg", data: mp3)))
        // Nothing heard: no audio, keep listening.
        let noise = try BridgeJSON.decode(AssistantReply.self, from: Data("""
            {"conversationId":"wc_5","turnId":"t-7","heard":"","say":"","audio":null,"expectReply":true}
            """.utf8))
        #expect(noise.items == [.event(.done(AssistantDone(conversationId: "wc_5", turnId: "t-7", expectReply: true)))])
    }

    @Test func readsAnnouncementsSessionsAndTheSummaryPart() throws {
        let page = try BridgeJSON.decode(AnnouncementPage.self, from: Data("""
            {"items":[{"id":7,"say":"“Fix login” finished.","audio":null,"kind":"done","threadId":"t_1","title":"Fix login"},
                      {"id":"a-8","say":"Deploy needs you.","kind":"needs_you"}, {"say":"no id"}],"cursor":8}
            """.utf8))
        #expect(page.items.map(\.id) == ["7", "a-8"])
        #expect(page.items[0].threadId == "t_1")
        #expect(page.cursor == "8")
        let session = try BridgeJSON.decode(AssistantSession.self, from: Data(#"{"conversationId":"wc_1","brain":"realtime","ready":true}"#.utf8))
        #expect(session == AssistantSession(conversationId: "wc_1", brain: .realtime, ready: true))
        let summary = try BridgeJSON.decode(MobileSummary.self, from: Data("""
            {"assistant":{"available":true,"brain":"realtime","model":"gpt-realtime-2.1","chatgpt":{"connected":true}}}
            """.utf8))
        #expect(summary.assistant == AssistantStatus(available: true, brain: .realtime, model: "gpt-realtime-2.1",
                                                     chatgptConnected: true))
        let older = try BridgeJSON.decode(MobileSummary.self, from: Data("{}".utf8))
        #expect(older.assistant == nil)
        // It survives the summary cache's encode / decode.
        let again = try BridgeJSON.decode(MobileSummary.self, from: try BridgeJSON.encoder().encode(summary))
        #expect(again.assistant == summary.assistant)
    }

    @Test func aTurnCarriesItsHeadersAndBody() throws {
        let wav = WAV.encode(pcm: Data(count: 3200))
        let at = Date(timeIntervalSince1970: 1_791_000_000)
        let audio = AssistantTurnRequest(turnId: "6F1B-1", conversationId: "wc_77", input: .audio(wav), language: "en-US",
                                         deviceTime: at).bridgeRequest
        #expect(audio.method == "POST")
        #expect(audio.path == "/v1/mobile/assistant/turn")
        #expect(audio.accept == "application/x-samrabbit-stream")
        #expect(audio.contentType == "audio/wav")
        #expect(audio.body == wav)
        #expect(audio.query == [URLQueryItem(name: "lang", value: "en-US")])
        #expect(audio.headers["X-SamRabbit-Conversation"] == "wc_77")
        #expect(audio.headers["X-SamRabbit-Turn"] == "6F1B-1")
        #expect(audio.headers["X-SamRabbit-Device-Time"]
            == AssistantTurnRequest.deviceTimeText(at))
        #expect(AssistantTurnRequest.deviceTimeText(at, timeZone: TimeZone(identifier: "America/New_York")!)
            == "2026-10-03T00:00:00-04:00")
        let first = AssistantTurnRequest(turnId: "t-2", conversationId: nil, input: .text("hi"), stream: false).bridgeRequest
        #expect(first.headers["X-SamRabbit-Conversation"] == "")
        #expect(first.accept == "application/json")
        #expect(first.contentType == "application/json")
        let body = try JSONDecoder().decode(JSONValue.self, from: try #require(first.body))
        #expect(body == ["text": "hi", "turnId": "t-2"])
        let announce = AssistantTurnRequest(turnId: "t-3", conversationId: "wc_77", input: .announce("12")).bridgeRequest
        let sent = try JSONDecoder().decode(JSONValue.self, from: try #require(announce.body))
        #expect(sent == ["announce": "12", "conversationId": "wc_77", "turnId": "t-3"])
        // Never relayed as one request (it streams): only through `TurnRelay`.
        #expect(!WatchRelay.Request(audio).allowed)
        #expect(WatchRelay.Request(.post("/v1/mobile/assistant/session", body: [:])).allowed)
        #expect(WatchRelay.Request(.post("/v1/mobile/assistant/cancel", body: [:])).allowed)
        #expect(WatchRelay.Request(.get("/v1/mobile/assistant/announcements")).allowed)
    }
}

@Suite("PCM and WAV")
struct PCMTests {
    @Test func writesACanonicalWAVHeader() throws {
        let wav = WAV.encode(samples: [0, 1, -1, 32767, -32768])
        #expect(wav.count == 44 + 10)
        #expect(wav.prefix(4) == Data("RIFF".utf8))
        #expect(Array(wav[4..<8]) == [46, 0, 0, 0])
        #expect(wav[8..<16] == Data("WAVEfmt ".utf8))
        #expect(Array(wav[16..<36]) == [16, 0, 0, 0, 1, 0, 1, 0, 0x80, 0x3E, 0, 0, 0, 0x7D, 0, 0, 2, 0, 16, 0])
        #expect(wav[36..<40] == Data("data".utf8))
        #expect(Array(wav[40..<44]) == [10, 0, 0, 0])
        #expect(Array(wav[44...]) == [0, 0, 1, 0, 0xFF, 0xFF, 0xFF, 0x7F, 0x00, 0x80])
        let decoded = try WAV.decode(wav)
        #expect(decoded == WAV.Decoded(sampleRate: 16_000, channels: 1, pcm: Data(wav[44...])))
        #expect(PCM16.samples(decoded.pcm) == [0, 1, -1, 32767, -32768])
    }

    @Test func readsWAVsWithOtherChunksAndTheExtensibleFormat() throws {
        var wav = Data("RIFF".utf8) + Data([0, 0, 0, 0]) + Data("WAVE".utf8)
        wav += Data("LIST".utf8) + Data([3, 0, 0, 0, 1, 2, 3, 0]) // odd size, padded
        var fmt = Data([0xFE, 0xFF, 1, 0]) + Data([0xC0, 0x5D, 0, 0]) + Data([0x80, 0xBB, 0, 0]) + Data([2, 0, 16, 0])
        fmt += Data([22, 0, 16, 0, 4, 0, 0, 0]) + Data([1, 0]) + Data(count: 14)
        wav += Data("fmt ".utf8) + Data([UInt8(fmt.count), 0, 0, 0]) + fmt
        wav += Data("data".utf8) + Data([4, 0, 0, 0]) + Data([1, 0, 2, 0])
        let decoded = try WAV.decode(wav)
        #expect(decoded.sampleRate == 24_000)
        #expect(decoded.pcm == Data([1, 0, 2, 0]))
        #expect(AssistantAudio(mime: "audio/wav", data: wav).pcm16k == nil) // not 16 kHz: played as a clip
        #expect(throws: WAV.Problem.notWAV) { _ = try WAV.decode(Data("ID3 mp3".utf8)) }
    }

    @Test func convertsAndMeasures() {
        let samples: [Int16] = [0, 16384, -16384, 32767]
        #expect(PCM16.samples(PCM16.data(samples)) == samples)
        #expect(PCM16.floats(PCM16.data([16384, -32768])) == [0.5, -1])
        #expect(PCM16.samples(fromFloats: [0.5, 2, -2, .nan]) == [16384, 32767, -32768, 0])
        #expect(PCM16.dbfs([Int16](repeating: 0, count: 100)) == -160)
        let full = (0..<1600).map { Int16(32767 * sin(Double($0) * 2 * .pi / 16)) }
        #expect(abs(PCM16.dbfs(full) - -3.01) < 0.05) // a full-scale sine
        #expect(PCM16.seconds(bytes: 32_000) == 1)
    }
}

/// Test signals at 16 kHz.
enum Signal {
    /// A deterministic noise at about `dbfs`.
    static func noise(_ seconds: Double, dbfs: Double, seed: UInt32 = 1) -> [Int16] {
        var state = seed
        let amplitude = pow(10, dbfs / 20) * 32768 * 1.73 // uniform noise: RMS = A / sqrt(3)
        return (0..<Int(seconds * 16_000)).map { _ in
            state = state &* 1_664_525 &+ 1_013_904_223
            let unit = Double(state >> 8) / Double(1 << 24) * 2 - 1
            return Int16(max(-32768, min(32767, unit * amplitude)))
        }
    }

    /// A 200 Hz tone at about `dbfs` (RMS).
    static func tone(_ seconds: Double, dbfs: Double) -> [Int16] {
        let amplitude = pow(10, dbfs / 20) * 32768 * 2.squareRoot()
        return (0..<Int(seconds * 16_000)).map { Int16(amplitude * sin(Double($0) * 2 * .pi * 200 / 16_000)) }
    }

    static func silence(_ seconds: Double) -> [Int16] { [Int16](repeating: 0, count: Int(seconds * 16_000)) }

    /// Talking: syllables of `dbfs` tone with short quiet gaps (a steady tone is not speech: the floor rises to it).
    static func speech(_ seconds: Double, dbfs: Double) -> [Int16] {
        var samples: [Int16] = []
        while Double(samples.count) / 16_000 < seconds {
            samples += tone(0.22, dbfs: dbfs) + noise(0.06, dbfs: -60, seed: UInt32(samples.count))
        }
        return Array(samples.prefix(Int(seconds * 16_000)))
    }
}

@Suite("Speech detection")
struct SpeechDetectorTests {
    func utterances(_ events: [SpeechDetector.Event]) -> [[Int16]] {
        events.compactMap { if case .utterance(let samples) = $0 { samples } else { nil } }
    }

    @Test func findsOneUtteranceWithItsPreRollAndATail() {
        var detector = SpeechDetector()
        var events = detector.feed(Signal.noise(1.0, dbfs: -60))
        #expect(events.isEmpty)
        #expect(abs((detector.floor ?? 0) - -60) < 2)
        events += detector.feed(Signal.tone(1.2, dbfs: -25))
        #expect(events == [.speechStarted])
        #expect(detector.inSpeech)
        events += detector.feed(Signal.noise(0.9, dbfs: -60, seed: 2))
        #expect(utterances(events).isEmpty) // the hangover is a second
        events += detector.feed(Signal.noise(0.3, dbfs: -60, seed: 3))
        let found = utterances(events)
        #expect(found.count == 1)
        // 0.15 s attack inside the 0.3 s pre-roll, the rest of the speech, a 0.25 s tail.
        let seconds = Double(found[0].count) / 16_000
        #expect(abs(seconds - (0.3 + 1.05 + 0.25)) < 0.05, "\(seconds)")
        #expect(!detector.inSpeech)
    }

    @Test func chunkSizesDoNotMatter() {
        let signal = Signal.noise(0.5, dbfs: -60) + Signal.tone(0.8, dbfs: -20) + Signal.noise(1.3, dbfs: -60, seed: 4)
        var whole = SpeechDetector()
        let expected = whole.feed(signal)
        for size in [1, 160, 333, 1600, 4800] {
            var detector = SpeechDetector()
            var events: [SpeechDetector.Event] = []
            var index = 0
            while index < signal.count {
                events += detector.feed(Array(signal[index..<min(signal.count, index + size)]))
                index += size
            }
            #expect(events == expected, "chunks of \(size)")
        }
        #expect(utterances(expected).count == 1)
    }

    @Test func dropsBlipsAndCutsLongSpeech() {
        var detector = SpeechDetector()
        // 0.25 s loud: speech starts after 0.15 s but 0.3 s are needed: discarded.
        let blip = detector.feed(Signal.noise(0.5, dbfs: -60) + Signal.tone(0.25, dbfs: -20) + Signal.noise(1.2, dbfs: -60))
        #expect(blip == [.speechStarted, .discarded])
        // 35 s of talking: cut at 30 s, then a new utterance starts.
        let long = detector.feed(Signal.speech(35, dbfs: -20))
        let found = utterances(long)
        #expect(found.count == 1)
        #expect(abs(Double(found[0].count) / 16_000 - 30) < 0.05)
        #expect(detector.inSpeech)
        // A tap ends it now.
        let flushed = detector.flush()
        #expect(utterances(flushed).count == 1)
        #expect(!detector.inSpeech)
        #expect(detector.flush().isEmpty)
    }

    /// The noise-floor fix: digital silence (-160 dBFS, a microphone starting up) is ignored and the floor never goes
    /// below -80 dBFS, so an ordinary room right after it is not taken for speech.
    @Test func digitalSilenceDoesNotPinTheFloor() {
        var floor = NoiseFloor()
        floor.update(power: -160, step: 0.02)
        floor.update(power: -100, step: 0.02)
        #expect(floor.value == nil)
        floor.update(power: -95, step: 0.02)
        #expect(floor.value == -80)
        floor.update(power: -70, step: 1)
        #expect(floor.value == -78.5) // rises 1.5 dB a second
        floor.update(power: -90, step: 0.02)
        #expect(floor.value == -80)
        floor.update(power: .nan, step: 1)
        #expect(floor.value == -80)

        var detector = SpeechDetector()
        var events = detector.feed(Signal.silence(2))
        #expect(detector.floor == nil)
        // A fan at -45 dBFS: with the floor pinned near -160 it counted as speech; now it is the floor.
        events += detector.feed(Signal.noise(3, dbfs: -45))
        #expect(events.isEmpty)
        #expect((detector.floor ?? -160) > -50)
        // Speech over the fan is still heard, and ends.
        events += detector.feed(Signal.tone(1, dbfs: -20) + Signal.noise(1.3, dbfs: -45, seed: 9))
        #expect(events.first == .speechStarted)
        #expect(utterances(events).count == 1)
    }

    @Test func resetSkipsTheEcho() {
        var detector = SpeechDetector()
        _ = detector.feed(Signal.noise(1, dbfs: -60))
        detector.reset(ignoring: 0.3)
        // The reply's tail is still in the room for 0.3 s: not speech.
        #expect(detector.feed(Signal.tone(0.3, dbfs: -20)).isEmpty)
        #expect(abs((detector.floor ?? 0) - -60) < 2) // the floor is kept
        #expect(detector.feed(Signal.tone(0.2, dbfs: -20)) == [.speechStarted])
    }

    /// The same fix in the voice-only capture's `VoiceActivity`.
    @Test func voiceActivityIgnoresDigitalSilenceToo() {
        var activity = VoiceActivity()
        var t = 0.0
        for _ in 0..<20 { t += 0.05; #expect(activity.feed(power: -160, elapsed: t) == .keepGoing) }
        #expect(activity.floor == nil)
        for _ in 0..<40 { t += 0.05; _ = activity.feed(power: -45, elapsed: t) }
        #expect(!activity.heardSpeech) // a room at -45 dBFS is not speech
        #expect(activity.floor == -45)
        for _ in 0..<20 { t += 0.05; _ = activity.feed(power: -18, elapsed: t) }
        #expect(activity.heardSpeech)
        var decision = VoiceActivity.Decision.keepGoing
        for _ in 0..<40 where decision == .keepGoing { t += 0.05; decision = activity.feed(power: -45, elapsed: t) }
        #expect(decision == .silence)
    }
}
