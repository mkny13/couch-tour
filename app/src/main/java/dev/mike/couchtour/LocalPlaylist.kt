package dev.mike.couchtour

import androidx.media3.common.MediaItem
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * Local playlists spanning both backends (#12) — the same account-free shape #11's
 * [LikedTracks] was expected to reuse, except a playlist is ordered and each entry needs
 * enough context to be resolved back into a [android.media3.common.MediaItem] later (a bare
 * track id, unlike [LikedTracks], isn't enough — see D161). Room, not `SharedPreferences`:
 * this is relational (one playlist, many ordered tracks), where [Favorites]/[LikedTracks]'s
 * flat `Set<String>` doesn't fit.
 */
@Entity(tableName = "local_playlists")
data class LocalPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Denormalized so the list screen renders without a join. */
    val trackCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One playlist entry. Enough to independently refetch the track at play time (D161):
 * [backend] + [trackId] + [showDate] locate a phish.in track inside its show; Relisten also
 * needs [artistSlug] (there's no fetch-by-id endpoint) and, since a show can have several
 * tapes with different track splits, [recordingId] — null falls back to the default tape,
 * same as [MusicSource.show].
 *
 * The rest ([title], [durationMs], [venueName], [artUrl]) is denormalized display data, so
 * the playlist screen renders without re-fetching every track — the same tradeoff
 * [Progress] makes for its own display fields.
 */
@Entity(
    tableName = "local_playlist_tracks",
    foreignKeys = [ForeignKey(
        entity = LocalPlaylistEntity::class,
        parentColumns = ["id"],
        childColumns = ["playlistId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("playlistId")],
)
data class LocalPlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val playlistId: String,
    val position: Int,
    /** [Backend.id] — "phishin" or "relisten". */
    val backend: String,
    /** phish.in `Track.id.toString()`, or Relisten's track uuid ([PlayableTrack.id]). */
    val trackId: String,
    val showDate: String,
    val artistSlug: String? = null,
    val recordingId: String? = null,
    val title: String,
    val durationMs: Long = 0,
    val venueName: String? = null,
    val artUrl: String? = null,
)

@Dao
interface LocalPlaylistDao {
    @Query("SELECT * FROM local_playlists ORDER BY updatedAt DESC")
    fun playlists(): Flow<List<LocalPlaylistEntity>>

    @Query("SELECT * FROM local_playlists WHERE id = :id")
    suspend fun playlist(id: String): LocalPlaylistEntity?

    @Query("SELECT * FROM local_playlist_tracks WHERE playlistId = :id ORDER BY position")
    fun tracks(id: String): Flow<List<LocalPlaylistTrackEntity>>

    @Query("SELECT * FROM local_playlist_tracks WHERE playlistId = :id ORDER BY position")
    suspend fun tracksOnce(id: String): List<LocalPlaylistTrackEntity>

    @Insert
    suspend fun insertPlaylist(playlist: LocalPlaylistEntity)

    @Insert
    suspend fun insertTrack(track: LocalPlaylistTrackEntity)

    @Query("SELECT COALESCE(MAX(position), -1) FROM local_playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: String): Int

    @Query("UPDATE local_playlists SET trackCount = trackCount + 1, updatedAt = :now WHERE id = :id")
    suspend fun incrementTrackCount(id: String, now: Long)

    @Query("DELETE FROM local_playlist_tracks WHERE rowId = :rowId")
    suspend fun deleteTrackRow(rowId: Long)

    @Query("UPDATE local_playlists SET trackCount = trackCount - 1, updatedAt = :now WHERE id = :id")
    suspend fun decrementTrackCount(id: String, now: Long)

    @Query("DELETE FROM local_playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Query("UPDATE local_playlists SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun renamePlaylist(id: String, name: String, now: Long)

    @Query("UPDATE local_playlist_tracks SET position = :position WHERE rowId = :rowId AND playlistId = :playlistId")
    suspend fun updateTrackPosition(rowId: Long, playlistId: String, position: Int)

    /** Appends [track] to the end of its playlist and bumps [LocalPlaylistEntity.trackCount]/`updatedAt`. */
    @Transaction
    suspend fun addTrack(track: LocalPlaylistTrackEntity, now: Long) {
        val position = maxPosition(track.playlistId) + 1
        insertTrack(track.copy(position = position))
        incrementTrackCount(track.playlistId, now)
    }

    /** The inverse of [addTrack] — [rowId] is [LocalPlaylistTrackEntity.rowId]. */
    @Transaction
    suspend fun removeTrack(rowId: Long, playlistId: String, now: Long) {
        deleteTrackRow(rowId)
        decrementTrackCount(playlistId, now)
    }

    @Query("UPDATE local_playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touchPlaylist(id: String, now: Long)

    /** Updates positions for [orderedRowIds] sequentially and bumps playlist `updatedAt`. */
    @Transaction
    suspend fun reorderTracks(playlistId: String, orderedRowIds: List<Long>, now: Long) {
        orderedRowIds.forEachIndexed { index, rowId ->
            updateTrackPosition(rowId, playlistId, index)
        }
        touchPlaylist(playlistId, now)
    }
}

/**
 * Builds a local playlist's queue from scratch — shared by [PlayerViewModel] (phone) and
 * [PlaybackService]'s Auto browse tree, the same way [recordingTrackItems] is, so a playlist
 * queued from either place resolves identically.
 */
internal suspend fun localPlaylistQueueItems(dao: LocalPlaylistDao, playlistId: String): List<MediaItem> {
    val playlist = dao.playlist(playlistId) ?: return emptyList()
    val resolved = resolveLocalPlaylistTracks(dao.tracksOnce(playlistId))
    return localPlaylistTrackItems(playlistId, playlist.name, resolved)
}

/**
 * Refetches every stored reference into something playable — there's no fetch-by-id endpoint
 * on either backend, so this mirrors [PlayerViewModel.playTrack]/`.resume()`'s existing
 * pattern (fetch the show, find the track inside it), batched to one fetch per distinct show
 * or tape rather than one per track. A reference that no longer resolves — a deleted show, a
 * track dropped from a tape — is skipped rather than failing the whole playlist.
 *
 * The per-show/per-tape fetches run concurrently (`async` + `awaitAll`), not one at a time —
 * a playlist spanning N distinct shows used to pay N sequential round trips before playback
 * could start, which is where a 30+ second resume on a "favorites across years" mixtape came
 * from (issue: slow Android playback resume).
 */
internal suspend fun resolveLocalPlaylistTracks(refs: List<LocalPlaylistTrackEntity>): List<ResolvedLocalTrack> = coroutineScope {
    val phishShows = refs.filter { it.backend == Backend.PHISHIN.id }
        .map { it.showDate }.distinct()
        .map { date -> async { date to runCatching { PhishInApi.show(date) }.getOrNull() } }
        .awaitAll().toMap()

    val relistenRefs = refs.filter { it.backend == Backend.RELISTEN.id }
    val relistenArtists = if (relistenRefs.isEmpty()) emptyList()
        else runCatching { RelistenCatalogSource.artists() }.getOrDefault(emptyList())
    val relistenShows = relistenRefs
        .mapNotNull { ref -> ref.artistSlug?.let { Triple(it, ref.showDate, ref.recordingId) } }
        .distinct()
        .map { key ->
            async {
                val (slug, date, recordingId) = key
                val artist = relistenArtists.firstOrNull { it.id == slug }
                key to artist?.let { runCatching { RelistenCatalogSource.show(it, date, recordingId) }.getOrNull() }
            }
        }
        .awaitAll().toMap()

    refs.mapNotNull { ref ->
        when (ref.backend) {
            Backend.PHISHIN.id -> {
                val show = phishShows[ref.showDate] ?: return@mapNotNull null
                val track = show.tracks.firstOrNull { it.id.toString() == ref.trackId } ?: return@mapNotNull null
                ResolvedLocalTrack(
                    id = track.id.toString(),
                    title = track.title,
                    url = track.mp3Url.orEmpty(),
                    waveformUrl = track.waveformImageUrl,
                    // Not track.showDate/venueName — both are null on a track nested inside
                    // a show fetch (Api.kt), only populated on flatter shapes like search
                    // results and playlist entries.
                    showDate = show.date,
                    venueName = show.venueName,
                    artUrl = show.albumCoverUrl ?: show.coverArtUrls?.medium,
                    artistName = "Phish",
                    backend = Backend.PHISHIN.id,
                    likedByUser = track.likedByUser,
                    likesCount = track.likesCount,
                )
            }
            Backend.RELISTEN.id -> {
                val slug = ref.artistSlug ?: return@mapNotNull null
                val detail = relistenShows[Triple(slug, ref.showDate, ref.recordingId)] ?: return@mapNotNull null
                val track = detail.tracks.firstOrNull { it.id == ref.trackId } ?: return@mapNotNull null
                ResolvedLocalTrack(
                    id = track.id,
                    title = track.title,
                    url = track.url,
                    waveformUrl = track.waveformUrl,
                    showDate = track.showDate,
                    venueName = track.venueName,
                    artUrl = track.artUrl,
                    artistName = detail.summary.artist.name,
                    backend = Backend.RELISTEN.id,
                    flacUrl = track.flacUrl,
                )
            }
            else -> null
        }
    }
}
