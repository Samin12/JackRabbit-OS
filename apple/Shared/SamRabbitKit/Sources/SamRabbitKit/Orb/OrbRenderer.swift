import CoreGraphics
import Foundation

/// The SamRabbit fluid orb, drawn on the CPU with the same math as the R1's FluidOrb shader and the
/// desktop app's `OrbRenderer.swift`: white crown, pale middle band, saturated base, wavy drifting
/// boundaries and a soft halo.
///
/// `field` renders only the orb's surface (no halo, no edge) into a small square image; `OrbView`
/// clips it to a vector circle and draws the halo as a gradient, so a 150 pt orb needs only about
/// 150 x 150 samples per frame. `image` renders the complete orb with halo (icons, widgets).
public enum OrbRenderer {
    public struct RGB: Sendable, Equatable {
        public var r: Float
        public var g: Float
        public var b: Float

        public init(r: Float, g: Float, b: Float) {
            self.r = r
            self.g = g
            self.b = b
        }

        public init(hex: UInt32) {
            r = Float((hex >> 16) & 0xFF) / 255
            g = Float((hex >> 8) & 0xFF) / 255
            b = Float(hex & 0xFF) / 255
        }

        /// 60% toward white (the middle band's tint).
        public var pale: RGB { RGB(r: r + (1 - r) * 0.6, g: g + (1 - g) * 0.6, b: b + (1 - b) * 0.6) }
    }

    /// SamTheme's orb blue (#1A73F2).
    public static let blue = RGB(r: 26 / 255, g: 115 / 255, b: 242 / 255)

    @inline(__always) static func mix(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }

    @inline(__always) static func smoothstep(_ e0: Float, _ e1: Float, _ x: Float) -> Float {
        let t = min(1, max(0, (x - e0) / (e1 - e0)))
        return t * t * (3 - 2 * t)
    }

    @inline(__always) static func wave(_ x: Float, _ t: Float, _ a: Float, _ b: Float, _ c: Float) -> Float {
        0.16 * sinf(x * a + t * b + c) + 0.09 * sinf(x * (a * 1.9) - t * (b * 1.4) + c * 2.3)
            + 0.05 * sinf(x * (a * 3.7) + t * (b * 0.6) - c)
    }

    /// The surface colour at unit-disc coordinates (y down); `d` is the distance from the centre.
    @inline(__always)
    static func surface(_ ux: Float, _ uy: Float, _ d: Float, time t: Float, cosA: Float, sinA: Float, k: Float,
                        base: RGB, tint: RGB) -> RGB {
        let rx = ux * cosA - uy * sinA
        let ry = ux * sinA + uy * cosA
        let x = rx + 0.25 * sinf(t * 0.17 + ry * 1.3)
        let y = ry + 0.06 * sinf(t * 0.33 + rx * 2.1)
        let b1 = -0.28 + wave(x, t * 0.55, 2.2, 1.0, 0.4) * k
        let b2 = 0.22 + wave(x, t * 0.48, 2.7, -0.8, 1.9) * k
        let s1 = 0.10 + 0.05 * sinf(t * 0.4 + x * 3.0)
        let s2 = 0.09 + 0.04 * sinf(t * 0.5 - x * 2.4)
        let m1 = smoothstep(b1 - s1, b1 + s1, y)
        var r = mix(0.99, tint.r, m1)
        var g = mix(1.0, tint.g, m1)
        var b = mix(1.0, tint.b, m1)
        let m2 = smoothstep(b2 - s2, b2 + s2, y)
        r = mix(r, base.r, m2)
        g = mix(g, base.g, m2)
        b = mix(b, base.b, m2)
        let streak = smoothstep(0.05, 0.0, abs(y - b2 + 0.12 * sinf(x * 4.0 + t))) * 0.12
        r = mix(r, tint.r, streak)
        g = mix(g, tint.g, streak)
        b = mix(b, tint.b, streak)
        let dc = min(d, 1)
        let shade = 1 - 0.10 * dc * dc * dc
        return RGB(r: r * shade, g: g * shade, b: b * shade)
    }

    /// The orb surface for a whole square (the corners continue the pattern so a clip edge has
    /// colour under its anti-aliasing). Opaque; clip it to a circle.
    public static func field(pixels: Int, time: Double, energy: Double, base: RGB = blue) -> CGImage? {
        let side = max(8, min(pixels, 512))
        var data = [UInt8](repeating: 255, count: side * side * 4)
        let t = Float(time.truncatingRemainder(dividingBy: 6283.185307))
        let ang = 0.18 * sinf(t * 0.21)
        let cosA = cosf(ang), sinA = sinf(ang)
        let k = 1 + Float(energy) * 1.6
        let tint = base.pale
        let scale = 2 / Float(side)
        data.withUnsafeMutableBufferPointer { buffer in
            for py in 0..<side {
                let uy = (Float(py) + 0.5) * scale - 1
                var i = py * side * 4
                for px in 0..<side {
                    let ux = (Float(px) + 0.5) * scale - 1
                    let d = (ux * ux + uy * uy).squareRoot()
                    let c = surface(ux, uy, d, time: t, cosA: cosA, sinA: sinA, k: k, base: base, tint: tint)
                    buffer[i] = UInt8(min(255, max(0, c.r * 255 + 0.5)))
                    buffer[i + 1] = UInt8(min(255, max(0, c.g * 255 + 0.5)))
                    buffer[i + 2] = UInt8(min(255, max(0, c.b * 255 + 0.5)))
                    i += 4
                }
            }
        }
        return makeImage(&data, side: side, alpha: .noneSkipLast)
    }

    /// The complete orb with its halo (the desktop app's `OrbRenderer.image`): a square image with
    /// the orb centred; `radiusFraction` is the orb radius as a fraction of the side.
    public static func image(pixels: Int, radiusFraction: Double = 0.3, centerYFraction: Double = 0.5,
                             time: Double = 7.3, energy: Double = 0.12, glow: Double = 1.0, base: RGB = blue) -> CGImage? {
        let w = max(1, min(pixels, 2048))
        let radius = Float(Double(w) * radiusFraction)
        let cx = Float(w) / 2
        let cy = Float(Double(w) * centerYFraction)
        let tint = base.pale
        let t = Float(time)
        let ang = 0.18 * sinf(t * 0.21)
        let cosA = cosf(ang), sinA = sinf(ang)
        let k = 1 + Float(energy) * 1.6
        let glowRadius = radius * (1.55 + Float(energy) * 0.25)
        let glowStrength = Float(glow)
        var data = [UInt8](repeating: 0, count: w * w * 4)
        data.withUnsafeMutableBufferPointer { buffer in
            for py in 0..<w {
                for px in 0..<w {
                    let x = Float(px) + 0.5
                    let y = Float(py) + 0.5
                    var r: Float = 0, g: Float = 0, b: Float = 0, a: Float = 0
                    if glowStrength > 0 {
                        let gd = hypotf(x - cx, y - (cy + radius * 0.25)) / glowRadius
                        var ga: Float = gd < 0.35 ? 0.431 : gd < 0.7 ? mix(0.431, 0.133, (gd - 0.35) / 0.35)
                            : gd < 1.0 ? mix(0.133, 0, (gd - 0.7) / 0.3) : 0
                        ga *= glowStrength
                        r = base.r * ga
                        g = base.g * ga
                        b = base.b * ga
                        a = ga
                    }
                    let ux = (x - cx) / radius
                    let uy = (y - cy) / radius
                    let d = hypotf(ux, uy)
                    if d <= 1 {
                        let c = surface(ux, uy, d, time: t, cosA: cosA, sinA: sinA, k: k, base: base, tint: tint)
                        let oa = min(1, max(0, (1 - d) * radius * 0.9))
                        r = c.r * oa + r * (1 - oa)
                        g = c.g * oa + g * (1 - oa)
                        b = c.b * oa + b * (1 - oa)
                        a = oa + a * (1 - oa)
                    }
                    let i = (py * w + px) * 4
                    buffer[i] = UInt8(min(255, max(0, r * 255 + 0.5)))
                    buffer[i + 1] = UInt8(min(255, max(0, g * 255 + 0.5)))
                    buffer[i + 2] = UInt8(min(255, max(0, b * 255 + 0.5)))
                    buffer[i + 3] = UInt8(min(255, max(0, a * 255 + 0.5)))
                }
            }
        }
        return makeImage(&data, side: w, alpha: .premultipliedLast)
    }

    private static func makeImage(_ data: inout [UInt8], side: Int, alpha: CGImageAlphaInfo) -> CGImage? {
        let space = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        return data.withUnsafeMutableBytes { buffer -> CGImage? in
            guard let context = CGContext(data: buffer.baseAddress, width: side, height: side, bitsPerComponent: 8,
                                          bytesPerRow: side * 4, space: space, bitmapInfo: alpha.rawValue) else {
                return nil
            }
            return context.makeImage()
        }
    }
}
