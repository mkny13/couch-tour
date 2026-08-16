import AppKit
import SwiftUI

enum SidebarSection: String, CaseIterable, Identifiable, Hashable {
    case continueListening = "Continue Listening"
    case artists = "Artists"
    case history = "History"
    case sync = "Sync"

    var id: String { rawValue }

    var systemImage: String {
        switch self {
        case .continueListening: return "play.circle"
        case .artists: return "music.mic"
        case .history: return "clock.arrow.circlepath"
        case .sync: return "arrow.triangle.2.circlepath"
        }
    }
}

/// Background catch-up cadence, in the absence of `BGTaskScheduler`-grade background
/// execution for this MVP: an immediate sync on launch and on returning to the foreground,
/// plus a 15-minute timer while the app stays open — the same interval Android's WorkManager
/// job uses, chosen there because it's WorkManager's own floor for periodic work.
private let periodicSyncInterval: Duration = .seconds(15 * 60)

struct RootView: View {
    @State private var selection: SidebarSection? = .artists
    @EnvironmentObject private var player: Player
    @EnvironmentObject private var appModel: AppModel

    var body: some View {
        VStack(spacing: 0) {
            NavigationSplitView {
                List(SidebarSection.allCases, selection: $selection) { section in
                    Label(section.rawValue, systemImage: section.systemImage).tag(section)
                }
                .navigationTitle("Couch Tour")
                .listStyle(.sidebar)
            } detail: {
                // A fresh NavigationStack per section, rather than one stack switching
                // content, so drill-down state (Artists → Periods → Shows → Show) doesn't
                // leak across sidebar sections and resets when you switch away and back.
                switch selection ?? .artists {
                case .artists:
                    NavigationStack { ArtistsView() }
                case .continueListening:
                    NavigationStack { ContinueListeningView() }
                case .history:
                    NavigationStack { HistoryView() }
                case .sync:
                    NavigationStack { SyncView(syncSession: appModel.syncSession) }
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
