import Foundation
import Testing
@testable import SamRabbitKit

/// The watch's voice input in the Kit: the recording's chunks through the iPhone, the phone putting them
/// back together, when a recording ends, and what each failure says.
@Suite("Voice: chunks, activity, problems")
struct VoiceTests {
    // MARK: - Chunks and reassembly

    func bytes(_ count: Int) -> Data { Data((0..<count).map { UInt8(truncatingIfNeeded: $0 &* 31 &+ 7) }) }

    @Test func chunksAreAtMost40KBAndNumbered() {
        let audio = bytes(VoiceRelay.chunkSize * 2 + 1234)
        let chunks = VoiceRelay.chunks(of: audio, id: "rec-1", language: "en-US")
        #expect(chunks.count == 3)
        #expect(chunks.map(\.seq) == [0, 1, 2])
        #expect(chunks.allSatisfy { $0.of == 3 && $0.id == "rec-1" && $0.contentType == "audio/mp4" && $0.language == "en-US" })
        #expect(chunks.allSatisfy { $0.data.count <= 40 * 1024 })
        #expect(chunks.map(\.data.count) == [40960, 40960, 1234])
        #expect(chunks.last?.isLast == true && chunks.first?.isLast == false)
        #expect(chunks.reduce(Data()) { $0 + $1.data } == audio)
        // Exactly one chunk's worth, and nothing at all.
        #expect(VoiceRelay.chunks(of: bytes(VoiceRelay.chunkSize)).count == 1)
        #expect(VoiceRelay.chunks(of: Data()).count == 1)
        // A minute of the watch's AAC (~240 KB) is six messages; the bridge's 2 MiB limit is 52.
        #expect(VoiceRelay.chunks(of: bytes(240_000)).count == 6)
        #expect(VoiceRelay.maxChunks == 52)
    }

    /// The WatchConnectivity dictionary: a small JSON header and the raw bytes (no base64).
    @Test func aChunkSurvivesItsMessage() throws {
        let chunk = VoiceRelay.chunks(of: bytes(50_000), id: "abc-123", language: "fr-FR")[1]
        let message = chunk.message
        #expect(message[VoiceRelay.dataKey] as? Data == chunk.data)
        #expect((message[VoiceRelay.chunkKey] as? Data).map { $0.count < 200 } == true)
        #expect(VoiceRelay.Chunk(message: message) == chunk)
        #expect(VoiceRelay.Chunk(message: [VoiceRelay.chunkKey: Data("{}".utf8)]) == nil)
        #expect(VoiceRelay.Chunk(message: WatchRelay.Request(.get("/v1/mobile/summary")).message) == nil)
    }

    @Test func thePhonePutsTheRecordingBackTogetherInAnyOrder() {
        let audio = bytes(VoiceRelay.chunkSize * 3 + 99)
        let chunks = VoiceRelay.chunks(of: audio, id: "rec-2")
        let assembler = VoiceRelay.Assembler()
        #expect(assembler.add(chunks[2]) == .waiting(received: 1))
        #expect(assembler.add(chunks[0]) == .waiting(received: 2))
        #expect(assembler.add(chunks[0]) == .waiting(received: 2)) // a repeat changes nothing
        #expect(assembler.add(chunks[3]) == .waiting(received: 3))
        #expect(assembler.pending == 1)
        guard case .complete(let upload) = assembler.add(chunks[1]) else {
            Issue.record("not complete")
            return
        }
        #expect(upload.data == audio)
        #expect(upload.id == "rec-2" && upload.contentType == "audio/mp4" && upload.language == nil)
        #expect(assembler.pending == 0)
        // A one-chunk recording is complete at once.
        let single = VoiceRelay.chunks(of: bytes(1000), id: "rec-3", language: "en-US")[0]
        #expect(assembler.add(single) == .complete(.init(id: "rec-3", contentType: "audio/mp4", language: "en-US",
                                                         data: bytes(1000))))
    }

    @Test func thePhoneRefusesWhatIsNotARecording() {
        let assembler = VoiceRelay.Assembler()
        func code(_ outcome: VoiceRelay.Assembler.Outcome) -> String? {
            guard case .refused(let response) = outcome else { return nil }
            return (try? JSONDecoder().decode(JSONValue.self, from: response.body))?["error"]["code"].string
        }
        let good = VoiceRelay.Chunk(id: "r1", seq: 0, of: 2, contentType: "audio/mp4", language: nil, data: bytes(10))
        var chunk = good
        chunk.id = "../etc"
        #expect(code(assembler.add(chunk)) == "invalid_audio")
        chunk = good
        chunk.seq = 2
        #expect(code(assembler.add(chunk)) == "invalid_audio")
        chunk = good
        chunk.of = VoiceRelay.maxChunks + 1
        #expect(code(assembler.add(chunk)) == "invalid_audio")
        chunk = good
        chunk.data = bytes(VoiceRelay.chunkSize + 1)
        #expect(code(assembler.add(chunk)) == "invalid_audio")
        chunk = good
        chunk.contentType = "text/plain"
        #expect(code(assembler.add(chunk)) == "unsupported_audio")
        chunk = good
        chunk.language = "en US; rm"
        #expect(code(assembler.add(chunk)) == "invalid_lang")
        // Pieces that don't belong together drop the recording.
        #expect(assembler.add(good) == .waiting(received: 1))
        var other = good
        other.seq = 1
        other.of = 3
        #expect(code(assembler.add(other)) == "invalid_audio")
        #expect(assembler.pending == 0)
        // No more than 2 MiB in all, whatever the chunks say.
        let big = VoiceRelay.Assembler()
        var refused: String?
        for seq in 0..<VoiceRelay.maxChunks {
            let piece = VoiceRelay.Chunk(id: "huge", seq: seq, of: VoiceRelay.maxChunks, contentType: "audio/mp4",
                                         language: nil, data: bytes(VoiceRelay.chunkSize))
            let outcome = big.add(piece)
            if let refusal = code(outcome) {
                refused = refusal
                break
            }
        }
        #expect(refused == "body_too_large")
        #expect(big.pending == 0)
    }

    @Test func thePhoneForgetsStalledRecordingsAndLimitsHowManyAreOpen() {
        let assembler = VoiceRelay.Assembler()
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        func first(_ id: String) -> VoiceRelay.Chunk {
            VoiceRelay.Chunk(id: id, seq: 0, of: 2, contentType: "audio/mp4", language: nil, data: bytes(10))
        }
        for id in ["a", "b", "c"] { #expect(assembler.add(first(id), now: start) == .waiting(received: 1)) }
        guard case .refused(let busy) = assembler.add(first("d"), now: start) else {
            Issue.record("a fourth recording was taken")
            return
        }
        #expect(busy.status == 503)
        // 90 s later the stalled ones are gone.
        #expect(assembler.add(first("d"), now: start.addingTimeInterval(91)) == .waiting(received: 1))
        #expect(assembler.pending == 1)
    }

    // MARK: - When a recording ends

    /// Quiet room (-55 dBFS), then speech (-22), then quiet again: it stops about 1.5 s after the speech.
    @Test func stopsAfterQuietOnceSpeechWasHeard() {
        var activity = VoiceActivity()
        var t = 0.0
        func run(_ power: Double, for seconds: Double) -> (VoiceActivity.Decision, Double) {
            let end = t + seconds
            while t < end - 1e-9 {
                t += 0.05
                let decision = activity.feed(power: power, elapsed: t)
                if decision != .keepGoing { return (decision, t) }
            }
            return (.keepGoing, t)
        }
        #expect(run(-55, for: 1.0).0 == .keepGoing)
        #expect(!activity.heardSpeech)
        #expect(activity.level < 0.05)
        #expect(run(-22, for: 1.2).0 == .keepGoing)
        #expect(activity.heardSpeech)
        #expect(activity.level > 0.5)
        let (decision, at) = run(-55, for: 3)
        #expect(decision == .silence)
        #expect(abs(at - 3.7) < 0.11) // speech ended at 2.2 s
    }

    @Test func neverStopsForQuietBeforeAnySpeechButStopsAtTheLimit() {
        var activity = VoiceActivity()
        var stopped: (VoiceActivity.Decision, Double)?
        var t = 0.0
        while t < 61, stopped == nil {
            t += 0.05
            let decision = activity.feed(power: -60, elapsed: t)
            if decision != .keepGoing { stopped = (decision, t) }
        }
        #expect(stopped?.0 == .limit)
        #expect(abs((stopped?.1 ?? 0) - 60) < 0.06)
        #expect(!activity.heardSpeech)
    }

    /// A tap on the watch (one loud frame) is not speech; a noisy room still ends after speech.
    @Test func clicksAreNotSpeechAndANoisyRoomStillEnds() {
        var activity = VoiceActivity()
        var t = 0.0
        for _ in 0..<10 { t += 0.05; _ = activity.feed(power: -50, elapsed: t) }
        t += 0.05
        _ = activity.feed(power: -15, elapsed: t)
        for _ in 0..<40 { t += 0.05; #expect(activity.feed(power: -50, elapsed: t) == .keepGoing) }
        #expect(!activity.heardSpeech)

        var noisy = VoiceActivity()
        t = 0
        for _ in 0..<20 { t += 0.05; _ = noisy.feed(power: -38, elapsed: t) } // fan noise
        for _ in 0..<20 { t += 0.05; _ = noisy.feed(power: -18, elapsed: t) } // speech
        #expect(noisy.heardSpeech)
        var decision = VoiceActivity.Decision.keepGoing
        for _ in 0..<40 where decision == .keepGoing { t += 0.05; decision = noisy.feed(power: -37, elapsed: t) }
        #expect(decision == .silence)
    }

    @Test func shortClipsAreDiscardedAndLevelsAreEased() {
        #expect(!VoiceActivity.keeps(duration: 0.39))
        #expect(VoiceActivity.keeps(duration: 0.4))
        #expect(VoiceActivity.level(forPower: -80) == 0)
        #expect(VoiceActivity.level(forPower: 0) == 1)
        #expect(VoiceActivity.level(forPower: -32.5) == 0.5)
        #expect(VoiceActivity.level(forPower: .nan) == 0)
    }

    @Test func theRecordingFormat() {
        let settings = VoiceFormat.recorderSettings
        #expect(settings.count == 5)
        #expect(VoiceFormat.contentType == "audio/mp4")
        #expect(VoiceFormat.languageTag(Locale(identifier: "en_US")) == "en-US")
        #expect(VoiceFormat.languageTag(Locale(identifier: "fr")) == "fr")
        let folder = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("voice-test-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = VoiceFormat.temporaryFile(in: folder)
        #expect(file.lastPathComponent.hasPrefix("voice-") && file.pathExtension == "m4a")
        FileManager.default.createFile(atPath: file.path, contents: Data([1]))
        FileManager.default.createFile(atPath: folder.appendingPathComponent("keep.txt").path, contents: Data([1]))
        VoiceFormat.removeLeftovers(in: folder)
        #expect(!FileManager.default.fileExists(atPath: file.path))
        #expect(FileManager.default.fileExists(atPath: folder.appendingPathComponent("keep.txt").path))
        try? FileManager.default.removeItem(at: folder)
    }

    // MARK: - What a failure says

    func server(_ status: Int, _ code: String, _ message: String = "") -> BridgeError {
        .server(status: status, code: code, message: message, retryable: false)
    }

    @Test func bridgeErrorsBecomeFriendlyProblems() {
        #expect(VoiceProblem(server(503, "transcribe_unavailable", "The Mac is downloading the speech model.")).kind == .unavailable)
        #expect(VoiceProblem(server(503, "transcribe_unavailable", "The Mac is downloading the speech model.")).detail
            == "The Mac is downloading the speech model.")
        #expect(VoiceProblem(server(503, "transcribe_permission")).kind == .permission)
        #expect(VoiceProblem(server(503, "transcribe_busy")).kind == .busy)
        #expect(VoiceProblem(server(502, "transcribe_failed")).kind == .failed)
        #expect(VoiceProblem(server(504, "transcribe_timeout")).kind == .failed)
        #expect(VoiceProblem(server(422, "no_speech")) == .noSpeech)
        for code in ["audio_too_long", "audio_too_large", "body_too_large"] {
            #expect(VoiceProblem(server(413, code)).kind == .tooLong)
        }
        for code in ["unsupported_audio", "invalid_audio"] { #expect(VoiceProblem(server(415, code)).kind == .failed) }
        for code in ["unsupported_language", "invalid_lang"] { #expect(VoiceProblem(server(422, code)).kind == .language) }
        #expect(VoiceProblem(server(404, "not_found")).kind == .outdated)
        #expect(VoiceProblem(server(404, "http_404")).kind == .outdated)
        #expect(VoiceProblem(.unreachable("x")).kind == .unreachable)
        #expect(VoiceProblem(.unauthorized).kind == .unauthorized)
        #expect(VoiceProblem(.notPaired).kind == .unauthorized)
        #expect(VoiceProblem(server(500, "internal_error", "The bridge failed unexpectedly.")).detail
            == "The bridge failed unexpectedly.")
        // Every problem has words and a symbol (the watch never shows a bare code).
        for problem in [VoiceProblem.microphone, .tooShort, .noSpeech, .recorderFailed, VoiceProblem(.cancelled)] {
            #expect(!problem.title.isEmpty && !problem.detail.isEmpty && !problem.symbol.isEmpty)
        }
    }

    @Test func theSummarySaysWhenVoiceIsUnavailable() {
        #expect(VoiceProblem(status: nil) == nil)
        #expect(VoiceProblem(status: TranscribeStatus(available: true)) == nil)
        #expect(VoiceProblem(status: TranscribeStatus(available: false, reason: "loading")) == nil)
        #expect(VoiceProblem(status: TranscribeStatus(available: false, reason: "check_failed")) == nil)
        #expect(VoiceProblem(status: TranscribeStatus(available: false, reason: "permission_denied"))?.kind == .permission)
        #expect(VoiceProblem(status: TranscribeStatus(available: false, reason: "model_downloading"))?.title
            == "Your Mac is getting ready")
        #expect(VoiceProblem(status: TranscribeStatus(available: false, reason: "helper_missing"))?.kind == .unavailable)
        #expect(VoiceProblem(status: TranscribeStatus(available: false, reason: "insufficient_resources"))?.kind == .busy)
        #expect(VoiceProblem(status: TranscribeStatus(available: false))?.kind == .unavailable)
    }

    @Test func decodesTheTranscriptAndTheSummaryPart() throws {
        let transcript = try BridgeJSON.decode(Transcript.self, from: Data(#"""
        {"text":"  Book the review  ","durationMs":2310,"engine":"SpeechTranscriber","locale":"en-US"}
        """#.utf8))
        #expect(transcript == Transcript(text: "Book the review", durationMs: 2310, engine: "SpeechTranscriber", locale: "en-US"))
        let summary = try BridgeJSON.decode(MobileSummary.self, from: Data(#"""
        {"mac":{"name":"Mac"},"transcribe":{"available":false,"reason":"model_missing"}}
        """#.utf8))
        #expect(summary.transcribe == TranscribeStatus(available: false, reason: "model_missing"))
        let older = try BridgeJSON.decode(MobileSummary.self, from: Data(#"{"mac":{"name":"Mac"}}"#.utf8))
        #expect(older.transcribe == nil)
    }

    /// The request line and headers: `POST /v1/mobile/transcribe?lang=`, the audio's own Content-Type.
    @Test func theUploadRequest() {
        let request = BridgeClient.transcribeRequest(language: "en-US", timeout: 55)
        #expect(request.method == "POST")
        #expect(request.path == "/v1/mobile/transcribe")
        #expect(request.query == [URLQueryItem(name: "lang", value: "en-US")])
        #expect(request.contentType == "audio/mp4")
        #expect(request.body == nil)
        #expect(request.authorized)
        #expect(request.timeout == 55)
        #expect(BridgeClient.transcribeRequest(language: nil).query.isEmpty)
        // Never relayed as one message (too big): the watch sends it in chunks instead.
        #expect(!WatchRelay.Request(request).allowed)
    }
}
