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
    let validPeriods = periods.filter { $0.id != "popular" }
    let yearStr = String(currentDate.prefix(4))
    guard let period = validPeriods.first(where: { $0.label == yearStr || $0.label.contains(yearStr) || $0.id == yearStr }) ?? validPeriods.first else {
        return nil
    }
    guard let shows = try? await src.shows(artist: artist, period: period) else { return nil }
    let currentShow = shows.first(where: { $0.date == currentDate })
    let effectiveTourName = tourName?.trimmingCharacters(in: .whitespaces).isEmpty == false ? tourName : currentShow?.tourName

    if let nextInPeriod = resolveNextConsecutiveShow(currentDate: currentDate, tourName: effectiveTourName, candidateShows: shows) {
        return nextInPeriod
    }

    if let yearInt = Int(yearStr) {
        let nextPeriods = validPeriods.filter { p in
            guard let pYear = Int(p.label.prefix(4)) ?? Int(p.id.prefix(4)) else { return false }
            return pYear > yearInt
        }.sorted { $0.label < $1.label }

        for np in nextPeriods.prefix(2) {
            if let nextShows = try? await src.shows(artist: artist, period: np),
               let found = resolveNextConsecutiveShow(currentDate: currentDate, tourName: effectiveTourName, candidateShows: nextShows) {
                return found
            }
        }
    }
    return nil
}
