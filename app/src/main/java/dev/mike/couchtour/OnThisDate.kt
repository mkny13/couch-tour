package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * The Home screen's "On this date" row (#13): shows the user's favorited artists played on
 * today's month/day, in years gone by.
 *
 * Backends answer this differently:
 * - **phish.in** has no month/day endpoint; `/shows` filters by `year=` or `year_range=`.
 *   The whole archive is under 2,000 shows with audio across ~35 periods, and consecutive
 *   periods can be batched into `year_range=` requests ([phishInRanges]) capped at 900 shows.
 *   Every Phish year costs about four requests, so it gets no year bound at all.
 * - **Relisten** provides a dedicated on-date endpoint ([RelistenApi.showsOnDate]:
 *   `GET /v2/artists/{slug}/shows/on-date?month=M&day=D`) that returns all matching shows
 *   across all years in a single request. Favorited Relisten artists are capped at
 *   [MAX_RELISTEN_ARTISTS] (10).
 *
 * Worst case is about fourteen requests, run once a day (see [OnThisDate]) and off the
 * critical path of the Home screen's first paint. See D162 and D300.
 *
 * Everything here works through the [MusicSource] seam rather than the two API clients, so
 * the whole path — including the phish.in range batching and the Relisten on-date fetch — is
 * testable with a fake source and no network, the same way [pickRandomShow] is (D36).
 */

/** Past this many shows in one `year_range=` request, phish.in's `per_page=1000` would
 *  truncate the page. Batches are sized against [PeriodRef.showCount] to stay under it, so
 *  the bound keeps holding as the archive grows rather than needing a hardcoded year list. */
private const val PHISHIN_RANGE_CAP = 900

/** Relisten artists beyond this many don't participate at all. Each favorite costs one
 *  on-date request. */
internal const val MAX_RELISTEN_ARTISTS = 10

/** How many matches the row shows. "A random selection", not every anniversary ever. */
internal const val MAX_ANNIVERSARY_SHOWS = 8

/** "1997-11-17" -> "11-17"; null for anything that isn't a `YYYY-MM-DD` date. Dates stay
 *  opaque strings throughout the app — there is no date type to parse into. */
internal fun monthDay(date: String): String? {
    if (date.length != 10 || date[4] != '-' || date[7] != '-') return null
    for (i in 0 until 10) {
        if (i == 4 || i == 7) continue
        if (date[i] !in '0'..'9') return null
    }
    return date.substring(5)
}

/** "1997-11-17" -> "1997"; null on the same terms as [monthDay]. */
private fun yearOf(date: String): String? =
    if (monthDay(date) != null) date.substring(0, 4) else null

/**
 * The shows in [shows] played on [today]'s month/day in some *other* year.
 *
 * [today] is a parameter rather than a read of the system clock so this is a pure function
 * with a fixed answer — no Robolectric clock tricks needed to test it.
 *
 * A leap-day [today] matches only other leap years. That's the honest answer rather than a
 * bug: there was no February 29th in 2023 to have played a show on.
 */
internal fun showsOnAnniversary(shows: List<ShowSummary>, today: String): List<ShowSummary> {
    val md = monthDay(today) ?: return emptyList()
    val thisYear = yearOf(today)
    return shows.filter { monthDay(it.date) == md && yearOf(it.date) != thisYear }
}

/** A phish.in period id is either "1997" or "1983-1987" ([Period]); this is its span, or
 *  null for [POPULAR_PERIOD_ID] and anything else that isn't a year or year range. */
private fun periodSpan(id: String): IntRange? {
    val parts = id.split("-")
    val start = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val end = when (parts.size) {
        1 -> start
        2 -> parts[1].toIntOrNull() ?: return null
        else -> return null
    }
    return if (end < start) null else start..end
}

/**
 * Collapses phish.in's ~35 single-year periods into a handful of `year_range=` ones, so
 * covering the whole archive costs about four requests instead of thirty-five.
 *
 * Greedy and order-preserving: consecutive periods accumulate until adding the next would
 * push the batch over [cap] shows, then a new batch starts. A period that already is a range
 * contributes its own span, and one whose own show count exceeds [cap] becomes a batch of its
 * own — it can't be split any finer, and one over-long page beats dropping the year.
 *
 * The synthetic [PeriodRef]s this returns are fed straight back to [PhishInSource.shows],
 * whose existing `period.contains("-")` branch turns them into `year_range=` queries (D11).
 */
internal fun phishInRanges(periods: List<PeriodRef>, cap: Int = PHISHIN_RANGE_CAP): List<PeriodRef> {
    val spans = periods
        .mapNotNull { p -> periodSpan(p.id)?.let { it to p.showCount } }
        .sortedWith(compareBy({ it.first.first }, { it.first.last }))
    if (spans.isEmpty()) return emptyList()

    val batches = mutableListOf<Pair<IntRange, Int>>()
    for ((span, count) in spans) {
        val last = batches.lastOrNull()
        val isContiguousOrOverlapping = last != null && span.first <= last.first.last + 1
        if (isContiguousOrOverlapping && last!!.second + count <= cap) {
            batches[batches.lastIndex] = (minOf(last.first.first, span.first)..maxOf(last.first.last, span.last)) to
                (last.second + count)
        } else {
            batches += span to count
        }
    }
    return batches.map { (span, count) ->
        // Always hyphenated, even for a single year: showsForPeriod picks year_range= off the
        // hyphen, and "1997-1997" is a range phish.in answers the same as year=1997.
        PeriodRef(id = "${span.first}-${span.last}", label = "${span.first}-${span.last}", showCount = count)
    }
}

/**
 * Trims the matches down to a bounded random handful, then orders them newest-first so the
 * row reads consistently rather than reshuffling on every recomposition.
 *
 * [random] is a parameter for the same reason [pickRandomShow]'s is — a seeded selection is
 * checkable in a test. This reuses that idiom rather than introducing a second one.
 */
internal fun pickAnniversaryShows(
    matches: List<ShowSummary>,
    limit: Int = MAX_ANNIVERSARY_SHOWS,
    random: Random = Random,
): List<ShowSummary> = matches.shuffled(random).take(limit).sortedByDescending { it.date }

/**
 * Fetches every favorited artist's shows for [today]'s month/day, within the bounds above.
 *
 * Artists are fanned out concurrently and each period fetch is wrapped in [runCatching]: a
 * backend that 500s costs its own results and nothing else, because a partly-populated
 * discovery row is worth more than an error message where a row would be. That's also why
 * this returns an empty list rather than throwing when everything fails.
 *
 * [source] and [random] are injectable so the whole path runs without a network call.
 */
suspend fun showsOnDate(
    favorites: List<ArtistRef>,
    today: String,
    random: Random = Random,
    source: (Backend) -> MusicSource = ::sourceFor,
): List<ShowSummary> = coroutineScope {
    val relisten = favorites.filter { it.backend == Backend.RELISTEN }.take(MAX_RELISTEN_ARTISTS)
    val participating = favorites.filter { it.backend != Backend.RELISTEN } + relisten

    if (participating.isEmpty()) return@coroutineScope emptyList()

    val perArtist = participating
        .map { artist -> async { runCatching { showsFor(artist, today, source) } } }
        .awaitAll()

    var successCount = 0
    var firstError: Throwable? = null
    val allShows = mutableListOf<ShowSummary>()

    for (result in perArtist) {
        result.onSuccess { shows ->
            allShows.addAll(shows)
            successCount++
        }.onFailure { error ->
            if (firstError == null) firstError = error
        }
    }

    if (successCount == 0 && firstError != null) {
        throw firstError!!
    }

    pickAnniversaryShows(allShows, random = random)
}

/** One artist's anniversary matches. The fetch is where the backends differ:
 *  phish.in batches its archive into `year_range=` queries, Relisten queries its
 *  on-date endpoint ([MusicSource.showsOnDate]) in a single request. */
private suspend fun showsFor(
    artist: ArtistRef,
    today: String,
    source: (Backend) -> MusicSource,
): List<ShowSummary> = coroutineScope {
    val src = source(artist.backend)
    val shows = when (artist.backend) {
        Backend.PHISHIN -> {
            val all = src.periods(artist)
            val periods = phishInRanges(all)
            periods
                .map { period -> async { runCatching { src.shows(artist, period) }.getOrDefault(emptyList()) } }
                .awaitAll()
                .flatten()
        }
        Backend.RELISTEN -> {
            val md = monthDay(today) ?: return@coroutineScope emptyList()
            val parts = md.split("-")
            val month = parts.getOrNull(0)?.toIntOrNull() ?: return@coroutineScope emptyList()
            val day = parts.getOrNull(1)?.toIntOrNull() ?: return@coroutineScope emptyList()
            src.showsOnDate(artist, month, day)
        }
        // YouTube artists have no anniversary tape; they can't be favorited anyway
        // (they never appear in any artist list), so this branch is purely exhaustive-when.
        Backend.YOUTUBE -> emptyList()
    }
    showsOnAnniversary(shows, today)
}

/**
 * The Home screen's entry point: [showsOnDate] behind a one-entry in-memory cache.
 *
 * The answer changes exactly once a day — it is keyed on the date — so re-running nineteen
 * requests on every return to Home would be pure waste. This deliberately stays in memory
 * rather than becoming a Room table, for the same reason [RelistenCatalogSource.cachedArtists]
 * does: a real catalog cache is a bigger feature than this row justifies, and the `progress`
 * table has no business holding throwaway catalog data. Process death re-fetches, which for a
 * once-a-day result is the right trade.
 *
 * [cached] is internal, not private, so tests can reset it between runs — the same escape
 * hatch `PhishInApi.baseUrl` uses.
 */
object OnThisDate {
    private val mutex = Mutex()

    /** Key is the date plus the favorites it was computed for, so favoriting an artist
     *  invalidates it as surely as midnight does. */
    @Volatile internal var cached: Pair<String, List<ShowSummary>>? = null

    internal fun cacheKey(favorites: List<ArtistRef>, today: String): String =
        today + "|" + favorites.map { it.key }.sorted().joinToString(",")

    suspend fun load(
        favorites: List<ArtistRef>,
        today: String,
        random: Random = Random,
        source: (Backend) -> MusicSource = ::sourceFor,
    ): List<ShowSummary> {
        if (favorites.isEmpty()) return emptyList()
        val key = cacheKey(favorites, today)
        cached?.let { (cachedKey, shows) -> if (cachedKey == key) return shows }
        return mutex.withLock {
            cached?.let { (cachedKey, shows) -> if (cachedKey == key) return shows }
            val shows = showsOnDate(favorites, today, random = random, source = source)
            if (shows.isNotEmpty()) {
                cached = key to shows
            }
            shows
        }
    }
}
