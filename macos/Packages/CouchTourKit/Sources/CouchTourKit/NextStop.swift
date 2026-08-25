import Foundation

private let notPartOfATour = "Not Part of a Tour"

/// Resolves the next consecutive show on tour after `currentDate` (#85).
///
/// If `tourName` is present and not "Not Part of a Tour", it looks for the next chronological
/// show within that same tour. If no subsequent show exists in that tour or `tourName` is nil/empty,
/// it falls back to the next chronological show overall in `candidateShows`.
public func resolveNextConsecutiveShow(
    currentDate: String,
    tourName: String?,
    candidateShows: [ShowSummary]
) -> ShowSummary? {
    let futureShows = candidateShows.filter { $0.date > currentDate }
    if let tourName = tourName?.trimmingCharacters(in: .whitespaces),
       !tourName.isEmpty,
       tourName != notPartOfATour {
        let nextInTour = futureShows.filter { $0.tourName == tourName }.min { $0.date < $1.date }
        if let nextInTour { return nextInTour }
    }
    return futureShows.min { $0.date < $1.date }
}

/// Fetches the artist's shows surrounding `currentDate` and resolves the next consecutive show on tour (#85).
public func findNextTourStop(
    artist: ArtistRef,
    currentDate: String,
    tourName: String? = nil,
    source: (Backend) -> MusicSource = sourceFor
) async -> ShowSummary? {
    let src = source(artist.backend)
    guard let periods = try? await src.periods(artist: artist) else { return nil }
    let yearStr = String(currentDate.prefix(4))
    guard let period = periods.first(where: { $0.label == yearStr || $0.label.contains(yearStr) || $0.id == yearStr }) ?? periods.first else {
        return nil
    }
    guard let shows = try? await src.shows(artist: artist, period: period) else { return nil }
    return resolveNextConsecutiveShow(currentDate: currentDate, tourName: tourName, candidateShows: shows)
}
