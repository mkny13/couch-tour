import CouchTourKit
import SwiftUI

/// Deterministic in-app surface for XCUITest coverage of macOS UI flows that are otherwise
/// network/database-backed in normal runs.
struct UITestHarnessView: View {
    @State private var sortOption: ShowSortOption = .dateDesc
    @State private var selectedTag: String = "All"
    @State private var contextAction = "None"

    /// Isolated defaults so the seeded like never touches the real library.
    @StateObject private var harnessLikes: LikedTracks = {
        let defaults = UserDefaults(suiteName: "dev.mike.couchtour.uitest-harness")!
        defaults.removePersistentDomain(forName: "dev.mike.couchtour.uitest-harness")
        let likes = LikedTracks(defaults: defaults)
        likes.toggle(LikedTrackRecord(
            trackId: "uih-liked-1", backend: "relisten", showDate: "1977-05-08",
            artistSlug: "grateful-dead", recordingId: "gd77-05-08", title: "Harness Liked Track",
            durationMs: 300_000, likedAt: 1))
        return likes
    }()

    private let sampleArtist = ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead")
    private let sampleShows: [ShowSummary] = [
        ShowSummary(
            artist: ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead"),
            date: "1998-12-31",
            venue: "Madison Square Garden",
            location: "New York, NY",
            rating: 4.9,
            tags: [Tag(name: "jam", priority: 10), Tag(name: "sbd", priority: 7)]
        ),
        ShowSummary(
            artist: ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead"),
            date: "1997-11-22",
            venue: "Hampton Coliseum",
            location: "Hampton, VA",
            rating: 4.2,
            tags: [Tag(name: "aud", priority: 6)]
        ),
    ]

    private var availableTags: [String] {
        let names = Set(sampleShows.flatMap(\.tags).map(\.name)).sorted()
        return ["All"] + names
    }

    private var filteredShows: [ShowSummary] {
        let base = selectedTag == "All"
            ? sampleShows
            : sampleShows.filter { show in show.tags.contains(where: { $0.name == selectedTag }) }
        return sortShows(base, by: sortOption)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("UI Test Harness")
                    .font(.title2.weight(.semibold))
                    .accessibilityIdentifier("uih.title")

                HStack(spacing: 16) {
                    Picker("Sort", selection: $sortOption) {
                        ForEach(ShowSortOption.allCases) { option in
                            Text(option.displayName).tag(option)
                        }
                    }
                    .pickerStyle(.menu)
                    .accessibilityIdentifier("uih.sort")

                    Picker("Tag", selection: $selectedTag) {
                        ForEach(availableTags, id: \.self) { tag in
                            Text(tag).tag(tag)
                        }
                    }
                    .pickerStyle(.menu)
                    .accessibilityIdentifier("uih.tag")
                }

                List(filteredShows, id: \.self) { show in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(show.date)
                            .font(.headline)
                        Text(show.where_)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        HStack(spacing: 6) {
                            ForEach(show.tags, id: \.name) { tag in
                                TagBadge(tag)
                            }
                        }
                    }
                    .contextMenu {
                        Button("Open Show") { contextAction = "open" }
                        Button("Mark Completed") { contextAction = "completed" }
                        Button("Remove from List", role: .destructive) { contextAction = "removed" }
                    }
                    .accessibilityIdentifier("uih.show.\(show.date)")
                }
                .accessibilityIdentifier("uih.showList")

                Text("Context action: \(contextAction)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("uih.contextAction")

                Divider()

                Text("Artwork/date badge")
                    .font(.headline)

                HStack(spacing: 24) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Large artwork")
                            .font(.caption)
                        ArtworkView(
                            url: nil,
                            artist: sampleArtist.name,
                            date: "1980-01-02",
                            size: 120
                        )
                        .accessibilityIdentifier("uih.artwork.large")
                    }
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Small artwork")
                            .font(.caption)
                        ArtworkView(
                            url: nil,
                            artist: sampleArtist.name,
                            date: "1980-01-02",
                            size: 36
                        )
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Small artwork")
                        .accessibilityIdentifier("uih.artwork.small")
                    }
                }

                Divider()

                // Library with one liked track whose resolution returns no tracks (#545).
                LocalPlaylistsView(resolveTracks: { _ in [] })
                    .environmentObject(harnessLikes)
                    .frame(height: 320)
                    .accessibilityIdentifier("uih.library")
            }
            .padding(20)
        }
    }
}
