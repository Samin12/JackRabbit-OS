import AVFoundation
import Foundation

// Voice input for the Apple Watch: watchOS has no speech recognizer, so the watch records a short clip and
// the Mac bridge turns it into words (`POST /v1/mobile/transcribe`). What both ends agree on lives here: the
// recording format, the answer, the summary's `transcribe` part and what each failure means to a person.

/// The words of a recording: `POST /v1/mobile/transcribe` -> `{text, durationMs, engine, locale}`.
public struct Transcript: Codable, Sendable, Equatable {
    public var text: String
    public var durationMs: Int
    public var engine: String?
    public var locale: String?

    public init(text: String, durationMs: Int = 0, engine: String? = nil, locale: String? = nil) {
        self.text = text
        self.durationMs = durationMs
        self.engine = engine
        self.locale = locale
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        text = (c.string("text") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        durationMs = c.int("durationMs")
        engine = c.text("engine")
        locale = c.text("locale")
    }
}

/// `summary.transcribe`: whether the Mac can turn recordings into words right now (`reason` when not:
/// `helper_missing`, `speech_unavailable`, `model_missing`, `model_downloading`, `permission_denied`, ...).
public struct TranscribeStatus: Codable, Sendable, Equatable {
    public var available: Bool
    public var reason: String?

    public init(available: Bool, reason: String? = nil) {
        self.available = available
        self.reason = reason
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: AnyKey.self)
        available = c.bool("available")
        reason = c.text("reason")
    }
}

/// How a voice clip is recorded and sent: AAC in an `.m4a`, 16 kHz mono at about 32 kbps (a minute is about
/// 240 KB), at most 60 seconds on the watch (the bridge takes 90 s and 2 MiB).
public enum VoiceFormat {
    public static let contentType = "audio/mp4"
    public static let fileExtension = "m4a"
    public static let sampleRate: Double = 16_000
    public static let channels = 1
    public static let bitRate = 32_000
    /// The bridge refuses larger bodies (413 `body_too_large`).
    public static let maxBytes = 2 * 1024 * 1024
    /// The watch stops recording here (the bridge allows 90 s).
    public static let maxSeconds: TimeInterval = 60
    /// Shorter clips are a slip of the finger: discarded, never sent.
    public static let minimumSeconds: TimeInterval = 0.4
    /// Quiet this long after speech ends the recording by itself.
    public static let silenceToStop: TimeInterval = 1.5
    /// Content types the bridge accepts.
    public static let acceptedTypes: Set<String> = ["audio/mp4", "audio/x-m4a", "audio/aac", "audio/wav"]

    /// `AVAudioRecorder` / `AVAudioFile` settings.
    public static var recorderSettings: [String: Any] {
        [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: bitRate,
            AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue,
        ]
    }

    /// `?lang=` for a locale: `en-US` (language and region), or just the language. The bridge makes it
    /// deterministic (`en` -> `en-US`).
    public static func languageTag(_ locale: Locale = .current) -> String? {
        guard let language = locale.language.languageCode?.identifier, !language.isEmpty else { return nil }
        if let region = locale.region?.identifier, region.count == 2 { return "\(language)-\(region)" }
        return language
    }

    /// A temporary file for one recording (`voice-<uuid>.m4a` in the temporary folder).
    public static func temporaryFile(in folder: URL = FileManager.default.temporaryDirectory) -> URL {
        folder.appendingPathComponent("voice-\(UUID().uuidString).\(fileExtension)")
    }

    /// Deletes recordings a previous run left behind (the app was stopped mid-recording).
    public static func removeLeftovers(in folder: URL = FileManager.default.temporaryDirectory) {
        let names = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        for name in names where name.hasPrefix("voice-") && name.hasSuffix(".\(fileExtension)") {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
    }
}

/// Decides when a recording ends: about 1.5 s of quiet once speech was heard, or the 60 s cap. Fed the
/// recorder's average power (dBFS) every ~50 ms. The noise floor follows the quietest level (down at
/// once, up slowly; `NoiseFloor`: readings at or below -100 dBFS are ignored and it never goes below -80),
/// so a noisy room still ends the recording; a sound counts as speech when it stands 10 dB above the floor
/// (and above -50 dBFS) for 150 ms.
public struct VoiceActivity: Sendable, Equatable {
    public enum Decision: Sendable, Equatable {
        case keepGoing
        /// Quiet after speech.
        case silence
        /// The time limit.
        case limit
    }

    public private(set) var heardSpeech = false
    /// 0...1, smoothed: what the orb shows.
    public private(set) var level: Double = 0
    private var noise = NoiseFloor()
    public var floor: Double? { noise.value }
    private var loudSince: TimeInterval?
    private var lastSpeechAt: TimeInterval?
    private var lastElapsed: TimeInterval?

    public let silence: TimeInterval
    public let limit: TimeInterval
    static let margin: Double = 10
    static let quietest: Double = -50
    static let speechMinimum: TimeInterval = 0.15

    public init(silence: TimeInterval = VoiceFormat.silenceToStop, limit: TimeInterval = VoiceFormat.maxSeconds) {
        self.silence = silence
        self.limit = limit
    }

    /// `power`: average power in dBFS (-160...0); `elapsed`: seconds since recording began.
    public mutating func feed(power: Double, elapsed: TimeInterval) -> Decision {
        let power = power.isFinite ? max(-160, min(0, power)) : -160
        let step = max(0, elapsed - (lastElapsed ?? elapsed))
        lastElapsed = elapsed
        noise.update(power: power, step: step)
        // No floor yet (only digital silence so far): nothing counts as speech.
        let threshold = floor.map { max($0 + Self.margin, Self.quietest) } ?? .infinity
        if power >= threshold {
            if loudSince == nil { loudSince = elapsed }
            if let since = loudSince, elapsed - since >= Self.speechMinimum {
                heardSpeech = true
            }
            if heardSpeech { lastSpeechAt = elapsed }
        } else {
            loudSince = nil
        }
        let target = Self.level(forPower: power)
        level = target > level ? level + (target - level) * 0.6 : level + (target - level) * 0.25
        if elapsed >= limit { return .limit }
        if heardSpeech, let last = lastSpeechAt, elapsed - last >= silence { return .silence }
        return .keepGoing
    }

    /// -55 dBFS (quiet room) ... -10 dBFS (speaking close) as 0 ... 1, eased.
    public static func level(forPower power: Double) -> Double {
        guard power.isFinite else { return 0 }
        let linear = min(1, max(0, (power + 55) / 45))
        return linear * linear * (3 - 2 * linear)
    }

    /// Whether a clip of this length is worth sending (shorter ones are discarded).
    public static func keeps(duration: TimeInterval) -> Bool { duration >= VoiceFormat.minimumSeconds }
}

/// What the watch says when a recording could not become words: a short title, one sentence and a
/// symbol. Every one of them offers "Say again"; none falls back to a keyboard.
public struct VoiceProblem: Sendable, Equatable, Error {
    public enum Kind: String, Sendable, Equatable {
        /// The Mac can't transcribe right now (not installed, model missing or downloading, ...).
        case unavailable
        /// Speech Recognition is turned off for the bridge on the Mac.
        case permission
        /// The watch's own microphone is not allowed.
        case microphone
        case noSpeech
        case tooShort
        case tooLong
        case busy
        case failed
        case language
        /// The bridge on the Mac doesn't know the route yet.
        case outdated
        case unreachable
        case unauthorized
    }

    public var kind: Kind
    public var title: String
    public var detail: String

    public init(_ kind: Kind, _ title: String, _ detail: String) {
        self.kind = kind
        self.title = title
        self.detail = detail
    }

    public var symbol: String {
        switch kind {
        case .unavailable, .outdated: "waveform.slash"
        case .permission, .microphone: "mic.slash.fill"
        case .noSpeech, .tooShort: "ear"
        case .tooLong: "timer"
        case .busy: "hourglass"
        case .failed: "exclamationmark.bubble.fill"
        case .language: "globe"
        case .unreachable: "wifi.exclamationmark"
        case .unauthorized: "iphone.slash"
        }
    }

    public static let microphone = VoiceProblem(.microphone, "Microphone is off",
                                                "Allow it on your watch: Settings > Privacy & Security > Microphone > SamRabbit.")
    public static let tooShort = VoiceProblem(.tooShort, "Didn't catch that", "Hold on a moment longer, then speak.")
    public static let noSpeech = VoiceProblem(.noSpeech, "I didn't hear anything", "Raise your wrist and speak a little closer.")
    public static let recorderFailed = VoiceProblem(.failed, "The microphone didn't start", "Try again in a moment.")

    /// The summary says the Mac can't transcribe now (`nil` when it can, or when it isn't sure: a check
    /// still loading or failing never blocks recording; the bridge answers for itself then).
    public init?(status: TranscribeStatus?) {
        guard let status, !status.available else { return nil }
        switch status.reason {
        case "loading", "check_failed", "check_timeout": return nil
        case "permission_denied": self = Self.permission
        case let reason?: self = Self.unavailable(reason: reason, message: nil)
        case nil: self = Self.unavailable(reason: nil, message: nil)
        }
    }

    /// A failed transcription, from the bridge's error codes.
    public init(_ error: BridgeError) {
        switch error {
        case .unreachable:
            self.init(.unreachable, "Can't reach your Mac", "Mac and iPhone are out of reach.")
        case .unauthorized:
            self.init(.unauthorized, "Watch removed", "Reconnect through your iPhone.")
        case .notPaired:
            self.init(.unauthorized, "Not paired", "Pair SamRabbit on your iPhone.")
        case .cancelled:
            self.init(.failed, "Cancelled", "Say it again when you're ready.")
        case .invalidResponse:
            self.init(.failed, "Couldn't read the words", "Try saying it again.")
        case .server(let status, let code, let message, _):
            self = Self.server(status: status, code: code, message: message)
        }
    }

    static let permission = VoiceProblem(.permission, "Speech is off on your Mac",
                                         "On the Mac: System Settings > Privacy & Security > Speech Recognition.")

    static func unavailable(reason: String?, message: String?) -> VoiceProblem {
        switch reason {
        case "model_missing", "model_downloading":
            VoiceProblem(.unavailable, "Your Mac is getting ready", "It's downloading the speech model. Try again in a minute.")
        case "insufficient_resources":
            VoiceProblem(.busy, "Your Mac is busy", "Say it again in a moment.")
        case "helper_missing":
            VoiceProblem(.unavailable, "Voice isn't set up on your Mac", "Run the SamRabbit bridge installer on your Mac again.")
        case "speech_unavailable":
            VoiceProblem(.unavailable, "Your Mac can't do voice", "Speech to text needs macOS 26 or newer on the Mac.")
        default:
            VoiceProblem(.unavailable, "Voice isn't available", message.flatMap { $0.isEmpty ? nil : $0 }
                ?? "Your Mac can't turn speech into words right now.")
        }
    }

    static func server(status: Int, code: String, message: String) -> VoiceProblem {
        switch code {
        case "transcribe_unavailable": unavailable(reason: nil, message: message)
        case "transcribe_permission": permission
        case "transcribe_busy": VoiceProblem(.busy, "Your Mac is busy", "Say it again in a moment.")
        case "no_speech": noSpeech
        case "audio_too_long", "audio_too_large", "body_too_large":
            VoiceProblem(.tooLong, "That was too long", "Keep it under a minute.")
        case "unsupported_audio", "invalid_audio":
            VoiceProblem(.failed, "The recording didn't come through", "Say it again.")
        case "unsupported_language", "invalid_lang":
            VoiceProblem(.language, "Language not supported", "Your Mac can't transcribe this watch's language.")
        case "transcribe_failed", "transcribe_timeout":
            VoiceProblem(.failed, "Couldn't make out the words", "Say it again.")
        case "not_found", "http_404", "relay_forbidden":
            VoiceProblem(.outdated, "Update SamRabbit on your Mac", "This Mac's bridge can't do voice yet.")
        default:
            status == 404
                ? VoiceProblem(.outdated, "Update SamRabbit on your Mac", "This Mac's bridge can't do voice yet.")
                : VoiceProblem(.failed, "Couldn't turn that into words", message.isEmpty ? "Say it again." : message)
        }
    }
}
