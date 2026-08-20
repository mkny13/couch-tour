import CouchTourKit
import SwiftUI

/// The full Now Playing view — artwork, identity, and the show's whole queue, so there's
/// somewhere to see "what's coming up" and jump around a show while browsing elsewhere.
/// Deliberately carries no transport: `MiniPlayerView` sits directly below this panel whenever
/// it's visible (RootView.swift), so duplicating play/pause/skip/scrub here would just be
/// redundant chrome a few points away.
struct NowPlayingInspector: View {
    @EnvironmentObject private var player: Player

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // An explicit label rather than relying on `.navigationTitle` alone — an
            // `.inspector` panel isn't itself a NavigationStack, so a title set that way isn't
            // guaranteed to render as visible header chrome.
            Text("Now Playing")
                .font(.headline)
                .padding()
            Divider()

            if let show = player.show {
                content(show: show)
            } else {
                ContentUnavailableView(
                    "Nothing playing",
                    systemImage: "music.note.list",
                    description: Text("Play a show to see its queue here.")
                )
            }
        }
    }

    @ViewBuilder
    private func content(show: ShowSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(spacing: 10) {
                ArtworkView(url: player.artURL, size: 160)
                VStack(spacing: 2) {
                    Text(player.currentTrack?.title ?? "—")
                        .font(.headline)
                        .multilineTextAlignment(.center)
                    Text("\(show.artist.name) · \(show.date)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    if !show.where_.isEmpty {
                        Text(show.where_)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .multilineTextAlignment(.center)
            }
            .frame(maxWidth: .infinity)
            .padding()

            Divider()

            ScrollViewReader { proxy in
                List {
                    ForEach(trackGroups(player.tracks), id: \.setName) { group in
                        Section(group.setName.isEmpty ? "" : group.setName) {
                            ForEach(group.tracks, id: \.id) { track in
                                QueueRow(
                                    track: track,
                                    isPlaying: player.currentTrack?.id == track.id
                                ) {
                                    guard let index = player.tracks.firstIndex(of: track) else { return }
                                    player.seek(toTrack: index)
                                }
                                .id(track.id)
                            }
                        }
                    }
                }
                .listStyle(.inset)
                .onChange(of: player.currentIndex) { _, _ in
                    guard let id = player.currentTrack?.id else { return }
                    withAnimation {
                        proxy.scrollTo(id, anchor: .center)
                    }
                }
            }
        }
    }
}

private struct QueueRow: View {
    let track: PlayableTrack
    let isPlaying: Bool
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack {
                if isPlaying {
                    Image(systemName: "speaker.wave.2.fill")
                        .foregroundStyle(.tint)
                        .font(.caption)
                }
                Text(track.title)
                    .fontWeight(isPlaying ? .semibold : .regular)
                Spacer()
                Text(fmt(track.durationMs))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
