import AVFoundation
import Observation
import SamRabbitKit
import Speech
import SwiftUI

/// Speech to text on this iPhone (on-device when the language supports it). Appends to the bound
/// text as you speak; the keyboard's own mic works in every field too.
@MainActor
@Observable
final class SpeechDictation {
    enum State: Equatable { case idle, listening, unavailable(String) }

    var state: State = .idle
    private var engine: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var prefix = ""

    var isListening: Bool { state == .listening }

    func toggle(_ text: Binding<String>) {
        if isListening { stop() } else { Task { await start(text) } }
    }

    func start(_ text: Binding<String>) async {
        let speech = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { continuation.resume(returning: $0) }
        }
        guard speech == .authorized else {
            state = .unavailable("Allow Speech Recognition for SamRabbit in Settings.")
            return
        }
        guard await AVAudioApplication.requestRecordPermission() else {
            state = .unavailable("Allow the microphone for SamRabbit in Settings.")
            return
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale.current) ?? SFSpeechRecognizer(locale: Locale(identifier: "en-US")),
              recognizer.isAvailable else {
            state = .unavailable("Dictation isn't available right now.")
            return
        }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            let engine = AVAudioEngine()
            let request = SFSpeechAudioBufferRecognitionRequest()
            request.shouldReportPartialResults = true
            request.addsPunctuation = true
            if recognizer.supportsOnDeviceRecognition { request.requiresOnDeviceRecognition = true }
            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [request] buffer, _ in
                request.append(buffer)
            }
            engine.prepare()
            try engine.start()
            self.engine = engine
            self.request = request
            let base = text.wrappedValue.trimmingCharacters(in: .whitespaces)
            prefix = base.isEmpty ? "" : base + " "
            state = .listening
            task = recognizer.recognitionTask(with: request) { [weak self] result, error in
                let transcript = result?.bestTranscription.formattedString
                let final = result?.isFinal ?? false
                Task { @MainActor in
                    guard let self else { return }
                    if let transcript { text.wrappedValue = self.prefix + transcript }
                    if error != nil || final { self.stop() }
                }
            }
        } catch {
            state = .unavailable("The microphone couldn't start.")
            stop()
        }
    }

    func stop() {
        engine?.stop()
        engine?.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.finish()
        engine = nil
        request = nil
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if state == .listening { state = .idle }
    }
}

/// The mic button next to text fields.
struct DictationButton: View {
    @Binding var text: String
    @State private var dictation = SpeechDictation()

    var body: some View {
        Button {
            Haptics.tap()
            dictation.toggle($text)
        } label: {
            Image(systemName: dictation.isListening ? "waveform" : "mic.fill")
                .font(.system(size: 15, weight: .semibold))
                .symbolEffect(.variableColor.iterative, isActive: dictation.isListening)
                .foregroundStyle(dictation.isListening ? SamTheme.red : SamTheme.ink2)
                .frame(width: 22, height: 22)
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .accessibilityLabel(dictation.isListening ? "Stop dictation" : "Dictate")
        .onDisappear { dictation.stop() }
        .popover(isPresented: Binding(get: {
            if case .unavailable = dictation.state { return true }
            return false
        }, set: { if !$0 { dictation.state = .idle } })) {
            if case .unavailable(let message) = dictation.state {
                Text(message).font(.system(size: 14)).padding().presentationCompactAdaptation(.popover)
            }
        }
    }
}

/// A one-line-to-five-line text field with a mic and a send button (thread replies, the Ask sheet).
struct Composer: View {
    @Binding var text: String
    var placeholder: String
    var sending = false
    var focused: FocusState<Bool>.Binding
    var send: () -> Void

    var body: some View {
        HStack(alignment: .bottom, spacing: 8) {
            TextField(placeholder, text: $text, axis: .vertical)
                .focused(focused)
                .lineLimit(1...5)
                .font(.system(size: 16))
                .padding(.horizontal, 16)
                .padding(.vertical, 11)
                .glassEffect(.regular, in: .rect(cornerRadius: 22))
                .submitLabel(.send)
            DictationButton(text: $text)
            Button(action: send) {
                Group {
                    if sending { ProgressView().controlSize(.small) } else {
                        Image(systemName: "arrow.up").font(.system(size: 16, weight: .bold))
                    }
                }
                .frame(width: 22, height: 22)
            }
            .buttonStyle(.glassProminent)
            .buttonBorderShape(.circle)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || sending)
            .accessibilityLabel("Send")
        }
    }
}
