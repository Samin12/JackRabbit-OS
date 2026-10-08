import SwiftUI

/// The SamRabbit design language on Apple platforms: the R1's SamTheme and the desktop app's
/// tokens (deep night background, the orb blue, glass panels with hairline rims).
public enum SamTheme {
    // MARK: Colours (desktop web/styles.css :root)
    public static let night = Color(hex: 0x090B10)
    public static let nightTop = Color(hex: 0x0D111A)
    public static let navy = Color(hex: 0x0B1324)
    public static let ink = Color(hex: 0xF5F8FF)
    public static let ink2 = Color(hex: 0xD5DDEC)
    public static let muted = Color(hex: 0x8C98AC)
    public static let faint = Color(hex: 0x5F6A80)
    public static let orb = Color(hex: 0x1A73F2)
    public static let orb2 = Color(hex: 0x3D8BFF)
    public static let orbPale = Color(hex: 0xA0C7FF)
    /// The interactive accent (#4A9EFF).
    public static let accent = Color(hex: 0x4A9EFF)
    public static let cyan = Color(hex: 0x5CA2FF)
    public static let violet = Color(hex: 0x7C6CFF)
    public static let mint = Color(hex: 0x68DECC)
    public static let amber = Color(hex: 0xFFC45C)
    public static let red = Color(hex: 0xFF6363)
    public static let green = Color(hex: 0x60D6A0)
    public static let pink = Color(hex: 0xFF5CA8)
    public static let line = Color(red: 190 / 255, green: 210 / 255, blue: 1).opacity(0.09)
    public static let line2 = Color(red: 190 / 255, green: 210 / 255, blue: 1).opacity(0.16)
    public static let glass = Color(red: 22 / 255, green: 27 / 255, blue: 39 / 255).opacity(0.66)
    public static let glass2 = Color(red: 29 / 255, green: 35 / 255, blue: 50 / 255).opacity(0.74)

    /// The generated-card accents by name.
    public static func accent(named name: String?) -> Color {
        switch name {
        case "violet": violet
        case "cyan": cyan
        case "mint": mint
        case "pink": pink
        case "amber": amber
        case "red": red
        case "green": green
        default: orb2
        }
    }

    /// The full-screen backdrop: night gradient with the blue blooms of the desktop app.
    @MainActor public static var background: some View { SamBackdrop() }

    // MARK: Type
    public static func display(_ size: CGFloat, weight: Font.Weight = .semibold) -> Font {
        .system(size: size, weight: weight, design: .default)
    }

    public static let eyebrow = Font.system(size: 11, weight: .semibold).width(.expanded)
}

/// The night backdrop with soft blue blooms (static: cheap enough for every screen).
public struct SamBackdrop: View {
    public var intensity: Double

    public init(intensity: Double = 1) { self.intensity = intensity }

    public var body: some View {
        GeometryReader { geometry in
            let w = geometry.size.width
            let h = geometry.size.height
            ZStack {
                LinearGradient(colors: [SamTheme.nightTop, SamTheme.navy.opacity(0.92), SamTheme.night],
                               startPoint: .top, endPoint: .bottom)
                RadialGradient(colors: [SamTheme.orb.opacity(0.22 * intensity), .clear], center: .init(x: 0.82, y: -0.06),
                               startRadius: 0, endRadius: max(w, h) * 0.75)
                RadialGradient(colors: [SamTheme.violet.opacity(0.08 * intensity), .clear], center: .init(x: 1.05, y: 1.02),
                               startRadius: 0, endRadius: max(w, h) * 0.6)
                RadialGradient(colors: [SamTheme.orb.opacity(0.11 * intensity), .clear], center: .init(x: -0.1, y: 0.02),
                               startRadius: 0, endRadius: max(w, h) * 0.5)
            }
        }
        .ignoresSafeArea()
    }
}

/// A glass panel: translucent night fill over the backdrop, a light top sheen, a hairline rim and
/// a soft shadow (the desktop `.gcard` / `--panel-shadow`).
public struct GlassCard: ViewModifier {
    var radius: CGFloat
    var tint: Color?
    var padding: CGFloat?

    public func body(content: Content) -> some View {
        content
            .padding(padding ?? 16)
            .background {
                ZStack {
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(.ultraThinMaterial)
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(LinearGradient(colors: [SamTheme.glass2, SamTheme.glass.opacity(0.9)],
                                             startPoint: .top, endPoint: .bottom))
                    if let tint {
                        RoundedRectangle(cornerRadius: radius, style: .continuous)
                            .fill(RadialGradient(colors: [tint.opacity(0.20), .clear], center: .topLeading,
                                                 startRadius: 0, endRadius: 260))
                    }
                    RoundedRectangle(cornerRadius: radius, style: .continuous)
                        .fill(LinearGradient(colors: [.white.opacity(0.055), .clear], startPoint: .top, endPoint: .center))
                }
            }
            .overlay {
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(LinearGradient(colors: [Color.white.opacity(0.13), SamTheme.line],
                                                 startPoint: .top, endPoint: .bottom), lineWidth: 0.75)
            }
            .shadow(color: .black.opacity(0.32), radius: 16, y: 8)
    }
}

extension View {
    /// Wraps the view in a SamRabbit glass panel.
    public func glassCard(radius: CGFloat = 22, tint: Color? = nil, padding: CGFloat? = nil) -> some View {
        modifier(GlassCard(radius: radius, tint: tint, padding: padding))
    }

    /// The SamRabbit screen chrome: night backdrop, dark scheme, accent tint.
    public func samScreen() -> some View {
        self
            .scrollContentBackground(.hidden)
            .background(SamBackdrop())
            .tint(SamTheme.accent)
            .preferredColorScheme(.dark)
    }
}

/// Small uppercase section label.
public struct SectionHeader: View {
    var title: String
    var count: Int?
    var symbol: String?

    public init(_ title: String, count: Int? = nil, symbol: String? = nil) {
        self.title = title
        self.count = count
        self.symbol = symbol
    }

    public var body: some View {
        HStack(spacing: 7) {
            if let symbol {
                Image(systemName: symbol).font(.system(size: 11, weight: .bold)).foregroundStyle(SamTheme.orbPale)
            }
            Text(title.uppercased())
                .font(SamTheme.eyebrow)
                .tracking(0.6)
                .foregroundStyle(SamTheme.muted)
            if let count, count > 0 {
                Text("\(count)")
                    .font(.system(size: 11, weight: .bold, design: .rounded))
                    .foregroundStyle(SamTheme.ink)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 1.5)
                    .background(Capsule().fill(SamTheme.line2))
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
    }
}

/// Colour, symbol and words for a thread status.
public struct StatusStyle: Sendable {
    public var color: Color
    public var symbol: String
    public var label: String

    public init(_ status: ThreadStatus) {
        label = status.label
        switch status {
        case .needsApproval:
            color = SamTheme.amber
            symbol = "hand.raised.fill"
        case .needsInput:
            color = SamTheme.violet
            symbol = "questionmark.bubble.fill"
        case .working:
            color = SamTheme.cyan
            symbol = "circle.dotted.circle"
        case .done:
            color = SamTheme.green
            symbol = "checkmark.circle.fill"
        case .error:
            color = SamTheme.red
            symbol = "exclamationmark.triangle.fill"
        case .idle, .unknown:
            color = SamTheme.muted
            symbol = "circle"
        }
    }
}

/// A capsule chip for a thread status.
public struct StatusChip: View {
    var status: ThreadStatus
    var compact: Bool

    public init(_ status: ThreadStatus, compact: Bool = false) {
        self.status = status
        self.compact = compact
    }

    public var body: some View {
        let style = StatusStyle(status)
        HStack(spacing: 5) {
            if status == .working {
                PulseDot(color: style.color)
            } else {
                Image(systemName: style.symbol).font(.system(size: compact ? 9 : 10, weight: .bold))
            }
            if !compact { Text(style.label).font(.system(size: 11.5, weight: .semibold)) }
        }
        .foregroundStyle(style.color)
        .padding(.horizontal, compact ? 6 : 9)
        .padding(.vertical, 4)
        .background(Capsule().fill(style.color.opacity(0.14)))
        .overlay(Capsule().strokeBorder(style.color.opacity(0.25), lineWidth: 0.5))
    }
}

/// A softly pulsing dot (live / working).
public struct PulseDot: View {
    var color: Color
    var size: CGFloat
    @State private var on = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    public init(color: Color = SamTheme.green, size: CGFloat = 7) {
        self.color = color
        self.size = size
    }

    public var body: some View {
        Circle()
            .fill(color)
            .frame(width: size, height: size)
            .background(Circle().fill(color.opacity(0.45)).scaleEffect(on ? 2.4 : 1).opacity(on ? 0 : 0.9))
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.easeOut(duration: 1.4).repeatForever(autoreverses: false)) { on = true }
            }
    }
}

extension Color {
    /// `Color(hex: 0x4A9EFF)`
    public init(hex: UInt32, opacity: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}
