import CouchTourKit
import SwiftUI

/// Left sidebar for the 3-pane macOS Ledger layout.
/// Displays Navigation items (Home, Search, Library, Settings),
/// Favorite Artists with show counts and unread cues, and Sync pairing status.
struct SidebarView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @Environment(\.ledgerColors) private var colors

    let favoritedArtists: [ArtistRef]
    let onSelectArtist: (ArtistRef) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // App Branding Header
            HStack(spacing: 8) {
                Circle()
                    .fill(Color(red: 0xF2 / 255.0, green: 0xA9 / 255.0, blue: 0x3B / 255.0))
                    .frame(width: 10, height: 10)
                Text("COUCH TOUR")
                    .font(.system(size: 11, weight: .bold))
                    .tracking(1.4)
                    .foregroundStyle(colors.textSecondary)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, 20)

            // Primary Navigation
            VStack(spacing: 2) {
                SidebarNavItem(
                    title: "Home",
                    icon: "house",
                    isSelected: appModel.path.isEmpty
                ) {
                    appModel.path.removeAll()
                }

                SidebarNavItem(
                    title: "Search",
                    icon: "magnifyingglass",
                    isSelected: appModel.path.last == .search
                ) {
                    if appModel.path.last != .search {
                        appModel.path.append(.search)
                    }
                }

                SidebarNavItem(
                    title: "Library",
                    icon: "books.vertical",
                    isSelected: appModel.path.last == .playlists || appModel.path.last == .listening
                ) {
                    if appModel.path.last != .playlists {
                        appModel.path.append(.playlists)
                    }
                }
            }
            .padding(.horizontal, 10)

            Divider()
                .overlay(colors.divider)
                .padding(.vertical, 14)
                .padding(.horizontal, 14)

            // FAVORITES Section
            VStack(alignment: .leading, spacing: 6) {
                Text("FAVORITES")
                    .font(.system(size: 10, weight: .bold))
                    .tracking(1.2)
                    .foregroundStyle(colors.textMuted)
                    .padding(.horizontal, 16)

                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(favoritedArtists, id: \.key) { artist in
                            Button {
                                onSelectArtist(artist)
                            } label: {
                                HStack {
                                    Text(artist.name)
                                        .font(.system(size: 13))
                                        .foregroundStyle(colors.textPrimary)
                                        .lineLimit(1)
                                    Spacer()
                                    Text("\(artist.showCount)")
                                        .font(.system(size: 11))
                                        .foregroundStyle(colors.textMuted)
                                }
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.clear, in: RoundedRectangle(cornerRadius: 6))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 10)
                }
            }

            Spacer()

            // SYNC STATUS Footer
            Divider()
                .overlay(colors.divider)
                .padding(.horizontal, 14)

            HStack(spacing: 8) {
                Circle()
                    .fill(appModel.syncSession.paired ? Color.green : colors.textMuted.opacity(0.5))
                    .frame(width: 8, height: 8)

                VStack(alignment: .leading, spacing: 2) {
                    Text(appModel.syncSession.paired ? "Sync active" : "Not paired")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(colors.textPrimary)

                    if let lastSynced = appModel.syncSession.lastSyncedAt, lastSynced > 0 {
                        Text("Synced \(timeAgo(lastSynced))")
                            .font(.system(size: 10))
                            .foregroundStyle(colors.textMuted)
                    }
                }

                Spacer()

                Button {
                    appModel.syncNow()
                } label: {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 12))
                        .foregroundStyle(colors.accent)
                }
                .buttonStyle(.plain)
            }
            .padding(16)
        }
        .frame(width: 236)
        .background(colors.elevated)
    }

    private func timeAgo(_ timestamp: Int64) -> String {
        let diff = Date().timeIntervalSince1970 - Double(timestamp) / 1000.0
        if diff < 60 { return "just now" }
        if diff < 3600 { return "\(Int(diff / 60))m ago" }
        return "\(Int(diff / 3600))h ago"
    }
}

private struct SidebarNavItem: View {
    let title: String
    let icon: String
    let isSelected: Bool
    let action: () -> Void

    @Environment(\.ledgerColors) private var colors

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 14))
                    .frame(width: 18)
                    .foregroundStyle(isSelected ? colors.accent : colors.textMuted)

                Text(title)
                    .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                    .foregroundStyle(isSelected ? colors.textPrimary : colors.textSecondary)

                Spacer()
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(
                isSelected ? colors.surface : Color.clear,
                in: RoundedRectangle(cornerRadius: 6)
            )
        }
        .buttonStyle(.plain)
    }
}
