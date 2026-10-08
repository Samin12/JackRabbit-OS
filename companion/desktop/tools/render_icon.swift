// Renders the SamRabbit app icon (1024 x 1024 PNG): the fluid orb on a dark glass squircle,
// following the macOS icon grid (824 pt body, 100 pt margin, soft shadow).
//   xcrun swiftc -O -parse-as-library tools/render_icon.swift Sources/OrbRenderer.swift -o build/render_icon
//   build/render_icon build/AppIcon-1024.png

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024
let body = CGRect(x: 100, y: 100, width: 824, height: 824)

/// Superellipse (n = 5) close to Apple's continuous-corner icon shape.
func squircle(_ rect: CGRect) -> CGPath {
    let path = CGMutablePath()
    let a = rect.width / 2, b = rect.height / 2
    let cx = rect.midX, cy = rect.midY
    let n = 5.0
    let steps = 720
    for index in 0...steps {
        let t = Double(index) / Double(steps) * 2 * Double.pi
        let c = cos(t), s = sin(t)
        let x = cx + a * CGFloat(copysign(pow(abs(c), 2 / n), c))
        let y = cy + b * CGFloat(copysign(pow(abs(s), 2 / n), s))
        if index == 0 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
    }
    path.closeSubpath()
    return path
}

func color(_ r: Double, _ g: Double, _ b: Double, _ a: Double = 1) -> CGColor {
    CGColor(srgbRed: r / 255, green: g / 255, blue: b / 255, alpha: a)
}

@main
enum RenderIcon {
    static func main() {
        guard CommandLine.arguments.count > 1 else {
            FileHandle.standardError.write("usage: render_icon <out.png>\n".data(using: .utf8)!)
            exit(64)
        }
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        guard let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { exit(1) }
        // CG's origin is bottom-left; flip so y grows downward like the design grid.
        ctx.translateBy(x: 0, y: CGFloat(size))
        ctx.scaleBy(x: 1, y: -1)
        let shape = squircle(body)

        // 1. Drop shadow under the body.
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: 14), blur: 28, color: color(0, 0, 0, 0.42))
        ctx.addPath(shape)
        ctx.setFillColor(color(10, 13, 20))
        ctx.fillPath()
        ctx.restoreGState()

        // 2. Body: deep night gradient, clipped to the squircle.
        ctx.saveGState()
        ctx.addPath(shape)
        ctx.clip()
        let bodyGradient = CGGradient(colorsSpace: space, colors: [color(24, 31, 48), color(12, 15, 24), color(6, 8, 13)] as CFArray,
                                      locations: [0, 0.55, 1])!
        ctx.drawLinearGradient(bodyGradient, start: CGPoint(x: 512, y: 100), end: CGPoint(x: 512, y: 924), options: [])
        // Blue bloom behind the orb.
        let bloom = CGGradient(colorsSpace: space, colors: [color(26, 115, 242, 0.55), color(26, 115, 242, 0.16), color(26, 115, 242, 0)] as CFArray,
                               locations: [0, 0.55, 1])!
        ctx.drawRadialGradient(bloom, startCenter: CGPoint(x: 512, y: 560), startRadius: 0, endCenter: CGPoint(x: 512, y: 560),
                               endRadius: 430, options: [])
        // Glass sheen across the top.
        let sheen = CGGradient(colorsSpace: space, colors: [color(255, 255, 255, 0.10), color(255, 255, 255, 0)] as CFArray,
                               locations: [0, 1])!
        ctx.drawLinearGradient(sheen, start: CGPoint(x: 512, y: 100), end: CGPoint(x: 512, y: 420), options: [])

        // 3. The orb (with its own halo), centred slightly above the middle.
        let phase = CommandLine.arguments.count > 2 ? (Double(CommandLine.arguments[2]) ?? 2.0) : 2.0
        if let orb = OrbRenderer.image(pixels: 760, radiusFraction: 0.32, centerYFraction: 0.47, time: phase, energy: 0.16, glow: 0.9) {
            let rect = CGRect(x: 512 - 380, y: 512 - 380 - 6, width: 760, height: 760)
            ctx.saveGState()
            // draw upright inside the flipped context
            ctx.translateBy(x: rect.minX, y: rect.maxY)
            ctx.scaleBy(x: 1, y: -1)
            ctx.draw(orb, in: CGRect(origin: .zero, size: rect.size))
            ctx.restoreGState()
        }
        ctx.restoreGState()

        // 4. Hairline rim: light at the top, fading toward the bottom.
        ctx.saveGState()
        ctx.addPath(shape)
        ctx.setLineWidth(3)
        ctx.replacePathWithStrokedPath()
        ctx.clip()
        let rim = CGGradient(colorsSpace: space, colors: [color(200, 220, 255, 0.34), color(200, 220, 255, 0.06)] as CFArray,
                             locations: [0, 1])!
        ctx.drawLinearGradient(rim, start: CGPoint(x: 512, y: 100), end: CGPoint(x: 512, y: 924), options: [])
        ctx.restoreGState()

        guard let image = ctx.makeImage(),
              let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: CommandLine.arguments[1]) as CFURL,
                                                                UTType.png.identifier as CFString, 1, nil) else { exit(1) }
        CGImageDestinationAddImage(destination, image, nil)
        exit(CGImageDestinationFinalize(destination) ? 0 : 1)
    }
}
