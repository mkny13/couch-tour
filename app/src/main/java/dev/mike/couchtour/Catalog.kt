package dev.mike.couchtour

/**
 * The backend-neutral catalog: just enough of a model to browse to a playable track.
 *
 * The app has never had a domain layer — phish.in's `@Serializable` DTOs went straight into
 * the UI, which was right while there was one backend. A second one needs somewhere for the
 * two to meet, but only on the browse path. Login, likes, playlists and search stay on the
 * raw phish.in DTOs, because they are phish.in account features and routing them through an
 * abstraction with one implementation would buy nothing.
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
)

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
}

// ------------------------------------------------------------------- phish.in

/** phish.in is a single-artist archive, so its artist is a constant rather than a fetch. */
val PHISH = ArtistRef(Backend.PHISHIN, "phish", "Phish")

/**
 * Adapts the existing [PhishInApi] to the seam. Deliberately thin: it reuses the client
 * untouched, including the period/year-range branch and the audio filtering, so nothing
 * about the Phish path changes and none of the existing tests move.
 */
object PhishInSource : MusicSource {
    override val backend = Backend.PHISHIN

    override suspend fun artists() = listOf(PHISH)

    override suspend fun periods(artist: ArtistRef) =
        PhishInApi.years().map { it.toPeriodRef() }

    override suspend fun shows(artist: ArtistRef, period: PeriodRef) =
        PhishInApi.showsForPeriod(period.id).map { it.toShowSummary() }

    override suspend fun show(artist: ArtistRef, date: String, recordingId: String?) =
        PhishInApi.show(date).toShowDetail()
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
