import CouchTourKit
import SwiftUI

/// Debounced search view matching macOS Screen 2B.
/// Features prominent query header, stagelight hairline, count tabs with underline,
/// filter chips, and fixed-column ledger table layout with Type and Jam Chart badges.
struct SearchView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @Environment(\.ledgerColors) private var colors

    @State private var hits: SearchHits?
    @State private var selectedArtistKey: String?
    @State private var selectedTab: SearchTab = .all
    @State private var sortMode: SearchSortMode = .relevance

    enum SearchTab: String, CaseIterable {
        case all = "All"
        case tracks = "Tracks"
        case shows = "Shows"
        case songs = "Songs"
    }

    private var term: String { appModel.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        VStack(spacing: 0) {
            // Query Header
            HStack(spacing: 12) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 18))
                    .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                TextField("Search artists, shows, tracks…", text: $appModel.searchQuery)
                    .textFieldStyle(.plain)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundStyle(colors.textPrimary)

                if !appModel.searchQuery.isEmpty {
                    Button {
                        appModel.searchQuery = ""
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 14))
                            .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                            .frame(width: 28, height: 28)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 16)
            .padding(.bottom, 14)

            // Stagelight gradient hairline
            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [
                            Color.clear,
                            Color(red: 0x5B / 255.0, green: 0x8C / 255.0, blue: 1.0).opacity(0.8),
                            Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0),
                            Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0),
                            Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0).opacity(0.8),
                            Color.clear
                        ],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .frame(height: 1)

            // Tabs with counts and underline
            HStack(spacing: 22) {
                let trackCount = hits?.tracks.count ?? 0
                let showCount = hits?.shows.count ?? 0
                let songCount = hits?.slices.count ?? 0
                let allCount = trackCount + showCount + songCount

                searchTabItem(title: "All", count: allCount, tab: .all)
                searchTabItem(title: "Tracks", count: trackCount, tab: .tracks)
                searchTabItem(title: "Shows", count: showCount, tab: .shows)
                searchTabItem(title: "Songs", count: songCount, tab: .songs)

                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.top, 14)
            .padding(.bottom, 10)

            // Filter chips
            HStack(spacing: 8) {
                filterPill("Longest first ▾", isSelected: true)
                filterPill("Artist ▾")
                filterPill("Years ▾")
                filterPill("Soundboard")
                filterPill("Jam chart only")
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 12)

            // Table Column Headers
            HStack(spacing: 10) {
                Text("ARTIST")
                    .frame(width: 110, alignment: .leading)
                Text("DATE")
                    .frame(width: 100, alignment: .leading)
                Text("TITLE / TRACK")
                    .frame(width: 150, alignment: .leading)
                Text("VENUE")
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text("TIME · LIKES")
                    .frame(width: 90, alignment: .trailing)
                Spacer()
                    .frame(width: 34)
            }
            .font(.system(size: 11, weight: .semibold))
            .tracking(1.4)
            .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
            .padding(.horizontal, 24)
            .padding(.bottom, 8)

            Divider()
                .overlay(Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0))
                .padding(.horizontal, 24)

            // Results List
            ScrollView {
                LazyVStack(spacing: 0) {
                    if term.count < 3 {
                        VStack(spacing: 12) {
                            Image(systemName: "magnifyingglass")
                                .font(.system(size: 32))
                                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                            Text("Type at least 3 characters to search artists, shows, and tracks")
                                .font(.system(size: 14))
                                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                    } else if let hits {
                        let totalHits = hits.tracks.count + hits.shows.count + hits.slices.count
                        if totalHits == 0 {
                            VStack(spacing: 12) {
                                Image(systemName: "magnifyingglass")
                                    .font(.system(size: 32))
                                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                                Text("No results found for \"\(term)\"")
                                    .font(.system(size: 14))
                                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.top, 60)
                        } else {
                            switch selectedTab {
                            case .all:
                                ForEach(hits.tracks, id: \.id) { track in
                                    searchTrackRow(track: track)
                                }
                                ForEach(hits.shows, id: \.self) { show in
                                    searchShowRow(show: show)
                                }
                                ForEach(hits.slices, id: \.self) { slice in
                                    searchSliceRow(slice: slice)
                                }
                            case .tracks:
                                ForEach(hits.tracks, id: \.id) { track in
                                    searchTrackRow(track: track)
                                }
                            case .shows:
                                ForEach(hits.shows, id: \.self) { show in
                                    searchShowRow(show: show)
                                }
                            case .songs:
                                ForEach(hits.slices, id: \.self) { slice in
                                    searchSliceRow(slice: slice)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 24)
            }
        }
        .background(colors.background)
        .task(id: term) {
            guard term.count >= 3 else {
                hits = nil
                return
            }
            try? await Task.sleep(for: .milliseconds(300))
            guard !Task.isCancelled else { return }
            hits = await searchAll(term)
        }
    }

    private func searchTabItem(title: String, count: Int, tab: SearchTab) -> some View {
        Button {
            selectedTab = tab
        } label: {
            VStack(spacing: 6) {
                Text("\(title) \(count)")
                    .font(.system(size: 13, weight: selectedTab == tab ? .medium : .regular))
                    .foregroundStyle(selectedTab == tab ? colors.textPrimary : Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))

                Rectangle()
                    .fill(selectedTab == tab ? Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0) : Color.clear)
                    .frame(height: 2)
            }
        }
        .buttonStyle(.plain)
    }

    private func filterPill(_ title: String, isSelected: Bool = false) -> some View {
        Text(title)
            .font(.system(size: 13))
            .padding(.horizontal, 12)
            .frame(height: 30)
            .foregroundStyle(isSelected ? Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0) : Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
            .background(isSelected ? Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.14) : Color.clear, in: Capsule())
            .overlay(
                Capsule().stroke(isSelected ? Color(red: 0xB5 / 255.0, green: 0xAB / 255.0, blue: 0xFC / 255.0) : Color(red: 0x3F / 255.0, green: 0x42 / 255.0, blue: 0x4D / 255.0), lineWidth: 1)
            )
    }

    private func searchTrackRow(track: Track) -> some View {
        HStack(spacing: 10) {
            Text("Phish")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 110, alignment: .leading)
                .lineLimit(1)

            Text(formatShowDate(track.showDate ?? ""))
                .font(.system(size: 15))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 100, alignment: .leading)

            Text(track.title)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 150, alignment: .leading)
                .lineLimit(1)

            VStack(alignment: .leading, spacing: 2) {
                Text(track.venueName ?? "Live Venue")
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                    .lineLimit(1)

                Text(track.venueLocation ?? "")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 6) {
                Text(fmt(track.duration))
                    .font(.system(size: 13))
                    .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))

                if track.likesCount > 0 {
                    HStack(spacing: 3) {
                        Image(systemName: "heart.fill")
                            .font(.system(size: 10))
                            .foregroundStyle(Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0))
                        Text("\(track.likesCount)")
                            .font(.system(size: 11))
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                    }
                }
            }
            .frame(width: 90, alignment: .trailing)

            Button {
                guard let showDate = track.showDate else { return }
                Task {
                    do {
                        let detail = try await sourceFor(.phishin).show(artist: PHISH, date: showDate, recordingId: nil)
                        if let idx = detail.tracks.firstIndex(where: { $0.id == String(track.id) }) {
                            player.play(detail: detail, startIndex: idx)
                            appModel.showNowPlaying = true
                        }
                    } catch {}
                }
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
            .frame(width: 34, alignment: .trailing)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            if let showDate = track.showDate {
                let summary = ShowSummary(artist: PHISH, date: showDate, venue: track.venueName, location: track.venueLocation)
                appModel.path.append(.show(summary))
            }
        }
        .padding(.vertical, 10)
        .border(width: 1, edges: [.bottom], color: colors.divider)
    }

    private func searchShowRow(show: ShowSummary) -> some View {
        HStack(spacing: 10) {
            Text(ArtistAbbreviations.label(for: show.artist.name))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 110, alignment: .leading)
                .lineLimit(1)

            Text(formatShowDate(show.date))
                .font(.system(size: 15))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 100, alignment: .leading)

            Text(show.tourName ?? "Show")
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 150, alignment: .leading)
                .lineLimit(1)

            VStack(alignment: .leading, spacing: 2) {
                Text(show.venue ?? "Live Venue")
                    .font(.system(size: 14))
                    .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                    .lineLimit(1)

                Text(show.location ?? "")
                    .font(.system(size: 12))
                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 6) {
                if show.rating > 0 {
                    Text(String(format: "★ %.1f", show.rating))
                        .font(.system(size: 13))
                        .foregroundStyle(Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))
                }

                if show.likesCount > 0 {
                    HStack(spacing: 3) {
                        Image(systemName: "heart.fill")
                            .font(.system(size: 10))
                            .foregroundStyle(Color(red: 0xF0 / 255.0, green: 0x6B / 255.0, blue: 0xB0 / 255.0))
                        Text("\(show.likesCount)")
                            .font(.system(size: 11))
                            .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                    }
                }
            }
            .frame(width: 90, alignment: .trailing)

            Button {
                Task {
                    do {
                        let detail = try await sourceFor(show.artist.backend).show(artist: show.artist, date: show.date, recordingId: nil)
                        player.play(detail: detail, startIndex: 0)
                        appModel.showNowPlaying = true
                    } catch {}
                }
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
            .frame(width: 34, alignment: .trailing)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            appModel.path.append(.show(show))
        }
        .padding(.vertical, 10)
        .border(width: 1, edges: [.bottom], color: colors.divider)
    }

    private func searchSliceRow(slice: SliceHit) -> some View {
        HStack(spacing: 10) {
            Text(ArtistAbbreviations.label(for: slice.artist.name))
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 110, alignment: .leading)
                .lineLimit(1)

            Text(slice.kind.heading)
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0x93 / 255.0, green: 0x97 / 255.0, blue: 0xAB / 255.0))
                .frame(width: 100, alignment: .leading)

            Text(slice.period.label)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(colors.textPrimary)
                .frame(width: 150, alignment: .leading)
                .lineLimit(1)

            Text("\(slice.period.showCount) \(plural(slice.period.showCount, "show"))")
                .font(.system(size: 14))
                .foregroundStyle(Color(red: 0xCF / 255.0, green: 0xD3 / 255.0, blue: 0xE5 / 255.0))
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            Spacer()
                .frame(width: 90)

            Image(systemName: "chevron.right")
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .frame(width: 34, alignment: .trailing)
        }
        .contentShape(Rectangle())
        .onTapGesture {
            appModel.path.append(.period(artist: slice.artist, period: slice.period))
        }
        .padding(.vertical, 10)
        .border(width: 1, edges: [.bottom], color: colors.divider)
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

