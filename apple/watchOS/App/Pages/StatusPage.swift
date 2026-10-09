import SamRabbitKit
import SwiftUI

/// Page 1: the orb, one line of status, and a big Ask button (voice -> a new T3 task).
struct StatusPage: View {
    @Environment(WatchModel.self) private var model
    @Environment(\.isLuminanceReduced) private var dimmed

    var body: some View {
        VStack(spacing: 3) {
            OrbView(mood: model.mood, animated: !dimmed, frameRate: 24)
                .frame(width: 74, height: 74)
                .padding(.top, 2)
                .padding(.bottom, 6)
            Text(headline)
                .font(.system(size: 19, weight: .semibold, design: .rounded))
                .foregroundStyle(SamTheme.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
            HStack(spacing: 4) {
                if model.route == .phone {
                    Image(systemName: "iphone").font(.system(size: 10, weight: .bold)).foregroundStyle(SamTheme.orbPale)
                        .accessibilityLabel("Through your iPhone")
                }
                if model.summary?.r1.live == true, !model.rejected {
                    PulseDot(color: SamTheme.green, size: 6)
                }
                Text(subline).font(.system(size: 12.5)).foregroundStyle(SamTheme.muted).lineLimit(1)
            }
            Spacer(minLength: 6)
            if model.rejected {
                Button {
                    Task { await model.connect(reissue: true) }
                } label: {
                    CapsuleFace(title: "Reconnect", symbol: "iphone", colors: [SamTheme.amber, SamTheme.amber.opacity(0.7)],
                                busy: model.connecting)
                }
                .buttonStyle(.plain)
            } else {
                VoiceButton(title: model.busy.contains("ask") ? "Starting…" : "Ask", purpose: .ask,
                            colors: [SamTheme.orb2, SamTheme.orb], height: 48, busy: model.busy.contains("ask"))
                    .accessibilityHint("Say a task for T3 Code")
            }
        }
        .padding(.horizontal, 6)
        .withBanner()
        .samPage(SamTheme.orb)
    }

    private var headline: String {
        if model.rejected { return "Watch removed" }
        guard model.summary != nil else { return model.lastError == nil ? "SamRabbit" : "Mac unreachable" }
        let needs = model.needsYouCount
        if needs > 0 { return needs == 1 ? "1 needs you" : "\(needs) need you" }
        if model.workingCount > 0 { return "\(model.workingCount) working" }
        return "All clear"
    }

    private var subline: String {
        if model.rejected { return "Reconnect through your iPhone" }
        guard let summary = model.summary else {
            return model.lastError.map { _ in "Is your Mac awake?" } ?? "Connecting…"
        }
        if summary.r1.live { return "R1 live · \(Formatting.clip(summary.r1.liveTitle ?? "Talking", 18))" }
        var parts: [String] = []
        if model.needsYouCount > 0, model.workingCount > 0 { parts.append("\(model.workingCount) working") }
        parts.append("R1 \(Formatting.ago(summary.r1.lastSeenAt))")
        if model.lastError != nil, let date = model.summaryDate { parts = ["As of \(Formatting.ago(date))"] }
        return parts.joined(separator: " · ")
    }
}
