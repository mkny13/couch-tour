package dev.mike.couchtour

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import coil.compose.AsyncImage

// Catalog browsing: periods, artists, artist shows, artist videos and the tour picker.

@Composable
fun ShowsScreen(period: String, nav: NavHostController) {
    val isPopular = period == POPULAR_PERIOD_ID
    val shows = loadOnce(period) {
        if (isPopular) PhishInApi.popularShows() else PhishInApi.showsForPeriod(period)
    }
    var selectedTag by rememberSaveable(period) { mutableStateOf<String?>("All") }

    Column(Modifier.fillMaxSize()) {
        Header(if (isPopular) POPULAR_PERIOD_LABEL else period, nav)
        Loaded(shows.value) { list ->
            val availableTags = remember(list) {
                val tags = list.flatMap { it.tags }
                    .distinctBy { it.name.lowercase() }
                    .sortedWith(compareByDescending<Tag> { it.priority }.thenBy { it.name })
                    .map { it.name }
                if (tags.isNotEmpty()) listOf("All") + tags else emptyList()
            }
            val filtered = if (selectedTag.isNullOrBlank() || selectedTag.equals("All", ignoreCase = true)) {
                list
            } else {
                list.filter { show -> show.tags.any { it.name.equals(selectedTag, ignoreCase = true) } }
            }

            LazyColumn {
                if (availableTags.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 8.dp),
                        ) {
                            items(availableTags, key = { it }) { tag ->
                                val isSelected = (selectedTag == null && tag == "All") ||
                                    selectedTag.equals(tag, ignoreCase = true)
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { selectedTag = if (tag == "All") null else tag },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No shows match tag \"$selectedTag\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(filtered, key = { it.date }) { show ->
                    val isPartial = show.audioStatus == "partial"
                    RowItem(
                        title = show.date,
                        subtitle = listOfNotNull(show.venueName, show.location)
                            .joinToString(" · "),
                        artUrl = show.coverArtUrls?.small,
                        tags = show.tags.map { it.toTagRef() },
                        onTagClick = { tag -> selectedTag = tag },
                        trailing = when {
                            isPopular -> "♥ ${show.likesCount}"
                            isPartial -> "partial"
                            else -> null
                        },
                        trailingSecondary = if (isPopular && isPartial) "partial" else null,
                        onClick = { nav.navigate("show/${show.date}") }
                    )
                }
            }
        }
    }
}

/** Full list of artists across all backends. */
@Composable
fun ArtistsScreen(nav: NavHostController) {
    val rawArtists = loadOnce { loadArtistsByBackend() }
    val favoriteKeys by Favorites.keys.collectAsState()
    val groups = remember(rawArtists.value, favoriteKeys) {
        rawArtists.value?.map { groupArtistsForBrowse(it, favoriteKeys) }
    }
    var sortMode by rememberSaveable { mutableStateOf(ArtistSortMode.POPULAR) }
    var query by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Header("Artists", nav)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Filter artists…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ArtistSortMode.entries, key = { it.name }) { mode ->
                FilterChip(
                    selected = sortMode == mode,
                    onClick = { sortMode = mode },
                    label = { Text(mode.label) },
                )
            }
        }
        Loaded(groups) { g ->
            val phishMatches = g.phish?.takeIf { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            val favorited = g.favorited.filterByName(query).sortedByMode(sortMode)
            val others = g.others.filterByName(query).sortedByMode(sortMode)

            if (phishMatches == null && favorited.isEmpty() && others.isEmpty()) {
                Text(
                    "No artists match \"$query\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                return@Loaded
            }

            LazyColumn {
                phishMatches?.let { phish ->
                    item(key = "${phish.backend.id}-${phish.id}") {
                        RowItem(
                            title = phish.name,
                            subtitle = "${phish.showCount} ${plural(phish.showCount, "show")}",
                            artUrl = null,
                            trailingContent = { FavoriteButton(phish) },
                            onClick = { nav.navigate("artist/${phish.backend.id}/${phish.id}") }
                        )
                    }
                }
                if (favorited.isNotEmpty()) {
                    item { SectionHeader("Favorites") }
                    items(favorited, key = { "fav-${it.backend.id}-${it.id}" }) { artist ->
                        RowItem(
                            title = artist.name,
                            subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                            artUrl = null,
                            trailingContent = { FavoriteButton(artist) },
                            onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                        )
                    }
                    item { SectionHeader("Artists") }
                }
                items(others, key = { "${it.backend.id}-${it.id}" }) { artist ->
                    RowItem(
                        title = artist.name,
                        subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                        artUrl = null,
                        trailingContent = { FavoriteButton(artist) },
                        onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                    )
                }
            }
        }
    }
}

/** Years (or ranged periods) for one artist of either backend. */
@Composable
fun ArtistScreen(backendId: String, artistId: String, nav: NavHostController) {
    val backend = Backend.from(backendId)
    val loaded = loadOnce(artistId) {
        val source = sourceFor(backend ?: error("Unknown backend $backendId"))
        val artist = source.artists().firstOrNull { it.id == artistId } ?: error("Unknown artist $artistId")
        artist to source.periods(artist)
    }

    Column(Modifier.fillMaxSize()) {
        Header(loaded.value?.getOrNull()?.first?.name ?: artistId, nav, trailing = {
            loaded.value?.getOrNull()?.first?.let { FavoriteButton(it) }
        })
        Loaded(loaded.value) { (artist, periods) ->
            LazyColumn {
                // Newest first, matching the phish.in years screen.
                items(periods.sortedByDescending { it.label }, key = { it.id }) { period ->
                    RowItem(
                        title = period.label,
                        subtitle = if (period.id == POPULAR_PERIOD_ID) POPULAR_PERIOD_SUBTITLE
                            else "${period.showCount} ${plural(period.showCount, "show")}",
                        artUrl = period.artUrl,
                        onClick = {
                            // Phish keeps its own show/track screens so likes and the
                            // "partial" audio badge — features Relisten has no
                            // analogue for — still work.
                            if (backend == Backend.PHISHIN) {
                                nav.navigate("shows/${period.id}")
                            } else {
                                nav.navigate("artist/$backendId/$artistId/${period.id}")
                            }
                        }
                    )
                }
                // YouTube section (#233): the artist channel's videos, appended below the
                // period list — the macOS target's layout (D251). Nothing at all when
                // there's no curated channel or no API key.
                youtubeSection(artist) { video ->
                    nav.navigate(youtubeRoute(video.id, video.title, video.thumbnailUrl, artist.name, artist.id))
                }
            }
        }
    }
}

/** Appends one artist's YouTube section into an [androidx.compose.foundation.lazy.LazyListScope]
 *  (#233). Hidden entirely — not even a header — when the artist has no curated channel
 *  or the install has no API key (D44/D251 precedent), since a permanently broken section
 *  is noise; a real fetch failure gets an inline error, distinct from "no videos".
 *  [onVideoClick] receives the whole [YouTubeVideo] — the caller owns the navigation and
 *  #234's screen needs the title/thumb alongside the id, so tests don't need a
 *  [NavHostController]. */
internal fun androidx.compose.foundation.lazy.LazyListScope.youtubeSection(
    artist: ArtistRef,
    onVideoClick: (YouTubeVideo) -> Unit,
) {
    if (youtubeSectionChannel(artist) == null) return
    item(key = "youtube") {
        YouTubeSectionContent(artist, onVideoClick)
    }
}

/** The section's body, inside its [LazyListScope.item]: a spinner while the channel's
 *  videos load, an inline error on failure (distinct from a genuinely empty channel),
 *  and the video rows once loaded. */
@Composable
internal fun YouTubeSectionContent(artist: ArtistRef, onVideoClick: (YouTubeVideo) -> Unit) {
    val section = loadOnce("youtube-${artist.key}") { YouTubeCatalogSource.youtubeContent(artist) }
    SectionHeader("YouTube", divided = true)
    Loaded(section.value) { videos ->
        videos.forEach { video ->
            YouTubeVideoRow(video) { onVideoClick(video) }
        }
        if (videos.isEmpty()) {
            Text(
                "No videos.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** One video: 16:9 thumbnail on the left (macOS target's row shape, D251), title and a
 *  relative upload date beside it. Tapping navigates to the video route; #234 plays it. */
@Composable
private fun YouTubeVideoRow(video: YouTubeVideo, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = video.title,
            modifier = Modifier
                .width(112.dp)
                .height(63.dp) // 16:9, YouTube's own aspect
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                video.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            video.publishedAtMs?.takeIf { it > 0 }?.let { ms ->
                Text(
                    relativeTime(ms),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * #234's video screen: starts (or picks up) playback of one YouTube video through the
 * shared session — lockscreen controls, scrobbling and progress all work like any other
 * queue — defaulting to audio-only background playback. The video surface renders the
 * muxed stream only while this screen is front-and-center; leaving the screen (or the
 * audio/video toggle) never restarts playback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun YouTubeVideoScreen(
    videoId: String,
    title: String,
    thumbUrl: String,
    artistName: String,
    artistId: String,
    vm: PlayerViewModel,
    nav: NavHostController,
) {
    val state by vm.state.collectAsState()
    val playbackError by vm.playbackError.collectAsState()
    val alreadyPlaying = state.backend == Backend.YOUTUBE.id && state.hasQueue && state.trackId == videoId
    // Returning to the screen for a video that's already playing must not restart it —
    // unlike a fresh row tap, which intentionally starts the video over.
    LaunchedEffect(videoId, alreadyPlaying) {
        if (!alreadyPlaying) {
            vm.playYouTube(
                YouTubeVideo(
                    id = videoId,
                    title = title,
                    channelId = "",
                    thumbnailUrl = thumbUrl.ifBlank { null },
                ),
                artistName = artistName.ifBlank { "Phish" },
                artistId = artistId,
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header(title.ifBlank { "YouTube" }, nav)

        // 16:9 surface: the real video when one is showing, the thumbnail otherwise.
        if (state.youTubeMode == YouTubePlaybackMode.VIDEO && vm.player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update = { view -> view.player = vm.player },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            )
        } else {
            AsyncImage(
                model = thumbUrl.ifBlank { null },
                contentDescription = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                title.ifBlank { videoId },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (artistName.isNotBlank()) {
                Text(
                    artistName,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            playbackError?.let { message ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(
                        message,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.playYouTube(YouTubeVideo(id = videoId, title = title, channelId = "", thumbnailUrl = thumbUrl.ifBlank { null }), artistName, artistId) }) {
                        Text("Retry")
                    }
                }
            }

            // Transport: play/pause plus the audio/video toggle (#234's headline). The
            // toggle swaps the stream in place — position survives.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                IconButton(
                    onClick = { vm.togglePlayPause() },
                    enabled = alreadyPlaying,
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                }
                // Hidden until playback exists — there is nothing to toggle before that.
                if (alreadyPlaying) {
                    TextButton(onClick = { vm.toggleYouTubeMode() }) {
                        Icon(
                            if (state.youTubeMode == YouTubePlaybackMode.VIDEO) Icons.Default.Headphones else Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.youTubeMode == YouTubePlaybackMode.VIDEO) "Audio only" else "Watch video")
                    }
                }
            }
        }
    }
}

/** Builds the player route for one video, with its context percent-encoded (#234). */
internal fun youtubeRoute(
    videoId: String,
    title: String?,
    thumbUrl: String?,
    artistName: String?,
    artistId: String?,
): String = "youtube/${android.net.Uri.encode(videoId)}" +
    "?title=${android.net.Uri.encode(title.orEmpty())}" +
    "&thumb=${android.net.Uri.encode(thumbUrl.orEmpty())}" +
    "&artist=${android.net.Uri.encode(artistName.orEmpty())}" +
    "&artistId=${android.net.Uri.encode(artistId.orEmpty())}"

/** Shows within one period of one Relisten artist. */
@Composable
fun ArtistShowsScreen(
    backendId: String,
    artistId: String,
    periodId: String,
    /** Set when arriving from a search hit (a song or venue): the period id isn't in
     *  [MusicSource.periods]' ordinary list, so its label travels with the route instead of
     *  being looked up — and skips the wasted years fetch that lookup would cost. */
    periodLabel: String? = null,
    nav: NavHostController,
) {
    val backend = Backend.from(backendId)
    val loaded = loadOnce(periodId) {
        val source = sourceFor(backend ?: error("Unknown backend $backendId"))
        val artist = source.artists().firstOrNull { it.id == artistId } ?: error("Unknown artist $artistId")
        val period = periodLabel?.let { PeriodRef(periodId, it) }
            ?: source.periods(artist).firstOrNull { it.id == periodId } ?: error("Unknown period $periodId")
        Triple(artist, period, source.shows(artist, period))
    }
    var sortMode by rememberSaveable(periodId) { mutableStateOf(ShowSortMode.DATE_DESC) }
    var selectedTag by rememberSaveable(periodId) { mutableStateOf<String?>("All") }

    val sortOptions = listOf(
        ShowSortMode.DATE_DESC to "Date",
        ShowSortMode.TOP_RATED to "Top rated",
        ShowSortMode.TRENDING_48H to "Trending 48h",
        ShowSortMode.HOT_7D to "Hot 7d",
        ShowSortMode.POPULAR_30D to "Popular 30d",
        ShowSortMode.MOMENTUM to "Momentum",
    )

    Column(Modifier.fillMaxSize()) {
        Header(loaded.value?.getOrNull()?.second?.label ?: periodId, nav)
        Loaded(loaded.value) { (_, _, shows) ->
            val availableTags = remember(shows) {
                val tags = shows.flatMap { it.tags }
                    .distinctBy { it.name.lowercase() }
                    .sortedWith(compareByDescending<TagRef> { it.priority }.thenBy { it.name })
                    .map { it.name }
                if (tags.isNotEmpty()) listOf("All") + tags else emptyList()
            }
            val filteredShows = if (selectedTag.isNullOrBlank() || selectedTag.equals("All", ignoreCase = true)) {
                shows
            } else {
                shows.filterByTag(selectedTag!!)
            }
            val ordered = filteredShows.sortedByMode(sortMode)

            LazyColumn {
                item {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            items(sortOptions, key = { it.first.name }) { (mode, label) ->
                                FilterChip(
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                    label = { Text(label) },
                                )
                            }
                        }
                        if (availableTags.size > 1) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                items(availableTags, key = { it }) { tag ->
                                    val isSelected = (selectedTag == null && tag == "All") ||
                                        selectedTag.equals(tag, ignoreCase = true)
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedTag = if (tag == "All") null else tag },
                                        label = { Text(tag) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (ordered.isEmpty()) {
                    item {
                        Text(
                            "No shows match tag \"$selectedTag\".",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(ordered, key = { it.date }) { show ->
                    val hasRating = show.rating > 0
                    val tapesLabel = if (show.recordingCount > 1) "${show.recordingCount} tapes" else null
                    val trailingText = when (sortMode) {
                        ShowSortMode.TRENDING_48H -> if (show.hotScore48h > 0) "🔥 ${"%.1f".format(show.hotScore48h)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.HOT_7D -> if (show.hotScore7d > 0) "🔥 ${"%.1f".format(show.hotScore7d)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.POPULAR_30D -> if (show.hotScore30d > 0) "🔥 ${"%.1f".format(show.hotScore30d)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.MOMENTUM -> if (show.momentumScore > 0) "⚡ ${"%.2f".format(show.momentumScore)}" else if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        ShowSortMode.TOP_RATED -> if (hasRating) "★ ${"%.1f".format(show.rating)}" else null
                        else -> if (hasRating) "★ ${"%.1f".format(show.rating)}" else tapesLabel
                    }
                    val trailingSecondary = if (trailingText != null && trailingText != tapesLabel) tapesLabel else null

                    RowItem(
                        title = show.date,
                        subtitle = show.where,
                        artUrl = show.artUrl,
                        tags = show.tags,
                        onTagClick = { tag -> selectedTag = tag },
                        show = show,
                        trailing = trailingText,
                        trailingSecondary = trailingSecondary,
                        onClick = { nav.navigate("recording/$backendId/$artistId/${show.date}") }
                    )
                }
            }
        }
    }
}

@Composable
internal fun TourPickerDialog(
    artist: ArtistRef,
    currentPreference: ArtistTourPreferenceEntity?,
    onDismiss: () -> Unit,
    onSave: (tourName: String?, year: String?) -> Unit,
    onClear: () -> Unit,
) {
    val periodsState = loadOnce(artist.key) {
        val src = sourceFor(artist.backend)
        src.periods(artist).filter { it.id != POPULAR_PERIOD_ID }
    }
    val validPeriods = periodsState.value?.getOrNull().orEmpty()
    val validYears = remember(validPeriods) {
        validPeriods.map { it.label }.filter { it.isNotBlank() }
    }

    var selectedYear by rememberSaveable(currentPreference) {
        mutableStateOf(
            currentPreference?.year
                ?: currentPreference?.tourName?.let { name ->
                    Regex("""\b(19\d\d|20\d\d)\b""").find(name)?.value
                }.orEmpty()
        )
    }
    var selectedTour by rememberSaveable(currentPreference) {
        mutableStateOf(currentPreference?.tourName.orEmpty())
    }
    var pickingYear by rememberSaveable(currentPreference) {
        mutableStateOf(selectedYear.isBlank())
    }

    val selectedPeriod = remember(validPeriods, selectedYear) {
        validPeriods.firstOrNull { it.label == selectedYear || it.id == selectedYear }
    }

    val showsState = loadOnce(artist.key to (selectedPeriod?.id ?: "")) {
        if (selectedPeriod == null) {
            emptyList()
        } else {
            val src = sourceFor(artist.backend)
            src.shows(artist, selectedPeriod)
        }
    }

    val availableTours = remember(showsState.value) {
        showsState.value?.getOrNull()
            ?.mapNotNull { it.tourName }
            ?.filter { it.isNotBlank() && it != NOT_PART_OF_A_TOUR }
            ?.distinct()
            ?.sorted()
            .orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Track tour for ${artist.name}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Choose a year and select a tour to track on your Next Stop shelf.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (pickingYear || selectedYear.isBlank()) {
                    TourYearPickerSection(
                        validYears = validYears,
                        selectedYear = selectedYear,
                        periodsLoaded = periodsState.value != null,
                        onSelectYear = { yr ->
                            if (selectedYear != yr) {
                                selectedYear = yr
                                selectedTour = ""
                            }
                            pickingYear = false
                        },
                    )
                } else {
                    TourSelectionSection(
                        selectedYear = selectedYear,
                        selectedTour = selectedTour,
                        availableTours = availableTours,
                        showsLoaded = showsState.value != null,
                        onChangeYear = { pickingYear = true },
                        onSelectTour = { selectedTour = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedYear.isNotBlank(),
                onClick = {
                    onSave(selectedTour.trim().takeIf { it.isNotBlank() }, selectedYear.trim())
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (currentPreference != null) {
                    TextButton(onClick = onClear) { Text("Reset") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
/** Spinner box shown while the periods or shows load inside the tour picker dialog. */
@Composable
private fun TourPickerLoadingBox() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

/** A single selectable dialog row with a trailing check mark when chosen. */
@Composable
private fun TourPickerRow(
    label: String,
    isSelected: Boolean,
    boldWhenSelected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (isSelected && boldWhenSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** Year list half of the tour picker dialog, shown while a year is being chosen. */
@Composable
private fun ColumnScope.TourYearPickerSection(
    validYears: List<String>,
    selectedYear: String,
    periodsLoaded: Boolean,
    onSelectYear: (String) -> Unit,
) {
    Text("Select a year:", style = MaterialTheme.typography.labelMedium)
    if (!periodsLoaded) {
        TourPickerLoadingBox()
    } else {
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = 260.dp)
        ) {
            items(validYears) { yr ->
                TourPickerRow(
                    label = yr,
                    isSelected = selectedYear == yr,
                ) {
                    onSelectYear(yr)
                }
            }
        }
    }
}



/** Tour list half of the tour picker dialog, shown once a year is picked. */
@Composable
private fun ColumnScope.TourSelectionSection(
    selectedYear: String,
    selectedTour: String,
    availableTours: List<String>,
    showsLoaded: Boolean,
    onChangeYear: () -> Unit,
    onSelectTour: (String) -> Unit,
) {
    // Header with selected year and "Change" action
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                "YEAR",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                selectedYear,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        TextButton(onClick = onChangeYear) {
            Text("Change year")
        }
    }

    Text("Select a tour from $selectedYear:", style = MaterialTheme.typography.labelMedium)

    if (!showsLoaded) {
        TourPickerLoadingBox()
    } else {
        LazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = 240.dp)
        ) {
            // Option 1: Entire Year (all shows in this year)
            item {
                TourEntireYearOption(
                    selectedYear = selectedYear,
                    isSelected = selectedTour.isBlank(),
                    onClick = { onSelectTour("") },
                )
            }

            // Option 2...N: Distinct tours in this year
            if (availableTours.isNotEmpty()) {
                items(availableTours) { tour ->
                    TourPickerRow(
                        label = tour,
                        isSelected = selectedTour == tour,
                        boldWhenSelected = true,
                    ) {
                        onSelectTour(tour)
                    }
                }
            } else {
                item {
                    Text(
                        "No named tours listed for $selectedYear.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

/** The "All shows in <year>" option — tracking every show rather than one named tour. */
@Composable
private fun TourEntireYearOption(
    selectedYear: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "All shows in $selectedYear",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Track all shows from this year",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/**
 * Star toggle for [ArtistRef]s (#14). A star rather than [LikeButton]'s heart, deliberately:
 * favoriting is local-only, works signed out, and applies to both backends, so it shouldn't
 * look like the phish.in-account "like" it has nothing to do with.
 */
@Composable
private fun FavoriteButton(artist: ArtistRef) {
    val favoriteKeys by Favorites.keys.collectAsState()
    val favorited = artist.key in favoriteKeys
    IconButton(onClick = { Favorites.toggle(artist.key) }) {
        Icon(
            if (favorited) Icons.Filled.Star else Icons.Filled.StarBorder,
            if (favorited) "Unfavorite ${artist.name}" else "Favorite ${artist.name}",
            tint = if (favorited) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
