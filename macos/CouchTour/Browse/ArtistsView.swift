import CouchTourKit
import SwiftUI

struct ArtistsView: View {
    @EnvironmentObject private var favorites: Favorites
    @EnvironmentObject private var appModel: AppModel
    @State private var relistenArtists: [ArtistRef] = []
    @State private var loadState: LoadState = .loading
    /// Owned here rather than left implicit, so a queued `AppModel.pendingArtistsDestination`
    /// (below) has a path to push onto — RootView's `NavigationStack { ArtistsView() }`
    /// wrapping has no explicit path otherwise. Still gets a fresh instance whenever the
    /// sidebar switches away from and back to Artists, same as every other section's state
    /// (see RootView.swift's comment on why each section gets its own `NavigationStack`).
    @State private var path = NavigationPath()

    private var merged: [ArtistRef] {
        mergeArtists(relistenArtists: relistenArtists, favorites: favorites.keys)
    }

    private var favoritedArtists: [ArtistRef] {
        merged.filter { favorites.keys.contains($0.key) }
    }

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                switch loadState {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                case .failed(let message):
                    ErrorView(message: message) { await load() }
                case .loaded:
                    List {
                        // Separate from the full "Artists" section below (Android parity, #56):
                        // quick access to a handful of artists without scrolling. Favoriting
                        // doesn't remove an artist from the full list, so it still shows up in
                        // both places.
                        if !favoritedArtists.isEmpty {
                            Section("Favorites") {
                                ForEach(favoritedArtists, id: \.self) { artist in
                                    artistRow(artist)
                                }
                            }
                        }
                        Section("Artists") {
                            ForEach(merged, id: \.self) { artist in
                                artistRow(artist)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Artists")
            .navigationDestination(for: ArtistRef.self) { PeriodsView(artist: $0) }
            .navigationDestination(for: ShowSummary.self) { ShowDetailView(show: $0) }
            .task { await load() }
        }
        // The player bar sets `selection = .artists` and `pendingArtistsDestination` in the
        // same call (AppModel.navigate(to:)), which mounts this whole view — including this
        // `onChange` — for the first time with the destination already set. `onChange` only
        // fires on a transition it observes while attached, so it alone misses that case; this
        // `onAppear` catches it. `onChange` still does the work for a second player-bar click
        // while already on Artists, where the view was already mounted.
        .onAppear { consumePendingArtistsDestination() }
        .onChange(of: appModel.pendingArtistsDestination) { _, _ in consumePendingArtistsDestination() }
    }

    private func consumePendingArtistsDestination() {
        guard let destination = appModel.pendingArtistsDestination else { return }
        // Replaces the path rather than appending, so a player-bar click always lands on
        // exactly the pushed destination — not on top of whatever the user had already
        // drilled into. That also means Back (BackButton.swift) always pops to the
        // Artists list, which is the one screen guaranteed to make sense as "home" even
        // when the user never browsed here themselves.
        switch destination {
        case .artist(let artist):
            path = NavigationPath([artist])
        case .show(let show):
            path = NavigationPath([show])
        }
        appModel.pendingArtistsDestination = nil
    }

    @ViewBuilder
    private func artistRow(_ artist: ArtistRef) -> some View {
        NavigationLink(value: artist) {
            HStack {
                VStack(alignment: .leading) {
                    Text(artist.name)
                    if artist.showCount > 0 {
                        Text("\(artist.showCount) \(plural(artist.showCount, "show"))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                // A star, not a heart — favoriting is local-only, works signed out, and
                // applies to both backends, so it shouldn't look like the phish.in-account
                // "like" it has nothing to do with (matches Android's FavoriteButton).
                Button {
                    favorites.toggle(artist.key)
                } label: {
                    Image(systemName: favorites.keys.contains(artist.key) ? "star.fill" : "star")
                        .foregroundStyle(favorites.keys.contains(artist.key) ? .yellow : .secondary)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func load() async {
        loadState = .loading
        do {
            relistenArtists = try await RelistenCatalogSource.shared.artists()
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load artists: \(error.localizedDescription)")
        }
    }
}

enum LoadState: Equatable {
    case loading
    case loaded
    case failed(String)
}

struct ErrorView: View {
    let message: String
    let retry: () async -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text(message).foregroundStyle(.secondary)
            Button("Retry") { Task { await retry() } }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}
