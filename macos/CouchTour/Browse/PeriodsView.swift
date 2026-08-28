import CouchTourKit
import SwiftUI

struct PeriodsView: View {
    let artist: ArtistRef

    @State private var periods: [PeriodRef] = []
    @State private var loadState: LoadState = .loading

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                List(periods, id: \.self) { period in
                    NavigationLink(value: Route.period(artist: artist, period: period)) {
                        VStack(alignment: .leading) {
                            Text(period.label)
                            if period.showCount > 0 {
                                Text("\(period.showCount) \(plural(period.showCount, "show"))")
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
            periods = try await sourceFor(artist.backend).periods(artist: artist)
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load \(artist.name)'s years: \(error.localizedDescription)")
        }
    }
}
