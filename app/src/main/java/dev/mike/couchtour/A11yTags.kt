package dev.mike.couchtour

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

object A11yTags {
    const val NAV_HOME = "nav.home"
    const val NAV_SEARCH = "nav.search"
    const val NAV_LIBRARY = "nav.library"
    const val NAV_HISTORY = "nav.history"
    const val NAV_SETTINGS = "nav.settings"

    const val HOME_SECTION_IN_PROGRESS = "home.section.in-progress"
    fun homeInProgressRow(queueKey: String) = "$HOME_SECTION_IN_PROGRESS.row.$queueKey"

    const val HOME_SECTION_NEXT_TOUR_STOPS = "home.section.next-tour-stops"
    fun homeNextTourStopRow(showKey: String) = "$HOME_SECTION_NEXT_TOUR_STOPS.row.$showKey"

    const val HOME_SECTION_ON_THIS_DATE = "home.section.on-this-date"
    fun homeOnThisDateRow(showKey: String) = "$HOME_SECTION_ON_THIS_DATE.row.$showKey"

    const val FAVORITES_LIST = "favorites.list"
    fun favoritesRow(artistKey: String) = "favorites.row.$artistKey"

    const val SEARCH_FIELD = "search.field"
    const val SEARCH_SECTION_ARTISTS = "search.section.artists"
    const val SEARCH_SECTION_SHOWS = "search.section.shows"
    const val SEARCH_SECTION_TRACKS = "search.section.tracks"
    const val SEARCH_RESULTS = "search.results"
}

/**
 * Without this on the root, Compose keeps testTag internal and `uiautomator dump` shows an empty
 * resource-id for every node, so the A11yTags can't be used to locate anything from adb.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun androidx.compose.ui.Modifier.testTagsAsResourceIds(): androidx.compose.ui.Modifier =
    this.semantics { testTagsAsResourceId = true }
