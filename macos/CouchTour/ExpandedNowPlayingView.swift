import AppKit
import CouchTourKit
import SwiftUI

/// Expanded full-window Now Playing screen (Screen 2D).
/// 1440x900 layout with ambient radial wash, top traffic lights,
/// 440x440 artwork tile with conic glow, TAPE/RATING/SET row,
/// 44px track title with FLAC badge, 110px tall waveform scrubber,
/// and 82x82 filled circular transport button.
struct ExpandedNowPlayingView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.ledgerColors) private var colors
    @Environment(\.dismiss) private var dismiss

    @State private var dragPositionMs: Double?
    @State private var showJamChartNote: Bool = false
    @State private var showComparePopover = false

    var body: some View {
        ZStack {
            colors.background
                .ignoresSafeArea()

            if colors.isDark {
                ambientWash
            }

            VStack(spacing: 0) {
                chromeBar
                Spacer()
                heroSection
                Spacer()
                scrubberSection
                timestampsRow
                transportRow
                volumeRow
            }
        }
        .frame(minWidth: 1000, minHeight: 700)
    }

    private var ambientWash: some View {
                ZStack {
                    RadialGradient(
                        colors: [Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0).opacity(0.50), Color.clear],
                        center: UnitPoint(x: 0.24, y: 0.26),
                        startRadius: 0,
                        endRadius: 400
                    )
                    RadialGradient(
                        colors: [Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0).opacity(0.45), Color.clear],
                        center: UnitPoint(x: 0.76, y: 0.20),
                        startRadius: 0,
                        endRadius: 400
                    )
                    RadialGradient(
                        colors: [Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0).opacity(0.28), Color.clear],
                        center: UnitPoint(x: 0.52, y: 0.76),
                        startRadius: 0,
                        endRadius: 450
                    )
                }
                .blur(radius: 40)
                .ignoresSafeArea()
    }

    private var chromeBar: some View {
                HStack {
                    TrafficLights()

                    Spacer()

                    Text("NOW PLAYING")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(1.4)
                        .foregroundStyle(colors.textMuted)

                    Spacer()

                    Button {
                        dismiss()
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.down.right.and.arrow.up.left")
                                .font(.system(size: 10))
                            Text("Collapse")
                                .font(.system(size: 12))
                        }
                        .foregroundStyle(colors.textSubtle)
                        .padding(.horizontal, 10)
                        .frame(height: 28)
                        .overlay(
                            Capsule().stroke(colors.controlOutline, lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Collapse Now Playing")
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 12)
    }

    private var artistName: String { player.show?.artist.name ?? "" }
    private var showDate: String { player.show?.date ?? "" }
    private var venueName: String { player.show?.where_.isEmpty == false ? player.show!.where_ : "" }
    private var trackTitle: String { player.currentTrack?.title ?? "No track playing" }
    private var duration: Double { Double(player.currentTrack?.durationMs ?? 0) }
    private var currentPos: Double { dragPositionMs ?? Double(player.positionMs) }
    private var progressFrac: Double { duration > 0 ? (currentPos / duration) : 0.0 }

    private var tapeLabel: String {
                    if let rec = player.recording {
                        var parts: [String] = []
                        parts.append(rec.isSoundboard ? "SBD" : "AUD")
                        if let taper = rec.taper, !taper.isEmpty {
                            parts.append(taper)
                        }
                        if rec.hasFlac && player.audioQuality == .lossless {
                            parts.append("FLAC")
                        }
                        return parts.joined(separator: " · ")
                    }
                    if let show = player.show {
                        let isSbd = show.tags.contains { $0.name.localizedCaseInsensitiveContains("sbd") }
                        let hasFlac = player.playsFlac(player.currentTrack)
                        var parts: [String] = []
                        parts.append(isSbd ? "SBD" : "AUD")
                        if hasFlac { parts.append("FLAC") }
                        return parts.joined(separator: " · ")
                    }
                    return "SBD"
    }

    private var heroSection: some View {
                HStack(alignment: .center, spacing: 56) {
                    // Left: 440x440 artwork with conic glow
                    ConicGlowArtwork(
                        url: player.artURL,
                        artist: artistName,
                        date: showDate,
                        size: 440,
                        cornerRadius: 16,
                        glowPadding: 22,
                        blurRadius: 34
                    )

                    // Right: Metadata + Title + Tape + Jam Chart Note
                    VStack(alignment: .leading, spacing: 0) {
                        headerText
                        tapeRatingSetRow
                        trackTitleBlock
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 72)
    }

    private var headerText: some View {
        VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 14) {
                            Text(artistName)
                                .font(.system(size: 34, weight: .medium))
                                .foregroundStyle(colors.textPrimary)
                            Text(formatShowDate(showDate))
                                .font(.system(size: 34, weight: .medium))
                                .foregroundStyle(colors.textPrimary)
                        }

                        Text(venueName)
                            .font(.system(size: 17))
                            .foregroundStyle(colors.textSecondary)
                            .padding(.top, 7)
        }
    }

    private var tapeRatingSetRow: some View {
                        HStack(alignment: .center, spacing: 26) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("TAPE")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(colors.textMuted)
                                HStack(spacing: 6) {
                                    Text(tapeLabel)
                                        .font(.system(size: 15))
                                        .foregroundStyle(colors.textPrimary)
                                    Image(systemName: "chevron.down")
                                        .font(.system(size: 10))
                                        .foregroundStyle(colors.textMuted)

                                    if player.hasRealAlternates {
                                        Button {
                                            if let currentTrack = player.currentTrack {
                                                let allSources = ([player.recording].compactMap { $0 } + player.alternates).sorted { $0.rating > $1.rating }
                                                player.enterCompareSourcesMode(sources: allSources, currentTrack: currentTrack, positionMs: player.positionMs)
                                                showComparePopover = true
                                            }
                                        } label: {
                                            Text("Compare")
                                                .font(.system(size: 9, weight: .semibold))
                                                .foregroundStyle(colors.accentTintText)
                                                .padding(.horizontal, 6)
                                                .padding(.vertical, 2)
                                                .overlay(RoundedRectangle(cornerRadius: 4).stroke(colors.accentIcon.opacity(0.45), lineWidth: 1))
                                        }
                                        .buttonStyle(.plain)
                                        .popover(isPresented: $showComparePopover) {
                                            CompareSourcesView()
                                        }
                                    }
                                }
                            }

                            VStack(alignment: .leading, spacing: 3) {
                                Text("SHOW RATING")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                if let rating = player.show?.rating, rating > 0 {
                                    Text(String(format: "★ %.1f", rating))
                                        .font(.system(size: 15))
                                        .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                                } else {
                                    Text("—")
                                        .font(.system(size: 15))
                                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                }
                            }

                            VStack(alignment: .leading, spacing: 3) {
                                Text("SET")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(colors.textMuted)
                                let currentIdx = (player.currentIndex ?? 0) + 1
                                let setCol = formatSetColumn(
                                    setName: player.currentTrack?.setName,
                                    trackPosition: player.currentTrack?.position,
                                    fallbackIndex: currentIdx
                                )
                                Text(setCol)
                                    .font(.system(size: 15))
                                    .foregroundStyle(colors.textPrimary)
                            }
                        }
                        .padding(.top, 20)
                        .overlay(
                            Rectangle()
                                .fill(colors.divider)
                                .frame(height: 1),
                            alignment: .top
                        )
    }

    private var trackTitleBlock: some View {
                        VStack(alignment: .leading, spacing: 0) {
                            let currentIdx = (player.currentIndex ?? 0) + 1
                            let eyebrow = formatSetAndTrackEyebrow(
                                setName: player.currentTrack?.setName,
                                trackPosition: player.currentTrack?.position,
                                fallbackIndex: currentIdx
                            )
                            Text(eyebrow)
                                .font(.system(size: 10, weight: .semibold))
                                .tracking(1.6)
                                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                                .padding(.bottom, 4)

                            HStack(spacing: 12) {
                                Text(trackTitle)
                                    .font(.system(size: 44, weight: .medium))
                                    .foregroundStyle(colors.textPrimary)

                                if player.playsFlac(player.currentTrack) {
                                    Text("FLAC")
                                        .font(.system(size: 11, weight: .semibold))
                                        .tracking(1.0)
                                        .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                                        .padding(.horizontal, 7)
                                        .padding(.vertical, 2)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 4)
                                                .stroke(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0).opacity(0.5), lineWidth: 1)
                                        )
                                }
                            }

                            let jamChartTag = player.currentTrack?.tags.first { $0.name.localizedCaseInsensitiveContains("jam") }
                            let isJamChart = jamChartTag != nil
                            let jamChartNotes = jamChartTag?.notes?.trimmingCharacters(in: .whitespacesAndNewlines)

                            HStack(spacing: 8) {
                                if isJamChart {
                                    Button {
                                        showJamChartNote.toggle()
                                    } label: {
                                        HStack(spacing: 5) {
                                            Text("JAM CHART")
                                                .font(.system(size: 11, weight: .semibold))
                                                .tracking(1.0)
                                            Image(systemName: showJamChartNote ? "chevron.up" : "chevron.down")
                                                .font(.system(size: 9))
                                        }
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .foregroundStyle(colors.accentTintText)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 4)
                                                .stroke(colors.accentIcon.opacity(0.45), lineWidth: 1)
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }

                                if let dur = player.currentTrack?.durationMs, dur > 0 {
                                    Text(formatCompactDuration(ms: dur))
                                        .font(.system(size: 11, weight: .semibold))
                                        .tracking(1.0)
                                        .foregroundStyle(colors.textSubtle)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 3)
                                        .overlay(
                                            RoundedRectangle(cornerRadius: 4)
                                                .stroke(colors.controlOutline, lineWidth: 1)
                                        )
                                }
                            }
                            .padding(.top, 14)

                            if isJamChart && showJamChartNote, let note = jamChartNotes, !note.isEmpty {
                                VStack(alignment: .leading, spacing: 8) {
                                    JamChartNoteCard(
                                        note: note,
                                        onDismiss: { showJamChartNote = false }
                                    )

                                    let date = player.show?.date ?? player.currentTrack?.showDate
                                    if let date, let slug = player.currentTrack?.slug,
                                       let url = URL(string: trackSourceUrl(date: date, slug: slug)) {
                                        Button {
                                            NSWorkspace.shared.open(url)
                                        } label: {
                                            HStack(spacing: 4) {
                                                Text("View on phish.in")
                                                Image(systemName: "arrow.up.right")
                                                    .font(.system(size: 10, weight: .semibold))
                                            }
                                            .font(.system(size: 11, weight: .medium))
                                            .foregroundStyle(colors.accent)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .frame(maxWidth: 560, alignment: .leading)
                                .padding(.top, 14)
                            }
                        }
                        .padding(.top, 34)
    }

    private var scrubberSection: some View {
        WaveformScrubber(
                    progressFraction: progressFrac,
                    waveformURL: player.currentTrack?.waveformURL
                ) { seekFrac in
                    dragPositionMs = nil
                    let targetMs = Int64(seekFrac * duration)
                    player.seek(toMs: targetMs)
                }
                .frame(height: 110)
                .padding(.horizontal, 72)
    }

    private var timestampsRow: some View {
                HStack {
                    Text(fmt(Int64(currentPos)))
                        .font(.system(size: 13))
                        .foregroundStyle(colors.textMuted)
                    Spacer()
                    let remaining = max(Int64(duration - currentPos), 0)
                    Text("-\(fmt(remaining))")
                        .font(.system(size: 13))
                        .foregroundStyle(colors.textMuted)
                }
                .padding(.horizontal, 72)
                .padding(.top, 8)
    }

    private var transportRow: some View {
                HStack(spacing: 14) {
                    if let show = player.show, let currentTrack = player.currentTrack {
                        TrackLikeButton(
                            backend: show.artist.backend,
                            trackID: currentTrack.id,
                            likesCount: currentTrack.likesCount,
                            likedByUser: currentTrack.likedByUser
                        )
                        .frame(width: 64, height: 64)
                    } else {
                        Spacer().frame(width: 64, height: 64)
                    }

                    Button {
                        player.skipToPrevious()
                    } label: {
                        Image(systemName: "backward.fill")
                            .font(.system(size: 26))
                            .foregroundStyle(colors.textPrimary)
                            .frame(width: 64, height: 64)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Previous track")

                    Button {
                        player.togglePlayPause()
                    } label: {
                        Circle()
                            .fill(colors.textPrimary)
                            .frame(width: 82, height: 82)
                            .overlay(
                                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                    .font(.system(size: 30))
                                    .foregroundStyle(colors.background)
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

                    Button {
                        player.skipToNext()
                    } label: {
                        Image(systemName: "forward.fill")
                            .font(.system(size: 26))
                            .foregroundStyle(colors.textPrimary)
                            .frame(width: 64, height: 64)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Next track")

                    if let show = player.show, let currentTrack = player.currentTrack {
                        AddToPlaylistButton {
                            [LocalPlaylistTrack(
                                playlistId: "",
                                backend: show.artist.backend.rawValue,
                                trackId: currentTrack.id,
                                showDate: show.date,
                                artistSlug: show.artist.backend == .relisten ? show.artist.id : nil,
                                recordingId: nil,
                                title: currentTrack.title,
                                durationMs: currentTrack.durationMs,
                                venueName: show.where_,
                                artUrl: currentTrack.artURL
                            )]
                        }
                        .frame(width: 64, height: 64)
                    } else {
                        Spacer().frame(width: 64, height: 64)
                    }
                }
                .padding(.top, 20)
    }

    private var volumeRow: some View {
                HStack(spacing: 20) {
                    volumeControl
                    CastRoutePickerButton()
                }
                .padding(.top, 8)
                .padding(.bottom, 34)
    }

    
    private var volumeControl: some View {
        HStack(spacing: 6) {
            Button {
                player.toggleMute()
            } label: {
                Image(systemName: volumeSymbol)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(player.volume == 0 ? "Unmute" : "Mute")

            Slider(value: $player.volume, in: 0...1)
                .frame(width: 90)
                .accessibilityLabel("Volume")
        }
        .foregroundStyle(colors.textMuted)
    }

    private var volumeSymbol: String {
        switch player.volume {
        case 0: return "speaker.slash.fill"
        case ..<0.34: return "speaker.wave.1.fill"
        case ..<0.67: return "speaker.wave.2.fill"
        default: return "speaker.wave.3.fill"
        }
    }
}