package dev.mike.phishin

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlayerState(
    val connected: Boolean = false,
    val hasQueue: Boolean = false,
    val isPlaying: Boolean = false,
    val trackTitle: String = "",
    val queueTitle: String = "",
    val artUrl: String? = null,
    val waveformUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

fun showQueueKey(date: String) = "show:$date"
fun playlistQueueKey(slug: String) = "playlist:$slug"

/** Queue-level labelling, identical for every item in one show or playlist. */
private data class QueueInfo(
    val key: String,
    val title: String,
    val subtitle: String,
    val art: String?,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val progressDao = PhishInDb.get(app).progressDao()

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        future.addListener({
            val c = future.get()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh()
            })
            refresh()
        }, MoreExecutors.directExecutor())
    }

    /** Called on a UI tick so the scrubber advances between player events. */
    fun refresh() {
        val c = controller ?: return
        val meta = c.currentMediaItem?.mediaMetadata
        _state.value = PlayerState(
            connected = true,
            hasQueue = c.mediaItemCount > 0,
            isPlaying = c.isPlaying,
            trackTitle = meta?.title?.toString().orEmpty(),
            queueTitle = meta?.albumTitle?.toString().orEmpty(),
            artUrl = meta?.artworkUri?.toString(),
            waveformUrl = meta?.extras?.getString(Keys.WAVEFORM),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
        )
    }

    // ------------------------------------------------------------------ play

    fun playShow(show: Show, startIndex: Int = 0, startPositionMs: Long = 0) {
        val subtitle = listOfNotNull(show.venueName, show.location).joinToString(" · ")
        val art = show.albumCoverUrl ?: show.coverArtUrls?.medium
        val info = QueueInfo(showQueueKey(show.date), show.date, subtitle, art)
        start(show.tracks.filter { it.playable }.map { mediaItem(it, info) }, startIndex, startPositionMs)
    }

    fun playPlaylist(playlist: Playlist, startIndex: Int = 0, startPositionMs: Long = 0) {
        val entries = playlist.entries.filter { it.track.playable }
        val subtitle = listOfNotNull(
            playlist.username?.let { "by $it" },
            "${entries.size} tracks",
        ).joinToString(" · ")
        val info = QueueInfo(
            playlistQueueKey(playlist.slug),
            playlist.name,
            subtitle,
            entries.firstOrNull()?.track?.showAlbumCoverUrl,
        )
        start(entries.map { mediaItem(it.track, info, it) }, startIndex, startPositionMs)
    }

    /** Play a track found via search, queueing its whole show so the rest of the set follows. */
    fun playTrack(track: Track) {
        val date = track.showDate ?: return
        viewModelScope.launch {
            runCatching { PhishInApi.show(date) }.onSuccess { show ->
                val index = show.tracks.filter { it.playable }.indexOfFirst { it.id == track.id }
                playShow(show, index.coerceAtLeast(0), 0)
            }
        }
    }

    private fun start(items: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
        val c = controller ?: return
        if (items.isEmpty()) return
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs)
        c.prepare()
        c.play()
    }

    /**
     * Resume a queue the user left earlier, re-fetching it from the API. Handles both
     * queue kinds; a finished queue restarts from the top, because its stored position is
     * the last second of the final track.
     */
    fun resume(progress: Progress) {
        val key = progress.queueKey
        val index = if (progress.finished) 0 else progress.trackIndex
        val position = if (progress.finished) 0L else progress.positionMs
        viewModelScope.launch {
            runCatching {
                if (key.startsWith("playlist:")) {
                    playPlaylist(PhishInApi.playlist(key.removePrefix("playlist:")), index, position)
                } else {
                    playShow(PhishInApi.show(key.removePrefix("show:")), index, position)
                }
            }
        }
    }

    /** Remove a show or playlist from "Continue listening" (or the archive) entirely. */
    fun forget(progress: Progress) {
        viewModelScope.launch { progressDao.clear(progress.queueKey) }
    }

    suspend fun progressFor(key: String): Progress? = progressDao.get(key)

    // --------------------------------------------------------------- controls

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPreviousMediaItem()
    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
    }

    override fun onCleared() {
        controller?.release()
        controller = null
        super.onCleared()
    }

    private fun mediaItem(track: Track, info: QueueInfo, entry: PlaylistEntry? = null): MediaItem {
        // Per-track, not shared: waveform and cover differ for every track in a playlist.
        val art = track.showAlbumCoverUrl ?: info.art
        val extras = Bundle().apply {
            putString(Keys.QUEUE_KEY, info.key)
            putString(Keys.QUEUE_TITLE, info.title)
            putString(Keys.QUEUE_SUBTITLE, info.subtitle)
            putString(Keys.QUEUE_ART, info.art)
            putString(Keys.WAVEFORM, track.waveformImageUrl)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist("Phish")
            .setAlbumTitle("${info.title} · ${info.subtitle}")
            .setArtworkUri(art?.let { Uri.parse(it) })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(extras)
            .build()

        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.mp3Url)
            .setMediaMetadata(meta)
            .apply {
                // Playlist entries can be excerpts; without this they'd play the full track.
                if (entry != null && (entry.startsAtSecond != null || entry.endsAtSecond != null)) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs((entry.startsAtSecond ?: 0) * 1000L)
                            .apply { entry.endsAtSecond?.let { setEndPositionMs(it * 1000L) } }
                            .build()
                    )
                }
            }
            .build()
    }
}
