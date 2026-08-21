import CouchTourKit
import SwiftUI

/// Add-a-track-to-a-local-playlist affordance (#59) — the macOS analogue of Android's
/// `AddToPlaylistButton`: a `.popover` in place of its `ModalBottomSheet`, same "existing
/// playlists + New Playlist" list. Sits next to the like button on every track row, same
/// placement Android uses.
struct AddToPlaylistButton: View {
    /// Builds the row to insert — `playlistId`/`position`/`rowId` are filled in by
    /// `LocalPlaylistStore.addTrack` at add-time, so the draft only needs to know what
    /// track it is.
    let draft: () -> LocalPlaylistTrack

    @EnvironmentObject private var appModel: AppModel
    @State private var showPicker = false
    @State private var playlists: [LocalPlaylist] = []
    @State private var newName = ""
    @State private var creatingNew = false

    var body: some View {
        Button {
            playlists = (try? appModel.localPlaylistStore?.playlists()) ?? []
            showPicker = true
        } label: {
            Image(systemName: "text.badge.plus")
                .foregroundStyle(.secondary)
        }
        .buttonStyle(.plain)
        .disabled(appModel.localPlaylistStore == nil)
        .popover(isPresented: $showPicker) {
            VStack(alignment: .leading, spacing: 8) {
                if creatingNew {
                    TextField("Playlist name", text: $newName)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { createAndAdd() }
                    Button("Create") { createAndAdd() }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                } else {
                    Button {
                        creatingNew = true
                    } label: {
                        Label("New Playlist", systemImage: "plus")
                    }
                    .buttonStyle(.plain)
                    if !playlists.isEmpty {
                        Divider()
                        ForEach(playlists, id: \.id) { playlist in
                            Button(playlist.name) { add(to: playlist.id) }
                                .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding()
            .frame(width: 220)
        }
    }

    private func add(to playlistId: String) {
        guard let store = appModel.localPlaylistStore else { return }
        try? store.addTrack(draft(), toPlaylist: playlistId, now: nowMs())
        showPicker = false
    }

    private func createAndAdd() {
        guard let store = appModel.localPlaylistStore else { return }
        let name = newName.trimmingCharacters(in: .whitespaces)
        guard !name.isEmpty else { return }
        let now = nowMs()
        if let playlist = try? store.createPlaylist(name: name, now: now) {
            try? store.addTrack(draft(), toPlaylist: playlist.id, now: now)
        }
        newName = ""
        creatingNew = false
        showPicker = false
    }

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
