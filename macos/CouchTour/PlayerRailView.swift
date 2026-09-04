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
                                .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                            Text(formatShowDate(show.date))
                                .font(.system(size: 24, weight: .medium))
                                .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                        }

                        Text(show.where_.isEmpty ? "Live Concert" : show.where_)
                            .font(.system(size: 14))
                            .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                            .padding(.top, 5)

                        // TAPE & SHOW RATING Row
                        HStack(alignment: .center, spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("TAPE")
                                    .font(.system(size: 10, weight: .semibold))
                                    .tracking(1.4)
                                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                                HStack(spacing: 6) {
                                    Text("SBD · Paluska · FLAC")
                                        .font(.system(size: 14))
                                        .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
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

                                Text("★ 4.6")
                                    .font(.system(size: 14))
                                    .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                            }
                        }
                        .padding(.top, 14)
                        .overlay(
                            Rectangle()
                                .fill(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0).opacity(0.14))
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
                        Text("SET II · TRACK \(currentIdx)")
                            .font(.system(size: 10, weight: .semibold))
                            .tracking(1.6)
                            .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

                        Text(player.currentTrack?.title ?? "Bathtub Gin")
                            .font(.system(size: 23, weight: .medium))
                            .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                            .padding(.top, 4)
                            .lineLimit(1)

                        HStack(spacing: 6) {
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

                            Text(formatCompactDuration(ms: player.currentTrack?.durationMs ?? 764_000))
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
                        .padding(.top, 10)

                        if showJamChartNote {
                            JamChartNoteCard(
                                note: "The jam chart entry for this version loads here from phish.in, describing what makes the take notable and where it goes.",
                                onDismiss: { showJamChartNote = false }
                            )
                            .padding(.top, 10)
                        }
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 16)

                    // Waveform Scrubber
                    let duration = Double(player.currentTrack?.durationMs ?? 764_000)
                    let currentPos = dragPositionMs ?? Double(player.positionMs)
                    let progressFrac = duration > 0 ? (currentPos / duration) : 0.0

                    WaveformScrubber(progressFraction: progressFrac) { seekFrac in
                        dragPositionMs = nil
                        let targetMs = Int64(seekFrac * duration)
                        player.seek(toMs: targetMs)
                    }
                    .frame(height: 64)
                    .padding(.horizontal, 24)

                    // Timestamps: 5:12 and -7:32
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
                        // Heart with like count 268 underneath
                        VStack(spacing: 2) {
                            Image(systemName: "heart")
                                .font(.system(size: 22))
                                .foregroundStyle(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
                            Text("268")
                                .font(.system(size: 11))
                                .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                        }
                        .frame(width: 58, height: 58)

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
                        Button {} label: {
                            Image(systemName: "text.badge.plus")
                                .font(.system(size: 22))
                                .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                                .frame(width: 58, height: 58)
                        }
                        .buttonStyle(.plain)
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
        VStack(spacing: 0) {
            // Artwork placeholder
            ConicGlowArtwork(
                url: nil,
                artist: "Phish",
                date: "1997-11-17",
                size: 344,
                cornerRadius: 14,
                glowPadding: 14,
                blurRadius: 24
            )
            .padding(.horizontal, 24)
            .padding(.top, 22)

            // Metadata Block
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 10) {
                    Text("Phish")
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                    Text("1997-11-17")
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                }

                Text("Thomas & Mack Center, Las Vegas, NV")
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                    .padding(.top, 5)

                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("TAPE")
                            .font(.system(size: 10, weight: .semibold))
                            .tracking(1.4)
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                        HStack(spacing: 6) {
                            Text("SBD · Paluska · FLAC")
                                .font(.system(size: 14))
                                .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
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
                        Text("★ 4.6")
                            .font(.system(size: 14))
                            .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                    }
                }
                .padding(.top, 14)
                .overlay(
                    Rectangle()
                        .fill(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0).opacity(0.14))
                        .frame(height: 1),
                    alignment: .top
                )
            }
            .padding(.horizontal, 24)
            .padding(.top, 22)

            Spacer(minLength: 16)

            VStack(alignment: .leading, spacing: 0) {
                Text("SET II · TRACK 4")
                    .font(.system(size: 10, weight: .semibold))
                    .tracking(1.6)
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

                Text("Bathtub Gin")
                    .font(.system(size: 23, weight: .medium))
                    .foregroundStyle(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                    .padding(.top, 4)

                HStack(spacing: 6) {
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

                    Text("12:44")
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
                .padding(.top, 10)

                if showJamChartNote {
                    JamChartNoteCard(
                        note: "The jam chart entry for this version loads here from phish.in, describing what makes the take notable and where it goes.",
                        onDismiss: { showJamChartNote = false }
                    )
                    .padding(.top, 10)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)

            WaveformScrubber(progressFraction: 0.41) { _ in }
                .frame(height: 64)
                .padding(.horizontal, 24)

            HStack {
                Text("5:12")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                Spacer()
                Text("-7:32")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
            }
            .padding(.horizontal, 24)
            .padding(.top, 6)

            HStack(spacing: 10) {
                VStack(spacing: 2) {
                    Image(systemName: "heart")
                        .font(.system(size: 22))
                        .foregroundStyle(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
                    Text("268")
                        .font(.system(size: 11))
                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                }
                .frame(width: 58, height: 58)

                Image(systemName: "backward.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                    .frame(width: 58, height: 58)

                Circle()
                    .fill(Color(red: 0xF3 / 255.0, green: 0xF5 / 255.0, blue: 0xFE / 255.0))
                    .frame(width: 72, height: 72)
                    .overlay(
                        Image(systemName: "pause.fill")
                            .font(.system(size: 26))
                            .foregroundStyle(Color(red: 0x16 / 255.0, green: 0x18 / 255.0, blue: 0x26 / 255.0))
                    )

                Image(systemName: "forward.fill")
                    .font(.system(size: 22))
                    .foregroundStyle(Color(red: 0xE9 / 255.0, green: 0xE9 / 255.0, blue: 0xED / 255.0))
                    .frame(width: 58, height: 58)

                Image(systemName: "text.badge.plus")
                    .font(.system(size: 22))
                    .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                    .frame(width: 58, height: 58)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, 16)
            .padding(.bottom, 26)
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
