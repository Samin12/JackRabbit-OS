import AppIntents
import Foundation
import SamRabbitKit

// The App Intents shared by the iPhone app and its widget extension (interactive widget buttons
// and the Control Center control run them in the extension). Their work is SamRabbitKit's
// `SamRabbitActions`, so Siri, Shortcuts, widgets and the app behave the same.

/// A route for the app to open next (`samrabbit://…`), left in the App Group by intents that run
/// outside the app.
enum PendingRoute {
    static let key = "pendingRoute"

    static func set(_ url: String) {
        BridgeAccount.shared.container.defaults.set(url, forKey: key)
    }
}

/// An error Siri and Shortcuts read out as is.
struct SamRabbitIntentError: Error, CustomLocalizedStringResourceConvertible {
    let message: String
    var localizedStringResource: LocalizedStringResource { "\(message)" }

    init(_ error: Error) {
        message = (error as? BridgeError)?.errorDescription ?? error.localizedDescription
    }
}

/// How long to block the calendar.
enum BlockLength: Int, AppEnum {
    case fifteen = 15
    case thirty = 30
    case fortyFive = 45
    case sixty = 60
    case ninety = 90
    case twoHours = 120

    static let typeDisplayRepresentation: TypeDisplayRepresentation = "Duration"
    static let caseDisplayRepresentations: [BlockLength: DisplayRepresentation] = [
        .fifteen: DisplayRepresentation(title: "15 minutes", synonyms: ["fifteen minutes", "a quarter hour"]),
        .thirty: DisplayRepresentation(title: "30 minutes", synonyms: ["thirty minutes", "half an hour"]),
        .fortyFive: DisplayRepresentation(title: "45 minutes", synonyms: ["forty five minutes"]),
        .sixty: DisplayRepresentation(title: "1 hour", synonyms: ["an hour", "60 minutes", "one hour"]),
        .ninety: DisplayRepresentation(title: "90 minutes", synonyms: ["an hour and a half"]),
        .twoHours: DisplayRepresentation(title: "2 hours", synonyms: ["two hours"]),
    ]
}

/// "Ask SamRabbit …": a new T3 Code task with automatic placement.
struct AskSamRabbitIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask SamRabbit"
    static let description = IntentDescription("Starts a T3 Code task on your Mac. SamRabbit picks the project.",
                                               categoryName: "Tasks")

    @Parameter(title: "Request", requestValueDialog: "What should SamRabbit do?")
    var text: String

    static var parameterSummary: some ParameterSummary { Summary("Ask SamRabbit to \(\.$text)") }

    init() {}
    init(text: String) { self.text = text }

    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        do {
            let created = try await SamRabbitActions.shared.ask(text)
            let title = created.title ?? Formatting.clip(text, 60)
            let place = created.projectName.map { " in \($0)" } ?? ""
            return .result(value: created.threadId, dialog: "Started “\(title)”\(place).")
        } catch {
            throw SamRabbitIntentError(error)
        }
    }
}

/// "Block 30 minutes with SamRabbit": puts a Focus block on the calendar from now.
struct BlockTimeIntent: AppIntent {
    static let title: LocalizedStringResource = "Block Time"
    static let description = IntentDescription("Blocks your calendar from now (a Focus event).", categoryName: "Calendar")

    @Parameter(title: "Duration", default: .thirty)
    var duration: BlockLength

    @Parameter(title: "Title", default: "Focus")
    var eventTitle: String

    static var parameterSummary: some ParameterSummary { Summary("Block \(\.$duration) as \(\.$eventTitle)") }

    init() {}
    init(minutes: Int) {
        duration = BlockLength(rawValue: minutes) ?? .thirty
        eventTitle = "Focus"
    }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        do {
            let result = try await SamRabbitActions.shared.block(minutes: duration.rawValue,
                                                                  title: eventTitle == "Focus" ? nil : eventTitle)
            let range = result.event.map { Formatting.range($0.startsAt, $0.endsAt) } ?? "from now"
            return .result(dialog: "Blocked \(range).")
        } catch {
            throw SamRabbitIntentError(error)
        }
    }
}

/// Adds the user's own words to today's Heptabase journal.
struct AddJournalNoteIntent: AppIntent {
    static let title: LocalizedStringResource = "Add Journal Note"
    static let description = IntentDescription("Adds your words, as you said them, to today's journal.",
                                               categoryName: "Journal")

    @Parameter(title: "Note", requestValueDialog: "What should I add?")
    var text: String

    static var parameterSummary: some ParameterSummary { Summary("Add \(\.$text) to my journal") }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        do {
            let result = try await SamRabbitActions.shared.note(text)
            return .result(dialog: result.state == "queued" ? "Queued for your journal." : "Added to your journal.")
        } catch {
            throw SamRabbitIntentError(error)
        }
    }
}

/// "What needs me?": a spoken summary of waiting tasks and the next event.
struct WhatNeedsMeIntent: AppIntent {
    static let title: LocalizedStringResource = "What Needs Me"
    static let description = IntentDescription("Tells you which tasks wait for you, what is running and what is next.",
                                               categoryName: "Tasks")

    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        let text = await SamRabbitActions.shared.whatNeedsMe()
        return .result(value: text, dialog: "\(text)")
    }
}

/// Opens an app (or a link) on the Mac.
struct OpenOnMacIntent: AppIntent {
    static let title: LocalizedStringResource = "Open on Mac"
    static let description = IntentDescription("Opens an app or a link on your Mac.", categoryName: "Mac")

    @Parameter(title: "App or link", requestValueDialog: "What should I open on your Mac?")
    var target: String

    static var parameterSummary: some ParameterSummary { Summary("Open \(\.$target) on my Mac") }

    init() {}
    init(target: String) { self.target = target }

    func perform() async throws -> some IntentResult & ProvidesDialog {
        let value = target.trimmingCharacters(in: .whitespacesAndNewlines)
        let isLink = value.lowercased().hasPrefix("http://") || value.lowercased().hasPrefix("https://")
        do {
            let result = try await SamRabbitActions.shared.openOnMac(app: isLink ? nil : value, url: isLink ? value : nil)
            return .result(dialog: "Opened \(result.app ?? value) on your Mac.")
        } catch {
            throw SamRabbitIntentError(error)
        }
    }
}

/// Opens SamRabbit on the Mac tab and takes a screenshot.
struct ScreenshotMacIntent: AppIntent {
    static let title: LocalizedStringResource = "Screenshot My Mac"
    static let description = IntentDescription("Shows what is on your Mac's screen right now.", categoryName: "Mac")
    static let openAppWhenRun = true

    @MainActor
    func perform() async throws -> some IntentResult {
        PendingRoute.set("samrabbit://mac/screenshot")
        return .result()
    }
}

/// Opens SamRabbit at the Ask sheet (the Control Center control, the widgets' Ask buttons).
struct OpenAskIntent: AppIntent {
    static let title: LocalizedStringResource = "Ask SamRabbit (open)"
    static let description = IntentDescription("Opens SamRabbit ready to take a request.", categoryName: "Tasks")
    static let openAppWhenRun = true
    static let isDiscoverable = false

    @MainActor
    func perform() async throws -> some IntentResult {
        PendingRoute.set("samrabbit://ask")
        return .result()
    }
}

/// Refreshes the widgets (the widgets' refresh button).
struct RefreshSamRabbitIntent: AppIntent {
    static let title: LocalizedStringResource = "Refresh SamRabbit"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await SamRabbitActions.shared.refreshQuietly()
        return .result()
    }
}
