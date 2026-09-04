import CouchTourKit
import SwiftUI

/// Expanded full-window Now Playing screen (Screen 2D).
/// Ambient background wash, large 240px cover-art tile with glow, comprehensive tape lineage,
/// vector waveform scrubber, full transport, and collapse affordance back to mini rail.
struct ExpandedNowPlayingView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.ledgerColors) private var colors
    @Environment(\.dismiss) private var dismiss

    @State private var dragPositionMs: Double?

    var body: some View {
        ZStack {
            // Ambient wash background
            colors.background
                .ignoresSafeArea()

            if colors.isDark {
                RadialGradient(
                    gradient: Gradient(colors: [
                        Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0).opacity(0.12),
                        Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.08),
                        Color.clear
                    ]),
                    center: .topLeading,
                    startRadius: 100,
                    endRadius: 700
                )
                .ignoresSafeArea()
            }

            VStack(spacing: 0) {
                // Window Chrome Bar / Collapse button
                HStack {
                    Button {
                        dismiss()
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "chevron.down")
                                .font(.system(size: 12, weight: .semibold))
                            Text("Collapse")
                                .font(.system(size: 13, weight: .medium))
                        }
                        .foregroundStyle(colors.textSecondary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(colors.surface, in: RoundedRectangle(cornerRadius: 16))
                    }
                    .buttonStyle(.plain)

                    Spacer()

                    Text("NOW PLAYING")
                        .font(.system(size: 11, weight: .bold))
                        .tracking(1.4)
                        .foregroundStyle(colors.textMuted)

                    Spacer()

                    CastRoutePickerButton()
                }
                .padding(.horizontal, 24)
                .padding(.top, 20)
                .padding(.bottom, 16)

                if let show = player.show {
                    ScrollView {
                        VStack(spacing: 24) {
                            // Hero section: Large artwork + Title block
                            HStack(alignment: .top, spacing: 32) {
                                // 240px Artwork with glow
                                ZStack {
                                    if colors.isDark {
                                        RoundedRectangle(cornerRadius: 16)
                                            .fill(LedgerTheme.specGradient)
                                            .frame(width: 248, height: 248)
                                            .blur(radius: 20)
                                            .opacity(0.35)
                                    }

                                    ArtworkView(
                                        url: player.artURL,
                                        artist: show.artist.name,
                                        date: show.date,
                                        size: 240
                                    )
                                    .clipShape(RoundedRectangle(cornerRadius: 12))
                                }

                                // Metadata Block
                                VStack(alignment: .leading, spacing: 8) {
                                    HStack(spacing: 8) {
                                        Text(show.artist.name)
                                            .font(.system(size: 18, weight: .medium))
                                            .foregroundStyle(colors.textSecondary)

                                        Text("·")
                                            .foregroundStyle(colors.textMuted)

                                        Text(show.date)
                                            .font(.system(size: 18, weight: .bold))
                                            .foregroundStyle(colors.textPrimary)

                                        if let track = player.currentTrack {
                                            StatusPill.codec(isFlac: track.flacUrl?.isEmpty == false)
                                        }
                                    }

                                    Text(player.currentTrack?.title ?? "—")
                                        .font(.system(size: 32, weight: .bold))
                                        .foregroundStyle(colors.textPrimary)

                                    if !show.where_.isEmpty {
                                        Text(show.where_)
                                            .font(.system(size: 14))
                                            .foregroundStyle(colors.textMuted)
                                    }

                                    HStack(spacing: 12) {
                                        Text("★ 4.5 rating")
                                            .font(.system(size: 13, weight: .medium))
                                            .foregroundStyle(colors.ratingAmber)

                                        if let track = player.currentTrack {
                                            TrackLikeButton(
                                                backend: show.artist.backend,
                                                trackID: track.id,
                                                likesCount: track.likesCount,
                                                likedByUser: track.likedByUser
                                            )
                                        }
                                    }
                                    .padding(.top, 4)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .padding(.horizontal, 40)
                            .padding(.top, 16)

                            // Waveform Scrubber
                            VStack(spacing: 6) {
                                let duration = Double(player.currentTrack?.durationMs ?? 0)
                                let currentPos = dragPositionMs ?? Double(player.positionMs)
                                let progressFraction = duration > 0 ? (currentPos / duration) : 0.0

                                WaveformScrubber(progressFraction: progressFraction) { seekFraction in
                                    dragPositionMs = nil
                                    let targetMs = Int64(seekFraction * duration)
                                    player.seek(toMs: targetMs)
                                }

                                HStack {
                                    Text(fmt(Int64(currentPos)))
                                        .font(.system(size: 12))
                                        .foregroundStyle(colors.textMuted)
                                    Spacer()
                                    Text(fmt(Int64(duration)))
                                        .font(.system(size: 12))
                                        .foregroundStyle(colors.textMuted)
                                }
                            }
                            .padding(.horizontal, 40)

                            // Full Transport Row
                            HStack(spacing: 36) {
                                Button {
                                    player.skipToPrevious()
                                } label: {
                                    Image(systemName: "backward.fill")
                                        .font(.system(size: 22))
                                        .foregroundStyle(colors.textPrimary)
                                }
                                .buttonStyle(.plain)
                                .disabled((player.currentIndex ?? 0) == 0)

                                Button {
                                    player.togglePlayPause()
                                } label: {
                                    ZStack {
                                        Circle()
                                            .fill(colors.accent)
                                            .frame(width: 56, height: 56)
                                        Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                            .font(.system(size: 24))
                                            .foregroundStyle(Color.white)
                                    }
                                }
                                .buttonStyle(.plain)

                                Button {
                                    player.skipToNext()
                                } label: {
                                    Image(systemName: "forward.fill")
                                        .font(.system(size: 22))
                                        .foregroundStyle(colors.textPrimary)
                                }
                                .buttonStyle(.plain)
                                .disabled((player.currentIndex ?? -1) >= player.tracks.count - 1)
                            }
                            .padding(.vertical, 8)

                            Divider().overlay(colors.divider)

                            // Queue list
                            VStack(alignment: .leading, spacing: 10) {
                                Text("SHOW QUEUE")
                                    .font(.system(size: 11, weight: .bold))
                                    .tracking(1.2)
                                    .foregroundStyle(colors.textMuted)
                                    .padding(.horizontal, 40)

                                LazyVStack(spacing: 2) {
                                    ForEach(player.tracks.indices, id: \.self) { index in
                                        let track = player.tracks[index]
                                        let isCurrent = index == player.currentIndex

                                        Button {
                                            player.seek(toTrack: index)
                                        } label: {
                                            HStack {
                                                Text("\(index + 1)")
                                                    .font(.system(size: 12))
                                                    .foregroundStyle(isCurrent ? colors.accent : colors.textMuted)
                                                    .frame(width: 24, alignment: .leading)

                                                Text(track.title)
                                                    .font(.system(size: 14, weight: isCurrent ? .semibold : .regular))
                                                    .foregroundStyle(isCurrent ? colors.accent : colors.textPrimary)

                                                Spacer()

                                                Text(fmt(track.durationMs))
                                                    .font(.system(size: 12))
                                                    .foregroundStyle(colors.textMuted)
                                            }
                                            .padding(.horizontal, 20)
                                            .padding(.vertical, 8)
                                            .background(
                                                isCurrent ? colors.surface : Color.clear,
                                                in: RoundedRectangle(cornerRadius: 6)
                                            )
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.horizontal, 20)
                            }
                        }
                        .padding(.bottom, 32)
                    }
                } else {
                    Spacer()
                    Text("No show currently playing.")
                        .foregroundStyle(colors.textMuted)
                    Spacer()
                }
            }
        }
        .frame(minWidth: 800, minHeight: 600)
    }
}
