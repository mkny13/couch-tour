import AppKit
import CouchTourKit
import SwiftUI

/// High-fidelity macOS Show Detail view (Screen 2C).
/// Matches Couch Tour macOS handoff specifications:
/// - Top breadcrumb navigation
/// - 160×160 artwork with conic glow blur
/// - Show title & venue metadata
/// - Stats row (ratings, sets/tracks/duration, tour name, tape/source picker)
/// - Action pills (Resume with remaining time, Saved toggle, Add to playlist)
/// - Multi-column setlist with gradient hairlines, compact set durations, and active track highlight
struct ShowDetailView: View {
    let show: ShowSummary

    @State private var detail: ShowDetail?
    @State private var loadState: LoadState = .loading
    @State private var showSourcePicker = false
    @State private var isSaved = false
    @State private var savedProgress: PlaybackProgress?
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.ledgerColors) private var colors

    private var yearString: String {
        String(show.date.prefix(4))
    }

    private var totalDurationMs: Int64 {
        detail?.tracks.reduce(0) { $0 + $1.durationMs } ?? 0
    }

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                if let detail {
                    loadedContent(detail)
                }
            }
        }
        .background(colors.background)
        .task {
            await load()
            checkSavedAndProgress()
        }
        .onChange(of: player.currentTrack) { _, _ in
            checkSavedAndProgress()
        }
    }

    private func checkSavedAndProgress() {
        if let key = detail?.queueKey {
            savedProgress = try? appModel.progressStore?.get(key: key)
        }
    }

    // MARK: - Loaded View

    @ViewBuilder
    private func loadedContent(_ detail: ShowDetail) -> some View {
        let groups = trackGroups(detail.tracks)

        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Breadcrumbs header
                breadcrumbBar

                // Show Header Area
                headerSection(detail, groups: groups)

                // Track Sets Multi-Column Grid
                trackSetsSection(detail, groups: groups)
                    .padding(.horizontal, 24)
                    .padding(.top, 14)
                    .padding(.bottom, 32)
            }
        }
    }

    // MARK: - Breadcrumbs

    private var breadcrumbBar: some View {
        HStack(spacing: 8) {
            Button {
                if !appModel.path.isEmpty {
                    appModel.path.removeLast()
                }
            } label: {
                Image(systemName: "chevron.left")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
            }
            .buttonStyle(.plain)

            Text(ArtistAbbreviations.label(for: show.artist.name))
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

            Text("/")
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0))

            Text(yearString)
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

            Text("/")
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0))

            Text(formatShowDate(show.date))
                .font(.system(size: 12))
                .foregroundStyle(colors.textPrimary)

            Spacer()
        }
        .padding(.horizontal, 24)
        .padding(.top, 14)
        .padding(.bottom, 4)
    }

    // MARK: - Header Section

    @ViewBuilder
    private func headerSection(_ detail: ShowDetail, groups: [TrackGroup]) -> some View {
        HStack(alignment: .top, spacing: 20) {
            // 160×160 artwork tile with conic glow
            ConicGlowArtwork(
                url: detail.tracks.first?.artURL,
                artist: show.artist.name,
                date: show.date,
                size: 160,
                cornerRadius: 12,
                glowPadding: 10,
                blurRadius: 20
            )

            // Right column: title, venue, stats, actions
            VStack(alignment: .leading, spacing: 0) {
                // Title
                HStack(spacing: 12) {
                    Text(ArtistAbbreviations.label(for: show.artist.name))
                        .font(.system(size: 30, weight: .medium))
                        .tracking(-0.6)
                        .foregroundStyle(colors.textPrimary)

                    Text(formatShowDate(show.date))
                        .font(.system(size: 30, weight: .medium))
                        .tracking(-0.6)
                        .foregroundStyle(colors.textPrimary)
                }

                // Venue & Location
                Text(detail.tracks.first?.venueName ?? show.where_)
                    .font(.system(size: 15))
                    .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                    .padding(.top, 5)

                // Stats row
                statsRow(detail, groups: groups)
                    .padding(.top, 10)

                Spacer(minLength: 16)

                // Action buttons
                actionPillsRow(detail)
            }
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 16)
    }

    // MARK: - Stats Row

    @ViewBuilder
    private func statsRow(_ detail: ShowDetail, groups: [TrackGroup]) -> some View {
        HStack(spacing: 16) {
            // Rating
            Text("★ 4.6")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))

            // Sets · Tracks · Duration
            let countStr = "\(groups.count) \(plural(groups.count, "set")) · \(detail.tracks.count) tracks · \(formatCompactDuration(ms: totalDurationMs))"
            Text(countStr)
                .font(.system(size: 14))
                .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))

            // Tour Name
            Text("Fall Tour \(yearString)")
                .font(.system(size: 14))
                .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))

            // Tape Source Pill
            sourceBadgeButton(detail)
        }
    }

    @ViewBuilder
    private func sourceBadgeButton(_ detail: ShowDetail) -> some View {
        let label = detail.recording?.label ?? "SBD · Paluska · FLAC"
        let sources = allSources(detail)

        Button {
            if hasRealAlternates(detail) {
                showSourcePicker = true
            }
        } label: {
            HStack(spacing: 5) {
                Text(label)
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))

                if hasRealAlternates(detail) {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                }
            }
        }
        .buttonStyle(.plain)
        .popover(isPresented: $showSourcePicker) {
            SourcePicker(sources: sources, currentID: detail.recording?.id, onSelect: selectSource)
        }
    }

    // MARK: - Action Pills Row

    @ViewBuilder
    private func actionPillsRow(_ detail: ShowDetail) -> some View {
        HStack(spacing: 10) {
            // Resume or Play from start
            if let progress = savedProgress, progress.positionMs > 0, !progress.finished {
                let currentTrack = detail.tracks.indices.contains(progress.trackIndex) ? detail.tracks[progress.trackIndex] : nil
                let remainingLabel = currentTrack != nil
                    ? formatRemainingTime(positionMs: Int64(progress.positionMs), durationMs: currentTrack!.durationMs)
                    : "Resume"

                Button {
                    player.play(detail: detail, startIndex: progress.trackIndex, resumePositionMs: Int64(progress.positionMs))
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "play.fill")
                            .font(.system(size: 13))
                        Text("Resume \(remainingLabel)")
                            .font(.system(size: 14, weight: .medium))
                    }
                    .frame(height: 38)
                    .padding(.horizontal, 16)
                    .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            } else {
                Button {
                    player.play(detail: detail, startIndex: 0)
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "play.fill")
                            .font(.system(size: 13))
                        Text("Play Show")
                            .font(.system(size: 14, weight: .medium))
                    }
                    .frame(height: 38)
                    .padding(.horizontal, 16)
                    .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0), lineWidth: 1)
                    )
                }
                .buttonStyle(.plain)
            }

            // Saved Toggle
            Button {
                isSaved.toggle()
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: isSaved ? "bookmark.fill" : "bookmark")
                        .font(.system(size: 14))
                    Text(isSaved ? "Saved" : "Save")
                        .font(.system(size: 14))
                }
                .frame(height: 38)
                .padding(.horizontal, 14)
                .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                )
            }
            .buttonStyle(.plain)

            // Add to Playlist
            AddToPlaylistButton {
                guard let firstTrack = detail.tracks.first else {
                    return LocalPlaylistTrack(
                        playlistId: "",
                        backend: show.artist.backend.rawValue,
                        trackId: "",
                        showDate: show.date,
                        artistSlug: show.artist.backend == .relisten ? show.artist.id : nil,
                        recordingId: detail.recording?.id,
                        title: show.where_,
                        durationMs: 0,
                        venueName: show.where_,
                        artUrl: nil
                    )
                }
                return LocalPlaylistTrack(
                    playlistId: "",
                    backend: show.artist.backend.rawValue,
                    trackId: firstTrack.id,
                    showDate: show.date,
                    artistSlug: show.artist.backend == .relisten ? show.artist.id : nil,
                    recordingId: detail.recording?.id,
                    title: firstTrack.title,
                    durationMs: firstTrack.durationMs,
                    venueName: firstTrack.venueName,
                    artUrl: firstTrack.artURL
                )
            }
            .frame(height: 38)
            .padding(.horizontal, 14)
            .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
            )
        }
    }

    // MARK: - Track Sets Section

    @ViewBuilder
    private func trackSetsSection(_ detail: ShowDetail, groups: [TrackGroup]) -> some View {
        if groups.count <= 1 {
            // Single Set
            ForEach(groups, id: \.setName) { group in
                setColumn(detail: detail, group: group, setIndex: 0)
            }
        } else {
            // Multi-set columns side by side
            HStack(alignment: .top, spacing: 28) {
                ForEach(Array(groups.enumerated()), id: \.element.setName) { index, group in
                    setColumn(detail: detail, group: group, setIndex: index)
                        .frame(maxWidth: .infinity)
                }
            }
        }
    }

    @ViewBuilder
    private func setColumn(detail: ShowDetail, group: TrackGroup, setIndex: Int) -> some View {
        let setMs = group.tracks.reduce(0) { $0 + $1.durationMs }
        let setName = group.setName.isEmpty ? "SET \(setIndex + 1)" : group.setName.uppercased()

        VStack(alignment: .leading, spacing: 0) {
            // Set Header
            HStack {
                Text(setName)
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(1.3)
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                Spacer()

                Text(formatCompactDuration(ms: setMs))
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
            }
            .padding(.bottom, 8)

            // Gradient Hairline
            setGradientHairline(setIndex: setIndex)
                .frame(height: 1)

            // Tracks
            VStack(spacing: 0) {
                ForEach(Array(group.tracks.enumerated()), id: \.element.id) { trackIdx, track in
                    let isPlaying = player.queueKey == detail.queueKey && player.currentTrack?.id == track.id
                    TrackTableRow(
                        trackNumber: trackIdx + 1,
                        track: track,
                        backend: show.artist.backend,
                        artistSlug: show.artist.backend == .relisten ? show.artist.id : nil,
                        recordingId: detail.recording?.id,
                        isPlaying: isPlaying
                    ) {
                        guard let globalIdx = detail.tracks.firstIndex(of: track) else { return }
                        player.play(detail: detail, startIndex: globalIdx)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func setGradientHairline(setIndex: Int) -> some View {
        if setIndex == 0 {
            LinearGradient(
                colors: [
                    Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0),
                    Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.45),
                    Color.clear
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
        } else {
            LinearGradient(
                colors: [
                    Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0),
                    Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0).opacity(0.45),
                    Color.clear
                ],
                startPoint: .leading,
                endPoint: .trailing
            )
        }
    }

    // MARK: - Source Helpers

    private func hasRealAlternates(_ detail: ShowDetail) -> Bool {
        show.artist.hasMultipleSources && !detail.alternates.isEmpty
    }

    private func allSources(_ detail: ShowDetail) -> [RecordingRef] {
        [detail.recording].compactMap { $0 } + detail.alternates
    }

    private func selectSource(_ recordingID: String) {
        showSourcePicker = false
        guard recordingID != detail?.recording?.id else { return }
        Task { await switchSource(to: recordingID) }
    }

    private func switchSource(to recordingID: String) async {
        let queueKeyBeforeSwitch = detail?.queueKey
        await load(recordingID: recordingID)

        guard let detail, let queueKeyBeforeSwitch, player.queueKey == queueKeyBeforeSwitch else { return }
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
                    if source.hasFlac {
                        sourceBadge("FLAC", color: .green)
                    } else {
                        sourceBadge("MP3", color: .secondary)
                    }
                    if source.isSoundboard {
                        sourceBadge("SBD", color: .accentColor)
                    }
                    // The "?" is deliberate — looksLikeMatrix is a text heuristic, not a
                    // guaranteed signal (Relisten has no structured matrix flag).
                    if source.looksLikeMatrix {
                        sourceBadge("Matrix?", color: .purple)
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
}

private func sourceBadge(_ text: String, color: Color) -> some View {
    Text(text)
        .font(.caption2)
        .fontWeight(.bold)
        .padding(.horizontal, 6)
        .padding(.vertical, 2)
        .background(color.opacity(0.15), in: RoundedRectangle(cornerRadius: 4))
        .foregroundStyle(color)
}

// MARK: - Track Table Row (Screen 2C Specification)

private struct TrackTableRow: View {
    let trackNumber: Int
    let track: PlayableTrack
    let backend: Backend
    let artistSlug: String?
    let recordingId: String?
    let isPlaying: Bool
    let onTap: () -> Void

    @Environment(\.ledgerColors) private var colors

    private var isJamChart: Bool {
        track.tags.contains { $0.name.localizedCaseInsensitiveContains("jam") } ||
        track.title.localizedCaseInsensitiveContains("tweezer") ||
        track.title.localizedCaseInsensitiveContains("gin") ||
        track.title.localizedCaseInsensitiveContains("ghost")
    }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 14) {
                // Track Number
                Text("\(trackNumber)")
                    .font(.system(size: 13))
                    .foregroundStyle(isPlaying ? Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0) : Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                    .frame(width: 26, alignment: .trailing)

                // Track Title + Badges
                HStack(spacing: 8) {
                    Text(track.title)
                        .font(.system(size: 15, weight: isPlaying ? .medium : .regular))
                        .foregroundStyle(isPlaying ? Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0) : colors.textPrimary)
                        .lineLimit(1)

                    if isJamChart {
                        Text("JAM CHART")
                            .font(.system(size: 9, weight: .semibold))
                            .tracking(0.9)
                            .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                            .padding(.horizontal, 5)
                            .padding(.vertical, 1)
                            .overlay(
                                RoundedRectangle(cornerRadius: 4)
                                    .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0).opacity(0.45), lineWidth: 1)
                            )
                    }

                    if isPlaying {
                        Text("PLAYING")
                            .font(.system(size: 9, weight: .semibold))
                            .tracking(1.1)
                            .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                    }
                }

                Spacer()

                // Duration
                Text(fmt(track.durationMs))
                    .font(.system(size: 13))
                    .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                    .frame(width: 52, alignment: .trailing)

                // Dots Menu
                Menu {
                    Button("Play Track", action: onTap)
                    TrackLikeButton(backend: backend, trackID: track.id, likesCount: track.likesCount, likedByUser: track.likedByUser)
                    AddToPlaylistButton {
                        LocalPlaylistTrack(
                            playlistId: "", backend: backend.rawValue, trackId: track.id,
                            showDate: track.showDate ?? "", artistSlug: artistSlug, recordingId: recordingId,
                            title: track.title, durationMs: track.durationMs, venueName: track.venueName, artUrl: track.artURL
                        )
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 13))
                        .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                        .frame(width: 30, height: 30, alignment: .trailing)
                }
                .menuStyle(.borderlessButton)
            }
            .padding(.vertical, 9)
            .padding(.horizontal, isPlaying ? 8 : 0)
            .background(
                Group {
                    if isPlaying {
                        ZStack(alignment: .leading) {
                            Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.28)
                            Rectangle()
                                .fill(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
                                .frame(width: 3)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                    } else {
                        Color.clear
                    }
                }
            )
            .overlay(
                VStack {
                    Spacer()
                    Divider().overlay(Color(red: 0x23 / 255.0, green: 0x25 / 255.0, blue: 0x32 / 255.0))
                }
            )
        }
        .buttonStyle(.plain)
    }
}
