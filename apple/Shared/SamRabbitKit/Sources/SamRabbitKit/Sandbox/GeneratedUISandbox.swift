import Foundation

/// The network lockdown for a generated UI shown interactive in a web view: a WebKit content rule
/// list that blocks every load except `data:`, `blob:` and `about:` URLs and the four CDNs the
/// documents may import from (the bridge's own CSP lists the same ones).
///
/// WebKit's `url-filter` has no `|` (it rejects the whole list with "Disjunctions are not supported
/// yet"), so each scheme and host is its own rule. The viewer compiles this list before it loads
/// anything and refuses to show the document when compiling fails: never a web view without it.
public enum GeneratedUISandbox {
    /// The hosts a generated UI may load scripts, styles and fonts from (HTTPS only).
    public static let allowedHosts = ["cdnjs.cloudflare.com", "esm.sh", "cdn.jsdelivr.net", "unpkg.com"]

    /// Local URL schemes the document itself uses (the page, inline images, generated blobs).
    public static let allowedSchemes = ["data", "blob", "about"]

    /// The identifier the compiled list is stored under (bump it when the rules change).
    public static let ruleListIdentifier = "samrabbit.genui.v2"

    /// The rule list in WebKit's content blocker JSON: block everything, then let the schemes and
    /// the CDNs through (`ignore-previous-rules`).
    public static let contentRules: String = {
        var rules: [[String: Any]] = [["trigger": ["url-filter": ".*"], "action": ["type": "block"]]]
        for scheme in allowedSchemes {
            rules.append(["trigger": ["url-filter": "^\(scheme):"], "action": ["type": "ignore-previous-rules"]])
        }
        for host in allowedHosts {
            let pattern = "^https://" + host.replacingOccurrences(of: ".", with: "\\.") + "/"
            rules.append(["trigger": ["url-filter": pattern], "action": ["type": "ignore-previous-rules"]])
        }
        let data = try? JSONSerialization.data(withJSONObject: rules, options: [.sortedKeys, .withoutEscapingSlashes])
        // Never reached with these literals; if it were, block everything rather than nothing.
        return data.map { String(decoding: $0, as: UTF8.self) }
            ?? #"[{"trigger":{"url-filter":".*"},"action":{"type":"block"}}]"#
    }()

    /// The URL filters in `contentRules`, in order (for tests and diagnostics).
    public static var urlFilters: [String] {
        guard let data = contentRules.data(using: .utf8),
              let rules = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return [] }
        return rules.compactMap { ($0["trigger"] as? [String: Any])?["url-filter"] as? String }
    }

    /// A sub-frame or navigation target the document may load (the same allowlist as the rules).
    public static func allows(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return false }
        if allowedSchemes.contains(scheme) { return true }
        guard scheme == "https", let host = url.host?.lowercased() else { return false }
        return allowedHosts.contains(host)
    }
}
