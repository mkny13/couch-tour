package dev.mike.phishin

import android.app.Application
import android.content.ComponentName
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

    fun playShow(show: Show, startIndex: Int = 0, startPositionMs: Long = 0) {
        val c = controller ?: return
        val items = mediaItems(show)
        if (items.isEmpty()) return
        val index = startIndex.coerceIn(0, items.lastIndex)
        c.setMediaItems(items, index, startPositionMs)
        c.prepare()
        c.play()
    }

    /**
     * Resume a queue the user left earlier; fetches the show fresh from the API.
     * A finished show restarts from the top — its stored position is the last second of
     * the encore, so resuming there would stop again immediately.
     */
    fun resume(progress: Progress) {
        val date = progress.queueKey.removePrefix("show:")
        viewModelScope.launch {
            runCatching { PhishInApi.show(date) }.onSuccess {
                if (progress.finished) playShow(it, 0, 0)
                else playShow(it, progress.trackIndex, progress.positionMs)
            }
        }
    }

    /** Remove a show from "Continue listening" (or the archive) entirely. */
    fun forget(progress: Progress) {
        viewModelScope.launch { progressDao.clear(progress.queueKey) }
    }

    /**
     * Play a track found via search. Queues its whole show so the rest of the set follows,
     * rather than stranding the user on a single song.
     */
    fun playTrack(track: Track) {
        val date = track.showDate ?: return
        viewModelScope.launch {
            runCatching { PhishInApi.show(date) }.onSuccess { show ->
                val index = show.tracks.filter { it.playable }.indexOfFirst { it.id == track.id }
                playShow(show, index.coerceAtLeast(0), 0)
            }
        }
    }

    suspend fun progressFor(date: String): Progress? = progressDao.get(showQueueKey(date))

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

    private fun mediaItems(show: Show): List<MediaItem> {
        val subtitle = listOfNotNull(show.venueName, show.location).joinToString(" · ")
        val art = show.albumCoverUrl ?: show.coverArtUrls?.medium
        return show.tracks.filter { it.playable }.map { track ->
            // Per-track, not shared: the waveform differs for every track in the queue.
            val extras = Bundle().apply {
                putString(Keys.QUEUE_KEY, showQueueKey(show.date))
                putString(Keys.QUEUE_TITLE, show.date)
                putString(Keys.QUEUE_SUBTITLE, subtitle)
                putString(Keys.QUEUE_ART, art)
                putString(Keys.WAVEFORM, track.waveformImageUrl)
            }
            val meta = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist("Phish")
                .setAlbumTitle("${show.date} · $subtitle")
                .setArtworkUri(art?.let { android.net.Uri.parse(it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setExtras(extras)
                .build()
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(track.mp3Url)
                .setMediaMetadata(meta)
                .build()
        }
    }
}
