import CouchTourKit
import SwiftUI

/// Right player rail (392px fixed) for macOS 3-pane desktop layout (Screen 2A).
/// Embeds ambient blur wash, 344x344 artwork tile with conic glow,
/// Tape FLAC and Show rating row, track block with Jam Chart note card,
/// 64px waveform scrubber, and 5-item transport row with 72x72 circle play button.
struct PlayerRailView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.ledgerColors) private var colors

    @State private var dragPositionMs: Double?
    @State private var showJamChartNote: Bool = true

    var body: some View {
        ZStack(alignment: .top) {
            // Ambient Radial Blur Wash (Dark Mode)
            if colors.isDark {
                ZStack {
                    RadialGradient(
                        colors: [Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0).opacity(0.45), Color.clear],
                        center: UnitPoint(x: 0.22, y: 0.30),
                        startRadius: 0,
                        endRadius: 180
                    )
                    RadialGradient(
                        colors: [Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0).opacity(0.40), Color.clear],
                        center: UnitPoint(x: 0.80, y: 0.20),
                        startRadius: 0,
                        endRadius: 180
                    )
                    RadialGradient(
                        colors: [Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0).opacity(0.28), Color.clear],
                        center: UnitPoint(x: 0.55, y: 0.62),
                        startRadius: 0,
                        endRadius: 200
                    )
                }
                .frame(width: 440, height: 460)
                .blur(radius: 30)
                .offset(y: -40)
                .allowsHitTesting(false)
            }

            VStack(alignment: .leading, spacing: 0) {
                // Header: NOW PLAYING + Expand Button
                HStack {
                    Text("NOW PLAYING")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(1.4)
                        .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

                    Spacer()

                    Button {
                        appModel.showNowPlaying = true
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "arrow.up.left.and.arrow.down.right")
                                .font(.system(size: 10))
                            Text("Expand")
                                .font(.system(size: 12))
                        }
                        .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                        .padding(.horizontal, 10)
                        .frame(height: 28)
                        .overlay(
                            Capsule().stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Expand Now Playing to fill the window")
                }
                .padding(.horizontal, 20)
                .padding(.top, 14)

                if let show = player.show {
                    // Artwork (344x344 with Conic Glow)
                    ConicGlowArtwork(
                        url: player.artURL,
                        artist: show.artist.name,
                        date: show.date,
                        size: 344,
                        cornerRadius: 14,
                        glowPadding: 14,
                        blurRadius: 24
                    )
                    .padding(.horizontal, 24)
                    .padding(.top, 22)

                    // Show Metadata Block
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 10) {
                            Text(show.artist.name)
                                .font(.system(size: 24, weight: .medium))
                                .foregroundStyle(colors.textPrimary)
                            Text(formatShowDate(show.date))
                                .font(.system(size: 24, weight: .medium))
                                .foregroundStyle(colors.textPrimary)
                        }

                        Text(show.where_.isEmpty ? "Live Concert" : show.where_)
                            .font(.system(size: 14))
                            .foregroundStyle(colors.textSecondary)
                            .padding(.top, 5)

                        let tapeLabel: String = {
                            if let rec = player.recording {
                                var parts: [String] = []
                                parts.append(rec.isSoundboard ? "SBD" : "AUD")
                                if let taper = rec.taper, !taper.isEmpty {
                                    parts.append(taper)
                                }
                                if rec.hasFlac {
                                    parts.append("FLAC")
                                }
                                return parts.joined(separator: " · ")
                            }
                            let isSbd = show.tags.contains { $0.name.localizedCaseInsensitiveContains("sbd") }
                            let hasFlac = player.currentTrack?.flacUrl?.isEmpty == false
                            var parts: [String] = []
                            parts.append(isSbd ? "SBD" : "AUD")
                            if hasFlac { parts.append("FLAC") }
                            return parts.joined(separator: " · ")
                        }()

                        // TAPE & SHOW RATING Row
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("TAPE")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                                HStack(spacing: 6) {
                                    Text(tapeLabel)
                                        .font(.system(size: 14))
                                        .foregroundStyle(colors.textPrimary)
                                        .lineLimit(1)
                                    Image(systemName: "chevron.down")
                                        .font(.system(size: 10))
                                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                }
                            }

                            Spacer()

                            VStack(alignment: .trailing, spacing: 2) {
                                Text("SHOW RATING")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                                if show.rating > 0 {
                                    Text(String(format: "★ %.1f", show.rating))
                                        .font(.system(size: 14))
                                        .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                                } else {
                                    Text("—")
                                        .font(.system(size: 14))
                                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                }
                            }
                        }
                        .padding(.top, 14)
                        .overlay(
                            Rectangle()
                                .fill(colors.divider)
                                .frame(height: 1),
                            alignment: .top
                        )
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 22)

                    Spacer(minLength: 16)

                    // Track Block (Eyebrow, Title, Jam Chart Pill, Note Card)
                    VStack(alignment: .leading, spacing: 0) {
                        let currentIdx = (player.currentIndex ?? 0) + 1
                        Text("TRACK \(currentIdx)")
                            .font(.system(size: 10, weight: .semibold))
                            .tracking(1.6)
                            .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

                        Text(player.currentTrack?.title ?? "—")
                            .font(.system(size: 23, weight: .medium))
                            .foregroundStyle(colors.textPrimary)
                            .padding(.top, 4)
                            .lineLimit(1)

                        let isJamChart = player.currentTrack?.tags.contains { $0.name.localizedCaseInsensitiveContains("jam") } == true

                        HStack(spacing: 6) {
                            if isJamChart {
                                Button {
                                    showJamChartNote.toggle()
                                } label: {
                                    HStack(spacing: 4) {
                                        Text("JAM CHART")
                                            .font(.system(size: 10, weight: .semibold))
                                            .tracking(1.0)
                                        Image(systemName: "chevron.down")
                                            .font(.system(size: 8))
                                    }
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 3)
                                    .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 4)
                                            .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0).opacity(0.45), lineWidth: 1)
                                    )
                                }
                                .buttonStyle(.plain)
                            }

                            if let dur = player.currentTrack?.durationMs, dur > 0 {
                                Text(formatCompactDuration(ms: dur))
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.0)
                                    .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 3)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 4)
                                            .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                                    )
                            }
                        }
                        .padding(.top, 10)

                        if isJamChart && showJamChartNote {
                            JamChartNoteCard(
                                note: "Jam chart entry available for this track.",
                                onDismiss: { showJamChartNote = false }
                            )
                            .padding(.top, 10)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 16)

                    // Waveform Scrubber
                    let duration = Double(player.currentTrack?.durationMs ?? 0)
                    let currentPos = dragPositionMs ?? Double(player.positionMs)
                    let progressFrac = duration > 0 ? (currentPos / duration) : 0.0

                    WaveformScrubber(progressFraction: progressFrac) { seekFrac in
                        dragPositionMs = nil
                        let targetMs = Int64(seekFrac * duration)
                        player.seek(toMs: targetMs)
                    }
                    .frame(height: 64)
                    .padding(.horizontal, 24)

                    // Timestamps
                    HStack {
                        Text(fmt(Int64(currentPos)))
                            .font(.system(size: 12))
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                        Spacer()
                        let remaining = max(Int64(duration - currentPos), 0)
                        Text("-\(fmt(remaining))")
                            .font(.system(size: 12))
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 6)

                    // 5-Item Transport Controls
                    HStack(spacing: 10) {
                        if let currentTrack = player.currentTrack {
                            TrackLikeButton(
                                backend: show.artist.backend,
                                trackID: currentTrack.id,
                                likesCount: currentTrack.likesCount,
                                likedByUser: currentTrack.likedByUser
                            )
                            .frame(width: 58, height: 58)
                        } else {
                            Spacer().frame(width: 58, height: 58)
                        }

                        // Previous button
                        Button {
                            player.skipToPrevious()
                        } label: {
                            Image(systemName: "backward.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                                .frame(width: 58, height: 58)
                        }
                        .buttonStyle(.plain)

                        // 72x72 Filled Circle Play/Pause Button
                        Button {
                            player.togglePlayPause()
                        } label: {
                            Circle()
                                .fill(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                                .frame(width: 72, height: 72)
                                .overlay(
                                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                        .font(.system(size: 26))
                                        .foregroundStyle(Color(red: 0x16 / 255.0, green: 0x18 / 255.0, blue: 0x26 / 255.0))
                                )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

                        // Next button
                        Button {
                            player.skipToNext()
                        } label: {
                            Image(systemName: "forward.fill")
                                .font(.system(size: 22))
                                .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                                .frame(width: 58, height: 58)
                        }
                        .buttonStyle(.plain)

                        // Add to playlist button
                        if let currentTrack = player.currentTrack {
                            AddToPlaylistButton {
                                LocalPlaylistTrack(
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
                                )
                            }
                            .frame(width: 58, height: 58)
                        } else {
                            Spacer().frame(width: 58, height: 58)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, 16)
                    .padding(.bottom, 26)

                } else {
                    // Empty state fallback when no show is loaded
                    fallbackRailContent
                }
            }
        }
        .frame(width: 392)
        .background(colors.elevated)
        .border(width: 1, edges: [.leading], color: colors.panelBorder)
    }

    @ViewBuilder
    private var fallbackRailContent: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "music.note")
                .font(.system(size: 40))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

            Text("Nothing playing")
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(colors.textPrimary)

            Text("Select a show or track to start listening.")
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
    }
}

private extension View {
    func border(width: CGFloat, edges: [Edge], color: Color) -> some View {
        overlay(EdgeBorder(width: width, edges: edges).foregroundColor(color))
    }
}

private struct EdgeBorder: Shape {
    var width: CGFloat
    var edges: [Edge]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        for edge in edges {
            var x: CGFloat {
                switch edge {
                case .top, .bottom, .leading: return rect.minX
                case .trailing: return rect.maxX - width
                }
            }
            var y: CGFloat {
                switch edge {
                case .top, .leading, .trailing: return rect.minY
                case .bottom: return rect.maxY - width
                }
            }
            var w: CGFloat {
                switch edge {
                case .top, .bottom: return rect.width
                case .leading, .trailing: return width
                }
            }
            var h: CGFloat {
                switch edge {
                case .top, .bottom: return width
                case .leading, .trailing: return rect.height
                }
            }
            path.addRect(CGRect(x: x, y: y, width: w, height: h))
        }
        return path
    }
}
