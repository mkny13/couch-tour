import Foundation

// phish.in account login (#57, port of Android's Auth.kt). A separate Keychain service from
// SyncTokenStore's — pairing and signing out of phish.in are unrelated (Sync.swift's own
// comment on SyncTokenStore already calls this out).

/// Only the JWT and username are ever persisted — the password is used for one request and
/// never stored, matching Android's `TokenStore` exactly.
public final class PhishInTokenStore {
    private let keychain: KeychainStoring
    private var memoryJwt: String?
    private var memoryUsername: String?

    private enum Key {
        static let jwt = "phishin.jwt"
        static let username = "phishin.username"
    }

    public init(keychain: KeychainStoring = SystemKeychain(service: "dev.mike.couchtour.phishin")) {
        self.keychain = keychain
        self.memoryJwt = keychain.get(forKey: Key.jwt)
        self.memoryUsername = keychain.get(forKey: Key.username)
    }

    public var jwt: String? {
        get { keychain.get(forKey: Key.jwt) ?? memoryJwt }
        set {
            memoryJwt = newValue
            keychain.set(newValue, forKey: Key.jwt)
        }
    }

    public var username: String? {
        get { keychain.get(forKey: Key.username) ?? memoryUsername }
        set {
            memoryUsername = newValue
            keychain.set(newValue, forKey: Key.username)
        }
    }

    public func clear() {
        memoryJwt = nil
        memoryUsername = nil
        keychain.set(nil, forKey: Key.jwt)
        keychain.set(nil, forKey: Key.username)
    }
}

/// Facade over `PhishInTokenStore` + `PhishInAPI`'s auth state, port of Android's `Session`
/// object. Restores from storage and wires `PhishInAPI.onUnauthorized` to `logout()` on
/// init, so constructing this once in `AppModel.init()` — before any screen or request runs
/// — is all a caller needs to do (mirrors `CouchTourApp.kt`'s explicit "restore the session
/// before any screen or the playback service issues a request").
@MainActor
public final class PhishInSession: ObservableObject {
    private let store: PhishInTokenStore

    @Published public private(set) var username: String?

    public init(store: PhishInTokenStore = PhishInTokenStore()) {
        self.store = store
        self.username = store.username
        PhishInAPI.authToken = store.jwt
        // Formed inside this MainActor init, so the closure itself is MainActor-isolated —
        // the actual hop off of `send()`'s background context happens at its `await
        // onUnauthorized?()` call site, not here.
        PhishInAPI.onUnauthorized = { [weak self] in
            self?.logout()
        }
    }

    public func login(email: String, password: String) async throws {
        let response = try await PhishInAPI.login(email: email, password: password)
        store.jwt = response.jwt
        store.username = response.username
        PhishInAPI.authToken = response.jwt
        username = response.username
    }

    public func logout() {
        store.clear()
        PhishInAPI.authToken = nil
        username = nil
    }
}
