import CouchTourKit
import SwiftUI

struct ArtistsView: View {
    @State private var artists: [ArtistRef] = []
    @State private var loadState: LoadState = .loading

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                List(artists, id: \.self) { artist in
                    NavigationLink(value: artist) {
                        VStack(alignment: .leading) {
                            Text(artist.name)
                            if artist.showCount > 0 {
                                Text("\(artist.showCount) \(plural(artist.showCount, "show"))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Artists")
        .navigationDestination(for: ArtistRef.self) { PeriodsView(artist: $0) }
        .task { await load() }
    }

    private func load() async {
        loadState = .loading
        do {
            // phish.in has exactly one artist and no fetch; Relisten's list is a real request.
            let relistenArtists = try await RelistenCatalogSource.shared.artists()
            artists = [PHISH] + relistenArtists.sorted { $0.name < $1.name }
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
