package dev.mike.couchtour

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keys stuffed into MediaMetadata.extras so the service can attribute a track to its queue. */
object Keys {
    const val QUEUE_KEY = "queue_key"
    const val QUEUE_TITLE = "queue_title"
    const val QUEUE_SUBTITLE = "queue_subtitle"
    const val QUEUE_ART = "queue_art"
    const val WAVEFORM = "waveform"

    /** Cast has to be told what to carry across the wire; nothing else enumerates these. */
    val ALL = listOf(QUEUE_KEY, QUEUE_TITLE, QUEUE_SUBTITLE, QUEUE_ART, WAVEFORM)
}

/**
 * Everything needed to pick playback up on another player: the queue, where in it we are,
 * and whether it was running. Captured continuously, because a cast session that drops has
 * already thrown its queue away by the time we're told about it.
 */
private data class Handoff(
    val items: List<MediaItem>,
    val index: Int,
    val positionMs: Long,
    val playWhenReady: Boolean,
)

/**
 * Foreground media service. This is the piece a WebView can't provide: it owns the
 * MediaSession that Android surfaces on the lockscreen, in the notification shade, and
 * to Bluetooth / headset buttons — and, as a [MediaLibraryService], the browse tree Android
 * Auto uses to show years, shows and tracks on a head unit.
 *
 * It owns both players — the local ExoPlayer and, once Cast is available, a RemoteCastPlayer
 * — and hands the session whichever one is live. Everything above it (the UI's
 * MediaController, the progress writer) is deliberately unaware of which.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private var localPlayer: ExoPlayer? = null
    private var castPlayer: RemoteCastPlayer? = null
    private var handoff: Handoff? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // -------------------------------------------------------------- audio focus / ducking
    // Cast doesn't touch phone audio, so focus is only ever requested for localPlayer.

    private var audioFocusRequest: AudioFocusRequest? = null

    /** Set only when we paused *because* of a transient loss, so regaining focus resumes. */
    private var pausedByAudioFocusLoss = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val player = localPlayer ?: return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                player.volume = 1f
                if (pausedByAudioFocusLoss) {
                    pausedByAudioFocusLoss = false
                    player.play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                pausedByAudioFocusLoss = false
                player.pause()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (player.isPlaying) pausedByAudioFocusLoss = true
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player.volume = 0.2f
        }
    }

    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
        if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = request
        }
    }

    private fun abandonAudioFocus() {
        val request = audioFocusRequest ?: return
        getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
        audioFocusRequest = null
        pausedByAudioFocusLoss = false
        localPlayer?.volume = 1f
    }

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                // Handled by hand below instead: Media3's own AudioFocusManager marks
                // MUSIC-content requests as willPauseWhenDucked = false, which tells the
                // platform it may duck us silently at the mixer on API 26+ — and having
                // said that, the platform never delivers AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                // to us at all, so ExoPlayer's own duck-to-20% code path never runs. Mixer
                // ducking is real but device/OEM-dependent and was inaudible on at least one
                // device (#23); requesting focus ourselves makes the duck deterministic.
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        localPlayer = player

        // Tapping the notification opens the app, which then navigates to whatever is
        // playing at that moment. The PendingIntent is built once and the queue changes as
        // playback moves, so the destination is resolved on arrival rather than baked in.
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        session = MediaLibrarySession.Builder(this, player, LibrarySessionCallback())
            .setSessionActivity(openApp)
            .build()

        // Save on the events that matter immediately. The same listener goes on the cast
        // player too, so casting scrobbles and records progress exactly like local playback.
        player.addListener(playerListener)

        // ...and on a slow tick while playing, so a crash or swipe-away loses at most 5s.
        scope.launch {
            while (true) {
                delay(5_000)
                val active = session?.player ?: continue
                if (active.isPlaying) {
                    saveNow()
                }
            }
        }

        // Cast initialises off the main thread and may never arrive at all, so the player
        // is attached whenever it turns up rather than waited for.
        scope.launch {
            Casting.castContext.filterNotNull().first()
            attachCast()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveNow()
            SyncSession.requestDebouncedPush(progressDao())
            if (session?.player === localPlayer) {
                if (isPlaying) requestAudioFocus() else if (!pausedByAudioFocusLoss) abandonAudioFocus()
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            saveNow()
            SyncSession.requestDebouncedPush(progressDao())
        }

        override fun onPlaybackStateChanged(state: Int) {
            saveNow()
            SyncSession.requestDebouncedPush(progressDao())
        }
    }

    // -------------------------------------------------------------------- cast

    private fun attachCast() {
        // Only looks up the CastContext the app has already initialised — which is why this
        // waits for that first. The context it needs is this service, for the DeviceInfo
        // that names the TV in the system volume panel.
        val cast = RemoteCastPlayer.Builder(this)
            .setMediaItemConverter(CastItemConverter())
            .build()
        cast.addListener(playerListener)
        cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchTo(cast)
            override fun onCastSessionUnavailable() {
                localPlayer?.let { switchTo(it) }
            }
        })
        castPlayer = cast
        // The service can be created while a cast session is already running — the app was
        // killed and relaunched, or the session was resumed by the framework.
        if (cast.isCastSessionAvailable) switchTo(cast)
    }

    /**
     * Move playback between the phone and the TV.
     *
     * Note what this deliberately does *not* do: it never clears the outgoing player's
     * queue. Clearing empties the timeline, which puts the player in `STATE_ENDED`, which
     * the progress writer reads as "played through to the encore" (D20) — a show would mark
     * itself finished every time you cast it. Stopping leaves the queue alone and lands in
     * `STATE_IDLE` instead.
     */
    private fun switchTo(next: Player) {
        val session = session ?: return
        val current = session.player
        if (current === next) return

        // Where the outgoing player actually is. A cast player that has just lost its
        // session still reports its last queue and position — but not when the receiver
        // cleared the queue first, which is what happens when someone else takes the TV.
        // The last tick's copy covers that case.
        val from = snapshot(current) ?: handoff
        (current as? ExoPlayer)?.stop()

        // Loaded before the session is handed over, so controllers and the notification
        // see the queue arrive rather than blinking empty in between.
        if (from != null && from.items.isNotEmpty()) {
            next.setMediaItems(from.items, from.index, from.positionMs)
            next.prepare()
            // Casting picks up where the phone left off, playing. Coming back the other way
            // it waits: the cast session usually ends because someone else took the TV, and
            // the phone suddenly playing out loud in that room is not what anyone wanted.
            next.playWhenReady = from.playWhenReady && next !== localPlayer
        }
        session.setPlayer(next)
        // Kept, not replaced: a cast player reports an empty queue until the receiver has
        // loaded it, and overwriting the fallback with that would lose the queue outright
        // if the session then failed.
        snapshot(next)?.let { handoff = it }
    }

    private fun snapshot(player: Player): Handoff? {
        val count = player.mediaItemCount
        if (count == 0) return null
        return Handoff(
            items = (0 until count).map { player.getMediaItemAt(it) },
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            playWhenReady = player.playWhenReady,
        )
    }

    private fun saveNow() {
        val player = session?.player ?: return
        snapshot(player)?.let { handoff = it }
        val item = player.currentMediaItem ?: return
        val extras = item.mediaMetadata.extras ?: return
        val key = extras.getString(Keys.QUEUE_KEY) ?: return

        val progress = Progress(
            queueKey = key,
            title = extras.getString(Keys.QUEUE_TITLE).orEmpty(),
            subtitle = extras.getString(Keys.QUEUE_SUBTITLE).orEmpty(),
            artUrl = extras.getString(Keys.QUEUE_ART),
            trackIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition.coerceAtLeast(0),
            trackTitle = item.mediaMetadata.title?.toString().orEmpty(),
            updatedAt = System.currentTimeMillis(),
            // Derived, never passed in: STATE_ENDED means the queue ran out, i.e. the show
            // played through its encore. Deriving it here means the several listeners that
            // all fire at the end of a queue can't race to clobber each other's value, and
            // any later play of the same show clears the flag on its own.
            finished = player.playbackState == Player.STATE_ENDED,
            // Read off the metadata rather than the extras: the item already publishes an
            // artist for external scrobblers to read (D50), so there is nothing to add and
            // no second copy to keep in step.
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
        )
        scope.launch { PhishInDb.get(applicationContext).progressDao().put(progress) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    // -------------------------------------------------------------------- browse tree

    /**
     * Android Auto's MediaBrowser calls in from a Binder thread and expects a
     * ListenableFuture back, not a suspend function — this bridges the two so the browse
     * tree can be built with the same suspending [PhishInApi] calls the rest of the app uses.
     */
    private fun <T : Any> CoroutineScope.futureOf(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        launch {
            try {
                future.set(block())
            } catch (t: Throwable) {
                future.setException(t)
            }
        }
        return future
    }

    private inner class LibrarySessionCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val node = BrowseNode.parse(parentId)
            return scope.futureOf {
                // A network hiccup in the car shouldn't crash the browse service — an empty
                // folder is a safe, recoverable result; a dead Binder call is not.
                val children = node?.let { runCatching { childrenFor(it) }.getOrDefault(emptyList()) }
                    ?: emptyList()
                LibraryResult.ofItemList(children.drop(page * pageSize).take(pageSize), params)
            }
        }
    }

    private fun browsableItem(
        id: String,
        title: String,
        subtitle: String? = null,
        artUri: String? = null,
    ): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .apply { subtitle?.let { setSubtitle(it) } }
            .apply { artUri?.let { setArtworkUri(Uri.parse(it)) } }
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .build()
        return MediaItem.Builder().setMediaId(id).setMediaMetadata(meta).build()
    }

    private fun rootItem(): MediaItem = browsableItem(BrowseNode.Root.id, "Couch Tour")

    private suspend fun childrenFor(node: BrowseNode): List<MediaItem> = when (node) {
        BrowseNode.Root -> rootChildren()
        BrowseNode.Continue -> continueChildren()
        BrowseNode.Years -> yearsChildren()
        BrowseNode.Artists -> artistsChildren()
        is BrowseNode.Year -> yearChildren(node.period)
        is BrowseNode.Tour -> tourChildren(node.period, node.name)
        is BrowseNode.ShowNode -> showTrackItems(PhishInApi.show(node.date))
        is BrowseNode.Artist -> artistPeriodsChildren(node)
        is BrowseNode.ArtistPeriod -> artistShowsChildren(node)
        is BrowseNode.Recording -> recordingChildren(node)
        is BrowseNode.Resume -> resumeChildren(node.queueKey)
    }

    private suspend fun rootChildren(): List<MediaItem> {
        val hasProgress = progressDao().inProgress().first().isNotEmpty()
        return buildList {
            if (hasProgress) add(browsableItem(BrowseNode.Continue.id, "Continue Listening"))
            // Above Years, per the plan: Relisten carries far more artists than phish.in has
            // years, so it's the entry point most trips down this tree will actually want.
            add(browsableItem(BrowseNode.Artists.id, "Artists"))
            add(browsableItem(BrowseNode.Years.id, "Years"))
        }
    }

    private suspend fun artistsChildren(): List<MediaItem> =
        RelistenCatalogSource.artists().sortedByDescending { it.showCount }.map { artist ->
            browsableItem(
                id = BrowseNode.Artist(artist.backend.id, artist.id).id,
                title = artist.name,
                subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
            )
        }

    private suspend fun artistPeriodsChildren(node: BrowseNode.Artist): List<MediaItem> {
        val backend = Backend.from(node.backend) ?: return emptyList()
        val source = sourceFor(backend)
        val artist = source.artists().firstOrNull { it.id == node.artistId } ?: return emptyList()
        return source.periods(artist).sortedByDescending { it.label }.map { period ->
            browsableItem(
                id = BrowseNode.ArtistPeriod(node.backend, node.artistId, period.id).id,
                title = period.label,
                // periods() is the same seam yearsChildren's own "Popular" entry rides
                // (D158) — this generic path doesn't special-case it today (Auto's own
                // "Artists" root never lists Phish, per artistsChildren below), but a period
                // with a real showCount of 0 must not read the same as one with none.
                subtitle = if (period.id == POPULAR_PERIOD_ID) POPULAR_PERIOD_SUBTITLE
                    else "${period.showCount} ${plural(period.showCount, "show")}",
                artUri = period.artUrl,
            )
        }
    }

    private suspend fun artistShowsChildren(node: BrowseNode.ArtistPeriod): List<MediaItem> {
        val backend = Backend.from(node.backend) ?: return emptyList()
        val source = sourceFor(backend)
        val artist = source.artists().firstOrNull { it.id == node.artistId } ?: return emptyList()
        val period = source.periods(artist).firstOrNull { it.id == node.periodId } ?: return emptyList()
        return source.shows(artist, period).map { show ->
            browsableItem(
                id = BrowseNode.Recording(node.backend, node.artistId, show.date).id,
                title = show.date,
                subtitle = show.where,
                artUri = show.artUrl,
            )
        }
    }

    /** Always the default tape (P3) — no tape switcher on a head unit, same scope cut as O3. */
    private suspend fun recordingChildren(node: BrowseNode.Recording): List<MediaItem> {
        val backend = Backend.from(node.backend) ?: return emptyList()
        val source = sourceFor(backend)
        val artist = source.artists().firstOrNull { it.id == node.artistId } ?: return emptyList()
        return recordingTrackItems(source.show(artist, node.date))
    }

    private suspend fun continueChildren(): List<MediaItem> =
        progressDao().inProgress().first().map { progress ->
            browsableItem(
                id = BrowseNode.Resume(progress.queueKey).id,
                title = progress.title,
                subtitle = listOfNotNull(progress.subtitle, progress.trackTitle)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                artUri = progress.artUrl,
            )
        }

    private suspend fun yearsChildren(): List<MediaItem> {
        val popular = browsableItem(
            id = BrowseNode.Year(POPULAR_PERIOD_ID).id,
            title = POPULAR_PERIOD_LABEL,
            subtitle = POPULAR_PERIOD_SUBTITLE,
        )
        val years = PhishInApi.years().map { period ->
            browsableItem(
                id = BrowseNode.Year(period.period).id,
                title = period.period,
                subtitle = "${period.showsWithAudioCount} shows",
                artUri = period.coverArtUrls?.medium,
            )
        }
        return listOf(popular) + years
    }

    /**
     * Most periods are one continuous run with a single (or no) tour name, so the year opens
     * straight onto its shows. Early, multi-tour years — several of the ranged periods like
     * "1983-1987" cover more than one — get an extra layer of tour nodes instead.
     *
     * [POPULAR_PERIOD_ID] (#21) short-circuits straight to a flat show list: its shows span
     * every year, so grouping by tour name the way a real year does would produce a folder
     * per show instead of a handful of tours.
     */
    private suspend fun yearChildren(period: String): List<MediaItem> {
        if (period == POPULAR_PERIOD_ID) return PhishInApi.popularShows().map { showItem(it) }
        val shows = PhishInApi.showsForPeriod(period)
        val tourNames = shows.mapNotNull { it.tourName }.distinct()
        if (tourNames.size <= 1) return shows.map { showItem(it) }
        return tourNames.map { name ->
            val tourShows = shows.filter { it.tourName == name }
            browsableItem(
                id = BrowseNode.Tour(period, name).id,
                title = name,
                subtitle = "${tourShows.size} shows",
                artUri = tourShows.firstOrNull { it.albumCoverUrl != null }?.albumCoverUrl,
            )
        }
    }

    private suspend fun tourChildren(period: String, name: String): List<MediaItem> =
        PhishInApi.showsForPeriod(period).filter { it.tourName == name }.map { showItem(it) }

    private fun showItem(show: Show): MediaItem = browsableItem(
        id = BrowseNode.ShowNode(show.date).id,
        title = show.date,
        subtitle = listOfNotNull(show.venueName, show.location).joinToString(" · "),
        artUri = show.albumCoverUrl ?: show.coverArtUrls?.medium,
    )

    /**
     * Picks up a queue exactly where "Continue listening" left off. There's no per-track
     * position here — Auto plays from the top of whichever track you tap — but landing on
     * the right track instead of the first one is most of what resuming means.
     */
    private suspend fun resumeChildren(queueKey: String): List<MediaItem> {
        val ref = parseQueueKey(queueKey) ?: return emptyList()
        val progress = progressDao().get(queueKey)
        val all = when (ref.kind) {
            QueueKind.SHOW -> showTrackItems(PhishInApi.show(ref.id))
            QueueKind.PLAYLIST -> playlistTrackItems(PhishInApi.playlist(ref.id))
            QueueKind.RECORDING -> {
                val rec = parseRecordingId(ref.id)
                if (rec == null) emptyList() else {
                    // Same fallback resume() uses on the phone: the cached artist list is the
                    // real ArtistRef (hasSets matters for the set-name divider), and the
                    // denormalised progress.artist covers it if that lookup fails offline.
                    val artist = RelistenCatalogSource.artists().firstOrNull { it.id == rec.artistSlug }
                        ?: ArtistRef(Backend.RELISTEN, rec.artistSlug, progress?.artist.orEmpty())
                    recordingTrackItems(RelistenCatalogSource.show(artist, rec.date, rec.sourceId))
                }
            }
            QueueKind.LOCAL_PLAYLIST -> localPlaylistQueueItems(PhishInDb.get(applicationContext).localPlaylistDao(), ref.id)
        }
        if (progress == null || progress.finished || all.isEmpty()) return all
        return all.drop(progress.trackIndex.coerceIn(0, all.lastIndex))
    }

    private fun progressDao() = PhishInDb.get(applicationContext).progressDao()

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveNow()
        abandonAudioFocus()
        castPlayer?.run {
            setSessionAvailabilityListener(null)
            release()
        }
        // Both players are released by hand rather than through session.player, which is
        // only ever one of them.
        localPlayer?.release()
        session?.release()
        castPlayer = null
        localPlayer = null
        session = null
        scope.cancel()
        super.onDestroy()
    }
}
