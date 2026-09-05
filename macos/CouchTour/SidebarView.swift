import CouchTourKit
import SwiftUI

/// Left sidebar for the 3-pane macOS Ledger layout (Screen 2A/2B/2C/2E).
/// Displays Window traffic lights, primary navigation (Home, Search, Library, History, Settings),
/// FAVORITE ARTISTS with show counts and gradient hairline, and Synced with phone footer.
struct SidebarView: View {
    @EnvironmentObject private var appModel: AppModel
    @EnvironmentObject private var player: Player
    @Environment(\.ledgerColors) private var colors

    let favoritedArtists: [ArtistRef]
    let onSelectArtist: (ArtistRef) -> Void

    private static let countFormatter: NumberFormatter = {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Window Traffic Lights
            TrafficLights()
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 22)

            // Primary Navigation
            VStack(spacing: 1) {
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
                    isSelected: appModel.path.last == .playlists
                ) {
                    if appModel.path.last != .playlists {
                        appModel.path.append(.playlists)
                    }
                }

                SidebarNavItem(
                    title: "History",
                    icon: "clock",
                    isSelected: appModel.path.last == .listening
                ) {
                    if appModel.path.last != .listening {
                        appModel.path.append(.listening)
                    }
                }

                SidebarNavItem(
                    title: "Settings",
                    icon: "gearshape",
                    isSelected: false
                ) {
                    NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
                }
            }
            .padding(.horizontal, 8)

            // FAVORITE ARTISTS Section
            Text("FAVORITE ARTISTS")
                .font(.system(size: 11, weight: .semibold))
                .tracking(1.4)
                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                .padding(.horizontal, 18)
                .padding(.top, 26)
                .padding(.bottom, 8)

            GradientHairline(height: 1, opacity: 0.9)
                .padding(.horizontal, 18)
                .padding(.bottom, 6)

            ScrollView {
                LazyVStack(alignment: .leading, spacing: 1) {
                    ForEach(favoritedArtists, id: \.key) { artist in
                        Button {
                            onSelectArtist(artist)
                        } label: {
                            HStack {
                                Text(ArtistAbbreviations.label(for: artist.name))
                                    .font(.system(size: 14))
                                    .foregroundStyle(colors.textPrimary)
                                    .lineLimit(1)
                                Spacer()
                                Text(Self.countFormatter.string(from: NSNumber(value: artist.showCount)) ?? "\(artist.showCount)")
                                    .font(.system(size: 12))
                                    .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                            }
                            .frame(height: 32)
                            .padding(.horizontal, 10)
                            .background(Color.clear, in: RoundedRectangle(cornerRadius: 7))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 8)
            }

            Spacer()

            // SYNC STATUS Footer
            Divider().overlay(colors.divider)

            Button {
                appModel.settingsTab = .sync
                NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
            } label: {
                HStack(spacing: 9) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 15))
                        .foregroundStyle(Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0))

                    VStack(alignment: .leading, spacing: 1) {
                        Text(appModel.syncSession.paired ? "Synced with phone" : "Not paired")
                            .font(.system(size: 12))
                            .foregroundStyle(colors.textSecondary)
                            .lineLimit(1)

                        if let lastSynced = appModel.syncSession.lastSyncedAt, lastSynced > 0 {
                            Text(relativeTime(lastSynced))
                                .font(.system(size: 11))
                                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                        } else {
                            Text("Ready to sync")
                                .font(.system(size: 11))
                                .foregroundStyle(Color(red: 0x75 / 255.0, green: 0x79 / 255.0, blue: 0x8C / 255.0))
                        }
                    }

                    Spacer()

                    Circle()
                        .fill(Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0))
                        .frame(width: 6, height: 6)
                        .shadow(color: Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.9), radius: 4)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .frame(width: 236)
        .background(colors.elevated)
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
                    .font(.system(size: 16))
                    .frame(width: 18)
                    .foregroundStyle(isSelected ? Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0) : Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))

                Text(title)
                    .font(.system(size: 14, weight: isSelected ? .medium : .regular))
                    .foregroundStyle(isSelected ? Color(red: 0xD2 / 255.0, green: 0xCE / 255.0, blue: 0xFD / 255.0) : Color(red: 0xB2 / 255.0, green: 0xB6 / 255.0, blue: 0xCA / 255.0))

                Spacer()
            }
            .frame(height: 34)
            .padding(.horizontal, 10)
            .background(
                isSelected ? Color(red: 0x91 / 255.0, green: 0x84 / 255.0, blue: 0xD9 / 255.0).opacity(0.16) : Color.clear,
                in: RoundedRectangle(cornerRadius: 7)
            )
        }
        .buttonStyle(.plain)
    }
}
