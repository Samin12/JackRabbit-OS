import CoreGraphics
import Foundation

/// The SamRabbit fluid orb, drawn on the CPU with the same math as the R1's FluidOrb shader:
/// white crown, pale middle band, saturated base, wavy boundaries and a soft halo.
/// Used for the app icon (tools/render_icon.swift) and the native "waiting" screen.
enum OrbRenderer {
    struct RGB {
        var r: Double
        var g: Double
        var b: Double
    }

    static let blue = RGB(r: 26 / 255, g: 115 / 255, b: 242 / 255)

    static func pale(_ c: RGB) -> RGB {
        RGB(r: c.r + (1 - c.r) * 0.6, g: c.g + (1 - c.g) * 0.6, b: c.b + (1 - c.b) * 0.6)
    }

    private static func mix(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }

    private static func smoothstep(_ e0: Double, _ e1: Double, _ x: Double) -> Double {
        let t = min(1, max(0, (x - e0) / (e1 - e0)))
        return t * t * (3 - 2 * t)
    }

    private static func wave(_ x: Double, _ t: Double, _ a: Double, _ b: Double, _ c: Double) -> Double {
        0.16 * sin(x * a + t * b + c) + 0.09 * sin(x * (a * 1.9) - t * (b * 1.4) + c * 2.3)
            + 0.05 * sin(x * (a * 3.7) + t * (b * 0.6) - c)
    }

    /// Premultiplied RGBA of the orb surface at unit-disc coordinates (y down), d = length.
    private static func surface(_ ux: Double, _ uy: Double, _ d: Double, time t: Double, energy: Double,
                                base: RGB, tint: RGB) -> RGB {
        let k = 1 + energy * 1.6
        let ang = 0.18 * sin(t * 0.21)
        let rx = ux * cos(ang) - uy * sin(ang)
        let ry = ux * sin(ang) + uy * cos(ang)
        let x = rx + 0.25 * sin(t * 0.17 + ry * 1.3)
        let y = ry + 0.06 * sin(t * 0.33 + rx * 2.1)
        let b1 = -0.28 + wave(x, t * 0.55, 2.2, 1.0, 0.4) * k
        let b2 = 0.22 + wave(x, t * 0.48, 2.7, -0.8, 1.9) * k
        let s1 = 0.10 + 0.05 * sin(t * 0.4 + x * 3.0)
        let s2 = 0.09 + 0.04 * sin(t * 0.5 - x * 2.4)
        let white = RGB(r: 0.99, g: 1.0, b: 1.0)
        let m1 = smoothstep(b1 - s1, b1 + s1, y)
        var c = RGB(r: mix(white.r, tint.r, m1), g: mix(white.g, tint.g, m1), b: mix(white.b, tint.b, m1))
        let m2 = smoothstep(b2 - s2, b2 + s2, y)
        c = RGB(r: mix(c.r, base.r, m2), g: mix(c.g, base.g, m2), b: mix(c.b, base.b, m2))
        let streak = smoothstep(0.05, 0.0, abs(y - b2 + 0.12 * sin(x * 4.0 + t))) * 0.12
        c = RGB(r: mix(c.r, tint.r, streak), g: mix(c.g, tint.g, streak), b: mix(c.b, tint.b, streak))
        let shade = 1 - 0.10 * d * d * d
        return RGB(r: c.r * shade, g: c.g * shade, b: c.b * shade)
    }

    /// A square image with the orb centred. `radiusFraction` is the orb radius as a fraction of the
    /// image side; the halo extends to ~1.55 x radius below-centre when `glow` > 0.
    static func image(pixels: Int, radiusFraction: Double = 0.3, centerYFraction: Double = 0.5,
                      time: Double = 7.3, energy: Double = 0.12, glow: Double = 1.0, base: RGB = blue) -> CGImage? {
        let w = max(1, pixels)
        let h = w
        let radius = Double(w) * radiusFraction
        let cx = Double(w) / 2
        let cy = Double(h) * centerYFraction
        let tint = pale(base)
        var data = [UInt8](repeating: 0, count: w * h * 4)
        let glowRadius = radius * (1.55 + energy * 0.25)
        for py in 0..<h {
            for px in 0..<w {
                let x = Double(px) + 0.5
                let y = Double(py) + 0.5
                var r = 0.0, g = 0.0, b = 0.0, a = 0.0
                if glow > 0 {
                    let gd = hypot(x - cx, y - (cy + radius * 0.25)) / glowRadius
                    var ga = gd < 0.35 ? 0.431 : gd < 0.7 ? mix(0.431, 0.133, (gd - 0.35) / 0.35)
                        : gd < 1.0 ? mix(0.133, 0, (gd - 0.7) / 0.3) : 0
                    ga *= glow
                    r = base.r * ga
                    g = base.g * ga
                    b = base.b * ga
                    a = ga
                }
                let ux = (x - cx) / radius
                let uy = (y - cy) / radius
                let d = hypot(ux, uy)
                if d <= 1 {
                    let c = surface(ux, uy, d, time: time, energy: energy, base: base, tint: tint)
                    let oa = min(1, max(0, (1 - d) * radius * 0.9))
                    r = c.r * oa + r * (1 - oa)
                    g = c.g * oa + g * (1 - oa)
                    b = c.b * oa + b * (1 - oa)
                    a = oa + a * (1 - oa)
                }
                let i = (py * w + px) * 4
                data[i] = UInt8(min(255, max(0, (r * 255).rounded())))
                data[i + 1] = UInt8(min(255, max(0, (g * 255).rounded())))
                data[i + 2] = UInt8(min(255, max(0, (b * 255).rounded())))
                data[i + 3] = UInt8(min(255, max(0, (a * 255).rounded())))
            }
        }
        let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        return data.withUnsafeMutableBytes { buffer -> CGImage? in
            guard let context = CGContext(data: buffer.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                          bytesPerRow: w * 4, space: space,
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            return context.makeImage()
        }
    }
}
