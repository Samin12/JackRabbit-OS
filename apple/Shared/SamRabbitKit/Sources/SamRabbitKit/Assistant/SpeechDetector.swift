import Foundation

/// The room's noise level: the quietest reading lately. It follows a quieter reading at once and rises slowly
/// (1.5 dB a second) when it gets louder, so a noisy place still lets speech end.
///
/// Readings at or below -100 dBFS are digital silence (a microphone that is just starting, a muted route, the
/// simulator), not the room: they are ignored, and the floor never goes below -80 dBFS. Without that, one silent
/// buffer pinned the floor near -160 and an ordinary room counted as speech for many seconds.
public struct NoiseFloor: Sendable, Equatable {
    /// Readings at or below this are ignored.
    public static let ignoredAtOrBelow: Double = -100
    /// The floor never goes below this.
    public static let lowest: Double = -80
    /// How fast the floor may rise (dB per second).
    public static let rise: Double = 1.5

    public private(set) var value: Double?

    public init() {}

    /// `step`: seconds since the previous reading.
    public mutating func update(power: Double, step: TimeInterval) {
        guard power.isFinite, power > Self.ignoredAtOrBelow else { return }
        let reading = max(Self.lowest, min(0, power))
        if let current = value {
            value = reading < current ? reading : min(reading, current + Self.rise * max(0, step))
        } else {
            value = reading
        }
    }
}

/// Finds utterances in a continuous 16 kHz mono stream (the conversation's always-on microphone): energy above the
/// noise floor for a moment starts speech, about a second of quiet after it ends the utterance.
///
/// * speech: 10 dB above the floor (and above -50 dBFS) for 150 ms;
/// * an utterance needs 0.3 s of speech (shorter is a cough or a tap: discarded);
/// * it ends 1 s after the last speech, or at 30 s;
/// * it keeps the 300 ms before speech started (the first syllable) and a short quiet tail.
///
/// Fed whatever the microphone tap delivers; it measures in 20 ms windows.
public struct SpeechDetector: Sendable {
    public struct Settings: Sendable, Equatable {
        public var sampleRate: Int = AssistantAudioFormat.sampleRate
        /// The analysis window.
        public var window: TimeInterval = 0.02
        /// Speech stands this far above the floor ...
        public var margin: Double = 10
        /// ... and above this.
        public var quietest: Double = -50
        /// Loud this long starts speech.
        public var attack: TimeInterval = 0.15
        /// An utterance needs this much speech.
        public var minimumSpeech: TimeInterval = 0.3
        /// Quiet this long after speech ends the utterance (the hangover).
        public var hangover: TimeInterval = 1.0
        /// Utterances are cut here.
        public var maximum: TimeInterval = 30
        /// Kept from before speech started.
        public var preRoll: TimeInterval = 0.3
        /// Kept after the last speech.
        public var tail: TimeInterval = 0.25

        public init() {}

        var windowSamples: Int { max(1, Int(Double(sampleRate) * window)) }
        func samples(_ seconds: TimeInterval) -> Int { Int(Double(sampleRate) * seconds) }
    }

    public enum Event: Sendable, Equatable {
        case speechStarted
        /// A finished utterance: 16-bit samples at `settings.sampleRate`.
        case utterance([Int16])
        /// Speech too short to send.
        case discarded
    }

    public let settings: Settings
    public private(set) var noise = NoiseFloor()
    public private(set) var inSpeech = false
    /// The last window's level, in dBFS.
    public private(set) var power: Double = -160
    /// 0...1, smoothed: what the orb shows.
    public private(set) var level: Double = 0

    private var pending: [Int16] = []
    private var preRoll: [Int16] = []
    private var utterance: [Int16] = []
    private var loudRun = 0
    private var quietRun = 0
    private var voiced = 0
    private var lastLoudEnd = 0
    private var ignoring = 0

    public init(settings: Settings = Settings()) {
        self.settings = settings
    }

    public var floor: Double? { noise.value }

    /// The utterance so far, in seconds (0 when not in speech).
    public var utteranceSeconds: TimeInterval { Double(utterance.count) / Double(settings.sampleRate) }

    /// Forgets any speech in progress (the microphone closed for a reply, or reopened after it). The noise floor
    /// is kept. `ignoring` drops that much input first (the reply's echo).
    public mutating func reset(ignoring seconds: TimeInterval = 0) {
        pending.removeAll(keepingCapacity: true)
        preRoll.removeAll(keepingCapacity: true)
        utterance.removeAll(keepingCapacity: true)
        inSpeech = false
        loudRun = 0
        quietRun = 0
        voiced = 0
        lastLoudEnd = 0
        level = 0
        ignoring = settings.samples(seconds)
    }

    /// Feeds microphone samples; returns what they completed.
    public mutating func feed(_ samples: [Int16]) -> [Event] {
        var input = samples[...]
        if ignoring > 0 {
            let dropped = min(ignoring, input.count)
            ignoring -= dropped
            input = input.dropFirst(dropped)
        }
        guard !input.isEmpty else { return [] }
        pending.append(contentsOf: input)
        let size = settings.windowSamples
        var events: [Event] = []
        var start = 0
        while pending.count - start >= size {
            events += analyse(pending[start..<(start + size)])
            start += size
        }
        pending.removeFirst(start)
        return events
    }

    /// Ends speech in progress now (a tap: "that's it"). Returns the utterance, or `discarded` when it was too
    /// short; nothing when no one was speaking.
    public mutating func flush() -> [Event] {
        guard inSpeech else { return [] }
        if !pending.isEmpty {
            utterance.append(contentsOf: pending)
            pending.removeAll(keepingCapacity: true)
        }
        return [finish(cut: false)]
    }

    private mutating func analyse(_ window: ArraySlice<Int16>) -> [Event] {
        let p = PCM16.dbfs(window)
        power = p
        noise.update(power: p, step: settings.window)
        let loud = noise.value.map { p >= max($0 + settings.margin, settings.quietest) } ?? false
        let target = VoiceActivity.level(forPower: p)
        level = target > level ? level + (target - level) * 0.6 : level + (target - level) * 0.25
        let size = window.count
        if !inSpeech {
            preRoll.append(contentsOf: window)
            let keep = max(settings.samples(settings.preRoll), settings.samples(settings.attack) + size)
            if preRoll.count > keep { preRoll.removeFirst(preRoll.count - keep) }
            loudRun = loud ? loudRun + size : 0
            guard loudRun >= settings.samples(settings.attack) else { return [] }
            inSpeech = true
            utterance = preRoll
            preRoll.removeAll(keepingCapacity: true)
            voiced = loudRun
            quietRun = 0
            lastLoudEnd = utterance.count
            return [.speechStarted]
        }
        utterance.append(contentsOf: window)
        if loud {
            voiced += size
            quietRun = 0
            lastLoudEnd = utterance.count
        } else {
            quietRun += size
        }
        if quietRun >= settings.samples(settings.hangover) { return [finish(cut: false)] }
        if utterance.count >= settings.samples(settings.maximum) { return [finish(cut: true)] }
        return []
    }

    private mutating func finish(cut: Bool) -> Event {
        let keep = cut ? utterance.count : min(utterance.count, lastLoudEnd + settings.samples(settings.tail))
        let event: Event = voiced >= settings.samples(settings.minimumSpeech)
            ? .utterance(Array(utterance.prefix(keep))) : .discarded
        utterance.removeAll(keepingCapacity: true)
        inSpeech = false
        loudRun = 0
        quietRun = 0
        voiced = 0
        lastLoudEnd = 0
        return event
    }
}
