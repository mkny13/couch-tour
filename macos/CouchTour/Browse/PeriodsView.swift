import CouchTourKit
import SwiftUI

struct PeriodsView: View {
    let artist: ArtistRef

    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel

    @State private var periods: [PeriodRef] = []
    @State private var loadState: LoadState = .loading
    @State private var videos: [YouTubeVideo] = []
    @State private var youtubeState: YouTubeSectionState = .loading

    /// The YouTube section is independent of the years list, so it carries its own state —
    /// a years-list failure shouldn't blank out videos that did load, and vice versa.
    private enum YouTubeSectionState: Equatable {
        case loading
        case loaded
        case failed(String)
    }

    /// The channel whose videos the section lists, nil when the section must hide entirely:
    /// artists the curated map doesn't cover (D251), and installs with no API key — the
    /// fetch could only ever fail, so a permanently-broken section would be noise.
    private var youtubeChannelId: String? {
        guard let apiKey = YouTubeAPI.apiKey, !apiKey.isEmpty else { return nil }
        return YouTubeChannels.channel(for: artist)
    }

    var body: some View {
        Group {
            switch loadState {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                ErrorView(message: message) { await load() }
            case .loaded:
                List {
                    Section {
                        ForEach(periods, id: \.self) { period in
                            NavigationLink(value: Route.period(artist: artist, period: period)) {
                                VStack(alignment: .leading) {
                                    Text(period.label)
                                    if period.showCount > 0 {
                                        Text("\(period.showCount) \(plural(period.showCount, "show"))")
                                            .font(.caption)
                                            .foregroundStyle(.secondary)
                                    }
                                }
                            }
                        }
                    }
                    if youtubeChannelId != nil {
                        youtubeSection
                    }
                }
            }
        }
        .task {
            await load()
            await loadVideos()
        }
    }

    @ViewBuilder
    private var youtubeSection: some View {
        Section {
            switch youtubeState {
            case .loading:
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Loading videos…")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            case .failed(let message):
                VStack(alignment: .leading, spacing: 8) {
                    Text(message)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Retry") { Task { await loadVideos() } }
                }
            case .loaded where videos.isEmpty:
                Text("No videos on this channel")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            case .loaded:
                ForEach(videos) { video in
                    YouTubeVideoRow(video: video) { tapped in
                        player.playYoutube(video: tapped)
                        appModel.showNowPlaying = true
                    }
                }
            }
        } header: {
            Text("YouTube")
        }
    }

    private func load() async {
        loadState = .loading
        do {
            periods = try await sourceFor(artist.backend).periods(artist: artist)
            loadState = .loaded
        } catch {
            loadState = .failed("Couldn't load \(artist.name)'s years: \(error.localizedDescription)")
        }
    }

    private func loadVideos() async {
        guard let channelId = youtubeChannelId else { return }
        youtubeState = .loading
        do {
            videos = try await YouTubeAPI.search(channelId: channelId).filter { !$0.id.isEmpty }
            youtubeState = .loaded
        } catch {
            youtubeState = .failed("Couldn't load YouTube videos: \(error.localizedDescription)")
        }
    }
}
