package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/**
 * The Home screen's "On this date" row (#13): shows the user's favorited artists played on
 * today's month/day, in years gone by.
 *
 * Neither backend can answer this question. phish.in's `/shows` filters by `year=` or
 * `year_range=` and nothing else ([PhishInApi.showsForPeriod]); Relisten's catalog is a
 * per-year fetch ([RelistenApi.year]). There is no search parameter and no "on this day"
 * endpoint on either side, so the only way to find matches is to pull down shows a period at
 * a time and compare the month/day of each date string. That makes this a *cost* problem
 * rather than a UI one, and the cost is lopsided:
 *
 * - **phish.in** is small and range-queryable. The whole archive is under 2,000 shows with
 *   audio across ~35 periods, and consecutive periods can be batched into one `year_range=`
 *   request. Every Phish year costs about four requests, so it gets no year bound at all —
 *   and the pre-1996 years are exactly the ones worth surfacing here.
 * - **Relisten** has no range endpoint: one request per year per artist. A thirty-year
 *   archive is thirty requests, multiplied by however many artists are favorited. That is
 *   the thing that has to be capped, and [RELISTEN_YEAR_BUDGET] is the cap.
 *
 * Worst case is about nineteen requests, run once a day (see [OnThisDate]) and off the
 * critical path of the Home screen's first paint. See D162.
 *
 * Everything here works through the [MusicSource] seam rather than the two API clients, so
 * the whole path — including the phish.in range batching, which rides
 * [PhishInSource.shows]'s existing `year_range=` branch — is testable with a fake source and
 * no network, the same way [pickRandomShow] is (D36).
 */

/** Past this many shows in one `year_range=` request, phish.in's `per_page=1000` would
 *  truncate the page. Batches are sized against [PeriodRef.showCount] to stay under it, so
 *  the bound keeps holding as the archive grows rather than needing a hardcoded year list. */
private const val PHISHIN_RANGE_CAP = 900

/** Total Relisten year-fetches allowed across every favorited artist, split evenly between
 *  them. Favoriting a dozen Relisten artists costs exactly what favoriting three costs. */
internal const val RELISTEN_YEAR_BUDGET = 12

/** Relisten artists beyond this many don't participate at all — splitting the budget any
 *  finer would fetch too few years each to find anything. Favorites are taken in the order
 *  the Home screen already shows them, so the choice isn't arbitrary from the user's side. */
internal const val MAX_RELISTEN_ARTISTS = 3

/** How many matches the row shows. "A random selection", not every anniversary ever. */
internal const val MAX_ANNIVERSARY_SHOWS = 8

/** "1997-11-17" -> "11-17"; null for anything that isn't a `YYYY-MM-DD` date. Dates stay
 *  opaque strings throughout the app — there is no date type to parse into. */
internal fun monthDay(date: String): String? =
    if (date.length == 10 && date[4] == '-' && date[7] == '-') date.substring(5) else null

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

/** Years per artist, once [RELISTEN_YEAR_BUDGET] is split between them. */
internal fun relistenYearBudget(artistCount: Int, budget: Int = RELISTEN_YEAR_BUDGET): Int =
    if (artistCount <= 0) 0 else maxOf(1, budget / artistCount)

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
    val spans = periods.mapNotNull { p -> periodSpan(p.id)?.let { it to p.showCount } }
    if (spans.isEmpty()) return emptyList()

    val batches = mutableListOf<Pair<IntRange, Int>>()
    for ((span, count) in spans) {
        val last = batches.lastOrNull()
        if (last == null || last.second + count > cap) {
            batches += span to count
        } else {
            batches[batches.lastIndex] = (minOf(last.first.first, span.first)..maxOf(last.first.last, span.last)) to
                (last.second + count)
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
    val yearsEach = relistenYearBudget(relisten.size)
    val participating = favorites.filter { it.backend != Backend.RELISTEN } + relisten

    val perArtist = participating
        .map { artist -> async { runCatching { showsFor(artist, today, yearsEach, source) }.getOrDefault(emptyList()) } }
        .awaitAll()

    pickAnniversaryShows(perArtist.flatten(), random = random)
}

/** One artist's anniversary matches. The period selection is where the two backends differ:
 *  phish.in batches its whole archive into ranges, Relisten takes its most recent
 *  [yearsEach] years — the same backend dispatch [showShareUrl] and [ShowDetail.queueKey]
 *  already do, rather than a capability flag on [MusicSource] with two possible answers. */
private suspend fun showsFor(
    artist: ArtistRef,
    today: String,
    yearsEach: Int,
    source: (Backend) -> MusicSource,
): List<ShowSummary> = coroutineScope {
    val src = source(artist.backend)
    val all = src.periods(artist)
    val periods = when (artist.backend) {
        Backend.PHISHIN -> phishInRanges(all)
        // Labels are plain years, so descending order is most-recent-first. Relisten's
        // archives are deep enough that the budget always binds long before the list ends.
        Backend.RELISTEN -> all.sortedByDescending { it.label }.take(yearsEach)
    }
    periods
        .map { period -> async { runCatching { src.shows(artist, period) }.getOrDefault(emptyList()) } }
        .awaitAll()
        .flatten()
        .let { showsOnAnniversary(it, today) }
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
    /** Key is the date plus the favorites it was computed for, so favoriting an artist
     *  invalidates it as surely as midnight does. */
    @Volatile internal var cached: Pair<String, List<ShowSummary>>? = null

    internal fun cacheKey(favorites: List<ArtistRef>, today: String): String =
        today + "|" + favorites.map { it.key }.sorted().joinToString(",")

    suspend fun load(favorites: List<ArtistRef>, today: String): List<ShowSummary> {
        if (favorites.isEmpty()) return emptyList()
        val key = cacheKey(favorites, today)
        cached?.let { (cachedKey, shows) -> if (cachedKey == key) return shows }
        val shows = showsOnDate(favorites, today)
        cached = key to shows
        return shows
    }
}
