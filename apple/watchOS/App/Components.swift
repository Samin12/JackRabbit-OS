import SamRabbitKit
import SwiftUI

// Small building blocks shared by the watch pages: cards, the page background, the dictation
// buttons and the result banner.

extension View {
    /// A watch card: a translucent night panel with a hairline rim and an optional tint bloom.
    func watchCard(tint: Color? = nil, padding: CGFloat = 10) -> some View {
        self
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(colors: [Color.white.opacity(0.11), Color.white.opacity(0.06)],
                                         startPoint: .top, endPoint: .bottom))
                    .overlay {
                        if let tint {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(RadialGradient(colors: [tint.opacity(0.22), .clear], center: .topLeading,
                                                     startRadius: 0, endRadius: 150))
                        }
                    }
            }
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(Color.white.opacity(0.10), lineWidth: 0.6)
            }
    }

    /// The page background behind a vertically paged tab: night with a soft tint from the top.
    func samPage(_ tint: Color) -> some View {
        containerBackground(for: .tabView) {
            ZStack {
                SamTheme.night
                LinearGradient(colors: [tint.opacity(0.42), tint.opacity(0.10), .clear],
                               startPoint: .top, endPoint: .center)
            }
        }
    }
}

/// A full-width capsule button face.
struct CapsuleFace: View {
    var title: String
    var symbol: String
    var colors: [Color]
    var height: CGFloat = 44
    var busy = false

    var body: some View {
        HStack(spacing: 6) {
            if busy {
                ProgressView().controlSize(.small)
            } else {
                Image(systemName: symbol).font(.system(size: 15, weight: .bold))
            }
            Text(title).font(.system(size: 16, weight: .semibold)).lineLimit(1).minimumScaleFactor(0.8)
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, minHeight: height)
        .background(Capsule().fill(LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)))
        .overlay(Capsule().strokeBorder(Color.white.opacity(0.18), lineWidth: 0.6))
        .contentShape(Capsule())
    }
}

/// A button that opens the watch's text input (dictation first, then Scribble or the keyboard) and
/// hands over what was said.
struct DictationButton: View {
    var title: String
    var symbol: String = "mic.fill"
    var prompt: String
    var colors: [Color]
    var height: CGFloat = 44
    var busy = false
    var onSubmit: (String) -> Void

    var body: some View {
        TextFieldLink(prompt: Text(prompt)) {
            CapsuleFace(title: title, symbol: symbol, colors: colors, height: height, busy: busy)
        } onSubmit: { text in
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { onSubmit(trimmed) }
        }
        .buttonStyle(.plain)
        .disabled(busy)
    }
}

/// The result of the last action, over the top of a page.
struct BannerView: View {
    let banner: WatchBanner

    var body: some View {
        let color = banner.style == .success ? SamTheme.green : SamTheme.red
        HStack(alignment: .top, spacing: 7) {
            Image(systemName: banner.style == .success ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(color)
            VStack(alignment: .leading, spacing: 1) {
                Text(banner.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(SamTheme.ink)
                if let detail = banner.detail {
                    Text(detail).font(.system(size: 12)).foregroundStyle(SamTheme.ink2).lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color(hex: 0x141B2A).opacity(0.96)))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(color.opacity(0.5), lineWidth: 0.8))
        .shadow(color: .black.opacity(0.5), radius: 8, y: 3)
        .transition(.move(edge: .top).combined(with: .opacity))
        .accessibilityElement(children: .combine)
    }
}

/// Shows the model's banner over a page.
struct BannerOverlay: ViewModifier {
    @Environment(WatchModel.self) private var model

    func body(content: Content) -> some View {
        content.overlay(alignment: .top) {
            if let banner = model.banner {
                BannerView(banner: banner)
                    .onTapGesture { withAnimation { model.banner = nil } }
                    .padding(.horizontal, 2)
            }
        }
        .animation(.spring(duration: 0.35), value: model.banner)
    }
}

extension View {
    func withBanner() -> some View { modifier(BannerOverlay()) }
}

/// A centered note for empty pages.
struct EmptyNote: View {
    var symbol: String
    var title: String
    var detail: String?
    var tint: Color = SamTheme.green

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: symbol).font(.system(size: 28, weight: .semibold)).foregroundStyle(tint)
            Text(title).font(.system(size: 15, weight: .semibold)).foregroundStyle(SamTheme.ink)
            if let detail {
                Text(detail).font(.system(size: 12.5)).foregroundStyle(SamTheme.muted).multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
    }
}

/// "T3 not connected" above the task pages while the Mac answers but T3 Code on it does not.
struct TasksNoticeRow: View {
    let error: BridgeError

    var body: some View {
        HStack(alignment: .top, spacing: 6) {
            Image(systemName: "bolt.horizontal.circle.fill").font(.system(size: 13, weight: .semibold))
                .foregroundStyle(SamTheme.amber)
            VStack(alignment: .leading, spacing: 1) {
                Text(error.isTaskServiceDown ? "T3 not connected" : "Tasks didn't update")
                    .font(.system(size: 13.5, weight: .semibold)).foregroundStyle(SamTheme.ink)
                Text(error.isTaskServiceDown ? "Open T3 Code on your Mac." : (error.watchDetail ?? "Try again soon."))
                    .font(.system(size: 11.5)).foregroundStyle(SamTheme.muted)
            }
            Spacer(minLength: 0)
        }
        .watchCard(tint: SamTheme.amber)
        .accessibilityIdentifier("t3-notice")
    }
}

/// "Assistant · 2 min ago"
struct ThreadMeta: View {
    var thread: TaskThread
    var now: Date = .now

    var body: some View {
        let parts = [thread.projectName, thread.updatedAt.map { Formatting.ago($0, now: now) }].compactMap { $0 }
        Text(parts.joined(separator: " · "))
            .font(.system(size: 11.5))
            .foregroundStyle(SamTheme.muted)
            .lineLimit(1)
    }
}
