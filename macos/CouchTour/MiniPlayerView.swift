import CouchTourKit
import SwiftUI

struct MiniPlayerView: View {
    @EnvironmentObject private var player: Player
    /// Non-nil only while the user is actively dragging the scrubber — see `scrubber`.
    @State private var dragPositionMs: Double?

    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(player.currentTrack?.title ?? "—")
                    .font(.headline)
                    .lineLimit(1)
                if let show = player.show {
                    Text("\(show.artist.name) · \(show.date)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(minWidth: 160, alignment: .leading)

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
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
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
