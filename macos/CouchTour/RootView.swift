import AppKit
import CouchTourKit
import SwiftUI

/// Background catch-up cadence, in the absence of `BGTaskScheduler`-grade background
/// execution for this MVP: an immediate sync on launch and on returning to the foreground,
/// plus a 15-minute timer while the app stays open — the same interval Android's WorkManager
/// job uses, chosen there because it's WorkManager's own floor for periodic work.
private let periodicSyncInterval: Duration = .seconds(15 * 60)

/// The window: one `NavigationStack` over Home, with the toolbar carrying orientation (back,
/// breadcrumb), search, and the two utility toggles.
///
/// There is no sidebar (D203). Home's tiles are the navigation, so every destination below is
/// something the user drilled into from there — which is what makes a single stack correct
/// where six separate ones used to be necessary.
struct RootView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @FocusState private var searchFieldFocused: Bool

    var body: some View {
        VStack(spacing: 0) {
            NavigationStack(path: $appModel.path) {
                HomeView()
                    .navigationDestination(for: Route.self) { destination(for: $0) }
            }
            // One title for the window, rather than one per screen. Each destination used to
            // set its own `.navigationTitle`, which now renders *beside* the breadcrumb and
            // says the same thing twice ("Couch Tour" then "Home"). The breadcrumb is the
            // better of the two — it shows the path, not just the leaf — so the window title
            // steps back to being the app's name for the Window menu and Mission Control.
            .navigationTitle("Couch Tour")
            .toolbar {
                BackButtonToolbarItem()
                ToolbarItem(placement: .navigation) {
                    Breadcrumb()
                }
                ToolbarItem(placement: .primaryAction) {
                    searchField
                }
                ToolbarItem(placement: .primaryAction) {
                    FeedbackButton(currentScreen: appModel.path.last?.crumbTitle ?? "Home")
                }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        appModel.showNowPlaying.toggle()
                    } label: {
                        Label("Now Playing", systemImage: "music.note.list")
                    }
                }
            }
            // A trailing inspector rather than its own window: it can show the queue for
            // whatever's playing without disturbing the browse stack, and stays in the same
            // window as everything else.
            .inspector(isPresented: $appModel.showNowPlaying) {
                NowPlayingInspector()
                    .inspectorColumnWidth(min: 260, ideal: 300, max: 380)
            }
            .onChange(of: appModel.searchQuery) { _, _ in syncSearchRoute() }
            .onChange(of: appModel.focusSearchField) { _, shouldFocus in
                guard shouldFocus else { return }
                searchFieldFocused = true
                appModel.focusSearchField = false
            }

            if player.show != nil {
                Divider()
                MiniPlayerView()
            }
        }
        .task {
            appModel.syncNow()
            while !Task.isCancelled {
                try? await Task.sleep(for: periodicSyncInterval)
                appModel.syncNow()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            appModel.syncNow()
        }
    }

    /// Every destination the window can push, in one place — replacing the nine
    /// `navigationDestination(for:)` declarations that used to be spread across the browse,
    /// search, home, and playlist screens, several of them declaring the same type twice.
    ///
    /// `navigationBarBackButtonHidden` is applied here rather than per view for the same
    /// reason: it suppresses macOS's own automatic back chevron, which would otherwise sit
    /// beside the toolbar's Back button as a second one.
    @ViewBuilder
    private func destination(for route: Route) -> some View {
        Group {
            switch route {
            case .artists:
                ArtistsView()
            case .listening:
                ListeningView()
            case .playlists:
                LocalPlaylistsView()
            case .search:
                SearchView()
            case .artist(let artist):
                PeriodsView(artist: artist)
            case .period(let artist, let period):
                ShowsView(artist: artist, period: period)
            case .show(let show):
                ShowDetailView(show: show)
            case .localPlaylist(let playlist):
                LocalPlaylistView(playlistId: playlist.id)
            }
        }
        .navigationBarBackButtonHidden(true)
    }

    /// Search as persistent chrome rather than a destination you navigate to and lose your
    /// place in (superseding D169).
    ///
    /// A plain `TextField` and not `.searchable`: focusing a searchable field programmatically
    /// needs `.searchFocused`, which is macOS 15, and this app's deployment target is 14.0 —
    /// that gap is exactly what made ⌘F a no-op before. `@FocusState` on a real field has no
    /// such floor.
    private var searchField: some View {
        HStack(spacing: 5) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)

            TextField("Artists, songs, venues, dates…", text: $appModel.searchQuery)
                .textFieldStyle(.plain)
                .frame(width: 200)
                .focused($searchFieldFocused)
                .accessibilityLabel("Search")

            if !appModel.searchQuery.isEmpty {
                Button {
                    appModel.searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .background(Color.secondary.opacity(0.12), in: Capsule())
    }

    /// Typing opens the results screen; clearing the field closes it again. Only when `.search`
    /// is the *top* of the path, so a search made before drilling into a hit doesn't yank the
    /// user back out of the show they opened.
    private func syncSearchRoute() {
        let term = appModel.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        if term.count >= 3 {
            if appModel.path.last != .search {
                appModel.path.append(.search)
            }
        } else if term.isEmpty, appModel.path.last == .search {
            appModel.path.removeLast()
        }
    }
}
