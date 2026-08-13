package dev.mike.couchtour

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs and pure mapping for [api.relisten.net](https://api.relisten.net), Relisten's public
 * API. The request layer (`RelistenApi`, matching `PhishInApi`'s shape) is P4 — this is only
 * the shapes and the mapping into the backend-neutral model from `Catalog.kt`, so both can be
 * tested without a network call, the same reasoning D36 and P2's `Catalog.kt` mapping used.
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

/** A show as it appears in a year's `shows` list — no sources, just enough to browse. */
@Serializable
data class RelistenShowSummary(
    @SerialName("display_date") val displayDate: String,
    val venue: RelistenVenue? = null,
    val tour: RelistenTour? = null,
    @SerialName("source_count") val sourceCount: Int = 0,
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
)

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

internal fun RelistenShowSummary.toShowSummary(artist: ArtistRef) = ShowSummary(
    artist = artist,
    date = displayDate,
    venue = venue?.name,
    location = venue?.location,
    tourName = tour?.name,
    recordingCount = sourceCount.coerceAtLeast(1),
)

internal fun RelistenSource.toRecordingRef() = RecordingRef(
    id = uuid,
    label = taper ?: if (isSoundboard) "Soundboard" else "Audience",
    isSoundboard = isSoundboard,
    rating = avgRatingWeighted,
    reviewCount = numReviews,
    taper = taper,
    lineage = lineage,
)

internal fun RelistenSourceTrack.toPlayableTrack(artist: ArtistRef, showDate: String, venueName: String?, setName: String) =
    PlayableTrack(
        id = uuid,
        title = title,
        // Suppressed for an artist without real sets (D-verified: Dead sources carry one
        // wrapper set literally named "Set") rather than every screen re-checking hasSets.
        setName = if (artist.hasSets) setName else "",
        durationMs = duration * 1000,
        url = mp3Url.orEmpty(),
        showDate = showDate,
        venueName = venueName,
    )

/**
 * [recordingId] null takes the default tape — the first source, since Relisten already
 * sorts them by rating. A non-null id that matches nothing (a stale queue key against a tape
 * that's since been removed) falls back to the default rather than an empty show.
 */
internal fun RelistenShowWithSources.toShowDetail(artist: ArtistRef, recordingId: String? = null): ShowDetail {
    val chosen = recordingId?.let { id -> sources.firstOrNull { it.uuid == id } } ?: sources.firstOrNull()
    val tracks = chosen?.sets
        ?.sortedBy { it.index }
        ?.flatMap { set ->
            set.tracks
                .filter { !it.mp3Url.isNullOrBlank() }
                .map { it.toPlayableTrack(artist, displayDate, venue?.name, set.name) }
        }
        .orEmpty()
    return ShowDetail(
        summary = ShowSummary(
            artist = artist,
            date = displayDate,
            venue = venue?.name,
            location = venue?.location,
            tourName = tour?.name,
            recordingCount = sources.size.coerceAtLeast(1),
        ),
        recording = chosen?.toRecordingRef(),
        alternates = sources.filter { it.uuid != chosen?.uuid }.map { it.toRecordingRef() },
        tracks = tracks,
    )
}
