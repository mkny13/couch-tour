import CouchTourKit
import SwiftUI

/// The hub. With the sidebar gone (D203) this screen *is* the app's navigation — its tiles are
/// the only way into Artists, Playlists, and Listening, which is why they carry chevrons now
/// rather than reading as decoration (#102's first complaint: clickable things that didn't look
/// clickable, sitting beside non-clickable things that did).
struct HomeView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player

    @State private var relistenArtists: [ArtistRef] = []
    @State private var recent: [PlaybackProgress] = []
    @State private var historyCount: Int = 0
    @State private var onThisDateShows: [ShowSummary] = []
    @State private var nextStopShow: ShowSummary?
    @State private var tourPreferences: [ArtistTourPreference] = []

    /// Non-nil when a load *failed*, as opposed to succeeding with nothing in it. These used to
    /// be swallowed, so a network outage and an empty library rendered identically (#102).
    @State private var artistsError: String?
    @State private var progressError: String?

    @State private var isFindingSurprise = false
    @State private var alertMessage: String?
    @State private var tourPickerArtist: ArtistRef?
    @State private var resolvingResumeRow: String?

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
            VStack(alignment: .leading, spacing: CardMetrics.sectionSpacing) {
                headerSection

                if let progressError {
                    InlineErrorView(message: progressError) { await reloadProgress() }
                }

                if !recent.isEmpty {
                    continueListeningSection
                }

                nextStopSection

                if !onThisDateShows.isEmpty {
                    onThisDateSection
                }

                if !favoritedArtists.isEmpty {
                    favoritesSection
                }

                librarySection

                settingsAndSyncSection
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 20)
        }
        .sheet(item: $tourPickerArtist, onDismiss: {
            Task {
                // reloadDiscovery() reads tourPreferences from state, so it must run after
                // reloadProgress() has refreshed that state — otherwise it just recomputes
                // the same stale answer TourPickerSheet's save/clear was meant to invalidate.
                await reloadProgress()
                await reloadDiscovery()
            }
        }) { artist in
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

    // MARK: - Continue Listening Shelf

    private var continueListeningSection: some View {
        Shelf("Continue Listening", systemImage: "play.circle.fill") {
            NavigationLink(value: Route.listening) {
                Text("See All")
            }
            .buttonStyle(.link)
            .font(.caption)
        } content: {
            ForEach(recent, id: \.queueKey) { item in
                ResumeCardView(
                    progress: item,
                    isResolvingNavigation: resolvingResumeRow == item.queueKey,
                    onPlay: { Task { await tapResume(item) } },
                    onOpen: { Task { await openResumeRow(item) } },
                    onMarkCompleted: { Task { await markResumeRowCompleted(item) } },
                    onRemove: { Task { await removeResumeRow(item) } }
                )
            }
        }
    }

    // MARK: - Next Couch Tour Stop

    @ViewBuilder
    private var nextStopSection: some View {
        if let show = nextStopShow {
            VStack(alignment: .leading, spacing: CardMetrics.headerSpacing) {
                SectionHeader("Next Couch Tour Stop", systemImage: "bookmark.fill")

                HStack(spacing: 16) {
                    ArtworkView(url: show.artURL, artist: show.artist.name, date: show.date, size: 64)

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

                    NavigationLink(value: Route.show(show)) {
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
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .help("Change Tour / Year for \(show.artist.name)")
                    .accessibilityLabel("Change tour or year for \(show.artist.name)")
                }
                .cardSurface(padding: 14)
            }
        } else if !favoritedArtists.isEmpty {
            let untouredDefunct = favoritedArtists.filter { artist in
                let pref = tourPreferences.first { $0.artistKey == artist.key }
                return pref == nil || (pref?.tourName == nil && pref?.year == nil)
            }

            if !untouredDefunct.isEmpty {
                VStack(alignment: .leading, spacing: CardMetrics.headerSpacing) {
                    SectionHeader("Next Couch Tour Stop", systemImage: "bookmark.fill")

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
                        .cardSurface()
                    }
                }
            }
        }
    }

    // MARK: - On This Date Shelf

    private var onThisDateSection: some View {
        Shelf("On This Date", systemImage: "calendar.badge.clock") {
            Text("Anniversary shows")
                .font(.caption)
                .foregroundStyle(.secondary)
        } content: {
            ForEach(onThisDateShows, id: \.date) { show in
                NavigationLink(value: Route.show(show)) {
                    AnniversaryCardView(show: show)
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Favorites Section

    private var favoritesSection: some View {
        VStack(alignment: .leading, spacing: CardMetrics.headerSpacing) {
            SectionHeader("Favorite Artists", systemImage: "star.fill")

            if let artistsError {
                InlineErrorView(message: artistsError) { await reloadArtists() }
            }

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 220, maximum: 300), spacing: 12)], spacing: 12) {
                ForEach(favoritedArtists, id: \.key) { artist in
                    NavigationLink(value: Route.artist(artist)) {
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
                            .accessibilityLabel("Remove \(artist.name) from favorites")
                        }
                        .cardSurface()
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

    // MARK: - Your Library

    /// These three tiles are the navigation the sidebar used to be (D203).
    private var librarySection: some View {
        VStack(alignment: .leading, spacing: CardMetrics.headerSpacing) {
            SectionHeader("Your library")

            if let artistsError, favoritedArtists.isEmpty {
                InlineErrorView(message: artistsError) { await reloadArtists() }
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                NavigationLink(value: Route.artists) {
                    NavigationTile(
                        title: "Artists",
                        subtitle: artistsError == nil
                            ? "\(mergedArtists.count) \(plural(mergedArtists.count, "artist")) available"
                            : "Couldn't load the artist list",
                        icon: "music.mic",
                        iconColor: .blue
                    )
                }
                .buttonStyle(.plain)

                NavigationLink(value: Route.playlists) {
                    NavigationTile(
                        title: "Playlists",
                        subtitle: "Local & phish.in mixtapes",
                        icon: "music.note.list",
                        iconColor: .purple
                    )
                }
                .buttonStyle(.plain)

                NavigationLink(value: Route.listening) {
                    NavigationTile(
                        title: "Listening",
                        subtitle: listeningSubtitle,
                        icon: "clock.arrow.circlepath",
                        iconColor: .orange
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var listeningSubtitle: String {
        if progressError != nil { return "Couldn't load your history" }
        if historyCount == 0 && recent.isEmpty { return "In progress & recently played" }
        return "\(recent.count) in progress · \(historyCount) played"
    }

    // MARK: - Settings & Status

    /// Every tile opens the existing ⌘, window at the tab it names. Home used to carry its own
    /// Account and Sync *sheets* — a second copy of both forms (D197); those are gone, so each
    /// form now exists in exactly one place (D203).
    private var settingsAndSyncSection: some View {
        VStack(alignment: .leading, spacing: CardMetrics.headerSpacing) {
            SectionHeader("Settings & status") {
                SettingsLink {
                    Text("Open Settings…")
                }
                .buttonStyle(.link)
                .font(.caption)
            }

            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                settingsTile(tab: .account) {
                    NavigationTile(
                        title: appModel.phishInSession.username ?? "phish.in Account",
                        subtitle: appModel.phishInSession.username == nil
                            ? "Not signed in · Click to log in"
                            : "Signed in to phish.in",
                        icon: "person.crop.circle",
                        iconColor: .blue
                    )
                }

                settingsTile(tab: .sync) {
                    NavigationTile(
                        title: "Sync",
                        subtitle: appModel.syncSession.paired ? "Syncing with your other devices" : "Click to set up",
                        icon: "arrow.triangle.2.circlepath",
                        iconColor: appModel.syncSession.paired ? .green : .secondary
                    ) {
                        // A pill with its own words, not just a green glyph — paired vs unpaired
                        // used to be carried by tint alone (#102).
                        StatusPill.paired(appModel.syncSession.paired)
                    }
                }

                settingsTile(tab: .playback) {
                    NavigationTile(
                        title: "Playback & updates",
                        // Where the sidebar's version footer went: the Playback tab already
                        // shows the version and "Check for Updates…", so rather than moving
                        // that footer anywhere it simply stopped being duplicated (D203).
                        subtitle: "\(appModel.playbackSettings.skipFiller ? "Skip filler on" : "Skip filler off") · \(Bundle.main.appVersionString)",
                        icon: "gearshape",
                        iconColor: .secondary
                    )
                }
            }
        }
    }

    /// `SettingsLink` is the sanctioned way to open the ⌘, scene (macOS 14+). It takes no
    /// action closure, so the tab is set alongside its own tap rather than before it.
    private func settingsTile<Content: View>(tab: SettingsTab, @ViewBuilder content: () -> Content) -> some View {
        SettingsLink {
            content()
        }
        .buttonStyle(.plain)
        .simultaneousGesture(TapGesture().onEnded {
            appModel.settingsTab = tab
        })
    }

    // MARK: - Actions & Data Loading

    private func surpriseMe() async {
        guard !mergedArtists.isEmpty else { return }
        isFindingSurprise = true
        do {
            let show = try await pickRandomShow(artists: surpriseMeArtists(favorited: favoritedArtists, merged: mergedArtists))
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

    private func openResumeRow(_ row: PlaybackProgress) async {
        resolvingResumeRow = row.queueKey
        do {
            switch try await resolveNavigationTarget(for: row, localPlaylistStore: appModel.localPlaylistStore) {
            case .show(let show): appModel.path.append(.show(show))
            case .localPlaylist(let playlist): appModel.path.append(.localPlaylist(playlist))
            }
        } catch {
            alertMessage = "Couldn't open \(row.title): \(error.localizedDescription)"
        }
        resolvingResumeRow = nil
    }

    // #115 — the same open/mark completed/remove trio as Android's long-press menu
    // (`MainActivity.kt:2377-2407`), applied here and in `ListeningView` so the Home shelf and
    // the full screen agree (D200/#98).
    private func markResumeRowCompleted(_ row: PlaybackProgress) async {
        guard let store = appModel.progressStore else { return }
        do {
            try store.markFinished(key: row.queueKey)
            await reloadProgress()
        } catch {
            alertMessage = "Couldn't update \(row.title): \(error.localizedDescription)"
        }
    }

    private func removeResumeRow(_ row: PlaybackProgress) async {
        guard let store = appModel.progressStore else { return }
        do {
            try store.dismiss(key: row.queueKey)
            await reloadProgress()
        } catch {
            alertMessage = "Couldn't remove \(row.title): \(error.localizedDescription)"
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
            artistsError = nil
        } catch {
            artistsError = "Couldn't load artists: \(error.localizedDescription)"
        }
    }

    private func reloadProgress() async {
        guard let store = appModel.progressStore else {
            progressError = appModel.progressStoreError
            return
        }
        do {
            recent = try store.inProgress()
            historyCount = try store.history().count
            tourPreferences = try store.getAllTourPreferences()
            progressError = nil
        } catch {
            progressError = "Couldn't load your listening history: \(error.localizedDescription)"
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
    let isResolvingNavigation: Bool
    let onPlay: () -> Void
    let onOpen: () -> Void
    let onMarkCompleted: () -> Void
    let onRemove: () -> Void

    /// Scales with the system text size so the title and track name below still fit when the
    /// user turns text up — a fixed 150pt frame just clipped them (#102).
    @ScaledMetric private var width: CGFloat = 150

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            // Artwork/title/subtitle are one target that opens the show; the play button below
            // is a second, non-overlapping target. A Button nested inside another Button's (or
            // NavigationLink's) label swallows clicks on macOS, so this uses a plain tap gesture
            // for navigation rather than wrapping the whole card in a second control — the real
            // Button for play sits outside its hit area entirely (#98).
            Button(action: onOpen) {
                ZStack {
                    // `PlaybackProgress` has no separate date field — `date: nil` here just
                    // means the badge reads "LIVE" instead of a specific show date, which is a
                    // fine placeholder for "something's in progress."
                    ArtworkView(url: progress.artUrl, artist: progress.artist, size: 150)
                        .clipShape(RoundedRectangle(cornerRadius: CardMetrics.cornerRadius))
                    if isResolvingNavigation {
                        ProgressView()
                            .controlSize(.small)
                            .padding(8)
                            .background(.black.opacity(0.35), in: Circle())
                    }
                }
            }
            .buttonStyle(.plain)
            .disabled(isResolvingNavigation)
            .accessibilityLabel("Open \(progress.title)")

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
            .frame(width: width, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture(perform: onOpen)

            Button(action: onPlay) {
                Label("Resume", systemImage: "play.fill")
                    .font(.subheadline.weight(.semibold))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.regular)
        }
        .frame(width: width)
        .cardSurface(padding: 8)
        .contextMenu {
            Button(action: onOpen) {
                Label("Open", systemImage: "arrow.up.forward.app")
            }
            Button(action: onMarkCompleted) {
                Label("Mark Completed", systemImage: "checkmark.circle")
            }
            Button(role: .destructive, action: onRemove) {
                Label("Remove from List", systemImage: "trash")
            }
        }
    }
}

private struct AnniversaryCardView: View {
    let show: ShowSummary

    @ScaledMetric private var width: CGFloat = 140

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ArtworkView(url: show.artURL, artist: show.artist.name, date: show.date, size: 140)
                .clipShape(RoundedRectangle(cornerRadius: CardMetrics.cornerRadius))

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
            .frame(width: width, alignment: .leading)
        }
        .cardSurface(padding: 8)
    }
}
