package dev.mike.couchtour

/**
 * Media IDs for the Android Auto / Automotive browse tree that [PlaybackService] serves
 * through [androidx.media3.session.MediaLibraryService.MediaLibrarySession.Callback].
 *
 * This is a separate namespace from the plain numeric track IDs [mediaItem] builds — a
 * browsable node and a playable track never collide, so a string prefix is enough to tell
 * them apart, the same trick [parseQueueKey] uses for the progress table's queue keys.
 */
sealed class BrowseNode {
    object Root : BrowseNode()
    object Continue : BrowseNode()
    object Years : BrowseNode()
    object Artists : BrowseNode()
    data class Year(val period: String) : BrowseNode()
    data class Tour(val period: String, val name: String) : BrowseNode()
    data class ShowNode(val date: String) : BrowseNode()

    /** One Relisten (or, in principle, any [Backend]) artist — lists its periods. */
    data class Artist(val backend: String, val artistId: String) : BrowseNode()

    /** One period of one artist — lists its shows. */
    data class ArtistPeriod(val backend: String, val artistId: String, val periodId: String) : BrowseNode()

    /** One Relisten show, always its default tape (P3) — Auto has no tape switcher. */
    data class Recording(val backend: String, val artistId: String, val date: String) : BrowseNode()

    /** [queueKey] is a [Progress.queueKey] verbatim, e.g. "show:1997-11-17" — reuse, not reinvention. */
    data class Resume(val queueKey: String) : BrowseNode()

    val id: String
        get() = when (this) {
            Root -> ROOT_ID
            Continue -> CONTINUE_ID
            Years -> YEARS_ID
            Artists -> ARTISTS_ID
            is Year -> "year:$period"
            is Tour -> "tour:$period:$name"
            is ShowNode -> "show:$date"
            is Artist -> "artist:$backend:$artistId"
            is ArtistPeriod -> "artistperiod:$backend:$artistId:$periodId"
            is Recording -> "recording:$backend:$artistId:$date"
            is Resume -> "resume:$queueKey"
        }

    companion object {
        const val ROOT_ID = "root"
        const val CONTINUE_ID = "continue"
        const val YEARS_ID = "years"
        const val ARTISTS_ID = "artists"

        /** Returns null for anything unrecognised — a stale or foreign media ID is skipped, not guessed at. */
        fun parse(id: String): BrowseNode? = when {
            id == ROOT_ID -> Root
            id == CONTINUE_ID -> Continue
            id == YEARS_ID -> Years
            id == ARTISTS_ID -> Artists

            id.startsWith("resume:") ->
                id.removePrefix("resume:").takeIf { it.isNotEmpty() }?.let { Resume(it) }

            // A tour name can itself contain a colon, so only the first one is a delimiter —
            // the same reasoning parseQueueKey uses for a playlist slug.
            id.startsWith("tour:") -> {
                val rest = id.removePrefix("tour:")
                val period = rest.substringBefore(':')
                val name = rest.substringAfter(':', missingDelimiterValue = "")
                if (period.isNotEmpty() && name.isNotEmpty()) Tour(period, name) else null
            }

            id.startsWith("year:") ->
                id.removePrefix("year:").takeIf { it.isNotEmpty() }?.let { Year(it) }

            id.startsWith("show:") ->
                id.removePrefix("show:").takeIf { it.isNotEmpty() }?.let { ShowNode(it) }

            // Backend ids ("phishin", "relisten") and artist slugs never contain a colon, so a
            // plain split is safe — unlike the tour/playlist cases above.
            id.startsWith("artistperiod:") -> {
                val parts = id.removePrefix("artistperiod:").split(':')
                if (parts.size == 3 && parts.all { it.isNotEmpty() }) ArtistPeriod(parts[0], parts[1], parts[2]) else null
            }

            id.startsWith("artist:") -> {
                val parts = id.removePrefix("artist:").split(':')
                if (parts.size == 2 && parts.all { it.isNotEmpty() }) Artist(parts[0], parts[1]) else null
            }

            id.startsWith("recording:") -> {
                val parts = id.removePrefix("recording:").split(':')
                if (parts.size == 3 && parts.all { it.isNotEmpty() }) Recording(parts[0], parts[1], parts[2]) else null
            }

            else -> null
        }
    }
}
