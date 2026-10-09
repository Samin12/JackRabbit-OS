import SamRabbitKit
import SwiftUI
import WebKit

/// A generated UI, interactive, in a locked-down web view: JavaScript only, no storage, no
/// cookies, no access to app data, and no network except the four CDNs the documents may import
/// from (`GeneratedUISandbox`, enforced by a WebKit content rule list). The rule list is compiled
/// before anything loads; when it cannot be compiled the document is not shown at all (fail
/// closed). Links open in Safari.
struct GeneratedUIViewer: View {
    let artifactId: String
    var title: String = ""
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var loaded: (html: String, rules: WKContentRuleList)?
    @State private var error: BridgeError?
    @State private var lockdownFailed = false

    var body: some View {
        NavigationStack {
            ZStack {
                SamBackdrop()
                if let loaded {
                    SandboxedWebView(html: loaded.html, rules: loaded.rules).ignoresSafeArea(edges: .bottom)
                } else if lockdownFailed {
                    ContentUnavailableView("Can't open this safely", systemImage: "lock.trianglebadge.exclamationmark",
                                           description: Text("SamRabbit couldn't switch on the network lock for "
                                                             + "generated UIs, so it won't open this one."))
                } else if let error {
                    ContentUnavailableView(error.shortDescription, systemImage: "exclamationmark.triangle",
                                           description: Text(error.errorDescription ?? ""))
                } else {
                    VStack(spacing: 16) {
                        OrbView(mood: .working).frame(width: 64, height: 64)
                        Text("Loading…").foregroundStyle(SamTheme.muted)
                    }
                }
            }
            .navigationTitle(title.isEmpty ? "Generated UI" : title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(systemName: "xmark") }
                }
            }
        }
        .preferredColorScheme(.dark)
        .task {
            guard let client = model.client else { return }
            let rules: WKContentRuleList
            do {
                rules = try await SandboxedWebView.compileRules()
            } catch {
                lockdownFailed = true
                return
            }
            do {
                loaded = (try await client.artifactDocument(artifactId), rules)
            } catch let failure as BridgeError {
                error = failure
            } catch {}
        }
    }
}

/// A `WKWebView` that can only exist with the compiled lockdown rules installed.
struct SandboxedWebView: UIViewRepresentable {
    let html: String
    let rules: WKContentRuleList

    /// Compiles `GeneratedUISandbox.contentRules` (throws when WebKit rejects them; never load then).
    @MainActor
    static func compileRules() async throws -> WKContentRuleList {
        let store: WKContentRuleListStore = WKContentRuleListStore.default()
        guard let list = try await store.compileContentRuleList(forIdentifier: GeneratedUISandbox.ruleListIdentifier,
                                                                encodedContentRuleList: GeneratedUISandbox.contentRules)
        else { throw URLError(.cannotLoadFromNetwork) }
        return list
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(rules)
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.allowsInlineMediaPlayback = true
        configuration.dataDetectorTypes = []
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.backgroundColor = .clear
        view.scrollView.contentInsetAdjustmentBehavior = .automatic
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.allowsLinkPreview = false
        view.loadHTMLString(html, baseURL: nil)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let url = action.request.url else { return .cancel }
            if let scheme = url.scheme?.lowercased(), GeneratedUISandbox.allowedSchemes.contains(scheme) { return .allow }
            if action.navigationType == .linkActivated, url.scheme == "https" || url.scheme == "http" {
                await UIApplication.shared.open(url)
            }
            // Sub-frames from the CDNs are allowed; the document itself never navigates away.
            if action.targetFrame?.isMainFrame == false, GeneratedUISandbox.allows(url) {
                return .allow
            }
            return .cancel
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
            if let url = action.request.url, url.scheme == "https" { UIApplication.shared.open(url) }
            return nil
        }
    }
}
