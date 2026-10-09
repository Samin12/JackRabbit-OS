import SamRabbitKit
import SwiftUI

/// What a `samrabbit://` link asked for (`samrabbit://block?minutes=45`, `ask?text=…`, `note?text=…`,
/// `mac/open?app=…`, `generate?text=…`), shown before anything happens. Links can come from any web
/// page or message, so nothing is written until the person taps Confirm here.
struct LinkConfirmSheet: View {
    let action: LinkAction
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var working = false

    var body: some View {
        VStack(spacing: 18) {
            OrbView(mood: working ? .working : .idle, halo: false)
                .frame(width: 64, height: 64)
                .padding(.top, 26)
            VStack(spacing: 6) {
                Text("A link wants to")
                    .font(SamTheme.eyebrow).tracking(0.5).textCase(.uppercase).foregroundStyle(SamTheme.faint)
                Text(action.headline)
                    .font(.system(size: 22, weight: .bold))
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("link-confirm-headline")
            }
            details
            Text("Nothing happens until you confirm.")
                .font(.system(size: 13)).foregroundStyle(SamTheme.muted)
            Spacer(minLength: 0)
            VStack(spacing: 10) {
                Button {
                    confirm()
                } label: {
                    Group {
                        if working { ProgressView() } else { Text(action.confirmTitle).fontWeight(.semibold) }
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.extraLarge)
                .disabled(working)
                .accessibilityIdentifier("link-confirm")
                HStack(spacing: 10) {
                    if action.payload != nil, !isBlock {
                        Button("Edit first") { model.edit(action) }
                            .buttonStyle(.glass).controlSize(.large)
                            .disabled(working)
                    }
                    Button("Cancel", role: .cancel) { dismiss() }
                        .buttonStyle(.glass).controlSize(.large)
                        .disabled(working)
                        .accessibilityIdentifier("link-cancel")
                }
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .samScreen()
        .presentationDetents([.fraction(0.66), .large])
        .interactiveDismissDisabled(working)
    }

    private var isBlock: Bool {
        if case .block = action { return true }
        return false
    }

    @ViewBuilder
    private var details: some View {
        switch action {
        case .block(let minutes, let title):
            TimelineView(.periodic(from: .now, by: 30)) { context in
                let end = context.date.addingTimeInterval(TimeInterval(minutes * 60))
                VStack(spacing: 4) {
                    Text("Now until \(Formatting.time(end))")
                        .font(.system(size: 17, weight: .semibold, design: .rounded))
                    Text("“\(title ?? "Focus")” on your calendar")
                        .font(.system(size: 14)).foregroundStyle(SamTheme.ink2)
                }
                .frame(maxWidth: .infinity)
                .glassCard(radius: 18, tint: SamTheme.violet, padding: 14)
            }
        case .openOnMac(let app, let url):
            Text(url ?? app ?? "")
                .font(.system(size: 15, design: url == nil ? .default : .monospaced))
                .foregroundStyle(SamTheme.ink)
                .lineLimit(4)
                .frame(maxWidth: .infinity, alignment: .leading)
                .glassCard(radius: 18, padding: 14)
        case .ask(let text), .note(let text), .generate(let text):
            ScrollView {
                Text("“\(text)”")
                    .font(.system(size: 16))
                    .foregroundStyle(SamTheme.ink)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxHeight: 180)
            .fixedSize(horizontal: false, vertical: true)
            .glassCard(radius: 18, padding: 14)
            Text(explainer).font(.system(size: 13.5)).foregroundStyle(SamTheme.ink2).multilineTextAlignment(.center)
        }
    }

    private var explainer: String {
        switch action {
        case .ask: "A new task on your Mac. SamRabbit picks the project."
        case .note: "Added word for word to today's Heptabase journal."
        case .generate: "Made on your Mac and kept in its Phone conversation."
        default: ""
        }
    }

    private func confirm() {
        guard !working else { return }
        working = true
        Task {
            let done = await model.perform(action)
            working = false
            if done, case .generate = action { return } // the composer took over the sheet
            if done { dismiss() }
        }
    }
}
