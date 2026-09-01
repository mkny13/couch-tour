import CouchTourKit
import SwiftUI

/// Continue Listening and History, merged (D203, superseding D171's placement of History as
/// its own flat screen).
///
/// They were two sidebar sections over the same table, differing only in which rows they
/// selected — and once Home grew its own Continue Listening shelf (#98), one of them was mostly
/// a second copy of that. A scope picker is the honest shape: same rows, same actions, one
/// screen. D171's two substantive choices both survive — flat and newest-first rather than
/// grouped or drilled into, with an artist filter over the full history.
struct ListeningView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player

    @State private var scope: Scope = .inProgress
    @State private var inProgress: [PlaybackProgress] = []
    @State private var history: [PlaybackProgress] = []
    @State private var loadState: LoadState = .loading
    @State private var resumeError: String?
    @State private var selectedArtist: String?
    @State private var resolvingRow: String?

    enum Scope: String, CaseIterable, Identifiable {
        case inProgress = "In Progress"
        case history = "History"

        var id: String { rawValue }
    }

    private var rows: [PlaybackProgress] {
        scope == .inProgress ? inProgress : history
    }

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                loadedBody
            }
        }
        .task { await load() }
        // A crude but sufficient refresh trigger for the MVP: reload whenever playback moves
        // to a new queue, so starting or advancing a show updates this list if it's on screen.
        .onChange(of: player.queueKey) { _, _ in Task { await load() } }
        // Without this, a background sync (launch/foreground/15-minute timer, AppModel.syncNow)
        // writes fresh rows via ProgressStore.put() but this view — unlike Android's reactive
        // Room Flow — never re-queries, so it can sit showing a stale list even though the two
        // devices' databases actually agree.
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

    @ViewBuilder
    private var loadedBody: some View {
        // Becomes nil (falling back to "everything") once the artist it names is no longer
        // among the rows — the same accident-of-key-reuse behavior SearchView's own artist
        // filter has.
        let artists = historyArtists(history)
        let activeArtist = selectedArtist.flatMap { artists.contains($0) ? $0 : nil }
        let filtered = scope == .history
            ? (activeArtist.map { artist in history.filter { $0.artist == artist } } ?? history)
            : inProgress

        VStack(spacing: 0) {
            HStack {
                Picker("Scope", selection: $scope) {
                    ForEach(Scope.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .labelsHidden()
                .frame(maxWidth: 260)

                if scope == .history, artists.count > 1 {
                    Picker("Artist", selection: $selectedArtist) {
                        Text("All artists").tag(String?.none)
                        ForEach(artists, id: \.self) { artist in
                            Text(artist).tag(String?.some(artist))
                        }
                    }
                    .pickerStyle(.menu)
                    .labelsHidden()
                    .frame(maxWidth: 220)
                }

                Spacer()
            }
            .padding([.horizontal, .top])

            if rows.isEmpty {
                emptyState
            } else {
                List(filtered, id: \.queueKey) { row in
                    HStack {
                        ProgressRow(
                            row: row,
                            isResolvingNavigation: resolvingRow == row.queueKey,
                            onOpen: { Task { await openRow(row) } },
                            onPlay: { Task { await tapResume(row) } }
                        )
                        if scope == .history {
                            Spacer()
                            Text(status(for: row))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    // Same open/mark completed/remove trio as Home's Continue Listening shelf
                    // (#115), so the two surfaces agree (D200/#98).
                    .contextMenu {
                        Button {
                            Task { await openRow(row) }
                        } label: {
                            Label(isPlaylist(row) ? "Open Playlist" : "Open Show", systemImage: "arrow.up.forward.app")
                        }
                        Button {
                            Task { await markCompleted(row) }
                        } label: {
                            Label("Mark Completed", systemImage: "checkmark.circle")
                        }
                        Button(role: .destructive) {
                            Task { await remove(row) }
                        } label: {
                            Label("Remove from List", systemImage: "trash")
                        }
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        ContentUnavailableView(
            scope == .inProgress ? "Nothing in progress" : "No listening history yet",
            systemImage: scope == .inProgress ? "play.circle" : "clock.arrow.circlepath",
            description: Text(
                scope == .inProgress
                    ? "Shows you start playing will show up here."
                    : "Shows you've played will show up here."
            )
        )
    }

    private func status(for row: PlaybackProgress) -> String {
        if row.finished { return "completed" }
        if row.dismissed { return "removed" }
        return "in progress"
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
            switch try await resolveNavigationTarget(for: row, localPlaylistStore: appModel.localPlaylistStore) {
            case .show(let show): appModel.path.append(.show(show))
            case .localPlaylist(let playlist): appModel.path.append(.localPlaylist(playlist))
            }
        } catch {
            resumeError = "Couldn't open \(row.title): \(error.localizedDescription)"
        }
        resolvingRow = nil
    }

    private func isPlaylist(_ row: PlaybackProgress) -> Bool {
        switch parseQueueKey(row.queueKey)?.kind {
        case .playlist, .localPlaylist: return true
        default: return false
        }
    }

    private func markCompleted(_ row: PlaybackProgress) async {
        guard let store = appModel.progressStore else { return }
        do {
            try store.markFinished(key: row.queueKey)
            await load()
        } catch {
            resumeError = "Couldn't update \(row.title): \(error.localizedDescription)"
        }
    }

    private func remove(_ row: PlaybackProgress) async {
        guard let store = appModel.progressStore else { return }
        do {
            try store.dismiss(key: row.queueKey)
            await load()
        } catch {
            resumeError = "Couldn't remove \(row.title): \(error.localizedDescription)"
        }
    }

    /// Both scopes come from one pass, so switching between them is instant and they can't
    /// disagree about what the database says.
    private func load() async {
        guard let store = appModel.progressStore else {
            loadState = .failed(appModel.progressStoreError ?? "The listening history database is unavailable.")
            return
        }
        loadState = .loading
        do {
            inProgress = try store.inProgress()
            history = try store.history()
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load your listening history: \(error.localizedDescription)")
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
            // split as HomeView's ResumeCardView, so the Listening screen and the Home shelf
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
            .accessibilityLabel("Resume \(row.title)")
        }
    }
}
