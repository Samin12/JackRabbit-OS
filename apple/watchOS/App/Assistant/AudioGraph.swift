import AVFoundation
import Foundation
import os
import SamRabbitKit

private let audioLog = Logger(subsystem: "com.samrabbit.mobile.watchkitapp", category: "audio")

/// Where the conversation's sound comes from: the watch's microphone, or (the simulator, debug builds) a made-up
/// voice. Delivers 16 kHz mono 16-bit samples on the main actor.
@MainActor
protocol ConversationInput: AnyObject {
    /// Whether the microphone may be used (asks the first time).
    func allowed() async -> Bool
    /// Starts delivering samples (the engine is already set up).
    func start(engine: AVAudioEngine, deliver: @escaping @MainActor ([Int16]) -> Void) throws
    func stop(engine: AVAudioEngine)
    /// The microphone was opened again for the next utterance (the made-up voice speaks again).
    func opened()
    /// The fixture plays no sound and needs no recording session.
    var isFixture: Bool { get }
}

/// One audio graph for the whole conversation (watchOS can't start recording from the background, so it is
/// started once, in front, and kept running between turns): the input's tap, converted to 16 kHz, and a player
/// node for the replies (streamed PCM, clips, the watch's own voice).
///
///     microphone --tap--> 16 kHz Int16 --> SpeechDetector (the engine decides when it listens)
///     replies --> AVAudioPlayerNode (16 kHz float) --> main mixer --> speaker / AirPods
///
/// It reports when something starts playing and when everything scheduled has played (`onPlaying`, `onDrained`),
/// and an interruption (a call, Siri) or a lost route it couldn't recover from (`onInterrupted`).
@MainActor
final class AudioGraph {
    enum StartFailure: Error, Equatable {
        case microphoneDenied
        case session(String)
        case engine(String)

        var message: String {
            switch self {
            case .microphoneDenied: "Microphone is off"
            case .session, .engine: "The microphone didn't start"
            }
        }
    }

    var onSamples: (@MainActor ([Int16]) -> Void)?
    var onPlaying: (@MainActor () -> Void)?
    var onDrained: (@MainActor () -> Void)?
    var onInterrupted: (@MainActor () -> Void)?

    let input: ConversationInput
    private(set) var running = false
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private let playFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1,
                                           interleaved: false)!
    /// Bumped by `stopPlayback`: completions of what was stopped are ignored.
    private var generation = 0
    private var outstanding = 0
    /// Frames scheduled in this generation, and the level of each buffer (for the orb).
    private var scheduledFrames: Int64 = 0
    private var levels: [(end: Int64, level: Double)] = []
    private var synthesizer: AVSpeechSynthesizer?
    private var speaking = false
    private var speechTimer: Task<Void, Never>?
    private var observers: [any NSObjectProtocol] = []
    private let muted: Bool

    init(input: ConversationInput, muted: Bool) {
        self.input = input
        self.muted = muted
    }

    // MARK: - Start and stop

    /// Asks for the microphone, sets up the audio session (play and record, AirPods allowed) and starts the engine.
    /// Siri may still hold the microphone right after "Talk to SamRabbit": retried for about 3 seconds.
    func start() async throws {
        guard !running else { return }
        guard await input.allowed() else { throw StartFailure.microphoneDenied }
        let session = AVAudioSession.sharedInstance()
        do {
            if input.isFixture {
                try session.setCategory(.playback, mode: .default, options: [])
            } else {
                try session.setCategory(.playAndRecord, mode: .default, policy: .default,
                                        options: [.allowBluetoothHFP, .allowBluetoothA2DP])
                try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
            }
            try? session.setPrefersNoInterruptionsFromSystemAlerts(true)
        } catch {
            throw StartFailure.session("category")
        }
        var lastError: Error?
        for delay in [0, 150, 300, 600, 1200] {
            if delay > 0 { try? await Task.sleep(for: .milliseconds(delay)) }
            do {
                _ = try await session.activate(options: [])
                try buildAndStart()
                running = true
                observe()
                return
            } catch {
                lastError = error
                teardown()
            }
        }
        audioLog.error("audio did not start: \(String(describing: lastError), privacy: .public)")
        throw StartFailure.engine("start")
    }

    private func buildAndStart() throws {
        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: playFormat)
        engine.mainMixerNode.outputVolume = muted ? 0 : 1
        self.engine = engine
        self.player = player
        try input.start(engine: engine) { [weak self] samples in self?.onSamples?(samples) }
        engine.prepare()
        try engine.start()
        player.play()
    }

    /// Stops everything and gives the audio session back.
    func stop() {
        guard running || engine != nil else { return }
        running = false
        stopPlayback()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        teardown()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func teardown() {
        if let engine {
            input.stop(engine: engine)
            engine.stop()
        }
        engine = nil
        player = nil
    }

    private func observe() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil,
                                            queue: .main) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                .flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .began
            guard began else { return }
            MainActor.assumeIsolated { self?.interrupted() }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil,
                                            queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.interrupted() }
        })
        if let engine {
            // AirPods came or went: the engine stopped; start it again with the new input.
            observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine,
                                                queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.reconfigure() }
            })
        }
    }

    private func interrupted() {
        guard running else { return }
        audioLog.notice("audio interrupted")
        onInterrupted?()
    }

    private func reconfigure() {
        guard running, let engine else { return }
        audioLog.notice("audio route changed")
        stopPlayback()
        input.stop(engine: engine)
        do {
            try input.start(engine: engine) { [weak self] samples in self?.onSamples?(samples) }
            engine.prepare()
            try engine.start()
            player?.play()
        } catch {
            onInterrupted?()
        }
    }

    // MARK: - Playback

    /// A piece of a streamed reply: PCM16LE mono 16 kHz.
    func schedule(pcm: Data) {
        let samples = PCM16.samples(pcm)
        guard !samples.isEmpty, let buffer = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: AVAudioFrameCount(samples.count)),
              let channel = buffer.floatChannelData?[0] else { return }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        for (index, sample) in samples.enumerated() { channel[index] = Float(sample) / 32768 }
        schedule(buffer)
    }

    /// A whole clip (MP3 from the Claude fallback's voice, AAC, a WAV in another format). Returns false when it
    /// couldn't be read (the conversation then speaks the words itself).
    @discardableResult
    func play(clip: AssistantAudio) -> Bool {
        let ext = clip.mime.contains("mpeg") || clip.mime.contains("mp3") ? "mp3"
            : clip.mime.contains("wav") ? "wav" : "m4a"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("reply-\(UUID().uuidString).\(ext)")
        defer { try? FileManager.default.removeItem(at: url) }
        do {
            try clip.data.write(to: url)
            let file = try AVAudioFile(forReading: url)
            guard let source = AVAudioPCMBuffer(pcmFormat: file.processingFormat,
                                                frameCapacity: AVAudioFrameCount(file.length)) else { return false }
            try file.read(into: source)
            guard let converted = convert(source) else { return false }
            schedule(converted)
            return true
        } catch {
            audioLog.notice("a clip could not be read")
            return false
        }
    }

    /// Says `text` with the watch's own voice (best en-US voice), through the same player.
    func speak(_ text: String) {
        let synthesizer = AVSpeechSynthesizer()
        self.synthesizer = synthesizer
        speaking = true
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = Self.voice
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 1.04
        let current = generation
        synthesizer.write(utterance) { [weak self] buffer in
            nonisolated(unsafe) let buffer = buffer
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    guard let self, self.generation == current else { return }
                    guard let pcm = buffer as? AVAudioPCMBuffer, pcm.frameLength > 0 else {
                        self.speechEnded(current) // the empty buffer that ends the speech
                        return
                    }
                    if let converted = self.convert(pcm) { self.schedule(converted) }
                    self.speechWatchdog(current, after: 1.5)
                }
            }
        }
        // A voice that never answers (or never says it is done) must not hang the conversation.
        speechWatchdog(current, after: 4)
    }

    /// Ends the watch's speech if nothing more came for `seconds` (the synthesizer writes faster than it plays).
    private func speechWatchdog(_ generation: Int, after seconds: TimeInterval) {
        speechTimer?.cancel()
        speechTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.speechEnded(generation)
        }
    }

    private func speechEnded(_ generation: Int) {
        guard generation == self.generation, speaking else { return }
        speechTimer?.cancel()
        speechTimer = nil
        speaking = false
        synthesizer = nil
        if outstanding == 0 { onDrained?() }
    }

    /// The best installed en-US voice (premium, then enhanced, then any).
    static let voice: AVSpeechSynthesisVoice? = {
        let voices = AVSpeechSynthesisVoice.speechVoices().filter { $0.language == "en-US" }
        return voices.max { $0.quality.rawValue < $1.quality.rawValue } ?? AVSpeechSynthesisVoice(language: "en-US")
    }()

    /// Stops what plays and drops what is scheduled (barge-in).
    func stopPlayback() {
        generation += 1
        outstanding = 0
        scheduledFrames = 0
        levels = []
        speaking = false
        speechTimer?.cancel()
        speechTimer = nil
        synthesizer?.stopSpeaking(at: .immediate)
        synthesizer = nil
        player?.stop()
        if running { player?.play() }
    }

    private func schedule(_ buffer: AVAudioPCMBuffer) {
        guard let player, buffer.frameLength > 0 else { return }
        let current = generation
        if outstanding == 0 { onPlaying?() }
        outstanding += 1
        scheduledFrames += Int64(buffer.frameLength)
        levels.append((scheduledFrames, Self.level(of: buffer)))
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.played(current) } }
        }
        if !player.isPlaying { player.play() }
    }

    private func played(_ generation: Int) {
        guard generation == self.generation else { return }
        outstanding = max(0, outstanding - 1)
        if outstanding == 0, !speaking { onDrained?() }
    }

    /// 0...1: how loud the reply is right now (the orb pulses with it).
    func playbackLevel() -> Double {
        guard outstanding > 0, let player, let nodeTime = player.lastRenderTime,
              let time = player.playerTime(forNodeTime: nodeTime) else { return 0 }
        let position = time.sampleTime
        while let first = levels.first, first.end <= position, levels.count > 1 { levels.removeFirst() }
        return levels.first?.level ?? 0
    }

    private static func level(of buffer: AVAudioPCMBuffer) -> Double {
        guard let channel = buffer.floatChannelData?[0] else { return 0 }
        let power = PCM16.dbfs(floats: UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
        return VoiceActivity.level(forPower: power + 8)
    }

    /// Any PCM buffer as the player's format (16 kHz mono float).
    private func convert(_ source: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        if source.format == playFormat { return source }
        guard let converter = AVAudioConverter(from: source.format, to: playFormat) else { return nil }
        let ratio = playFormat.sampleRate / source.format.sampleRate
        let capacity = AVAudioFrameCount(Double(source.frameLength) * ratio + 1024)
        guard let output = AVAudioPCMBuffer(pcmFormat: playFormat, frameCapacity: capacity) else { return nil }
        nonisolated(unsafe) var consumed = false
        nonisolated(unsafe) let input = source
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if consumed {
                status.pointee = .endOfStream
                return nil
            }
            consumed = true
            status.pointee = .haveData
            return input
        }
        return error == nil && output.frameLength > 0 ? output : nil
    }
}

/// The watch's microphone: a tap on the engine's input in its own format, converted to 16 kHz mono 16-bit.
@MainActor
final class MicrophoneInput: ConversationInput {
    var isFixture: Bool { false }

    func allowed() async -> Bool {
        switch AVAudioApplication.shared.recordPermission {
        case .granted: true
        case .denied: false
        default: await AVAudioApplication.requestRecordPermission()
        }
    }

    func start(engine: AVAudioEngine, deliver: @escaping @MainActor ([Int16]) -> Void) throws {
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0, let tap = TapConverter(from: format) else {
            throw AudioGraph.StartFailure.engine("input format")
        }
        input.installTap(onBus: 0, bufferSize: AVAudioFrameCount(format.sampleRate / 10), format: format) { buffer, _ in
            guard let samples = tap.convert(buffer), !samples.isEmpty else { return }
            // In order (the detector reads a continuous stream).
            DispatchQueue.main.async { MainActor.assumeIsolated { deliver(samples) } }
        }
    }

    func stop(engine: AVAudioEngine) {
        engine.inputNode.removeTap(onBus: 0)
    }

    func opened() {}
}

/// Converts the microphone's buffers to 16 kHz mono 16-bit, on the tap's thread (one converter keeps its state
/// across buffers).
final class TapConverter: @unchecked Sendable {
    private let converter: AVAudioConverter
    private let target: AVAudioFormat

    init?(from source: AVAudioFormat) {
        guard let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16_000, channels: 1, interleaved: true),
              let converter = AVAudioConverter(from: source, to: target) else { return nil }
        self.converter = converter
        self.target = target
    }

    func convert(_ buffer: AVAudioPCMBuffer) -> [Int16]? {
        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 64)
        guard let output = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return nil }
        var consumed = false
        var error: NSError?
        converter.convert(to: output, error: &error) { _, status in
            if consumed {
                status.pointee = .noDataNow
                return nil
            }
            consumed = true
            status.pointee = .haveData
            return buffer
        }
        guard error == nil, let channel = output.int16ChannelData?[0] else { return nil }
        return Array(UnsafeBufferPointer(start: channel, count: Int(output.frameLength)))
    }
}
