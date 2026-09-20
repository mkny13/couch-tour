package dev.mike.couchtour

// The YouTube browse surface's catalog wiring (#233) — the Android twin of the macOS app
// target's YouTubeChannels (D251): a hand-curated artist→channel map plus a MusicSource
// that fronts YouTubeApi for the artist page's section. Kept out of Catalog.kt so the
// tape backends stay together and this stays a single small file, like the macOS one.

/**
 * Which YouTube channel each artist's videos come from — the seam Part 1 (#232) left as
 * a parameter and D251 resolved on macOS. Curated by hand, keyed `backend:artistId`
 * ([ArtistRef.key]), because there is no catalog API for it: YouTube channel IDs are
 * opaque and only the owner knows which channel is authoritative for a given tape
 * source. An artist absent from the map has no YouTube section at all — the artist page
 * hides it rather than guessing a channel.
 */
object YouTubeChannels {
    private val mappings = mapOf(
        // Phish's official channel, resolved from youtube.com/@phish's channel metadata
        // (same entry as the macOS map, D251).
        "phishin:phish" to "UCDEPOd0RCvw8iSTqFpSBZLA",
    )

    fun channel(artist: ArtistRef): String? = mappings[artist.key]
}

/**
 * The channel whose videos an artist's page lists, or null when the section must hide
 * entirely: artists the curated map doesn't cover (D251), and installs with no API key
 * (D44/D251 precedent — owner-supplied) — the fetch could only ever fail, so a
 * permanently-broken section would be noise. Error/retry states are reserved for real
 * fetch failures (network, quota).
 */
internal fun youtubeSectionChannel(artist: ArtistRef): String? =
    YouTubeApi.apiKey?.takeIf { it.isNotBlank() }?.let { YouTubeChannels.channel(artist) }

/**
 * The YouTube backend's [MusicSource]. YouTube isn't a tape catalog — no artists, no
 * periods, no shows, and no term-based search (#233 out of scope) — so every inherited
 * browse method answers empty and only [youtubeContent] is real: the videos of the
 * channel an artist maps to, newest first, for the artist page's section.
 */
object YouTubeCatalogSource : MusicSource {
    override val backend = Backend.YOUTUBE

    override suspend fun artists(): List<ArtistRef> = emptyList()
    override suspend fun periods(artist: ArtistRef): List<PeriodRef> = emptyList()
    override suspend fun shows(artist: ArtistRef, period: PeriodRef): List<ShowSummary> = emptyList()

    override suspend fun search(term: String): SearchHits = SearchHits()

    /** Unreachable in practice — nothing can navigate to a YouTube "show" — so the
     *  detail is an empty shell around the requested artist rather than a throw that
     *  a generic caller wouldn't expect. */
    override suspend fun show(artist: ArtistRef, date: String, recordingId: String?): ShowDetail =
        ShowDetail(summary = ShowSummary(artist = artist, date = date))

    override suspend fun youtubeContent(artist: ArtistRef): List<YouTubeVideo> {
        val channelId = youtubeSectionChannel(artist) ?: return emptyList()
        return YouTubeApi.search(channelId)
    }
}
