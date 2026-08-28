import CouchTourKit
import SwiftUI

struct MiniPlayerView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
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
                    if let show = player.show {
                        Button {
                            appModel.navigate(to: .show(show))
                        } label: {
                            Text(player.currentTrack?.title ?? "—")
                                .font(.headline)
                                .lineLimit(1)
                        }
                        .buttonStyle(.plain)
                    } else {
                        Text(player.currentTrack?.title ?? "—")
                            .font(.headline)
                            .lineLimit(1)
                    }
                    if let show = player.show {
                        HStack(spacing: 4) {
                            HStack(spacing: 4) {
                                Button {
                                    appModel.navigate(to: .artist(show.artist))
                                } label: {
                                    Text(show.artist.name)
                                }
                                .buttonStyle(.plain)
                                Text("·")
                                Button {
                                    appModel.navigate(to: .show(show))
                                } label: {
                                    Text(show.date)
                                }
                                .buttonStyle(.plain)
                            }
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            if let track = player.currentTrack {
                                StatusPill.codec(isFlac: track.flacUrl?.isEmpty == false)
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
                .accessibilityLabel("Previous track")

                Button {
                    player.togglePlayPause()
                } label: {
                    Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                        .font(.title2)
                }
                .accessibilityLabel(player.isPlaying ? "Pause" : "Play")

                Button {
                    player.skipToNext()
                } label: {
                    Image(systemName: "forward.fill")
                }
                .disabled((player.currentIndex ?? -1) >= player.tracks.count - 1)
                .accessibilityLabel("Next track")

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
            .accessibilityLabel(player.volume == 0 ? "Unmute" : "Mute")

            Slider(value: $player.volume, in: 0...1)
                .frame(width: 72)
                .accessibilityLabel("Volume")
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
            .accessibilityLabel("Playback position")
            .accessibilityValue(fmt(Int64(displayedMs)))
            Text(fmt(duration)).font(.caption).monospacedDigit()
        }
        .frame(minWidth: 240)
    }
}
