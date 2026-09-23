package dev.mike.couchtour

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * The Google TV browse hierarchy (#225 Part 2.1, #226 Part 2.2 of #9): artist, artist's
 * years, a year's shows, and a show's tracks, rendered with Compose for TV
 * (`androidx.tv.material3` widgets, laid out with plain `androidx.compose.foundation.lazy.*`
 * containers — the pinned `androidx.tv:tv-foundation:1.0.0` stable release shipped its
 * `TvLazyColumn`/`TvLazyRow`/`TvLazyVerticalGrid` wrappers only in earlier alphas and
 * dropped them before 1.0.0, so standard `LazyColumn`/`LazyRow`/`LazyVerticalGrid` plus
 * tv-material3's own focus-aware components are what Compose for TV actually ships today)
 * rather than Leanback Views. Playback stays out (Part 3): selecting a track is a stub.
 *
 * The grouping, years, shows, and tracks all ride the exact seams the phone app and Android
 * Auto already use — [groupArtistsForBrowse] for the phish/favorited/everyone-else split,
 * [MusicSource.periods]/[MusicSource.shows]/[MusicSource.show] for the rest — so the TV
 * never grows a second catalog path to drift from the first two.
 */

/** The three browse sections the TV artist level shows, in this order. */
internal enum class TvArtistSection(val heading: String) {
    PHISH("Phish"),
    FAVORITES("Favorites"),
    ALL("All artists"),
}

internal data class TvArtistSectionData(val section: TvArtistSection, val artists: List<ArtistRef>)

/**
 * Flattens [ArtistGroups] into TV sections. [ArtistGroups.favorited] and
 * [ArtistGroups.others] arrive already show-count-sorted from [groupArtistsForBrowse]'s
 * callers' convention ([mergeArtists]), but the sort is repeated here so the TV's ordering
 * doesn't depend on what the caller remembered to do. Empty sections are dropped — a
 * heading with nothing under it is a dead focus lane on a D-pad.
 */
internal fun tvArtistSections(groups: ArtistGroups): List<TvArtistSectionData> = buildList {
    groups.phish?.let { add(TvArtistSectionData(TvArtistSection.PHISH, listOf(it))) }
    if (groups.favorited.isNotEmpty()) {
        add(TvArtistSectionData(TvArtistSection.FAVORITES, groups.favorited.sortedByDescending { it.showCount }))
    }
    if (groups.others.isNotEmpty()) {
        add(TvArtistSectionData(TvArtistSection.ALL, groups.others.sortedByDescending { it.showCount }))
    }
}

/** One card on the TV years grid: a [PeriodRef] plus its count subtitle. */
internal data class TvYearItem(val period: PeriodRef, val subtitle: String)

/**
 * An artist's periods, sorted newest-first by label — the same string sort
 * [PlaybackService.artistPeriodsChildren] uses, so ranged labels ("1983-1987") and the
 * synthetic "Popular" period land in the same order on every surface ('P' > '9' keeps
 * "Popular" first with no pinning logic, per [POPULAR_PERIOD_ID]'s doc).
 */
internal fun tvYearItems(periods: List<PeriodRef>): List<TvYearItem> =
    periods.sortedByDescending { it.label }.map { period ->
        TvYearItem(
            period = period,
            subtitle = if (period.id == POPULAR_PERIOD_ID) POPULAR_PERIOD_SUBTITLE
            else "${period.showCount} ${plural(period.showCount, "show")}",
        )
    }

/** One card on the TV shows grid: a [ShowSummary] plus its venue/location subtitle. */
internal data class TvShowItem(val show: ShowSummary, val subtitle: String)

/**
 * A period's shows, newest-first — [ShowSortMode.DATE_DESC], the same default the phone's
 * [ArtistShowsScreen] opens with. TV has no sort/filter controls (out of scope per #226): a
 * D-pad grid is for browsing, not for the phone's tag-chip filtering.
 */
internal fun tvShowItems(shows: List<ShowSummary>): List<TvShowItem> =
    shows.sortedByMode(ShowSortMode.DATE_DESC).map { show ->
        TvShowItem(show = show, subtitle = show.where)
    }

/** One row on the TV track list: a [PlayableTrack] plus its 1-based position in the show. */
internal data class TvTrackRow(val position: Int, val track: PlayableTrack)

/** A contiguous run of [TvTrackRow]s sharing one set name ("Set 1", "Encore", or ""). */
internal data class TvTrackSection(val setName: String, val rows: List<TvTrackRow>)

/**
 * Groups a show's tracks into set sections, in their existing order — the same
 * adjacency-based grouping MainActivity's `groupedBySet` uses for the phone, reimplemented
 * as a pure function so it's testable without a Compose UI test (matching this file's
 * existing convention of pulling D-pad screens' data prep out into plain functions). A blank
 * [PlayableTrack.setName] (a Relisten tape without real sets, per [ArtistRef.hasSets])
 * collapses into one section with an empty name, which the UI reads as "skip the header"
 * rather than rendering a meaningless "Set" divider.
 */
internal fun tvTrackSections(tracks: List<PlayableTrack>): List<TvTrackSection> {
    val sections = mutableListOf<TvTrackSection>()
    tracks.forEachIndexed { index, track ->
        val position = index + 1
        val last = sections.lastOrNull()
        if (last != null && last.setName == track.setName) {
            sections[sections.lastIndex] = last.copy(rows = last.rows + TvTrackRow(position, track))
        } else {
            sections.add(TvTrackSection(track.setName, listOf(TvTrackRow(position, track))))
        }
    }
    return sections
}

/**
 * Runs [block] once per [key], exposing null while in flight — the phone's `loadOnce`
 * pattern, mirrored here rather than shared because that one is private to MainActivity.
 */
@Composable
private fun <T> tvLoadOnce(key: Any = Unit, block: suspend () -> T): State<Result<T>?> =
    produceState<Result<T>?>(initialValue = null, key1 = key) {
        value = runCatching { block() }
    }

/**
 * The four-level browse stack: artists, one artist's years, one year's shows, one show's
 * tracks. Back on the remote pops exactly one level via [BackHandler]; there is deliberately
 * no nav library here — four `remember`ed states are not a graph, and the phone's NavHost
 * assumes touch affordances a D-pad doesn't have. Each [BackHandler] is enabled only at the
 * level it pops, relying on the state forming a strict chain (a show is only ever set once a
 * period is, which is only ever set once an artist is) so at most one is active at a time.
 */
@Composable
fun TvBrowseScreen(vm: PlayerViewModel = viewModel()) {
    var selectedArtist by remember { mutableStateOf<ArtistRef?>(null) }
    var selectedPeriod by remember { mutableStateOf<PeriodRef?>(null) }
    var selectedShow by remember { mutableStateOf<ShowSummary?>(null) }
    var showNowPlaying by remember { mutableStateOf(false) }

    val artist = selectedArtist
    val period = selectedPeriod
    val show = selectedShow

    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }
    BackHandler(enabled = !showNowPlaying && show != null) { selectedShow = null }
    BackHandler(enabled = !showNowPlaying && show == null && period != null) { selectedPeriod = null }
    BackHandler(enabled = !showNowPlaying && show == null && period == null && artist != null) { selectedArtist = null }

    if (showNowPlaying) {
        TvNowPlayingScreen(vm = vm, onBack = { showNowPlaying = false })
    } else {
        when {
            artist == null -> TvArtistBrowseScreen(
                vm = vm,
                onArtistClick = { selectedArtist = it },
                onOpenNowPlaying = { showNowPlaying = true },
            )
            period == null -> TvYearBrowseScreen(
                artist = artist,
                vm = vm,
                onBack = { selectedArtist = null },
                onYearClick = { selectedPeriod = it },
                onOpenNowPlaying = { showNowPlaying = true },
            )
            show == null -> TvShowBrowseScreen(
                artist = artist,
                period = period,
                vm = vm,
                onBack = { selectedPeriod = null },
                onShowClick = { selectedShow = it },
                onOpenNowPlaying = { showNowPlaying = true },
            )
            else -> TvTrackListScreen(
                artist = artist,
                show = show,
                vm = vm,
                onBack = { selectedShow = null },
                onOpenNowPlaying = { showNowPlaying = true },
            )
        }
    }
}

@Composable
private fun TvArtistBrowseScreen(
    vm: PlayerViewModel,
    onArtistClick: (ArtistRef) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val perBackend by tvLoadOnce("artists") { loadArtistsByBackend() }
    val favoriteKeys by Favorites.keys.collectAsState()
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val inProgressRows by PhishInDb.get(context).progressDao().inProgress().collectAsState(initial = emptyList())
    val continueListeningItems = remember(inProgressRows) { tvContinueListeningItems(inProgressRows) }

    val sections = remember(perBackend, favoriteKeys) {
        perBackend?.getOrNull()?.let { backend ->
            tvArtistSections(groupArtistsForBrowse(backend, favoriteKeys))
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            perBackend == null -> Text("Loading the archive…")
            perBackend!!.isFailure -> Text(
                "Couldn't load the catalog.\nCheck the network and reopen Couch Tour.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> TvArtistSectionList(
                sections = sections.orEmpty(),
                continueListening = continueListeningItems,
                hasQueue = state.hasQueue,
                onArtistClick = onArtistClick,
                onResumeClick = { progress ->
                    vm.resume(progress)
                    onOpenNowPlaying()
                },
                onOpenNowPlaying = onOpenNowPlaying,
            )
        }
    }
}

@Composable
private fun TvArtistSectionList(
    sections: List<TvArtistSectionData>,
    continueListening: List<TvContinueListeningItem>,
    hasQueue: Boolean,
    onArtistClick: (ArtistRef) -> Unit,
    onResumeClick: (Progress) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (hasQueue) {
            item(key = "header-now-playing") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(onClick = onOpenNowPlaying) {
                        Text("▶ Now Playing")
                    }
                }
            }
        }
        if (continueListening.isNotEmpty()) {
            item(key = "heading-continue-listening") {
                Text(
                    text = "Continue listening",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "row-continue-listening") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(continueListening, key = { "progress-${it.progress.queueKey}" }) { item ->
                        TvCard(
                            title = item.title,
                            subtitle = item.subtitle,
                            onClick = { onResumeClick(item.progress) },
                        )
                    }
                }
            }
        }
        sections.forEach { section ->
            item(key = "heading-${section.section.name}") {
                Text(
                    text = section.section.heading,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item(key = "row-${section.section.name}") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(section.artists, key = { it.key }) { artist ->
                        TvCard(
                            title = artist.name,
                            subtitle = "${artist.showCount} ${plural(artist.showCount, "show")}",
                            onClick = { onArtistClick(artist) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvYearBrowseScreen(
    artist: ArtistRef,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onYearClick: (PeriodRef) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val periods by tvLoadOnce("periods-${artist.key}") {
        sourceFor(artist.backend).periods(artist)
    }
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = artist.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            // The remote's Back button does the same thing (BackHandler in TvBrowseScreen);
            // this exists because a visible, focusable way back reads better at the head of
            // a grid full of cards than a gesture the user has to already know about.
            Button(onClick = onBack) { Text("← All artists") }
            if (state.hasQueue) {
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onOpenNowPlaying) { Text("▶ Now Playing") }
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                periods == null -> Text("Loading years…")
                periods!!.isFailure -> Text(
                    "Couldn't load ${artist.name}'s years.\nCheck the network and try again.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val yearItems = remember(periods) {
                        periods!!.getOrNull()?.let { tvYearItems(it) }.orEmpty()
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(6),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(yearItems, key = { it.period.id }) { year ->
                            TvCard(
                                title = year.period.label,
                                subtitle = year.subtitle,
                                wide = false,
                                onClick = { onYearClick(year.period) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvShowBrowseScreen(
    artist: ArtistRef,
    period: PeriodRef,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onShowClick: (ShowSummary) -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val shows by tvLoadOnce("shows-${artist.key}-${period.id}") {
        sourceFor(artist.backend).shows(artist, period)
    }
    val state by vm.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${artist.name} · ${period.label}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Button(onClick = onBack) { Text("← Years") }
            if (state.hasQueue) {
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onOpenNowPlaying) { Text("▶ Now Playing") }
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                shows == null -> Text("Loading shows…")
                shows!!.isFailure -> Text(
                    "Couldn't load ${period.label}'s shows.\nCheck the network and try again.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val showItems = remember(shows) {
                        shows!!.getOrNull()?.let { tvShowItems(it) }.orEmpty()
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(showItems, key = { it.show.date }) { item ->
                            TvCard(
                                title = item.show.date,
                                subtitle = item.subtitle,
                                onClick = { onShowClick(item.show) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvTrackListScreen(
    artist: ArtistRef,
    show: ShowSummary,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit,
) {
    val detail by tvLoadOnce("show-${artist.key}-${show.date}") {
        sourceFor(artist.backend).show(artist, show.date)
    }
    val savedProgress by tvLoadOnce("progress-${show.date}") {
        vm.progressFor(showQueueKey(show.date))
    }
    val state by vm.state.collectAsState()
    val progress = savedProgress?.getOrNull()?.takeIf { !it.finished }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 48.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = show.date,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (show.where.isNotEmpty()) {
                    Text(
                        text = show.where,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onBack) { Text("← Shows") }
            if (progress != null && detail?.getOrNull() != null) {
                Button(onClick = {
                    detail?.getOrNull()?.let { d ->
                        vm.playShowDetail(d, startIndex = progress.trackIndex, startPositionMs = progress.positionMs)
                        onOpenNowPlaying()
                    }
                }) {
                    Text("▶ Resume (${progress.trackTitle})")
                }
            }
            if (state.hasQueue) {
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = onOpenNowPlaying) { Text("▶ Now Playing") }
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                detail == null -> Text("Loading tracks…")
                detail!!.isFailure -> Text(
                    "Couldn't load ${show.date}'s tracks.\nCheck the network and try again.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    val sections = remember(detail) {
                        detail!!.getOrNull()?.tracks?.let { tvTrackSections(it) }.orEmpty()
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        sections.forEach { section ->
                            if (section.setName.isNotEmpty()) {
                                item(key = "set-${section.setName}") {
                                    Text(
                                        text = section.setName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                                    )
                                }
                            }
                            items(section.rows, key = { "track-${it.track.id}" }) { row ->
                                TvTrackCard(row = row, onClick = {
                                    detail?.getOrNull()?.let { d ->
                                        val trackIndex = d.tracks.indexOfFirst { t -> t.id == row.track.id }.coerceAtLeast(0)
                                        vm.playShowDetail(d, startIndex = trackIndex)
                                        onOpenNowPlaying()
                                    }
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one card style the artist/year/show grids share: a tv-material3 [Card] sized for a
 * 10-foot read. Focus behavior (scale-up on focus) comes from CardDefaults — what makes this
 * comply with Compose for TV's material focus guidelines without hand-drawing highlight
 * borders.
 */
@Composable
private fun TvCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    wide: Boolean = true,
) {
    Card(
        onClick = onClick,
        modifier = if (wide) Modifier.width(200.dp) else Modifier.width(150.dp),
        scale = CardDefaults.scale(focusedScale = 1.1f),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(if (wide) 76.dp else 88.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One row on the track list: a full-width tv-material3 [Card] so a D-pad down/up move keeps
 * one focus lane, rather than the grids' left/right lanes — a list, not a shelf.
 */
@Composable
private fun TvTrackCard(row: TvTrackRow, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "${row.position}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp),
            )
            Text(
                text = row.track.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = fmt(row.track.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
