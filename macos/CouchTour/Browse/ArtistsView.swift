import CouchTourKit
import SwiftUI

struct ArtistsView: View {
    @EnvironmentObject private var favorites: Favorites
    @State private var relistenArtists: [ArtistRef] = []
    @State private var loadState: LoadState = .loading

    private var merged: [ArtistRef] {
        mergeArtists(relistenArtists: relistenArtists, favorites: favorites.keys)
    }

    private var favoritedArtists: [ArtistRef] {
        merged.filter { favorites.keys.contains($0.key) }
    }

    var body: some View {
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
        .task { await load() }
    }

    @ViewBuilder
    private func artistRow(_ artist: ArtistRef) -> some View {
        NavigationLink(value: Route.artist(artist)) {
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
                .accessibilityLabel(
                    favorites.keys.contains(artist.key)
                        ? "Remove \(artist.name) from favorites"
                        : "Add \(artist.name) to favorites"
                )
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
