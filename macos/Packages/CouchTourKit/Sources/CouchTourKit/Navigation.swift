/// Every place the macOS window can be, as one type.
///
/// The sidebar is gone (D203) and the window has a single `NavigationStack`, so this replaces
/// both `SidebarSection` and the nine per-view `navigationDestination(for:)` declarations that
/// used to be scattered across the browse, search, and playlist screens. Two things fall out of
/// that consolidation:
///
/// - The stack's path is a typed `[Route]` rather than a `NavigationPath`, because the toolbar
///   breadcrumb has to *read* the trail and `NavigationPath` is type-erased.
/// - Search no longer needs a private wrapper type for show hits. SwiftUI allows one
///   `navigationDestination` per data type per stack, so pushing `ShowSummary` from two screens
///   used to require one of them to disguise it; with a single enum there's only one destination.
///
/// Lives here rather than in the app target for the same reason `PlayerBarDestination` does:
/// it's a pure model with no SwiftUI in it, and being here is what makes it testable. The
/// design-system views this batch also added are UI and stay out of this package.
public enum Route: Hashable {
    case artists
    /// Continue Listening and History merged into one screen (D203, superseding D171).
    case listening
    case playlists
    case search
    case artist(ArtistRef)
    case period(artist: ArtistRef, period: PeriodRef)
    case show(ShowSummary)
    case localPlaylist(LocalPlaylist)

    /// This route's own segment of the breadcrumb — and, via the trail's last element, the
    /// screen name a feedback issue is filed against (`FeedbackButton`). A show's date or an
    /// artist's name is better metadata than the sidebar section name it replaced.
    public var crumbTitle: String {
        switch self {
        case .artists: return "Artists"
        case .listening: return "Listening"
        case .playlists: return "Playlists"
        case .search: return "Search"
        case .artist(let artist): return artist.name
        case .period(_, let period): return period.label
        case .show(let show): return show.date
        case .localPlaylist(let playlist): return playlist.name
        }
    }
}

/// The app name, then one segment per level drilled in. An empty path is Home, which is why the
/// root crumb is always present — the window is never nowhere.
public func breadcrumbTrail(path: [Route]) -> [String] {
    ["Couch Tour"] + path.map(\.crumbTitle)
}

/// The whole path a player-bar (or Now Playing inspector) click should land on, not just the
/// destination itself.
///
/// D202 solved this by *replacing* the Artists section's path with a single entry, so Back
/// always popped somewhere coherent instead of onto whatever the user had browsed to earlier.
/// With one stack for the window that property is cheaper to keep and worth more: synthesizing
/// the levels above the destination means Back walks up through them and the breadcrumb reads
/// as a real path rather than a lone leaf.
public func routes(for destination: PlayerBarDestination) -> [Route] {
    switch destination {
    case .artist(let artist):
        return [.artists, .artist(artist)]
    case .show(let show):
        return [.artists, .artist(show.artist), .show(show)]
    }
}
