import CouchTourKit
import SwiftUI

struct ShowsView: View {
    let artist: ArtistRef
    let period: PeriodRef

    @State private var shows: [ShowSummary] = []
    @State private var loadState: LoadState = .loading
    @State private var sortOption: ShowSortOption = .dateDesc
    @State private var selectedTag: String = "All"

    /// Built from the tags actually present in this period's shows, sorted by priority
    /// descending then name, "All" prefixed — Android's shape (`MainActivity.kt:837-846`).
    private var availableTags: [String] {
        var seen: [String: Tag] = [:]
        for tag in shows.flatMap(\.tags) {
            seen[tag.name.lowercased()] = tag
        }
        let names = seen.values
            .sorted { lhs, rhs in
                lhs.priority != rhs.priority ? lhs.priority > rhs.priority : lhs.name < rhs.name
            }
            .map(\.name)
        return names.isEmpty ? [] : ["All"] + names
    }

    /// Falls back to "All" once the tag it names is no longer among this period's shows,
    /// rather than silently rendering nothing.
    private var effectiveTag: String {
        availableTags.contains(selectedTag) ? selectedTag : "All"
    }

    /// Sort is view state applied to what's already loaded, not a refetch — `load()` resets
    /// `loadState` wholesale, so routing sort/filter through it would hit the network on
    /// every change.
    private var displayedShows: [ShowSummary] {
        sortShows(shows.filterByTag(effectiveTag), by: sortOption)
    }

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                VStack(spacing: 0) {
                    if availableTags.count > 1 {
                        tagPicker
                    }

                    if !shows.isEmpty && displayedShows.isEmpty {
                        ContentUnavailableView(
                            "No shows tagged \"\(effectiveTag)\"",
                            systemImage: "tag",
                            description: Text("Try a different tag.")
                        )
                    } else {
                        List(displayedShows, id: \.self) { show in
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
            }
        }
        // A toolbar Picker, not Android's chip row — ShowsView already sits inside a window
        // whose toolbar carries navigation chrome (RootView.swift), and a menu-style Picker is
        // the idiom the rest of this app already uses for inline filters (SearchView's artist
        // picker, ListeningView's scope/artist pickers) rather than introducing a second one.
        .toolbar {
            ToolbarItem {
                Picker("Sort", selection: $sortOption) {
                    ForEach(ShowSortOption.allCases) { option in
                        Text(option.displayName).tag(option)
                    }
                }
                .pickerStyle(.menu)
            }
        }
        .task { await load() }
    }

    private var tagPicker: some View {
        Picker("Tag", selection: Binding(get: { effectiveTag }, set: { selectedTag = $0 })) {
            ForEach(availableTags, id: \.self) { tag in
                Text(tag).tag(tag)
            }
        }
        .pickerStyle(.menu)
        .labelsHidden()
        .padding([.horizontal, .top])
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
