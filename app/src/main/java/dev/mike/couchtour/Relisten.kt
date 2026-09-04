package dev.mike.couchtour

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * DTOs, pure mapping, and the request layer for [api.relisten.net](https://api.relisten.net),
 * Relisten's public API. The mapping stays in pure functions, tested without a network call,
 * the same reasoning D36 and P2's `Catalog.kt` mapping used; `RelistenApi` matches
 * `PhishInApi`'s shape (a `MockWebServer`-testable `internal var baseUrl`, no Retrofit, D4).
 *
 * Facts baked into the mapping below, verified against the live API (see
 * MULTI-ARTIST-PLAN.md "Verified against the live API" — do not re-derive these):
 * - Track `duration` is **seconds**; the app is milliseconds everywhere else, so it's
 *   multiplied by 1000 on the way in.
 * - `sources` arrive pre-sorted by `avg_rating_weighted` descending, so the default tape is
 *   just `sources.firstOrNull()`. Do NOT tie-break on `is_soundboard` — that can rank below
 *   the top slot and would override Relisten's own ranking.
 * - `mp3_url` is nullable; tracks without one are dropped, the same rule D12 applies to
 *   phish.in, so the UI and the queue builder agree on what an index means.
 */

@Serializable
data class RelistenFeatures(
    val sets: Boolean = true,
    @SerialName("multiple_sources") val multipleSources: Boolean = false,
)

@Serializable
data class RelistenArtist(
    val uuid: String,
    val slug: String,
    val name: String,
    @SerialName("show_count") val showCount: Int = 0,
    val features: RelistenFeatures = RelistenFeatures(),
)

/** `/v3/artists/{uuid}/years` — one entry per year (or era, for early ranged periods). */
@Serializable
data class RelistenYear(
    val uuid: String,
    val year: String,
    @SerialName("show_count") val showCount: Int = 0,
)

@Serializable
data class RelistenVenue(
    val name: String? = null,
    val location: String? = null,
)

@Serializable
data class RelistenTour(
    val name: String? = null,
)

@Serializable
data class RelistenPopularityWindow(
    val plays: Int = 0,
    val hours: Double = 0.0,
    @SerialName("hot_score") val hotScore: Double = 0.0,
)

@Serializable
data class RelistenPopularityWindows(
    @SerialName("48h") val window48h: RelistenPopularityWindow? = null,
    @SerialName("7d") val window7d: RelistenPopularityWindow? = null,
    @SerialName("30d") val window30d: RelistenPopularityWindow? = null,
) {
    val w48h: RelistenPopularityWindow? get() = window48h
    val w7d: RelistenPopularityWindow? get() = window7d
    val w30d: RelistenPopularityWindow? get() = window30d
}

@Serializable
data class RelistenPopularity(
    @SerialName("momentum_score") val momentumScore: Double = 0.0,
    @SerialName("trend_ratio") val trendRatio: Double = 0.0,
    val windows: RelistenPopularityWindows? = null,
)

/** A show as it appears in a year's `shows` list — no sources, just enough to browse.
 *  [avgRating] (0-10) is a genuine field on this endpoint, confirmed live (#21) — the show
 *  detail endpoint's per-source `avg_rating_weighted` was long assumed to be the only place
 *  Relisten exposes rating, which would have made "top rated" require a fetch per show. It
 *  doesn't: this list already carries it, so sorting a period's shows by rating costs nothing
 *  beyond the fetch the browse screen makes anyway. */
@Serializable
data class RelistenShowSummary(
    @SerialName("display_date") val displayDate: String,
    val venue: RelistenVenue? = null,
    val tour: RelistenTour? = null,
    @SerialName("source_count") val sourceCount: Int = 0,
    @SerialName("avg_rating") val avgRating: Double = 0.0,
    @SerialName("has_soundboard_source") val hasSoundboardSource: Boolean = false,
    @SerialName("has_streamable_flac_source") val hasStreamableFlacSource: Boolean = false,
    val popularity: RelistenPopularity? = null,
)

/** `/v3/artists/{artistUuid}/years/{yearUuid}` */
@Serializable
data class RelistenYearWithShows(
    val year: String,
    val shows: List<RelistenShowSummary> = emptyList(),
)

@Serializable
data class RelistenSourceTrack(
    val uuid: String,
    val title: String,
    @SerialName("track_position") val trackPosition: Int = 0,
    /** Seconds — see the file-level doc. Converted to ms in [toPlayableTrack]. */
    val duration: Long = 0,
    @SerialName("mp3_url") val mp3Url: String? = null,
    @SerialName("flac_url") val flacUrl: String? = null,
)

@Serializable
data class RelistenSourceSet(
    val index: Int = 0,
    val name: String = "",
    @SerialName("is_encore") val isEncore: Boolean = false,
    val tracks: List<RelistenSourceTrack> = emptyList(),
)

/** One tape of a show — what [RecordingRef] is modelling. */
@Serializable
data class RelistenSource(
    val uuid: String,
    val sets: List<RelistenSourceSet> = emptyList(),
    @SerialName("is_soundboard") val isSoundboard: Boolean = false,
    @SerialName("avg_rating_weighted") val avgRatingWeighted: Double = 0.0,
    @SerialName("num_reviews") val numReviews: Int = 0,
    val taper: String? = null,
    val lineage: String? = null,
)

/** `/v2/artists/{artistIdOrSlug}/shows/{date}` — a show with every tape of it. */
@Serializable
data class RelistenShowWithSources(
    @SerialName("display_date") val displayDate: String,
    val venue: RelistenVenue? = null,
    val tour: RelistenTour? = null,
    val sources: List<RelistenSource> = emptyList(),
    @SerialName("has_soundboard_source") val hasSoundboardSource: Boolean = false,
    @SerialName("has_streamable_flac_source") val hasStreamableFlacSource: Boolean = false,
    val popularity: RelistenPopularity? = null,
)

// -------------------------------------------------------------------- search

/** The artist embedded in a search hit — a slimmer projection than [RelistenArtist],
 *  missing `show_count`/`features`, which is fine since every destination screen
 *  re-resolves the real [ArtistRef] through `source.artists()`. */
@Serializable
data class RelistenSlimArtist(val slug: String, val name: String)

@Serializable
data class RelistenSearchShow(
    @SerialName("slim_artist") val slimArtist: RelistenSlimArtist,
    @SerialName("display_date") val displayDate: String,
    @SerialName("source_count") val sourceCount: Int = 0,
)

@Serializable
data class RelistenSearchSong(
    @SerialName("slim_artist") val slimArtist: RelistenSlimArtist,
    val name: String,
    val uuid: String,
    @SerialName("shows_played_at") val showsPlayedAt: Int = 0,
)

@Serializable
data class RelistenSearchVenue(
    @SerialName("slim_artist") val slimArtist: RelistenSlimArtist,
    val name: String,
    val location: String? = null,
    val uuid: String,
)

/** `/v3/search?q=` — six buckets; `Sources` and `Tours` are dropped by [ignoreUnknownKeys]
 *  since neither has a screen to land on (see MULTI-ARTIST-PLAN.md). */
@Serializable
data class RelistenSearchResults(
    @SerialName("Artists") val artists: List<RelistenArtist> = emptyList(),
    @SerialName("Shows") val shows: List<RelistenSearchShow> = emptyList(),
    @SerialName("Songs") val songs: List<RelistenSearchSong> = emptyList(),
    @SerialName("Venues") val venues: List<RelistenSearchVenue> = emptyList(),
)

/** `/v3/artists/{slug}/songs/{uuid}` and `/v3/artists/{slug}/venues/{uuid}` share this
 *  shape: the entity's own name plus the shows it appears in. */
@Serializable
data class RelistenSliceWithShows(
    val name: String,
    val shows: List<RelistenShowSummary> = emptyList(),
)

/** Namespace prefixes for [PeriodRef.id] so [RelistenCatalogSource.shows] can dispatch a
 *  song or venue hit to the right endpoint instead of the ordinary year lookup. A bare uuid
 *  (no prefix) is still a year id — existing routes are untouched. */
private const val SONG_PREFIX = "song:"
private const val VENUE_PREFIX = "venue:"

internal fun songPeriodId(uuid: String) = "$SONG_PREFIX$uuid"
internal fun venuePeriodId(uuid: String) = "$VENUE_PREFIX$uuid"

// ---------------------------------------------------------------- mapping

internal fun RelistenArtist.toArtistRef() = ArtistRef(
    backend = Backend.RELISTEN,
    id = slug,
    name = name,
    showCount = showCount,
    hasSets = features.sets,
    hasMultipleSources = features.multipleSources,
)

internal fun RelistenYear.toPeriodRef() = PeriodRef(id = uuid, label = year, showCount = showCount)

internal fun RelistenPopularity.toPopularity() = Popularity(
    momentumScore = momentumScore,
    trendRatio = trendRatio,
    hotScore48h = windows?.window48h?.hotScore ?: 0.0,
    hotScore7d = windows?.window7d?.hotScore ?: 0.0,
    hotScore30d = windows?.window30d?.hotScore ?: 0.0,
)

internal fun RelistenShowSummary.toShowSummary(artist: ArtistRef) = ShowSummary(
    artist = artist,
    date = displayDate,
    venue = venue?.name,
    location = venue?.location,
    tourName = tour?.name,
    recordingCount = sourceCount.coerceAtLeast(1),
    rating = avgRating,
    popularity = popularity?.toPopularity(),
    tags = deriveSyntheticTags(isSoundboard = hasSoundboardSource, hasFlac = hasStreamableFlacSource),
)

internal fun RelistenSource.toRecordingRef(): RecordingRef {
    val hasFlac = sets.any { set -> set.tracks.any { !it.flacUrl.isNullOrBlank() } }
    val taperStr = taper?.takeIf { it.isNotBlank() }
    val lineageStr = lineage?.takeIf { it.isNotBlank() }
    val matrixCheck = listOfNotNull(taperStr, lineageStr).any { it.contains("matrix", ignoreCase = true) }
    val synthetic = deriveSyntheticTags(
        isSoundboard = isSoundboard,
        looksLikeMatrix = matrixCheck,
        hasFlac = hasFlac,
    )
    return RecordingRef(
        id = uuid,
        // Relisten sends "" rather than omitting the field on plenty of sources — blank, not
        // just null, has to fall through to the SBD/AUD label or every one of them shows empty.
        label = taperStr ?: if (isSoundboard) "Soundboard" else "Audience",
        isSoundboard = isSoundboard,
        hasFlac = hasFlac,
        rating = avgRatingWeighted,
        reviewCount = numReviews,
        taper = taperStr,
        lineage = lineageStr,
        tags = synthetic,
    )
}

internal fun RelistenSourceTrack.toPlayableTrack(
    artist: ArtistRef,
    showDate: String,
    venueName: String?,
    setName: String,
    sourceTags: List<TagRef> = emptyList(),
) = PlayableTrack(
    id = uuid,
    title = title,
    // Suppressed for an artist without real sets (D-verified: Dead sources carry one
    // wrapper set literally named "Set") rather than every screen re-checking hasSets.
    setName = if (artist.hasSets) setName else "",
    durationMs = duration * 1000,
    url = mp3Url.orEmpty(),
    showDate = showDate,
    venueName = venueName,
    flacUrl = flacUrl,
    tags = if (!flacUrl.isNullOrBlank() && sourceTags.none { it.name.equals("FLAC", ignoreCase = true) }) {
        sourceTags + SYNTHETIC_TAG_FLAC
    } else {
        sourceTags
    },
)

/**
 * [recordingId] null takes the default tape — the first source, since Relisten already
 * sorts them by rating. A non-null id that matches nothing (a stale queue key against a tape
 * that's since been removed) falls back to the default rather than an empty show.
 */
internal fun RelistenShowWithSources.toShowDetail(artist: ArtistRef, recordingId: String? = null): ShowDetail {
    val chosen = recordingId?.let { id -> sources.firstOrNull { it.uuid == id } } ?: sources.firstOrNull()
    val chosenRecording = chosen?.toRecordingRef()
    val chosenTags = chosenRecording?.tags.orEmpty()
    val tracks = chosen?.sets
        ?.sortedBy { it.index }
        ?.flatMap { set ->
            set.tracks
                .filter { !it.mp3Url.isNullOrBlank() }
                .map { it.toPlayableTrack(artist, displayDate, venue?.name, set.name, sourceTags = chosenTags) }
        }
        .orEmpty()
    val hasAnySbd = hasSoundboardSource || sources.any { it.isSoundboard }
    val hasAnyFlac = hasStreamableFlacSource || sources.any { src -> src.sets.any { set -> set.tracks.any { !it.flacUrl.isNullOrBlank() } } }
    val hasAnyMatrix = sources.any { it.toRecordingRef().looksLikeMatrix }
    val summaryTags = if (chosenRecording != null) {
        chosenTags
    } else {
        deriveSyntheticTags(isSoundboard = hasAnySbd, looksLikeMatrix = hasAnyMatrix, hasFlac = hasAnyFlac)
    }
    return ShowDetail(
        summary = ShowSummary(
            artist = artist,
            date = displayDate,
            venue = venue?.name,
            location = venue?.location,
            tourName = tour?.name,
            recordingCount = sources.size.coerceAtLeast(1),
            popularity = popularity?.toPopularity(),
            tags = summaryTags,
        ),
        recording = chosenRecording,
        alternates = sources.filter { it.uuid != chosen?.uuid }.map { it.toRecordingRef() },
        tracks = tracks,
    )
}

/**
 * Maps every bucket to [SearchHits], dropping the `phish` slug: phish.in is the Phish
 * backend (D-verified, MULTI-ARTIST-PLAN.md decision 2), so a Relisten hit for it would be
 * a near-duplicate row missing likes, waveforms, and cover art. Shows and slices carry
 * artist-projection [ArtistRef]s built from [RelistenSlimArtist] rather than a full lookup —
 * every screen they lead to re-resolves the real one anyway.
 */
internal fun RelistenSearchResults.toSearchHits(): SearchHits {
    fun RelistenSlimArtist.toRef() = ArtistRef(Backend.RELISTEN, slug, name)

    return SearchHits(
        artists = artists.filter { it.slug != PHISH.id }.map { it.toArtistRef() },
        shows = shows.filter { it.slimArtist.slug != PHISH.id }.map {
            ShowSummary(
                artist = it.slimArtist.toRef(),
                date = it.displayDate,
                recordingCount = it.sourceCount.coerceAtLeast(1),
            )
        },
        slices = songs.filter { it.slimArtist.slug != PHISH.id }.map {
            SliceHit(SliceKind.SONG, it.slimArtist.toRef(), PeriodRef(songPeriodId(it.uuid), it.name, it.showsPlayedAt))
        } + venues.filter { it.slimArtist.slug != PHISH.id }.map {
            SliceHit(SliceKind.VENUE, it.slimArtist.toRef(), PeriodRef(venuePeriodId(it.uuid), it.name))
        },
    )
}

internal fun RelistenSliceWithShows.toShowSummaries(artist: ArtistRef) =
    shows.map { it.toShowSummary(artist) }

// ------------------------------------------------------------------- requests

/**
 * Plain reads, no key and no auth (per the plan's API notes). `/v3` carries artists and
 * years; the per-show endpoint with every tape is still `/v2` — Relisten hasn't moved it.
 */
object RelistenApi {
    private val DEFAULT_BASE = "https://api.relisten.net/api".toHttpUrl()

    /** Overridden by tests to point at a local mock server, same pattern as PhishInApi. */
    internal var baseUrl: HttpUrl = DEFAULT_BASE

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory { TimingEventListener("RelistenApi") }
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun path(vararg segments: String) =
        baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}", resp.code)
            resp.body?.string() ?: throw ApiException("Empty response")
        }
    }

    suspend fun artists(): List<RelistenArtist> =
        json.decodeFromString(get(path("v3", "artists").build()))

    suspend fun years(artistUuid: String): List<RelistenYear> =
        json.decodeFromString(get(path("v3", "artists", artistUuid, "years").build()))

    suspend fun year(artistUuid: String, yearUuid: String): RelistenYearWithShows =
        json.decodeFromString(get(path("v3", "artists", artistUuid, "years", yearUuid).build()))

    suspend fun show(artistIdOrSlug: String, date: String): RelistenShowWithSources =
        json.decodeFromString(get(path("v2", "artists", artistIdOrSlug, "shows", date).build()))

    /** [term] goes in `q`, a query parameter — unlike phish.in's `/search/{term}` path segment. */
    suspend fun search(term: String): RelistenSearchResults {
        val url = baseUrl.newBuilder().addPathSegment("v3").addPathSegment("search")
            .addQueryParameter("q", term).build()
        return json.decodeFromString(get(url))
    }

    suspend fun song(artistIdOrSlug: String, songUuid: String): RelistenSliceWithShows =
        json.decodeFromString(get(path("v3", "artists", artistIdOrSlug, "songs", songUuid).build()))

    suspend fun venue(artistIdOrSlug: String, venueUuid: String): RelistenSliceWithShows =
        json.decodeFromString(get(path("v3", "artists", artistIdOrSlug, "venues", venueUuid).build()))
}

/**
 * Wires [RelistenApi] and the mapping above behind the [MusicSource] seam P2 defined.
 * `/v3/artists/{artistUuidOrSlug}/years` and its `.../years/{yearUuid}` sibling both accept
 * the slug directly (confirmed live), so [ArtistRef.id] — already the slug — needs no uuid
 * lookup to feed either call.
 */
object RelistenCatalogSource : MusicSource {
    override val backend = Backend.RELISTEN

    // The one cache O4 originally called for: a ~200-entry list re-fetched on every
    // back-navigation is the one case worth it on its own. #61 gives it the same TTL as the
    // caches below rather than letting it live forever.
    // Not private: tests reset it between runs, the same way PhishInApi exposes baseUrl.
    @Volatile internal var cachedArtists: List<ArtistRef>? = null
    @Volatile private var cachedArtistsAt: Long = 0L

    // Periods, shows, and show detail (#61) — O4's deliberate scope cut on everything below
    // the artist list. Keyed by [ArtistRef.key], which already embeds the backend, so there's
    // no risk of a phish.in and a Relisten period id colliding in the same map.
    internal val periodsCache = TtlCache<String, List<PeriodRef>>(CATALOG_CACHE_TTL_MS, maxEntries = 250)
    internal val showsCache = TtlCache<String, List<ShowSummary>>(CATALOG_CACHE_TTL_MS, maxEntries = 400)
    internal val showDetailCache = TtlCache<String, ShowDetail>(CATALOG_CACHE_TTL_MS, maxEntries = 200)

    override suspend fun artists(): List<ArtistRef> {
        cachedArtists?.let { if (System.currentTimeMillis() - cachedArtistsAt < CATALOG_CACHE_TTL_MS) return it }
        return RelistenApi.artists().map { it.toArtistRef() }.also {
            cachedArtists = it
            cachedArtistsAt = System.currentTimeMillis()
        }
    }

    override suspend fun periods(artist: ArtistRef): List<PeriodRef> =
        periodsCache.get(artist.key) ?: RelistenApi.years(artist.id).map { it.toPeriodRef() }
            .also { periodsCache.put(artist.key, it) }

    /** A [period] id namespaced `song:`/`venue:` (from a search hit) routes to the matching
     *  entity endpoint instead of the ordinary year lookup — see the prefix constants above. */
    override suspend fun shows(artist: ArtistRef, period: PeriodRef): List<ShowSummary> {
        val cacheKey = "${artist.key}/${period.id}"
        showsCache.get(cacheKey)?.let { return it }
        val shows = when {
            period.id.startsWith(SONG_PREFIX) ->
                RelistenApi.song(artist.id, period.id.removePrefix(SONG_PREFIX)).toShowSummaries(artist)
            period.id.startsWith(VENUE_PREFIX) ->
                RelistenApi.venue(artist.id, period.id.removePrefix(VENUE_PREFIX)).toShowSummaries(artist)
            else -> RelistenApi.year(artist.id, period.id).shows.map { it.toShowSummary(artist) }
        }
        showsCache.put(cacheKey, shows)
        return shows
    }

    override suspend fun show(artist: ArtistRef, date: String, recordingId: String?): ShowDetail {
        val cacheKey = "${artist.key}/$date/${recordingId ?: "default"}"
        showDetailCache.get(cacheKey)?.let { return it }
        return RelistenApi.show(artist.id, date).toShowDetail(artist, recordingId)
            .also { showDetailCache.put(cacheKey, it) }
    }

    override suspend fun search(term: String): SearchHits = RelistenApi.search(term).toSearchHits()

    /** Test-only hook: clears every cache above in one call, the way [PhishInSource] does. */
    internal fun resetCache() {
        cachedArtists = null
        cachedArtistsAt = 0L
        periodsCache.clear()
        showsCache.clear()
        showDetailCache.clear()
    }
}
