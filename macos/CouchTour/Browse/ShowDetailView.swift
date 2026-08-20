import CouchTourKit
import SwiftUI

struct ShowDetailView: View {
    let show: ShowSummary

    @State private var detail: ShowDetail?
    @State private var selectedRecordingID: String?
    @State private var loadState: LoadState = .loading
    @EnvironmentObject private var player: Player

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                if let detail {
                    List {
                        if show.artist.hasMultipleSources, !detail.alternates.isEmpty || detail.recording != nil {
                            Section("Tape") {
                                tapeSwitcher(detail)
                            }
                        }
                        ForEach(trackGroups(detail.tracks), id: \.setName) { group in
                            Section(group.setName.isEmpty ? "" : group.setName) {
                                ForEach(group.tracks, id: \.id) { track in
                                    TrackRow(
                                        track: track,
                                        // Keyed on queueKey, not just the show: two tapes of
                                        // the same date share a ShowSummary but not a queue.
                                        isPlaying: player.queueKey == detail.queueKey && player.currentTrack?.id == track.id
                                    ) {
                                        guard let index = detail.tracks.firstIndex(of: track) else { return }
                                        player.play(detail: detail, startIndex: index)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(show.date)
        .task { await load() }
    }

    @ViewBuilder
    private func tapeSwitcher(_ detail: ShowDetail) -> some View {
        let tapes = ([detail.recording].compactMap { $0 } + detail.alternates)
        Picker("Tape", selection: Binding(
            get: { selectedRecordingID ?? detail.recording?.id },
            set: { newValue in
                selectedRecordingID = newValue
                Task { await load(recordingID: newValue) }
            }
        )) {
            ForEach(tapes, id: \.id) { tape in
                Text(tapeLabel(tape)).tag(Optional(tape.id))
            }
        }
        .pickerStyle(.menu)
    }

    private func tapeLabel(_ tape: RecordingRef) -> String {
        var parts = [tape.label]
        if tape.rating > 0 { parts.append(String(format: "%.2f★", tape.rating)) }
        return parts.joined(separator: " · ")
    }

    private func load(recordingID: String? = nil) async {
        loadState = .loading
        do {
            detail = try await sourceFor(show.artist.backend).show(
                artist: show.artist, date: show.date, recordingId: recordingID
            )
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load this show: \(error.localizedDescription)")
        }
    }
}

private struct TrackRow: View {
    let track: PlayableTrack
    let isPlaying: Bool
    let onTap: () -> Void

    var body: some View {
        // A Button rather than .onTapGesture: keyboard- and VoiceOver-activatable, and
        // .onTapGesture wasn't reliably triggered by synthetic clicks during D166's live
        // verification pass even though List row navigation elsewhere in the app was fine.
        Button(action: onTap) {
            HStack {
                if isPlaying {
                    Image(systemName: "speaker.wave.2.fill")
                        .foregroundStyle(.tint)
                        .font(.caption)
                }
                Text(track.title)
                    .fontWeight(isPlaying ? .semibold : .regular)
                Spacer()
                Text(fmt(track.durationMs))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
