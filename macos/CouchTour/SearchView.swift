import CouchTourKit
import SwiftUI

/// Debounced search across every backend, in its own sidebar section (D169) rather than a
/// toolbar `.searchable` over browse or a ⌘F sheet — a search hit drills into the same
/// PeriodsView/ShowsView/ShowDetailView destinations browse already has, without disturbing
/// Artists' own drill-down state. Port of Android's SearchResultsList / searchFor
/// (`MainActivity.kt`).
struct SearchView: View {
    @State private var query = ""
    @State private var hits: SearchHits?
    @State private var selectedArtistKey: String?
    @FocusState private var isFocused: Bool
    @EnvironmentObject private var appModel: AppModel

    private var term: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

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
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Artists, songs, venues, dates…")
        // .searchFocused needs macOS 15; this project's deployment target is 14 (see
        // project.yml), so the actual focus-on-⌘F behavior is gated inside the modifier —
        // on 14 the field still gets shown (via appModel.selection = .search), just not
        // auto-focused.
        .modifier(FocusOnRequest(appModel: appModel, isFocused: $isFocused))
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
        let filtered = hits.filteredTo(selectedArtist)

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

            if filtered.isEmpty {
                ContentUnavailableView(
                    hits.failed.isEmpty ? "Nothing matched" : "Search failed",
                    systemImage: "magnifyingglass",
                    description: Text(hits.failed.isEmpty ? "Try a different search." : failureMessage(hits.failed))
                )
            } else {
                resultsListBody(filtered)
            }
        }
        .navigationDestination(for: ArtistRef.self) { PeriodsView(artist: $0) }
        .navigationDestination(for: SliceHit.self) { ShowsView(artist: $0.artist, period: $0.period) }
        // Not ShowSummary directly: ShowsView (reached via a SliceHit push, above) already
        // owns that type's destination for its own list, and SwiftUI only supports one
        // navigationDestination per data type per NavigationStack — a second declaration
        // here would silently lose one of them. SearchDestination is this stack's own type
        // for the hits it links to directly (show and track rows).
        .navigationDestination(for: SearchDestination.self) { ShowDetailView(show: $0.show) }
    }

    @ViewBuilder
    private func resultsListBody(_ hits: SearchHits) -> some View {
        List {
            if !hits.artists.isEmpty {
                Section("Artists") {
                    ForEach(hits.artists, id: \.self) { artist in
                        NavigationLink(value: artist) {
                            row(title: artist.name, subtitle: "\(artist.showCount) \(plural(artist.showCount, "show"))")
                        }
                    }
                }
            }
            if !hits.shows.isEmpty {
                Section("Shows") {
                    ForEach(hits.shows, id: \.self) { show in
                        NavigationLink(value: SearchDestination(show: show)) {
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
                            NavigationLink(value: slice) {
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
            let openableTracks = hits.tracks.filter { $0.showDate != nil }
            if !openableTracks.isEmpty {
                Section("Tracks") {
                    ForEach(openableTracks, id: \.id) { track in
                        NavigationLink(value: SearchDestination(show: showSummary(for: track))) {
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

/// This stack's own identity for a show hit search links to directly, so it doesn't share
/// `ShowSummary`'s `navigationDestination` with `ShowsView` (see the comment above).
private struct SearchDestination: Hashable {
    let show: ShowSummary
}

/// Applies `.searchFocused` only where it's available (macOS 15+); CouchTourApp's ⌘F command
/// still switches to the Search section either way via `appModel.selection`, this only adds
/// the auto-focus.
private struct FocusOnRequest: ViewModifier {
    let appModel: AppModel
    var isFocused: FocusState<Bool>.Binding

    func body(content: Content) -> some View {
        if #available(macOS 15, *) {
            content
                .searchFocused(isFocused)
                .onChange(of: appModel.focusSearchField) { _, shouldFocus in
                    guard shouldFocus else { return }
                    isFocused.wrappedValue = true
                    appModel.focusSearchField = false
                }
        } else {
            content
                .onChange(of: appModel.focusSearchField) { _, shouldFocus in
                    guard shouldFocus else { return }
                    appModel.focusSearchField = false
                }
        }
    }
}
