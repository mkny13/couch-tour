import CouchTourKit
import SwiftUI

struct MiniPlayerView: View {
    @EnvironmentObject private var player: Player

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

            scrubber
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var scrubber: some View {
        let duration = player.currentTrack?.durationMs ?? 0
        return HStack(spacing: 8) {
            Text(fmt(player.positionMs)).font(.caption).monospacedDigit()
            Slider(
                value: Binding(
                    get: { Double(player.positionMs) },
                    set: { player.seek(toMs: Int64($0)) }
                ),
                in: 0...Double(max(duration, 1))
            )
            Text(fmt(duration)).font(.caption).monospacedDigit()
        }
        .frame(minWidth: 240)
    }
}
