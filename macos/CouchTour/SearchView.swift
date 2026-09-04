import CouchTourKit
import SwiftUI

/// Debounced search across every backend. Port of Android's SearchResultsList / searchFor
/// (`MainActivity.kt`).
///
/// The results, only — the field itself is now persistent toolbar chrome (RootView.swift),
/// which supersedes D169's "search is its own sidebar section". D169's real argument was that a
/// search hit should drill into the same destinations browse uses without disturbing browse's
/// own stack; with one stack for the window (D203) that's what pushing a `Route` does anyway,
/// and search stops being a place you have to navigate *to* in order to look something up.
struct SearchView: View {
    @State private var hits: SearchHits?
    @State private var selectedArtistKey: String?
    @State private var selectedTag: String = "All"
    @State private var sortMode: SearchSortMode = .relevance
    @EnvironmentObject private var appModel: AppModel

    private var term: String { appModel.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        Group {
            if term.count < 3 {
                ContentUnavailableView(
                    "Search Couch Tour",
                    systemImage: "magnifyingglass",
                    description: Text("Find an artist, show, song, or venue.")
                )
            } else if let hits {
                resultsList(hits)
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        // Collapses a burst of keystrokes into one request, the same debounce Android's
        // searchFor (produceState, key1 = term) uses. task(id:) cancels the previous run
        // whenever the trimmed term changes.
        .task(id: term) {
            guard term.count >= 3 else {
                hits = nil
                return
            }
            hits = nil
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            hits = await searchAll(term)
        }
    }

    @ViewBuilder
    private func resultsList(_ hits: SearchHits) -> some View {
        let artistsPresent = hits.artistsPresent
        // Becomes nil (falling back to "everything") once the artist it named is no longer
        // among the hits — the same accident-of-key-reuse behavior Android's chip row has.
        let selectedArtist = artistsPresent.first { key(for: $0) == selectedArtistKey }
        let filteredByArtist = hits.filteredTo(selectedArtist)

        let availableTags = tagOptions(for: filteredByArtist)
        // Same fallback as the artist picker above: a tag that's dropped out of the results
        // (a new search, or narrowing to an artist that doesn't carry it) reverts to "All"
        // rather than rendering nothing.
        let effectiveTag = availableTags.contains(selectedTag) ? selectedTag : "All"
        let filtered = filterByTag(filteredByArtist, tagName: effectiveTag)

        VStack(spacing: 0) {
            if artistsPresent.count > 1 {
                Picker("Artist", selection: $selectedArtistKey) {
                    Text("All").tag(String?.none)
                    ForEach(artistsPresent, id: \.self) { artist in
                        Text(artist.name).tag(String?.some(key(for: artist)))
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .padding([.horizontal, .top])
            }

            if availableTags.count > 1 {
                Picker("Tag", selection: Binding(get: { effectiveTag }, set: { selectedTag = $0 })) {
                    ForEach(availableTags, id: \.self) { tag in
                        Text(tag).tag(tag)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
                .padding([.horizontal, .top])
            }

            if filtered.isEmpty {
                ContentUnavailableView(
                    hits.failed.isEmpty ? "Nothing matched" : "Search failed",
                    systemImage: "magnifyingglass",
                    description: Text(hits.failed.isEmpty ? "Try a different search." : failureMessage(hits.failed))
                )
            } else {
                resultsListBody(filtered)
                    // A toolbar control, not a third stacked chip row on top of the artist
                    // and tag pickers above (#91) — only shown when it would do something,
                    // matching Android's `r.shows.isNotEmpty() || r.tracks.isNotEmpty()` guard.
                    .toolbar {
                        if !filtered.shows.isEmpty || !filtered.tracks.isEmpty {
                            ToolbarItem {
                                Picker("Sort", selection: $sortMode) {
                                    ForEach(SearchSortMode.allCases) { mode in
                                        Text(mode.displayName).tag(mode)
                                    }
                                }
                                .pickerStyle(.menu)
                            }
                        }
                    }
            }
        }
    }

    /// The union of tags on the shows and tracks currently in view, sorted by priority
    /// descending then name, "All" prefixed — Android's shape (`MainActivity.kt:1484-1502`).
    private func tagOptions(for hits: SearchHits) -> [String] {
        var seen: [String: Tag] = [:]
        for tag in hits.shows.flatMap(\.tags) + hits.tracks.flatMap(\.tags) {
            seen[tag.name.lowercased()] = tag
        }
        let names = seen.values
            .sorted { lhs, rhs in
                lhs.priority != rhs.priority ? lhs.priority > rhs.priority : lhs.name < rhs.name
            }
            .map(\.name)
        return names.isEmpty ? [] : ["All"] + names
    }

    /// Matches Android's search tag filter: artists and song/venue slices aren't tagged at all,
    /// so picking a real tag narrows to shows and tracks only rather than leaving unrelated
    /// sections showing.
    private func filterByTag(_ hits: SearchHits, tagName: String) -> SearchHits {
        guard tagName.caseInsensitiveCompare("All") != .orderedSame else { return hits }
        return SearchHits(
            shows: hits.shows.filterByTag(tagName),
            tracks: hits.tracks.filter { track in
                track.tags.contains { $0.name.caseInsensitiveCompare(tagName) == .orderedSame }
            },
            failed: hits.failed
        )
    }

    @ViewBuilder
    private func resultsListBody(_ hits: SearchHits) -> some View {
        List {
            if !hits.artists.isEmpty {
                Section("Artists") {
                    ForEach(hits.artists, id: \.self) { artist in
                        NavigationLink(value: Route.artist(artist)) {
                            row(title: artist.name, subtitle: "\(artist.showCount) \(plural(artist.showCount, "show"))")
                        }
                    }
                }
            }
            if !hits.shows.isEmpty {
                Section("Shows") {
                    ForEach(hits.shows.sortedForSearch(by: sortMode), id: \.self) { show in
                        NavigationLink(value: Route.show(show)) {
                            row(title: show.date, subtitle: showSubtitle(show))
                        }
                    }
                }
            }
            ForEach([SliceKind.song, .venue], id: \.self) { kind in
                let slices = hits.slices.filter { $0.kind == kind }
                if !slices.isEmpty {
                    Section(kind.heading) {
                        ForEach(slices, id: \.self) { slice in
                            NavigationLink(value: Route.period(artist: slice.artist, period: slice.period)) {
                                row(
                                    title: slice.period.label,
                                    subtitle: "\(slice.artist.name) · \(slice.period.showCount) \(plural(slice.period.showCount, "show"))"
                                )
                            }
                        }
                    }
                }
            }
            // A track with no show_date can't be opened inside its show, so it's dropped
            // rather than pushing a summary that can't load.
            let openableTracks = hits.tracks.filter { $0.showDate != nil }.sortedForSearch(by: sortMode)
            if !openableTracks.isEmpty {
                Section("Tracks") {
                    ForEach(openableTracks, id: \.id) { track in
                        NavigationLink(value: Route.show(showSummary(for: track))) {
                            row(
                                title: track.title,
                                subtitle: [track.showDate, track.venueName, track.venueLocation].compactMap { $0 }.joined(separator: " · "),
                                trailing: fmt(track.duration)
                            )
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func row(title: String, subtitle: String, trailing: String? = nil) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if !subtitle.isEmpty {
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
            }
            if let trailing {
                Spacer()
                Text(trailing).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private func showSubtitle(_ show: ShowSummary) -> String {
        [show.artist.backend == .phishin ? nil : show.artist.name, show.where_.isEmpty ? nil : show.where_]
            .compactMap { $0 }
            .joined(separator: " · ")
    }

    /// A phish.in track hit opens the show it belongs to — the setlist there is one click
    /// from the track playing — rather than auto-playing or getting its own screen.
    private func showSummary(for track: Track) -> ShowSummary {
        ShowSummary(
            artist: PHISH,
            date: track.showDate ?? "",
            venue: track.venueName,
            location: track.venueLocation,
            artURL: track.showAlbumCoverUrl
        )
    }

    private func key(for artist: ArtistRef) -> String { "\(artist.backend.rawValue)/\(artist.id)" }

    private func failureMessage(_ failed: Set<Backend>) -> String {
        let names = Backend.allCases.filter { failed.contains($0) }.map { $0 == .phishin ? "Phish" : "Relisten" }
        return "Couldn't search " + names.joined(separator: " or ") + "."
    }
}
