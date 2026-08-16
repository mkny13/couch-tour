import Foundation
import Security

/// Seam over Keychain access so tests never touch the real system keychain — `swift test`
/// under an unattended CI/dev run should not be able to trigger a Keychain access prompt.
/// `SystemKeychain` is the real implementation; tests inject `InMemoryKeychain` instead.
public protocol KeychainStoring {
    func set(_ value: String?, forKey key: String)
    func get(forKey key: String) -> String?
}

/// The real Keychain, service `dev.mike.couchtour.sync`.
///
/// `kSecAttrAccessibleAfterFirstUnlock` rather than `WhenUnlocked`, so a background sync can
/// still read the token while the Mac is locked but has been unlocked at least once since
/// boot. `kSecAttrSynchronizable = false` is deliberate: a future iOS client must not
/// silently inherit this Mac's device identity via iCloud Keychain — pairing is meant to be
/// an explicit per-device act, and per-device revocation would otherwise break the moment two
/// devices shared one Keychain-synced identity.
public struct SystemKeychain: KeychainStoring {
    private let service = "dev.mike.couchtour.sync"

    public init() {}

    public func set(_ value: String?, forKey key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        SecItemDelete(query as CFDictionary)
        guard let value, let data = value.data(using: .utf8) else { return }

        var addQuery = query
        addQuery[kSecValueData as String] = data
        addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        addQuery[kSecAttrSynchronizable as String] = false
        SecItemAdd(addQuery as CFDictionary, nil)
    }

    public func get(forKey key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
}

/// Test-only in-memory stand-in — see `KeychainStoring`.
public final class InMemoryKeychain: KeychainStoring {
    private var storage: [String: String] = [:]

    public init() {}

    public func set(_ value: String?, forKey key: String) { storage[key] = value }
    public func get(forKey key: String) -> String? { storage[key] }
}
