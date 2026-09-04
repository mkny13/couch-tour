import CouchTourKit
import SwiftUI

/// Right player rail (392px fixed) for macOS 3-pane desktop layout.
/// Embeds current track info, tape FLAC status, show rating, transport controls,
/// waveform scrubber, and scrollable up-next queue.
struct PlayerRailView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.ledgerColors) private var colors

    @State private var dragPositionMs: Double?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header: NOW PLAYING + Expand Button
            HStack {
                Text("NOW PLAYING")
                    .font(.system(size: 11, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(colors.textMuted)

                Spacer()

                Button {
                    appModel.showNowPlaying.toggle()
                } label: {
                    Image(systemName: "arrow.up.left.and.arrow.down.right")
                        .font(.system(size: 11))
                        .foregroundStyle(colors.textMuted)
                }
                .buttonStyle(.plain)
                .help("Expanded player")
            }
            .padding(.horizontal, 20)
            .padding(.top, 16)
            .padding(.bottom, 12)

            Divider().overlay(colors.divider)

            if let show = player.show {
                ScrollView {
                    VStack(spacing: 16) {
                        // Artwork Tile
                        ArtworkView(
                            url: player.artURL,
                            artist: show.artist.name,
                            date: show.date,
                            size: 180
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                        .padding(.top, 8)

                        // Track + Show metadata
                        VStack(spacing: 4) {
                            Text(player.currentTrack?.title ?? "—")
                                .font(.system(size: 18, weight: .semibold))
                                .foregroundStyle(colors.textPrimary)
                                .multilineTextAlignment(.center)
                                .lineLimit(2)

                            Text("\(show.artist.name) · \(show.date)")
                                .font(.system(size: 13))
                                .foregroundStyle(colors.textSecondary)

                            if !show.where_.isEmpty {
                                Text(show.where_)
                                    .font(.system(size: 11))
                                    .foregroundStyle(colors.textMuted)
                                    .lineLimit(1)
                            }

                            HStack(spacing: 8) {
                                if let track = player.currentTrack {
                                    StatusPill.codec(isFlac: track.flacUrl?.isEmpty == false)
                                }
                                Text("★ 4.4")
                                    .font(.system(size: 11, weight: .medium))
                                    .foregroundStyle(colors.ratingAmber)
                            }
                            .padding(.top, 2)
                        }

                        // Waveform Scrubber
                        VStack(spacing: 4) {
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
                                    .font(.system(size: 11))
                                    .foregroundStyle(colors.textMuted)
                                Spacer()
                                Text(fmt(Int64(duration)))
                                    .font(.system(size: 11))
                                    .foregroundStyle(colors.textMuted)
                            }
                        }
                        .padding(.horizontal, 16)

                        // Transport Controls
                        HStack(spacing: 24) {
                            if let track = player.currentTrack {
                                TrackLikeButton(
                                    backend: show.artist.backend,
                                    trackID: track.id,
                                    likesCount: track.likesCount,
                                    likedByUser: track.likedByUser
                                )
                            }

                            Button {
                                player.skipToPrevious()
                            } label: {
                                Image(systemName: "backward.fill")
                                    .font(.system(size: 16))
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
                                        .frame(width: 44, height: 44)
                                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                                        .font(.system(size: 18))
                                        .foregroundStyle(Color.white)
                                }
                            }
                            .buttonStyle(.plain)

                            Button {
                                player.skipToNext()
                            } label: {
                                Image(systemName: "forward.fill")
                                    .font(.system(size: 16))
                                    .foregroundStyle(colors.textPrimary)
                            }
                            .buttonStyle(.plain)
                            .disabled((player.currentIndex ?? -1) >= player.tracks.count - 1)

                            CastRoutePickerButton()
                        }
                        .padding(.vertical, 4)

                        Divider().overlay(colors.divider)

                        // Up Next Queue Header
                        HStack {
                            Text("UP NEXT")
                                .font(.system(size: 10, weight: .bold))
                                .tracking(1.0)
                                .foregroundStyle(colors.textMuted)
                            Spacer()
                            Text("\(player.tracks.count) tracks")
                                .font(.system(size: 11))
                                .foregroundStyle(colors.textMuted)
                        }
                        .padding(.horizontal, 16)

                        // Up next queue list
                        LazyVStack(spacing: 2) {
                            ForEach(player.tracks.indices, id: \.self) { index in
                                let track = player.tracks[index]
                                let isCurrent = index == player.currentIndex

                                Button {
                                    player.seek(toTrack: index)
                                } label: {
                                    HStack {
                                        Text("\(index + 1)")
                                            .font(.system(size: 11))
                                            .foregroundStyle(isCurrent ? colors.accent : colors.textMuted)
                                            .frame(width: 20, alignment: .leading)

                                        Text(track.title)
                                            .font(.system(size: 12, weight: isCurrent ? .semibold : .regular))
                                            .foregroundStyle(isCurrent ? colors.accent : colors.textPrimary)
                                            .lineLimit(1)

                                        Spacer()

                                        Text(fmt(track.durationMs))
                                            .font(.system(size: 11))
                                            .foregroundStyle(colors.textMuted)
                                    }
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(
                                        isCurrent ? colors.surface : Color.clear,
                                        in: RoundedRectangle(cornerRadius: 6)
                                    )
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.horizontal, 10)
                    }
                    .padding(.bottom, 20)
                }
            } else {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "music.note")
                        .font(.system(size: 32))
                        .foregroundStyle(colors.textMuted.opacity(0.6))
                    Text("No show playing")
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(colors.textSecondary)
                    Text("Pick a show to start listening.")
                        .font(.system(size: 12))
                        .foregroundStyle(colors.textMuted)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(width: 392)
        .background(colors.elevated)
    }
}
