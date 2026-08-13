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
 *
 * [artist] feeds the MediaSession the official Last.fm app scrobbles from (D50). Every
 * existing queue is phish.in's, so it defaults to "Phish" rather than making every call site
 * repeat it; [recordingTrackItems] is the one place that passes the real artist name in —
 * left at the default, every Dead show would scrobble as Phish.
 */
internal data class QueueInfo(
    val key: String?,
    val title: String,
    val subtitle: String,
    val art: String?,
    val artist: String = "Phish",
)

/** "1997-11-17 · McNichols Arena", falling back to the queue when a track lacks a show. */
internal fun albumFor(showDate: String?, venueName: String?, info: QueueInfo): String {
    val fromTrack = listOfNotNull(showDate, venueName).joinToString(" · ")
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
    // Playlist entries can be excerpts; without this they'd play the full track. Playlists
    // are a phish.in account feature (D30), so this stays here rather than in the shared core.
    val clipping = if (entry != null && (entry.startsAtSecond != null || entry.endsAtSecond != null)) {
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs((entry.startsAtSecond ?: 0) * 1000L)
            .apply { entry.endsAtSecond?.let { setEndPositionMs(it * 1000L) } }
            .build()
    } else null

    return coreMediaItem(
        id = track.id.toString(),
        title = track.title,
        url = track.mp3Url.orEmpty(),
        art = art,
        waveformUrl = track.waveformImageUrl,
        showDate = track.showDate,
        venueName = track.venueName,
        info = info,
        clipping = clipping,
    )
}

/** The Relisten counterpart of [mediaItem] — same shape, a [PlayableTrack] instead of a phish.in [Track]. */
internal fun recordingMediaItem(track: PlayableTrack, info: QueueInfo): MediaItem = coreMediaItem(
    id = track.id,
    title = track.title,
    url = track.url,
    art = track.artUrl ?: info.art,
    waveformUrl = track.waveformUrl,
    showDate = track.showDate,
    venueName = track.venueName,
    info = info,
)

/**
 * The one place both [mediaItem] and [recordingMediaItem] build the actual [MediaItem], so a
 * phish.in show and a Relisten tape produce byte-identical queues for the same inputs — the
 * property [PlaybackService]'s browse tree relies on (D73).
 */
private fun coreMediaItem(
    id: String,
    title: String,
    url: String,
    art: String?,
    waveformUrl: String?,
    showDate: String?,
    venueName: String?,
    info: QueueInfo,
    clipping: MediaItem.ClippingConfiguration? = null,
): MediaItem {
    val extras = Bundle().apply {
        info.key?.let { putString(Keys.QUEUE_KEY, it) }
        putString(Keys.QUEUE_TITLE, info.title)
        putString(Keys.QUEUE_SUBTITLE, info.subtitle)
        putString(Keys.QUEUE_ART, info.art)
        putString(Keys.WAVEFORM, waveformUrl)
    }
    val meta = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(info.artist)
        // The album is the show the track was played at, not the queue it arrived in.
        // External scrobblers (the Last.fm app reads our MediaSession directly) take
        // this field verbatim, and "some playlist · by someone · 99 tracks" is not an
        // album. Every track carries its own show, including inside a playlist.
        .setAlbumTitle(albumFor(showDate, venueName, info))
        // Queue identity lives here instead, so the mini player still shows the
        // playlist you started from rather than the underlying show.
        .setSubtitle("${info.title} · ${info.subtitle}")
        .setArtworkUri(art?.let { Uri.parse(it) })
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setExtras(extras)
        .build()

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        // ExoPlayer sniffs the container and never needs this. Cast does: a queue item
        // with no content type is rejected outright by the media item converter, so
        // casting would throw on the first track without it. Relisten is MP3-only too (O3).
        .setMimeType(MimeTypes.AUDIO_MPEG)
        .setMediaMetadata(meta)
        .apply { clipping?.let { setClippingConfiguration(it) } }
        .build()
}

/**
 * The playable [MediaItem]s for one tape of a Relisten show, in track order. [ShowDetail]
 * already picked the recording (P3's `toShowDetail`) and filtered to tracks with audio, the
 * same contract [showTrackItems] relies on for phish.in.
 *
 * A show with no chosen recording — [ShowDetail.queueKey] is then null — still builds a
 * (unresumable) queue rather than an empty one: better to let it play than to silently
 * refuse, the same tradeoff D42 makes for shuffle.
 */
internal fun recordingTrackItems(detail: ShowDetail): List<MediaItem> {
    val summary = detail.summary
    val info = QueueInfo(
        key = detail.queueKey,
        title = summary.date,
        subtitle = summary.where,
        art = summary.artUrl ?: detail.tracks.firstOrNull()?.artUrl,
        artist = summary.artist.name,
    )
    return detail.tracks.map { recordingMediaItem(it, info) }
}
