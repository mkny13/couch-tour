package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The Home screen's "Next Couch Tour stop" row (#22): the oldest unplayed show from the
 * current tours of the user's favorited artists — a way to catch up before the next show.
 *
 * Neither backend exposes a "current tour" concept directly. [PeriodRef]s are years (or
 * year ranges on phish.in), not tours, so the only way to find one is to fetch an artist's
 * most recent shows and read [ShowSummary.tourName] off of them. That makes this the same
 * shape of problem [OnThisDate] solves for anniversaries: fetch a bounded slice of each
 * favorited artist's catalog through the [MusicSource] seam, fan the artists out
 * concurrently, and cache the network half once a day (D162, D164).
 *
 * The two latest periods are fetched per artist, not one — a tour that crosses New Year (say
 * the most recent show is in January, the rest of the run is the previous December) would
 * otherwise expose only its tail, and since this picks the *oldest* show in the tour, a
 * missing older half doesn't degrade the answer, it makes it wrong.
 *
 * Unlike [OnThisDate], the "unplayed" half of the answer is not part of the daily cache —
 * it depends on the `progress` table, which changes the moment a show finishes. [NextStop]
 * caches only [currentTours]' network result; callers apply [oldestUnplayed] live against a
 * fresh set of finished queue keys on every recomposition.
 */

/** Relisten artists beyond this many, and phish.in artists beyond this many, don't
 *  participate — matching [MAX_RELISTEN_ARTISTS]'s per-backend-cap precedent rather than one
 *  cap shared across backends whose costs aren't the same shape. */
internal const val MAX_TOUR_ARTISTS = 3

/** The most recent show's period plus the one before it, so a tour spanning a year boundary
 *  is seen whole. */
internal const val TOUR_PERIODS = 2

/**
 * The latest [count] year-shaped periods, most recent first. Sorted on [PeriodRef.label]
 * rather than [PeriodRef.id] — id is the year on phish.in but an opaque uuid on Relisten
 * ([RelistenYear.toPeriodRef]), while label is a plain year string on both. The synthetic
 * [POPULAR_PERIOD_ID] ("Popular") isn't a year and drops out rather than sorting arbitrarily.
 */
internal fun recentPeriods(periods: List<PeriodRef>, count: Int = TOUR_PERIODS): List<PeriodRef> =
    periods.filter { it.label.toIntOrNull() != null }
        .sortedByDescending { it.label.toInt() }
        .take(count)

/**
 * The backend-neutral identity of the show a catalog entry represents — what "have I played
 * this?" actually compares. A phish.in show is exactly its queue key; a Relisten show is
 * matched by [recordingShowKey] rather than [ShowSummary]'s (nonexistent) recording id, since
 * a favorited-artist catalog fetch never resolves down to a specific tape.
 */
internal fun showId(show: ShowSummary): String = when (show.artist.backend) {
    Backend.PHISHIN -> showQueueKey(show.date)
    Backend.RELISTEN -> recordingShowKey(show.artist.id, show.date)
}

/**
 * The same identity for a set of stored queue keys, e.g. [ProgressDao.finishedKeys]. Keys
 * that aren't shows at all (playlists, local playlists) or don't parse contribute nothing —
 * a malformed or unrelated row should be ignored, not crash the match.
 */
internal fun playedShowIds(keys: Collection<String>): Set<String> = keys.mapNotNullTo(mutableSetOf()) { raw ->
    when (val ref = parseQueueKey(raw)) {
        null -> null
        else -> when (ref.kind) {
            QueueKind.SHOW -> showQueueKey(ref.id)
            QueueKind.RECORDING -> parseRecordingId(ref.id)?.let { recordingShowKey(it.artistSlug, it.date) }
            QueueKind.PLAYLIST, QueueKind.LOCAL_PLAYLIST -> null
        }
    }
}

/** Both backends use this exact string as [ShowSummary.tourName] for a show that isn't part
 *  of a named tour (confirmed live against both APIs) rather than leaving the field blank —
 *  a sentinel, not a real tour, so it gets the same opt-out treatment a blank name would. */
private const val NOT_PART_OF_A_TOUR = "Not Part of a Tour"

/**
 * The shows in [shows] that share the tour of the most recent one — empty if that show
 * carries no tour name, which is how an artist without a "current tour" opts out entirely
 * (older or single-show periods often have none, and a lot of them are explicitly tagged
 * [NOT_PART_OF_A_TOUR] rather than left blank). Without this, an inactive artist's standalone
 * shows would get lumped into one fake "tour" spanning however many years happen to be
 * fetched, and its oldest entry would win the cross-artist pick every time — a defunct band
 * with a deep, mostly-untoured archive would permanently crowd out artists that actually
 * tour.
 */
internal fun currentTourShows(shows: List<ShowSummary>): List<ShowSummary> {
    val latest = shows.maxByOrNull { it.date } ?: return emptyList()
    val tour = latest.tourName
        ?.takeIf { it.isNotBlank() && it != NOT_PART_OF_A_TOUR }
        ?: return emptyList()
    return shows.filter { it.tourName == tour }
}

/**
 * The single oldest show in [candidates] with no matching entry in [played]. Ties (same
 * date, different artists) break on [ArtistRef.key] so the answer is deterministic rather
 * than depending on fan-out ordering.
 */
internal fun oldestUnplayed(candidates: List<ShowSummary>, played: Set<String>): ShowSummary? =
    candidates.filterNot { showId(it) in played }
        .minWithOrNull(compareBy({ it.date }, { it.artist.key }))

/**
 * Fetches every favorited artist's current-tour shows, within [MAX_TOUR_ARTISTS] per backend.
 * Artists are fanned out concurrently; each artist's own fetch is wrapped in [runCatching] so
 * one backend erroring costs only its own result, matching [OnThisDate]'s degrade-not-fail
 * shape. [source] is injectable so this runs without a network call in tests.
 */
suspend fun currentTours(
    favorites: List<ArtistRef>,
    source: (Backend) -> MusicSource = ::sourceFor,
): List<ShowSummary> = coroutineScope {
    val participating = favorites.groupBy { it.backend }
        .flatMap { (_, artists) -> artists.take(MAX_TOUR_ARTISTS) }

    participating
        .map { artist -> async { runCatching { tourFor(artist, source) }.getOrDefault(emptyList()) } }
        .awaitAll()
        .flatten()
}

private suspend fun tourFor(artist: ArtistRef, source: (Backend) -> MusicSource): List<ShowSummary> = coroutineScope {
    val src = source(artist.backend)
    val periods = recentPeriods(src.periods(artist))
    val shows = periods
        .map { period -> async { runCatching { src.shows(artist, period) }.getOrDefault(emptyList()) } }
        .awaitAll()
        .flatten()
    currentTourShows(shows)
}

/**
 * The Home screen's entry point: [currentTours] behind a one-entry in-memory cache, the same
 * shape as [OnThisDate] — keyed on the date plus the favorited artists, since that's exactly
 * what the network fetch depends on. The unplayed filter is deliberately *not* part of this
 * cache; apply [oldestUnplayed] to the result against a live finished-keys set instead.
 */
object NextStop {
    @Volatile internal var cached: Pair<String, List<ShowSummary>>? = null

    internal fun cacheKey(favorites: List<ArtistRef>, today: String): String =
        today + "|" + favorites.map { it.key }.sorted().joinToString(",")

    suspend fun load(favorites: List<ArtistRef>, today: String): List<ShowSummary> {
        if (favorites.isEmpty()) return emptyList()
        val key = cacheKey(favorites, today)
        cached?.let { (cachedKey, shows) -> if (cachedKey == key) return shows }
        val shows = currentTours(favorites)
        cached = key to shows
        return shows
    }
}

/**
 * Resolves the next consecutive show on tour after [currentDate] (#85).
 *
 * If [tourName] is present and not [NOT_PART_OF_A_TOUR], it looks for the next chronological
 * show within that same tour. If no subsequent show exists in that tour or [tourName] is null/empty,
 * it falls back to the next chronological show overall in [candidateShows].
 */
internal fun resolveNextConsecutiveShow(
    currentDate: String,
    tourName: String?,
    candidateShows: List<ShowSummary>,
): ShowSummary? {
    val futureShows = candidateShows.filter { it.date > currentDate }
    if (!tourName.isNullOrBlank() && tourName != NOT_PART_OF_A_TOUR) {
        val nextInTour = futureShows.filter { it.tourName == tourName }.minByOrNull { it.date }
        if (nextInTour != null) return nextInTour
    }
    return futureShows.minByOrNull { it.date }
}

/**
 * Fetches the artist's shows surrounding [currentDate] and resolves the next consecutive show on tour (#85).
 */
suspend fun findNextTourStop(
    artist: ArtistRef,
    currentDate: String,
    tourName: String? = null,
    source: (Backend) -> MusicSource = ::sourceFor,
): ShowSummary? = runCatching {
    val src = source(artist.backend)
    val periods = src.periods(artist).filter { it.id != POPULAR_PERIOD_ID }
    val yearStr = currentDate.take(4)
    val periodIndex = periods.indexOfFirst { it.label == yearStr || it.label.contains(yearStr) || it.id == yearStr }
    val currentPeriod = if (periodIndex != -1) periods[periodIndex] else periods.firstOrNull() ?: return null
    val shows = src.shows(artist, currentPeriod)
    val currentShow = shows.firstOrNull { it.date == currentDate }
    val effectiveTourName = tourName?.takeIf { it.isNotBlank() } ?: currentShow?.tourName

    val nextInCurrent = resolveNextConsecutiveShow(currentDate, effectiveTourName, shows)
    if (nextInCurrent != null) return nextInCurrent

    // If no subsequent show was in this period (e.g. year-end show or tour spans across periods),
    // check subsequent periods in chronological order.
    val yearInt = yearStr.toIntOrNull()
    if (yearInt != null) {
        val nextPeriods = periods.filter { p ->
            val pYear = p.label.take(4).toIntOrNull() ?: p.id.take(4).toIntOrNull()
            pYear != null && pYear > yearInt
        }.sortedBy { it.label }

        for (np in nextPeriods.take(2)) {
            val nextShows = runCatching { src.shows(artist, np) }.getOrDefault(emptyList())
            val found = resolveNextConsecutiveShow(currentDate, effectiveTourName, nextShows)
            if (found != null) return found
        }
    }
    null
}.getOrNull()

