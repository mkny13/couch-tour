import CouchTourKit
import SwiftUI

struct ShowDetailView: View {
    let show: ShowSummary

    @State private var detail: ShowDetail?
    @State private var loadState: LoadState = .loading
    @State private var showSourcePicker = false
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
                        if hasRealAlternates(detail) {
                            Section("Source") {
                                sourceRow(detail)
                            }
                        }
                        ForEach(trackGroups(detail.tracks), id: \.setName) { group in
                            Section(group.setName.isEmpty ? "" : group.setName) {
                                ForEach(group.tracks, id: \.id) { track in
                                    TrackRow(
                                        track: track,
                                        backend: show.artist.backend,
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
        .navigationSubtitle(show.where_)
        .task { await load() }
    }

    /// No source to switch on a single-source artist (Phish) or a show with only one —
    /// matches Android's `SourcePicker` gate. The old gate here also opened on
    /// `detail.recording != nil` alone, which rendered a one-item picker with nothing to pick.
    private func hasRealAlternates(_ detail: ShowDetail) -> Bool {
        show.artist.hasMultipleSources && !detail.alternates.isEmpty
    }

    /// The collapsed row that opens the source popover — current source's label plus a count,
    /// the macOS analogue of Android's collapsed `RowItem` before its `ModalBottomSheet` opens.
    @ViewBuilder
    private func sourceRow(_ detail: ShowDetail) -> some View {
        let sources = allSources(detail)
        Button {
            showSourcePicker = true
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(detail.recording?.label ?? "Source")
                Text("\(sources.count) \(plural(sources.count, "source"))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .popover(isPresented: $showSourcePicker) {
            SourcePicker(sources: sources, currentID: detail.recording?.id, onSelect: selectSource)
        }
    }

    /// Current source pinned first, same order the old `Picker` used. Deliberately no
    /// client-side sort beyond that — Relisten already returns sources ranked by
    /// `avg_rating_weighted` desc (D79), so re-sorting here would just fight that ranking.
    private func allSources(_ detail: ShowDetail) -> [RecordingRef] {
        [detail.recording].compactMap { $0 } + detail.alternates
    }

    private func selectSource(_ recordingID: String) {
        showSourcePicker = false
        // Tapping the current source closes the popover and does nothing else — same as
        // Android's picker.
        guard recordingID != detail?.recording?.id else { return }
        Task { await switchSource(to: recordingID) }
    }

    /// The behavioral half of #17 the old `Picker` never had: switching sources restarts
    /// playback on the new one at the same track index and position, rather than leaving the
    /// player on the old source while the displayed track list moves out from under it.
    private func switchSource(to recordingID: String) async {
        let queueKeyBeforeSwitch = detail?.queueKey
        await load(recordingID: recordingID)

        // Only carry position if this show's queue is what's actually playing right now —
        // otherwise this is just browsing a source, not the active queue.
        guard let detail, let queueKeyBeforeSwitch, player.queueKey == queueKeyBeforeSwitch else { return }
        // Tapers split tracks differently, so the same index carries over as an
        // approximation, not an exact position — same call Android's picker makes.
        let index = min(player.currentIndex ?? 0, max(detail.tracks.count - 1, 0))
        player.play(detail: detail, startIndex: index, resumePositionMs: player.positionMs)
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

/// A popover rather than a sheet — the macOS analogue of Android's `ModalBottomSheet`: anchored
/// to the row that opened it, dismissed by clicking away, lighter than a modal sheet for what
/// is fundamentally a picker.
private struct SourcePicker: View {
    let sources: [RecordingRef]
    let currentID: String?
    let onSelect: (String) -> Void

    var body: some View {
        List(sources, id: \.id) { source in
            Button {
                onSelect(source.id)
            } label: {
                SourceRow(source: source, isCurrent: source.id == currentID)
            }
            .buttonStyle(.plain)
        }
        .listStyle(.plain)
        .frame(width: 340)
        .frame(minHeight: 80, maxHeight: 420)
    }
}

private struct SourceRow: View {
    let source: RecordingRef
    let isCurrent: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: "checkmark")
                .font(.caption)
                .foregroundStyle(.tint)
                .opacity(isCurrent ? 1 : 0)
                .padding(.top, 2)

            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(source.label)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .lineLimit(1)
                    if source.isSoundboard {
                        badge("SBD", color: .accentColor)
                    }
                    // The "?" is deliberate — looksLikeMatrix is a text heuristic, not a
                    // guaranteed signal (Relisten has no structured matrix flag).
                    if source.looksLikeMatrix {
                        badge("Matrix?", color: .purple)
                    }
                }
                if source.rating > 0 {
                    Text(ratingLine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                // Suppressed when it would just repeat the label — the label already
                // defaults to the taper's name.
                if let taper = source.taper, taper != source.label {
                    Text("Taper: \(taper)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let lineage = source.lineage {
                    Text("Lineage: \(lineage)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }

    private var ratingLine: String {
        var parts = [String(format: "★ %.1f", source.rating)]
        if source.reviewCount > 0 {
            parts.append("\(source.reviewCount) \(plural(source.reviewCount, "review"))")
        }
        return parts.joined(separator: " · ")
    }

    private func badge(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.caption2)
            .fontWeight(.bold)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
            .foregroundStyle(color)
    }
}

private struct TrackRow: View {
    let track: PlayableTrack
    let backend: Backend
    let isPlaying: Bool
    let onTap: () -> Void

    var body: some View {
        HStack {
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

            TrackLikeButton(
                backend: backend, trackID: track.id, likesCount: track.likesCount, likedByUser: track.likedByUser
            )
        }
    }
}
