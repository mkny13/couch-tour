import CouchTourKit
import SwiftUI

struct ContinueListeningView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @State private var rows: [PlaybackProgress] = []
    @State private var loadState: LoadState = .loading
    @State private var resumeError: String?
    @State private var navigationTarget: ResumeNavigationTarget?
    @State private var resolvingRow: String?

    var body: some View {
        Group {
            if let error = appModel.progressStoreError {
                ErrorView(message: error) {}
            } else {
                switch loadState {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                case .failed(let message):
                    ErrorView(message: message) { await load() }
                case .loaded:
                    if rows.isEmpty {
                        ContentUnavailableView(
                            "Nothing in progress",
                            systemImage: "play.circle",
                            description: Text("Shows you start playing will show up here.")
                        )
                    } else {
                        List(rows, id: \.queueKey) { row in
                            ProgressRow(
                                row: row,
                                isResolvingNavigation: resolvingRow == row.queueKey,
                                onOpen: { Task { await openRow(row) } },
                                onPlay: { Task { await tapResume(row) } }
                            )
                        }
                    }
                }
            }
        }
        .navigationTitle("Continue Listening")
        .navigationDestination(item: $navigationTarget) { target in
            switch target {
            case .show(let show): ShowDetailView(show: show)
            case .localPlaylist(let playlist): LocalPlaylistView(playlistId: playlist.id)
            }
        }
        .task { await load() }
        // A crude but sufficient refresh trigger for the MVP: reload whenever playback moves
        // to a new queue, so starting or advancing a show updates this list if it's on screen.
        .onChange(of: player.queueKey) { _, _ in Task { await load() } }
        // Without this, a background sync (launch/foreground/15-minute timer, AppModel.syncNow)
        // writes fresh rows via ProgressStore.put() but this view — unlike Android's reactive
        // Room Flow — never re-queries, so it can sit showing a stale "Continue Listening" list
        // even though the two devices' databases actually agree.
        .onChange(of: appModel.syncSession.lastSyncedAt) { _, _ in Task { await load() } }
        .alert("Couldn't resume", isPresented: Binding(
            get: { resumeError != nil },
            set: { if !$0 { resumeError = nil } }
        )) {
            Button("OK") {}
        } message: {
            Text(resumeError ?? "")
        }
    }

    private func tapResume(_ row: PlaybackProgress) async {
        do {
            try await resume(row, player: player, localPlaylistStore: appModel.localPlaylistStore)
        } catch {
            resumeError = "Couldn't resume \(row.title): \(error.localizedDescription)"
        }
    }

    private func openRow(_ row: PlaybackProgress) async {
        resolvingRow = row.queueKey
        do {
            navigationTarget = try await resolveNavigationTarget(
                for: row, localPlaylistStore: appModel.localPlaylistStore
            )
        } catch {
            resumeError = "Couldn't open \(row.title): \(error.localizedDescription)"
        }
        resolvingRow = nil
    }

    private func load() async {
        guard let store = appModel.progressStore else { return }
        loadState = .loading
        do {
            rows = try store.inProgress()
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load Continue Listening: \(error.localizedDescription)")
        }
    }
}

struct ProgressRow: View {
    let row: PlaybackProgress
    let isResolvingNavigation: Bool
    let onOpen: () -> Void
    let onPlay: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            // Text opens the show; the play button is a separate, non-overlapping target — same
            // split as HomeView's ResumeCardView, so the sidebar section and the Home shelf
            // agree on what a click means (#98).
            VStack(alignment: .leading) {
                Text(row.artist.isEmpty ? row.title : "\(row.artist) · \(row.title)")
                Text(row.subtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(row.trackTitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(relativeTime(row.updatedAt))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)

            Spacer()

            if isResolvingNavigation {
                ProgressView()
                    .controlSize(.small)
            }

            Button(action: onPlay) {
                Image(systemName: "play.circle.fill")
                    .font(.title)
                    .foregroundStyle(.tint)
            }
            .buttonStyle(.plain)
            .help("Resume")
        }
    }
}
