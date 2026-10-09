import AppIntents

/// Siri on the watch ("Ask SamRabbit"): opens SamRabbit on the watch already listening (voice only), like the
/// Action Button's control.
struct SamRabbitWatchShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: OpenSamRabbitWatchIntent(), phrases: [
            "Ask \(.applicationName)",
            "Talk to \(.applicationName)",
        ], shortTitle: "Ask SamRabbit", systemImageName: "mic.fill")
    }

    static let shortcutTileColor: ShortcutTileColor = .navy
}
