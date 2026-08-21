import CouchTourKit
import SwiftUI

/// One local playlist's tracks: play-from-here, remove, delete the whole playlist (#59) — the
/// macOS analogue of Android's `LocalPlaylistScreen`. The list renders instantly from the
/// denormalized `LocalPlaylistTrack` fields (no network call); playing resolves the real,
/// freshly-fetched tracks via `resolveLocalPlaylistTracks` first, same as `Resume.swift`.
struct LocalPlaylistView: View {
    let playlistId: String

    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @Environment(\.dismiss) private var dismiss

    @State private var playlist: LocalPlaylist?
    @State private var rows: [LocalPlaylistTrack] = []
    @State private var isResolving = false
    @State private var error: String?

    var body: some View {
        Group {
            if rows.isEmpty {
                ContentUnavailableView(
                    "No tracks yet",
                    systemImage: "music.note.list",
                    description: Text("Add tracks from any show using the playlist button next to its like button.")
                )
            } else {
                List {
                    ForEach(rows, id: \.rowId) { row in
                        HStack {
                            Button {
                                Task { await play(startingAt: row) }
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(row.title)
                                    Text(subtitle(for: row))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            Spacer()
                            Text(fmt(row.durationMs))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Button {
                                remove(row)
                            } label: {
                                Image(systemName: "xmark.circle")
                                    .foregroundStyle(.secondary)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .navigationTitle(playlist?.name ?? "Playlist")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await play(startingAt: nil) }
                } label: {
                    Label("Play", systemImage: "play.fill")
                }
                .disabled(rows.isEmpty)
            }
            ToolbarItem(placement: .primaryAction) {
                Button(role: .destructive) {
                    deletePlaylist()
                } label: {
                    Label("Delete Playlist", systemImage: "trash")
                }
            }
        }
        .overlay {
            if isResolving {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity).background(.regularMaterial)
            }
        }
        .task { load() }
        .alert("Couldn't play", isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button("OK") {}
        } message: {
            Text(error ?? "")
        }
    }

    private func subtitle(for row: LocalPlaylistTrack) -> String {
        [row.venueName, row.showDate].compactMap { $0 }.joined(separator: " · ")
    }

    private func load() {
        guard let store = appModel.localPlaylistStore else { return }
        playlist = try? store.playlist(id: playlistId)
        rows = (try? store.tracks(playlistId: playlistId)) ?? []
    }

    private func play(startingAt startRow: LocalPlaylistTrack?) async {
        guard let store = appModel.localPlaylistStore, let playlist else { return }
        isResolving = true
        defer { isResolving = false }
        do {
            let detail = try await localPlaylistShowDetail(playlist, store: store)
            guard !detail.tracks.isEmpty else { throw ResumeError.unresumable }
            let startIndex = startRow.flatMap { row in detail.tracks.firstIndex { $0.id == row.trackId } } ?? 0
            player.play(detail: detail, startIndex: startIndex)
        } catch {
            self.error = "Couldn't resolve this playlist's tracks: \(error.localizedDescription)"
        }
    }

    private func remove(_ row: LocalPlaylistTrack) {
        guard let store = appModel.localPlaylistStore, let rowId = row.rowId else { return }
        try? store.removeTrack(rowId: rowId, fromPlaylist: playlistId, now: Int64(Date().timeIntervalSince1970 * 1000))
        load()
    }

    private func deletePlaylist() {
        guard let store = appModel.localPlaylistStore else { return }
        try? store.deletePlaylist(id: playlistId)
        dismiss()
    }
}
