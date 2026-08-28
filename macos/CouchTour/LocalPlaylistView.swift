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
    @State private var isRenaming = false
    @State private var renameText = ""
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
                    ForEach(Array(rows.enumerated()), id: \.element.rowId) { index, row in
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

                            HStack(spacing: 4) {
                                Button {
                                    moveUp(row)
                                } label: {
                                    Image(systemName: "chevron.up")
                                        .foregroundStyle(index > 0 ? .secondary : .quaternary)
                                }
                                .buttonStyle(.plain)
                                .disabled(index == 0)

                                Button {
                                    moveDown(row)
                                } label: {
                                    Image(systemName: "chevron.down")
                                        .foregroundStyle(index < rows.count - 1 ? .secondary : .quaternary)
                                }
                                .buttonStyle(.plain)
                                .disabled(index >= rows.count - 1)

                                Button {
                                    remove(row)
                                } label: {
                                    Image(systemName: "xmark.circle")
                                        .foregroundStyle(.secondary)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .contextMenu {
                            Button("Move Up") { moveUp(row) }.disabled(index == 0)
                            Button("Move Down") { moveDown(row) }.disabled(index >= rows.count - 1)
                            Divider()
                            Button("Remove", role: .destructive) { remove(row) }
                        }
                    }
                    .onMove(perform: move)
                }
            }
        }
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
                Button {
                    renameText = playlist?.name ?? ""
                    isRenaming = true
                } label: {
                    Label("Rename", systemImage: "pencil")
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button(role: .destructive) {
                    deletePlaylist()
                } label: {
                    Label("Delete Playlist", systemImage: "trash")
                }
            }
        }
        .sheet(isPresented: $isRenaming) {
            RenamePlaylistSheet(name: $renameText) { newName in
                rename(to: newName)
                isRenaming = false
            } onCancel: {
                isRenaming = false
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

    private func rename(to newName: String) {
        guard let store = appModel.localPlaylistStore else { return }
        let trimmed = newName.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        try? store.renamePlaylist(id: playlistId, name: trimmed, now: Int64(Date().timeIntervalSince1970 * 1000))
        load()
    }

    private func move(from source: IndexSet, to destination: Int) {
        var mutable = rows
        mutable.move(fromOffsets: source, toOffset: destination)
        saveOrder(mutable)
    }

    private func moveUp(_ row: LocalPlaylistTrack) {
        guard let index = rows.firstIndex(of: row), index > 0 else { return }
        var mutable = rows
        mutable.swapAt(index, index - 1)
        saveOrder(mutable)
    }

    private func moveDown(_ row: LocalPlaylistTrack) {
        guard let index = rows.firstIndex(of: row), index < rows.count - 1 else { return }
        var mutable = rows
        mutable.swapAt(index, index + 1)
        saveOrder(mutable)
    }

    private func saveOrder(_ newRows: [LocalPlaylistTrack]) {
        guard let store = appModel.localPlaylistStore else { return }
        let rowIds = newRows.compactMap(\.rowId)
        try? store.reorderTracks(playlistId: playlistId, orderedRowIds: rowIds, now: Int64(Date().timeIntervalSince1970 * 1000))
        load()
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

private struct RenamePlaylistSheet: View {
    @Binding var name: String
    let onRename: (String) -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Rename Playlist").font(.headline)
            TextField("Name", text: $name)
                .textFieldStyle(.roundedBorder)
                .onSubmit { onRename(name) }
            HStack {
                Spacer()
                Button("Cancel") { onCancel() }
                Button("Rename") { onRename(name) }
                    .keyboardShortcut(.defaultAction)
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
        .padding()
        .frame(width: 320)
    }
}
