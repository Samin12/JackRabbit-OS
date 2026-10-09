// Renders the SamRabbit iPhone / Apple Watch app icon (1024 x 1024 PNG, opaque, full bleed - iOS
// applies its own mask): the fluid orb on the deep night backdrop of the desktop icon
// (companion/desktop/tools/render_icon.swift), with the same orb math (SamRabbitKit OrbRenderer).
//
//   xcrun swiftc -O -parse-as-library apple/tools/render_icon.swift \
//     apple/Shared/SamRabbitKit/Sources/SamRabbitKit/Orb/OrbRenderer.swift -o /tmp/render_icon
//   /tmp/render_icon apple/iOS/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png [phase]

import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

private let size = 1024

private func color(_ r: Double, _ g: Double, _ b: Double, _ a: Double = 1) -> CGColor {
    CGColor(srgbRed: r / 255, green: g / 255, blue: b / 255, alpha: a)
}

@main
enum RenderIcon {
    static func main() {
        guard CommandLine.arguments.count > 1 else {
            FileHandle.standardError.write(Data("usage: render_icon <out.png> [phase]\n".utf8))
            exit(64)
        }
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        guard let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { exit(1) }
        ctx.translateBy(x: 0, y: CGFloat(size))
        ctx.scaleBy(x: 1, y: -1) // y down like the design grid

        // 1. Deep night body.
        let body = CGGradient(colorsSpace: space, colors: [color(24, 31, 48), color(12, 15, 24), color(6, 8, 13)] as CFArray,
                              locations: [0, 0.55, 1])!
        ctx.drawLinearGradient(body, start: CGPoint(x: 512, y: 0), end: CGPoint(x: 512, y: 1024), options: [])
        // 2. Blue bloom behind the orb and a violet whisper bottom-right (the desktop backdrop).
        let bloom = CGGradient(colorsSpace: space, colors: [color(26, 115, 242, 0.55), color(26, 115, 242, 0.16),
                                                            color(26, 115, 242, 0)] as CFArray, locations: [0, 0.55, 1])!
        ctx.drawRadialGradient(bloom, startCenter: CGPoint(x: 512, y: 560), startRadius: 0,
                               endCenter: CGPoint(x: 512, y: 560), endRadius: 520, options: [])
        let violet = CGGradient(colorsSpace: space, colors: [color(124, 108, 255, 0.14), color(124, 108, 255, 0)] as CFArray,
                                locations: [0, 1])!
        ctx.drawRadialGradient(violet, startCenter: CGPoint(x: 1000, y: 1040), startRadius: 0,
                               endCenter: CGPoint(x: 1000, y: 1040), endRadius: 560, options: [])
        // 3. Glass sheen across the top.
        let sheen = CGGradient(colorsSpace: space, colors: [color(255, 255, 255, 0.10), color(255, 255, 255, 0)] as CFArray,
                               locations: [0, 1])!
        ctx.drawLinearGradient(sheen, start: CGPoint(x: 512, y: 0), end: CGPoint(x: 512, y: 380), options: [])

        // 4. The orb with its halo, a touch above centre.
        let phase = CommandLine.arguments.count > 2 ? (Double(CommandLine.arguments[2]) ?? 2.0) : 2.0
        if let orb = OrbRenderer.image(pixels: 900, radiusFraction: 0.33, centerYFraction: 0.47, time: phase,
                                       energy: 0.16, glow: 0.95) {
            let rect = CGRect(x: 512 - 450, y: 512 - 450 - 8, width: 900, height: 900)
            ctx.saveGState()
            ctx.translateBy(x: rect.minX, y: rect.maxY)
            ctx.scaleBy(x: 1, y: -1)
            ctx.draw(orb, in: CGRect(origin: .zero, size: rect.size))
            ctx.restoreGState()
        }

        guard let image = ctx.makeImage(),
              let destination = CGImageDestinationCreateWithURL(URL(fileURLWithPath: CommandLine.arguments[1]) as CFURL,
                                                                UTType.png.identifier as CFString, 1, nil) else { exit(1) }
        CGImageDestinationAddImage(destination, image, nil)
        exit(CGImageDestinationFinalize(destination) ? 0 : 1)
    }
}
