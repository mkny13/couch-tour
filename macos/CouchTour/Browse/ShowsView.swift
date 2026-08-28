import CouchTourKit
import SwiftUI

struct ShowsView: View {
    let artist: ArtistRef
    let period: PeriodRef

    @State private var shows: [ShowSummary] = []
    @State private var loadState: LoadState = .loading

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                List(shows, id: \.self) { show in
                    NavigationLink(value: Route.show(show)) {
                        VStack(alignment: .leading) {
                            HStack {
                                Text(show.date)
                                if show.partial {
                                    Text("partial")
                                        .font(.caption2)
                                        .padding(.horizontal, 6)
                                        .background(.orange.opacity(0.2), in: Capsule())
                                }
                            }
                            if !show.where_.isEmpty {
                                Text(show.where_)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .task { await load() }
    }

    private func load() async {
        loadState = .loading
        do {
            shows = try await sourceFor(artist.backend).shows(artist: artist, period: period)
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load shows: \(error.localizedDescription)")
        }
    }
}
