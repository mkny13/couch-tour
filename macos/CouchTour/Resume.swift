import CouchTourKit
import Foundation

/// Turns a stored `PlaybackProgress` row back into a fetchable `ShowDetail`, mirroring
/// Android's `PlayerViewModel.resume` — parse the queue key, dispatch on its kind, re-fetch
/// from the network rather than trusting anything stale in the row beyond display fields.
/// Playlists are out of scope for the desktop MVP (D5-equivalent), so a playlist key resolves
/// to nothing rather than a broken fetch.
enum ResumeError: Error {
    case unresumable
}

func resolveShowDetail(for progress: PlaybackProgress) async throws -> ShowDetail {
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
    }
}

/// Resumes at the stored track/position, unless the queue already finished — replaying a
/// finished queue restarts from the top rather than reopening it a second from the end (D22).
@MainActor
func resume(_ progress: PlaybackProgress, player: Player) async throws {
    let detail = try await resolveShowDetail(for: progress)
    guard !detail.tracks.isEmpty else { throw ResumeError.unresumable }
    if progress.finished {
        player.play(detail: detail, startIndex: 0)
    } else {
        let startIndex = min(max(progress.trackIndex, 0), detail.tracks.count - 1)
        player.play(detail: detail, startIndex: startIndex, resumePositionMs: progress.positionMs)
    }
}
