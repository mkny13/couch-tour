package dev.mike.phishin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Last.fm's rules for when a play counts, from their scrobbling guidelines: the track must
 * be longer than 30 seconds, and it counts once it has been played for half its length or
 * four minutes, whichever comes first.
 *
 * Pure so the timing can be tested without a player.
 */
object ScrobblePolicy {
    const val MIN_TRACK_MS = 30_000L
    const val ALWAYS_AFTER_MS = 240_000L

    fun eligible(durationMs: Long): Boolean = durationMs > MIN_TRACK_MS

    fun shouldScrobble(playedMs: Long, durationMs: Long): Boolean {
        if (!eligible(durationMs)) return false
        return playedMs >= minOf(durationMs / 2, ALWAYS_AFTER_MS)
    }
}

/** Signs Last.fm calls. Params are sorted by name, concatenated, and md5'd with the secret. */
internal fun signature(params: Map<String, String>, secret: String): String {
    val joined = params.toSortedMap().entries.joinToString("") { "${it.key}${it.value}" } + secret
    return MessageDigest.getInstance("MD5")
        .digest(joined.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class LastFmException(message: String) : Exception(message)

object LastFmApi {
    private val ENDPOINT = "https://ws.audioscrobbler.com/2.0/".toHttpUrl()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Overridden by tests. */
    internal var endpoint: HttpUrl = ENDPOINT
    internal var apiKey: String = BuildConfig.LASTFM_KEY
    internal var apiSecret: String = BuildConfig.LASTFM_SECRET

    val configured: Boolean get() = apiKey.isNotBlank() && apiSecret.isNotBlank()

    private suspend fun call(method: String, params: Map<String, String>, post: Boolean): String =
        withContext(Dispatchers.IO) {
            val signed = params + ("method" to method) + ("api_key" to apiKey)
            val body = signed + ("api_sig" to signature(signed, apiSecret))

            val request = if (post) {
                val form = FormBody.Builder().apply {
                    body.forEach { (k, v) -> add(k, v) }
                    add("format", "json")
                }.build()
                Request.Builder().url(endpoint).post(form).build()
            } else {
                val url = endpoint.newBuilder().apply {
                    body.forEach { (k, v) -> addQueryParameter(k, v) }
                    addQueryParameter("format", "json")
                }.build()
                Request.Builder().url(url).get().build()
            }

            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                // Last.fm reports failures in the body, sometimes with a 200.
                val error = runCatching {
                    json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.content
                }.getOrNull()
                if (error != null) throw LastFmException(error)
                if (!resp.isSuccessful) throw LastFmException("HTTP ${resp.code}")
                text
            }
        }

    private fun String.field(vararg path: String): String? {
        var element = runCatching { json.parseToJsonElement(this) }.getOrNull() ?: return null
        for (key in path) element = element.jsonObject[key] ?: return null
        return runCatching { element.jsonPrimitive.content }.getOrNull()
    }

    /** Step 1 of browser auth: a request token the user then approves in a browser. */
    suspend fun requestToken(): String =
        call("auth.getToken", emptyMap(), post = false).field("token")
            ?: throw LastFmException("No token in response")

    fun authorizeUrl(token: String): String =
        "https://www.last.fm/api/auth/?api_key=$apiKey&token=$token"

    /** Step 2, after the user approves: exchange the token for a lasting session key. */
    suspend fun session(token: String): Pair<String, String> {
        val body = call("auth.getSession", mapOf("token" to token), post = false)
        val key = body.field("session", "key") ?: throw LastFmException("No session key")
        val name = body.field("session", "name").orEmpty()
        return key to name
    }

    suspend fun updateNowPlaying(sessionKey: String, scrobble: Scrobble) {
        call(
            "track.updateNowPlaying",
            buildMap {
                put("artist", scrobble.artist)
                put("track", scrobble.track)
                if (scrobble.album.isNotBlank()) put("album", scrobble.album)
                if (scrobble.durationSec > 0) put("duration", scrobble.durationSec.toString())
                put("sk", sessionKey)
            },
            post = true,
        )
    }

    suspend fun scrobble(sessionKey: String, scrobble: Scrobble) {
        call(
            "track.scrobble",
            buildMap {
                put("artist", scrobble.artist)
                put("track", scrobble.track)
                if (scrobble.album.isNotBlank()) put("album", scrobble.album)
                if (scrobble.durationSec > 0) put("duration", scrobble.durationSec.toString())
                put("timestamp", scrobble.timestampSec.toString())
                put("sk", sessionKey)
            },
            post = true,
        )
    }
}

/** One play, ready to submit. */
data class Scrobble(
    val artist: String,
    val track: String,
    val album: String,
    val durationSec: Int,
    /** Unix seconds at which the track *started*, which is what Last.fm wants. */
    val timestampSec: Long,
)
