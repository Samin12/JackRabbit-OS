// Renders the fake bridge's fixture images (synthetic, no real screen content):
//   xcrun swift apple/dev/make_fixtures.swift apple/dev/fixtures
// genui-focus.jpg   a generated-UI preview ("Focus this week" bar chart), 960 px wide like the bridge's
// genui-release.jpg a second generated UI (release checklist)
// mac-screenshot.jpg a made-up Mac desktop (menu bar, an editor window and a browser window)

import AppKit
import CoreGraphics
import Foundation

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "apple/dev/fixtures"
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)

func rgb(_ hex: UInt32, _ a: CGFloat = 1) -> NSColor {
    NSColor(srgbRed: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255, alpha: a)
}

func render(_ name: String, width: Int, height: Int, quality: Double = 0.82, draw: (CGContext) -> Void) {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0, space: space,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    // y down like a page
    ctx.translateBy(x: 0, y: CGFloat(height))
    ctx.scaleBy(x: 1, y: -1)
    let graphics = NSGraphicsContext(cgContext: ctx, flipped: true)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = graphics
    draw(ctx)
    NSGraphicsContext.restoreGraphicsState()
    let image = ctx.makeImage()!
    let rep = NSBitmapImageRep(cgImage: image)
    let data = rep.representation(using: .jpeg, properties: [.compressionFactor: quality])!
    try! data.write(to: URL(fileURLWithPath: "\(out)/\(name)"))
    print("\(name): \(width)x\(height), \(data.count / 1024) KB")
}

func text(_ string: String, _ x: CGFloat, _ y: CGFloat, size: CGFloat, weight: NSFont.Weight = .regular,
          color: NSColor = rgb(0xF5F8FF), mono: Bool = false) {
    let font = mono ? NSFont.monospacedSystemFont(ofSize: size, weight: weight) : NSFont.systemFont(ofSize: size, weight: weight)
    (string as NSString).draw(at: CGPoint(x: x, y: y), withAttributes: [.font: font, .foregroundColor: color])
}

func round(_ ctx: CGContext, _ rect: CGRect, _ radius: CGFloat, fill: NSColor, stroke: NSColor? = nil) {
    let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(path)
    ctx.setFillColor(fill.cgColor)
    ctx.fillPath()
    if let stroke {
        ctx.addPath(path)
        ctx.setStrokeColor(stroke.cgColor)
        ctx.setLineWidth(1.5)
        ctx.strokePath()
    }
}

func backdrop(_ ctx: CGContext, _ w: CGFloat, _ h: CGFloat) {
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let g = CGGradient(colorsSpace: space, colors: [rgb(0x0D111A).cgColor, rgb(0x090B10).cgColor] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: h), options: [])
    let bloom = CGGradient(colorsSpace: space, colors: [rgb(0x1A73F2, 0.28).cgColor, rgb(0x1A73F2, 0).cgColor] as CFArray,
                           locations: [0, 1])!
    ctx.drawRadialGradient(bloom, startCenter: CGPoint(x: w * 0.85, y: -20), startRadius: 0,
                           endCenter: CGPoint(x: w * 0.85, y: -20), endRadius: w * 0.7, options: [])
}

// 1. Generated UI: "Focus this week"
render("genui-focus.jpg", width: 960, height: 620) { ctx in
    backdrop(ctx, 960, 620)
    text("FOCUS · THIS WEEK", 48, 44, size: 18, weight: .semibold, color: rgb(0x8C98AC))
    text("18.5 h of deep work", 48, 74, size: 44, weight: .bold)
    text("+3.2 h vs last week · best day Wednesday", 48, 134, size: 21, color: rgb(0x60D6A0))
    let values: [CGFloat] = [2.5, 3.0, 4.5, 3.5, 2.0, 1.5, 1.5]
    let labels = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    let base: CGFloat = 540
    let maxH: CGFloat = 300
    for (i, value) in values.enumerated() {
        let x = 70 + CGFloat(i) * 124
        let h = value / 4.5 * maxH
        let rect = CGRect(x: x, y: base - h, width: 74, height: h)
        let space = CGColorSpace(name: CGColorSpace.sRGB)!
        let color = i == 2 ? 0x5CA2FF : 0x1A73F2
        let g = CGGradient(colorsSpace: space, colors: [rgb(UInt32(color)).cgColor, rgb(UInt32(color), 0.35).cgColor] as CFArray,
                           locations: [0, 1])!
        ctx.saveGState()
        ctx.addPath(CGPath(roundedRect: rect, cornerWidth: 12, cornerHeight: 12, transform: nil))
        ctx.clip()
        ctx.drawLinearGradient(g, start: CGPoint(x: 0, y: rect.minY), end: CGPoint(x: 0, y: rect.maxY), options: [])
        ctx.restoreGState()
        text(String(format: "%.1f", value), x + 16, base - h - 34, size: 20, weight: .semibold,
             color: i == 2 ? rgb(0xF5F8FF) : rgb(0xA0C7FF))
        text(labels[i], x + 16, base + 18, size: 19, color: rgb(0x8C98AC))
    }
}

// 2. Generated UI: "Release checklist"
render("genui-release.jpg", width: 960, height: 560) { ctx in
    backdrop(ctx, 960, 560)
    text("RELEASE 2.4 · TODAY", 48, 44, size: 18, weight: .semibold, color: rgb(0x8C98AC))
    text("4 of 6 steps done", 48, 74, size: 44, weight: .bold)
    round(ctx, CGRect(x: 48, y: 146, width: 864, height: 14), 7, fill: rgb(0xFFFFFF, 0.08))
    round(ctx, CGRect(x: 48, y: 146, width: 576, height: 14), 7, fill: rgb(0x4A9EFF))
    let steps = [("Tests green on main", true), ("Changelog written", true), ("Staging deploy", true),
                 ("Smoke test on staging", true), ("Approve production deploy", false), ("Announce in #releases", false)]
    for (i, step) in steps.enumerated() {
        let y = 196 + CGFloat(i) * 56
        round(ctx, CGRect(x: 48, y: y, width: 864, height: 46), 14, fill: rgb(0x161B27, 0.9), stroke: rgb(0xBED2FF, 0.1))
        round(ctx, CGRect(x: 64, y: y + 11, width: 24, height: 24), 7, fill: step.1 ? rgb(0x60D6A0) : rgb(0xFFFFFF, 0.1))
        text(step.1 ? "✓" : "", 69, y + 9, size: 19, weight: .bold, color: rgb(0x090B10))
        text(step.0, 106, y + 10, size: 21, weight: .medium, color: step.1 ? rgb(0xD5DDEC) : rgb(0xF5F8FF))
    }
}

// 3. A made-up Mac desktop.
render("mac-screenshot.jpg", width: 1600, height: 1000, quality: 0.72) { ctx in
    let space = CGColorSpace(name: CGColorSpace.sRGB)!
    let wall = CGGradient(colorsSpace: space, colors: [rgb(0x1B2A4E).cgColor, rgb(0x3B2B6B).cgColor, rgb(0x0E1A33).cgColor] as CFArray,
                          locations: [0, 0.55, 1])!
    ctx.drawLinearGradient(wall, start: .zero, end: CGPoint(x: 1600, y: 1000), options: [])
    // menu bar
    ctx.setFillColor(rgb(0x0B0F18, 0.55).cgColor)
    ctx.fill(CGRect(x: 0, y: 0, width: 1600, height: 30))
    text("  T3 Code    File    Edit    View    Window    Help", 10, 5, size: 15, weight: .medium)
    text("Thu 10:42", 1490, 5, size: 15, weight: .medium)
    // editor window
    round(ctx, CGRect(x: 70, y: 70, width: 900, height: 620), 14, fill: rgb(0x10141E), stroke: rgb(0xFFFFFF, 0.12))
    ctx.setFillColor(rgb(0x171C28).cgColor)
    ctx.fill(CGRect(x: 71, y: 71, width: 898, height: 38))
    for (i, c) in [0xFF5F57, 0xFEBC2E, 0x28C840].enumerated() {
        ctx.setFillColor(rgb(UInt32(c)).cgColor)
        ctx.fillEllipse(in: CGRect(x: 88 + CGFloat(i) * 22, y: 83, width: 13, height: 13))
    }
    text("T3 Code — Fix login redirect", 400, 81, size: 14, weight: .medium, color: rgb(0x8C98AC))
    ctx.setFillColor(rgb(0x0C1018).cgColor)
    ctx.fill(CGRect(x: 71, y: 109, width: 230, height: 580))
    for (i, row) in ["Assistant", "  Fix login redirect", "  Weekly report", "Website", "  Pricing page copy"].enumerated() {
        text(row, 90, 128 + CGFloat(i) * 30, size: 14, weight: i == 1 ? .semibold : .regular,
             color: i == 1 ? rgb(0xF5F8FF) : rgb(0x8C98AC))
    }
    let code = ["export async function redirectAfterLogin(req) {", "  const next = safeNext(req.query.next)",
                "  if (!next) return '/home'", "  return next.startsWith('/') ? next : '/home'", "}", "",
                "// Assistant: the open redirect is fixed; tests pass.", "✓ 42 tests passed"]
    for (i, line) in code.enumerated() {
        text(line, 330, 132 + CGFloat(i) * 28, size: 15,
             color: line.hasPrefix("//") || line.hasPrefix("✓") ? rgb(0x60D6A0) : rgb(0xD5DDEC), mono: true)
    }
    // browser window
    round(ctx, CGRect(x: 760, y: 300, width: 780, height: 600), 14, fill: rgb(0xF4F6FB), stroke: rgb(0x000000, 0.2))
    ctx.setFillColor(rgb(0xE3E7F0).cgColor)
    ctx.fill(CGRect(x: 761, y: 301, width: 778, height: 44))
    round(ctx, CGRect(x: 900, y: 309, width: 520, height: 28), 14, fill: rgb(0xFFFFFF))
    text("calendar.google.com", 1100, 314, size: 14, color: rgb(0x5F6A80))
    text("Thursday, October 8", 790, 370, size: 26, weight: .semibold, color: rgb(0x1B2333))
    let events = [("10:30  Standup", 0x1A73F2), ("12:00  Lunch with Maya", 0x60D6A0), ("14:00  Focus", 0x7C6CFF),
                  ("16:30  Design review", 0xFFC45C)]
    for (i, e) in events.enumerated() {
        round(ctx, CGRect(x: 790, y: 420 + CGFloat(i) * 92, width: 720, height: 76), 10, fill: rgb(UInt32(e.1), 0.16))
        ctx.setFillColor(rgb(UInt32(e.1)).cgColor)
        ctx.fill(CGRect(x: 790, y: 420 + CGFloat(i) * 92, width: 6, height: 76))
        text(e.0, 812, 444 + CGFloat(i) * 92, size: 20, weight: .medium, color: rgb(0x1B2333))
    }
    // dock
    round(ctx, CGRect(x: 520, y: 930, width: 560, height: 62), 18, fill: rgb(0xFFFFFF, 0.18), stroke: rgb(0xFFFFFF, 0.25))
    for i in 0..<8 {
        round(ctx, CGRect(x: 538 + CGFloat(i) * 66, y: 938, width: 48, height: 48), 11,
              fill: rgb([0x1A73F2, 0x60D6A0, 0xFFC45C, 0xFF6363, 0x7C6CFF, 0x5CA2FF, 0xFF5CA8, 0x68DECC][i]))
    }
}
