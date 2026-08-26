import AppKit
import SwiftUI

enum SidebarSection: String, CaseIterable, Identifiable, Hashable {
    case continueListening = "Continue Listening"
    case artists = "Artists"
    case search = "Search"
    case history = "History"
    /// Account-free, spans both backends (#59) — its own top-level section rather than
    /// nested under Artists, the same peer-level placement Account/Sync got in Settings.
    case playlists = "Playlists"

    var id: String { rawValue }

    var systemImage: String {
        switch self {
        case .continueListening: return "play.circle"
        case .artists: return "music.mic"
        case .search: return "magnifyingglass"
        case .history: return "clock.arrow.circlepath"
        case .playlists: return "music.note.list"
        }
    }
}

/// Background catch-up cadence, in the absence of `BGTaskScheduler`-grade background
/// execution for this MVP: an immediate sync on launch and on returning to the foreground,
/// plus a 15-minute timer while the app stays open — the same interval Android's WorkManager
/// job uses, chosen there because it's WorkManager's own floor for periodic work.
private let periodicSyncInterval: Duration = .seconds(15 * 60)

struct RootView: View {
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel

    var body: some View {
        VStack(spacing: 0) {
            NavigationSplitView {
                // Bound through AppModel, not local @State — CouchTourApp's ⌘F command
                // needs to switch to Search from outside this view, the same reason
                // showNowPlaying lives there rather than as local state.
                List(SidebarSection.allCases, selection: $appModel.selection) { section in
                    Label(section.rawValue, systemImage: section.systemImage).tag(section)
                }
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 4) {
                        Text(Bundle.main.appVersionString)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        #if BETA
                        Button("Update to Latest Beta") {
                            AppModel.launchUpdateScript()
                        }
                        .buttonStyle(.link)
                        .font(.caption2)
                        #endif
                    }
                    .padding(.vertical, 8)
                }
                .navigationTitle("Couch Tour")
                .listStyle(.sidebar)
            } detail: {
                // A fresh NavigationStack per section, rather than one stack switching
                // content, so drill-down state (Artists → Periods → Shows → Show) doesn't
                // leak across sidebar sections and resets when you switch away and back.
                Group {
                    switch appModel.selection ?? .artists {
                    case .artists:
                        NavigationStack { ArtistsView() }
                    case .search:
                        NavigationStack { SearchView() }
                    case .continueListening:
                        NavigationStack { ContinueListeningView() }
                    case .history:
                        NavigationStack { HistoryView() }
                    case .playlists:
                        NavigationStack { LocalPlaylistsView() }
                    }
                }
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button {
                            appModel.showNowPlaying.toggle()
                        } label: {
                            Label("Now Playing", systemImage: "music.note.list")
                        }
                    }
                }
                // A trailing inspector rather than a fifth sidebar section or a separate
                // window: it can show the queue for whatever's playing without disturbing
                // browse's own NavigationStack (see the comment above), and stays in the
                // same window as everything else.
                .inspector(isPresented: $appModel.showNowPlaying) {
                    NowPlayingInspector()
                        .inspectorColumnWidth(min: 260, ideal: 300, max: 380)
                }
            }
            if player.show != nil {
                Divider()
                MiniPlayerView()
            }
        }
        .task {
            appModel.syncNow()
            while !Task.isCancelled {
                try? await Task.sleep(for: periodicSyncInterval)
                appModel.syncNow()
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            appModel.syncNow()
        }
    }
}
