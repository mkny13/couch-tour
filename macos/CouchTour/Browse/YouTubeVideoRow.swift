import CouchTourKit
import SwiftUI

/// One video in the artist page's YouTube section (#230). Matches `TrackTableRow`'s
/// density — thumbnail, title, publish date — and the whole row is the play affordance,
/// like Home's on-this-date cards. The tap goes to `Player.playYoutube(video:)`, which
/// (#230 stub, #231 real) opens the Now Playing inspector.
struct YouTubeVideoRow: View {
    let video: YouTubeVideo
    let onTap: (YouTubeVideo) -> Void

    @Environment(\.ledgerColors) private var colors

    var body: some View {
        Button { onTap(video) } label: {
            HStack(spacing: 14) {
                ArtworkView(url: video.thumbnailURL, artist: video.title, size: 44)

                VStack(alignment: .leading, spacing: 2) {
                    Text(video.title)
                        .font(.system(size: 15))
                        .foregroundStyle(colors.textPrimary)
                        .lineLimit(2)
                    if let publishedAt = video.publishedAt {
                        Text(publishedAt.formatted(date: .abbreviated, time: .omitted))
                            .font(.system(size: 13))
                            .foregroundStyle(colors.textSubtle)
                    }
                }

                Spacer()

                Image(systemName: "play.circle")
                    .font(.system(size: 20))
                    .foregroundStyle(colors.accentIcon)
                    .frame(width: 30, height: 30, alignment: .trailing)
            }
            .padding(.vertical, 5)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Play \(video.title)")
    }
}

/// The `.youtube` route's landing spot. Nothing pushes it yet — the player-bar / Now
/// Playing click-back wiring is #231's — but a pushed `Route` needs a destination, so
/// until then this is a minimal page: the video's metadata and a play button.
struct YouTubeVideoView: View {
    let video: YouTubeVideo

    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel
    @Environment(\.ledgerColors) private var colors

    var body: some View {
        VStack(spacing: 16) {
            ArtworkView(url: video.thumbnailURL, artist: video.title, size: 240)
            Text(video.title)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .multilineTextAlignment(.center)
            Button {
                player.playYoutube(video: video)
                appModel.showNowPlaying = true
            } label: {
                Label("Play", systemImage: "play.fill")
            }
            .buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }
}
