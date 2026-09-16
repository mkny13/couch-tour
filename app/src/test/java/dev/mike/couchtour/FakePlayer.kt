package dev.mike.couchtour

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * A media3 player with no audio behind it, for the UI tests (#250): [PlayerViewModel.attach]
 * takes it in place of the MediaController a real PlaybackService would hand over, so the
 * transport controls and "Resume playback" run their real code paths against something whose
 * state a test can read back. [SimpleBasePlayer] does the listener bookkeeping — this only
 * keeps the playlist, the current index and play/pause.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FakePlayer : SimpleBasePlayer(Looper.getMainLooper()) {

    var items: List<MediaItem> = emptyList()
        private set
    var index = 0
        private set
    private var playing = false

    /** Where the last load or seek put the playhead — not [getContentPosition], which
     *  [SimpleBasePlayer] extrapolates forward in real time while "playing". */
    var requestedPositionMs = 0L
        private set

    val currentTitle: String? get() = items.getOrNull(index)?.mediaMetadata?.title?.toString()

    override fun getState(): State {
        val builder = State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder().addAll(
                    COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP,
                    COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_MEDIA_ITEM,
                    COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
                    COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
                    COMMAND_RELEASE,
                ).build()
            )
            .setPlayWhenReady(playing, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (items.isEmpty()) return builder.setPlaybackState(STATE_IDLE).build()
        return builder
            .setPlaylist(items.mapIndexed { i, item ->
                MediaItemData.Builder("$i-${item.mediaId}")
                    .setMediaItem(item)
                    .setDurationUs(600_000_000L)
                    .build()
            })
            .setCurrentMediaItemIndex(index)
            .setContentPositionMs(requestedPositionMs)
            .setPlaybackState(STATE_READY)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playing = playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSetMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> {
        items = mediaItems.toList()
        index = if (startIndex == C.INDEX_UNSET) 0 else startIndex
        requestedPositionMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex != C.INDEX_UNSET) index = mediaItemIndex
        requestedPositionMs = if (positionMs == C.TIME_UNSET) 0 else positionMs
        return Futures.immediateVoidFuture()
    }
}
