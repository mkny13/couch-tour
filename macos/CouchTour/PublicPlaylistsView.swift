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

/// One public playlist's tracks. Tapping a row plays the playlist from there.
struct PublicPlaylistView: View {
    let summary: PublicPlaylistSummary

    @EnvironmentObject private var player: Player

    @State private var playlist: PublicPlaylist?
    @State private var isLoading = true
    @State private var error: String?

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
                    if let description = playlist.description, !description.isEmpty {
                        Text(description).font(.callout).foregroundStyle(.secondary)
                    }
                    ForEach(playlist.tracks.filter(\.playable), id: \.position) { track in
                        Button {
                            play(playlist, startingAt: track)
                        } label: {
                            HStack {
                                VStack(alignment: .leading) {
                                    Text(track.title)
                                    Text([track.date, track.venue].compactMap { $0 }.joined(separator: " · "))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(fmt(track.durationMs)).font(.caption).foregroundStyle(.secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    if let playlist { play(playlist, startingAt: nil) }
                } label: {
                    Label("Play", systemImage: "play.fill")
                }
                .disabled(playlist?.tracks.contains(where: \.playable) != true)
            }
        }
        .task { await load() }
    }

    private func play(_ playlist: PublicPlaylist, startingAt track: PublicPlaylistTrack?) {
        let detail = playlist.toShowDetail()
        let startIndex = track.flatMap { t in detail.tracks.firstIndex { $0.position == t.position } } ?? 0
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
