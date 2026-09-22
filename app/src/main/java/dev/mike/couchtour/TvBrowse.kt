package dev.mike.couchtour

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * The Google TV browse hierarchy (#225, Part 2.1 of #9): the artist level and the
 * artist→years level, rendered with Compose for TV (`androidx.tv.material3` widgets, laid
 * out with plain `androidx.compose.foundation.lazy.*` containers — the pinned
 * `androidx.tv:tv-foundation:1.0.0` stable release shipped its `TvLazyColumn`/`TvLazyRow`/
 * `TvLazyVerticalGrid` wrappers only in earlier alphas and dropped them before 1.0.0, so
 * standard `LazyColumn`/`LazyRow`/`LazyVerticalGrid` plus tv-material3's own
 * focus-aware components are what Compose for TV actually ships today) rather than Leanback
 * Views. Show/track drill-down stays out (Part 2.2); playback stays out (Part 3).
 *
 * The grouping and the years both ride the exact seams the phone app and Android Auto
 * already use — [groupArtistsForBrowse] for the phish/favorited/everyone-else split and
 * [MusicSource.periods] for the years — so the TV never grows a second catalog path to
 * drift from the first two.
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
 * The two-level browse stack: artists, then one artist's years. Back on the remote pops
 * the level via [BackHandler]; there is deliberately no nav library here — two states are
 * not a graph, and the phone's NavHost assumes touch affordances a D-pad doesn't have.
 */
@Composable
fun TvBrowseScreen() {
    var selectedArtist by remember { mutableStateOf<ArtistRef?>(null) }
    BackHandler(enabled = selectedArtist != null) { selectedArtist = null }

    val artist = selectedArtist
    if (artist == null) {
        TvArtistBrowseScreen(onArtistClick = { selectedArtist = it })
    } else {
        TvYearBrowseScreen(artist = artist, onBack = { selectedArtist = null })
    }
}

@Composable
private fun TvArtistBrowseScreen(onArtistClick: (ArtistRef) -> Unit) {
    val perBackend by tvLoadOnce("artists") { loadArtistsByBackend() }
    val favoriteKeys by Favorites.keys.collectAsState()

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
            else -> TvArtistSectionList(sections.orEmpty(), onArtistClick)
        }
    }
}

@Composable
private fun TvArtistSectionList(
    sections: List<TvArtistSectionData>,
    onArtistClick: (ArtistRef) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
private fun TvYearBrowseScreen(artist: ArtistRef, onBack: () -> Unit) {
    val periods by tvLoadOnce("periods-${artist.key}") {
        sourceFor(artist.backend).periods(artist)
    }

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
                                onClick = { /* Part 2.2: show drill-down */ },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The one card style both levels share: a tv-material3 [Card] sized for a 10-foot read.
 * Focus behavior (scale-up on focus) comes from CardDefaults — what makes this comply with
 * Compose for TV's material focus guidelines without hand-drawing highlight borders.
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

