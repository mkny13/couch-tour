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
    @EnvironmentObject private var likedTracks: LikedTracks
    @EnvironmentObject private var session: PhishInSession
    @Environment(\.ledgerColors) private var colors

    /// Injectable so the UI-test harness can force a resolution failure without the network.
    var resolveTracks: ([LocalPlaylistTrack]) async -> [PlayableTrack] = resolveLocalPlaylistTracks

    @State private var playlists: [LocalPlaylist] = []
    @State private var hasPlaybackHistory = false
    @State private var playError: String?
    @State private var account = LibraryAccountData.empty
    @State private var playlistTracks: [LocalPlaylistTrack] = []
    @State private var query = ""
    @State private var selectedCategory: LibraryCategory = .all
    @State private var librarySort: LibrarySort = .recentlyAdded
    @State private var newName = ""
    @State private var showNewPlaylistField = false

    enum LibrarySort: String, CaseIterable, Identifiable {
        case recentlyAdded = "Recently added"
        case title = "Title"
        case artist = "Artist"

        var id: String { rawValue }
    }

    enum LibraryCategory: String, CaseIterable, Identifiable {
        case all = "All"
        case playlists = "Playlists"
        case shows = "Shows"
        case tracks = "Tracks"

        var id: String { rawValue }
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
                    .fill(colors.divider)
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
        // Re-fetch live on entry and whenever the shared session signs in/out; signed out
        // clears account rows without a request.
        .task(id: session.username) {
            account = .empty
            let loaded = await LibraryAccountData.load(signedIn: session.username != nil)
            if !Task.isCancelled { account = loaded }
        }
        .onChange(of: showNewPlaylistField) { _, isShowing in if !isShowing { load() } }
    }

    // MARK: - Header

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("YOUR LIBRARY")
                .font(.system(size: 11, weight: .semibold))
                .tracking(1.8)
                .foregroundStyle(colors.textMuted)

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
                    .foregroundStyle(colors.textMuted)

                TextField("Search your library", text: $query)
                    .textFieldStyle(.plain)
                    .font(.system(size: 13))
                    .foregroundStyle(colors.textPrimary)

                if !query.isEmpty {
                    Button {
                        query = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundStyle(colors.textMuted)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 12)
            .frame(height: 34)
            .background(colors.surface)
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(colors.panelBorder, lineWidth: 1)
            )

            // Sort menu pill
            Menu {
                ForEach(LibrarySort.allCases) { sort in
                    Button {
                        librarySort = sort
                    } label: {
                        HStack {
                            Text(sort.rawValue)
                            if librarySort == sort {
                                Image(systemName: "checkmark")
                            }
                        }
                    }
                }
            } label: {
                HStack(spacing: 5) {
                    Text(librarySort.rawValue)
                        .font(.system(size: 13))
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                }
                .padding(.horizontal, 12)
                .frame(height: 34)
                .foregroundStyle(colors.accentTintText)
                .background(colors.accentTintText.opacity(colors.isDark ? 0.14 : 0.10))
                .clipShape(Capsule())
                .overlay(Capsule().stroke(colors.accentIcon, lineWidth: 1))
            }
            .menuStyle(.borderlessButton)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
    }

    // MARK: - Category Filter Tabs

    private var filterTabsRow: some View {
        let allCount = items.count
        let playlistsCount = items.filter { $0.kind == .playlist }.count
        let showsCount = items.filter { $0.kind == .show }.count
        let tracksCount = items.filter { $0.kind == .track }.count

        return HStack(spacing: 8) {
            categoryTabButton(.all, label: "All \(allCount)")
            categoryTabButton(.playlists, label: "Playlists \(playlistsCount)")
            categoryTabButton(.shows, label: "Shows \(showsCount)")
            categoryTabButton(.tracks, label: "Tracks \(tracksCount)")

            Spacer()

            Button {
                appModel.path.append(.publicPlaylists)
            } label: {
                Text("Browse public playlists")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(colors.accentTintText)
            }
            .buttonStyle(.plain)
            .padding(.trailing, 12)

            Button {
                showNewPlaylistField = true
            } label: {
                HStack(spacing: 5) {
                    Text("New playlist")
                        .font(.system(size: 12, weight: .medium))
                    Image(systemName: "plus")
                        .font(.system(size: 11, weight: .semibold))
                }
                .foregroundStyle(colors.accentTintText)
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
                .foregroundStyle(isSelected ? colors.accentTintText : colors.textSubtle)
                .background(isSelected ? (colors.isDark ? colors.accent.opacity(0.16) : colors.accentTintText.opacity(0.12)) : Color.clear)
                .clipShape(Capsule())
                .overlay(
                    Capsule()
                        .stroke(isSelected ? colors.accentIcon : colors.controlOutline, lineWidth: 1)
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
        .foregroundStyle(colors.textMuted)
        .padding(.horizontal, 24)
        .padding(.bottom, 8)
    }

    // MARK: - Table Rows

    private var tableRowsSection: some View {
        let filtered = filteredItems

        return VStack(spacing: 0) {
            // Outside emptyState so a failed play stays visible while library rows remain.
            if let playError {
                Text(playError)
                    .font(.system(size: 12))
                    .foregroundStyle(colors.textMuted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 8)
                    .accessibilityIdentifier(AXIdentifiers.libraryPlayError)
            }
            ForEach(filtered) { item in
                tableRow(item)
            }
            if filtered.isEmpty { emptyState }
        }
    }

    @ViewBuilder
    private var emptyState: some View {
        let searching = !query.trimmingCharacters(in: .whitespaces).isEmpty
        VStack(spacing: 8) {
            Text(emptyMessage(searching: searching))
                .font(.system(size: 13))
                .foregroundStyle(colors.textMuted)
                .multilineTextAlignment(.center)
            if !searching, selectedCategory == .shows || selectedCategory == .all, hasPlaybackHistory {
                Button("Shows you've played are in History") {
                    appModel.path.append(.listening)
                }
                .buttonStyle(.plain)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(colors.accentTintText)
                .accessibilityIdentifier(AXIdentifiers.libraryHistoryLink)
            }
            if account.failed {
                Text("Couldn't load your phish.in account content. Local items are shown.")
                    .font(.system(size: 12)).foregroundStyle(colors.textMuted)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 28)
    }

    private func emptyMessage(searching: Bool) -> String {
        if searching { return "Nothing in your library matches \"\(query)\"." }
        switch selectedCategory {
        case .shows: return "No saved shows yet."
        case .tracks: return "No liked tracks yet. Tap the heart on a Relisten track to save it here."
        case .playlists: return "No playlists yet."
        case .all: return "Your library is empty."
        }
    }

    @ViewBuilder
    private func tableRow(_ item: LibrarySourceItem) -> some View {
        HStack(spacing: 8) {
            // TYPE Badge
            HStack {
                TypeBadge(type: item.kind.rawValue)
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
                        .accessibilityIdentifier("\(AXIdentifiers.libraryRow).\(item.id)")

                    if item.kind != .playlist, !item.subtitle.isEmpty {
                        Text(item.subtitle)
                            .font(.system(size: 12))
                            .foregroundStyle(colors.textMuted)
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
                .foregroundStyle(colors.textSubtle)
                .frame(width: 112, alignment: .leading)
                .lineLimit(1)

            // RATING / TRACKS
            Text(item.kind == .playlist ? item.subtitle : "")
                .font(.system(size: 13))
                .foregroundStyle(colors.textSubtle)
                .frame(width: 74, alignment: .trailing)
                .lineLimit(1)

            // LENGTH
            Text(lengthText(for: item))
                .font(.system(size: 13))
                .foregroundStyle(colors.textSubtle)
                .frame(width: 64, alignment: .trailing)
                .lineLimit(1)

            // ADDED
            Text(item.addedAt.map { relativeTime($0) } ?? "")
                .font(.system(size: 13))
                .foregroundStyle(colors.textMuted)
                .frame(width: 86, alignment: .trailing)

            // Play Button
            Button {
                handleItemClick(item)
            } label: {
                Circle()
                    .stroke(colors.accentIcon, lineWidth: 1)
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(colors.accentTintText)
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
                    .foregroundStyle(colors.textMuted)
                    .frame(width: 26, height: 30, alignment: .trailing)
            }
            .menuStyle(.borderlessButton)
        }
        .padding(.vertical, 10)
        .overlay(
            VStack {
                Spacer()
                Divider().overlay(colors.divider)
            }
        )
    }

    private func handleItemClick(_ item: LibrarySourceItem) {
        if let pl = item.playlist {
            appModel.path.append(.localPlaylist(pl))
        } else if let pl = item.accountPlaylist {
            appModel.path.append(.publicPlaylist(pl))
        } else if let show = item.accountShow {
            appModel.path.append(.show(show))
        } else if let track = item.accountTrack {
            play(accountTrack: track)
        } else if let record = item.likedTrack {
            Task { await play(record) }
        }
    }

    /// A liked Relisten track plays through the same fetch-and-resolve path as playlist rows,
    /// wrapped as a one-track queue keyed by the track so History can reopen it.
    private func play(_ record: LikedTrackRecord) async {
        let tracks = await resolveTracks([record.asPlaylistTrack])
        guard !tracks.isEmpty else {
            playError = "Couldn't load \"\(record.title)\" right now."
            return
        }
        playError = nil
        let artist = ArtistRef(backend: .relisten, id: record.artistSlug ?? "", name: record.artistSlug ?? "Relisten")
        let summary = ShowSummary(artist: artist, date: record.showDate)
        let detail = ShowDetail(summary: summary, tracks: tracks, queueKey: "liked:\(record.trackId)")
        player.play(detail: detail, startIndex: 0)
    }

    /// An account-liked phish.in track already carries its mp3 URL, so it plays as a one-track
    /// queue without a further fetch.
    private func play(accountTrack track: Track) {
        guard track.playable else {
            playError = "\"\(track.title)\" has no audio right now."
            return
        }
        playError = nil
        let summary = ShowSummary(artist: PHISH, date: track.showDate ?? "", venue: track.venueName)
        let detail = ShowDetail(
            summary: summary, tracks: [track.toPlayableTrack(showArt: track.showAlbumCoverUrl)],
            queueKey: "liked:phishin-\(track.id)")
        player.play(detail: detail, startIndex: 0)
    }

    private func lengthText(for item: LibrarySourceItem) -> String {
        if let t = item.accountTrack { return formatCompactDuration(ms: t.duration) }
        return item.likedTrack.map { formatCompactDuration(ms: $0.durationMs) } ?? ""
    }

    // MARK: - Data Loading & Aggregation

    /// Saved local sources only: playlists, their tracks, and locally liked Relisten tracks.
    /// Playback history lives in `ListeningView` and is deliberately not read here (#539).
    private var items: [LibrarySourceItem] {
        LibrarySources.items(
            playlists: playlists, playlistTracks: playlistTracks, likedTracks: likedTracks.listableRecords,
            account: account
        )
    }

    private var filteredItems: [LibrarySourceItem] {
        let categoryFiltered: [LibrarySourceItem]
        switch selectedCategory {
        case .all:
            categoryFiltered = items
        case .playlists:
            categoryFiltered = items.filter { $0.kind == .playlist }
        case .shows:
            categoryFiltered = items.filter { $0.kind == .show }
        case .tracks:
            categoryFiltered = items.filter { $0.kind == .track }
        }

        let sorted = LibrarySources.sorted(categoryFiltered, by: {
            switch librarySort {
            case .recentlyAdded: return .recentlyAdded
            case .title: return .title
            case .artist: return .artist
            }
        }())

        if query.trimmingCharacters(in: .whitespaces).isEmpty {
            return sorted
        }

        let q = query.localizedLowercase
        return sorted.filter {
            $0.name.localizedLowercase.contains(q) ||
            $0.subtitle.localizedLowercase.contains(q) ||
            $0.artist.localizedLowercase.contains(q)
        }
    }

    private func load() {
        playlists = (try? appModel.localPlaylistStore?.playlists()) ?? []
        hasPlaybackHistory = ((try? appModel.progressStore?.history().count) ?? 0) > 0
        var allTr: [LocalPlaylistTrack] = []
        for pl in playlists {
            if let trs = try? appModel.localPlaylistStore?.tracks(playlistId: pl.id) {
                allTr.append(contentsOf: trs)
            }
        }
        playlistTracks = allTr
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
