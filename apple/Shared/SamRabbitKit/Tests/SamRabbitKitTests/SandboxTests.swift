#if canImport(WebKit)
import Foundation
import Testing
import WebKit
@testable import SamRabbitKit

/// The generated-UI network lockdown (`GeneratedUISandbox`): WebKit must accept the rule list
/// (it rejects a whole list over one `|` disjunction), and on macOS a real web view with it loads
/// nothing from a server that the same page reaches without it.
@Suite("Generated UI sandbox", .serialized)
@MainActor
struct SandboxTests {
    @Test func rulesHaveNoDisjunctions() {
        let filters = GeneratedUISandbox.urlFilters
        #expect(filters.first == ".*")
        #expect(filters.count == 1 + GeneratedUISandbox.allowedSchemes.count + GeneratedUISandbox.allowedHosts.count)
        #expect(!filters.contains { $0.contains("|") || $0.contains("(") })
        #expect(filters.contains("^https://cdnjs\\.cloudflare\\.com/"))
        #expect(GeneratedUISandbox.allows(URL(string: "https://esm.sh/react@19")!))
        #expect(!GeneratedUISandbox.allows(URL(string: "http://esm.sh/react@19")!))
        #expect(!GeneratedUISandbox.allows(URL(string: "https://evil.example/x.js")!))
        #expect(!GeneratedUISandbox.allows(URL(string: "https://esm.sh.evil.example/x.js")!))
    }

    @Test func webKitCompilesTheRuleList() async throws {
        let list = try await Self.compile()
        #expect(list.identifier == GeneratedUISandbox.ruleListIdentifier)
    }

    @Test func aDisjunctionWouldFailToCompile() async {
        // Guards the test above: this store really rejects what the old rules did.
        let store = Self.store()
        await #expect(throws: (any Error).self) {
            _ = try await store.compileContentRuleList(
                forIdentifier: "disjunction",
                encodedContentRuleList: #"[{"trigger":{"url-filter":"^(data|blob):"},"action":{"type":"block"}}]"#)
        }
    }

    #if os(macOS)
    @Test func theCompiledRulesBlockEveryOtherHost() async throws {
        let server = try ProbeServer()
        let open = try await server.hits(loading: nil)
        // The probe page reaches the server without the rules (so the check below means something)...
        #expect(Set(open).isSuperset(of: ["/img", "/fetch", "/script-img"]))
        // ...and reaches nothing with them.
        let locked = try await server.hits(loading: try await Self.compile())
        #expect(locked.isEmpty)
    }
    #endif

    static func store() -> WKContentRuleListStore {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("rules-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return WKContentRuleListStore(url: folder)!
    }

    static func compile() async throws -> WKContentRuleList {
        let list = try await store().compileContentRuleList(forIdentifier: GeneratedUISandbox.ruleListIdentifier,
                                                            encodedContentRuleList: GeneratedUISandbox.contentRules)
        return try #require(list)
    }
}

#if os(macOS)
/// A local HTTP server (stdlib Python, like the fake bridge) that records the paths it is asked for.
final class ProbeServer: @unchecked Sendable {
    let process = Process()
    let port: Int
    let log: URL

    init() throws {
        log = FileManager.default.temporaryDirectory.appendingPathComponent("probe-\(UUID().uuidString).log")
        FileManager.default.createFile(atPath: log.path, contents: nil)
        let script = """
        import http.server, socketserver, sys
        log = open(sys.argv[1], "a", buffering=1)
        class H(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                log.write(self.path + "\\n")
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(b"ok")
            def log_message(self, *args):
                pass
        server = socketserver.TCPServer(("127.0.0.1", 0), H)
        print("PORT %d" % server.server_address[1], flush=True)
        server.serve_forever()
        """
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = ["-I", "-c", script, log.path]
        let output = Pipe()
        process.standardOutput = output
        process.standardError = FileHandle.nullDevice
        try process.run()
        var buffer = Data()
        var found: Int?
        while found == nil {
            let chunk = output.fileHandleForReading.availableData
            if chunk.isEmpty { break }
            buffer.append(chunk)
            if let line = String(decoding: buffer, as: UTF8.self).split(separator: "\n").first(where: { $0.hasPrefix("PORT ") }) {
                found = Int(line.dropFirst(5))
            }
        }
        guard let found else {
            process.terminate()
            throw BridgeError.unreachable("the probe server did not start")
        }
        port = found
    }

    deinit { process.terminate() }

    /// Loads a page that asks the server for an image, a fetch and a script-made image, and returns
    /// the paths the server saw.
    @MainActor
    func hits(loading rules: WKContentRuleList?) async throws -> [String] {
        try Data().write(to: log)
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        if let rules { configuration.userContentController.add(rules) }
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 320, height: 240), configuration: configuration)
        let origin = "http://127.0.0.1:\(port)"
        view.loadHTMLString("""
            <html><body><img src="\(origin)/img"><script>
            fetch("\(origin)/fetch").catch(function () {});
            var probe = new Image(); probe.src = "\(origin)/script-img";
            document.title = "ran";
            </script></body></html>
            """, baseURL: nil)
        // The page itself runs either way (JavaScript is on); give the loads time to arrive.
        let deadline = Date.now.addingTimeInterval(10)
        while Date.now < deadline {
            try await Task.sleep(for: .milliseconds(200))
            if (try? await view.evaluateJavaScript("document.title")) as? String == "ran" { break }
        }
        #expect((try? await view.evaluateJavaScript("document.title")) as? String == "ran")
        try await Task.sleep(for: .seconds(rules == nil ? 1.5 : 3))
        let text = (try? String(contentsOf: log, encoding: .utf8)) ?? ""
        return text.split(separator: "\n").map(String.init)
    }
}
#endif
#endif
