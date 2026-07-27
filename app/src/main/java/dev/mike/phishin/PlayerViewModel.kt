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

/**
 * Queue-level labelling, identical for every item in one show or playlist.
 *
 * A null [key] means the queue is ephemeral and its position is not recorded — the saver
 * skips any item without one. Used by shuffle, whose order can't be reconstructed.
 */
private data class QueueInfo(
    val key: String?,
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
            queueTitle = (meta?.subtitle ?: meta?.albumTitle)?.toString().orEmpty(),
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

    /**
     * Play a set of loose tracks in random order.
     *
     * Deliberately not resumable: the queue key is omitted so no progress row is written.
     * Recording a position would be a lie — resuming re-fetches and re-shuffles, so the
     * saved index would land on a different track than the one you left.
     */
    fun shuffle(tracks: List<Track>, title: String) {
        val playable = tracks.filter { it.playable }.shuffled()
        val info = QueueInfo(null, title, "${playable.size} tracks, shuffled", null)
        start(playable.map { mediaItem(it, info) }, 0, 0)
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
        val ref = parseQueueKey(progress.queueKey) ?: return
        val index = if (progress.finished) 0 else progress.trackIndex
        val position = if (progress.finished) 0L else progress.positionMs
        viewModelScope.launch {
            runCatching {
                when (ref.kind) {
                    QueueKind.PLAYLIST ->
                        playPlaylist(PhishInApi.playlist(ref.id), index, position)
                    QueueKind.SHOW ->
                        playShow(PhishInApi.show(ref.id), index, position)
                }
            }
        }
    }

    /**
     * Hide from "Continue listening" without losing it — it stays in history, and playing
     * it again brings it back.
     */
    fun dismiss(progress: Progress) {
        viewModelScope.launch { progressDao.dismiss(progress.queueKey) }
    }

    /** Erase from history entirely. */
    fun forget(progress: Progress) {
        viewModelScope.launch { progressDao.clear(progress.queueKey) }
    }

    /** Mark as played through without actually playing to the end. */
    fun markCompleted(progress: Progress) {
        viewModelScope.launch { progressDao.markFinished(progress.queueKey) }
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

    /** "1997-11-17 · McNichols Arena", falling back to the queue when a track lacks a show. */
    private fun albumFor(track: Track, info: QueueInfo): String {
        val fromTrack = listOfNotNull(track.showDate, track.venueName).joinToString(" · ")
        return fromTrack.ifBlank { "${info.title} · ${info.subtitle}" }
    }

    private fun mediaItem(track: Track, info: QueueInfo, entry: PlaylistEntry? = null): MediaItem {
        // Per-track, not shared: waveform and cover differ for every track in a playlist.
        val art = track.showAlbumCoverUrl ?: info.art
        val extras = Bundle().apply {
            info.key?.let { putString(Keys.QUEUE_KEY, it) }
            putString(Keys.QUEUE_TITLE, info.title)
            putString(Keys.QUEUE_SUBTITLE, info.subtitle)
            putString(Keys.QUEUE_ART, info.art)
            putString(Keys.WAVEFORM, track.waveformImageUrl)
        }
        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist("Phish")
            // The album is the show the track was played at, not the queue it arrived in.
            // External scrobblers (the Last.fm app reads our MediaSession directly) take
            // this field verbatim, and "some playlist · by someone · 99 tracks" is not an
            // album. Every track carries its own show, including inside a playlist.
            .setAlbumTitle(albumFor(track, info))
            // Queue identity lives here instead, so the mini player still shows the
            // playlist you started from rather than the underlying show.
            .setSubtitle("${info.title} · ${info.subtitle}")
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
