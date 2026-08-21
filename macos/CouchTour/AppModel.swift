import CouchTourKit
import Foundation

/// Holds the one `ProgressStore` for the app's lifetime. A failure to open the on-disk
/// database is surfaced rather than crashing the app outright — the browse and playback
/// screens don't depend on it, only Continue Listening and History do.
@MainActor
final class AppModel: ObservableObject {
    let progressStore: ProgressStore?
    let progressStoreError: String?
    #if BETA
    let syncSession = SyncSession(store: SyncTokenStore(keychain: SystemKeychain(service: "dev.mike.couchtour.beta.sync")))
    #else
    let syncSession = SyncSession()
    #endif
    /// `UserDefaults.standard` is already per-bundle-id, so the beta target's favorites don't
    /// need the explicit namespacing Keychain services and the GRDB file do (Player.swift's
    /// volume setting relies on the same fact).
    let favorites = Favorites()
    /// Whether the Now Playing inspector is open. Lives here, not as local `@State` in
    /// RootView, because CouchTourApp's View-menu toggle needs to reach it too.
    @Published var showNowPlaying = false
    /// The sidebar's current section. Lives here rather than as `@State` in RootView for the
    /// same reason `showNowPlaying` does — CouchTourApp's ⌘F command switches to Search from
    /// outside RootView.
    @Published var selection: SidebarSection? = .artists
    /// Set true to ask SearchView to focus its search field, then cleared by SearchView once
    /// it does — a one-shot signal rather than persistent state, so it doesn't fight the
    /// field's own focus once the user starts typing.
    @Published var focusSearchField = false

    init() {
        do {
            #if BETA
            progressStore = try ProgressStore(url: ProgressStore.defaultURL(appSupportDirName: "dev.mike.couchtour.beta"))
            #else
            progressStore = try ProgressStore()
            #endif
            progressStoreError = nil
        } catch {
            progressStore = nil
            progressStoreError = "Couldn't open the listening history database: \(error)"
        }
    }

    /// One push-then-pull cycle. Fire-and-forget: `sync` is a no-op if unpaired, and any
    /// network failure here is caught and dropped — nothing surfaces a sync error to the UI
    /// in this MVP, matching macOS's own "Task on activate plus a timer" cadence rather than
    /// a guaranteed-background mechanism.
    func syncNow() {
        guard let progressStore else { return }
        Task { try? await syncSession.sync(progressStore) }
    }
}
