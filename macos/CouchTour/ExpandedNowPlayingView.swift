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
    @State private var showJamChartNote: Bool = true

    var body: some View {
        ZStack {
            // Background & Ambient Wash
            colors.background
                .ignoresSafeArea()

            if colors.isDark {
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

            VStack(spacing: 0) {
                // Window Chrome Bar / Collapse Button
                HStack {
                    TrafficLights()

                    Spacer()

                    Text("NOW PLAYING")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(1.4)
                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

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
                        .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                        .padding(.horizontal, 10)
                        .frame(height: 28)
                        .overlay(
                            Capsule().stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Collapse Now Playing")
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 12)

                let artistName = player.show?.artist.name ?? "Phish"
                let showDate = player.show?.date ?? "1997-11-17"
                let venueName = player.show?.where_.isEmpty == false ? player.show!.where_ : "Thomas & Mack Center, Las Vegas, NV"
                let trackTitle = player.currentTrack?.title ?? "Bathtub Gin"
                let duration = Double(player.currentTrack?.durationMs ?? 764_000)
                let currentPos = dragPositionMs ?? Double(player.positionMs)
                let progressFrac = duration > 0 ? (currentPos / duration) : 0.0

                Spacer()

                // Hero section: 2 columns
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
                        HStack(spacing: 14) {
                            Text(artistName)
                                .font(.system(size: 34, weight: .medium))
                                .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                            Text(formatShowDate(showDate))
                                .font(.system(size: 34, weight: .medium))
                                .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                        }

                        Text(venueName)
                            .font(.system(size: 17))
                            .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                            .padding(.top, 7)

                        // Tape / Show Rating / Set Row
                        HStack(alignment: .center, spacing: 26) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text("TAPE")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                HStack(spacing: 6) {
                                    Text("SBD · Paluska · FLAC")
                                        .font(.system(size: 15))
                                        .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                                    Image(systemName: "chevron.down")
                                        .font(.system(size: 10))
                                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                }
                            }

                            VStack(alignment: .leading, spacing: 3) {
                                Text("SHOW RATING")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                Text("★ 4.6")
                                    .font(.system(size: 15))
                                    .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                            }

                            VStack(alignment: .leading, spacing: 3) {
                                Text("SET")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                                Text("II · Track 4")
                                    .font(.system(size: 15))
                                    .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                            }
                        }
                        .padding(.top, 20)
                        .overlay(
                            Rectangle()
                                .fill(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0).opacity(0.14))
                                .frame(height: 1),
                            alignment: .top
                        )

                        // Track Title & Badges
                        VStack(alignment: .leading, spacing: 0) {
                            HStack(spacing: 12) {
                                Text(trackTitle)
                                    .font(.system(size: 44, weight: .medium))
                                    .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))

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

                            HStack(spacing: 8) {
                                Button {
                                    showJamChartNote.toggle()
                                } label: {
                                    HStack(spacing: 5) {
                                        Text("JAM CHART")
                                            .font(.system(size: 11, weight: .semibold))
                                            .tracking(1.0)
                                        Image(systemName: "chevron.down")
                                            .font(.system(size: 9))
                                    }
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 4)
                                            .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0).opacity(0.45), lineWidth: 1)
                                    )
                                }
                                .buttonStyle(.plain)

                                Text(formatCompactDuration(ms: player.currentTrack?.durationMs ?? 764_000))
                                    .font(.system(size: 11, weight: .semibold))
                                    .tracking(1.0)
                                    .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 3)
                                    .overlay(
                                        RoundedRectangle(cornerRadius: 4)
                                            .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                                    )
                            }
                            .padding(.top, 14)

                            if showJamChartNote {
                                JamChartNoteCard(
                                    note: "The jam chart entry for this version loads here from phish.in, describing what makes the take notable and where it goes.",
                                    onDismiss: { showJamChartNote = false }
                                )
                                .frame(maxWidth: 560)
                                .padding(.top, 14)
                            }
                        }
                        .padding(.top, 34)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(.horizontal, 72)

                Spacer()

                // Waveform Scrubber (110px tall)
                WaveformScrubber(progressFraction: progressFrac) { seekFrac in
                    dragPositionMs = nil
                    let targetMs = Int64(seekFrac * duration)
                    player.seek(toMs: targetMs)
                }
                .frame(height: 110)
                .padding(.horizontal, 72)

                // Timestamps: 5:12 and -7:32
                HStack {
                    Text(fmt(Int64(currentPos)))
                        .font(.system(size: 13))
                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                    Spacer()
                    let remaining = max(Int64(duration - currentPos), 0)
                    Text("-\(fmt(remaining))")
                        .font(.system(size: 13))
                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                }
                .padding(.horizontal, 72)
                .padding(.top, 8)

                // Transport Row (82x82 filled play button)
                HStack(spacing: 14) {
                    VStack(spacing: 2) {
                        Image(systemName: "heart")
                            .font(.system(size: 24))
                            .foregroundStyle(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
                        Text("268")
                            .font(.system(size: 11))
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                    }
                    .frame(width: 64, height: 64)

                    Button {
                        player.skipToPrevious()
                    } label: {
                        Image(systemName: "backward.fill")
                            .font(.system(size: 26))
                            .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                            .frame(width: 64, height: 64)
                    }
                    .buttonStyle(.plain)

                    Button {
                        player.togglePlayPause()
                    } label: {
                        Circle()
                            .fill(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                            .frame(width: 82, height: 82)
                            .overlay(
                                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                    .font(.system(size: 30))
                                    .foregroundStyle(Color(red: 0x16 / 255.0, green: 0x18 / 255.0, blue: 0x26 / 255.0))
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

                    Button {
                        player.skipToNext()
                    } label: {
                        Image(systemName: "forward.fill")
                            .font(.system(size: 26))
                            .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                            .frame(width: 64, height: 64)
                    }
                    .buttonStyle(.plain)

                    Button {} label: {
                        Image(systemName: "text.badge.plus")
                            .font(.system(size: 24))
                            .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                            .frame(width: 64, height: 64)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.top, 20)
                .padding(.bottom, 34)
            }
        }
        .frame(minWidth: 1000, minHeight: 700)
    }
}
