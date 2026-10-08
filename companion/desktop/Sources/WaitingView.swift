import AppKit
import SwiftUI

enum Palette {
    static let background = Color(red: 9 / 255, green: 11 / 255, blue: 16 / 255)
    static let backgroundTop = Color(red: 13 / 255, green: 17 / 255, blue: 26 / 255)
    static let ink = Color(red: 245 / 255, green: 248 / 255, blue: 255 / 255)
    static let muted = Color(red: 140 / 255, green: 152 / 255, blue: 172 / 255)
    static let orb = Color(red: 26 / 255, green: 115 / 255, blue: 242 / 255)
    static let pale = Color(red: 160 / 255, green: 199 / 255, blue: 255 / 255)
}

/// The fluid orb as a SwiftUI view: pre-rendered once (OrbRenderer), floated with a gentle bob.
struct OrbImage: View {
    let size: CGFloat
    var dim = false
    private static var cache: [Int: NSImage] = [:]

    private var image: NSImage? {
        let pixels = Int(size * 2)
        if let cached = Self.cache[pixels] { return cached }
        guard let cg = OrbRenderer.image(pixels: pixels, radiusFraction: 0.3, centerYFraction: 0.44, time: 2.0,
                                         energy: 0.12, glow: 1.0) else { return nil }
        let image = NSImage(cgImage: cg, size: NSSize(width: size, height: size))
        Self.cache[pixels] = image
        return image
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            Group {
                if let image {
                    Image(nsImage: image).resizable().interpolation(.high)
                } else {
                    Circle().fill(Palette.orb).padding(size * 0.2)
                }
            }
            .frame(width: size, height: size)
            .offset(y: CGFloat(sin(t * 0.9)) * 4)
            .opacity(dim ? 0.55 : 1)
        }
    }
}

struct WaitingView: View {
    @ObservedObject var model: WaitingModel

    var body: some View {
        ZStack {
            LinearGradient(colors: [Palette.backgroundTop, Palette.background], startPoint: .top, endPoint: .center)
            RadialGradient(colors: [Palette.orb.opacity(0.16), .clear], center: UnitPoint(x: 0.78, y: -0.1),
                           startRadius: 0, endRadius: 620)
            VStack(spacing: 0) {
                OrbImage(size: 230, dim: model.reason != .starting)
                    .padding(.bottom, -26)
                Text(model.title)
                    .font(.system(size: 23, weight: .semibold))
                    .foregroundStyle(Palette.ink)
                    .padding(.bottom, 10)
                Text(model.detail)
                    .font(.system(size: 14))
                    .foregroundStyle(Palette.muted)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .frame(maxWidth: 460)
                if let command = model.command {
                    Text(command)
                        .font(.system(size: 12.5, design: .monospaced))
                        .foregroundStyle(Palette.ink.opacity(0.85))
                        .textSelection(.enabled)
                        .padding(.horizontal, 11).padding(.vertical, 5)
                        .background(RoundedRectangle(cornerRadius: 8).fill(Color.white.opacity(0.06)))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.white.opacity(0.08)))
                        .padding(.top, 12)
                }
                HStack(spacing: 14) {
                    if model.reason != .starting {
                        Button(action: model.retry) {
                            Label("Retry now", systemImage: "arrow.clockwise")
                                .font(.system(size: 13, weight: .semibold))
                                .padding(.horizontal, 14).padding(.vertical, 7)
                                .foregroundStyle(.white)
                                .background(Capsule().fill(LinearGradient(colors: [Color(red: 0.16, green: 0.51, blue: 1),
                                                                                  Color(red: 0.1, green: 0.42, blue: 0.9)],
                                                                         startPoint: .top, endPoint: .bottom)))
                                .shadow(color: Palette.orb.opacity(0.35), radius: 10, y: 4)
                        }
                        .buttonStyle(.plain)
                    }
                    if model.retrying {
                        HStack(spacing: 7) {
                            ProgressView().controlSize(.small)
                            Text(model.reason == .starting ? "Connecting…" : "Retrying automatically")
                                .font(.system(size: 12))
                                .foregroundStyle(Palette.muted)
                        }
                    }
                }
                .padding(.top, 22)
            }
            .padding(40)
            .offset(y: -24)
        }
        .ignoresSafeArea()
        .opacity(model.visible ? 1 : 0)
        .animation(.easeOut(duration: 0.25), value: model.visible)
        .preferredColorScheme(.dark)
    }
}
