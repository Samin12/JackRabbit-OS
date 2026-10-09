import AppIntents
import Foundation
import SamRabbitKit

// The watch's App Intents, compiled into the watch app and its widget extension (the system needs
// both before a control can open the app). They run on the watch: the iPhone's "Ask SamRabbit"
// control opens the iPhone app, so watchOS doesn't offer it on the watch.

/// Where `OpenSamRabbitWatchIntent` opens the watch app.
enum WatchDestination: String, AppEnum {
    case ask, needs, working, upnext, quick

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "SamRabbit page"
    static let caseDisplayRepresentations: [WatchDestination: DisplayRepresentation] = [
        .ask: DisplayRepresentation(title: "Ask", synonyms: ["dictation", "new task"]),
        .needs: DisplayRepresentation(title: "Needs You", synonyms: ["approvals"]),
        .working: "Working",
        .upnext: DisplayRepresentation(title: "Up Next", synonyms: ["calendar"]),
        .quick: DisplayRepresentation(title: "Quick Actions", synonyms: ["quick"]),
    ]

    /// The route the watch app follows: Ask opens the status page with dictation already on.
    var route: String {
        switch self {
        case .ask: PendingRoute.askListening
        case .needs, .working, .upnext, .quick: "samrabbit://tab/\(rawValue)"
        }
    }
}

/// Opens SamRabbit on the watch. With Ask (the default) it opens the dictation sheet for a new T3
/// task right away. This is what the Watch Ultra's Action Button runs (the "Ask SamRabbit" control)
/// and what Siri on the watch runs ("Ask SamRabbit").
struct OpenSamRabbitWatchIntent: OpenIntent {
    static let title: LocalizedStringResource = "Open SamRabbit"
    static let description = IntentDescription("Opens SamRabbit on your watch. Ask starts dictation for a new task.")

    @Parameter(title: "Page", default: .ask)
    var target: WatchDestination

    init() {}
    init(_ target: WatchDestination) { self.target = target }

    @MainActor
    func perform() async throws -> some IntentResult {
        PendingRoute.set(target.route, in: BridgeAccount.shared.container.defaults)
        return .result()
    }
}
