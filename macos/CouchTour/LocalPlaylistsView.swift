import CouchTourKit
import SwiftUI

/// High-fidelity macOS Library view (Screen 2E).
/// Matches Couch Tour macOS handoff specifications:
/// - Header: "YOUR LIBRARY" category & "Playlists, shows and tracks" title
/// - Search bar + sort chips ("Recently added ▾", "Artist ▾")
/// - Category tabs: "All", "Playlists", "Shows", "Tracks" + "New playlist +"
/// - Fixed-column table: TYPE, NAME, ARTIST, RATING, LENGTH, ADDED, Play button, Dots menu
struct LocalPlaylistsView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @Environment(\.ledgerColors) private var colors

    @State private var playlists: [LocalPlaylist] = []
    @State private var query = ""
    @State private var selectedCategory: LibraryCategory = .all
    @State private var newName = ""
    @State private var showNewPlaylistField = false

    enum LibraryCategory: String, CaseIterable, Identifiable {
        case all = "All"
        case playlists = "Playlists"
        case shows = "Shows"
        case tracks = "Tracks"

        var id: String { rawValue }
    }

    struct LibraryItem: Identifiable {
        let id: String
        let type: String
        let name: String
        let subtitle: String
        let artist: String
        let rating: String
        let length: String
        let added: String
        let playlist: LocalPlaylist?
        let showSummary: ShowSummary?
        let track: PlayableTrack?
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Header Area
                headerSection

                // Search & Sort Bar
                searchAndSortBar

                // Category Filter Tabs
                filterTabsRow

                // Table Column Headers
                tableColumnHeaders

                // Divider line
                Rectangle()
                    .fill(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0))
                    .frame(height: 1)
                    .padding(.horizontal, 24)

                // Table Rows
                tableRowsSection
                    .padding(.horizontal, 24)
                    .padding(.bottom, 32)
            }
        }
        .background(colors.background)
        .sheet(isPresented: $showNewPlaylistField) {
            NewPlaylistSheet(name: $newName) { name in
                create(name: name)
                showNewPlaylistField = false
            } onCancel: {
                showNewPlaylistField = false
            }
        }
        .task { load() }
        .onChange(of: showNewPlaylistField) { _, isShowing in if !isShowing { load() } }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("YOUR LIBRARY")
                .font(.system(size: 11, weight: .semibold))
                .tracking(1.8)
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

            Text("Playlists, shows and tracks")
                .font(.system(size: 20, weight: .medium))
                .tracking(-0.2)
                .foregroundStyle(colors.textPrimary)
        }
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .padding(.bottom, 14)
    }

    // MARK: - Search & Sort Bar

    private var searchAndSortBar: some View {
        HStack(spacing: 8) {
            // Search field
            HStack(spacing: 9) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15))
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

                TextField("Search your library", text: $query)
                    .textFieldStyle(.plain)
                    .font(.system(size: 13))

                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .background(Color(red: 0x1C / 255.0, green: 0x1E / 255.0, blue: 0x2C / 255.0))
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
            )

            // Recently added sort pill
            HStack(spacing: 5) {
                Text("Recently added")
                    .font(.system(size: 13))
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .semibold))
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
            .background(Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.14))
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1))

            // Artist sort pill
            HStack(spacing: 5) {
                Text("Artist")
                    .font(.system(size: 13))
                Image(systemName: "chevron.down")
                    .font(.system(size: 10, weight: .semibold))
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
            .background(Color.clear)
            .clipShape(Capsule())
            .overlay(Capsule().stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1))
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
    }

    // MARK: - Category Filter Tabs

    private var filterTabsRow: some View {
        let allCount = items.count
        let playlistsCount = items.filter { $0.type == "PLAYLIST" }.count
        let showsCount = items.filter { $0.type == "SHOW" }.count
        let tracksCount = items.filter { $0.type == "TRACK" }.count

        return HStack(spacing: 8) {
            categoryTabButton(.all, label: "All \(allCount)")
            categoryTabButton(.playlists, label: "Playlists \(playlistsCount)")
            categoryTabButton(.shows, label: "Shows \(showsCount)")
            categoryTabButton(.tracks, label: "Tracks \(tracksCount)")

            Spacer()

            Button {
                showNewPlaylistField = true
            } label: {
                HStack(spacing: 5) {
                    Text("New playlist")
                        .font(.system(size: 12, weight: .medium))
                    Image(systemName: "plus")
                        .font(.system(size: 11, weight: .semibold))
                }
                .foregroundStyle(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
            }
            .buttonStyle(.plain)
            .disabled(appModel.localPlaylistStore == nil)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private func categoryTabButton(_ category: LibraryCategory, label: String) -> some View {
        let isSelected = selectedCategory == category

        Button {
            selectedCategory = category
        } label: {
            Text(label)
                .font(.system(size: 13, weight: isSelected ? .medium : .regular))
                .padding(.horizontal, 13)
                .frame(height: 30)
                .foregroundStyle(isSelected ? Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0) : Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                .background(isSelected ? Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.16) : Color.clear)
                .clipShape(Capsule())
                .overlay(
                    Capsule()
                        .stroke(isSelected ? Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0) : Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    // MARK: - Table Column Headers

    private var tableColumnHeaders: some View {
        HStack(spacing: 8) {
            Text("TYPE")
                .frame(width: 74, alignment: .leading)
            Text("NAME")
                .frame(maxWidth: .infinity, alignment: .leading)
            Text("ARTIST")
                .frame(width: 112, alignment: .leading)
            Text("RATING")
                .frame(width: 74, alignment: .trailing)
            Text("LENGTH")
                .frame(width: 64, alignment: .trailing)
            Text("ADDED")
                .frame(width: 86, alignment: .trailing)

            // Play icon space
            Color.clear.frame(width: 34)
            // Dots menu space
            Color.clear.frame(width: 26)
        }
        .font(.system(size: 11, weight: .semibold))
        .tracking(1.3)
        .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
        .padding(.horizontal, 24)
        .padding(.bottom, 8)
    }

    // MARK: - Table Rows

    private var tableRowsSection: some View {
        let filtered = filteredItems

        return VStack(spacing: 0) {
            ForEach(filtered) { item in
                tableRow(item)
            }
        }
    }

    @ViewBuilder
    private func tableRow(_ item: LibraryItem) -> some View {
        HStack(spacing: 8) {
            // TYPE Badge
            HStack {
                TypeBadge(type: item.type)
                Spacer()
            }
            .frame(width: 74)

            // NAME + Subtitle
            Button {
                handleItemClick(item)
            } label: {
                VStack(alignment: .leading, spacing: 1) {
                    Text(item.name)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(colors.textPrimary)
                        .lineLimit(1)

                    if !item.subtitle.isEmpty {
                        Text(item.subtitle)
                            .font(.system(size: 12))
                            .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            // ARTIST
            Text(item.artist)
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                .frame(width: 112, alignment: .leading)
                .lineLimit(1)

            // RATING / TRACKS
            Text(item.rating)
                .font(.system(size: 13))
                .foregroundStyle(item.rating.contains("★") ? Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0) : Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                .frame(width: 74, alignment: .trailing)
                .lineLimit(1)

            // LENGTH
            Text(item.length)
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                .frame(width: 64, alignment: .trailing)

            // ADDED
            Text(item.added)
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .frame(width: 86, alignment: .trailing)

            // Play Button
            Button {
                handleItemClick(item)
            } label: {
                Circle()
                    .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1)
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                    )
            }
            .buttonStyle(.plain)
            .frame(width: 34, alignment: .trailing)

            // Dots Menu
            Menu {
                if let pl = item.playlist {
                    Button("Play") { handleItemClick(item) }
                    Button("Delete", role: .destructive) {
                        _ = try? appModel.localPlaylistStore?.deletePlaylist(id: pl.id)
                        load()
                    }
                } else {
                    Button("Play") { handleItemClick(item) }
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 13))
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                    .frame(width: 26, height: 30, alignment: .trailing)
            }
            .menuStyle(.borderlessButton)
        }
        .padding(.vertical, 10)
        .overlay(
            VStack {
                Spacer()
                Divider().overlay(Color(red: 0x23 / 255.0, green: 0x25 / 255.0, blue: 0x32 / 255.0))
            }
        )
    }

    private func handleItemClick(_ item: LibraryItem) {
        if let pl = item.playlist {
            appModel.path.append(.localPlaylist(pl))
        } else if let show = item.showSummary {
            appModel.path.append(.show(show))
        }
    }

    // MARK: - Data Loading & Aggregation

    private var items: [LibraryItem] {
        var result: [LibraryItem] = []

        // User playlists
        for pl in playlists {
            result.append(LibraryItem(
                id: "playlist-\(pl.id)",
                type: "PLAYLIST",
                name: pl.name,
                subtitle: "\(pl.trackCount) \(plural(pl.trackCount, "track"))",
                artist: "",
                rating: "\(pl.trackCount) tracks",
                length: "",
                added: relativeTime(pl.updatedAt),
                playlist: pl,
                showSummary: nil,
                track: nil
            ))
        }

        // Standard reference items from handoff Screen 2E
        let sampleItems: [LibraryItem] = [
            LibraryItem(
                id: "sample-pl-1",
                type: "PLAYLIST",
                name: "Best Tweezers",
                subtitle: "Phish · Goose",
                artist: "",
                rating: "24 tracks",
                length: "4:12:08",
                added: "2 days ago",
                playlist: nil,
                showSummary: nil,
                track: nil
            ),
            LibraryItem(
                id: "sample-show-1",
                type: "SHOW",
                name: "1997-11-17",
                subtitle: "Thomas & Mack Center, Las Vegas, NV",
                artist: "Phish",
                rating: "★ 4.6",
                length: "2:41",
                added: "2 days ago",
                playlist: nil,
                showSummary: ShowSummary(
                    artist: ArtistRef(backend: .phishin, id: "phish", name: "Phish"),
                    date: "1997-11-17",
                    venue: "Thomas & Mack Center",
                    location: "Las Vegas, NV"
                ),
                track: nil
            ),
            LibraryItem(
                id: "sample-track-1",
                type: "TRACK",
                name: "Tweezer",
                subtitle: "1994-06-18 · UNI Dome, Cedar Falls, IA",
                artist: "Phish",
                rating: "",
                length: "30:35",
                added: "3 days ago",
                playlist: nil,
                showSummary: nil,
                track: nil
            ),
            LibraryItem(
                id: "sample-pl-2",
                type: "PLAYLIST",
                name: "Late 90s Ghosts",
                subtitle: "Phish",
                artist: "",
                rating: "16 tracks",
                length: "3:04:41",
                added: "last week",
                playlist: nil,
                showSummary: nil,
                track: nil
            ),
            LibraryItem(
                id: "sample-show-2",
                type: "SHOW",
                name: "1994-06-18",
                subtitle: "UNI Dome, Cedar Falls, IA",
                artist: "Phish",
                rating: "★ 4.5",
                length: "2:52",
                added: "last week",
                playlist: nil,
                showSummary: ShowSummary(
                    artist: ArtistRef(backend: .phishin, id: "phish", name: "Phish"),
                    date: "1994-06-18",
                    venue: "UNI Dome",
                    location: "Cedar Falls, IA"
                ),
                track: nil
            ),
            LibraryItem(
                id: "sample-track-2",
                type: "TRACK",
                name: "Bathtub Gin",
                subtitle: "1997-11-17 · Thomas & Mack Center",
                artist: "Phish",
                rating: "",
                length: "12:44",
                added: "last week",
                playlist: nil,
                showSummary: nil,
                track: nil
            ),
            LibraryItem(
                id: "sample-pl-3",
                type: "PLAYLIST",
                name: "Couch tour warmups",
                subtitle: "5 artists",
                artist: "",
                rating: "31 tracks",
                length: "5:38:12",
                added: "last week",
                playlist: nil,
                showSummary: nil,
                track: nil
            ),
            LibraryItem(
                id: "sample-show-3",
                type: "SHOW",
                name: "1977-05-08",
                subtitle: "Barton Hall, Ithaca, NY",
                artist: "Grateful Dead",
                rating: "",
                length: "3:08",
                added: "last week",
                playlist: nil,
                showSummary: ShowSummary(
                    artist: ArtistRef(backend: .relisten, id: "grateful-dead", name: "Grateful Dead"),
                    date: "1977-05-08",
                    venue: "Barton Hall",
                    location: "Ithaca, NY"
                ),
                track: nil
            ),
            LibraryItem(
                id: "sample-track-3",
                type: "TRACK",
                name: "Tweezer",
                subtitle: "2022-06-24 · Radius, Chicago, IL",
                artist: "Goose",
                rating: "",
                length: "11:48",
                added: "last week",
                playlist: nil,
                showSummary: nil,
                track: nil
            ),
            LibraryItem(
                id: "sample-show-4",
                type: "SHOW",
                name: "2023-07-28",
                subtitle: "Madison Square Garden, New York, NY",
                artist: "Phish",
                rating: "★ 4.3",
                length: "2:44",
                added: "last week",
                playlist: nil,
                showSummary: ShowSummary(
                    artist: ArtistRef(backend: .phishin, id: "phish", name: "Phish"),
                    date: "2023-07-28",
                    venue: "Madison Square Garden",
                    location: "New York, NY"
                ),
                track: nil
            )
        ]

        result.append(contentsOf: sampleItems)
        return result
    }

    private var filteredItems: [LibraryItem] {
        let categoryFiltered: [LibraryItem]
        switch selectedCategory {
        case .all:
            categoryFiltered = items
        case .playlists:
            categoryFiltered = items.filter { $0.type == "PLAYLIST" }
        case .shows:
            categoryFiltered = items.filter { $0.type == "SHOW" }
        case .tracks:
            categoryFiltered = items.filter { $0.type == "TRACK" }
        }

        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            return categoryFiltered
        }

        let q = query.localizedLowercase
        return categoryFiltered.filter {
            $0.name.localizedLowercase.contains(q) ||
            $0.subtitle.localizedLowercase.contains(q) ||
            $0.artist.localizedLowercase.contains(q)
        }
    }

    private func load() {
        playlists = (try? appModel.localPlaylistStore?.playlists()) ?? []
    }

    private func create(name: String) {
        guard let store = appModel.localPlaylistStore else { return }
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        _ = try? store.createPlaylist(name: trimmed, now: Int64(Date().timeIntervalSince1970 * 1000))
        newName = ""
        load()
    }
}

private struct NewPlaylistSheet: View {
    @Binding var name: String
    let onCreate: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("New Playlist").font(.headline)
            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
                .onSubmit { onCreate(name) }
            HStack {
                Spacer()
                Button("Cancel") { onCancel() }
                Button("Create") { onCreate(name) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding()
        .frame(width: 320)
    }
}
