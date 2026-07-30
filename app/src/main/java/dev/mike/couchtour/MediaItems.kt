package dev.mike.couchtour

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes

/**
 * Queue-level labelling, identical for every item in one show or playlist.
 *
 * A null [key] means the queue is ephemeral and its position is not recorded — the saver
 * skips any item without one. Used by shuffle, whose order can't be reconstructed.
 */
internal data class QueueInfo(
    val key: String?,
    val title: String,
    val subtitle: String,
    val art: String?,
)

/** "1997-11-17 · McNichols Arena", falling back to the queue when a track lacks a show. */
internal fun albumFor(track: Track, info: QueueInfo): String {
    val fromTrack = listOfNotNull(track.showDate, track.venueName).joinToString(" · ")
    return fromTrack.ifBlank { "${info.title} · ${info.subtitle}" }
}

/**
 * The playable [MediaItem]s for a whole show, in set order. Shared by [PlayerViewModel.playShow]
 * and the Android Auto browse tree ([PlaybackService]'s "show:" node) so a show queued from
 * either place is built exactly the same way.
 */
internal fun showTrackItems(show: Show): List<MediaItem> {
    val subtitle = listOfNotNull(show.venueName, show.location).joinToString(" · ")
    val art = show.albumCoverUrl ?: show.coverArtUrls?.medium
    val info = QueueInfo(showQueueKey(show.date), show.date, subtitle, art)
    return show.tracks.filter { it.playable }.map { mediaItem(it, info) }
}

/** Same as [showTrackItems], for a playlist — shared by [PlayerViewModel.playPlaylist] and Auto. */
internal fun playlistTrackItems(playlist: Playlist): List<MediaItem> {
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
    return entries.map { mediaItem(it.track, info, it) }
}

internal fun mediaItem(track: Track, info: QueueInfo, entry: PlaylistEntry? = null): MediaItem {
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
        // ExoPlayer sniffs the container and never needs this. Cast does: a queue item
        // with no content type is rejected outright by the media item converter, so
        // casting would throw on the first track without it. The archive is all MP3.
        .setMimeType(MimeTypes.AUDIO_MPEG)
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
