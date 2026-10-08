import SamRabbitKit
import SwiftUI

/// Page 3: what T3 is working on. Tap a task for its latest messages, Reply and Stop.
struct WorkingPage: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(spacing: 7) {
                if let problem = model.tasksProblem { TasksNoticeRow(error: problem) }
                if model.working.isEmpty, model.tasksProblem != nil {
                    EmptyView()
                } else if model.working.isEmpty {
                    EmptyNote(symbol: "moon.zzz.fill", title: "Nothing running", detail: "Ask from the first page.",
                              tint: SamTheme.cyan)
                } else {
                    ForEach(model.working) { thread in
                        NavigationLink(value: ThreadRoute(threadId: thread.threadId, title: thread.title)) {
                            WorkingRow(thread: thread)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 2)
        }
        .navigationTitle("Working")
        .withBanner()
        .samPage(SamTheme.cyan)
    }
}

struct WorkingRow: View {
    let thread: TaskThread

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            PulseDot(color: SamTheme.cyan, size: 7).padding(.top, 5)
            VStack(alignment: .leading, spacing: 2) {
                Text(thread.title)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(SamTheme.ink)
                    .lineLimit(2)
                if let summary = thread.summary {
                    Text(summary).font(.system(size: 12.5)).foregroundStyle(SamTheme.ink2).lineLimit(2)
                }
                ThreadMeta(thread: thread)
            }
            Spacer(minLength: 0)
        }
        .watchCard(tint: SamTheme.cyan)
    }
}

/// A task's latest messages with Reply (dictation), Approve / Deny when it waits, and Stop.
struct ThreadDetailView: View {
    @Environment(WatchModel.self) private var model
    let route: ThreadRoute
    @State private var detail: ThreadDetail?
    @State private var failed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if let detail {
                    HStack(spacing: 6) {
                        StatusChip(detail.thread.status)
                        Spacer(minLength: 0)
                    }
                    Text(detail.thread.title).font(.system(size: 16, weight: .semibold)).foregroundStyle(SamTheme.ink)
                    ThreadMeta(thread: detail.thread)
                    ForEach(detail.messages.suffix(4)) { message in
                        MessageBubble(message: message)
                    }
                    actions(for: detail)
                } else if failed {
                    EmptyNote(symbol: "wifi.exclamationmark", title: "Couldn't load", detail: "Is your Mac awake?",
                              tint: SamTheme.amber)
                } else {
                    ProgressView().frame(maxWidth: .infinity).padding(.top, 30)
                }
            }
            .padding(.horizontal, 2)
        }
        .navigationTitle("Task")
        .withBanner()
        .containerBackground(for: .navigation) {
            ZStack {
                SamTheme.night
                LinearGradient(colors: [SamTheme.orb.opacity(0.35), .clear], startPoint: .top, endPoint: .center)
            }
        }
        .task { await load() }
    }

    @ViewBuilder private func actions(for detail: ThreadDetail) -> some View {
        let thread = detail.thread
        let busy = model.busy.contains(thread.threadId)
        if thread.status == .needsApproval, let pending = detail.pending, pending.canRespond {
            if !pending.text.isEmpty {
                Text(pending.text).font(.system(size: 12.5)).foregroundStyle(SamTheme.ink2)
            }
            HStack(spacing: 6) {
                Button("Approve") { Task { await model.approve(thread, true, pending: pending); await load() } }
                    .buttonStyle(.borderedProminent).tint(SamTheme.green.opacity(0.85))
                Button("Deny") { Task { await model.approve(thread, false, pending: pending); await load() } }
                    .buttonStyle(.bordered).tint(SamTheme.red)
            }
            .disabled(busy)
        }
        DictationButton(title: thread.status == .needsInput ? "Answer" : "Reply",
                        prompt: "Reply to “\(Formatting.clip(thread.title, 30))”",
                        colors: [SamTheme.orb2, SamTheme.orb], height: 40, busy: busy) { text in
            Task {
                if thread.status == .needsInput {
                    await model.answer(thread, text, pending: detail.pending)
                } else {
                    await model.reply(thread, text)
                }
                await load()
            }
        }
        if thread.status == .working {
            Button(role: .destructive) {
                Task { await model.stop(thread); await load() }
            } label: {
                Label("Stop", systemImage: "stop.fill").frame(maxWidth: .infinity)
            }
            .disabled(busy)
        }
    }

    private func load() async {
        guard let client = model.account.client(timeout: 8) else { return }
        do {
            detail = try await client.thread(route.threadId)
            failed = false
        } catch {
            failed = detail == nil
        }
    }
}

struct MessageBubble: View {
    let message: ThreadMessage

    var body: some View {
        let mine = message.role == .user
        VStack(alignment: .leading, spacing: 2) {
            Text(mine ? "You" : message.role == .tool ? "Tool" : "T3")
                .font(.system(size: 10.5, weight: .semibold))
                .foregroundStyle(mine ? SamTheme.orbPale : SamTheme.muted)
            Text(Self.plain(message.text))
                .font(.system(size: 13))
                .foregroundStyle(mine ? SamTheme.ink : SamTheme.ink2)
                .lineLimit(8)
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
            .fill(mine ? SamTheme.orb.opacity(0.28) : Color.white.opacity(0.07)))
    }

    /// Markdown to plain inline text (bold, code and lists read fine on a small screen).
    static func plain(_ text: String) -> AttributedString {
        let options = AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        let cleaned = text.replacingOccurrences(of: "```", with: "")
        return (try? AttributedString(markdown: cleaned, options: options)) ?? AttributedString(text)
    }
}
