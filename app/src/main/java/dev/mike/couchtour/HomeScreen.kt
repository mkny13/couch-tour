package dev.mike.couchtour

import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(vm: PlayerViewModel, nav: NavHostController) {
    val rawArtists = loadOnce { loadArtistsByBackend() }
    val favoriteKeys by Favorites.keys.collectAsState()
    // A derived merge rather than part of loadOnce's cached fetch: it must re-run whenever
    // the user favorites/unfavorites an artist, not just once per screen load.
    val artists = remember(rawArtists.value, favoriteKeys) {
        rawArtists.value?.map { mergeArtists(it, favoriteKeys) }
    }
    val recent by vm.progressDao.inProgress().collectAsState(initial = emptyList())
    val historyCount by vm.progressDao.historyCount().collectAsState(initial = 0)
    val username by Session.username.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val term = query.trim()
    val results = searchFor(term)

    Column(Modifier.fillMaxSize()) {
        val favoritedArtists = artists?.getOrNull()?.filter { it.key in favoriteKeys }.orEmpty()
        val preferences by vm.artistTourPreferenceDao.getAllPreferences().collectAsState(initial = emptyList())
        val preferencesMap = remember(preferences) { preferences.associateBy { it.artistKey } }
        var tourPickerArtist by remember { mutableStateOf<ArtistRef?>(null) }
        var focusedArtistKey by rememberSaveable { mutableStateOf<String?>(null) }

        LaunchedEffect(favoriteKeys) {
            if (focusedArtistKey != null && focusedArtistKey !in favoriteKeys) {
                focusedArtistKey = null
            }
        }

        // A second, independent load rather than part of loadArtistsByBackend's: it is a
        // multi-request walk of the favorited artists' catalogs (#13), far slower than the
        // artist list, and the screen's first paint must not wait on it. Keyed on the date and
        // the favorites, which is exactly what the answer depends on.
        val today = remember { java.time.LocalDate.now() }
        val dateHeader = remember(today) {
            today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", java.util.Locale.US)).uppercase()
        }
        val ledger = LocalLedgerColors.current
        val todayStr = remember(today) { today.toString() }
        var onThisDateRetry by remember { mutableIntStateOf(0) }
        val onThisDate = loadOnce(Triple(todayStr, favoritedArtists.map { it.key }, onThisDateRetry)) {
            OnThisDate.load(favoritedArtists, todayStr)
        }
        var nextStopRetry by remember { mutableIntStateOf(0) }
        val nextStopKey = remember(todayStr, favoritedArtists.map { it.key }, preferencesMap, nextStopRetry) {
            listOf(todayStr, favoritedArtists.map { it.key }, preferencesMap, nextStopRetry)
        }
        val nextStopShows = loadOnce(nextStopKey) {
            NextStop.load(favoritedArtists, todayStr, preferencesMap)
        }
        val finishedKeys by vm.progressDao.finishedKeys().collectAsState(initial = emptyList())
        val nextStop = remember(nextStopShows.value, finishedKeys, focusedArtistKey) {
            val loadedShows = nextStopShows.value?.getOrNull().orEmpty()
            val candidates = focusedCandidates(loadedShows, focusedArtistKey)
            oldestUnplayed(candidates, playedShowIds(finishedKeys))
        }


        LazyColumn(Modifier.fillMaxSize()) {
            // Date header + "Surprise me" chip
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = dateHeader,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.6.sp,
                        color = ledger.textSubtle,
                        modifier = Modifier.weight(1f)
                    )
                    FeedbackButton(
                        nav = nav,
                        modifier = Modifier.size(36.dp),
                        iconSize = 20.dp,
                        tint = ledger.textMuted
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    SurpriseMeChip(surpriseMeArtists(favoritedArtists, artists?.getOrNull().orEmpty()), nav)
                }
            }

            // IN PROGRESS section
            if (recent.isNotEmpty()) {
                item {
                    Column(Modifier.testTag(A11yTags.HOME_SECTION_IN_PROGRESS)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { nav.navigate("history") }
                            ) {
                                Text(
                                    text = "IN PROGRESS",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.2.sp,
                                    color = ledger.textMuted
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "History",
                                    tint = ledger.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = "${recent.size} of $historyCount",
                                fontSize = 12.sp,
                                color = ledger.accentIcon,
                                modifier = Modifier.clickable { nav.navigate("history") }
                            )
                        }
                        GradientHairline(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                        )
                        recent.take(4).forEach { p ->
                            InProgressLedgerRow(p, vm, nav, Modifier.testTag(A11yTags.homeInProgressRow(p.queueKey)))
                        }
                    }
                }
            }

            // NEXT TOUR STOP Card
            if (nextStop != null || favoritedArtists.isNotEmpty()) {
                val show = nextStop
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ledger.cardSurface)
                            .border(1.dp, ledger.panelBorder, RoundedCornerShape(10.dp))
                            .testTag(A11yTags.HOME_SECTION_NEXT_TOUR_STOPS)
                    ) {
                        Column {
                            // Top Amber/Pink hairline
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(Color(0xFFF2A93B), Color(0xFFF06BB0), Color.Transparent)
                                        )
                                    )
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, top = 10.dp, end = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "NEXT TOUR STOP",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.2.sp,
                                    color = ledger.textMuted
                                )
                                Spacer(Modifier.weight(1f))
                                val targetPickerArtist = favoritedArtists.firstOrNull { it.key == focusedArtistKey }
                                    ?: show?.artist
                                    ?: favoritedArtists.firstOrNull()
                                if (targetPickerArtist != null) {
                                    Text(
                                        text = "Change tour…",
                                        fontSize = 12.sp,
                                        color = ledger.textSubtle,
                                        modifier = Modifier.clickable { tourPickerArtist = targetPickerArtist }
                                    )
                                }
                            }
                            if (favoritedArtists.isNotEmpty()) {
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(favoritedArtists, key = { it.key }) { artist ->
                                        val selected = (focusedArtistKey ?: show?.artist?.key) == artist.key
                                        Box(
                                            modifier = Modifier
                                                .height(26.dp)
                                                .clip(RoundedCornerShape(13.dp))
                                                .background(
                                                    if (selected) Color(0x299184D9) else Color.Transparent
                                                )
                                                .border(
                                                    1.dp,
                                                    if (selected) ledger.accentIcon else ledger.controlOutline,
                                                    RoundedCornerShape(13.dp)
                                                )
                                                .clickable { focusedArtistKey = if (focusedArtistKey == artist.key) null else artist.key }
                                                .padding(horizontal = 11.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = artist.name,
                                                fontSize = 12.sp,
                                                color = if (selected) ledger.accentTintText else ledger.textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                            val nextStopError = nextStopShows.value?.isFailure == true
                            if (nextStopError) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Couldn't load tour stops.",
                                        fontSize = 12.sp,
                                        color = ledger.textSubtle,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    Button(onClick = { nextStopRetry++ }) {
                                        Text("Retry")
                                    }
                                }
                            } else if (focusedArtistKey != null && show == null) {
                                val artistName = favoritedArtists.firstOrNull { it.key == focusedArtistKey }?.name.orEmpty()
                                Text(
                                    text = "Nothing to catch up on $artistName.",
                                    fontSize = 12.sp,
                                    color = ledger.textSubtle,
                                    modifier = Modifier.padding(start = 14.dp, bottom = 12.dp)
                                )
                            }
                            if (show != null && !nextStopError) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .testTag(A11yTags.homeNextTourStopRow("${show.artist.key}-${show.date}")),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                when (show.artist.backend) {
                                                    Backend.PHISHIN -> nav.navigate("show/${show.date}")
                                                    Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                                                    // Home's in-progress rows are tape shows;
                                                    // YouTube artists have none.
                                                    Backend.YOUTUBE -> Unit
                                                }
                                            }
                                    ) {
                                        Text(
                                            text = show.artist.name,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = ledger.textPrimary
                                        )
                                        Text(
                                            text = show.date,
                                            fontSize = 14.sp,
                                            color = ledger.textSecondary,
                                            modifier = Modifier.padding(top = 1.dp)
                                        )
                                        val subtitle = listOfNotNull(
                                            show.where.ifBlank { null },
                                            show.tourName
                                        ).joinToString(" · ")
                                        Text(
                                            text = subtitle,
                                            fontSize = 12.sp,
                                            color = ledger.textSubtle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (show.rating > 0.0) {
                                        Text(
                                            text = "★ ${"%.1f".format(java.util.Locale.US, show.rating)}",
                                            fontSize = 12.sp,
                                            color = ledger.ratingAmber,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                    CircularPlayButton(
                                        isPlaying = false,
                                        onClick = { vm.playNextTourStop(show) },
                                        size = 34.dp,
                                        iconSize = 16.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ON THIS DATE section
            loadedWithRetry(onThisDate.value, onRetry = { onThisDateRetry++ }) { shows ->
                if (shows.isNotEmpty()) {
                    item {
                        Column(Modifier.testTag(A11yTags.HOME_SECTION_ON_THIS_DATE)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ON THIS DATE",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.2.sp,
                                    color = ledger.textMuted
                                )
                                Text(
                                    text = "${shows.size} ${plural(shows.size, "show")}",
                                    fontSize = 12.sp,
                                    color = ledger.textSubtle
                                )
                            }
                            GradientHairline(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                            )
                            shows.forEach { show ->
                                OnThisDateLedgerRow(show, nav, Modifier.testTag(A11yTags.homeOnThisDateRow("${show.artist.key}-${show.date}")))
                            }
                        }
                    }
                }
            }

            // Separate from the full "Artists" list below (Relisten parity, #14) — quick
            // access to the bands the user cares about, rather than scrolling a merged list
            // that can run to hundreds of entries. Favoriting doesn't remove an artist from
            // that full list, so it still shows up in both places.
            if (favoritedArtists.isNotEmpty()) {
                item {
                    Column(Modifier.testTag(A11yTags.FAVORITES_LIST)) {
                        SectionHeader("Favorites", divided = true)
                        favoritedArtists.forEach { artist ->
                            RowItem(
                                title = artist.name,
                                subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                                artUrl = null,
                                modifier = Modifier.testTag(A11yTags.favoritesRow(artist.key)),
                                onClick = { nav.navigate("artist/${artist.backend.id}/${artist.id}") }
                            )
                        }
                    }
                }
            }

            item { SectionHeader("Artists", divided = true) }
            item {
                RowItem(
                    title = "Browse artists",
                    subtitle = artists?.getOrNull()?.let { "${it.size} ${plural(it.size, "artist")} on phish.in and Relisten" }
                        ?: "Explore artists on phish.in and Relisten",
                    artUrl = null,
                    modifier = Modifier.testTag(A11yTags.HOME_BROWSE_ARTISTS),
                    onClick = { nav.navigate("artists") }
                )
            }

            item { SectionHeader("Your phish.in account", divided = true) }
            if (username == null) {
                item {
                    RowItem(
                        title = "Log in",
                        subtitle = "See your saved shows, tracks, and playlists",
                        artUrl = null,
                        onClick = { nav.navigate("login") }
                    )
                }
            } else {
                item {
                    RowItem("My shows", "Shows you've liked", null) { nav.navigate("mine/shows") }
                }
                item {
                    RowItem("My tracks", "Tracks you've liked", null) { nav.navigate("mine/tracks") }
                }
                item {
                    RowItem("My playlists", "Created by you and liked", null) {
                        nav.navigate("mine/playlists")
                    }
                }
                item {
                    RowItem("Signed in as $username", "Tap to log out", null) { Session.logout() }
                }
            }
            item {
                RowItem("Browse playlists", "Public playlists on phish.in", null) {
                    nav.navigate("playlists")
                }
            }
            item {
                // Account-free (#12), so it sits outside the phish.in-account section above —
                // and isn't titled "My playlists" too, which that section's row already is.
                RowItem("Local playlists", "Mix tracks from any artist, saved on this device", null) {
                    nav.navigate("local-playlists")
                }
            }

            item { SectionHeader("Playback", divided = true) }
            item {
                val skipFiller by PlaybackSettings.skipFiller.collectAsState()
                RowItem(
                    title = "Skip filler tracks",
                    subtitle = "Skip intros, tuning, and banter during playback",
                    artUrl = null,
                    trailingContent = {
                        Switch(
                            checked = skipFiller,
                            onCheckedChange = { PlaybackSettings.setSkipFiller(it) },
                        )
                    },
                    onClick = { PlaybackSettings.toggle() },
                )
            }

            item { SectionHeader("Appearance", divided = true) }
            item {
                val currentTheme by ThemeSettings.themeMode.collectAsState()
                var showThemeDialog by rememberSaveable { mutableStateOf(false) }
                RowItem(
                    title = "Theme",
                    subtitle = when (currentTheme) {
                        ThemeMode.AUTO -> "Auto (system default)"
                        ThemeMode.LIGHT -> "Light"
                        ThemeMode.DARK -> "Dark"
                    },
                    artUrl = null,
                    onClick = { showThemeDialog = true },
                )
                if (showThemeDialog) {
                    ThemePickerDialog(
                        currentMode = currentTheme,
                        onDismiss = { showThemeDialog = false },
                        onSelect = {
                            ThemeSettings.setThemeMode(it)
                            showThemeDialog = false
                        },
                    )
                }
            }

            item { SectionHeader("Sync", divided = true) }
            item {
                val paired by SyncSession.paired.collectAsState()
                RowItem(
                    title = "Sync across devices",
                    subtitle = if (paired) "Paired — manage devices" else "Not paired",
                    artUrl = null,
                    onClick = { nav.navigate("sync") }
                )
            }

            // Diagnostic detail, not a feature — small, muted, and at the literal bottom of
            // the scrollable list so it never competes with the content above (#43).
            item {
                Text(
                    "Couch Tour ${BuildConfig.VERSION_NAME}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 16.dp)
                )
            }
        }

        tourPickerArtist?.let { artist ->
            TourPickerDialog(
                artist = artist,
                currentPreference = preferencesMap[artist.key] ?: preferencesMap[artist.id],
                onDismiss = { tourPickerArtist = null },
                onSave = { tour, yr ->
                    vm.setArtistTourPreference(artist.key, tour, yr)
                    tourPickerArtist = null
                },
                onClear = {
                    vm.clearArtistTourPreference(artist.key)
                    tourPickerArtist = null
                }
            )
        }
    }
}

@Composable
private fun SurpriseMeChip(artists: List<ArtistRef>, nav: NavHostController) {
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ledger = LocalLedgerColors.current

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color.Transparent)
            .border(1.dp, ledger.controlOutline, RoundedCornerShape(13.dp))
            .clickable(enabled = !busy && artists.isNotEmpty()) {
                busy = true
                scope.launch {
                    runCatching { pickRandomShow(artists) }
                        .onSuccess { show ->
                            when (show.artist.backend) {
                                Backend.PHISHIN -> nav.navigate("show/${show.date}")
                                Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                                // Surprise me draws from the tape backends; YouTube
                                // artists have no shows, so this can't come up.
                                Backend.YOUTUBE -> Unit
                            }
                        }
                    busy = false
                }
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Shuffle,
                contentDescription = null,
                tint = ledger.accentIcon,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (busy) "Finding…" else "Surprise me",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = ledger.textSecondary
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InProgressLedgerRow(progress: Progress, vm: PlayerViewModel, nav: NavHostController, modifier: Modifier = Modifier) {
    val ledger = LocalLedgerColors.current
    val playerState by vm.state.collectAsState()
    val isCurrentlyPlaying = playerState.hasQueue && playerState.queueKey == progress.queueKey
    val fraction = if (isCurrentlyPlaying && playerState.durationMs > 0) {
        (playerState.positionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else if (progress.positionMs > 0) {
        // When duration is unknown from offline progress, estimate reasonable progress based on position
        (progress.positionMs.toFloat() / (progress.positionMs + 300_000L).toFloat()).coerceIn(0.1f, 0.95f)
    } else {
        0.05f
    }
    var menuOpen by remember { mutableStateOf(false) }
    val isPlaylist = progress.queueKey.startsWith("playlist:") || progress.queueKey.startsWith("local-playlist:")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { openQueue(progress, nav) },
                onLongClick = { menuOpen = true }
            )
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = progress.artist.ifBlank { "Phish" },
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = ledger.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 136.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatShowDate(progress.title),
                fontSize = 15.sp,
                color = ledger.textSecondary,
                maxLines = 1
            )
            Text(
                text = progress.trackTitle,
                fontSize = 14.sp,
                color = ledger.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 12.dp)
            )
            CircularPlayButton(
                isPlaying = isCurrentlyPlaying && playerState.isPlaying,
                onClick = {
                    if (isCurrentlyPlaying) {
                        vm.togglePlayPause()
                    } else {
                        vm.resume(progress)
                    }
                },
                size = 30.dp,
                iconSize = 15.dp
            )
        }
        // Bottom 2px progress bar overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .align(Alignment.BottomCenter)
                .background(ledger.textPrimary.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(2.dp)
                    .background(Color(0xFFF06BB0))
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Resume playback") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    vm.resume(progress)
                }
            )
            DropdownMenuItem(
                text = { Text(if (isPlaylist) "Open playlist" else "Open show") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    openQueue(progress, nav)
                }
            )
            DropdownMenuItem(
                text = { Text("Mark completed") },
                leadingIcon = { Icon(Icons.Default.Check, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    vm.markCompleted(progress)
                }
            )
            DropdownMenuItem(
                text = { Text("Remove from In Progress") },
                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    vm.dismiss(progress)
                }
            )
            DropdownMenuItem(
                text = { Text("Delete from history") },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    vm.forget(progress)
                }
            )
        }
    }
}

@Composable
private fun OnThisDateLedgerRow(show: ShowSummary, nav: NavHostController, modifier: Modifier = Modifier) {
    val ledger = LocalLedgerColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    when (show.artist.backend) {
                        Backend.PHISHIN -> nav.navigate("show/${show.date}")
                        Backend.RELISTEN -> nav.navigate("recording/relisten/${show.artist.id}/${show.date}")
                        // On-this-date rows are tape shows; YouTube artists have none.
                        Backend.YOUTUBE -> Unit
                    }
                }
                .padding(horizontal = 20.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val year = show.date.take(4)
                    Text(
                        text = year,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ledger.textPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = show.artist.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = ledger.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val venueLocation = show.where.ifBlank { null }
                if (venueLocation != null) {
                    Text(
                        text = venueLocation,
                        fontSize = 12.sp,
                        color = ledger.textSubtle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            if (show.rating > 0.0) {
                Text(
                    text = "★ ${"%.1f".format(java.util.Locale.US, show.rating)}",
                    fontSize = 12.sp,
                    color = ledger.ratingAmber,
                    modifier = Modifier.padding(start = 8.dp)
                )
            } else if (show.likesCount > 0) {
                Text(
                    text = "♥ ${show.likesCount}",
                    fontSize = 12.sp,
                    color = Color(0xFFF06BB0),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            thickness = 1.dp,
            color = ledger.listDivider
        )
    }
}
