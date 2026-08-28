import CouchTourKit
import SwiftUI

/// Local playlists list + create (#59) — the macOS analogue of Android's
/// `LocalPlaylistsScreen`. Account-free, spans both backends.
struct LocalPlaylistsView: View {
    @EnvironmentObject private var appModel: AppModel
    @State private var playlists: [LocalPlaylist] = []
    @State private var newName = ""
    @State private var showNewPlaylistField = false

    var body: some View {
        Group {
            if appModel.localPlaylistStore == nil {
                ErrorView(message: appModel.progressStoreError ?? "Couldn't open the playlist database.") {}
            } else if playlists.isEmpty {
                ContentUnavailableView(
                    "No playlists yet",
                    systemImage: "music.note.list",
                    description: Text("Mix tracks from any artist, saved on this device.")
                )
            } else {
                List(playlists, id: \.id) { playlist in
                    NavigationLink(value: Route.localPlaylist(playlist)) {
                        VStack(alignment: .leading) {
                            Text(playlist.name)
                            Text("\(playlist.trackCount) \(plural(playlist.trackCount, "track"))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showNewPlaylistField = true
                } label: {
                    Label("New Playlist", systemImage: "plus")
                }
                .disabled(appModel.localPlaylistStore == nil)
            }
        }
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
