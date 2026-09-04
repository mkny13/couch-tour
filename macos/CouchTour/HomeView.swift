import CouchTourKit
import SwiftUI

/// Center ledger pane for macOS 3-pane layout (Screen 2A).
/// Contains the Top bar with Date and In Progress header,
/// In-Progress Shelf of 236px cards with 2px top progress bars,
/// NEXT TOUR STOPS card table with top accent bar, and
/// ON THIS DATE shelf of 236px cards.
struct HomeView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @Environment(\.ledgerColors) private var colors

    @State private var relistenArtists: [ArtistRef] = []
    @State private var recent: [PlaybackProgress] = []
    @State private var historyCount: Int = 0
    @State private var onThisDateShows: [ShowSummary] = []
    @State private var nextStopShows: [ShowSummary] = []
    @State private var tourPreferences: [ArtistTourPreference] = []

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

    private var formattedLedgerDate: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "EEEE, MMM d"
        return formatter.string(from: Date()).uppercased()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Top Ledger Bar (Date, In progress, Surprise Me, Filter pills)
                topLedgerBar

                // In Progress Shelf
                inProgressShelf
                    .padding(.bottom, 20)

                // Next Tour Stops Card
                nextTourStopsCard
                    .padding(.bottom, 20)

                // On This Date Shelf
                onThisDateShelf
                    .padding(.bottom, 24)
            }
        }
        .background(colors.background)
        .sheet(item: $tourPickerArtist, onDismiss: {
            Task {
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

    // MARK: - Top Ledger Bar

    private var topLedgerBar: some View {
        HStack(alignment: .center, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(formattedLedgerDate)
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(1.4)
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

                Button {
                    appModel.path.append(.listening)
                } label: {
                    HStack(spacing: 6) {
                        Text("In progress")
                            .font(.system(size: 20, weight: .medium))
                            .foregroundStyle(colors.textPrimary)

                        Image(systemName: "chevron.right")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("In progress — open History")
            }

            Spacer()

            // Surprise Me Pill
            Button {
                Task { await surpriseMe() }
            } label: {
                HStack(spacing: 8) {
                    if isFindingSurprise {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "shuffle")
                            .font(.system(size: 14))
                    }
                    Text("Surprise me")
                        .font(.system(size: 14, weight: .medium))
                }
                .padding(.horizontal, 16)
                .frame(height: 38)
                .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0), lineWidth: 1)
                )
            }
            .buttonStyle(.plain)
            .disabled(isFindingSurprise || mergedArtists.isEmpty)

            // Recently played filter pill
            HStack(spacing: 5) {
                Text("Recently played")
                    .font(.system(size: 13))
                Image(systemName: "chevron.down")
                    .font(.system(size: 10))
            }
            .padding(.horizontal, 12)
            .frame(height: 30)
            .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
            .background(Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.14), in: Capsule())
            .overlay(Capsule().stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1))

            // All artists filter pill
            HStack(spacing: 5) {
                Text("All artists")
                    .font(.system(size: 13))
                Image(systemName: "chevron.down")
                    .font(.system(size: 10))
            }
            .padding(.horizontal, 12)
            .frame(height: 30)
            .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
            .overlay(Capsule().stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1))

            // Arrow circle buttons
            HStack(spacing: 6) {
                Circle()
                    .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: "chevron.left")
                            .font(.system(size: 11))
                            .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                    )

                Circle()
                    .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: "chevron.right")
                            .font(.system(size: 11))
                            .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                    )
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .padding(.bottom, 14)
    }

    // MARK: - In Progress Shelf

    private var inProgressShelf: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                if !recent.isEmpty {
                    ForEach(recent, id: \.queueKey) { item in
                        inProgressCard(item)
                    }
                } else {
                    // Fallback placeholder cards matching Screen 2A layout
                    inProgressFallbackCards
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 2)
            .padding(.bottom, 6)
        }
    }

    private func inProgressCard(_ item: PlaybackProgress) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Top 2px Progress Bar Overlay
            let frac: Double = {
                if player.queueKey == item.queueKey, let dur = player.currentTrack?.durationMs, dur > 0 {
                    return progressFraction(positionMs: player.positionMs, durationMs: dur)
                }
                return 0.45
            }()
            ProgressBarOverlay(fraction: frac)

            VStack(alignment: .leading, spacing: 0) {
                // Header: Artist + Date + Menu
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(ArtistAbbreviations.label(for: item.artist))
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(colors.textPrimary)
                            .lineLimit(1)

                        Text(formatShowDate(item.title))
                            .font(.system(size: 15))
                            .foregroundStyle(colors.textPrimary)
                            .lineLimit(1)
                    }

                    Spacer()

                    Menu {
                        Button("Open show") { Task { await openResumeRow(item) } }
                        Button("Play from start") { Task { await tapResume(item) } }
                        Button("Mark completed") { Task { await markResumeRowCompleted(item) } }
                        Button("Remove from in progress") { Task { await removeResumeRow(item) } }
                    } label: {
                        Image(systemName: "ellipsis")
                            .font(.system(size: 13))
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                            .frame(width: 26, height: 26)
                    }
                    .menuStyle(.borderlessButton)
                    .frame(width: 26, height: 26)
                }

                // Track Title
                Text(item.trackTitle.isEmpty ? "Track" : item.trackTitle)
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                    .padding(.top, 8)
                    .lineLimit(1)

                // Venue
                Text("Live recording")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                    .padding(.top, 2)
                    .lineLimit(1)

                // Bottom Row: Remaining time + Play button
                HStack(alignment: .center) {
                    let timeLabel: String = {
                        if player.queueKey == item.queueKey, let dur = player.currentTrack?.durationMs, dur > 0 {
                            return formatRemainingTime(positionMs: item.positionMs, durationMs: dur)
                        }
                        return "\(fmt(item.positionMs)) played"
                    }()
                    Text(timeLabel)
                        .font(.system(size: 12))
                        .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))

                    Spacer()

                    Button {
                        Task { await tapResume(item) }
                    } label: {
                        Circle()
                            .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1)
                            .frame(width: 30, height: 30)
                            .overlay(
                                Image(systemName: "play.fill")
                                    .font(.system(size: 12))
                                    .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                            )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Play \(item.trackTitle)")
                }
                .padding(.top, 12)
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 13)
        }
        .frame(width: 236)
        .background(colors.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(colors.panelBorder, lineWidth: 1)
        )
    }

    @ViewBuilder
    private var inProgressFallbackCards: some View {
        // Spec 2A mock sample in-progress cards when history is empty
        Group {
            sampleCard(artist: "Phish", date: "1997-11-17", track: "Bathtub Gin", venue: "Thomas & Mack, Las Vegas", left: "7:32 left", frac: 0.41, color: Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0))
            sampleCard(artist: "Grateful Dead", date: "1977-05-08", track: "Scarlet Begonias", venue: "Barton Hall, Ithaca", left: "21:04 left", frac: 0.62, color: Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0))
            sampleCard(artist: "pgroove", date: "2005-04-16", track: "Three Weeks", venue: "Georgia Theatre, Athens", left: "1:42 left", frac: 0.24, color: Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
        }
    }

    private func sampleCard(artist: String, date: String, track: String, venue: String, left: String, frac: Double, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            ProgressBarOverlay(fraction: frac, fillColor: color)

            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(ArtistAbbreviations.label(for: artist))
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(colors.textPrimary)
                        Text(date)
                            .font(.system(size: 15))
                            .foregroundStyle(colors.textPrimary)
                    }
                    Spacer()
                    Image(systemName: "ellipsis")
                        .font(.system(size: 13))
                        .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                        .frame(width: 26, height: 26)
                }

                Text(track)
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                    .padding(.top, 8)

                Text(venue)
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                    .padding(.top, 2)

                HStack {
                    Text(left)
                        .font(.system(size: 12))
                        .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                    Spacer()
                    Circle()
                        .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1)
                        .frame(width: 30, height: 30)
                        .overlay(
                            Image(systemName: "play.fill")
                                .font(.system(size: 12))
                                .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                        )
                }
                .padding(.top, 12)
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 13)
        }
        .frame(width: 236)
        .background(colors.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(colors.panelBorder, lineWidth: 1)
        )
    }

    // MARK: - Next Tour Stops Card

    private var nextTourStopsCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Top 2px Accent Bar: #f2a93b -> #f06bb0
            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [
                            Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0),
                            Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0),
                            Color.clear
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .frame(height: 2)

            // Header Row
            HStack {
                Text("NEXT TOUR STOPS")
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(1.4)
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                Spacer()

                NavigationLink(value: Route.artists) {
                    HStack(spacing: 4) {
                        Text("Favorite artists")
                            .font(.system(size: 12))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10))
                    }
                    .foregroundStyle(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 8)

            // Table rows
            if !nextStopShows.isEmpty {
                ForEach(nextStopShows.prefix(3), id: \.date) { show in
                    tourStopRow(show: show)
                }
            } else {
                // Fallback rows from Spec 2A
                fallbackTourStopRow(artist: "Phish", date: "2026-07-24", loc: "Alpine Valley, WI · Summer Tour 2026", rating: "★ 4.2")
                fallbackTourStopRow(artist: "Goose", date: "2026-08-02", loc: "The Anthem, Washington, DC · Summer 2026", rating: "")
                fallbackTourStopRow(artist: "WSP", date: "2026-09-18", loc: "Red Rocks, Morrison, CO · Fall Tour", rating: "")
            }
        }
        .padding(.horizontal, 0)
        .background(colors.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(colors.panelBorder, lineWidth: 1)
        )
        .padding(.horizontal, 24)
    }

    private func tourStopRow(show: ShowSummary) -> some View {
        HStack(spacing: 16) {
            Text(ArtistAbbreviations.label(for: show.artist.name))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 140, alignment: .leading)
                .lineLimit(1)

            Text(formatShowDate(show.date))
                .font(.system(size: 15))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 110, alignment: .leading)

            let subtitle = [show.where_.isEmpty ? nil : show.where_, show.tourName]
                .compactMap { $0 }
                .joined(separator: " · ")
            Text(subtitle)
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(1)

            Text("★ 4.2")
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                .frame(width: 64, alignment: .trailing)

            NavigationLink(value: Route.show(show)) {
                Circle()
                    .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1)
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                    )
            }
            .buttonStyle(.plain)
            .frame(width: 40, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .border(width: 1, edges: [.top], color: colors.divider)
    }

    private func fallbackTourStopRow(artist: String, date: String, loc: String, rating: String) -> some View {
        HStack(spacing: 16) {
            Text(ArtistAbbreviations.label(for: artist))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 140, alignment: .leading)
                .lineLimit(1)

            Text(date)
                .font(.system(size: 15))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 110, alignment: .leading)

            Text(loc)
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(1)

            Text(rating)
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                .frame(width: 64, alignment: .trailing)

            Circle()
                .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1)
                .frame(width: 30, height: 30)
                .overlay(
                    Image(systemName: "play.fill")
                        .font(.system(size: 12))
                        .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                )
                .frame(width: 40, alignment: .trailing)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .border(width: 1, edges: [.top], color: colors.divider)
    }

    // MARK: - On This Date Shelf

    private var onThisDateShelf: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Header
            HStack {
                Text("ON THIS DATE")
                    .font(.system(size: 11, weight: .semibold))
                    .tracking(1.4)
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                Spacer()

                HStack(spacing: 10) {
                    Text("\(onThisDateShows.isEmpty ? 7 : onThisDateShows.count) shows")
                        .font(.system(size: 12))
                        .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))

                    HStack(spacing: 6) {
                        Circle()
                            .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                            .frame(width: 30, height: 30)
                            .overlay(
                                Image(systemName: "chevron.left")
                                    .font(.system(size: 11))
                                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                            )

                        Circle()
                            .stroke(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
                            .frame(width: 30, height: 30)
                            .overlay(
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 11))
                                    .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                            )
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 22)
            .padding(.bottom, 8)

            GradientHairline(height: 1, opacity: 0.85)
                .padding(.horizontal, 24)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 14) {
                    if !onThisDateShows.isEmpty {
                        ForEach(onThisDateShows, id: \.date) { show in
                            NavigationLink(value: Route.show(show)) {
                                onThisDateCard(show: show)
                            }
                            .buttonStyle(.plain)
                        }
                    } else {
                        // Spec 2A fallback on this date cards
                        fallbackOnThisDateCard(artist: "Phish", date: "1993-09-03", venue: "Cabot Street Cinema, Beverly, MA", rating: "★ 4.4", hasBookmark: true)
                        fallbackOnThisDateCard(artist: "Grateful Dead", date: "1988-09-03", venue: "Capital Centre, Landover, MD", rating: "2:48", hasBookmark: false)
                        fallbackOnThisDateCard(artist: "WSP", date: "2011-09-03", venue: "Red Rocks, Morrison, CO", rating: "2:33", hasBookmark: false)
                        fallbackOnThisDateCard(artist: "Goose", date: "2021-09-03", venue: "Whitewater Amphitheater, TX", rating: "★ 4.1", hasBookmark: false)
                        fallbackOnThisDateCard(artist: "phil", date: "2003-09-03", venue: "Alpine Valley, East Troy, WI", rating: "2:56", hasBookmark: false)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 12)
                .padding(.bottom, 12)
            }
        }
    }

    private func onThisDateCard(show: ShowSummary) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Text(ArtistAbbreviations.label(for: show.artist.name))
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(colors.textPrimary)
                    .lineLimit(1)
                Spacer()
                Image(systemName: "bookmark.fill")
                    .font(.system(size: 11))
                    .foregroundStyle(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
            }

            Text(formatShowDate(show.date))
                .font(.system(size: 15))
                .foregroundStyle(colors.textPrimary)
                .padding(.top, 1)

            Text(show.where_.isEmpty ? "Live Venue" : show.where_)
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .padding(.top, 8)
                .lineLimit(1)

            HStack {
                Text("★ 4.4")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                Spacer()
                Circle()
                    .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1)
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                    )
            }
            .padding(.top, 12)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(width: 236)
        .background(colors.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(colors.panelBorder, lineWidth: 1)
        )
    }

    private func fallbackOnThisDateCard(artist: String, date: String, venue: String, rating: String, hasBookmark: Bool) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6) {
                Text(ArtistAbbreviations.label(for: artist))
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(colors.textPrimary)
                    .lineLimit(1)
                Spacer()
                if hasBookmark {
                    Image(systemName: "bookmark.fill")
                        .font(.system(size: 11))
                        .foregroundStyle(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0))
                }
            }

            Text(date)
                .font(.system(size: 15))
                .foregroundStyle(colors.textPrimary)
                .padding(.top, 1)

            Text(venue)
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .padding(.top, 8)
                .lineLimit(1)

            HStack {
                Text(rating)
                    .font(.system(size: 12))
                    .foregroundStyle(rating.starts(with: "★") ? Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0) : Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                Spacer()
                Circle()
                    .stroke(Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0), lineWidth: 1)
                    .frame(width: 30, height: 30)
                    .overlay(
                        Image(systemName: "play.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0))
                    )
            }
            .padding(.top, 12)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(width: 236)
        .background(colors.surface)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .overlay(
            RoundedRectangle(cornerRadius: 10)
                .stroke(colors.panelBorder, lineWidth: 1)
        )
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

        if !favs.isEmpty {
            onThisDateShows = await OnThisDate.load(favorites: favs, today: dateStr)
        } else {
            onThisDateShows = []
        }

        if !favs.isEmpty {
            let candidateShows = await NextStop.load(
                favorites: favs, today: dateStr, preferences: tourPreferences
            )
            nextStopShows = candidateShows
        } else {
            nextStopShows = []
        }
    }
}

private extension View {
    func border(width: CGFloat, edges: [Edge], color: Color) -> some View {
        overlay(EdgeBorder(width: width, edges: edges).foregroundColor(color))
    }
}

private struct EdgeBorder: Shape {
    var width: CGFloat
    var edges: [Edge]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        for edge in edges {
            var x: CGFloat {
                switch edge {
                case .top, .bottom, .leading: return rect.minX
                case .trailing: return rect.maxX - width
                }
            }
            var y: CGFloat {
                switch edge {
                case .top, .leading, .trailing: return rect.minY
                case .bottom: return rect.maxY - width
                }
            }
            var w: CGFloat {
                switch edge {
                case .top, .bottom: return rect.width
                case .leading, .trailing: return width
                }
            }
            var h: CGFloat {
                switch edge {
                case .top, .bottom: return width
                case .leading, .trailing: return rect.height
                }
            }
            path.addRect(CGRect(x: x, y: y, width: w, height: h))
        }
        return path
    }
}

