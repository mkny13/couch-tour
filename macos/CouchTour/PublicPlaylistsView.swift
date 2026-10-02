import CouchTourKit
import SwiftUI

/// phish.in's public community playlists (#428): browse the list, open one, play it.
/// Read-only — they're someone else's lists, unlike `LocalPlaylistsView`.
struct PublicPlaylistsView: View {
    @EnvironmentObject private var appModel: AppModel

    @State private var playlists: [PublicPlaylistSummary] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var query = ""

    private var filtered: [PublicPlaylistSummary] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return playlists }
        return playlists.filter {
            $0.name.localizedCaseInsensitiveContains(q) || ($0.author ?? "").localizedCaseInsensitiveContains(q)
        }
    }

    var body: some View {
        Group {
            if isLoading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error {
                ContentUnavailableView {
                    Label("Couldn't load playlists", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(error)
                } actions: {
                    Button("Retry") { Task { await load() } }
                }
            } else if filtered.isEmpty {
                ContentUnavailableView("No playlists", systemImage: "music.note.list")
            } else {
                List(filtered, id: \.slug) { playlist in
                    Button {
                        appModel.path.append(.publicPlaylist(playlist))
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(playlist.name)
                            Text(subtitle(playlist))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .accessibilityIdentifier(AXIdentifiers.publicPlaylistsList)
            }
        }
        .searchable(text: $query, prompt: "Search public playlists…")
        .task { await load() }
    }

    private func subtitle(_ p: PublicPlaylistSummary) -> String {
        var parts = ["\(p.tracksCount) \(plural(p.tracksCount, "track"))", formatCompactDuration(ms: p.durationMs)]
        if let author = p.author, !author.isEmpty { parts.append("by \(author)") }
        if p.likesCount > 0 { parts.append("♥ \(p.likesCount)") }
        return parts.joined(separator: " · ")
    }

    private func load() async {
        isLoading = true
        error = nil
        do {
            playlists = try await PhishInAPI.publicPlaylists()
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}

/// One public playlist: header (name, author, counts, description, like), a track filter,
/// and the tracks. Tapping a row plays the playlist from there.
struct PublicPlaylistView: View {
    let summary: PublicPlaylistSummary

    @EnvironmentObject private var player: Player
    @Environment(\.ledgerColors) private var colors

    @State private var playlist: PublicPlaylist?
    @State private var isLoading = true
    @State private var error: String?
    @State private var query = ""

    private func visibleEntries(_ playlist: PublicPlaylist) -> [PublicPlaylistEntry] {
        let q = query.trimmingCharacters(in: .whitespaces)
        let playable = playlist.playableEntries
        guard !q.isEmpty else { return playable }
        return playable.filter {
            $0.track.title.localizedCaseInsensitiveContains(q)
                || ($0.track.showDate ?? "").localizedCaseInsensitiveContains(q)
                || ($0.track.venueName ?? "").localizedCaseInsensitiveContains(q)
        }
    }

    var body: some View {
        Group {
            if isLoading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error {
                ContentUnavailableView {
                    Label("Couldn't load playlist", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(error)
                } actions: {
                    Button("Retry") { Task { await load() } }
                }
            } else if let playlist {
                List {
                    header(playlist)
                    ForEach(visibleEntries(playlist), id: \.position) { entry in
                        Button {
                            play(playlist, startingAt: entry)
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(entry.track.title)
                                    Text([entry.track.showDate, entry.track.venueName].compactMap { $0 }.joined(separator: " · "))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(fmt(entry.duration > 0 ? entry.duration : entry.track.duration))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .searchable(text: $query, prompt: "Search this playlist…")
                .accessibilityIdentifier(AXIdentifiers.publicPlaylistTracks)
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    if let playlist { play(playlist, startingAt: nil) }
                } label: {
                    Label("Play", systemImage: "play.fill")
                }
                .disabled(playlist?.playableEntries.isEmpty != false)
            }
        }
        .task { await load() }
    }

    private func header(_ playlist: PublicPlaylist) -> some View {
        let s = playlist.summary
        return VStack(alignment: .leading, spacing: 6) {
            Text(s.name).font(.title2.weight(.semibold))
            Text(headerSubtitle(s)).font(.callout).foregroundStyle(.secondary)
            if let description = s.description, !description.isEmpty {
                Text(description).font(.callout).foregroundStyle(.secondary)
            }
            ShowLikeButton(showID: s.id, likable: .playlist, likesCount: s.likesCount, likedByUser: s.likedByUser)
        }
        .padding(.vertical, 4)
    }

    private func headerSubtitle(_ s: PublicPlaylistSummary) -> String {
        var parts = ["\(s.tracksCount) \(plural(s.tracksCount, "track"))", formatCompactDuration(ms: s.durationMs)]
        if let author = s.author, !author.isEmpty { parts.append("by \(author)") }
        return parts.joined(separator: " · ")
    }

    private func play(_ playlist: PublicPlaylist, startingAt entry: PublicPlaylistEntry?) {
        let detail = playlist.toShowDetail()
        let startIndex = entry.flatMap { e in detail.tracks.firstIndex { $0.position == e.position } } ?? 0
        player.play(detail: detail, startIndex: startIndex)
    }

    private func load() async {
        isLoading = true
        error = nil
        do {
            playlist = try await PhishInAPI.publicPlaylist(summary.slug)
        } catch {
            self.error = error.localizedDescription
        }
        isLoading = false
    }
}
