package dev.mike.couchtour

import android.app.Application
import android.content.ComponentName
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class PlayerState(
    val connected: Boolean = false,
    val hasQueue: Boolean = false,
    val isPlaying: Boolean = false,
    val trackTitle: String = "",
    /** The show the track was played at — "1995-12-29 · Worcester Centrum Centre". */
    val showTitle: String = "",
    val queueTitle: String = "",
    /** Null for ephemeral queues such as shuffle, which have nothing to navigate to. */
    val queueKey: String? = null,
    val artUrl: String? = null,
    val waveformUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Index into the current queue — what the source picker reads to carry a mid-track
     *  position across a source switch (#17). */
    val trackIndex: Int = 0,
    val trackId: String? = null,
    val backend: String? = null,
    val likedByUser: Boolean = false,
    val likesCount: Int = 0,
)

class PlayerViewModel(app: Application) : AndroidViewModel(app) {

    private var controller: MediaController? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    val progressDao = PhishInDb.get(app).progressDao()
    val localPlaylistDao = PhishInDb.get(app).localPlaylistDao()

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
        val item = c.currentMediaItem
        val meta = item?.mediaMetadata
        val queue = (meta?.subtitle ?: meta?.albumTitle)?.toString().orEmpty()
        val show = meta?.albumTitle?.toString().orEmpty()
        val extras = meta?.extras
        _state.value = PlayerState(
            connected = true,
            hasQueue = c.mediaItemCount > 0,
            isPlaying = c.isPlaying,
            trackTitle = meta?.title?.toString().orEmpty(),
            // Don't say the same thing twice. Two ways it can happen: a track with no show
            // of its own falls back to the queue label verbatim, and a show played from its
            // own page has a queue line that is the show line plus the city. A prefix test
            // catches both. In a playlist the two are unrelated, so both lines survive.
            showTitle = if (queue.startsWith(show)) "" else show,
            queueTitle = queue,
            queueKey = extras?.getString(Keys.QUEUE_KEY),
            artUrl = meta?.artworkUri?.toString(),
            waveformUrl = extras?.getString(Keys.WAVEFORM),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
            trackIndex = c.currentMediaItemIndex,
            trackId = extras?.getString(Keys.TRACK_ID) ?: item?.mediaId,
            backend = extras?.getString(Keys.BACKEND),
            likedByUser = extras?.getBoolean(Keys.LIKED, false) ?: false,
            likesCount = extras?.getInt(Keys.LIKES_COUNT, 0) ?: 0,
        )
    }

    // ------------------------------------------------------------------ play

    fun playShow(show: Show, startIndex: Int = 0, startPositionMs: Long = 0) {
        val playable = show.tracks.filter { it.playable }
        val filtered = filterPlaybackTracks(playable, startIndex, PlaybackSettings.skipFiller.value) { it.title }
        val subtitle = listOfNotNull(show.venueName, show.location).joinToString(" · ")
        val art = show.albumCoverUrl ?: show.coverArtUrls?.medium
        val info = QueueInfo(showQueueKey(show.date), show.date, subtitle, art)
        start(filtered.items.map { mediaItem(it, info) }, filtered.startIndex, startPositionMs)
    }

    fun playPlaylist(playlist: Playlist, startIndex: Int = 0, startPositionMs: Long = 0) {
        val playable = playlist.entries.filter { it.track.playable }
        val filtered = filterPlaybackTracks(playable, startIndex, PlaybackSettings.skipFiller.value) { it.track.title }
        val subtitle = listOfNotNull(
            playlist.username?.let { "by $it" },
            "${playable.size} tracks",
        ).joinToString(" · ")
        val info = QueueInfo(
            playlistQueueKey(playlist.slug),
            playlist.name,
            subtitle,
            playable.firstOrNull()?.track?.showAlbumCoverUrl,
        )
        start(filtered.items.map { mediaItem(it.track, info, it) }, filtered.startIndex, startPositionMs)
    }

    /** Play one tape of a Relisten show. [detail] already picked the recording (P3). */
    fun playRecording(detail: ShowDetail, startIndex: Int = 0, startPositionMs: Long = 0) {
        val filtered = filterPlaybackTracks(detail.tracks, startIndex, PlaybackSettings.skipFiller.value) { it.title }
        val summary = detail.summary
        val info = QueueInfo(
            key = detail.queueKey,
            title = summary.date,
            subtitle = summary.where,
            art = summary.artUrl ?: detail.tracks.firstOrNull()?.artUrl,
            artist = summary.artist.name,
        )
        start(filtered.items.map { recordingMediaItem(it, info) }, filtered.startIndex, startPositionMs)
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
        val c = controller ?: run {
            Log.w("PlayerViewModel", "start() called before MediaController connected; dropping the tap")
            return
        }
        if (items.isEmpty()) return
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), startPositionMs)
        c.prepare()
        c.play()
    }

    // ------------------------------------------------------- local playlists (#12, D161)

    suspend fun createLocalPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        localPlaylistDao.insertPlaylist(LocalPlaylistEntity(id, name, createdAt = now, updatedAt = now))
        return id
    }

    fun addToLocalPlaylist(playlistId: String, track: LocalPlaylistTrackEntity) {
        viewModelScope.launch {
            localPlaylistDao.addTrack(track.copy(playlistId = playlistId), System.currentTimeMillis())
        }
    }

    fun removeFromLocalPlaylist(rowId: Long, playlistId: String) {
        viewModelScope.launch { localPlaylistDao.removeTrack(rowId, playlistId, System.currentTimeMillis()) }
    }

    fun renameLocalPlaylist(id: String, name: String) {
        viewModelScope.launch { localPlaylistDao.renamePlaylist(id, name, System.currentTimeMillis()) }
    }

    fun reorderLocalPlaylistTracks(playlistId: String, orderedRowIds: List<Long>) {
        viewModelScope.launch { localPlaylistDao.reorderTracks(playlistId, orderedRowIds, System.currentTimeMillis()) }
    }

    fun moveLocalPlaylistTrack(playlistId: String, tracks: List<LocalPlaylistTrackEntity>, fromIndex: Int, toIndex: Int) {
        if (fromIndex !in tracks.indices || toIndex !in tracks.indices || fromIndex == toIndex) return
        val mutable = tracks.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        reorderLocalPlaylistTracks(playlistId, mutable.map { it.rowId })
    }

    fun deleteLocalPlaylist(id: String) {
        viewModelScope.launch { localPlaylistDao.deletePlaylist(id) }
    }

    fun playLocalPlaylist(playlistId: String, startIndex: Int = 0, startPositionMs: Long = 0) {
        viewModelScope.launch {
            runCatching {
                val resolved = resolveLocalPlaylistTracks(localPlaylistDao.tracksOnce(playlistId))
                val filtered = filterPlaybackTracks(resolved, startIndex, PlaybackSettings.skipFiller.value) { it.title }
                val name = localPlaylistDao.playlist(playlistId)?.name.orEmpty()
                localPlaylistTrackItems(playlistId, name, filtered.items) to filtered.startIndex
            }.onSuccess { (items, index) -> start(items, index, startPositionMs) }
        }
    }

    private suspend fun localPlaylistItems(playlistId: String): List<MediaItem> =
        localPlaylistQueueItems(localPlaylistDao, playlistId)

    /**
     * Resume a queue the user left earlier, re-fetching it from the API. Handles both
     * queue kinds; a finished queue restarts from the top, because its stored position is
     * the last second of the final track.
     */
    fun resume(progress: Progress) {
        val ref = parseQueueKey(progress.queueKey) ?: return
        val index = if (progress.finished) 0 else progress.trackIndex
        val position = if (progress.finished) 0L else progress.positionMs
        val startNanos = System.nanoTime()
        viewModelScope.launch {
            runCatching {
                when (ref.kind) {
                    QueueKind.PLAYLIST ->
                        playPlaylist(PhishInApi.playlist(ref.id), index, position)
                    QueueKind.SHOW ->
                        playShow(PhishInApi.show(ref.id), index, position)
                    QueueKind.RECORDING -> {
                        val rec = parseRecordingId(ref.id) ?: return@runCatching
                        // The real ArtistRef, not a name-only stand-in: hasSets controls
                        // whether toShowDetail suppresses the set-name divider, and getting
                        // it wrong here would only show up on resume, not on first play.
                        // progress.artist is the fallback if the artist list can't be
                        // fetched (e.g. offline) — degrades to showing that divider rather
                        // than failing to resume at all.
                        val artist = RelistenCatalogSource.artists().firstOrNull { it.id == rec.artistSlug }
                            ?: ArtistRef(Backend.RELISTEN, rec.artistSlug, progress.artist)
                        // The stored source id, not the current default tape — resuming
                        // must reopen the exact tape the position was recorded against.
                        playRecording(RelistenCatalogSource.show(artist, rec.date, rec.sourceId), index, position)
                    }
                    QueueKind.LOCAL_PLAYLIST -> start(localPlaylistItems(ref.id), index, position)
                }
            }.onSuccess {
                Log.d("PlayerViewModel", "resume(${ref.kind}) ready after ${(System.nanoTime() - startNanos) / 1_000_000}ms")
            }.onFailure { e ->
                Log.w("PlayerViewModel", "resume(${ref.kind}) failed after ${(System.nanoTime() - startNanos) / 1_000_000}ms: ${e.javaClass.simpleName}: ${e.message}")
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
        viewModelScope.launch { progressDao.clear(progress.queueKey, System.currentTimeMillis()) }
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
}
