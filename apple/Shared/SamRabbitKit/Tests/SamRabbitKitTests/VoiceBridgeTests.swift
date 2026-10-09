#if os(macOS)
import AVFoundation
import Foundation
import Testing
@testable import SamRabbitKit

/// A short recording in the watch's own format (`VoiceFormat.recorderSettings`: AAC, 16 kHz mono,
/// 32 kbps), written by AVFoundation's encoder, so the settings themselves are tested too.
enum VoiceClip {
    static func make(seconds: Double = 1.2) throws -> URL {
        let url = VoiceFormat.temporaryFile(in: URL(fileURLWithPath: NSTemporaryDirectory()))
        let format = try #require(AVAudioFormat(standardFormatWithSampleRate: VoiceFormat.sampleRate, channels: 1))
        let file = try AVAudioFile(forWriting: url, settings: VoiceFormat.recorderSettings,
                                   commonFormat: .pcmFormatFloat32, interleaved: false)
        let frames = AVAudioFrameCount(seconds * VoiceFormat.sampleRate)
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        let samples = try #require(buffer.floatChannelData?[0])
        for index in 0..<Int(frames) {
            let t = Double(index) / VoiceFormat.sampleRate
            samples[index] = Float(0.3 * sin(2 * .pi * 220 * t) * (0.5 + 0.5 * sin(2 * .pi * 3 * t)))
        }
        try file.write(from: buffer)
        return url // closed when `file` goes away
    }
}

@Suite("Voice against the fake bridge", .serialized)
struct VoiceBridgeTests {
    @Test func theWatchFormatIsAnM4AOfTheRightLength() throws {
        let url = try VoiceClip.make(seconds: 2)
        defer { try? FileManager.default.removeItem(at: url) }
        let data = try Data(contentsOf: url)
        #expect(data.count > 1000 && data.count < 40_000) // a ~24 KB header (AVAudioFile) and up to 32 kbps
        #expect(data[4..<8] == Data("ftyp".utf8))
        let file = try AVAudioFile(forReading: url)
        #expect(file.fileFormat.sampleRate == 16_000)
        #expect(file.fileFormat.channelCount == 1)
        #expect(abs(Double(file.length) / 16_000 - 2) < 0.1)
    }

    /// The upload's shape as the bridge sees it: `POST /v1/mobile/transcribe?lang=en-US`, `audio/mp4`, the
    /// file's exact bytes, the device's own token.
    @Test func uploadsTheFileAndReadsTheWords() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, account) = try await bridge.pairedClient()
        _ = try await bridge.control("transcribe", ["delay": 0, "text": "Book the design review for Friday"])
        let url = try VoiceClip.make()
        defer { try? FileManager.default.removeItem(at: url) }
        let size = try Data(contentsOf: url).count
        let transcript = try await client.transcribe(file: url, language: "en-US")
        #expect(transcript.text == "Book the design review for Friday")
        #expect(abs(transcript.durationMs - 1200) < 150)
        #expect(transcript.locale == "en-US")
        let upload = try await bridge.transcribeState()["uploads"].array?.last
        #expect(upload?["bytes"].int == size)
        #expect(upload?["contentType"].string == "audio/mp4")
        #expect(upload?["kind"].string == "mp4")
        #expect(upload?["lang"].string == "en-US")
        #expect(upload?["deviceId"].string == account.pairing?.deviceId)
        // In memory too (the phone's relay path), and nothing over 2 MiB leaves the device.
        #expect(try await client.transcribe(audio: Data(contentsOf: url)).text == "Book the design review for Friday")
        await #expect(throws: BridgeError.server(status: 413, code: "audio_too_large", message: "Recordings can be at most 2 MB.",
                                                 retryable: false)) {
            _ = try await client.transcribe(audio: Data(count: VoiceFormat.maxBytes + 1))
        }
        #expect(try await bridge.transcribeState()["uploads"].array?.count == 2)
    }

    @Test func theSummaryAndTheErrorsSayWhenVoiceIsUnavailable() async throws {
        let bridge = try FakeBridgeProcess()
        let (client, _) = try await bridge.pairedClient()
        #expect(try await client.summary().transcribe == TranscribeStatus(available: true))
        let url = try VoiceClip.make(seconds: 0.6)
        defer { try? FileManager.default.removeItem(at: url) }

        func problem() async -> VoiceProblem? {
            do {
                _ = try await client.transcribe(file: url)
                return nil
            } catch let error as BridgeError {
                return VoiceProblem(error)
            } catch {
                return nil
            }
        }
        _ = try await bridge.control("transcribe", ["mode": "unavailable", "delay": 0])
        let summary = try await client.summary()
        #expect(summary.transcribe == TranscribeStatus(available: false, reason: "model_downloading"))
        #expect(VoiceProblem(status: summary.transcribe)?.title == "Your Mac is getting ready")
        #expect(await problem()?.kind == .unavailable)
        _ = try await bridge.control("transcribe", ["mode": "permission"])
        #expect(VoiceProblem(status: try await client.summary().transcribe)?.kind == .permission)
        #expect(await problem()?.kind == .permission)
        _ = try await bridge.control("transcribe", ["mode": "failed"])
        #expect(try await client.summary().transcribe?.available == true)
        #expect(await problem()?.kind == .failed)
        _ = try await bridge.control("transcribe", ["mode": "busy"])
        #expect(await problem()?.kind == .busy)
        _ = try await bridge.control("transcribe", ["mode": "no_speech"])
        #expect(await problem() == .noSpeech)
        _ = try await bridge.control("transcribe", ["mode": "missing"])
        #expect(try await client.summary().transcribe == nil)
        #expect(await problem()?.kind == .outdated)
        _ = try await bridge.control("transcribe", ["mode": "empty"])
        #expect(try await client.transcribe(file: url).text.isEmpty)
        // The bridge reads the container's own bytes: text sent as audio is refused.
        let text = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("voice-\(UUID().uuidString).m4a")
        try Data("not audio at all".utf8).write(to: text)
        defer { try? FileManager.default.removeItem(at: text) }
        _ = try await bridge.control("transcribe", ["mode": "ok"])
        await #expect { _ = try await client.transcribe(file: text) } throws: {
            ($0 as? BridgeError)?.code == "unsupported_audio"
        }
    }

    /// The watch can't reach the Mac: the recording goes to the iPhone in chunks, the phone uploads it with
    /// the watch's own child token and answers with the bridge's words.
    @Test func throughThePhoneInChunksWithTheWatchsToken() async throws {
        let closed = try SilentPort(listening: false)
        let (bridge, relay, watch, context) = try await RelayTests().setUpWithContext(watchHost: closed.host)
        _ = try await bridge.control("transcribe", ["delay": 0, "text": "Reply that the fix is in review"])
        let url = try VoiceClip.make(seconds: 25) // more than one chunk
        defer { try? FileManager.default.removeItem(at: url) }
        let chunks = (try Data(contentsOf: url).count + VoiceRelay.chunkSize - 1) / VoiceRelay.chunkSize
        #expect(chunks >= 2)
        let transcript = try await watch.transcribe(file: url, language: "en-US")
        #expect(transcript.text == "Reply that the fix is in review")
        #expect(relay.state.withLock { $0.relayed } == ["VOICE \(chunks) chunks"])
        #expect(relay.state.withLock { $0.direct } == [false])
        let upload = try await bridge.transcribeState()["uploads"].array?.last
        #expect(upload?["deviceId"].string == context.deviceId)
        #expect(upload?["platform"].string == "watchos")
        #expect(upload?["parentId"].string == relay.phone.pairing?.deviceId)
        #expect(upload?["bytes"].int == (try Data(contentsOf: url)).count)
        #expect(upload?["lang"].string == "en-US")

        // Once the Mac revoked the watch, the phone's upload is refused too.
        try await bridge.revoke(context.deviceId)
        await #expect(throws: BridgeError.unauthorized) { _ = try await watch.transcribe(file: url) }
        // A phone that couldn't reach the Mac either: unreachable, never sent again.
        relay.state.withLock { $0.answerZero = true }
        await #expect { _ = try await watch.transcribe(file: url) } throws: {
            if case .unreachable = $0 as? BridgeError { return true }
            return false
        }
    }

    /// A Mac that took the connection but never answered: transcribing changes nothing, so unlike a new
    /// task the recording does go through the phone after the timeout.
    @Test func aTimeoutGoesThroughThePhone() async throws {
        let hanging = try SilentPort(listening: true)
        let (bridge, relay, watch, _) = try await RelayTests().setUpWithContext(watchHost: hanging.host)
        _ = try await bridge.control("transcribe", ["delay": 0])
        let url = try VoiceClip.make(seconds: 0.8)
        defer { try? FileManager.default.removeItem(at: url) }
        let transcript = try await watch.transcribe(file: url, timeout: 1)
        #expect(transcript.text.hasPrefix("Draft the release notes"))
        #expect(relay.state.withLock { $0.relayed } == ["VOICE 1 chunks"])
    }

    /// Right after a direct failure the phone goes first; when it is out of reach, straight to the Mac.
    @Test func thePhoneFirstWhenPreferredThenDirect() async throws {
        let (bridge, relay, watch, context) = try await RelayTests().setUpWithContext()
        _ = try await bridge.control("transcribe", ["delay": 0])
        let url = try VoiceClip.make(seconds: 0.8)
        defer { try? FileManager.default.removeItem(at: url) }
        relay.state.withLock { $0.prefersRelay = true }
        _ = try await watch.transcribe(file: url)
        #expect(relay.state.withLock { $0.relayed } == ["VOICE 1 chunks"])
        #expect(relay.state.withLock { $0.direct }.isEmpty)
        relay.state.withLock { $0.down = true }
        _ = try await watch.transcribe(file: url)
        #expect(relay.state.withLock { $0.direct } == [true])
        let uploads = try await bridge.transcribeState()["uploads"].array ?? []
        #expect(uploads.count == 2)
        #expect(uploads.allSatisfy { $0["deviceId"].string == context.deviceId })
    }
}

@Suite("Re-pairing", .serialized)
struct RepairTests {
    /// Pairing again with the same Mac retires the old device there (its token and its watch's stop
    /// working); a wrong code keeps the old pairing.
    @Test func pairingAgainWithTheSameMacUnpairsTheOldDevice() async throws {
        let bridge = try FakeBridgeProcess()
        let (_, account) = try await bridge.pairedClient()
        let oldToken = try #require(account.token)
        let oldDevice = try #require(account.pairing?.deviceId)
        let watch = try await account.provisionWatch()
        #expect(try await bridge.deviceIds() == [oldDevice, watch.deviceId])

        let wrong = await BridgeError.capture {
            try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "WRNGCDE2"), deviceName: "x", account: account)
        }
        #expect((try? wrong.get()) == nil)
        #expect(account.token == oldToken)
        #expect(try await bridge.deviceIds() == [oldDevice, watch.deviceId])

        let pairing = try await Pairer.pair(link: PairLink(hosts: [bridge.host], code: "SAMRABBT"), deviceName: "Phone 2",
                                            account: account)
        #expect(pairing.deviceId != oldDevice)
        #expect(try await bridge.deviceIds() == [pairing.deviceId])
        await #expect(throws: BridgeError.unauthorized) {
            _ = try await BridgeClient(hosts: [bridge.host], token: oldToken).summary()
        }
        #expect(try await account.requireClient().summary().t3.available)
    }

    /// Another Mac: the first one keeps its record (it is only forgotten here).
    @Test func pairingWithAnotherMacLeavesTheFirstAlone() async throws {
        let first = try FakeBridgeProcess()
        let second = try FakeBridgeProcess()
        let (_, account) = try await first.pairedClient()
        let oldDevice = try #require(account.pairing?.deviceId)
        _ = try await Pairer.pair(link: PairLink(hosts: [second.host], code: "SAMRABBT"), deviceName: "x", account: account)
        #expect(try await first.deviceIds() == [oldDevice])
        #expect(account.pairing?.hosts == [second.host])
        #expect(!Pairer.sameBridge(BridgePairing(hosts: [first.host], deviceId: "d", bridgeName: nil, bridgeVersion: nil,
                                                 deviceName: "x"), PairLink(hosts: [second.host], code: "SAMRABBT")))
    }
}

@Suite("Fake bridge: unpair reads its body")
struct FakeUnpairTests {
    /// A kept-alive connection stays in step after `POST /v1/mobile/unpair {}` (the body is read like the
    /// real bridge reads it): the next request on the same connection is answered normally.
    @Test func unpairWithABodyOnAKeptAliveConnection() async throws {
        let bridge = try FakeBridgeProcess()
        let (_, account) = try await bridge.pairedClient()
        let token = try #require(account.token)
        let raw = "POST /v1/mobile/unpair HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer \(token)\r\n"
            + "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}"
            + "GET /health HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n"
        let answer = try exchangeRaw(port: bridge.port, raw)
        #expect(answer.contains(#""revoked":1"#))
        #expect(answer.components(separatedBy: "HTTP/1.").count - 1 == 2)
        #expect(answer.contains(#""service":"samrabbit-bridge""#))
    }

    /// Every request's body is read, also where the route doesn't use it (`/__fake/reset {}`): the next
    /// request on the connection is the one that counts (here: the text the voice route answers with).
    @Test func helperBodiesKeepAKeptAliveConnectionInStep() async throws {
        let bridge = try FakeBridgeProcess()
        let text = #"{"text":"Kept in step"}"#
        let raw = "POST /__fake/reset HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}"
            + "POST /__fake/transcribe HTTP/1.1\r\nHost: x\r\nContent-Length: \(text.utf8.count)\r\n"
            + "Connection: close\r\n\r\n\(text)"
        let answer = try exchangeRaw(port: bridge.port, raw)
        #expect(answer.components(separatedBy: "HTTP/1.").count - 1 == 2)
        #expect(try await bridge.transcribeState()["text"].string == "Kept in step")
    }

    /// Sends bytes to 127.0.0.1:port and reads until the server closes.
    func exchangeRaw(port: Int, _ text: String) throws -> String {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        defer { close(fd) }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = UInt16(port).bigEndian
        var timeout = timeval(tv_sec: 5, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard connected == 0 else { throw BridgeError.unreachable("connect") }
        let bytes = Array(text.utf8)
        _ = bytes.withUnsafeBytes { send(fd, $0.baseAddress, bytes.count, 0) }
        var received = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while true {
            let count = recv(fd, &buffer, buffer.count, 0)
            if count <= 0 { break }
            received.append(contentsOf: buffer[0..<count])
        }
        return String(decoding: received, as: UTF8.self)
    }
}
#endif
