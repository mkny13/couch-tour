import AppKit
import Combine
import CouchTourKit
import Foundation

/// Which form ⌘, shows. Home's Settings & status tiles each name one (D203) — the duplicate
/// Account and Sync sheets Home used to carry are gone, so this is how a tile still lands
/// somewhere specific.
enum SettingsTab: Hashable {
    case playback
    case account
    case sync
}

/// Holds the one `ProgressStore` for the app's lifetime. A failure to open the on-disk
/// database is surfaced rather than crashing the app outright — the browse and playback
/// screens don't depend on it, only Listening does.
@MainActor
final class AppModel: ObservableObject {
    let progressStore: ProgressStore?
    let progressStoreError: String?
    /// nil under the same condition `progressStore` is — local playlists share its
    /// `phishin.db` connection (#59), so there's nothing to open independently.
    let localPlaylistStore: LocalPlaylistStore?
    #if BETA
    let syncSession = SyncSession(store: SyncTokenStore(keychain: SystemKeychain(service: "dev.mike.couchtour.beta.sync")))
    #else
    let syncSession = SyncSession()
    #endif
    /// `UserDefaults.standard` is already per-bundle-id, so the beta target's favorites don't
    /// need the explicit namespacing Keychain services and the GRDB file do (Player.swift's
    /// volume setting relies on the same fact).
    let favorites = Favorites()
    let likedTracks = LikedTracks()
    let playbackSettings = PlaybackSettings()
    let themeSettings = ThemeSettings()
    #if BETA
    let phishInSession = PhishInSession(store: PhishInTokenStore(keychain: SystemKeychain(service: "dev.mike.couchtour.beta.phishin")))
    #else
    let phishInSession = PhishInSession()
    #endif
    let updater = UpdaterViewModel()
    /// Whether the Now Playing inspector is open. Lives here, not as local `@State` in
    /// RootView, because CouchTourApp's View-menu toggle needs to reach it too.
    @Published var showNowPlaying = false
    /// The window's one navigation path (D203). Empty is Home. Lives here rather than as
    /// `@State` in RootView for the same reason `showNowPlaying` does — the menu bar's ⌘1–⌘4
    /// and the player bar both move the window from outside RootView.
    ///
    /// A typed `[Route]` rather than a `NavigationPath` because the toolbar breadcrumb has to
    /// read the trail back out, and `NavigationPath` is type-erased.
    @Published var path: [Route] = []
    /// The persistent toolbar search field's text. Shared rather than owned by SearchView
    /// because the field is now chrome that outlives the results screen it pushes.
    @Published var searchQuery = ""
    /// Set true to ask the toolbar's search field to take focus, then cleared once it does — a
    /// one-shot rather than persistent state, so it doesn't fight the field's own focus once
    /// the user starts typing. Still needed with the sidebar gone: a `Commands` scene can't
    /// reach a `@FocusState` directly. What it no longer needs is the section hop ⌘F used to
    /// take through `selection` (D169).
    @Published var focusSearchField = false
    /// Which tab ⌘, opens on. Home's Settings & status tiles set this before opening the
    /// window, so a tile lands on the form it names.
    @Published var settingsTab: SettingsTab = .playback
    private var themeCancellable: AnyCancellable?

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
        localPlaylistStore = progressStore.flatMap { try? LocalPlaylistStore(sharing: $0) }
        themeCancellable = themeSettings.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
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

    func checkForUpdates() {
        updater.checkForUpdates()
    }

    /// Sends the window to a destination clicked in the player bar or the Now Playing
    /// inspector, both of which sit outside the `NavigationStack`.
    ///
    /// Replaces the path rather than appending to it, so the click always lands on exactly the
    /// destination named — not on top of whatever the user had already drilled into (D202's
    /// reasoning, which survives the sidebar's removal intact). `routes(for:)` supplies the
    /// levels above it so Back walks up through them.
    func navigate(to destination: PlayerBarDestination) {
        path = routes(for: destination)
    }

    /// Jumps to a top-level destination — the ⌘1–⌘4 menu items, and Home's own tiles. Same
    /// replace-don't-append rule as `navigate(to:)`.
    func jump(to route: Route?) {
        path = route.map { [$0] } ?? []
    }

    /// Truncates the path to `count` levels, which is what clicking a breadcrumb does.
    func popTo(depth count: Int) {
        guard count < path.count else { return }
        path = Array(path.prefix(count))
    }

    static func launchUpdateScript() {
        #if BETA
        let scriptName = "scripts/install-beta.command"
        #else
        let scriptName = "scripts/install.command"
        #endif
        let baseURL = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()

        let scriptURL = baseURL.appendingPathComponent(scriptName)
        NSWorkspace.shared.open(scriptURL)
    }
}

