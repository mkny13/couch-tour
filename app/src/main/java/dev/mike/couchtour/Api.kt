package dev.mike.couchtour

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    val id: Long = 0,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("liked_by_user") val likedByUser: Boolean = false,
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

/** The three things phish.in lets you like. Matches the API's `likable_type` values. */
enum class Likable { Show, Track, Playlist }

@Serializable
data class Track(
    val id: Long,
    val title: String,
    /** phish.in's own URL slug for this track's page, e.g. "mikes-song" at
     *  https://phish.in/1997-11-22/mikes-song (confirmed live: the page's own og:url and
     *  og:title echo the slug back, and an unrecognised one falls back to the show's title
     *  rather than 404ing — so this is a real per-track page, not decorative). Used to build
     *  a share link (#19); nothing else in the app reads it. */
    val slug: String? = null,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("liked_by_user") val likedByUser: Boolean = false,
    val position: Int = 0,
    /** milliseconds */
    val duration: Long = 0,
    @SerialName("set_name") val setName: String = "",
    @SerialName("audio_status") val audioStatus: String = "missing",
    @SerialName("mp3_url") val mp3Url: String? = null,
    @SerialName("waveform_image_url") val waveformImageUrl: String? = null,
    // Present on search results, /tracks and playlist entries; absent when nested in a show.
    @SerialName("show_date") val showDate: String? = null,
    @SerialName("venue_name") val venueName: String? = null,
    @SerialName("venue_location") val venueLocation: String? = null,
    @SerialName("show_album_cover_url") val showAlbumCoverUrl: String? = null,
) {
    val playable: Boolean get() = !mp3Url.isNullOrBlank() && audioStatus != "missing"
}

@Serializable
data class Playlist(
    val id: Long,
    val slug: String,
    val name: String,
    val description: String? = null,
    val username: String? = null,
    val duration: Long = 0,
    @SerialName("tracks_count") val tracksCount: Int = 0,
    @SerialName("likes_count") val likesCount: Int = 0,
    @SerialName("liked_by_user") val likedByUser: Boolean = false,
    /** Only populated by the single-playlist endpoint, never by the list endpoints. */
    val entries: List<PlaylistEntry> = emptyList(),
)

/**
 * A playlist entry can be an excerpt rather than a whole track — [startsAtSecond] and
 * [endsAtSecond] define the clip. Ignoring them would play the wrong audio.
 */
@Serializable
data class PlaylistEntry(
    val track: Track,
    val position: Int = 0,
    /** Effective length in ms — the clipped span, not the whole track. */
    val duration: Long = 0,
    @SerialName("starts_at_second") val startsAtSecond: Int? = null,
    @SerialName("ends_at_second") val endsAtSecond: Int? = null,
)

@Serializable
data class LoginResponse(val jwt: String, val username: String, val email: String)

@Serializable
data class User(val username: String, val email: String)

@Serializable
data class SearchResults(
    @SerialName("exact_show") val exactShow: Show? = null,
    @SerialName("other_shows") val otherShows: List<Show> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val shows: List<Show> get() = listOfNotNull(exactShow) + otherShows
    val isEmpty: Boolean get() = shows.isEmpty() && tracks.isEmpty() && playlists.isEmpty()
}

@Serializable
private data class ShowsPage(
    val shows: List<Show> = emptyList(),
    @SerialName("total_entries") val totalEntries: Int = 0,
)

@Serializable
private data class TracksPage(
    val tracks: List<Track> = emptyList(),
    @SerialName("total_entries") val totalEntries: Int = 0,
)

@Serializable
private data class PlaylistsPage(
    val playlists: List<Playlist> = emptyList(),
    @SerialName("total_entries") val totalEntries: Int = 0,
)

object PhishInApi {
    private val DEFAULT_BASE = "https://phish.in/api/v2".toHttpUrl()
    private val JSON_MEDIA = "application/json".toMediaType()

    /** Overridden by tests to point at a local mock server. */
    internal var baseUrl: HttpUrl = DEFAULT_BASE

    /**
     * The JWT from /auth/login, sent as `X-Auth-Token`.
     *
     * NOT `Authorization: Bearer` — that header carries API keys, a separate mechanism.
     * Both produce an identical 401, so getting this wrong fails silently-looking.
     */
    @Volatile var authToken: String? = null

    /**
     * Called when a request that carried a token is rejected. A JWT that has expired or
     * been revoked would otherwise leave the app looking signed in while every personal
     * screen shows an error.
     */
    @Volatile var onUnauthorized: (() -> Unit)? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory { TimingEventListener("PhishInApi") }
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private fun Request.Builder.withAuth() = apply {
        header("Accept", "application/json")
        authToken?.let { header("X-Auth-Token", it) }
    }

    private suspend fun send(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            // Only meaningful when we actually sent a token; a 401 from /auth/login is
            // just wrong credentials.
            if (resp.code == 401 && request.header("X-Auth-Token") != null) onUnauthorized?.invoke()
            if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}", resp.code)
            resp.body?.string() ?: throw ApiException("Empty response")
        }
    }

    private suspend fun get(url: HttpUrl): String =
        send(Request.Builder().url(url).withAuth().build())

    private suspend fun postJson(url: HttpUrl, body: Map<String, String>): String {
        val payload = JsonObject(body.mapValues { JsonPrimitive(it.value) }).toString()
        return send(
            Request.Builder().url(url).withAuth()
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
        )
    }

    private fun path(vararg segments: String) =
        baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }

    // ------------------------------------------------------------------ auth

    suspend fun login(email: String, password: String): LoginResponse =
        json.decodeFromString(
            postJson(path("auth", "login").build(), mapOf("email" to email, "password" to password))
        )

    suspend fun currentUser(): User =
        json.decodeFromString(get(path("auth", "user").build()))

    // ----------------------------------------------------------------- browse

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
        json.decodeFromString(get(path("shows", date).build()))

    /** The archive-wide popular/top-rated browse (#21) — sorted server-side by like count,
     *  not scoped to any one year, unlike [showsForPeriod]. */
    suspend fun popularShows(): List<Show> {
        val url = path("shows")
            .addQueryParameter("audio_status", "complete_or_partial")
            .addQueryParameter("sort", "likes_count:desc")
            .addQueryParameter("per_page", "100")
            .build()
        return json.decodeFromString<ShowsPage>(get(url)).shows
    }

    /** The API rejects terms shorter than 3 characters. */
    suspend fun search(term: String): SearchResults {
        val url = path("search", term)
            .addQueryParameter("audio_status", "complete_or_partial")
            .build()
        return json.decodeFromString<SearchResults>(get(url))
    }

    // -------------------------------------------------------------- playlists

    /**
     * [filter] is "mine" or "liked", both of which require auth. Unauthenticated, the API
     * ignores the filter and returns every public playlist rather than erroring — so
     * callers must not request a filter without a token.
     */
    suspend fun playlists(filter: String? = null, sort: String = "likes_count:desc"): List<Playlist> {
        val url = path("playlists")
            .addQueryParameter("sort", sort)
            .addQueryParameter("per_page", "100")
            .apply { filter?.let { addQueryParameter("filter", it) } }
            .build()
        return json.decodeFromString<PlaylistsPage>(get(url)).playlists
    }

    suspend fun playlist(slug: String): Playlist =
        json.decodeFromString(get(path("playlists", slug).build()))

    // ------------------------------------------------------------------ likes

    /** Both require auth; unauthenticated the API rejects them rather than silently no-op. */
    suspend fun like(type: Likable, id: Long) {
        val payload = JsonObject(
            mapOf(
                "likable_type" to JsonPrimitive(type.name),
                "likable_id" to JsonPrimitive(id),
            )
        ).toString()
        send(
            Request.Builder().url(path("likes").build()).withAuth()
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
        )
    }

    suspend fun unlike(type: Likable, id: Long) {
        val url = path("likes")
            .addQueryParameter("likable_type", type.name)
            .addQueryParameter("likable_id", id.toString())
            .build()
        send(Request.Builder().url(url).withAuth().delete().build())
    }

    // ----------------------------------------------------------- liked by user

    suspend fun likedShows(): List<Show> {
        val url = path("shows")
            .addQueryParameter("liked_by_user", "true")
            .addQueryParameter("audio_status", "complete_or_partial")
            .addQueryParameter("sort", "date:desc")
            .addQueryParameter("per_page", "1000")
            .build()
        return json.decodeFromString<ShowsPage>(get(url)).shows
    }

    suspend fun likedTracks(): List<Track> {
        val url = path("tracks")
            .addQueryParameter("liked_by_user", "true")
            .addQueryParameter("audio_status", "complete_or_partial")
            .addQueryParameter("per_page", "1000")
            .build()
        return json.decodeFromString<TracksPage>(get(url)).tracks
    }
}

class ApiException(message: String, val code: Int = 0) : Exception(message) {
    val unauthorized: Boolean get() = code == 401
}
