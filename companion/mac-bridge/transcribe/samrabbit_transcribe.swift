// samrabbit-transcribe: on-device speech to text for the SamRabbit Mac bridge (POST /v1/mobile/transcribe).
//
//   samrabbit-transcribe --file <audio> [--locale en-US] [--max-seconds 90] [--engine auto|speech|dictation]
//   samrabbit-transcribe --check   [--locale en-US] [--engine ...]   is it usable? (no audio, no download)
//   samrabbit-transcribe --prepare [--locale en-US] [--engine ...]   download the language model if it is missing
//   samrabbit-transcribe --version
//
// It prints exactly one JSON object on stdout and nothing else anywhere: the words are only ever in that object
// (never on stderr, never in a log). Errors are {"ok": false, "code", "message", "detail"?} with a matching exit
// status. The audio is a file (m4a/AAC, WAV, ADTS AAC), so no microphone and no permission prompt is involved.
//
// Engine: SpeechAnalyzer with SpeechTranscriber (macOS 26+, on device, punctuated). DictationTranscriber when this
// Mac has no SpeechTranscriber or it does not know the language. A transcription never downloads a model (that can
// take minutes): it answers transcribe_unavailable / model_missing and the bridge runs --prepare in the background.
//
// Built by companion/mac-bridge/install.sh:
//   xcrun --sdk macosx swiftc -O -parse-as-library samrabbit_transcribe.swift -o samrabbit-transcribe \
//     -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker Info.plist && codesign -s - -f ...

import AVFoundation
import Foundation
import Speech

let helperVersion = "1"

struct Failure: Error {
    let code: String
    let message: String
    var detail: String? = nil

    var exitStatus: Int32 {
        switch code {
        case "transcribe_unavailable": return 3
        case "transcribe_permission": return 4
        case "unsupported_language": return 5
        case "unsupported_audio": return 6
        case "audio_too_long": return 7
        case "no_speech": return 8
        case "usage": return 64
        default: return 1  // transcribe_failed
        }
    }
}

enum Engine: String {
    case speech = "SpeechTranscriber"
    case dictation = "DictationTranscriber"
}

struct Options {
    var mode = ""
    var file: String?
    var locale = "en-US"
    var maxSeconds = 90.0
    var engine = "auto"
}

func emit(_ object: [String: Any], status: Int32 = 0) -> Never {
    var data = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
    data.append(0x0A)
    FileHandle.standardOutput.write(data)
    exit(status)
}

func fail(_ failure: Failure) -> Never {
    var value: [String: Any] = ["ok": false, "code": failure.code, "message": failure.message]
    if let detail = failure.detail { value["detail"] = detail }
    emit(value, status: failure.exitStatus)
}

func parse(_ arguments: [String]) throws -> Options {
    var options = Options()
    var index = 1
    func value(_ name: String) throws -> String {
        index += 1
        guard index < arguments.count else { throw Failure(code: "usage", message: "\(name) needs a value.") }
        return arguments[index]
    }
    while index < arguments.count {
        let argument = arguments[index]
        switch argument {
        case "--file": options.mode = "file"; options.file = try value(argument)
        case "--check": options.mode = "check"
        case "--prepare": options.mode = "prepare"
        case "--version": options.mode = "version"
        case "--locale": options.locale = try value(argument)
        case "--engine": options.engine = try value(argument)
        case "--max-seconds":
            guard let seconds = Double(try value(argument)), seconds > 0 else {
                throw Failure(code: "usage", message: "--max-seconds must be a positive number.")
            }
            options.maxSeconds = seconds
        default: throw Failure(code: "usage", message: "Unknown argument.")
        }
        index += 1
    }
    guard !options.mode.isEmpty else {
        throw Failure(code: "usage", message: "Use --file <audio>, --check, --prepare or --version.")
    }
    guard ["auto", "speech", "dictation"].contains(options.engine) else {
        throw Failure(code: "usage", message: "--engine must be auto, speech or dictation.")
    }
    return options
}

func tag(_ locale: Locale) -> String { locale.identifier(.bcp47) }

func permissionState() -> String {
    switch SFSpeechRecognizer.authorizationStatus() {
    case .authorized: return "authorized"
    case .denied: return "denied"
    case .restricted: return "restricted"
    case .notDetermined: return "notDetermined"
    @unknown default: return "unknown"
    }
}

/// The engine and its exact locale for a requested language (SpeechTranscriber first).
func pickEngine(_ requested: Locale, _ choice: String) async throws -> (Engine, Locale) {
    if choice != "dictation", SpeechTranscriber.isAvailable,
       let locale = await SpeechTranscriber.supportedLocale(equivalentTo: requested) {
        return (.speech, locale)
    }
    if choice != "speech", let locale = await DictationTranscriber.supportedLocale(equivalentTo: requested) {
        return (.dictation, locale)
    }
    if choice == "speech" && !SpeechTranscriber.isAvailable {
        throw Failure(code: "transcribe_unavailable", message: "This Mac has no on-device speech transcriber.",
                      detail: "speech_unavailable")
    }
    throw Failure(code: "unsupported_language", message: "This Mac cannot transcribe that language.")
}

func makeModule(_ engine: Engine, _ locale: Locale) -> any SpeechModule {
    switch engine {
    case .speech:
        return SpeechTranscriber(locale: locale, preset: .transcription)
    case .dictation:
        return DictationTranscriber(locale: locale, contentHints: [], transcriptionOptions: [.punctuation],
                                    reportingOptions: [], attributeOptions: [])
    }
}

func modelInstalled(_ engine: Engine, _ locale: Locale) async -> Bool {
    let installed = engine == .speech ? await SpeechTranscriber.installedLocales
        : await DictationTranscriber.installedLocales
    return installed.contains { tag($0) == tag(locale) }
}

/// Speech framework and Core Audio errors as the bridge's codes (the detail is a domain and number, no words).
func classify(_ error: Error) -> Failure {
    if let failure = error as? Failure { return failure }
    let ns = error as NSError
    let detail = "\(ns.domain)#\(ns.code)"
    if let speech = error as? SFSpeechError {
        let code = speech.code
        if code == .noModel || code == .assetLocaleNotAllocated || code == .tooManyAssetLocalesAllocated
            || code == .cannotAllocateUnsupportedLocale {
            return Failure(code: "transcribe_unavailable", message: "The speech model for this language is not "
                           + "ready on this Mac.", detail: "model_missing")
        }
        if code == .insufficientResources {
            return Failure(code: "transcribe_unavailable", message: "The Mac is too busy to transcribe right now.",
                           detail: "insufficient_resources")
        }
        if code == .audioReadFailed || code == .unexpectedAudioFormat || code == .incompatibleAudioFormats
            || code == .audioDisordered {
            return Failure(code: "unsupported_audio", message: "The recording could not be read.", detail: detail)
        }
    }
    let state = permissionState()
    if state == "denied" || state == "restricted"
        || ns.localizedDescription.lowercased().contains("not authorized") {
        return Failure(code: "transcribe_permission", message: "Speech recognition is not allowed for SamRabbit. "
                       + "Turn it on in System Settings > Privacy & Security > Speech Recognition.", detail: detail)
    }
    return Failure(code: "transcribe_failed", message: "The Mac could not transcribe the recording.", detail: detail)
}

func check(_ options: Options) async -> [String: Any] {
    var value: [String: Any] = ["ok": true, "version": helperVersion, "permission": permissionState(),
                                "speechTranscriber": SpeechTranscriber.isAvailable]
    do {
        let (engine, locale) = try await pickEngine(Locale(identifier: options.locale), options.engine)
        let installed = await modelInstalled(engine, locale)
        value["engine"] = engine.rawValue
        value["locale"] = tag(locale)
        value["model"] = installed ? "installed" : "missing"
        value["available"] = installed
        if !installed { value["reason"] = "model_missing" }
    } catch {
        let failure = classify(error)
        value["available"] = false
        value["model"] = failure.code == "unsupported_language" ? "unsupported" : "unknown"
        value["reason"] = failure.detail ?? failure.code
    }
    return value
}

func prepare(_ options: Options) async throws -> [String: Any] {
    let (engine, locale) = try await pickEngine(Locale(identifier: options.locale), options.engine)
    let module = makeModule(engine, locale)
    var downloaded = false
    do {
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            try await request.downloadAndInstall()
            downloaded = true
        }
    } catch {
        let failure = classify(error)
        throw Failure(code: failure.code == "transcribe_failed" ? "transcribe_unavailable" : failure.code,
                      message: "The speech model could not be downloaded (is the Mac online?).",
                      detail: failure.detail)
    }
    return ["ok": true, "engine": engine.rawValue, "locale": tag(locale), "model": "installed",
            "downloaded": downloaded]
}

func transcribe(_ options: Options) async throws -> [String: Any] {
    let started = Date()
    guard let path = options.file else { throw Failure(code: "usage", message: "--file needs a path.") }
    let file: AVAudioFile
    do {
        file = try AVAudioFile(forReading: URL(fileURLWithPath: path))
    } catch {
        let ns = error as NSError
        throw Failure(code: "unsupported_audio", message: "The recording could not be read.",
                      detail: "\(ns.domain)#\(ns.code)")
    }
    let rate = file.fileFormat.sampleRate
    guard rate > 0 else { throw Failure(code: "unsupported_audio", message: "The recording has no sample rate.") }
    let seconds = Double(file.length) / rate
    let durationMs = Int((seconds * 1000).rounded())
    if seconds > options.maxSeconds + 0.5 {  // AAC priming and padding
        throw Failure(code: "audio_too_long", message: "The recording is longer than \(Int(options.maxSeconds)) s.",
                      detail: "\(durationMs)ms")
    }
    if file.length == 0 { throw Failure(code: "no_speech", message: "The recording is empty.") }

    let (engine, locale) = try await pickEngine(Locale(identifier: options.locale), options.engine)
    let module = makeModule(engine, locale)
    if !(await modelInstalled(engine, locale)) {
        let request: AssetInstallationRequest?
        do {
            request = try await AssetInventory.assetInstallationRequest(supporting: [module])
        } catch {
            throw classify(error)
        }
        if request != nil {
            throw Failure(code: "transcribe_unavailable", message: "The speech model for this language is not on "
                          + "this Mac yet.", detail: "model_missing")
        }
    }

    let analyzer = SpeechAnalyzer(modules: [module])
    let collector: Task<[String], Error>
    switch module {
    case let transcriber as SpeechTranscriber:
        collector = Task {
            var parts: [String] = []
            for try await result in transcriber.results where result.isFinal {
                parts.append(String(result.text.characters))
            }
            return parts
        }
    case let transcriber as DictationTranscriber:
        collector = Task {
            var parts: [String] = []
            var pending: String?  // a result that was never made final (dictation on a file)
            for try await result in transcriber.results {
                if result.isFinal {
                    parts.append(String(result.text.characters))
                    pending = nil
                } else {
                    pending = String(result.text.characters)
                }
            }
            if let pending { parts.append(pending) }
            return parts
        }
    default:
        throw Failure(code: "transcribe_failed", message: "Unknown speech module.")
    }
    do {
        if let end = try await analyzer.analyzeSequence(from: file) {
            try await analyzer.finalizeAndFinish(through: end)
        } else {
            await analyzer.cancelAndFinishNow()
        }
    } catch {
        collector.cancel()
        await analyzer.cancelAndFinishNow()
        throw classify(error)
    }
    let parts: [String]
    do {
        parts = try await collector.value
    } catch {
        throw classify(error)
    }
    let text = parts.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        .joined(separator: " ")
        .split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    if text.isEmpty {
        throw Failure(code: "no_speech", message: "No speech was heard in the recording.", detail: "\(durationMs)ms")
    }
    return ["ok": true, "text": text, "durationMs": durationMs, "engine": engine.rawValue, "locale": tag(locale),
            "elapsedMs": Int(Date().timeIntervalSince(started) * 1000)]
}

@main
struct SamRabbitTranscribe {
    static func main() async {
        let options: Options
        do {
            options = try parse(CommandLine.arguments)
        } catch {
            fail(classify(error))
        }
        do {
            switch options.mode {
            case "version": emit(["ok": true, "version": helperVersion])
            case "check": emit(await check(options))
            case "prepare": emit(try await prepare(options))
            default: emit(try await transcribe(options))
            }
        } catch {
            fail(classify(error))
        }
    }
}
