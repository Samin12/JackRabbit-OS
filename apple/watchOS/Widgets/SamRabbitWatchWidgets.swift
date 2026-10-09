import SamRabbitKit
import SwiftUI
import WidgetKit

@main
struct SamRabbitWatchWidgetsBundle: WidgetBundle {
    var body: some Widget {
        NeedsYouComplication()
        NextUpComplication()
        WatchAskControl()
    }
}

// MARK: - Control (Action Button, Control Center, Smart Stack)

/// "Ask SamRabbit" on the watch: opens the watch app with dictation on for a new task. On Apple
/// Watch Ultra: Settings > Action Button > Action > Control, then Control (says "Configure" until
/// set) > SamRabbit > Ask SamRabbit. A single press then runs it. It runs on the watch (the iPhone's control opens the iPhone app, so watchOS
/// doesn't offer that one here).
struct WatchAskControl: ControlWidget {
    static let kind = "com.samrabbit.watch.control.ask"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind) {
            ControlWidgetButton(action: OpenSamRabbitWatchIntent(.ask)) {
                Label("Ask SamRabbit", systemImage: "sparkles")
                    .controlWidgetActionHint("Ask SamRabbit")
            }
        }
        .displayName("Ask SamRabbit")
        .description("Opens SamRabbit on your watch with dictation on, for a new task.")
    }
}

// MARK: - Data

struct ComplicationEntry: TimelineEntry {
    let snapshot: ComplicationSnapshot
    var date: Date { snapshot.date }
}

/// Reads the summary the watch app last saved in the App Group and fetches a fresh one itself
/// (directly: WatchConnectivity isn't available to complications). One timeline holds an entry
/// every 5 minutes for the next hour, so "in 25m" stays right; it is refreshed every 15 minutes
/// and whenever the watch app changes something.
struct ComplicationProvider: TimelineProvider {
    func placeholder(in context: Context) -> ComplicationEntry { ComplicationEntry(snapshot: .sample()) }

    func getSnapshot(in context: Context, completion: @escaping @Sendable (ComplicationEntry) -> Void) {
        if context.isPreview {
            completion(ComplicationEntry(snapshot: .sample()))
            return
        }
        completion(ComplicationEntry(snapshot: Self.cached()))
    }

    func getTimeline(in context: Context, completion: @escaping @Sendable (Timeline<ComplicationEntry>) -> Void) {
        Task {
            let base = await Self.fresh()
            let entries = (0..<12).map { step -> ComplicationEntry in
                var snapshot = base
                snapshot.date = base.date.addingTimeInterval(TimeInterval(step) * 300)
                return ComplicationEntry(snapshot: snapshot)
            }
            completion(Timeline(entries: entries, policy: .after(.now.addingTimeInterval(15 * 60))))
        }
    }

    static func cached(now: Date = .now) -> ComplicationSnapshot {
        let entry = SummaryCache.shared.load()
        return ComplicationSnapshot(date: now, summary: entry?.summary, fetchedAt: entry?.savedAt,
                                    paired: BridgeAccount.shared.pairing != nil)
    }

    static func fresh() async -> ComplicationSnapshot {
        let account = BridgeAccount.shared
        guard account.pairing != nil, let client = account.client() else { return cached() }
        do {
            let summary = try await client.summary(timeout: 8)
            SummaryCache.shared.save(summary)
            return ComplicationSnapshot(date: .now, summary: summary, fetchedAt: .now, paired: true)
        } catch {
            return cached()
        }
    }
}

// MARK: - Widgets

/// The needs-you count: a ring (circular), the count with the next event along the bezel
/// (corner), or one line (inline).
struct NeedsYouComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "com.samrabbit.watch.needs", provider: ComplicationProvider()) { entry in
            NeedsYouFace(snapshot: entry.snapshot)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(URL(string: "samrabbit://tab/needs"))
        }
        .configurationDisplayName("Needs You")
        .description("How many tasks wait for you.")
        .supportedFamilies([.accessoryCircular, .accessoryCorner, .accessoryInline])
    }
}

/// The next event and what is working.
struct NextUpComplication: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "com.samrabbit.watch.next", provider: ComplicationProvider()) { entry in
            NextUpRectangularComplication(snapshot: entry.snapshot)
                .containerBackground(for: .widget) { Color.clear }
                .widgetURL(URL(string: "samrabbit://tab/upnext"))
        }
        .configurationDisplayName("Next Up")
        .description("Your next event and how many tasks are working.")
        .supportedFamilies([.accessoryRectangular])
    }
}

struct NeedsYouFace: View {
    @Environment(\.widgetFamily) private var family
    let snapshot: ComplicationSnapshot

    var body: some View {
        switch family {
        case .accessoryCorner: NeedsYouCornerComplication(snapshot: snapshot)
        case .accessoryInline: StatusInlineComplication(snapshot: snapshot)
        default: NeedsYouCircularComplication(snapshot: snapshot)
        }
    }
}

#Preview("Circular", as: .accessoryCircular) {
    NeedsYouComplication()
} timeline: {
    ComplicationEntry(snapshot: .sample())
}

#Preview("Rectangular", as: .accessoryRectangular) {
    NextUpComplication()
} timeline: {
    ComplicationEntry(snapshot: .sample())
}
