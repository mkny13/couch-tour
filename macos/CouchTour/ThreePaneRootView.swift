import AppKit
import CouchTourKit
import SwiftUI

private let periodicSyncInterval: Duration = .seconds(15 * 60)

/// 3-Pane Desktop Root View for macOS Couch Tour (Screen 2A/2B/2C/2E).
/// Left Pane: Fixed Sidebar (~236px) - Navigation, Favorites, and Sync.
/// Center Pane: Flexible Content (Home, Search, Show Detail, Library).
/// Right Pane: Fixed Player Rail (392px) - Active Show, Waveform Scrubber, Up Next Queue.
struct ThreePaneRootView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.ledgerColors) private var colors
    @FocusState private var searchFieldFocused: Bool

    @State private var favoritedArtists: [ArtistRef] = []
    @State private var relistenArtists: [ArtistRef] = []

    var body: some View {
        HStack(spacing: 0) {
            // Left Sidebar
            SidebarView(favoritedArtists: favoritedArtists) { artist in
                appModel.navigate(to: .artist(artist))
            }

            Divider().overlay(colors.panelBorder)

            // Center Content Pane
            VStack(spacing: 0) {
                NavigationStack(path: $appModel.path) {
                    HomeView()
                        .navigationDestination(for: Route.self) { destination(for: $0) }
                }
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
                            Label("Expanded Player", systemImage: "arrow.up.left.and.arrow.down.right")
                        }
                    }
                }
            }
            .frame(minWidth: 500, maxWidth: .infinity)
            .background(colors.background)

            Divider().overlay(colors.panelBorder)

            // Right Player Rail
            PlayerRailView()
        }
        .background(colors.background)
        .sheet(isPresented: $appModel.showNowPlaying) {
            ExpandedNowPlayingView()
        }
        .task {
            appModel.syncNow()
            await loadFavorites()
            while !Task.isCancelled {
                try? await Task.sleep(for: periodicSyncInterval)
                appModel.syncNow()
            }
        }
        .onChange(of: appModel.favorites.keys) { _, _ in
            Task { await loadFavorites() }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            appModel.syncNow()
        }
    }

    private func loadFavorites() async {
        if relistenArtists.isEmpty {
            relistenArtists = (try? await RelistenCatalogSource.shared.artists()) ?? []
        }
        let merged = mergeArtists(relistenArtists: relistenArtists, favorites: appModel.favorites.keys)
        favoritedArtists = merged.filter { appModel.favorites.keys.contains($0.key) }
    }

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

    private var searchField: some View {
        HStack(spacing: 5) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(colors.textMuted)
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
                        .foregroundStyle(colors.textMuted)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .background(colors.surface, in: Capsule())
    }
}
