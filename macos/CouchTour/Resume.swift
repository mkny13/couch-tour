import CouchTourKit
import Foundation

/// Turns a stored `PlaybackProgress` row back into a fetchable `ShowDetail`, mirroring
/// Android's `PlayerViewModel.resume` — parse the queue key, dispatch on its kind, re-fetch
/// from the network rather than trusting anything stale in the row beyond display fields.
/// A `.playlist` key is a public phish.in playlist (#428), re-fetched by slug; local playlists
/// (#59) are handled below.
enum ResumeError: LocalizedError {
    case unresumable

    var errorDescription: String? {
        switch self {
        case .unresumable:
            return "This item can no longer be resumed."
        }
    }
}

@MainActor
func resolveShowDetail(
    for progress: PlaybackProgress,
    localPlaylistStore: LocalPlaylistStore?,
    likedTracks: LikedTracks? = nil
) async throws -> ShowDetail {
    guard let ref = parseQueueKey(progress.queueKey) else { throw ResumeError.unresumable }
    switch ref.kind {
    case .show:
        return try await sourceFor(.phishin).show(artist: PHISH, date: ref.id, recordingId: nil)

    case .recording:
        guard let recordingID = parseRecordingId(ref.id) else { throw ResumeError.unresumable }
        // The row's own `artist` is denormalised precisely so this doesn't need a second
        // fetch just to get a display name — see Progress.artist in ProgressStore.swift.
        let artist = ArtistRef(backend: .relisten, id: recordingID.artistSlug, name: progress.artist)
        return try await sourceFor(.relisten).show(
            artist: artist, date: recordingID.date, recordingId: recordingID.sourceId
        )

    case .playlist:
        return try await PhishInAPI.publicPlaylist(ref.id).toShowDetail()

    // YouTube resume isn't implemented yet — a `youtube:` row (D253) can't be
    // re-fetched into a ShowDetail, so it can't be resumed from history.
    case .youtube:
        throw ResumeError.unresumable

    case .localPlaylist:
        guard let localPlaylistStore, let playlist = try? localPlaylistStore.playlist(id: ref.id) else {
            throw ResumeError.unresumable
        }
        return try await localPlaylistShowDetail(playlist, store: localPlaylistStore)

    case .likedTrack:
        if ref.id.hasPrefix(phishinLikedTrackPrefix) {
            let idString = String(ref.id.dropFirst(phishinLikedTrackPrefix.count))
            guard let trackId = Int64(idString) else {
                throw ResumeError.unresumable
            }
            let tracks: [Track]
            do {
                tracks = try await PhishInAPI.likedTracks()
            } catch {
                throw ResumeError.unresumable
            }
            guard let track = tracks.first(where: { $0.id == trackId }), track.playable else {
                throw ResumeError.unresumable
            }
            let showDate = track.showDate ?? progress.title
            let summary = ShowSummary(artist: PHISH, date: showDate, venue: track.venueName)
            return ShowDetail(
                summary: summary,
                tracks: [track.toPlayableTrack(showArt: track.showAlbumCoverUrl)],
                queueKey: phishinLikedTrackQueueKey(trackId)
            )
        } else {
            guard let likedTracks, let record = likedTracks.records[ref.id] else {
                throw ResumeError.unresumable
            }
            let tracks = await resolveLocalPlaylistTracks([record.asPlaylistTrack])
            guard !tracks.isEmpty else {
                throw ResumeError.unresumable
            }
            let backend = Backend(rawValue: record.backend) ?? .relisten
            let artist = ArtistRef(backend: backend, id: record.artistSlug ?? "", name: record.artistSlug ?? "Relisten")
            let summary = ShowSummary(artist: artist, date: record.showDate)
            return ShowDetail(summary: summary, tracks: tracks, queueKey: likedTrackQueueKey(record.trackId))
        }
    }
}

/// Where tapping a Continue Listening row's artwork/title should navigate (#98). A local
/// playlist can't go through `ShowDetailView` — it re-fetches by `artist.backend`/`date`, and a
/// local playlist's synthetic summary (see `localPlaylistShowDetail` below) has neither — so it
/// routes to `LocalPlaylistView` instead, the same destination `LocalPlaylistsView` already uses.
enum ResumeNavigationTarget: Hashable {
    case show(ShowSummary)
    case localPlaylist(LocalPlaylist)
    case publicPlaylist(PublicPlaylistSummary)
}

/// Resolves a stored `PlaybackProgress` row to where tapping it should navigate. Checks for a
/// local playlist first — a cheap local `LocalPlaylistStore` read — rather than routing it
/// through `resolveShowDetail`'s heavier track-resolving `.localPlaylist` branch, which exists
/// to build a playable queue, not just to identify the target screen.
@MainActor
func resolveNavigationTarget(
    for progress: PlaybackProgress,
    localPlaylistStore: LocalPlaylistStore?,
    likedTracks: LikedTracks? = nil
) async throws -> ResumeNavigationTarget {
    guard let ref = parseQueueKey(progress.queueKey) else { throw ResumeError.unresumable }
    if ref.kind == .localPlaylist {
        guard let localPlaylistStore, let playlist = try? localPlaylistStore.playlist(id: ref.id) else {
            throw ResumeError.unresumable
        }
        return .localPlaylist(playlist)
    }
    if ref.kind == .playlist {
        return .publicPlaylist(try await PhishInAPI.publicPlaylist(ref.id).summary)
    }
    let detail = try await resolveShowDetail(
        for: progress,
        localPlaylistStore: localPlaylistStore,
        likedTracks: likedTracks
    )
    return .show(detail.summary)
}

/// Resumes at the stored track/position, unless the queue already finished — replaying a
/// finished queue restarts from the top rather than reopening it a second from the end (D22).
@MainActor
func resume(
    _ progress: PlaybackProgress,
    player: Player,
    localPlaylistStore: LocalPlaylistStore?,
    likedTracks: LikedTracks? = nil
) async throws {
    let detail = try await resolveShowDetail(
        for: progress,
        localPlaylistStore: localPlaylistStore,
        likedTracks: likedTracks
    )
    guard !detail.tracks.isEmpty else { throw ResumeError.unresumable }
    if progress.finished {
        player.play(detail: detail, startIndex: 0)
    } else {
        let startIndex = min(max(progress.trackIndex, 0), detail.tracks.count - 1)
        player.play(detail: detail, startIndex: startIndex, resumePositionMs: progress.positionMs)
    }
}

/// A local playlist's tracks, wrapped in a `ShowDetail` so it can flow through the same
/// `Player.play(detail:)`/resume path every other queue kind uses — `queueKey` is passed in
/// explicitly since a playlist spans arbitrary shows, not one `summary.artist.backend`.
/// `artist.name`/`date` here are what History's artist
/// filter show for this queue, so the playlist's own name and track count stand in for a real
/// show's artist and date (each local playlist becomes its own row in History's filter).
func localPlaylistShowDetail(_ playlist: LocalPlaylist, store: LocalPlaylistStore) async throws -> ShowDetail {
    let rows = try store.tracks(playlistId: playlist.id)
    let tracks = await resolveLocalPlaylistTracks(rows)
    let artist = ArtistRef(backend: .phishin, id: "local:\(playlist.id)", name: playlist.name)
    let summary = ShowSummary(artist: artist, date: "\(tracks.count) \(plural(tracks.count, "track"))")
    return ShowDetail(summary: summary, tracks: tracks, queueKey: localPlaylistQueueKey(playlist.id))
}
