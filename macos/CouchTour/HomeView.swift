import CouchTourKit
import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player

    @State private var relistenArtists: [ArtistRef] = []
    @State private var recent: [PlaybackProgress] = []
    @State private var historyCount: Int = 0
    @State private var onThisDateShows: [ShowSummary] = []
    @State private var nextStopShow: ShowSummary?
    @State private var tourPreferences: [ArtistTourPreference] = []

    @State private var isFindingSurprise = false
    @State private var alertMessage: String?
    @State private var tourPickerArtist: ArtistRef?

    private var mergedArtists: [ArtistRef] {
        mergeArtists(relistenArtists: relistenArtists, favorites: appModel.favorites.keys)
    }

    private var favoritedArtists: [ArtistRef] {
        mergedArtists.filter { appModel.favorites.keys.contains($0.key) }
    }

    private var today: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date())
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                // MARK: - Header & Surprise Me
                headerSection

                // MARK: - Continue Listening Shelf
                if !recent.isEmpty {
                    continueListeningSection
                }

                // MARK: - Next Couch Tour Stop
                nextStopSection

                // MARK: - On This Date Shelf
                if !onThisDateShows.isEmpty {
                    onThisDateSection
                }

                // MARK: - Favorite Artists
                if !favoritedArtists.isEmpty {
                    favoritesSection
                }

                // MARK: - Library & Quick Links
                libraryOverviewSection

                // MARK: - Settings, Account & Sync
                settingsAndSyncSection

                // MARK: - Version Footer
                footerSection
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .navigationTitle("Home")
        .navigationDestination(for: ArtistRef.self) { PeriodsView(artist: $0) }
        .navigationDestination(for: ShowSummary.self) { ShowDetailView(show: $0) }
        .sheet(item: $tourPickerArtist) { artist in
            TourPickerSheet(artist: artist)
        }
        .task {
            await reloadAll()
        }
        .onChange(of: appModel.favorites.keys) { _, _ in
            Task { await reloadDiscovery() }
        }
        .onChange(of: player.queueKey) { _, _ in
            Task { await reloadProgress() }
        }
        .onChange(of: appModel.syncSession.lastSyncedAt) { _, _ in
            Task { await reloadProgress() }
        }
        .alert(
            "Couch Tour",
            isPresented: Binding(
                get: { alertMessage != nil },
                set: { if !$0 { alertMessage = nil } }
            ),
            actions: { Button("OK") { alertMessage = nil } },
            message: { Text(alertMessage ?? "") }
        )
    }

    // MARK: - Header & Surprise Me

    private var headerSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Couch Tour")
                        .font(.title)
                        .fontWeight(.bold)
                    Text("Your live concert companion")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                Button {
                    Task { await surpriseMe() }
                } label: {
                    HStack(spacing: 8) {
                        if isFindingSurprise {
                            ProgressView()
                                .controlSize(.small)
                            Text("Finding a show…")
                        } else {
                            Image(systemName: "shuffle")
                            Text("Surprise Me")
                        }
                    }
                    .font(.body.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.regular)
                .disabled(isFindingSurprise || mergedArtists.isEmpty)
            }
        }
    }

    // MARK: - Continue Listening Shelf

    private var continueListeningSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Continue Listening", systemImage: "play.circle.fill")
                    .font(.title3)
                    .fontWeight(.semibold)
                Spacer()
                Button("See All") {
                    appModel.selection = .continueListening
                }
                .buttonStyle(.link)
                .font(.caption)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(recent, id: \.queueKey) { item in
                        ResumeCardView(progress: item) {
                            Task { await tapResume(item) }
                        }
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    // MARK: - Next Couch Tour Stop

    @ViewBuilder
    private var nextStopSection: some View {
        if let show = nextStopShow {
            VStack(alignment: .leading, spacing: 12) {
                Label("Next Couch Tour Stop", systemImage: "bookmark.fill")
                    .font(.title3)
                    .fontWeight(.semibold)

                HStack(spacing: 16) {
                    ArtworkView(
                        url: show.artURL,
                        size: 64
                    )

                    VStack(alignment: .leading, spacing: 4) {
                        Text("\(show.date) · \(show.artist.name)")
                            .font(.headline)
                            .lineLimit(1)

                        let subtitle = [show.tourName, show.where_.isEmpty ? nil : show.where_]
                            .compactMap { $0 }
                            .joined(separator: " — ")
                        if !subtitle.isEmpty {
                            Text(subtitle)
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                    }

                    Spacer()

                    NavigationLink(value: show) {
                        HStack(spacing: 4) {
                            Image(systemName: "play.fill")
                            Text("Open Show")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)

                    Button {
                        tourPickerArtist = show.artist
                    } label: {
                        Image(systemName: "pencil")
                            .font(.caption)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("Change Tour / Year for \(show.artist.name)")
                }
                .padding(14)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            }
        } else if !favoritedArtists.isEmpty {
            let untouredDefunct = favoritedArtists.filter { artist in
                let pref = tourPreferences.first { $0.artistKey == artist.key }
                return pref == nil || (pref?.tourName == nil && pref?.year == nil)
            }

            if !untouredDefunct.isEmpty {
                VStack(alignment: .leading, spacing: 12) {
                    Label("Next Couch Tour Stop", systemImage: "bookmark.fill")
                        .font(.title3)
                        .fontWeight(.semibold)

                    ForEach(untouredDefunct.prefix(2), id: \.key) { artist in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Choose tour/year for \(artist.name)")
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                                Text("Pick a historical tour or year to track on your Next Stop shelf")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("Set Tour / Year") {
                                tourPickerArtist = artist
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                        .padding(12)
                        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                    }
                }
            }
        }
    }

    // MARK: - On This Date Shelf

    private var onThisDateSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("On This Date", systemImage: "calendar.badge.clock")
                    .font(.title3)
                    .fontWeight(.semibold)
                Spacer()
                Text("Anniversary shows")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    ForEach(onThisDateShows, id: \.date) { show in
                        NavigationLink(value: show) {
                            AnniversaryCardView(show: show)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 4)
            }
        }
    }

    // MARK: - Favorites Section

    private var favoritesSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("Favorite Artists", systemImage: "star.fill")
                    .font(.title3)
                    .fontWeight(.semibold)
                Spacer()
            }

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 220, maximum: 300), spacing: 12)], spacing: 12) {
                ForEach(favoritedArtists, id: \.key) { artist in
                    NavigationLink(value: artist) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(artist.name)
                                    .font(.headline)
                                    .lineLimit(1)
                                if artist.showCount > 0 {
                                    Text("\(artist.showCount) \(plural(artist.showCount, "show"))")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            Spacer()
                            Button {
                                appModel.favorites.toggle(artist.key)
                            } label: {
                                Image(systemName: "star.fill")
                                    .foregroundStyle(.yellow)
                            }
                            .buttonStyle(.plain)
                        }
                        .padding(12)
                        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
                    }
                    .buttonStyle(.plain)
                    .contextMenu {
                        Button {
                            tourPickerArtist = artist
                        } label: {
                            Label("Set Next Stop Tour / Year...", systemImage: "calendar.badge.clock")
                        }
                    }
                }
            }
        }
    }

    // MARK: - Library & Quick Links

    private var libraryOverviewSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Library & Explore")
                .font(.title3)
                .fontWeight(.semibold)

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                // Browse Artists
                Button {
                    appModel.selection = .artists
                } label: {
                    QuickLinkCard(
                        title: "Browse Artists",
                        subtitle: "\(mergedArtists.count) artists available",
                        icon: "music.mic",
                        color: .blue
                    )
                }
                .buttonStyle(.plain)

                // Playlists
                Button {
                    appModel.selection = .playlists
                } label: {
                    QuickLinkCard(
                        title: "Playlists",
                        subtitle: "Local & phish.in mixtapes",
                        icon: "music.note.list",
                        color: .purple
                    )
                }
                .buttonStyle(.plain)

                // History
                Button {
                    appModel.selection = .history
                } label: {
                    QuickLinkCard(
                        title: "Listening History",
                        subtitle: historyCount > 0 ? "\(historyCount) shows played" : "Recent listening",
                        icon: "clock.arrow.circlepath",
                        color: .orange
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Settings, Account & Sync

    private var settingsAndSyncSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Settings & Status")
                .font(.title3)
                .fontWeight(.semibold)

            HStack(spacing: 16) {
                // Account status
                HStack(spacing: 10) {
                    Image(systemName: "person.crop.circle")
                        .font(.title2)
                        .foregroundStyle(.tint)
                    VStack(alignment: .leading, spacing: 2) {
                        if let username = appModel.phishInSession.username {
                            Text("Signed in as \(username)")
                                .font(.subheadline)
                                .fontWeight(.medium)
                            Text("phish.in account connected")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        } else {
                            Text("phish.in Account")
                                .font(.subheadline)
                                .fontWeight(.medium)
                            Text("Not signed in")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))

                // Skip filler toggle
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Skip filler tracks")
                            .font(.subheadline)
                            .fontWeight(.medium)
                        Text("Intros, tuning & banter")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Toggle("", isOn: Binding(
                        get: { appModel.playbackSettings.skipFiller },
                        set: { appModel.playbackSettings.skipFiller = $0 }
                    ))
                    .labelsHidden()
                    .toggleStyle(.switch)
                }
                .frame(maxWidth: .infinity)
                .padding(12)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))

                // Sync status
                HStack(spacing: 10) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.title2)
                        .foregroundStyle(appModel.syncSession.paired ? .green : .secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Sync")
                            .font(.subheadline)
                            .fontWeight(.medium)
                        Text(appModel.syncSession.paired ? "Paired" : "Not paired")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    // MARK: - Version Footer

    private var footerSection: some View {
        HStack {
            Spacer()
            Text("Couch Tour \(Bundle.main.appVersionString)")
                .font(.caption2)
                .foregroundStyle(.tertiary)
            Spacer()
        }
        .padding(.top, 12)
    }

    // MARK: - Actions & Data Loading

    private func surpriseMe() async {
        guard !mergedArtists.isEmpty else { return }
        isFindingSurprise = true
        do {
            let show = try await pickRandomShow(artists: mergedArtists)
            let detail = try await sourceFor(show.artist.backend).show(
                artist: show.artist, date: show.date, recordingId: nil
            )
            if !detail.tracks.isEmpty {
                player.play(detail: detail, startIndex: 0)
                appModel.showNowPlaying = true
            }
        } catch {
            alertMessage = "Couldn't find a random show: \(error.localizedDescription)"
        }
        isFindingSurprise = false
    }

    private func tapResume(_ row: PlaybackProgress) async {
        do {
            try await resume(row, player: player, localPlaylistStore: appModel.localPlaylistStore)
        } catch {
            alertMessage = "Couldn't resume \(row.title): \(error.localizedDescription)"
        }
    }

    private func reloadAll() async {
        await reloadArtists()
        await reloadProgress()
        await reloadDiscovery()
    }

    private func reloadArtists() async {
        do {
            relistenArtists = try await RelistenCatalogSource.shared.artists()
        } catch {
            // Keep empty on error
        }
    }

    private func reloadProgress() async {
        guard let store = appModel.progressStore else { return }
        do {
            recent = try store.inProgress()
            historyCount = try store.history().count
            tourPreferences = try store.getAllTourPreferences()
        } catch {
            // Drop on error
        }
    }

    private func reloadDiscovery() async {
        let favs = favoritedArtists
        let dateStr = today

        // Load On This Date
        if !favs.isEmpty {
            onThisDateShows = await OnThisDate.load(favorites: favs, today: dateStr)
        } else {
            onThisDateShows = []
        }

        // Load Next Stop
        if !favs.isEmpty {
            let candidateShows = await NextStop.load(
                favorites: favs, today: dateStr, preferences: tourPreferences
            )
            let finishedKeys: [String]
            if let store = appModel.progressStore, let hist = try? store.history() {
                finishedKeys = hist.filter { $0.finished }.map { $0.queueKey }
            } else {
                finishedKeys = []
            }
            nextStopShow = oldestUnplayed(candidates: candidateShows, played: playedShowIds(from: finishedKeys))
        } else {
            nextStopShow = nil
        }
    }
}

// MARK: - Supporting Subviews

private struct ResumeCardView: View {
    let progress: PlaybackProgress
    let onPlay: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ZStack(alignment: .bottomTrailing) {
                ArtworkView(
                    url: progress.artUrl,
                    size: 150
                )
                .clipShape(RoundedRectangle(cornerRadius: 8))

                Button(action: onPlay) {
                    Image(systemName: "play.fill")
                        .font(.caption)
                        .foregroundStyle(.white)
                        .padding(8)
                        .background(.tint, in: Circle())
                }
                .buttonStyle(.plain)
                .padding(8)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(progress.artist.isEmpty ? progress.title : "\(progress.artist) · \(progress.title)")
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .lineLimit(1)

                Text(progress.trackTitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                Text(relativeTime(progress.updatedAt))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .frame(width: 150, alignment: .leading)
        }
        .padding(8)
        .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct AnniversaryCardView: View {
    let show: ShowSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ArtworkView(
                url: show.artURL,
                size: 140
            )
            .clipShape(RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 2) {
                Text(show.date)
                    .font(.subheadline)
                    .fontWeight(.bold)
                    .lineLimit(1)

                Text(show.artist.name)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                if !show.where_.isEmpty {
                    Text(show.where_)
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
            }
            .frame(width: 140, alignment: .leading)
        }
        .padding(8)
        .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct QuickLinkCard: View {
    let title: String
    let subtitle: String
    let icon: String
    let color: Color

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title2)
                .foregroundStyle(color)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .padding(12)
        .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
    }
}
