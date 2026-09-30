import Foundation

public enum SidebarFavoritesState: Equatable {
    case loading
    case empty
    case artists([ArtistRef])
}

public func sidebarFavoritesState(artists: [ArtistRef], isLoaded: Bool) -> SidebarFavoritesState {
    if !isLoaded {
        return .loading
    }
    if artists.isEmpty {
        return .empty
    }
    let sorted = artists.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    return .artists(sorted)
}
