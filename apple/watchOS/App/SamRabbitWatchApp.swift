import SamRabbitKit
import SwiftUI

@main
struct SamRabbitWatchApp: App {
    @State private var model: WatchModel
    @Environment(\.scenePhase) private var scenePhase

    init() {
        PhoneLink.shared.start()
        _model = State(initialValue: WatchModel())
    }

    var body: some Scene {
        WindowGroup {
            WatchRootView()
                .environment(model)
                .onOpenURL { model.handle(url: $0) }
                .task { ComplicationRenderHarness.runIfRequested() }
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: model.start()
            case .background: model.stop()
            default: break
            }
        }
    }
}

/// The five pages (Digital Crown / swipe), or the "pair on your iPhone" screen.
struct WatchRootView: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        @Bindable var model = model
        Group {
            if model.paired {
                NavigationStack {
                    TabView(selection: $model.page) {
                        StatusPage().tag(WatchPage.status)
                        NeedsYouPage().tag(WatchPage.needs)
                        WorkingPage().tag(WatchPage.working)
                        UpNextPage().tag(WatchPage.upnext)
                        QuickPage().tag(WatchPage.quick)
                    }
                    .tabViewStyle(.verticalPage)
                    .navigationDestination(for: ThreadRoute.self) { route in
                        ThreadDetailView(route: route)
                    }
                }
            } else {
                UnpairedView()
            }
        }
        .tint(SamTheme.accent)
        .preferredColorScheme(.dark)
    }
}

struct ThreadRoute: Hashable {
    var threadId: String
    var title: String
}

/// Shown until the iPhone has sent a pairing.
struct UnpairedView: View {
    @Environment(WatchModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(spacing: 8) {
                OrbView(mood: .offline, animated: false).frame(width: 64, height: 64).padding(.top, 8)
                Text("Pair on your iPhone").font(.system(size: 17, weight: .semibold)).foregroundStyle(SamTheme.ink)
                Text("Open SamRabbit on your iPhone and pair it with your Mac. Your watch connects by itself.")
                    .font(.system(size: 13))
                    .foregroundStyle(SamTheme.muted)
                    .multilineTextAlignment(.center)
                Button {
                    Task { await model.connect() }
                } label: {
                    CapsuleFace(title: model.connecting ? "Checking…" : "Check again", symbol: "iphone",
                                colors: [SamTheme.orb2, SamTheme.orb], height: 40, busy: model.connecting)
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
            .padding(.horizontal, 4)
        }
        .background(SamTheme.night.ignoresSafeArea())
        .withBanner()
    }
}
