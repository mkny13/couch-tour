import CouchTourKit
import SwiftUI

/// Flat, newest-first, with an artist filter (D171) rather than grouped sections or a
/// drill-down — this is what History is mostly used for ("what did I just play"), so staying
/// on one screen beats an extra click. Android's History screen has no filter or grouping at
/// all; the DAO groundwork (`Progress.artists()`/`historyFor()`) exists there but is only
/// exercised by tests, so this isn't a port either way.
struct HistoryView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @State private var rows: [PlaybackProgress] = []
    @State private var loadState: LoadState = .loading
    @State private var resumeError: String?
    @State private var selectedArtist: String?

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
                    let artists = historyArtists(rows)
                    // Becomes nil (falling back to "everything") once the artist it names is
                    // no longer among the rows — the same accident-of-key-reuse behavior
                    // SearchView's own artist filter has.
                    let activeArtist = selectedArtist.flatMap { artists.contains($0) ? $0 : nil }
                    let filteredRows = activeArtist.map { artist in rows.filter { $0.artist == artist } } ?? rows

                    VStack(spacing: 0) {
                        if artists.count > 1 {
                            Picker("Artist", selection: $selectedArtist) {
                                Text("All artists").tag(String?.none)
                                ForEach(artists, id: \.self) { artist in
                                    Text(artist).tag(String?.some(artist))
                                }
                            }
                            .pickerStyle(.menu)
                            .labelsHidden()
                            .padding([.horizontal, .top])
                        }

                        if rows.isEmpty {
                            ContentUnavailableView(
                                "No listening history yet",
                                systemImage: "clock.arrow.circlepath",
                                description: Text("Shows you've played will show up here.")
                            )
                        } else {
                            List(filteredRows, id: \.queueKey) { row in
                                HStack {
                                    // ProgressRow's own third line already covers "last
                                    // played" — shared with Continue Listening, so it's not
                                    // repeated here.
                                    ProgressRow(row: row)
                                    Spacer()
                                    Text(status(for: row))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                .contentShape(Rectangle())
                                .onTapGesture { Task { await tapResume(row) } }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("History")
        .task { await load() }
        .onChange(of: player.queueKey) { _, _ in Task { await load() } }
        .alert("Couldn't resume", isPresented: Binding(
            get: { resumeError != nil },
            set: { if !$0 { resumeError = nil } }
        )) {
            Button("OK") {}
        } message: {
            Text(resumeError ?? "")
        }
    }

    private func status(for row: PlaybackProgress) -> String {
        if row.finished { return "completed" }
        if row.dismissed { return "removed" }
        return "in progress"
    }

    private func tapResume(_ row: PlaybackProgress) async {
        do {
            try await resume(row, player: player)
        } catch {
            resumeError = "Couldn't resume \(row.title): \(error.localizedDescription)"
        }
    }

    private func load() async {
        guard let store = appModel.progressStore else { return }
        loadState = .loading
        do {
            rows = try store.history()
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load History: \(error.localizedDescription)")
        }
    }
}
