import CouchTourKit
import SwiftUI

struct ArtistsView: View {
    @EnvironmentObject private var favorites: Favorites
    @State private var relistenArtists: [ArtistRef] = []
    @State private var loadState: LoadState = .loading
    @State private var sortMode: ArtistSortMode = .popular
    @State private var query: String = ""

    /// The phish-pinned / favorited / everyone-else split (#116) — kept apart, rather than
    /// flattened via `mergeArtists`, so favorites can render as their own pinned section and
    /// each group can be filtered and sorted independently while Phish, whose position-1 slot
    /// predates favoriting, stays outside either.
    private var groups: ArtistGroups {
        groupArtistsForBrowse(relistenArtists: relistenArtists, favorites: favorites.keys)
    }

    private var phishMatch: ArtistRef? {
        guard let phish = groups.phish else { return nil }
        return [phish].filterByName(query).first
    }

    // Favorites stay pinned regardless of sort — only the filter narrows this section — but
    // within the section itself sortMode still applies, matching Batch 2A's Android rule.
    private var favoritedArtists: [ArtistRef] {
        groups.favorited.filterByName(query).sorted(by: sortMode)
    }

    private var otherArtists: [ArtistRef] {
        groups.others.filterByName(query).sorted(by: sortMode)
    }

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                if phishMatch == nil && favoritedArtists.isEmpty && otherArtists.isEmpty {
                    ContentUnavailableView(
                        "No artists match \"\(query)\"",
                        systemImage: "magnifyingglass",
                        description: Text("Try a different name.")
                    )
                } else {
                    List {
                        if let phishMatch {
                            artistRow(phishMatch)
                        }
                        // Separate from "Artists" below (Android parity, #56): quick access
                        // to a handful of artists without scrolling. Sort applies within the
                        // section, but favorites never move out of it regardless of mode.
                        if !favoritedArtists.isEmpty {
                            Section("Favorites") {
                                ForEach(favoritedArtists, id: \.self) { artist in
                                    artistRow(artist)
                                }
                            }
                        }
                        Section("Artists") {
                            ForEach(otherArtists, id: \.self) { artist in
                                artistRow(artist)
                            }
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "Filter artists…")
        .toolbar {
            ToolbarItem {
                Picker("Sort", selection: $sortMode) {
                    ForEach(ArtistSortMode.allCases) { mode in
                        Text(mode.displayName).tag(mode)
                    }
                }
                .pickerStyle(.menu)
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
