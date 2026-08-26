import CouchTourKit
import SwiftUI

struct MiniPlayerView: View {
    @EnvironmentObject private var player: Player
    /// Non-nil only while the user is actively dragging the scrubber — see `scrubber`.
    @State private var dragPositionMs: Double?

    var body: some View {
        VStack(spacing: 0) {
            if let prompt = player.postShowPrompt {
                NextTourStopPromptBanner(
                    prompt: prompt,
                    onPlay: { player.playNextTourStop(prompt) },
                    onDismiss: { player.dismissPostShowPrompt() }
                )
                .padding(.bottom, 4)
            }

            HStack(spacing: 16) {
                ArtworkView(url: player.artURL)

                VStack(alignment: .leading, spacing: 2) {
                    Text(player.currentTrack?.title ?? "—")
                        .font(.headline)
                        .lineLimit(1)
                    if let show = player.show {
                        HStack(spacing: 4) {
                            Text("\(show.artist.name) · \(show.date)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            if let track = player.currentTrack {
                                Text(track.flacUrl?.isEmpty == false ? "FLAC" : "MP3")
                                    .font(.system(size: 9, weight: .bold))
                                    .padding(.horizontal, 3)
                                    .padding(.vertical, 1)
                                    .background((track.flacUrl?.isEmpty == false ? Color.green : Color.secondary).opacity(0.18))
                                    .foregroundStyle(track.flacUrl?.isEmpty == false ? Color.green : Color.secondary)
                                    .clipShape(RoundedRectangle(cornerRadius: 3))
                            }
                        }
                    }
                }
                .frame(minWidth: 160, alignment: .leading)

                if let track = player.currentTrack, let show = player.show {
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
                }
                .disabled((player.currentIndex ?? 0) == 0)

                Button {
                    player.togglePlayPause()
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                }

                Button {
                    player.skipToNext()
                } label: {
                    Image(systemName: "forward.fill")
                }
                .disabled((player.currentIndex ?? -1) >= player.tracks.count - 1)

                scrubber

                volumeControl

                CastRoutePickerButton()
            }
            .buttonStyle(.borderless)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }

    private var volumeControl: some View {
        HStack(spacing: 6) {
            Button {
                player.toggleMute()
            } label: {
                Image(systemName: volumeSymbol)
            }

            Slider(value: $player.volume, in: 0...1)
                .frame(width: 72)
        }
    }

    private var volumeSymbol: String {
        switch player.volume {
        case 0: return "speaker.slash.fill"
        case ..<0.34: return "speaker.wave.1.fill"
        case ..<0.67: return "speaker.wave.2.fill"
        default: return "speaker.wave.3.fill"
        }
    }

    /// Tracks the drag locally and only calls through to `player.seek` when the drag ends —
    /// `Slider`'s `value` binding fires on every intermediate value otherwise, which was
    /// issuing a continuous stream of seeks at `AVPlayer` while dragging.
    private var scrubber: some View {
        let duration = player.currentTrack?.durationMs ?? 0
        let displayedMs = dragPositionMs ?? Double(player.positionMs)
        return HStack(spacing: 8) {
            Text(fmt(Int64(displayedMs))).font(.caption).monospacedDigit()
            Slider(
                value: Binding(
                    get: { displayedMs },
                    set: { dragPositionMs = $0 }
                ),
                in: 0...Double(max(duration, 1)),
                onEditingChanged: { isEditing in
                    if !isEditing, let dragPositionMs {
                        player.seek(toMs: Int64(dragPositionMs))
                    }
                    if !isEditing { dragPositionMs = nil }
                }
            )
            Text(fmt(duration)).font(.caption).monospacedDigit()
        }
        .frame(minWidth: 240)
    }
}
