package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.random.Random

/**
 * The Home screen's "Discover" row (#834, replacing #13's "On this date"): a random handful of
 * the user's favorited artists' top-rated shows that they haven't started yet.
 *
 * Each backend already ranks its own catalog, so "top rated" costs one request per artist:
 * - **phish.in**: the archive-wide top-by-likes list ([PhishInApi.popularShows], the same
 *   one behind the "Popular" period).
 * - **Relisten**: `GET /v2/artists/{slug}/shows/top` ([RelistenApi.topShows]), sorted by
 *   `avg_rating`. Favorited Relisten artists are capped at [MAX_RELISTEN_ARTISTS].
 *
 * The network half is cached once a day ([Discover]); the "unstarted" half is applied live
 * against the `progress` table ([pickDiscoverShows]), the same split [NextStop] uses, so a
 * show drops out the moment it's started rather than at midnight.
 */

/** Relisten artists beyond this many don't participate at all. Each favorite costs one request. */
internal const val MAX_RELISTEN_ARTISTS = 10

/** How many shows the row shows. "A random selection", not the whole top list. */
internal const val MAX_DISCOVER_SHOWS = 8

internal data class Fetched<T>(val value: T, val complete: Boolean)

/** A seed that is the same all day and different tomorrow: "2026-11-18" -> 20261118. Not
 *  [String.hashCode], so the macOS port can derive the identical seed (Swift hashes are
 *  randomised per process). */
internal fun daySeed(today: String): Long = today.filter { it.isDigit() }.toLongOrNull() ?: 0L

/**
 * [limit] of [candidates] not in [started], in an order fixed for [today].
 *
 * Candidates are put in a canonical order before the seeded shuffle, so the answer doesn't
 * depend on which artist's fetch finished first. The shuffle happens before the started
 * filter, not after, so starting one show swaps in the next pick without reshuffling the rest.
 */
internal fun pickDiscoverShows(
    candidates: List<ShowSummary>,
    started: Set<String>,
    today: String,
    limit: Int = MAX_DISCOVER_SHOWS,
): List<ShowSummary> =
    candidates
        .distinctBy { showId(it) }
        .sortedWith(compareBy({ it.artist.key }, { it.date }))
        .shuffled(Random(daySeed(today)))
        .filterNot { showId(it) in started }
        .take(limit)

/**
 * Every favorited artist's top-rated shows, fanned out concurrently. One artist's failure
 * costs only its own shows (a partly-filled row beats an error where a row would be), but
 * marks the result incomplete so [Discover] won't cache it as the day's answer.
 */
internal suspend fun topRatedShows(
    favorites: List<ArtistRef>,
    source: (Backend) -> MusicSource = ::sourceFor,
): Fetched<List<ShowSummary>> = coroutineScope {
    val relisten = favorites.filter { it.backend == Backend.RELISTEN }.take(MAX_RELISTEN_ARTISTS)
    val participating = favorites.filter { it.backend == Backend.PHISHIN } + relisten
    if (participating.isEmpty()) return@coroutineScope Fetched(emptyList(), true)

    val perArtist = participating
        .map { artist ->
            async {
                try {
                    Fetched(source(artist.backend).topShows(artist), true)
                } catch (e: IOException) {
                    Fetched(emptyList(), false)
                } catch (e: ApiException) {
                    Fetched(emptyList(), false)
                }
            }
        }
        .awaitAll()
    Fetched(perArtist.flatMap { it.value }, perArtist.all { it.complete })
}

/**
 * [topRatedShows] behind a one-entry in-memory cache keyed on the date and the favorites, so
 * returning to Home doesn't refetch and favoriting an artist invalidates it like midnight
 * does. In memory rather than Room for the reason D162 gave On This Date: process death
 * refetching a once-a-day answer is the right trade.
 */
object Discover {
    private val mutex = Mutex()

    @Volatile internal var cached: Pair<String, List<ShowSummary>>? = null

    internal fun cacheKey(favorites: List<ArtistRef>, today: String): String =
        today + "|" + favorites.map { it.key }.sorted().joinToString(",")

    /** The day's candidates — unfiltered; callers apply [pickDiscoverShows] live. */
    suspend fun load(
        favorites: List<ArtistRef>,
        today: String,
        source: (Backend) -> MusicSource = ::sourceFor,
    ): List<ShowSummary> {
        if (favorites.isEmpty()) return emptyList()
        val key = cacheKey(favorites, today)
        cached?.let { (cachedKey, shows) -> if (cachedKey == key) return shows }
        return mutex.withLock {
            cached?.let { (cachedKey, shows) -> if (cachedKey == key) return shows }
            val fetched = topRatedShows(favorites, source)
            if (fetched.complete && fetched.value.isNotEmpty()) cached = key to fetched.value
            fetched.value
        }
    }

    internal fun resetCache() {
        cached = null
    }
}
