import Foundation

/// One Library row's identity and sort keys, independent of SwiftUI (#539). The Library lists
/// saved local items only — playback history belongs to `ListeningView`, so nothing here reads
/// `ProgressStore`.
public struct LibrarySourceItem: Equatable, Identifiable {
    public enum Kind: String, Sendable { case playlist = "PLAYLIST", show = "SHOW", track = "TRACK" }

    public let id: String
    public let kind: Kind
    public let name: String
    public let subtitle: String
    public let artist: String
    /// Epoch ms; nil sorts last under "Recently added".
    public let addedAt: Int64?
    public let playlist: LocalPlaylist?
    /// Set for liked-track rows, which play from their stored record.
    public let likedTrack: LikedTrackRecord?
    /// Account rows (#540): each routes to the existing destination for its kind.
    public let accountPlaylist: PublicPlaylistSummary?
    public let accountShow: ShowSummary?
    public let accountTrack: Track?

    init(
        id: String, kind: Kind, name: String, subtitle: String, artist: String, addedAt: Int64?,
        playlist: LocalPlaylist? = nil, likedTrack: LikedTrackRecord? = nil,
        accountPlaylist: PublicPlaylistSummary? = nil, accountShow: ShowSummary? = nil,
        accountTrack: Track? = nil
    ) {
        self.id = id
        self.kind = kind
        self.name = name
        self.subtitle = subtitle
        self.artist = artist
        self.addedAt = addedAt
        self.playlist = playlist
        self.likedTrack = likedTrack
        self.accountPlaylist = accountPlaylist
        self.accountShow = accountShow
        self.accountTrack = accountTrack
    }
}

/// The signed-in phish.in account's saved content (#540), fetched live and never persisted.
public struct LibraryAccountData: Equatable {
    public var playlists: [PublicPlaylistSummary] = []
    public var shows: [Show] = []
    public var tracks: [Track] = []
    /// True when a request failed; the lists are then empty and must not read as "no content".
    public var failed = false

    public init(
        playlists: [PublicPlaylistSummary] = [], shows: [Show] = [], tracks: [Track] = [], failed: Bool = false
    ) {
        self.playlists = playlists
        self.shows = shows
        self.tracks = tracks
        self.failed = failed
    }

    public static let empty = LibraryAccountData()

    /// Signed out makes zero requests (D26: `filter=mine` returns every public playlist when
    /// unauthenticated). Any failure keeps the whole result empty-but-flagged so local items
    /// stay and the UI can warn instead of showing a silent empty account.
    public static func load(signedIn: Bool) async -> LibraryAccountData {
        guard signedIn, let token = PhishInAPI.authToken, !token.isEmpty else { return .empty }
        do {
            async let mine = PhishInAPI.accountPlaylists(filter: "mine")
            async let liked = PhishInAPI.accountPlaylists(filter: "liked")
            async let shows = PhishInAPI.likedShows()
            async let tracks = PhishInAPI.likedTracks()
            return try await LibraryAccountData(
                playlists: mine + liked, shows: shows, tracks: tracks)
        } catch {
            return LibraryAccountData(failed: true)
        }
    }
}

public enum LibrarySources {
    public static func items(
        playlists: [LocalPlaylist],
        playlistTracks: [LocalPlaylistTrack],
        likedTracks: [LikedTrackRecord],
        account: LibraryAccountData = .empty
    ) -> [LibrarySourceItem] {
        var result: [LibrarySourceItem] = playlists.map { pl in
            LibrarySourceItem(
                id: "playlist-\(pl.id)", kind: .playlist, name: pl.name,
                subtitle: "\(pl.trackCount) \(pl.trackCount == 1 ? "track" : "tracks")",
                artist: "", addedAt: pl.updatedAt, playlist: pl
            )
        }
        for (idx, tr) in playlistTracks.enumerated() {
            result.append(LibrarySourceItem(
                id: "track-\(tr.rowId ?? Int64(idx))", kind: .track, name: tr.title,
                subtitle: subtitle(showDate: tr.showDate, venue: tr.venueName),
                artist: tr.artistSlug ?? tr.backend, addedAt: nil
            ))
        }
        // A track liked and also in a playlist is two rows on purpose: different sources.
        for rec in likedTracks where rec.hasDisplayMetadata {
            result.append(LibrarySourceItem(
                id: "liked-\(rec.trackId)", kind: .track, name: rec.title,
                subtitle: subtitle(showDate: rec.showDate, venue: rec.venueName),
                artist: rec.artistSlug ?? rec.backend, addedAt: rec.likedAt,
                likedTrack: rec
            ))
        }
        // phish.in gives no like-added time, so account rows stay undated and follow dated local
        // rows under Recently added. A playlist both created and liked is one row (by slug).
        var seenSlugs = Set<String>()
        for pl in account.playlists where seenSlugs.insert(pl.slug).inserted {
            result.append(LibrarySourceItem(
                id: "account-playlist-\(pl.slug)", kind: .playlist, name: pl.name,
                subtitle: "\(pl.tracksCount) \(pl.tracksCount == 1 ? "track" : "tracks")",
                artist: pl.author ?? "phish.in", addedAt: nil, accountPlaylist: pl
            ))
        }
        for show in account.shows {
            let summary = show.toShowSummary()
            result.append(LibrarySourceItem(
                id: "account-show-\(show.date)", kind: .show, name: show.date,
                subtitle: summary.where_, artist: PHISH.name, addedAt: nil, accountShow: summary
            ))
        }
        for tr in account.tracks {
            result.append(LibrarySourceItem(
                id: "account-track-\(tr.id)", kind: .track, name: tr.title,
                subtitle: subtitle(showDate: tr.showDate ?? "", venue: tr.venueName),
                artist: PHISH.name, addedAt: nil, accountTrack: tr
            ))
        }
        return result
    }

    public enum Sort: Sendable { case recentlyAdded, title, artist }

    /// Stable: ties keep source order. Undated rows sort after dated ones, newest first.
    public static func sorted(_ items: [LibrarySourceItem], by sort: Sort) -> [LibrarySourceItem] {
        let indexed = Array(items.enumerated())
        let ordered: [(offset: Int, element: LibrarySourceItem)]
        switch sort {
        case .recentlyAdded:
            ordered = indexed.sorted { a, b in
                switch (a.element.addedAt, b.element.addedAt) {
                case let (x?, y?): return x != y ? x > y : a.offset < b.offset
                case (_?, nil): return true
                case (nil, _?): return false
                case (nil, nil): return a.offset < b.offset
                }
            }
        case .title:
            ordered = indexed.sorted { compare($0, $1) { $0.name } }
        case .artist:
            ordered = indexed.sorted { compare($0, $1) { $0.artist } }
        }
        return ordered.map(\.element)
    }

    private static func compare(
        _ a: (offset: Int, element: LibrarySourceItem),
        _ b: (offset: Int, element: LibrarySourceItem),
        key: (LibrarySourceItem) -> String
    ) -> Bool {
        let r = key(a.element).localizedCaseInsensitiveCompare(key(b.element))
        return r == .orderedSame ? a.offset < b.offset : r == .orderedAscending
    }

    private static func subtitle(showDate: String, venue: String?) -> String {
        [showDate, venue ?? ""].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}
