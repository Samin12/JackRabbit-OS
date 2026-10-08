import SamRabbitKit
import SwiftUI

/// Chats: every R1 conversation from the bridge's sync store, newest first, with search and
/// live badges. Refreshes from the live stream while visible.
struct ChatsView: View {
    @Environment(AppModel.self) private var model
    @State private var conversations: [ConversationSummary] = []
    @State private var query = ""
    @State private var nextBefore: Int?
    @State private var loading = false
    @State private var error: BridgeError?

    var body: some View {
        List {
            if !model.isPaired {
                PairPromptCard().listRowBackground(Color.clear).listRowSeparator(.hidden)
            }
            ForEach(conversations) { conversation in
                NavigationLink(value: ConversationRoute(conversationId: conversation.conversationId,
                                                        title: conversation.title)) {
                    ConversationRow(conversation: conversation)
                }
                .listRowBackground(Color.clear)
                .listRowSeparatorTint(SamTheme.line)
                .listRowSeparator(.hidden, edges: conversation.id == conversations.first?.id ? .top : [])
                .onAppear {
                    if conversation.id == conversations.last?.id, nextBefore != nil { Task { await loadMore() } }
                }
            }
            if conversations.isEmpty, model.isPaired, !loading {
                ContentUnavailableView(query.isEmpty ? "No conversations yet" : "No matches",
                                       systemImage: "waveform",
                                       description: Text(query.isEmpty ? "Talk to your R1 and the conversation shows up here."
                                                         : "Nothing mentions “\(query)”."))
                    .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .samScreen()
        .navigationTitle("Chats")
        .searchable(text: $query, prompt: "Search conversations")
        .refreshable { await load() }
        .task(id: query) {
            if !query.isEmpty { try? await Task.sleep(for: .milliseconds(300)) }
            await load()
        }
        .task(id: model.pairing?.deviceId) { await follow() }
    }

    func load() async {
        guard let client = model.client else { return }
        loading = true
        defer { loading = false }
        do {
            let page = try await client.conversations(limit: 40, query: query.isEmpty ? nil : query)
            withAnimation(.smooth) { conversations = page.conversations }
            nextBefore = page.nextBefore
            error = nil
        } catch let failure as BridgeError {
            if failure != .cancelled { error = failure }
        } catch {}
    }

    func loadMore() async {
        guard let client = model.client, let before = nextBefore else { return }
        nextBefore = nil
        if let page = try? await client.conversations(limit: 40, before: before, query: query.isEmpty ? nil : query) {
            conversations += page.conversations.filter { item in !conversations.contains { $0.id == item.id } }
            nextBefore = page.nextBefore
        }
    }

    /// Reloads the list (debounced) when the stream reports activity.
    func follow() async {
        guard let client = model.client else { return }
        var pending: Task<Void, Never>?
        for await update in LiveSyncFeed(client: client).updates(after: nil) {
            guard case .event = update else { continue }
            pending?.cancel()
            pending = Task {
                try? await Task.sleep(for: .seconds(1.2))
                if !Task.isCancelled, query.isEmpty { await load() }
            }
        }
    }
}

struct ConversationRow: View {
    let conversation: ConversationSummary

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle().fill(conversation.live ? SamTheme.orb.opacity(0.22) : SamTheme.glass2)
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(conversation.live ? SamTheme.orbPale : SamTheme.muted)
            }
            .frame(width: 38, height: 38)
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(conversation.displayTitle)
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(SamTheme.ink)
                        .lineLimit(1)
                    if conversation.live { LiveBadge() }
                    Spacer(minLength: 4)
                    Text(Formatting.ago(conversation.lastAt))
                        .font(.system(size: 12))
                        .foregroundStyle(SamTheme.faint)
                }
                if let preview = conversation.preview {
                    Text(preview)
                        .font(.system(size: 14))
                        .foregroundStyle(SamTheme.muted)
                        .lineLimit(2)
                }
                Text(Formatting.count(conversation.messageCount, "message"))
                    .font(.system(size: 11.5))
                    .foregroundStyle(SamTheme.faint)
            }
        }
        .padding(.vertical, 6)
    }

    var icon: String {
        if conversation.live { return "waveform" }
        if conversation.device == "phone" || conversation.title == "Phone" { return "iphone" }
        return "bubble.left.and.bubble.right"
    }
}

/// One conversation: messages, cards, images and generated UIs, live over SSE while open.
struct ConversationView: View {
    @Environment(AppModel.self) private var model
    let conversationId: String
    var initialTitle: String?
    @State private var timeline: ConversationTimeline
    @State private var items: [TimelineItem] = []
    @State private var summary: ConversationSummary?
    @State private var loaded = false
    @State private var error: BridgeError?
    @State private var connected = false
    @State private var viewerImage: ViewerImage?
    @State private var viewerArtifact: GeneratedUIItem?

    init(conversationId: String, initialTitle: String?) {
        self.conversationId = conversationId
        self.initialTitle = initialTitle
        _timeline = State(initialValue: ConversationTimeline(conversationId: conversationId))
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    if loaded, items.isEmpty {
                        ContentUnavailableView("Nothing here yet", systemImage: "waveform")
                    }
                    ForEach(items) { item in
                        TimelineRow(item: item, openImage: { viewerImage = $0 }, openUI: { viewerArtifact = $0 })
                            .id(item.id)
                            .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                    if !loaded, error == nil {
                        ProgressView().frame(maxWidth: .infinity).padding(.top, 60)
                    }
                    if let error, !loaded {
                        ContentUnavailableView(error.shortDescription, systemImage: "wifi.exclamationmark",
                                               description: Text(error.errorDescription ?? ""))
                    }
                    Color.clear.frame(height: 8).id("end")
                }
                .padding(.horizontal, 14)
                .padding(.top, 6)
                .padding(.bottom, 20)
                .animation(.smooth(duration: 0.3), value: items.count)
            }
            .defaultScrollAnchor(.bottom)
            .onChange(of: items.last?.version) { _, _ in
                withAnimation(.smooth) { proxy.scrollTo("end", anchor: .bottom) }
            }
            .onChange(of: items.count) { _, _ in
                withAnimation(.smooth) { proxy.scrollTo("end", anchor: .bottom) }
            }
        }
        .samScreen()
        .navigationTitle(summary?.displayTitle ?? initialTitle ?? "Conversation")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if summary?.live == true || model.summary?.r1.liveConversationId == conversationId {
                    LiveBadge()
                } else if connected {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .foregroundStyle(SamTheme.faint)
                        .accessibilityLabel("Following live updates")
                }
            }
        }
        .task(id: conversationId) { await run() }
        .fullScreenCover(item: $viewerImage) { image in
            ZoomableImageViewer(image: image.image, title: image.title)
        }
        .fullScreenCover(item: $viewerArtifact) { ui in
            GeneratedUIViewer(artifactId: ui.artifactId, title: ui.title)
        }
    }

    /// Loads the history, then applies live events until the view goes away.
    func run() async {
        guard let client = model.client else { return }
        do {
            let page = try await client.allEvents(conversationId: conversationId)
            timeline.apply(page.events)
            items = timeline.sorted
            loaded = true
            error = nil
            if let list = try? await client.conversations(limit: 60) {
                summary = list.conversations.first { $0.conversationId == conversationId }
            }
            for await update in LiveSyncFeed(client: client).updates(after: page.cursor) {
                switch update {
                case .connected:
                    connected = true
                case .disconnected:
                    connected = false
                case .event(let event):
                    guard event.conversationId == conversationId else { continue }
                    if timeline.apply(event) { items = timeline.sorted }
                    if event.type == "conversation.ended" || event.type == "conversation.started" {
                        summary = try? await client.conversations(limit: 60).conversations
                            .first { $0.conversationId == conversationId }
                    }
                }
            }
        } catch let failure as BridgeError {
            if failure != .cancelled { error = failure }
        } catch {}
    }
}

struct ViewerImage: Identifiable {
    let id = UUID()
    let image: UIImage
    let title: String
}
