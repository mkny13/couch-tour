import CouchTourKit
import SwiftUI

/// Likes for a track row (#58): phish.in's account-gated, server-side like (with a public
/// count) for `.phishin` tracks, Relisten's local-only `LikedTracks` heart (no count, no auth
/// check) for `.relisten` ones — port of Android's `LikeButton`/`LikeTrackButton` split, folded
/// into one view since this app already unified both backends' rows into one `TrackRow`.
struct TrackLikeButton: View {
    let backend: Backend
    let trackID: String
    let likesCount: Int
    let likedByUser: Bool
    /// Display metadata persisted when a Relisten track is liked, so the Library can list and
    /// play it (#539). Unused for phish.in, whose likes live on the account.
    let record: LikedTrackRecord?

    @EnvironmentObject private var session: PhishInSession
    @EnvironmentObject private var likedTracks: LikedTracks

    @State private var liked: Bool
    @State private var count: Int

    init(backend: Backend, trackID: String, likesCount: Int, likedByUser: Bool, record: LikedTrackRecord? = nil) {
        self.backend = backend
        self.trackID = trackID
        self.likesCount = likesCount
        self.likedByUser = likedByUser
        self.record = record
        _liked = State(initialValue: likedByUser)
        _count = State(initialValue: likesCount)
    }

    var body: some View {
        switch backend {
        case .relisten:
            let isLiked = likedTracks.ids.contains(trackID)
            Button {
                if var record {
                    record.likedAt = Int64(Date().timeIntervalSince1970 * 1000)
                    likedTracks.toggle(record)
                } else {
                    likedTracks.toggle(trackID)
                }
            } label: {
                Image(systemName: isLiked ? "heart.fill" : "heart")
                    .foregroundStyle(isLiked ? .pink : .secondary)
            }
            .buttonStyle(.plain)

        case .phishin:
            // Signed out it still shows the public count, but tapping is inert — matches
            // Android's Session.username gate.
            let signedIn = session.username != nil
            Button {
                toggle()
            } label: {
                HStack(spacing: 3) {
                    Image(systemName: liked ? "heart.fill" : "heart")
                        .foregroundStyle(liked ? .pink : .secondary)
                    if count > 0 {
                        Text("\(count)").font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
            .buttonStyle(.plain)
            .disabled(!signedIn)
        }
    }

    private func toggle() {
        guard let id = Int64(trackID) else { return }
        let wasLiked = liked
        liked.toggle()
        count += liked ? 1 : -1
        Task {
            do {
                if wasLiked {
                    try await PhishInAPI.unlike(.track, id)
                } else {
                    try await PhishInAPI.like(.track, id)
                }
            } catch {
                liked = wasLiked
                count += wasLiked ? 1 : -1
            }
        }
    }
}
