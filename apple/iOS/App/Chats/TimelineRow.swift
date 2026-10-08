import SamRabbitKit
import SwiftUI

/// One timeline item, drawn like the desktop app: bubbles, cards, images and generated UIs.
struct TimelineRow: View {
    let item: TimelineItem
    var openImage: (ViewerImage) -> Void
    var openUI: (GeneratedUIItem) -> Void

    var body: some View {
        switch item.content {
        case .user(let text, let origin):
            UserBubble(text: text, origin: origin, at: item.at)
        case .assistant(let text, let streaming, let interrupted):
            AssistantBubble(text: text, streaming: streaming, interrupted: interrupted)
        case .card(let card, let dismissed, let updates):
            GenCardView(card: card, dismissed: dismissed, updates: updates)
        case .generatedUI(let ui):
            GeneratedUIRow(item: ui, open: { openUI(ui) })
        case .image(let image):
            ImageRow(image: image, open: openImage)
        case .tool(let tool):
            ToolRow(tool: tool)
        case .t3(let update):
            T3UpdateRow(update: update)
        case .system(let kind, let title, let text):
            SystemRow(kind: kind, title: title, text: text)
        case .divider(let label, let variant):
            DividerRow(label: label, variant: variant, at: item.at)
        case .saved(let summary, let memoryCount):
            SavedRow(summary: summary, memoryCount: memoryCount)
        }
    }
}

struct UserBubble: View {
    let text: String
    let origin: String?
    let at: Date

    var body: some View {
        HStack {
            Spacer(minLength: 48)
            VStack(alignment: .trailing, spacing: 3) {
                Text(text)
                    .font(.system(size: 15.5))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(
                        UnevenRoundedRectangle(topLeadingRadius: 20, bottomLeadingRadius: 20, bottomTrailingRadius: 6,
                                               topTrailingRadius: 20, style: .continuous)
                            .fill(LinearGradient(colors: [SamTheme.orb2, SamTheme.orb], startPoint: .top, endPoint: .bottom))
                    )
                    .shadow(color: SamTheme.orb.opacity(0.3), radius: 10, y: 4)
                    .textSelection(.enabled)
                HStack(spacing: 4) {
                    if origin == "phone" { Image(systemName: "iphone") }
                    Text(at.formatted(.dateTime.hour().minute()))
                }
                .font(.system(size: 10.5))
                .foregroundStyle(SamTheme.faint)
                .padding(.trailing, 4)
            }
        }
    }
}

struct AssistantBubble: View {
    let text: String
    let streaming: Bool
    let interrupted: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            OrbView(mood: streaming ? .live : .idle, animated: streaming, halo: false)
                .frame(width: 24, height: 24)
                .padding(.top, 4)
            VStack(alignment: .leading, spacing: 4) {
                Group {
                    if text.isEmpty, streaming {
                        TypingDots()
                    } else {
                        Text(text + (streaming ? " ▍" : ""))
                            .font(.system(size: 15.5))
                            .foregroundStyle(SamTheme.ink)
                            .textSelection(.enabled)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(
                    UnevenRoundedRectangle(topLeadingRadius: 6, bottomLeadingRadius: 20, bottomTrailingRadius: 20,
                                           topTrailingRadius: 20, style: .continuous)
                        .fill(SamTheme.glass2)
                )
                .overlay(
                    UnevenRoundedRectangle(topLeadingRadius: 6, bottomLeadingRadius: 20, bottomTrailingRadius: 20,
                                           topTrailingRadius: 20, style: .continuous)
                        .strokeBorder(SamTheme.line2, lineWidth: 0.6)
                )
                if interrupted {
                    Label("Interrupted", systemImage: "pause.circle")
                        .font(.system(size: 11)).foregroundStyle(SamTheme.faint).padding(.leading, 4)
                }
            }
            Spacer(minLength: 32)
        }
    }
}

struct TypingDots: View {
    @State private var phase = 0.0

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 20)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(spacing: 5) {
                ForEach(0..<3) { index in
                    Circle()
                        .fill(SamTheme.orbPale)
                        .frame(width: 6, height: 6)
                        .opacity(0.35 + 0.65 * max(0, sin(t * 5 - Double(index) * 0.8)))
                }
            }
            .padding(.vertical, 5)
        }
    }
}

/// A generated card (GenCardCodec), styled like the desktop `.gcard`.
struct GenCardView: View {
    let card: GenCard
    var dismissed = false
    var updates = 0

    var body: some View {
        let accent = SamTheme.accent(named: card.accent)
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 10) {
                if let symbol = CardSymbols.symbol(card.icon) {
                    Image(systemName: symbol)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(accent)
                        .frame(width: 32, height: 32)
                        .background(RoundedRectangle(cornerRadius: 10).fill(accent.opacity(0.16)))
                }
                VStack(alignment: .leading, spacing: 2) {
                    if let eyebrow = card.eyebrow {
                        Text(eyebrow.uppercased()).font(SamTheme.eyebrow).tracking(0.5).foregroundStyle(accent)
                    }
                    Text(card.title).font(.system(size: 17, weight: .semibold)).foregroundStyle(SamTheme.ink)
                    if let subtitle = card.subtitle {
                        Text(subtitle).font(.system(size: 13.5)).foregroundStyle(SamTheme.muted)
                    }
                }
                Spacer(minLength: 0)
                if dismissed {
                    CardBadge(text: "Dismissed", color: SamTheme.faint)
                } else if card.terminal || card.state == "done" {
                    CardBadge(text: "Done", color: SamTheme.green)
                } else if card.live {
                    LiveBadge()
                } else if updates > 0 {
                    CardBadge(text: updates == 1 ? "Updated" : "Updated ×\(updates)", color: SamTheme.orbPale)
                }
            }
            if !card.liveLines.isEmpty {
                Text(card.liveLines.joined(separator: " · "))
                    .font(.system(size: 13)).foregroundStyle(SamTheme.ink2)
            }
            ForEach(Array(card.body.enumerated()), id: \.offset) { _, block in
                CardBlockView(block: block, accent: accent)
            }
            if !card.actions.isEmpty {
                FlowLayout(spacing: 6) {
                    ForEach(card.actions, id: \.self) { label in
                        Text(label)
                            .font(.system(size: 12, weight: .medium))
                            .foregroundStyle(SamTheme.ink2)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Capsule().fill(SamTheme.line2))
                    }
                }
            }
        }
        .glassCard(radius: 20, tint: accent, padding: 14)
        .opacity(dismissed ? 0.55 : 1)
        .padding(.leading, 34)
    }
}

struct CardBadge: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text)
            .font(.system(size: 10.5, weight: .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(Capsule().fill(color.opacity(0.15)))
    }
}

struct CardBlockView: View {
    let block: GenCard.Block
    let accent: Color

    var body: some View {
        switch block {
        case .text(let text, let style):
            Text(text)
                .font(.system(size: style == "lead" ? 16.5 : 14.5, weight: style == "lead" ? .medium : .regular))
                .foregroundStyle(style == "muted" ? SamTheme.muted : SamTheme.ink2)
        case .stat(let value, let label, let delta, let trend):
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(value).font(.system(size: 30, weight: .bold, design: .rounded)).foregroundStyle(SamTheme.ink)
                VStack(alignment: .leading, spacing: 1) {
                    if let label { Text(label).font(.system(size: 13)).foregroundStyle(SamTheme.muted) }
                    if let delta {
                        Label(delta, systemImage: trend == "down" ? "arrow.down.right" : trend == "up" ? "arrow.up.right" : "minus")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(trend == "down" ? SamTheme.red : SamTheme.green)
                    }
                }
            }
        case .keyValues(let pairs, let columns):
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), alignment: .leading), count: columns), spacing: 8) {
                ForEach(pairs) { pair in
                    VStack(alignment: .leading, spacing: 1) {
                        Text(pair.key).font(.system(size: 11.5)).foregroundStyle(SamTheme.muted)
                        Text(pair.value).font(.system(size: 14.5, weight: .medium)).foregroundStyle(SamTheme.ink)
                    }
                }
            }
        case .list(let rows), .checklist(let rows):
            VStack(spacing: 8) {
                ForEach(rows) { row in
                    HStack(spacing: 10) {
                        Image(systemName: row.checked ? "checkmark.circle.fill" : "circle.fill")
                            .font(.system(size: row.checked ? 14 : 6))
                            .foregroundStyle(row.checked ? SamTheme.green : accent)
                            .frame(width: 16)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(row.title).font(.system(size: 14.5, weight: .medium)).foregroundStyle(SamTheme.ink)
                                .strikethrough(row.checked, color: SamTheme.faint)
                            if let detail = row.detail {
                                Text(detail).font(.system(size: 12.5)).foregroundStyle(SamTheme.muted)
                            }
                        }
                        Spacer(minLength: 0)
                        if let trailing = row.trailing {
                            Text(trailing).font(.system(size: 13, weight: .medium)).foregroundStyle(SamTheme.ink2)
                        }
                    }
                }
            }
        case .progress(let label, let value, let steps, let step):
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    if let label { Text(label).font(.system(size: 13)).foregroundStyle(SamTheme.ink2) }
                    Spacer()
                    if let value { Text("\(Int(value * 100))%").font(.system(size: 12, weight: .semibold)).foregroundStyle(accent) }
                }
                if let value {
                    ProgressView(value: value).tint(accent)
                } else {
                    ProgressView().progressViewStyle(.linear).tint(accent)
                }
                if !steps.isEmpty {
                    Text(steps.enumerated().map { ($0.offset < (step ?? -1) ? "✓ " : "") + $0.element }.joined(separator: "  ›  "))
                        .font(.system(size: 11.5)).foregroundStyle(SamTheme.muted)
                }
            }
        case .bars(let values, let labels, let highlight, let unit):
            let top = max(values.max() ?? 1, 0.0001)
            HStack(alignment: .bottom, spacing: 6) {
                ForEach(Array(values.enumerated()), id: \.offset) { index, value in
                    VStack(spacing: 4) {
                        Text(String(format: "%g", (value * 10).rounded() / 10) + (index == highlight ? (unit ?? "") : ""))
                            .font(.system(size: 10, weight: .semibold)).foregroundStyle(SamTheme.orbPale)
                        RoundedRectangle(cornerRadius: 5)
                            .fill(index == highlight ? SamTheme.cyan : accent.opacity(0.75))
                            .frame(height: max(4, 70 * value / top))
                        Text(index < labels.count ? labels[index] : "").font(.system(size: 10)).foregroundStyle(SamTheme.muted)
                    }
                    .frame(maxWidth: .infinity)
                }
            }
        case .weather(let condition, let temp, let high, let low, let place):
            HStack(spacing: 14) {
                Image(systemName: CardSymbols.weather(condition))
                    .symbolRenderingMode(.multicolor)
                    .font(.system(size: 34))
                VStack(alignment: .leading, spacing: 2) {
                    Text(temp).font(.system(size: 30, weight: .semibold, design: .rounded))
                    Text([condition.replacingOccurrences(of: "-", with: " ").capitalized,
                          high.map { "H \($0)" }, low.map { "L \($0)" }].compactMap { $0 }.joined(separator: "  "))
                        .font(.system(size: 12.5)).foregroundStyle(SamTheme.muted)
                    if let place { Label(place, systemImage: "mappin").font(.system(size: 12)).foregroundStyle(SamTheme.faint) }
                }
            }
        case .timer(let label, let endsAt, _, let done):
            HStack {
                Image(systemName: "timer").foregroundStyle(accent)
                if done || (endsAt ?? .distantPast) < .now {
                    Text("Done").font(.system(size: 22, weight: .semibold, design: .rounded))
                } else if let endsAt {
                    Text(timerInterval: Date.now...endsAt, countsDown: true)
                        .font(.system(size: 22, weight: .semibold, design: .rounded).monospacedDigit())
                }
                if let label { Text(label).font(.system(size: 13)).foregroundStyle(SamTheme.muted) }
            }
        case .divider:
            Divider().overlay(SamTheme.line2)
        }
    }
}

enum CardSymbols {
    static func symbol(_ icon: String?) -> String? {
        switch icon {
        case "calendar": "calendar"
        case "timer": "timer"
        case "weather", "sun": "sun.max.fill"
        case "music": "music.note"
        case "mail": "envelope.fill"
        case "check", "task", "tasks": "checklist"
        case "chart": "chart.bar.fill"
        case "mac", "computer": "macbook"
        case "code", "t3": "chevron.left.forwardslash.chevron.right"
        case "note", "journal": "book.closed.fill"
        case "location", "pin": "mappin.circle.fill"
        case .none: nil
        default: "sparkles"
        }
    }

    static func weather(_ condition: String) -> String {
        switch condition {
        case "sunny": "sun.max.fill"
        case "clear-night": "moon.stars.fill"
        case "partly-cloudy": "cloud.sun.fill"
        case "cloudy": "cloud.fill"
        case "rain": "cloud.rain.fill"
        case "storm": "cloud.bolt.rain.fill"
        case "snow": "cloud.snow.fill"
        case "fog": "cloud.fog.fill"
        case "wind": "wind"
        default: "cloud.fill"
        }
    }
}

/// A generated UI in the timeline: its picture; tap to open it interactive.
struct GeneratedUIRow: View {
    let item: GeneratedUIItem
    let open: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "wand.and.sparkles").foregroundStyle(SamTheme.pink)
                Text("GENERATED UI").font(SamTheme.eyebrow).tracking(0.5).foregroundStyle(SamTheme.pink)
                Spacer()
                if item.status == .generating { ProgressView().controlSize(.mini) }
            }
            switch item.status {
            case .ready:
                Button(action: open) {
                    ArtifactImage(artifactId: item.artifactId, blobId: item.imageBlobId,
                                  aspect: item.width > 0 && item.height > 0 ? CGFloat(item.width) / CGFloat(item.height) : nil)
                        .overlay(alignment: .bottomTrailing) {
                            Label("Open", systemImage: "hand.tap.fill")
                                .font(.system(size: 12, weight: .semibold))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .glassEffect(.regular, in: .capsule)
                                .padding(10)
                        }
                }
                .buttonStyle(.plain)
            case .generating:
                RoundedRectangle(cornerRadius: 16)
                    .fill(SamTheme.glass)
                    .frame(height: 140)
                    .overlay {
                        VStack(spacing: 10) {
                            OrbView(mood: .working, halo: false).frame(width: 40, height: 40)
                            Text("Designing on your Mac…").font(.system(size: 13)).foregroundStyle(SamTheme.muted)
                        }
                    }
            case .failed:
                Label(item.error ?? "That visual couldn't be made.", systemImage: "exclamationmark.triangle")
                    .font(.system(size: 13.5)).foregroundStyle(SamTheme.amber)
            }
            if !item.title.isEmpty {
                Text(item.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(SamTheme.ink)
            }
            if !item.summary.isEmpty {
                Text(item.summary).font(.system(size: 13.5)).foregroundStyle(SamTheme.muted)
            }
        }
        .glassCard(radius: 20, tint: SamTheme.pink, padding: 12)
        .padding(.leading, 34)
    }
}

struct ImageRow: View {
    @Environment(AppModel.self) private var model
    let image: ImageItem
    let open: (ViewerImage) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                if let loaded = ImageStore.shared.cached("blob:\(image.blobId)") {
                    open(ViewerImage(image: loaded, title: image.sourceLabel))
                }
            } label: {
                BlobImage(blobId: image.blobId,
                          aspect: image.width > 0 && image.height > 0 ? CGFloat(image.width) / CGFloat(image.height) : nil)
            }
            .buttonStyle(.plain)
            Label(image.caption ?? image.sourceLabel, systemImage: image.source == "mac_screenshot" ? "macbook" : "photo")
                .font(.system(size: 11.5))
                .foregroundStyle(SamTheme.faint)
        }
        .padding(.leading, 34)
        .padding(.trailing, 20)
    }
}

struct ToolRow: View {
    let tool: ToolItem

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: tool.isError ? "exclamationmark.triangle.fill" : tool.pending ? "gearshape.2" : "checkmark.circle")
                .foregroundStyle(tool.isError ? SamTheme.amber : SamTheme.faint)
                .symbolEffect(.rotate, isActive: tool.pending)
            Text(ToolRow.label(tool.tool))
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(SamTheme.muted)
            if let summary = tool.summary {
                Text(summary).font(.system(size: 12.5)).foregroundStyle(SamTheme.faint).lineLimit(1)
            }
        }
        .padding(.leading, 40)
    }

    static func label(_ tool: String) -> String {
        switch tool {
        case "mac_look": "Looked at the Mac"
        case "mac_open": "Opened on the Mac"
        case "calendar_create", "calendar_block": "Updated the calendar"
        case "journal_add": "Wrote to the journal"
        case "t3_new_thread": "Started a T3 task"
        default: tool.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }
}

struct T3UpdateRow: View {
    @Environment(AppModel.self) private var model
    let update: T3Update

    var body: some View {
        let style = StatusStyle(update.status)
        Button {
            if let id = update.threadId {
                model.tab = .tasks
                model.tasksPath.append(ThreadRoute(threadId: id))
            }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: style.symbol).foregroundStyle(style.color).padding(.top, 2)
                VStack(alignment: .leading, spacing: 3) {
                    Text(update.title ?? "T3 update")
                        .font(.system(size: 14.5, weight: .semibold)).foregroundStyle(SamTheme.ink)
                    Text([update.projectTitle, style.label].compactMap { $0 }.joined(separator: " · "))
                        .font(.system(size: 12, weight: .medium)).foregroundStyle(style.color)
                    if let last = update.lastMessage {
                        Text(last).font(.system(size: 13)).foregroundStyle(SamTheme.ink2).lineLimit(3)
                    } else if update.title == nil || !update.line.contains(update.title ?? "") {
                        Text(update.line).font(.system(size: 13)).foregroundStyle(SamTheme.ink2).lineLimit(2)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(SamTheme.faint)
            }
            .glassCard(radius: 16, tint: style.color, padding: 12)
        }
        .buttonStyle(.plain)
        .padding(.leading, 34)
    }
}

struct SystemRow: View {
    let kind: String
    let title: String?
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: kind == "note" ? "book.closed" : kind == "uievent" ? "hand.tap" : "checkmark.seal")
            Text(text).lineLimit(3)
        }
        .font(.system(size: 12.5))
        .foregroundStyle(SamTheme.muted)
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.vertical, 2)
    }
}

struct DividerRow: View {
    let label: String
    let variant: String
    let at: Date

    var body: some View {
        HStack(spacing: 10) {
            Rectangle().fill(SamTheme.line2).frame(height: 0.6)
            Text("\(label) · \(at.formatted(.dateTime.hour().minute()))")
                .font(.system(size: 11, weight: .medium))
                .foregroundStyle(variant == "warn" ? SamTheme.amber : SamTheme.faint)
                .fixedSize()
            Rectangle().fill(SamTheme.line2).frame(height: 0.6)
        }
        .padding(.vertical, 6)
    }
}

struct SavedRow: View {
    let summary: String
    let memoryCount: Int

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "tray.and.arrow.down.fill").foregroundStyle(SamTheme.mint)
            VStack(alignment: .leading, spacing: 2) {
                Text("Saved").font(.system(size: 12.5, weight: .semibold)).foregroundStyle(SamTheme.mint)
                if !summary.isEmpty { Text(summary).font(.system(size: 13)).foregroundStyle(SamTheme.ink2) }
                if memoryCount > 0 {
                    Text(Formatting.count(memoryCount, "memory", "memories")).font(.system(size: 11.5)).foregroundStyle(SamTheme.faint)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassCard(radius: 16, tint: SamTheme.mint, padding: 12)
    }
}
