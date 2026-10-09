import SamRabbitKit
import SwiftUI

/// Page 1, the watch's main screen: the conversation with SamRabbit. The orb listens, thinks and speaks; a small
/// live caption shows what was heard and what is being said; a line of status around it (what needs you, what is
/// working, what's next). Tap the orb to talk, to send now, or to interrupt. Scroll down for the last few turns.
struct AssistantPage: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.isLuminanceReduced) private var dimmed
    /// `-SamRabbitStillOrb YES` (UI tests): a still orb, so the app goes idle between frames and queries are quick.
    private let still = UserDefaults.standard.bool(forKey: "SamRabbitStillOrb")

    private var engine: ConversationEngine { model.conversation }
    private var machine: ConversationMachine { engine.machine }

    var body: some View {
        ScrollView {
            VStack(spacing: 3) {
                StatusStrip()
                Button {
                    engine.active ? engine.tap() : model.talk()
                } label: {
                    OrbView(mood: mood, animated: !dimmed && !still, frameRate: 30, level: dimmed ? 0 : engine.level)
                        .frame(width: 78, height: 78)
                        .padding(.vertical, 5)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("assistant-orb")
                .accessibilityLabel(orbLabel)
                .disabled(model.rejected || !model.paired)

                Text(stateWord)
                    .font(.system(size: 15, weight: .semibold, design: .rounded))
                    .foregroundStyle(stateColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .accessibilityIdentifier("assistant-state")
                    .accessibilityValue(audioValue)
                if let detail = problemDetail {
                    Text(detail)
                        .font(.system(size: 11.5))
                        .foregroundStyle(SamTheme.muted)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("assistant-problem")
                }
                if !dimmed { caption }
                controls.padding(.top, 4)
                if !dimmed, earlier.count > 0 { history }
            }
            .padding(.horizontal, 4)
        }
        .toolbar {
            if engine.active {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        engine.stop()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(.white)
                    }
                    .tint(SamTheme.red)
                    .accessibilityLabel("Stop")
                    .accessibilityIdentifier("assistant-stop")
                }
            }
        }
        .withBanner()
        .samPage(engine.active ? SamTheme.orb2 : SamTheme.orb)
    }

    // MARK: - State

    private var mood: OrbMood {
        if model.rejected || !model.paired { return .offline }
        switch machine.phase {
        case .idle: return model.mood
        case .starting, .listening, .hearing, .speaking: return .live
        case .thinking: return .working
        }
    }

    private var stateWord: String {
        if model.rejected { return "Watch removed" }
        switch machine.phase {
        case .idle:
            if let problem = machine.problem { return problem }
            if let reason = model.assistantUnavailable { return reason }
            return "Tap to talk"
        case .starting: return "Starting…"
        case .listening: return "Listening…"
        case .hearing: return "Hearing you…"
        case .thinking: return "Thinking…"
        case .speaking: return machine.live?.kind == .announcement ? "Heads up" : "Speaking…"
        }
    }

    /// Debug builds: whether the microphone is live ("audio on"/"audio off"), for the UI tests (nothing in release).
    private var audioValue: String {
        #if DEBUG
        engine.audioRunning ? "audio on" : "audio off"
        #else
        ""
        #endif
    }

    /// What to do about the problem that ended the last conversation.
    private var problemDetail: String? {
        guard machine.phase == .idle, let problem = machine.problem else { return nil }
        switch problem {
        case "Microphone is off": return "Allow it on your watch: Settings > Privacy & Security > Microphone > SamRabbit."
        case "Can't reach your Mac": return "Is your Mac awake? Tap Talk to try again."
        case "Update SamRabbit on your Mac": return "Run the SamRabbit installer on your Mac again."
        case "Assistant unavailable on the Mac": return "Connect ChatGPT in SamRabbit on your Mac."
        default: return nil
        }
    }

    private var stateColor: Color {
        switch machine.phase {
        case .idle: machine.problem == nil ? SamTheme.ink : SamTheme.amber
        case .thinking: SamTheme.orbPale
        default: SamTheme.ink
        }
    }

    private var orbLabel: String {
        switch machine.phase {
        case .idle: "Talk to SamRabbit"
        case .hearing: "Send now"
        case .thinking, .speaking: "Interrupt"
        default: "Listening"
        }
    }

    // MARK: - Caption

    /// The exchange being heard or answered, else the last one (while the next is awaited).
    private var shown: ConversationMachine.Exchange? { machine.live ?? machine.exchanges.last }

    @ViewBuilder
    private var caption: some View {
        if let exchange = shown, machine.phase != .idle || machine.problem == nil {
            VStack(spacing: 2) {
                if !exchange.heard.isEmpty {
                    Text(exchange.heard)
                        .font(.system(size: 11.5, weight: .regular))
                        .italic()
                        .foregroundStyle(SamTheme.muted)
                        .lineLimit(2)
                        .accessibilityIdentifier("assistant-heard")
                }
                if !exchange.say.isEmpty {
                    Text(exchange.say)
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(SamTheme.ink)
                        .lineLimit(5)
                        .accessibilityIdentifier("assistant-say")
                }
                ForEach(exchange.cards, id: \.self) { card in
                    AssistantCardView(card: card)
                }
                ForEach(exchange.actions, id: \.self) { action in
                    Label(action.title, systemImage: Self.symbol(action.kind))
                        .font(.system(size: 11.5, weight: .semibold))
                        .foregroundStyle(SamTheme.mint)
                        .lineLimit(1)
                }
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .transition(.opacity)
            .animation(.easeOut(duration: 0.2), value: exchange.say)
        }
    }

    @ViewBuilder
    private var controls: some View {
        if model.rejected {
            Button {
                Task { await model.connect(reissue: true) }
            } label: {
                CapsuleFace(title: "Reconnect", symbol: "iphone", colors: [SamTheme.amber, SamTheme.amber.opacity(0.7)],
                            height: 40, busy: model.connecting)
            }
            .buttonStyle(.plain)
        } else if engine.active {
            // Stop is the top-left button; the orb taps to send now or to interrupt.
            EmptyView()
        } else {
            Button {
                model.talk()
            } label: {
                CapsuleFace(title: "Talk", symbol: "waveform", colors: [SamTheme.orb2, SamTheme.orb], height: 40)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("assistant-talk")
        }
    }

    // MARK: - History

    private var earlier: [ConversationMachine.Exchange] {
        let all = machine.exchanges.filter { !$0.heard.isEmpty || !$0.say.isEmpty }
        guard let shown else { return all.reversed() }
        return all.filter { $0.id != shown.id }.reversed()
    }

    private var history: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Earlier").font(.system(size: 11, weight: .semibold)).foregroundStyle(SamTheme.muted)
                .padding(.top, 10)
            ForEach(earlier) { exchange in
                VStack(alignment: .leading, spacing: 2) {
                    if !exchange.heard.isEmpty {
                        Text(exchange.heard).font(.system(size: 11.5)).italic().foregroundStyle(SamTheme.muted)
                    }
                    if !exchange.say.isEmpty {
                        Text(exchange.say).font(.system(size: 12.5)).foregroundStyle(SamTheme.ink2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .watchCard(tint: exchange.kind == .announcement ? SamTheme.amber : nil, padding: 8)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("assistant-history")
    }

    static func symbol(_ kind: String) -> String {
        if kind.hasPrefix("t3") { return "hammer.fill" }
        if kind.hasPrefix("calendar") { return "calendar" }
        if kind.hasPrefix("journal") { return "book.pages.fill" }
        if kind.hasPrefix("ui") || kind.hasPrefix("genui") { return "rectangle.on.rectangle" }
        if kind.hasPrefix("mac") { return "laptopcomputer" }
        return "checkmark.circle.fill"
    }
}

/// A small card the assistant showed (`{"type":"card", title, body}`).
struct AssistantCardView: View {
    let card: AssistantCard

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(card.title).font(.system(size: 11, weight: .semibold)).foregroundStyle(SamTheme.orbPale).lineLimit(1)
            Text(card.body).font(.system(size: 12.5, weight: .medium)).foregroundStyle(SamTheme.ink).lineLimit(3)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .watchCard(tint: SamTheme.orb, padding: 7)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("assistant-card")
    }
}

/// One line of status over the orb: what needs you, what's working, what's next, or the connection.
struct StatusStrip: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        HStack(spacing: 4) {
            if model.route == .phone {
                Image(systemName: "iphone").font(.system(size: 9, weight: .bold)).foregroundStyle(SamTheme.orbPale)
                    .accessibilityLabel("Through your iPhone")
            }
            if model.needsYouCount > 0 {
                Circle().fill(SamTheme.amber).frame(width: 5, height: 5)
            }
            Text(text)
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(SamTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("assistant-status")
    }

    private var text: String {
        if !model.paired { return "Pair on your iPhone" }
        if model.rejected { return "Reconnect through your iPhone" }
        guard model.summary != nil else { return model.lastError == nil ? "Connecting…" : "Is your Mac awake?" }
        var parts: [String] = []
        let needs = model.needsYouCount
        if needs > 0 { parts.append(needs == 1 ? "1 needs you" : "\(needs) need you") }
        if model.workingCount > 0 { parts.append("\(model.workingCount) working") }
        if parts.isEmpty, let next = model.events().first {
            parts.append("\(Formatting.clip(next.title, 14)) \(Formatting.until(next.startsAt, end: next.endsAt, short: true))")
        }
        if parts.isEmpty { parts.append("All clear") }
        return parts.joined(separator: " · ")
    }
}
