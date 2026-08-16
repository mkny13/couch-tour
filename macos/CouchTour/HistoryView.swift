import CouchTourKit
import SwiftUI

/// Flat, newest-first for now. Grouping by artist (matching Android's History screen) is
/// left for later — real depth there is worth doing once there's real history to look at.
struct HistoryView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @State private var rows: [PlaybackProgress] = []
    @State private var loadState: LoadState = .loading
    @State private var resumeError: String?

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
                            "No listening history yet",
                            systemImage: "clock.arrow.circlepath",
                            description: Text("Shows you've played will show up here.")
                        )
                    } else {
                        List(rows, id: \.queueKey) { row in
                            HStack {
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
