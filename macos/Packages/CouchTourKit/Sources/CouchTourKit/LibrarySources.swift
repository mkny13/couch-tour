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
}

public enum LibrarySources {
    public static func items(
        playlists: [LocalPlaylist],
        playlistTracks: [LocalPlaylistTrack],
        likedTracks: [LikedTrackRecord]
    ) -> [LibrarySourceItem] {
        var result: [LibrarySourceItem] = playlists.map { pl in
            LibrarySourceItem(
                id: "playlist-\(pl.id)", kind: .playlist, name: pl.name,
                subtitle: "\(pl.trackCount) \(pl.trackCount == 1 ? "track" : "tracks")",
                artist: "", addedAt: pl.updatedAt, playlist: pl, likedTrack: nil
            )
        }
        for (idx, tr) in playlistTracks.enumerated() {
            result.append(LibrarySourceItem(
                id: "track-\(tr.rowId ?? Int64(idx))", kind: .track, name: tr.title,
                subtitle: subtitle(showDate: tr.showDate, venue: tr.venueName),
                artist: tr.artistSlug ?? tr.backend, addedAt: nil, playlist: nil, likedTrack: nil
            ))
        }
        // A track liked and also in a playlist is two rows on purpose: different sources.
        for rec in likedTracks where rec.hasDisplayMetadata {
            result.append(LibrarySourceItem(
                id: "liked-\(rec.trackId)", kind: .track, name: rec.title,
                subtitle: subtitle(showDate: rec.showDate, venue: rec.venueName),
                artist: rec.artistSlug ?? rec.backend, addedAt: rec.likedAt,
                playlist: nil, likedTrack: rec
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
