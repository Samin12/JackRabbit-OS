import Foundation
import Security
import Synchronization

/// Where the mobile token lives.
public protocol SecretStore: Sendable {
    func secret(for account: String) -> String?
    @discardableResult func setSecret(_ value: String?, for account: String) -> Bool
}

/// The Keychain, shared through the App Group access group so the widgets and intents can read
/// the token. Items are readable after the first unlock (widgets refresh while the phone is locked)
/// and never leave the device (`ThisDeviceOnly`, no iCloud sync).
///
/// When the Keychain refuses the shared group (an unsigned simulator build has no
/// keychain-access-groups entitlement), the token falls back to a protected file in the App Group
/// container, so the widgets still work there.
public final class KeychainStore: SecretStore {
    public static let shared = KeychainStore()

    private let service: String
    private let accessGroup: String?
    private let fallback: SharedContainer?

    public init(service: String = SamRabbit.keychainService, accessGroup: String? = SamRabbit.appGroup,
                fallback: SharedContainer? = .shared) {
        self.service = service
        self.accessGroup = accessGroup
        self.fallback = fallback
    }

    private func query(_ account: String, group: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let group { query[kSecAttrAccessGroup as String] = group }
        return query
    }

    public func secret(for account: String) -> String? {
        for group in groups {
            var request = query(account, group: group)
            request[kSecReturnData as String] = true
            request[kSecMatchLimit as String] = kSecMatchLimitOne
            var item: CFTypeRef?
            if SecItemCopyMatching(request as CFDictionary, &item) == errSecSuccess, let data = item as? Data,
               let text = String(data: data, encoding: .utf8) {
                return text
            }
        }
        if let data = fallback?.read(fallbackName(account)), let text = String(data: data, encoding: .utf8) {
            return text
        }
        return nil
    }

    @discardableResult
    public func setSecret(_ value: String?, for account: String) -> Bool {
        for group in groups { SecItemDelete(query(account, group: group) as CFDictionary) }
        fallback?.remove(fallbackName(account))
        guard let value else { return true }
        for group in groups {
            var item = query(account, group: group)
            item[kSecValueData as String] = Data(value.utf8)
            item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(item as CFDictionary, nil)
            if status == errSecSuccess {
                // Read it back from the shared group: some simulator builds accept the write but
                // cannot find the item again.
                if secretInKeychain(account, group: group) != nil { return true }
            }
            kitLog.notice("keychain write failed (\(status, privacy: .public)) for group \(group ?? "default", privacy: .public)")
        }
        guard let fallback else { return false }
        do {
            try fallback.write(Data(value.utf8), to: fallbackName(account))
            kitLog.notice("token kept in the App Group container (keychain unavailable)")
            return true
        } catch {
            return false
        }
    }

    private func secretInKeychain(_ account: String, group: String?) -> String? {
        var request = query(account, group: group)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &item) == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    /// The shared group first (widgets need it), then the app's default group.
    private var groups: [String?] {
        accessGroup.map { [$0] } ?? [nil]
    }

    private func fallbackName(_ account: String) -> String { ".\(account).secret" }
}

/// Secrets in memory (tests, previews).
public final class MemorySecretStore: SecretStore {
    private let values = Mutex<[String: String]>([:])

    public init(_ initial: [String: String] = [:]) {
        values.withLock { $0 = initial }
    }

    public func secret(for account: String) -> String? { values.withLock { $0[account] } }

    @discardableResult
    public func setSecret(_ value: String?, for account: String) -> Bool {
        values.withLock { $0[account] = value }
        return true
    }
}
