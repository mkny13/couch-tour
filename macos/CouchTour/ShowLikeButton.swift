import CouchTourKit
import SwiftUI

/// The show-detail header's Like pill (#148) — the replacement for the removed Save/bookmark
/// concept (ROADMAP: "Two Ways to Mark a Show, Not Three"). A phish.in server-side show like,
/// the same POST a track row's `TrackLikeButton` makes with `.track`; toggled optimistically
/// and rolled back on failure. Hidden for Relisten shows, which have no show-like concept —
/// `ShowSummary.id` stays at its 0 default there, which is what this keys off.
struct ShowLikeButton: View {
    let showID: Int64
    let likesCount: Int
    let likedByUser: Bool

    @EnvironmentObject private var session: PhishInSession
    @Environment(\.ledgerColors) private var colors

    @State private var liked: Bool
    @State private var count: Int

    init(showID: Int64, likesCount: Int, likedByUser: Bool) {
        self.showID = showID
        self.likesCount = likesCount
        self.likedByUser = likedByUser
        _liked = State(initialValue: likedByUser)
        _count = State(initialValue: likesCount)
    }

    var body: some View {
        if showID != 0 {
            likePill
                // Signed out the public count still shows, but tapping is inert — the same
                // gate TrackLikeButton applies, matching Android's Session.username check.
                .disabled(session.username == nil)
        }
    }

    private var likePill: some View {
        Button {
            toggle()
        } label: {
            HStack(spacing: 8) {
                Image(systemName: liked ? "heart.fill" : "heart")
                    .font(.system(size: 14))
                if count > 0 {
                    Text("\(count)")
                        .font(.system(size: 14))
                }
            }
            .frame(height: 38)
            .padding(.horizontal, 14)
            .foregroundStyle(liked ? colors.accentTintText : colors.textSubtle)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(liked ? colors.accent : colors.controlOutline, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func toggle() {
        let wasLiked = liked
        liked.toggle()
        count += liked ? 1 : -1
        Task {
            do {
                if wasLiked {
                    try await PhishInAPI.unlike(.show, showID)
                } else {
                    try await PhishInAPI.like(.show, showID)
                }
            } catch {
                liked = wasLiked
                count += wasLiked ? 1 : -1
            }
        }
    }
}