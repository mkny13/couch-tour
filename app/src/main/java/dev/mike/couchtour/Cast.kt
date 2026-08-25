package dev.mike.couchtour

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors
import com.google.android.gms.cast.MediaMetadata as CastMetadata

private const val TAG = "Casting"

/**
 * Google's stock media receiver. It plays plain progressive MP3 over HTTPS, which is
 * exactly what phish.in serves, so there's nothing to register or host.
 */
private val RECEIVER_APP_ID: String = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

/**
 * Read by the Cast framework, by name, from the manifest.
 *
 * The receiver's own media session and notification are switched off: this app already
 * publishes a `MediaSession` from [PlaybackService], and a second one would mean two sets
 * of lockscreen controls and every track scrobbled twice.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(RECEIVER_APP_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setMediaSessionEnabled(false)
                    .setNotificationOptions(null)
                    .build()
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> = emptyList()
}

/** The Cast connection, such as it is: which device we're on, and how to get off it. */
object Casting {

    private val _castContext = MutableStateFlow<CastContext?>(null)

    /** Null until the framework has initialised, and forever on a device without Cast. */
    val castContext: StateFlow<CastContext?> = _castContext.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)

    /** The friendly name of the device we're casting to, or null when playing locally. */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** Routes that can run our receiver — the filter the device picker discovers against. */
    val routeSelector: MediaRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(RECEIVER_APP_ID))
        .build()

    /**
     * Initialisation is deliberately quiet. A phone with no Play services, or an outdated
     * one, is a normal phone: Cast never becomes available, the button never appears, and
     * nothing else in the app is affected.
     */
    fun init(context: Context) {
        runCatching {
            CastContext.getSharedInstance(context, Executors.newSingleThreadExecutor())
                .addOnSuccessListener { ready(it) }
                .addOnFailureListener { Log.i(TAG, "Cast unavailable: ${it.message}") }
        }.onFailure { Log.i(TAG, "Cast unavailable: ${it.message}") }
    }

    private fun ready(context: CastContext) {
        _castContext.value = context
        // The app can be started while a session from a previous run is still up.
        _deviceName.value = context.sessionManager.currentCastSession?.castDevice?.friendlyName
        context.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    /** Ends the session and stops the receiver, rather than leaving it idling on the TV. */
    fun stop() {
        _castContext.value?.sessionManager?.endCurrentSession(true)
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        private fun connected(session: CastSession?) {
            _deviceName.value = session?.castDevice?.friendlyName
        }

        private fun disconnected() {
            _deviceName.value = null
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = connected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connected(session)
        override fun onSessionEnded(session: CastSession, error: Int) = disconnected()
        override fun onSessionSuspended(session: CastSession, reason: Int) = disconnected()
        override fun onSessionStartFailed(session: CastSession, error: Int) = disconnected()
        override fun onSessionResumeFailed(session: CastSession, error: Int) = disconnected()
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
    }
}

// ------------------------------------------------------------------ media items

/** Where the app's own fields ride across the Cast wire. */
private const val KEY_MEDIA_ID = "media_id"
private const val KEY_URI = "uri"
private const val KEY_ARTWORK = "artwork"

/**
 * Converts our queue items to Cast's and back.
 *
 * Media3's own converter carries only what a TV needs to display — title, artist, album,
 * artwork — and drops `MediaMetadata.extras`, which is where this app keeps the queue key,
 * the queue's own title, and the waveform. Losing the queue key means no progress row, and
 * remembering where you are is the whole point of the app.
 *
 * Media3 caches the items it sent and normally rebuilds its timeline from those, so the
 * loss only shows up on the paths where that cache is empty — a session resumed after the
 * app was killed, or a queue another sender started. Those are exactly the paths where the
 * extras matter most, so they go into the item's `customData`, which the receiver echoes
 * back untouched, and come home again on the other side.
 *
 * Not carried: [MediaItem.ClippingConfiguration]. A receiver plays whole files, so a
 * playlist excerpt casts as the full track — see DECISIONS.md.
 */
class CastItemConverter : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val local = checkNotNull(mediaItem.localConfiguration) { "A cast item needs a URI" }
        val meta = mediaItem.mediaMetadata

        // What the TV shows while the track plays.
        val castMeta = CastMetadata(CastMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            meta.title?.let { putString(CastMetadata.KEY_TITLE, it.toString()) }
            meta.artist?.let { putString(CastMetadata.KEY_ARTIST, it.toString()) }
            meta.albumTitle?.let { putString(CastMetadata.KEY_ALBUM_TITLE, it.toString()) }
            meta.subtitle?.let { putString(CastMetadata.KEY_SUBTITLE, it.toString()) }
            meta.artworkUri?.let { addImage(WebImage(it)) }
        }

        val extras = meta.extras
        val rawUrl = local.uri.toString()
        val mp3Fallback = extras?.getString(Keys.MP3_URL)
        val isFlac = local.mimeType == MimeTypes.AUDIO_FLAC || rawUrl.endsWith(".flac", ignoreCase = true)
        val castUrl = if (isFlac && !mp3Fallback.isNullOrBlank()) mp3Fallback else rawUrl

        // Same shape as media3's own converter: the track id identifies the queue item,
        // the URL is what gets fetched.
        val info = MediaInfo.Builder(mediaItem.mediaId.takeIf { it != MediaItem.DEFAULT_MEDIA_ID } ?: castUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(MimeTypes.AUDIO_MPEG)
            .setContentUrl(castUrl)
            .setMetadata(castMeta)
            .setCustomData(castCustomData(mediaItem))
            .build()

        return MediaQueueItem.Builder(info).build()
    }

    override fun toMediaItem(item: MediaQueueItem): MediaItem {
        val info = item.media ?: return MediaItem.EMPTY
        val custom = info.customData
        val castMeta = info.metadata

        val meta = MediaMetadata.Builder()
            .setTitle(castMeta?.getString(CastMetadata.KEY_TITLE))
            .setArtist(castMeta?.getString(CastMetadata.KEY_ARTIST))
            .setAlbumTitle(castMeta?.getString(CastMetadata.KEY_ALBUM_TITLE))
            .setSubtitle(castMeta?.getString(CastMetadata.KEY_SUBTITLE))
            .setArtworkUri(custom.optionalString(KEY_ARTWORK)?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(castExtras(custom))
            .build()

        val flacUrl = custom.optionalString(Keys.FLAC_URL)
        val uri = flacUrl ?: custom.optionalString(KEY_URI) ?: info.contentUrl ?: info.contentId
        val mimeType = if (flacUrl != null) MimeTypes.AUDIO_FLAC else (info.contentType ?: MimeTypes.AUDIO_MPEG)

        return MediaItem.Builder()
            .setMediaId(custom.optionalString(KEY_MEDIA_ID) ?: MediaItem.DEFAULT_MEDIA_ID)
            .setUri(uri)
            .setMimeType(mimeType)
            .setMediaMetadata(meta)
            .build()
    }
}

/** Packs the fields the app can't lose into the Cast item's custom data. */
internal fun castCustomData(mediaItem: MediaItem): JSONObject {
    val extras = mediaItem.mediaMetadata.extras
    return JSONObject().apply {
        put(KEY_MEDIA_ID, mediaItem.mediaId)
        mediaItem.localConfiguration?.uri?.let { put(KEY_URI, it.toString()) }
        mediaItem.mediaMetadata.artworkUri?.let { put(KEY_ARTWORK, it.toString()) }
        for (key in Keys.ALL) extras?.getString(key)?.let { put(key, it) }
    }
}

/** Unpacks what [castCustomData] wrote. Absent keys stay absent rather than becoming "". */
internal fun castExtras(custom: JSONObject?): Bundle = Bundle().apply {
    for (key in Keys.ALL) custom.optionalString(key)?.let { putString(key, it) }
}

private fun JSONObject?.optionalString(key: String): String? =
    this?.optString(key)?.takeIf { it.isNotEmpty() }
