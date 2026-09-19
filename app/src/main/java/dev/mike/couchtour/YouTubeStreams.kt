package dev.mike.couchtour

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

/**
 * The seam between browse metadata ([YouTubeApi] — YouTube Data API v3, which never returns
 * playable URLs) and the direct audio/video stream URLs #234 plays. Kept small so tests can
 * inject a fake — real resolution is NewPipeExtractor's, which talks to YouTube itself and
 * breaks whenever YouTube changes its internals, exactly the kind of dependency that must
 * never reach a unit test.
 */
interface YouTubeStreamResolver {
    suspend fun resolve(videoId: String): ResolvedStreams
}

/** One stream URL was missing after resolution — there is nothing for #234 to play. */
class StreamResolutionException(message: String) : Exception(message)

/**
 * NewPipeExtractor-backed resolution (the planning note's recommended choice: no API key,
 * widely used by Android YouTube clients). Picks the highest-bitrate audio stream and the
 * highest-resolution muxed (video+audio) stream, so an audio/video toggle never has to
 * merge separate tracks — the same stream shapes #234's Media3 player will consume.
 */
object NewPipeStreamResolver : YouTubeStreamResolver {

    /**
     * NewPipe wants a process-wide init with a Downloader; ours reuses the app's OkHttp
     * (see build.gradle.kts for why NewPipeExtractor's transitive 5.x copy is excluded).
     * `lazy` keeps that init off paths that never resolve — every unit test, for instance.
     */
    private val initOnce: Any by lazy {
        NewPipe.init(YouTubeDownloader(), Localization.fromLocale(Locale.US))
    }

    override suspend fun resolve(videoId: String): ResolvedStreams = withContext(Dispatchers.IO) {
        initOnce
        val info = try {
            StreamInfo_getInfo(videoId)
        } catch (e: Exception) {
            throw StreamResolutionException("Failed to resolve streams for $videoId: ${e.message}")
        }

        // NewPipe sorts no stream lists for us; pick by bitrate/resolution ourselves.
        // Resolution strings ("720p60") need digit extraction — formats vary.
        val audio = info.audioStreams.maxByOrNull { it.averageBitrate }
        val video = info.videoStreams.maxByOrNull {
            it.resolution.filter(Char::isDigit).toIntOrNull() ?: 0
        }

        when {
            audio == null -> throw StreamResolutionException("No audio stream for $videoId")
            video == null -> throw StreamResolutionException("No video stream for $videoId")
        }
        ResolvedStreams(
            audioStreamUrl = audio.content,
            videoStreamUrl = video.content,
            durationMs = info.duration * 1000,
        )
    }

    /**
     * Indirection so the YouTube-only import stays inside this one function — keeps the
     * rest of the file readable about which NewPipe API does what.
     */
    private fun StreamInfo_getInfo(videoId: String) =
        org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(
            ServiceList.YouTube,
            "https://www.youtube.com/watch?v=$videoId",
        )
}

/**
 * Bridges NewPipeExtractor's Downloader to the app's OkHttp. NewPipe makes all its network
 * calls through this injected interface, so no internal HTTP client of its own exists to
 * conflict with ours.
 */
class YouTubeDownloader(private val http: OkHttpClient = OkHttpClient()) : Downloader() {
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())
        request.headers().forEach { (name, values) -> values.forEach { builder.header(name, it) } }
        val data = request.dataToSend()
        if (data != null) {
            builder.method(request.httpMethod(), data.toRequestBody())
        } else {
            builder.get()
        }

        http.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string()
            // NewPipe v0.26's Response carries a trailing latestUrl (the URL after any
            // redirects) so extractors can resolve relative links.
            return Response(resp.code, resp.message, resp.headers.toMultimap(), body, resp.request.url.toString())
        }
    }
}
