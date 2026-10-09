import SamRabbitKit
import SwiftUI

/// Page 2: tasks waiting for you: Approve / Deny, the offered answers, or a spoken reply.
struct NeedsYouPage: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                if let problem = model.tasksProblem { TasksNoticeRow(error: problem) }
                if model.needsYou.isEmpty, model.tasksProblem != nil {
                    EmptyView()
                } else if model.needsYou.isEmpty {
                    EmptyNote(symbol: "checkmark.circle.fill", title: "Nothing needs you",
                              detail: model.workingCount > 0 ? "\(Formatting.count(model.workingCount, "task")) working." : nil)
                } else {
                    ForEach(model.needsYou) { thread in
                        NeedsYouCard(thread: thread)
                    }
                }
            }
            .padding(.horizontal, 2)
        }
        .navigationTitle("Needs you")
        .withBanner()
        .samPage(SamTheme.amber)
    }
}

struct NeedsYouCard: View {
    @Environment(WatchModel.self) private var model
    let thread: TaskThread

    var body: some View {
        let style = StatusStyle(thread.status)
        let busy = model.busy.contains(thread.threadId)
        let pending = thread.pending
        let approval = pending?.kind == .approval || (pending?.kind != .question && thread.status == .needsApproval)
        // Only a card that knows its request may answer it (summary threads don't): open it first.
        let canRespond = pending?.canRespond == true
        VStack(alignment: .leading, spacing: 6) {
            NavigationLink(value: ThreadRoute(threadId: thread.threadId, title: thread.title)) {
                VStack(alignment: .leading, spacing: 3) {
                    HStack(spacing: 4) {
                        Image(systemName: style.symbol).font(.system(size: 10, weight: .bold))
                        Text(approval ? "Approval" : "Question").font(.system(size: 11.5, weight: .semibold))
                        Spacer(minLength: 2)
                        Text(thread.projectName ?? "").font(.system(size: 11)).foregroundStyle(SamTheme.muted).lineLimit(1)
                    }
                    .foregroundStyle(style.color)
                    Text(thread.title)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(SamTheme.ink)
                        .lineLimit(3)
                    if let text = thread.pending?.text, !text.isEmpty {
                        Text(text).font(.system(size: 12.5)).foregroundStyle(SamTheme.ink2).lineLimit(4)
                    } else if let summary = thread.summary {
                        Text(summary).font(.system(size: 12.5)).foregroundStyle(SamTheme.ink2).lineLimit(3)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)

            if !canRespond {
                NavigationLink(value: ThreadRoute(threadId: thread.threadId, title: thread.title)) {
                    Label(approval ? "Review" : "Open", systemImage: "arrow.up.right")
                        .font(.system(size: 14, weight: .semibold)).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            } else if approval {
                HStack(spacing: 6) {
                    Button {
                        Task { await model.approve(thread, true, pending: pending) }
                    } label: {
                        Text("Approve").font(.system(size: 15, weight: .semibold)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(SamTheme.green.opacity(0.85))
                    .accessibilityIdentifier("approve-\(thread.threadId)")
                    Button {
                        Task { await model.approve(thread, false, pending: pending) }
                    } label: {
                        Text("Deny").font(.system(size: 15, weight: .semibold)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(SamTheme.red)
                    .accessibilityIdentifier("deny-\(thread.threadId)")
                }
                VoiceButton(title: "Reply", purpose: .reply(thread),
                            colors: [Color.white.opacity(0.16), Color.white.opacity(0.10)], height: 36)
                    .accessibilityIdentifier("reply-\(thread.threadId)")
            } else {
                ForEach((thread.pending?.options ?? []).prefix(4)) { option in
                    Button {
                        Task { await model.answer(thread, option.value, pending: pending) }
                    } label: {
                        Text(option.label).font(.system(size: 14, weight: .medium)).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .tint(SamTheme.violet)
                }
                VoiceButton(title: "Answer", purpose: .answer(thread, pending),
                            colors: [SamTheme.violet.opacity(0.9), SamTheme.violet.opacity(0.6)], height: 38)
                    .accessibilityIdentifier("answer-\(thread.threadId)")
            }
        }
        .disabled(busy)
        .opacity(busy ? 0.55 : 1)
        .overlay { if busy { ProgressView() } }
        .watchCard(tint: style.color)
    }
}
