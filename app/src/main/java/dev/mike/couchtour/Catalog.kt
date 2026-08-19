package dev.mike.couchtour

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/**
 * The backend-neutral catalog: just enough of a model to browse to a playable track.
 *
 * The app has never had a domain layer — phish.in's `@Serializable` DTOs went straight into
 * the UI, which was right while there was one backend. A second one needs somewhere for the
 * two to meet, on the browse path and now the search path too. Login, likes, and playlists
 * stay on the raw phish.in DTOs, because they are phish.in account features and routing them
 * through an abstraction with one implementation would buy nothing.
 *
 * The mapping lives in pure functions rather than inside the [MusicSource] implementations
 * so it can be tested without a network call (D36).
 */

enum class Backend(val id: String) {
    PHISHIN("phishin"),
    RELISTEN("relisten");

    companion object {
        /** Null for anything unrecognised — these ids travel in nav routes and media IDs. */
        fun from(id: String): Backend? = entries.firstOrNull { it.id == id }
    }
}

data class ArtistRef(
    val backend: Backend,
    /** phish.in has one artist; on Relisten this is the artist's slug, e.g. "grateful-dead". */
    val id: String,
    val name: String,
    val showCount: Int = 0,
    /** Relisten's `features.sets`. False means sources carry one wrapper set named "Set",
     *  so a set-name divider would be a meaningless one. Always true for phish.in. */
    val hasSets: Boolean = true,
    /** Relisten's `features.multiple_sources`. False means there is no tape to switch. */
    val hasMultipleSources: Boolean = false,
) {
    /** Identifies this artist across backends for favoriting (#14) — stable, storable, and
     *  distinct from [name], which two artists on different backends can share (see
     *  [mergeArtists]'s Relisten-Phish dedup). */
    val key: String get() = "${backend.id}:$id"
}

/**
 * A browsable slice of an artist's catalog. On phish.in this is a *period* and may be a range
 * ("1983-1987"), which is why [id] is carried verbatim — `showsForPeriod` needs it back to
 * pick `year_range=` over `year=` (D11). On Relisten it is a year's uuid.
 */
data class PeriodRef(
    val id: String,
    val label: String,
    val showCount: Int = 0,
    val artUrl: String? = null,
)

data class ShowSummary(
    val artist: ArtistRef,
    val date: String,
    val venue: String? = null,
    val location: String? = null,
    val tourName: String? = null,
    val artUrl: String? = null,
    /** Some of the audio is missing. phish.in's `audio_status`; Relisten has no analogue. */
    val partial: Boolean = false,
    val recordingCount: Int = 1,
    /** Relisten's `avg_rating` (0-10), fetched free alongside the rest of a period's shows —
     *  there's no per-show equivalent on phish.in, where popularity means [Show.likesCount]
     *  and is queried server-side instead (see [POPULAR_PERIOD_ID]). */
    val rating: Double = 0.0,
) {
    /** "McNichols Arena · Denver, CO" */
    val where: String get() = listOfNotNull(venue, location).joinToString(" · ")
}

/**
 * One tape of one show.
 *
 * This is the concept phish.in doesn't have. Relisten carries around nine recordings of an
 * average Grateful Dead show — different tapers, different lineage, different soundboard or
 * audience provenance — and each splits the music into its own tracks. That last part is why
 * a recording is part of a queue key and not a display detail.
 */
data class RecordingRef(
    val id: String,
    val label: String,
    val isSoundboard: Boolean = false,
    val rating: Double = 0.0,
    val reviewCount: Int = 0,
    val taper: String? = null,
    val lineage: String? = null,
)

data class PlayableTrack(
    val id: String,
    val title: String,
    val setName: String = "",
    /** Milliseconds. Relisten reports seconds and is converted on the way in. */
    val durationMs: Long = 0,
    val url: String,
    val waveformUrl: String? = null,
    /** The show this track was played at — the album an external scrobbler reads (D50). */
    val showDate: String? = null,
    val venueName: String? = null,
    val artUrl: String? = null,
)

data class ShowDetail(
    val summary: ShowSummary,
    val recording: RecordingRef? = null,
    val alternates: List<RecordingRef> = emptyList(),
    val tracks: List<PlayableTrack> = emptyList(),
) {
    /**
     * Where this queue's progress is stored, or null if it isn't resumable.
     *
     * A phish.in show keys itself exactly as it always has, which is what lets a second
     * backend arrive without a migration. A Relisten show without a chosen tape has no key
     * at all rather than a broken one — recording nothing beats recording a position that
     * parses back to nothing, the same call shuffle makes (D42).
     */
    val queueKey: String?
        get() = when (summary.artist.backend) {
            Backend.PHISHIN -> showQueueKey(summary.date)
            Backend.RELISTEN -> recording?.let {
                recordingQueueKey(summary.artist.id, summary.date, it.id)
            }
        }
}

interface MusicSource {
    val backend: Backend

    suspend fun artists(): List<ArtistRef>
    suspend fun periods(artist: ArtistRef): List<PeriodRef>
    suspend fun shows(artist: ArtistRef, period: PeriodRef): List<ShowSummary>

    /** [recordingId] null takes the source's own default — the best tape, where there's a choice. */
    suspend fun show(artist: ArtistRef, date: String, recordingId: String? = null): ShowDetail

    /** [term] is at least 3 characters — both APIs return nothing below that. A backend
     *  with nothing to offer returns empty hits rather than throwing. */
    suspend fun search(term: String): SearchHits
}

/** Shared by MainActivity's screens and PlaybackService's Auto browse tree — one seam, two callers. */
internal fun sourceFor(backend: Backend): MusicSource = when (backend) {
    Backend.PHISHIN -> PhishInSource
    Backend.RELISTEN -> RelistenCatalogSource
}

/**
 * Every artist across every backend, for the Home screen's artist list. Phish is pinned
 * first — it is the only artist with an account, likes, and playlists behind it — and the
 * rest sort by how much tape exists (most-recorded first).
 *
 * Relisten separately archives Phish too (its own taper-community collection, slug "phish",
 * a different show count than phish.in's) — so without filtering, "Phish" would appear
 * twice with two different numbers. The pinned phish.in entry wins; Relisten's copy is
 * dropped rather than shown as a second, confusing "Phish".
 *
 * [favorites] (#14, [ArtistRef.key]s) pin their artists to the front of the *rest* of the
 * list, right after Phish rather than ahead of it — Phish's position-1 slot is earned by its
 * special account features, not by being liked, so favoriting never displaces it. Within each
 * group (favorited, then not) the existing show-count-descending order still applies.
 */
internal fun mergeArtists(
    perBackend: Map<Backend, List<ArtistRef>>,
    favorites: Set<String> = emptySet(),
): List<ArtistRef> {
    val phish = perBackend[Backend.PHISHIN].orEmpty()
    val rest = perBackend.filterKeys { it != Backend.PHISHIN }.values.flatten()
        .filterNot { it.name.equals(PHISH.name, ignoreCase = true) }
    val (favorited, unfavorited) = rest.partition { it.key in favorites }
    return phish + favorited.sortedByDescending { it.showCount } + unfavorited.sortedByDescending { it.showCount }
}

/**
 * Picks a random show for the Home screen's "Surprise me" button (#20). Neither backend
 * exposes a random-show endpoint, so this walks the same artist → period → show path the
 * browse screens do: a random artist from the merged list, a random period of that artist,
 * then a random show within it — 2-3 sequential calls, same as browsing by hand.
 *
 * [random] and [source] are parameters rather than the real [sourceFor] so the selection is
 * deterministic and network-free in tests. A period with only partial-audio shows falls back
 * to picking among them anyway, rather than costing another round trip to find a period that
 * has a complete one.
 */
suspend fun pickRandomShow(
    artists: List<ArtistRef>,
    random: Random = Random,
    source: (Backend) -> MusicSource = ::sourceFor,
): ShowSummary {
    val artist = artists.random(random)
    val src = source(artist.backend)
    val period = src.periods(artist).random(random)
    val shows = src.shows(artist, period)
    val candidates = shows.filterNot { it.partial }.ifEmpty { shows }
    return candidates.random(random)
}

/** What a song or venue hit resolves to: a named slice of one artist's catalog. */
enum class SliceKind(val heading: String) { SONG("Songs"), VENUE("Venues") }

data class SliceHit(val kind: SliceKind, val artist: ArtistRef, val period: PeriodRef)

/**
 * The merged result of searching every backend. Kept flat (not grouped by artist) so
 * [SearchResultsList] can render one section per type, matching the phish.in-only layout it
 * already had; [artistsPresent] and [filteredTo] are what let the UI narrow to one artist
 * without a second fetch.
 */
data class SearchHits(
    val artists: List<ArtistRef> = emptyList(),
    val shows: List<ShowSummary> = emptyList(),
    val slices: List<SliceHit> = emptyList(),
    /** phish.in only, raw DTOs on purpose: playing a hit queues its whole show
     *  (PlayerViewModel.playTrack) and the row carries a like button — both account
     *  features with no Relisten analogue. */
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    /** Backends whose search failed, so partial results can say so instead of reading as
     *  "nothing matched". */
    val failed: Set<Backend> = emptySet(),
) {
    val isEmpty: Boolean
        get() = artists.isEmpty() && shows.isEmpty() && slices.isEmpty() &&
            tracks.isEmpty() && playlists.isEmpty()

    operator fun plus(other: SearchHits) = SearchHits(
        artists = artists + other.artists,
        shows = shows + other.shows,
        slices = slices + other.slices,
        tracks = tracks + other.tracks,
        playlists = playlists + other.playlists,
        failed = failed + other.failed,
    )

    /** The chip row's contents — every backend+artist that produced at least one hit. */
    val artistsPresent: List<ArtistRef>
        get() = (artists + shows.map { it.artist } + slices.map { it.artist } +
            tracks.map { PHISH } + playlists.map { PHISH })
            .distinctBy { it.backend to it.id }

    /** Narrows to one artist's hits, or returns everything for a null [key]. phish.in's
     *  tracks and playlists are Phish's alone, so they drop out for any other artist. */
    fun filteredTo(key: ArtistRef?): SearchHits {
        if (key == null) return this
        return SearchHits(
            artists = artists.filter { it.backend == key.backend && it.id == key.id },
            shows = shows.filter { it.artist.backend == key.backend && it.artist.id == key.id },
            slices = slices.filter { it.artist.backend == key.backend && it.artist.id == key.id },
            tracks = if (key.backend == Backend.PHISHIN && key.id == PHISH.id) tracks else emptyList(),
            playlists = if (key.backend == Backend.PHISHIN && key.id == PHISH.id) playlists else emptyList(),
            failed = failed,
        )
    }
}

/** Fans out a search across every backend; one backend's failure doesn't cost the rest. */
suspend fun searchAll(term: String): SearchHits = coroutineScope {
    Backend.entries
        .map { b ->
            async {
                runCatching { sourceFor(b).search(term) }.getOrElse { SearchHits(failed = setOf(b)) }
            }
        }
        .awaitAll()
        .fold(SearchHits()) { acc, hits -> acc + hits }
}

// ------------------------------------------------------------------- phish.in

/** phish.in is a single-artist archive, so its artist is a constant rather than a fetch. */
val PHISH = ArtistRef(Backend.PHISHIN, "phish", "Phish")

/**
 * A synthetic [PeriodRef.id], not a real year — [PhishInSource.periods] prepends it so
 * "Popular" (#21) rides the same periods()/shows() seam MainActivity and Android Auto's
 * browse tree already consume, rather than a one-off screen bolted on beside it. "Popular"
 * sorts ahead of every numeric year label when a caller orders periods by label descending
 * (`'P' > '9'`), so no separate pinning logic is needed to keep it first.
 */
const val POPULAR_PERIOD_ID = "popular"
const val POPULAR_PERIOD_LABEL = "Popular"
/** What every UI shows in place of a show count for [POPULAR_PERIOD_ID] — it has none of
 *  its own, unlike a real year (MainActivity.kt's `ArtistScreen`, PlaybackService.kt's
 *  `yearsChildren`/`artistPeriodsChildren` all render this rather than "0 shows"). */
const val POPULAR_PERIOD_SUBTITLE = "Top rated by likes"

/**
 * Adapts the existing [PhishInApi] to the seam. Deliberately thin: it reuses the client
 * untouched, including the period/year-range branch and the audio filtering, so nothing
 * about the Phish path changes and none of the existing tests move.
 */
object PhishInSource : MusicSource {
    override val backend = Backend.PHISHIN

    override suspend fun artists() = listOf(PHISH)

    override suspend fun periods(artist: ArtistRef): List<PeriodRef> =
        listOf(PeriodRef(POPULAR_PERIOD_ID, POPULAR_PERIOD_LABEL)) + PhishInApi.years().map { it.toPeriodRef() }

    override suspend fun shows(artist: ArtistRef, period: PeriodRef): List<ShowSummary> =
        if (period.id == POPULAR_PERIOD_ID) PhishInApi.popularShows().map { it.toShowSummary() }
        else PhishInApi.showsForPeriod(period.id).map { it.toShowSummary() }

    override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) =
        PhishInApi.show(date).toShowDetail()

    override suspend fun search(term: String) = PhishInApi.search(term).toSearchHits()
}

internal fun Period.toPeriodRef() =
    PeriodRef(id = period, label = period, showCount = showsWithAudioCount, artUrl = coverArtUrls?.medium)

internal fun Show.toShowSummary() = ShowSummary(
    artist = PHISH,
    date = date,
    venue = venueName,
    location = location,
    tourName = tourName,
    artUrl = albumCoverUrl ?: coverArtUrls?.medium,
    partial = audioStatus == "partial",
    recordingCount = 1,
)

internal fun Show.toShowDetail(): ShowDetail {
    val summary = toShowSummary()
    return ShowDetail(
        summary = summary,
        // Filtering here is what keeps the UI and the queue builder agreeing on what index
        // 4 means (D12) — they both read this list rather than filtering separately.
        tracks = tracks.filter { it.playable }.map { it.toPlayableTrack(summary.artUrl) },
    )
}

internal fun SearchResults.toSearchHits() = SearchHits(
    shows = shows.map { it.toShowSummary() },
    tracks = tracks,
    playlists = playlists,
)

internal fun Track.toPlayableTrack(showArt: String?) = PlayableTrack(
    id = id.toString(),
    title = title,
    setName = setName,
    // Already milliseconds. Relisten's are seconds — see RelistenTrack.toPlayableTrack.
    durationMs = duration,
    url = mp3Url.orEmpty(),
    waveformUrl = waveformImageUrl,
    showDate = showDate,
    venueName = venueName,
    artUrl = showAlbumCoverUrl ?: showArt,
)

// -------------------------------------------------------------------- sharing

/**
 * The public web page for a show — what a share sheet should link to so a recipient without
 * the app can still open it. A function of [Backend] rather than a field on [ShowSummary],
 * matching this file's existing backend-dispatch pattern (D36): it's a derivation of data
 * the model already carries, not new data of its own.
 *
 * Both URLs confirmed live (#19): phish.in's `/<date>` returns its own `og:url` matching
 * exactly; Relisten's `/<artist-slug>/<date>` renders that show's own `<title>`, while an
 * unknown artist or date 404s with a real "404 - Page Not Found" page — Relisten has no
 * catch-all SPA shell here, so a 200 means the page is genuine.
 */
fun showShareUrl(artist: ArtistRef, date: String): String = when (artist.backend) {
    Backend.PHISHIN -> "https://phish.in/$date"
    Backend.RELISTEN -> "https://relisten.net/${artist.id}/$date"
}

/**
 * The public web page for one track, or null when the backend doesn't have one — callers
 * fall back to [showShareUrl] rather than share a broken link.
 *
 * phish.in publishes one per track, `/<date>/<track-slug>` (confirmed live: its own `og:url`
 * and `og:title` echo the slug back, and an unrecognised slug falls back to the show's own
 * title rather than 404ing — so the page is real and slug-validated, not decorative).
 * Relisten has no equivalent: unlike phish.in, `/<artist>/<date>/<source-uuid>` 404s live,
 * the same as any other unknown Relisten route — there's nothing to link to.
 */
fun trackShareUrl(artist: ArtistRef, date: String, trackSlug: String?): String? = when (artist.backend) {
    Backend.PHISHIN -> trackSlug?.let { "https://phish.in/$date/$it" }
    Backend.RELISTEN -> null
}
