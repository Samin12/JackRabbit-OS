import SamRabbitKit
import SwiftUI
import WebKit

/// A generated UI, interactive, in a locked-down web view: JavaScript only, no storage, no
/// cookies, no access to app data, and no network except the four CDNs the documents may import
/// from (the bridge's own CSP lists the same ones). Links open in Safari.
struct GeneratedUIViewer: View {
    let artifactId: String
    var title: String = ""
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var html: String?
    @State private var error: BridgeError?

    var body: some View {
        NavigationStack {
            ZStack {
                SamBackdrop()
                if let html {
                    SandboxedWebView(html: html).ignoresSafeArea(edges: .bottom)
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
            do {
                html = try await client.artifactDocument(artifactId)
            } catch let failure as BridgeError {
                error = failure
            } catch {}
        }
    }
}

struct SandboxedWebView: UIViewRepresentable {
    let html: String

    static let allowedHosts = ["cdnjs.cloudflare.com", "esm.sh", "cdn.jsdelivr.net", "unpkg.com"]

    /// Blocks every load except the CDNs, data:, blob: and about: URLs.
    static let rules = """
    [{"trigger":{"url-filter":".*"},"action":{"type":"block"}},
     {"trigger":{"url-filter":"^(data|blob|about):"},"action":{"type":"ignore-previous-rules"}},
     {"trigger":{"url-filter":"^https://(cdnjs\\\\.cloudflare\\\\.com|esm\\\\.sh|cdn\\\\.jsdelivr\\\\.net|unpkg\\\\.com)/"},
      "action":{"type":"ignore-previous-rules"}}]
    """

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
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
        context.coordinator.load(html, into: view)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        func load(_ html: String, into view: WKWebView) {
            WKContentRuleListStore.default().compileContentRuleList(forIdentifier: "samrabbit.genui",
                                                                    encodedContentRuleList: SandboxedWebView.rules) { list, _ in
                Task { @MainActor in
                    if let list { view.configuration.userContentController.add(list) }
                    view.loadHTMLString(html, baseURL: nil)
                }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let url = action.request.url else { return .cancel }
            if url.scheme == "about" || url.scheme == "data" || url.scheme == "blob" { return .allow }
            if action.navigationType == .linkActivated, url.scheme == "https" || url.scheme == "http" {
                await UIApplication.shared.open(url)
            }
            // Sub-frames from the CDNs are allowed; the document itself never navigates away.
            if action.targetFrame?.isMainFrame == false, let host = url.host,
               SandboxedWebView.allowedHosts.contains(host) {
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
