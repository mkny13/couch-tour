package dev.mike.couchtour

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

// YouTube Data API v3 client, mirroring the macOS YouTubeAPI.swift shape from #229: a
// stateless object with a test-overridable `baseUrl`, DTOs that decode defensively, and
// only `search.list` implemented — Part 1 (#232) fetches a channel's video list. Duration
// and playable URLs come from a separate stream-resolution step (#232's NewPipeExtractor
// resolver), which is why YouTubeVideo.durationMs and its stream URLs stay null here.

@Serializable
data class YouTubeSearchResponse(val items: List<YouTubeVideoDto> = emptyList())

/**
 * A flattened `search.list` item: `id.videoId` plus the `snippet` fields the catalog needs.
 * Every field has a safe default so a trimmed or extended real-world payload never throws —
 * the same style as [Show]/[Track] in Api.kt.
 */
@Serializable
data class YouTubeVideoDto(
    val id: YouTubeIdDto = YouTubeIdDto(),
    val snippet: YouTubeSnippetDto? = null,
)

@Serializable
data class YouTubeIdDto(val videoId: String = "")

@Serializable
data class YouTubeSnippetDto(
    val title: String = "",
    val description: String? = null,
    @SerialName("publishedAt") val publishedAt: String? = null,
    @SerialName("channelId") val channelId: String? = null,
    val thumbnails: YouTubeThumbnailsDto? = null,
)

@Serializable
data class YouTubeThumbnailsDto(
    val high: YouTubeThumbnailDto? = null,
    val medium: YouTubeThumbnailDto? = null,
    @SerialName("default") val default_: YouTubeThumbnailDto? = null,
)

@Serializable
data class YouTubeThumbnailDto(val url: String? = null)

/**
 * `search.list`'s `publishedAt` is ISO-8601; null (rather than a throw) on anything
 * unparseable, so one malformed timestamp can't fail a whole channel listing.
 */
private fun publishedAtMs(iso: String?): Long? = iso?.let {
    try {
        OffsetDateTime.parse(it).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

/**
 * The `channelId` parameter is the seam Part 2 (#233) resolves through: the artist→channel
 * mapping happens upstream of this mapping, falling back to the snippet's own.
 */
internal fun YouTubeVideoDto.toYouTubeVideo(channelIdOverride: String? = null): YouTubeVideo {
    val snippet = snippet ?: return YouTubeVideo(
        id = id.videoId,
        title = "",
        channelId = channelIdOverride ?: "",
    )
    // Prefer the largest size present; search.list omits sizes unpredictably.
    val thumbnailUrl = snippet.thumbnails?.let { it.high?.url ?: it.medium?.url ?: it.default_?.url }
    return YouTubeVideo(
        id = id.videoId,
        title = snippet.title,
        channelId = channelIdOverride ?: snippet.channelId ?: "",
        thumbnailUrl = thumbnailUrl,
        description = snippet.description,
        publishedAtMs = publishedAtMs(snippet.publishedAt),
    )
}

object YouTubeApi {
    private val DEFAULT_BASE = "https://www.googleapis.com/youtube/v3".toHttpUrl()
    private val JSON_MEDIA = "application/json".toMediaType()

    /** Overridden by tests to point at a local mock server. */
    internal var baseUrl: HttpUrl = DEFAULT_BASE

    /**
     * Owner-supplied credential (D44/D251 precedent — the builder does not register a
     * Google Cloud project). Sent as `?key=` only when non-nil; the app target wires the
     * real key, and the browse surface hides when it is unset.
     */
    @Volatile var apiKey: String? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .eventListenerFactory { TimingEventListener("YouTubeApi") }
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", JSON_MEDIA.toString()).build())
            .execute().use { resp ->
                if (!resp.isSuccessful) throw ApiException("HTTP ${resp.code}", resp.code)
                resp.body?.string() ?: throw ApiException("Empty response")
            }
    }

    /**
     * A channel's videos, newest first. Pagination is deliberately left off: 50 newest is
     * plenty for an artist-page section, and every extra page burns the API key's quota.
     */
    suspend fun search(channelId: String): List<YouTubeVideo> {
        val url = baseUrl.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("part", "snippet")
            .addQueryParameter("channelId", channelId)
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "50")
            .addQueryParameter("order", "date")
            .apply { apiKey?.let { addQueryParameter("key", it) } }
            .build()
        return json.decodeFromString<YouTubeSearchResponse>(get(url)).items
            .map { it.toYouTubeVideo() }
    }
}
