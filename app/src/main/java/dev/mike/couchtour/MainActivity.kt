package dev.mike.couchtour

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Set when launched from the media notification; consumed once the UI has navigated. */
    private val openNowPlaying = mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // launchMode is singleTask, so a second tap re-enters through here, not onCreate.
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) openNowPlaying.value = true
        SyncApi.maybeApplyBaseUrlOverride(this, intent.getStringExtra(EXTRA_SYNC_BASE_URL))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openNowPlaying.value = intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) == true
        SyncApi.maybeApplyBaseUrlOverride(this, intent?.getStringExtra(EXTRA_SYNC_BASE_URL))

        if (savedInstanceState == null) {
            // An immediate catch-up on launch, on top of the periodic background job.
            // Fire-and-forget: sync() is a no-op if unpaired. lifecycleScope ties this launch
            // to the Activity lifecycle so the coroutine (and the Activity reference it
            // captures) is cancelled if the Activity is destroyed before sync completes.
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    SyncSession.sync(PhishInDb.get(this@MainActivity).progressDao())
                } catch (e: Exception) {
                    android.util.Log.w("Sync", "Launch sync failed; the periodic job will retry", e)
                    DiagnosticsLog.log("sync.error", DiagnosticsLog.Level.WARN, "code" to syncErrorCode(e))
                }
            }
        }

        // Without this the media notification (and therefore the lockscreen controls)
        // is silently suppressed on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            CouchTourTheme {
                Surface(modifier = Modifier.fillMaxSize().testTagsAsResourceIds()) { App(openNowPlaying = openNowPlaying) }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_NOW_PLAYING = "open_now_playing"
        const val EXTRA_SYNC_BASE_URL = "syncBaseUrl"
    }
}

@Composable
fun App(
    vm: PlayerViewModel = viewModel(),
    openNowPlaying: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    val nav = rememberNavController()
    val state by vm.state.collectAsState()

    // Arriving from the media notification. Waits for the queue to be known — not just the
    // key, since shuffle queues have none — then opens the player once and clears the flag.
    LaunchedEffect(openNowPlaying.value, state.hasQueue) {
        if (openNowPlaying.value && state.hasQueue) {
            nav.navigate("player")
            openNowPlaying.value = false
        }
    }

    // Player events don't fire while a track simply advances, so tick the scrubber.
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            delay(500)
            vm.refresh()
        }
    }

    val currentRoute by nav.currentBackStackEntryAsState()
    val isPlayerRoute = currentRoute?.destination?.route == "player"
    Scaffold(
        bottomBar = {
            if (!isPlayerRoute) {
                Column {
                    if (state.hasQueue) {
                        MiniPlayer(state, vm, nav)
                    }
                    LedgerBottomBar(currentRoute?.destination?.route, nav)
                }
            }
        }
    ) { padding ->
        DisposableEffect(nav) {
            val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
                val route = destination.route?.substringBefore('?')
                if (route != null) {
                    DiagnosticsLog.log("nav.route", "route" to route)
                }
            }
            nav.addOnDestinationChangedListener(listener)
            onDispose {
                nav.removeOnDestinationChangedListener(listener)
            }
        }
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(vm, nav) }
            composable("artists") { ArtistsScreen(nav) }
            composable("search") { SearchScreen(vm, nav) }
            composable("library") { LibraryScreen(vm, nav) }
            composable("settings") { SettingsScreen(vm, nav) }
            composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
            composable("player") { NowPlayingScreen(vm, nav) }
            composable("history") { HistoryScreen(vm, nav) }
            composable("login") { LoginScreen(nav) }
            composable("artist/{backend}/{id}") { entry ->
                ArtistScreen(
                    backendId = entry.arguments?.getString("backend").orEmpty(),
                    artistId = entry.arguments?.getString("id").orEmpty(),
                    nav = nav,
                )
            }
            // A YouTube video row's destination (#234): the playback screen. The video's
            // title/thumb/artist travel as query args so the screen can build the queue
            // without another fetch; the id stays the single path argument.
            composable(
                "youtube/{videoId}?title={title}&thumb={thumb}&artist={artist}&artistId={artistId}",
                arguments = listOf(
                    navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    navArgument("thumb") { type = NavType.StringType; defaultValue = "" },
                    navArgument("artist") { type = NavType.StringType; defaultValue = "" },
                    navArgument("artistId") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                YouTubeVideoScreen(
                    videoId = entry.arguments?.getString("videoId").orEmpty(),
                    title = entry.arguments?.getString("title").orEmpty(),
                    thumbUrl = entry.arguments?.getString("thumb").orEmpty(),
                    artistName = entry.arguments?.getString("artist").orEmpty(),
                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                    vm = vm,
                    nav = nav,
                )
            }
            composable(
                "artist/{backend}/{id}/{period}?label={label}",
                arguments = listOf(navArgument("label") { type = NavType.StringType; nullable = true }),
            ) { entry ->
                ArtistShowsScreen(
                    backendId = entry.arguments?.getString("backend").orEmpty(),
                    artistId = entry.arguments?.getString("id").orEmpty(),
                    periodId = entry.arguments?.getString("period").orEmpty(),
                    periodLabel = entry.arguments?.getString("label"),
                    nav = nav,
                )
            }
            composable(
                "recording/{backend}/{artistId}/{date}?src={src}&resumeIndex={resumeIndex}&resumeMs={resumeMs}",
                arguments = listOf(
                    navArgument("src") { type = NavType.StringType; nullable = true },
                    // Carried by the source picker when it catches this show mid-playback
                    // (#17) — a query param, not an Int/Long NavType, because those can't be
                    // nullable and most navigations to this route have neither.
                    navArgument("resumeIndex") { type = NavType.StringType; nullable = true },
                    navArgument("resumeMs") { type = NavType.StringType; nullable = true },
                ),
            ) { entry ->
                RecordingScreen(
                    backendId = entry.arguments?.getString("backend").orEmpty(),
                    artistId = entry.arguments?.getString("artistId").orEmpty(),
                    date = entry.arguments?.getString("date").orEmpty(),
                    recordingId = entry.arguments?.getString("src"),
                    resumeIndex = entry.arguments?.getString("resumeIndex")?.toIntOrNull(),
                    resumeMs = entry.arguments?.getString("resumeMs")?.toLongOrNull(),
                    vm = vm,
                    nav = nav,
                )
            }
            composable("shows/{period}") { entry ->
                ShowsScreen(entry.arguments?.getString("period").orEmpty(), nav)
            }
            composable("show/{date}") { entry ->
                ShowScreen(entry.arguments?.getString("date").orEmpty(), vm, nav)
            }
            composable("playlists") {
                PlaylistsScreen("Playlists", nav) { PhishInApi.playlists() }
            }
            composable("playlist/{slug}") { entry ->
                PlaylistScreen(entry.arguments?.getString("slug").orEmpty(), vm, nav)
            }
            composable("mine/playlists") {
                PlaylistsScreen("My playlists", nav) {
                    // "mine" and "liked" are separate filters; the page shows both together.
                    (PhishInApi.playlists(filter = "mine") + PhishInApi.playlists(filter = "liked"))
                        .distinctBy { it.slug }
                }
            }
            composable("mine/shows") { MyShowsScreen(nav) }
            composable("mine/tracks") { MyTracksScreen(vm, nav) }
            composable("local-playlists") { LocalPlaylistsScreen(vm, nav) }
            composable("local-playlist/{id}") { entry ->
                LocalPlaylistScreen(entry.arguments?.getString("id").orEmpty(), vm, nav)
            }
            composable("sync") { SyncScreen(vm, nav) }
            composable("scan") { ScanScreen(nav) }
        }
    }
}

// ---------------------------------------------------------------- screens

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
private fun YouTubeVideoScreen(
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
fun TagBadge(
    tag: TagRef,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val fallbackColor = when (tag.name.uppercase()) {
        "SBD", "SOUNDBOARD" -> MaterialTheme.colorScheme.primary
        "FLAC" -> MaterialTheme.colorScheme.secondary
        "MATRIX", "MATRIX?" -> MaterialTheme.colorScheme.tertiary
        "JAMCHARTS" -> Color(0xFFE91E63)
        "BUSTOUT", "BUSTOUT*" -> Color(0xFFFF9800)
        "GUEST" -> Color(0xFF9C27B0)
        "LORE" -> Color(0xFF009688)
        else -> MaterialTheme.colorScheme.outline
    }
    val badgeColor = parseTagColor(tag.color, fallbackColor)
    val baseModifier = modifier.padding(end = 4.dp)
    Surface(
        color = badgeColor.copy(alpha = 0.18f),
        contentColor = badgeColor,
        shape = RoundedCornerShape(4.dp),
        modifier = if (onClick != null) baseModifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick) else baseModifier,
    ) {
        Text(
            text = tag.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun parseTagColor(colorStr: String?, defaultColor: Color): Color {
    if (colorStr.isNullOrBlank()) return defaultColor
    return try {
        val hex = colorStr.trim().removePrefix("#")
        val colorInt = when (hex.length) {
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> hex.toLong(16).toInt()
            else -> return defaultColor
        }
        Color(colorInt)
    } catch (_: Throwable) {
        defaultColor
    }
}

/**
 * Heart toggle for a Relisten [PlayableTrack] (#11, #372), backed by [LikedTracks]. Deliberately
 * separate from phish.in's [LikeButton]: no account gate, no server round-trip, no public
 * count — just a local like.
 */
@Composable
internal fun LikeTrackButton(
    ref: LikedTrackRef,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    val likedIds by LikedTracks.ids.collectAsState()
    val liked = ref.id in likedIds
    IconButton(onClick = { LikedTracks.toggle(ref) }, modifier = modifier) {
        Icon(
            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
internal fun LikeTrackButton(
    trackId: String,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    LikeTrackButton(LikedTrackRef(id = trackId), modifier, iconSize)
}

/**
 * Add-to-playlist button for either backend's track row (#12) — [buildRef] is called with
 * `playlistId`/`position` left as placeholders; [PlayerViewModel.addToLocalPlaylist] fills
 * both in. A bottom sheet, matching [SourcePicker]'s reasoning: playlists here can run long
 * enough (and "New playlist" needs its own row) that a [DropdownMenu] would cramp them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToPlaylistButton(
    vm: PlayerViewModel,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
    tint: Color = LocalLedgerColors.current.accentIcon,
    buildRef: () -> LocalPlaylistTrackEntity
) {
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist", modifier = Modifier.size(iconSize), tint = tint)
    }
    if (open) {
        val playlists by vm.localPlaylistDao.playlists().collectAsState(initial = emptyList())
        ModalBottomSheet(onDismissRequest = { open = false }) {
            LazyColumn {
                item {
                    RowItem("New playlist", "", null) {
                        open = false
                        creating = true
                    }
                }
                items(playlists, key = { it.id }) { playlist ->
                    RowItem(
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} ${plural(playlist.trackCount, "track")}",
                        artUrl = null,
                    ) {
                        open = false
                        vm.addToLocalPlaylist(playlist.id, buildRef())
                    }
                }
            }
        }
    }
    if (creating) {
        NewPlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                creating = false
                scope.launch {
                    val id = vm.createLocalPlaylist(name)
                    vm.addToLocalPlaylist(id, buildRef())
                }
            },
        )
    }
}

@Composable
internal fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddTracksToPlaylistDialog(
    vm: PlayerViewModel,
    tracks: List<LocalPlaylistTrackEntity>,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playlists by vm.localPlaylistDao.playlists().collectAsState(initial = emptyList())

    if (creating) {
        NewPlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                creating = false
                scope.launch {
                    val id = vm.createLocalPlaylist(name)
                    tracks.forEach { vm.addToLocalPlaylist(id, it) }
                    onDismiss()
                }
            }
        )
    } else {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            LazyColumn {
                item {
                    RowItem("New playlist", "", null) {
                        creating = true
                    }
                }
                items(playlists, key = { it.id }) { playlist ->
                    RowItem(
                        title = playlist.name,
                        subtitle = "${playlist.trackCount} ${plural(playlist.trackCount, "track")}",
                        artUrl = null,
                    ) {
                        scope.launch {
                            tracks.forEach { vm.addToLocalPlaylist(playlist.id, it) }
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThemePickerDialog(
    currentMode: ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (ThemeMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose theme") },
        text = {
            Column {
                listOf(
                    ThemeMode.AUTO to "Auto (system default)",
                    ThemeMode.LIGHT to "Light",
                    ThemeMode.DARK to "Dark",
                ).forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (mode == currentMode),
                                onClick = { onSelect(mode) },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = (mode == currentMode),
                            onClick = null,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
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

@Composable
internal fun SearchResultsList(
    results: SearchHits?,
    vm: PlayerViewModel,
    nav: NavHostController,
) {
    if (results == null) {
        Loading()
        return
    }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>("All") }
    var sortMode by rememberSaveable { mutableStateOf(SearchSortMode.RELEVANCE) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    val artistsPresent = results.artistsPresent
    // Selection survives to a different query only by accident of key reuse — clear it once
    // the artist it named is no longer among the hits.
    val selectedArtist = artistsPresent.firstOrNull { "${it.backend.id}/${it.id}" == selected }
    val rArtist = results.filteredTo(selectedArtist)

    val availableTags = remember(rArtist) {
        val showTags = rArtist.shows.flatMap { it.tags }.map { it.name }
        val trackTags = rArtist.tracks.flatMap { it.tags }.map { it.name }
        val all = (showTags + trackTags).distinctBy { it.lowercase() }
        if (all.isNotEmpty()) listOf("All") + all else emptyList()
    }

    val r = remember(rArtist, selectedTag) {
        if (selectedTag.isNullOrBlank() || selectedTag.equals("All", ignoreCase = true)) {
            rArtist
        } else {
            val tag = selectedTag!!
            rArtist.copy(
                shows = rArtist.shows.filterByTag(tag),
                tracks = rArtist.tracks.filter { it.tags.any { t -> t.name.equals(tag, ignoreCase = true) } },
                artists = emptyList(),
                slices = emptyList(),
                playlists = emptyList(),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (artistsPresent.size > 1) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selected == null,
                        onClick = { selected = null },
                        label = { Text("All") },
                    )
                }
                items(artistsPresent, key = { "${it.backend.id}/${it.id}" }) { artist ->
                    val key = "${artist.backend.id}/${artist.id}"
                    FilterChip(
                        selected = selected == key,
                        onClick = { selected = if (selected == key) null else key },
                        label = { Text(artist.name) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (availableTags.size > 1) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
            Spacer(Modifier.height(4.dp))
        }

        // A third stacked chip row here would be too much chrome on top of the artist and
        // tag rows above, so this is one compact line rather than a LazyRow of its own (#91).
        if (r.shows.isNotEmpty() || r.tracks.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Box {
                    TextButton(onClick = { sortMenuOpen = true }) {
                        Text("Sort: ${sortMode.label}")
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                        SearchSortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = { sortMode = mode; sortMenuOpen = false },
                            )
                        }
                    }
                }
            }
        }

        if (r.isEmpty) {
            val message = if (results.failed.isNotEmpty()) {
                "Couldn't search " + results.failed.joinToString(" or ") { backend ->
                    when (backend) {
                        Backend.PHISHIN -> "Phish"
                        Backend.RELISTEN -> "Relisten"
                        Backend.YOUTUBE -> "YouTube"
                    }
                } + "."
            } else {
                "Nothing matched."
            }
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            return
        }

        LazyColumn(Modifier.testTag(A11yTags.SEARCH_RESULTS)) {
            if (r.artists.isNotEmpty()) {
                item {
                    Column(Modifier.testTag(A11yTags.SEARCH_SECTION_ARTISTS)) {
                        SectionHeader("Artists")
                        r.artists.forEach { artist ->
                            RowItem(
                                title = artist.name,
                                subtitle = if (artist.showCount > 0) "${artist.showCount} ${plural(artist.showCount, "show")}" else null,
                                artUrl = null,
                                onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                            )
                        }
                    }
                }
            }
            if (r.shows.isNotEmpty()) {
                item {
                    Column(Modifier.testTag(A11yTags.SEARCH_SECTION_SHOWS)) {
                        SectionHeader("Shows")
                        r.shows.sortedByMode(sortMode).forEach { show ->
                    RowItem(
                        title = show.date,
                        subtitle = listOfNotNull(
                            if (show.artist.backend != Backend.PHISHIN) show.artist.name else null,
                            show.where.ifBlank { null },
                        ).joinToString(" · "),
                        artUrl = show.artUrl,
                        tags = show.tags,
                        onTagClick = { tag -> selectedTag = tag },
                        show = show,
                        onClick = {
                            when (show.artist.backend) {
                                Backend.PHISHIN -> nav.navigate("show/${show.date}")
                                Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                                // Search hits are tape shows; YouTube has no term search.
                                Backend.YOUTUBE -> Unit
                            }
                        }
                    )
                }
                    }
                }
            }
            SliceKind.entries.forEach { kind ->
                val slices = r.slices.filter { it.kind == kind }
                if (slices.isNotEmpty()) {
                    item { SectionHeader(kind.heading) }
                    items(slices, key = { "${kind.name}-${it.artist.backend.id}-${it.artist.id}-${it.period.id}" }) { slice ->
                        RowItem(
                            title = slice.period.label,
                            subtitle = if (slice.period.showCount > 0) {
                                "${slice.artist.name} · ${slice.period.showCount} ${plural(slice.period.showCount, "show")}"
                            } else {
                                slice.artist.name
                            },
                            artUrl = null,
                            onClick = {
                                val encodedPeriod = android.net.Uri.encode(slice.period.id)
                                val encodedLabel = android.net.Uri.encode(slice.period.label)
                                nav.navigate("artist/${slice.artist.backend.id}/${slice.artist.id}/$encodedPeriod?label=$encodedLabel")
                            }
                        )
                    }
                }
            }
            if (r.playlists.isNotEmpty()) {
                item { SectionHeader("Playlists") }
                items(r.playlists, key = { "pl-${it.slug}" }) { PlaylistRow(it, nav) }
            }
            if (r.tracks.isNotEmpty()) {
                item {
                    Column(Modifier.testTag(A11yTags.SEARCH_SECTION_TRACKS)) {
                        SectionHeader("Tracks")
                        r.tracks.sortedByMode(sortMode).forEach { track ->
                    RowItem(
                        title = track.title,
                        subtitle = listOfNotNull(
                            track.showDate, track.venueName, track.venueLocation
                        ).joinToString(" · "),
                        artUrl = track.showAlbumCoverUrl,
                        tags = track.tags.map { it.toTagRef() },
                        onTagClick = { tag -> selectedTag = tag },
                        trailing = fmt(track.duration),
                        trailingContent = {
                            LikeButton(
                                Likable.Track, track.id,
                                track.likedByUser, track.likesCount,
                            )
                        },
                        onClick = { vm.playTrack(track) }
                    )
                }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pieces

@Composable
internal fun ResumeBanner(progress: Progress, onResume: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onResume)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        Spacer(Modifier.width(10.dp))
        Text(
            "Resume “${progress.trackTitle}” at ${fmt(progress.positionMs)}",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** Navigates to whatever a queue key points at. The optional context only matters for
 *  YouTube queues (#234), which are keyed by video id alone — the title/thumb/artist that
 *  the player route wants come from whichever progress row the caller has on hand. */
internal fun openQueueKey(
    key: String,
    nav: NavHostController,
    trackTitle: String? = null,
    artUrl: String? = null,
    artistName: String? = null,
) {
    val ref = parseQueueKey(key) ?: return
    when (ref.kind) {
        QueueKind.PLAYLIST -> nav.navigate("playlist/${ref.id}")
        QueueKind.SHOW -> nav.navigate("show/${ref.id}")
        QueueKind.RECORDING -> {
            val rec = parseRecordingId(ref.id) ?: return
            nav.navigate("recording/${Backend.RELISTEN.id}/${rec.artistSlug}/${rec.date}?src=${rec.sourceId}")
        }
        QueueKind.LOCAL_PLAYLIST -> nav.navigate("local-playlist/${ref.id}")
        QueueKind.YOUTUBE -> nav.navigate(
            youtubeRoute(ref.id, trackTitle, artUrl, artistName, artistId = "")
        )
    }
}

internal fun openQueue(progress: Progress, nav: NavHostController) =
    openQueueKey(progress.queueKey, nav)

/**
 * Heart plus count. Owns its own state so a row updates immediately, and rolls back if the
 * request fails rather than showing a like that didn't happen. Signed out it still shows
 * the count, since that's public, but tapping is inert.
 */
@Composable
internal fun LikeButton(
    type: Likable,
    id: Long,
    initiallyLiked: Boolean,
    initialCount: Int,
    modifier: Modifier = Modifier,
    iconSize: Dp = 18.dp,
) {
    val signedIn by Session.username.collectAsState()
    var liked by remember(id) { mutableStateOf(initiallyLiked) }
    var count by remember(id) { mutableIntStateOf(initialCount) }
    var busy by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (signedIn == null) Modifier else Modifier.clickable(enabled = !busy) {
                    val wasLiked = liked
                    liked = !wasLiked
                    count += if (wasLiked) -1 else 1
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            if (wasLiked) PhishInApi.unlike(type, id) else PhishInApi.like(type, id)
                        }
                        if (result.isFailure) {
                            liked = wasLiked
                            count += if (wasLiked) 1 else -1
                        }
                        busy = false
                    }
                }
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Icon(
            if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (liked) "Unlike" else "Like",
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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

@Composable
private fun MiniPlayer(state: PlayerState, vm: PlayerViewModel, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(ledger.cardSurface)
            .border(1.dp, ledger.panelBorder)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { nav.navigate("player") }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!state.artUrl.isNullOrEmpty()) {
                ShowArtwork(
                    artUrl = state.artUrl,
                    contentDescription = "Open ${state.queueTitle}",
                    artistName = state.artistName.ifEmpty { if (state.backend == Backend.PHISHIN.id) PHISH.name else null },
                    date = state.showDate,
                    venue = state.venueName,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                CoverArtPlaceholder(
                    modifier = Modifier.size(40.dp),
                    cornerRadius = 8.dp
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.trackTitle.ifEmpty { "Not Playing" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ledger.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when {
                    state.showDate.isNotEmpty() && state.artistName.isNotEmpty() -> "${state.showDate} · ${state.artistName} · ${state.audioFormat}"
                    state.showDate.isNotEmpty() -> "${state.showDate} · ${state.audioFormat}"
                    state.showTitle.isNotEmpty() -> "${state.showTitle} · ${state.audioFormat}"
                    else -> state.queueTitle
                }
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = ledger.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(40.dp)) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = ledger.textPrimary,
                    )
                } else {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = ledger.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            IconButton(onClick = { vm.next() }, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = ledger.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        if (state.durationMs > 0) {
            val fraction = (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ledger.textPrimary.copy(alpha = 0.12f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(ledger.specGradient)
                )
            }
        }
    }
}

@Composable
fun LedgerBottomBar(currentRoute: String?, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ledger.cardSurface)
            .border(1.dp, ledger.panelBorder)
            .navigationBarsPadding()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isHome = currentRoute == "home"
        val isSearch = currentRoute in listOf("artists", "search")
        val isLibrary = currentRoute in listOf("library", "local-playlists", "playlists", "mine/shows", "mine/tracks", "mine/playlists")
        val isSettings = currentRoute in listOf("settings", "sync", "login", "scan")

        BottomNavItem(
            label = "Home",
            icon = Icons.Default.Home,
            selected = isHome,
            testTag = A11yTags.NAV_HOME,
            onClick = {
                if (!isHome) {
                    nav.navigate("home") {
                        popUpTo("home") { inclusive = true }
                    }
                }
            }
        )
        BottomNavItem(
            label = "Search",
            icon = Icons.Default.Search,
            selected = isSearch,
            testTag = A11yTags.NAV_SEARCH,
            onClick = {
                if (!isSearch) {
                    nav.navigate("search")
                }
            }
        )
        BottomNavItem(
            label = "Library",
            icon = Icons.Default.Layers,
            selected = isLibrary,
            testTag = A11yTags.NAV_LIBRARY,
            onClick = {
                if (!isLibrary) {
                    nav.navigate("library")
                }
            }
        )
        BottomNavItem(
            label = "Settings",
            icon = Icons.Default.Tune,
            selected = isSettings,
            testTag = A11yTags.NAV_SETTINGS,
            onClick = {
                if (!isSettings) {
                    nav.navigate("settings")
                }
            }
        )
    }
}

@Composable
private fun BottomNavItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val ledger = LocalLedgerColors.current
    val color = if (selected) ledger.accentIcon else ledger.textSubtle
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = color
        )
    }
}

@Composable
fun Header(
    title: String,
    nav: NavHostController,
    /** Slot for a per-screen control, e.g. ArtistScreen's favorite star. */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
        }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
        FeedbackButton(nav)
        CastButton()
        // Back only unwinds one step; from a playlist four levels deep that's tedious.
        IconButton(onClick = { nav.popBackStack("home", inclusive = false) }) {
            Icon(Icons.Default.Home, "Home")
        }
    }
}

/**
 * Section heading. [divided] draws a rule above it so the home screen's sections read as
 * distinct blocks rather than one continuous list; the first section on a screen omits it.
 */
@Composable
internal fun SectionHeader(text: String, divided: Boolean = false) {
    Column {
        if (divided) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
        }
        Text(
            text.uppercase(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        )
    }
}

@Composable
internal fun RowItem(
    title: String,
    subtitle: String? = null,
    artUrl: String? = null,
    tags: List<TagRef> = emptyList(),
    onTagClick: ((String) -> Unit)? = null,
    trailing: String? = null,
    /** A second, dimmer line under [trailing] — e.g. History's "last played" timestamp. */
    trailingSecondary: String? = null,
    /** Slot for a control that isn't part of the row's own click target, e.g. a heart. */
    trailingContent: (@Composable () -> Unit)? = null,
    show: ShowSummary? = null,
    artistName: String? = null,
    date: String? = null,
    venue: String? = null,
    showArtwork: Boolean = (artUrl != null || show != null || (date != null && artistName != null)),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = if (trailingContent != null) 4.dp else 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Rows without artwork (playlists, account actions) shouldn't reserve the slot.
        if (showArtwork) {
            ShowArtwork(
                artUrl = artUrl ?: show?.artUrl,
                show = show,
                artistName = artistName ?: show?.artist?.name,
                date = date ?: show?.date ?: title.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) },
                venue = venue ?: show?.venue ?: subtitle?.ifBlank { null },
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    tags.sortedByDescending { it.priority }.take(2).forEach { tag ->
                        TagBadge(tag, onClick = if (onTagClick != null) { { onTagClick(tag.name) } } else null)
                    }
                }
            }
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (trailing != null) {
            Column(horizontalAlignment = Alignment.End) {
                Text(trailing, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (trailingSecondary != null) {
                    Text(trailingSecondary, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
        trailingContent?.invoke()
    }
}

@Composable
internal fun Loading() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorText(t: Throwable) {
    Text(
        "Couldn't load: ${t.message}",
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(16.dp)
    )
}

/** Collapses the load/loading/error scaffold every detail screen repeats: nothing while
 *  [result] is in flight (null), [ErrorText] on failure, [content] once it resolves. */
@Composable
internal fun <T> Loaded(result: Result<T>?, content: @Composable (T) -> Unit) {
    when (result) {
        null -> Loading()
        else -> result.fold(onSuccess = { content(it) }, onFailure = { ErrorText(it) })
    }
}

/** [Loaded]'s form for a section embedded in a longer [androidx.compose.foundation.lazy.LazyListScope.item]
 *  list, which can't wrap itself in its own [Loading]/[ErrorText] outside the list. */
private fun <T> androidx.compose.foundation.lazy.LazyListScope.loaded(
    result: Result<T>?,
    content: androidx.compose.foundation.lazy.LazyListScope.(T) -> Unit,
) {
    when (result) {
        null -> item { Loading() }
        else -> result.fold(onSuccess = { content(it) }, onFailure = { item { ErrorText(it) } })
    }
}

internal fun <T> androidx.compose.foundation.lazy.LazyListScope.loadedWithRetry(
    result: Result<T>?,
    onRetry: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.(T) -> Unit,
) {
    when (result) {
        null -> item { Loading() }
        else -> result.fold(
            onSuccess = { content(it) },
            onFailure = { 
                item { 
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        ErrorText(it)
                        Button(onClick = onRetry) {
                            Text("Retry")
                        }
                    }
                } 
            }
        )
    }
}

// ---------------------------------------------------------------- helpers

/**
 * Debounced search across every backend. produceState cancels the previous coroutine
 * whenever [term] changes, so the delay collapses a burst of keystrokes into one request.
 * [searchAll] already degrades per backend on failure, so this doesn't need a `runCatching`
 * of its own — [SearchHits.failed] carries what didn't answer.
 */
@Composable
internal fun searchFor(term: String): State<SearchHits?> =
    produceState<SearchHits?>(initialValue = null, key1 = term) {
        if (term.length < 3) {
            value = null
            return@produceState
        }
        value = null
        delay(300)
        value = searchAll(term)
    }

/** Runs [block] once per [key], exposing null while in flight. */
@Composable
internal fun <T> loadOnce(key: Any = Unit, block: suspend () -> T): State<Result<T>?> =
    produceState<Result<T>?>(initialValue = null, key1 = key) {
        value = runCatching { block() }
    }
