import Foundation

extension MobileSummary {
    /// Believable sample data for widget placeholders, gallery previews and SwiftUI previews
    /// (never shown as real data: widgets use it only for `placeholder` and the widget picker).
    public static func sample(now: Date = .now) -> MobileSummary {
        let minute: TimeInterval = 60
        let base = now.addingTimeInterval(-now.timeIntervalSince1970.truncatingRemainder(dividingBy: 300))
        return MobileSummary(
            generatedAt: now,
            mac: MacStatus(name: "Samin's MacBook Pro", online: true, screenLocked: false),
            r1: R1Status(lastSeenAt: now.addingTimeInterval(-3 * minute), live: false),
            t3: TaskOverview(available: true, needsYou: 2, working: 2, threads: [
                TaskThread(threadId: "s1", title: "Deploy release 2.4 to staging", projectName: "Assistant",
                           status: .needsApproval, updatedAt: now.addingTimeInterval(-2 * minute),
                           summary: "Build 2.4.0-rc1 is ready; waiting for approval."),
                TaskThread(threadId: "s2", title: "Fix login redirect", projectName: "Website", status: .needsInput,
                           updatedAt: now.addingTimeInterval(-5 * minute), summary: "One question about the default page."),
                TaskThread(threadId: "s3", title: "Weekly report from Linear", projectName: "Assistant", status: .working,
                           updatedAt: now.addingTimeInterval(-40), summary: "Collecting closed issues…"),
                TaskThread(threadId: "s4", title: "Refactor calendar sync", projectName: "SamRabbit", status: .working,
                           updatedAt: now.addingTimeInterval(-90), summary: "2 of 5 files done."),
                TaskThread(threadId: "s5", title: "Pricing page copy", projectName: "Website", status: .done,
                           updatedAt: now.addingTimeInterval(-30 * minute), summary: "PR #42 is ready."),
            ]),
            calendar: CalendarOverview(available: true, next: [
                CalendarEvent(title: "Standup", startsAt: base.addingTimeInterval(25 * minute),
                              endsAt: base.addingTimeInterval(40 * minute), meetingUrl: URL(string: "https://meet.google.com/abc")),
                CalendarEvent(title: "Lunch with Maya", startsAt: base.addingTimeInterval(125 * minute),
                              endsAt: base.addingTimeInterval(185 * minute), location: "Blue Bottle, Hayes St"),
                CalendarEvent(title: "Design review", startsAt: base.addingTimeInterval(245 * minute),
                              endsAt: base.addingTimeInterval(290 * minute)),
            ]),
            latestConversation: ConversationPreview(conversationId: "c_sample", title: "Weekly focus review",
                                                    lastAt: now.addingTimeInterval(-6 * minute),
                                                    preview: "You logged 18.5 hours of deep work, 3.2 more than last week."),
            journal: JournalStatus(available: true))
    }
}
