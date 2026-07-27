package dev.mike.phishin

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

@Serializable
data class CoverArt(
    val large: String? = null,
    val medium: String? = null,
    val small: String? = null,
)

/**
 * /years returns "periods", not plain years: most are a single year ("1997") but the
 * early ones are ranges ("1983-1987"). The two forms need different query params, see
 * [PhishInApi.showsForPeriod].
 */
@Serializable
data class Period(
    val period: String,
    @SerialName("shows_count") val showsCount: Int = 0,
    @SerialName("shows_with_audio_count") val showsWithAudioCount: Int = 0,
    val era: String? = null,
    @SerialName("cover_art_urls") val coverArtUrls: CoverArt? = null,
)

@Serializable
data class Show(
    val date: String,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("tour_name") val tourName: String? = null,
    @SerialName("audio_status") val audioStatus: String = "missing",
    val duration: Long = 0,
    @SerialName("album_cover_url") val albumCoverUrl: String? = null,
    @SerialName("cover_art_urls") val coverArtUrls: CoverArt? = null,
    val venue: Venue? = null,
    val tracks: List<Track> = emptyList(),
) {
    val location: String? get() = venue?.location
}

@Serializable
data class Venue(
    val name: String? = null,
    val location: String? = null,
)

@Serializable
data class Track(
    val id: Long,
    val title: String,
    val position: Int = 0,
    /** milliseconds */
    val duration: Long = 0,
    @SerialName("set_name") val setName: String = "",
    @SerialName("audio_status") val audioStatus: String = "missing",
    @SerialName("mp3_url") val mp3Url: String? = null,
    @SerialName("waveform_image_url") val waveformImageUrl: String? = null,
    // Present on search results and /tracks, absent when nested inside a show.
    @SerialName("show_date") val showDate: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_location") val venueLocation: String? = null,
) {
    val playable: Boolean get() = !mp3Url.isNullOrBlank() && audioStatus != "missing"
}

/**
 * `/search` also returns songs, venues, tags, and playlists. Only shows and tracks are
 * modelled: both are directly actionable (open a show, play a performance), while the
 * others would each need a screen that doesn't exist yet.
 */
@Serializable
data class SearchResults(
    @SerialName("exact_show") val exactShow: Show? = null,
    @SerialName("other_shows") val otherShows: List<Show> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val shows: List<Show> get() = listOfNotNull(exactShow) + otherShows
    val isEmpty: Boolean get() = shows.isEmpty() && tracks.isEmpty()
}

@Serializable
private data class ShowsPage(
    val shows: List<Show> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
    @SerialName("current_page") val currentPage: Int = 1,
)

object PhishInApi {
    private val BASE = "https://phish.in/api/v2".toHttpUrl()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}")
            resp.body?.string() ?: throw ApiException("Empty response")
        }
    }

    private fun path(vararg segments: String) =
        BASE.newBuilder().apply { segments.forEach { addPathSegment(it) } }

    suspend fun years(): List<Period> =
        json.decodeFromString<List<Period>>(get(path("years").build()))
            .filter { it.showsWithAudioCount > 0 }

    /**
     * A period is either "1997" or "1983-1987"; the API wants `year=` for the former and
     * `year_range=` for the latter. Passing a range to `year=` silently returns nothing.
     */
    suspend fun showsForPeriod(period: String): List<Show> {
        val url = path("shows")
            .addQueryParameter(if (period.contains("-")) "year_range" else "year", period)
            .addQueryParameter("audio_status", "complete_or_partial")
            .addQueryParameter("sort", "date:asc")
            .addQueryParameter("per_page", "1000")
            .build()
        return json.decodeFromString<ShowsPage>(get(url)).shows
    }

    suspend fun show(date: String): Show =
        json.decodeFromString<Show>(get(path("shows", date).build()))

    /** The API rejects terms shorter than 3 characters. */
    suspend fun search(term: String): SearchResults {
        val url = path("search", term)
            .addQueryParameter("audio_status", "complete_or_partial")
            .build()
        return json.decodeFromString<SearchResults>(get(url))
    }
}

class ApiException(message: String) : Exception(message)
