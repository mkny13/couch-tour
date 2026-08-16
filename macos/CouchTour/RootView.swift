import SwiftUI

enum SidebarSection: String, CaseIterable, Identifiable, Hashable {
    case continueListening = "Continue Listening"
    case artists = "Artists"
    case history = "History"

    var id: String { rawValue }

    var systemImage: String {
        switch self {
        case .continueListening: return "play.circle"
        case .artists: return "music.mic"
        case .history: return "clock.arrow.circlepath"
        }
    }
}

struct RootView: View {
    @State private var selection: SidebarSection? = .artists
    @EnvironmentObject private var player: Player

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
                }
            }
            if player.show != nil {
                Divider()
                MiniPlayerView()
            }
        }
    }
}
