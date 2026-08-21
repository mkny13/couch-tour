import CouchTourKit
import Foundation

/// Turns a stored `PlaybackProgress` row back into a fetchable `ShowDetail`, mirroring
/// Android's `PlayerViewModel.resume` — parse the queue key, dispatch on its kind, re-fetch
/// from the network rather than trusting anything stale in the row beyond display fields.
/// Server-side (phish.in) playlists are still out of scope (D5-equivalent), so a `.playlist`
/// key resolves to nothing; local playlists (#59) are handled below.
enum ResumeError: Error {
    case unresumable
}

func resolveShowDetail(for progress: PlaybackProgress, localPlaylistStore: LocalPlaylistStore?) async throws -> ShowDetail {
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
        throw ResumeError.unresumable

    case .localPlaylist:
        guard let localPlaylistStore, let playlist = try? localPlaylistStore.playlist(id: ref.id) else {
            throw ResumeError.unresumable
        }
        return try await localPlaylistShowDetail(playlist, store: localPlaylistStore)
    }
}

/// Resumes at the stored track/position, unless the queue already finished — replaying a
/// finished queue restarts from the top rather than reopening it a second from the end (D22).
@MainActor
func resume(_ progress: PlaybackProgress, player: Player, localPlaylistStore: LocalPlaylistStore?) async throws {
    let detail = try await resolveShowDetail(for: progress, localPlaylistStore: localPlaylistStore)
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
/// `artist.name`/`date` here are what `MiniPlayerView`/`NowPlayingInspector`/History's artist
/// filter show for this queue, so the playlist's own name and track count stand in for a real
/// show's artist and date (each local playlist becomes its own row in History's filter).
func localPlaylistShowDetail(_ playlist: LocalPlaylist, store: LocalPlaylistStore) async throws -> ShowDetail {
    let rows = try store.tracks(playlistId: playlist.id)
    let tracks = await resolveLocalPlaylistTracks(rows)
    let artist = ArtistRef(backend: .phishin, id: "local:\(playlist.id)", name: playlist.name)
    let summary = ShowSummary(artist: artist, date: "\(tracks.count) \(plural(tracks.count, "track"))")
    return ShowDetail(summary: summary, tracks: tracks, queueKey: localPlaylistQueueKey(playlist.id))
}
