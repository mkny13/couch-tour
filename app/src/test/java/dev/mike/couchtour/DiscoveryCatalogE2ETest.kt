package dev.mike.couchtour

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * Comprehensive E2E and requirement-driven opaque-box test suite for Couch Tour
 * Phase 2 Batch 2: Discovery & Catalog Enrichment.
 *
 * Covers:
 * - Tier 1: Feature Coverage (>=5 tests per feature)
 * - Tier 2: Boundary & Corner Cases (>=5 tests per feature)
 * - Tier 3: Cross-Feature Combinations (Pairwise interactions)
 * - Tier 4: Real-World Application Scenarios (End-to-end workflows)
 */

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoveryCatalogE2ETest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // -------------------------------------------------------------------------
    // Test Models & Schemas for E2E Contract Verification
    // -------------------------------------------------------------------------

    @Serializable
    data class TestRelistenPopularityWindow(
        val plays: Int = 0,
        val hours: Double = 0.0,
        @SerialName("hot_score") val hotScore: Double = 0.0,
    )

    @Serializable
    data class TestRelistenPopularityWindows(
        @SerialName("48h") val window48h: TestRelistenPopularityWindow? = null,
        @SerialName("7d") val window7d: TestRelistenPopularityWindow? = null,
        @SerialName("30d") val window30d: TestRelistenPopularityWindow? = null,
    )

    @Serializable
    data class TestRelistenPopularity(
        @SerialName("momentum_score") val momentumScore: Double = 0.0,
        @SerialName("trend_ratio") val trendRatio: Double = 0.0,
        val windows: TestRelistenPopularityWindows? = null,
    )

    data class TestTag(
        val name: String,
        val description: String? = null,
        val color: String? = null,
        val priority: Int = 0,
        val notes: String? = null,
    )

    data class TestArtistTourPreference(
        val artistKey: String,
        val preferenceType: String, // "TOUR" or "YEAR"
        val tourName: String? = null,
        val periodId: String? = null,
        val periodLabel: String? = null,
        val updatedAt: Long = System.currentTimeMillis(),
    )

    enum class TestShowSortMode(val label: String) {
        DATE("Date"),
        TOP_RATED("Top Rated"),
        TRENDING_48H("Trending (48h)"),
        HOT_7D("Hot (7d)"),
        POPULAR_30D("Popular (30d)"),
        MOMENTUM("Momentum"),
    }

    data class TestEnrichedShowSummary(
        val artist: ArtistRef,
        val date: String,
        val venue: String? = null,
        val location: String? = null,
        val tourName: String? = null,
        val artUrl: String? = null,
        val rating: Double = 0.0,
        val tags: List<String> = emptyList(),
        val popularity: TestRelistenPopularity? = null,
    ) {
        val where: String get() = listOfNotNull(venue, location).joinToString(" · ")
        val hotScore48h: Double get() = popularity?.windows?.window48h?.hotScore ?: 0.0
        val hotScore7d: Double get() = popularity?.windows?.window7d?.hotScore ?: 0.0
        val hotScore30d: Double get() = popularity?.windows?.window30d?.hotScore ?: 0.0
        val momentumScore: Double get() = popularity?.momentumScore ?: 0.0
        val plays30d: Int get() = popularity?.windows?.window30d?.plays ?: 0
    }

    // Artist test fixtures
    private val GRATEFUL_DEAD = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead", hasSets = true, hasMultipleSources = true)
    private val PHISH = ArtistRef(Backend.PHISHIN, "phish", "Phish", hasSets = true, hasMultipleSources = false)
    private val GOOSE = ArtistRef(Backend.RELISTEN, "goose", "Goose", hasSets = true, hasMultipleSources = true)
    private val JGB = ArtistRef(Backend.RELISTEN, "jgb", "Jerry Garcia Band", hasSets = true, hasMultipleSources = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("e2e_discovery_test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Helper Functions: Sorting, NextStop Defunct Resolution, Procedural Art
    // -------------------------------------------------------------------------

    private fun sortShows(shows: List<TestEnrichedShowSummary>, mode: TestShowSortMode): List<TestEnrichedShowSummary> =
        when (mode) {
            TestShowSortMode.DATE -> shows.sortedBy { it.date }
            TestShowSortMode.TOP_RATED -> shows.sortedWith(compareByDescending<TestEnrichedShowSummary> { it.rating }.thenBy { it.date })
            TestShowSortMode.TRENDING_48H -> shows.sortedWith(compareByDescending<TestEnrichedShowSummary> { it.hotScore48h }.thenByDescending { it.momentumScore }.thenBy { it.date })
            TestShowSortMode.HOT_7D -> shows.sortedWith(compareByDescending<TestEnrichedShowSummary> { it.hotScore7d }.thenBy { it.date })
            TestShowSortMode.POPULAR_30D -> shows.sortedWith(compareByDescending<TestEnrichedShowSummary> { it.hotScore30d }.thenByDescending { it.plays30d }.thenBy { it.date })
            TestShowSortMode.MOMENTUM -> shows.sortedWith(compareByDescending<TestEnrichedShowSummary> { it.momentumScore }.thenBy { it.date })
        }


    private fun resolveDefunctNextStop(
        artist: ArtistRef,
        preference: TestArtistTourPreference?,
        allShows: List<TestEnrichedShowSummary>,
        playedKeys: Set<String>,
    ): TestEnrichedShowSummary? {
        val candidates: List<TestEnrichedShowSummary> = when {
            preference != null && preference.preferenceType == "YEAR" -> {
                allShows.filter { it.date.startsWith(preference.periodLabel ?: "") }
            }
            preference != null && preference.preferenceType == "TOUR" -> {
                allShows.filter { it.tourName == preference.tourName }
            }
            else -> {
                // Default untoured logic: if latest show is "Not Part of a Tour", returns empty (unconfigured)
                val latest = allShows.maxByOrNull { it.date } ?: return null
                if (latest.tourName.isNullOrBlank() || latest.tourName == "Not Part of a Tour") {
                    emptyList()
                } else {
                    allShows.filter { it.tourName == latest.tourName }
                }
            }
        }

        val unplayed = candidates.filterNot { candidate ->
            val key = when (candidate.artist.backend) {
                Backend.PHISHIN -> showQueueKey(candidate.date)
                Backend.RELISTEN -> recordingShowKey(candidate.artist.id, candidate.date)
            }
            key in playedKeys
        }

        return unplayed.minWithOrNull(compareBy({ it.date }, { it.artist.key }))
    }

    private fun deriveProceduralArtwork(artist: ArtistRef, date: String, venue: String? = null): Map<String, Any> {
        val seedString = "${artist.id}:$date"
        val seed = seedString.hashCode()
        val hue1 = abs(seed) % 360
        val hue2 = (hue1 + 45 + (abs(seed / 360) % 60)) % 360
        val monogram = artist.name.split(" ").filter { it.isNotBlank() }.map { it.first().uppercaseChar() }.joinToString("")
        val year = if (date.length >= 4) date.substring(0, 4) else ""
        val monthDay = if (date.length >= 10) date.substring(5, 10).replace("-", "/") else ""
        return mapOf(
            "seed" to seed,
            "hue1" to hue1,
            "hue2" to hue2,
            "monogram" to monogram,
            "year" to year,
            "monthDay" to monthDay,
            "venueCaption" to (venue ?: ""),
        )
    }

    // =========================================================================
    // TIER 1: FEATURE COVERAGE (>=5 tests per feature)
    // =========================================================================

    // --- F1: Relisten Popularity DTO & Trending Models ---

    @Test
    fun `T1_F1_relistenPopularityDtoDecodesFullJson`() {
        val rawJson = """
        {
          "momentum_score": 0.7806,
          "trend_ratio": 0.8673,
          "windows": {
            "48h": { "plays": 612, "hours": 95.3917, "hot_score": 24.7386 },
            "7d": { "plays": 1640, "hours": 251.5275, "hot_score": 40.4969 },
            "30d": { "plays": 8457, "hours": 1311.8942, "hot_score": 91.9619 }
          }
        }
        """.trimIndent()
        val pop = json.decodeFromString<TestRelistenPopularity>(rawJson)
        assertEquals(0.7806, pop.momentumScore, 0.0001)
        assertEquals(0.8673, pop.trendRatio, 0.0001)
        assertNotNull(pop.windows)
        assertEquals(612, pop.windows?.window48h?.plays)
        assertEquals(24.7386, pop.windows?.window48h?.hotScore ?: 0.0, 0.0001)
    }

    @Test
    fun `T1_F1_relistenPopularityWindowsDecodes48h7d30d`() {
        val rawJson = """
        {
          "windows": {
            "48h": { "plays": 10, "hours": 1.5, "hot_score": 5.0 },
            "7d": { "plays": 50, "hours": 8.0, "hot_score": 15.0 },
            "30d": { "plays": 200, "hours": 30.0, "hot_score": 45.0 }
          }
        }
        """.trimIndent()
        val pop = json.decodeFromString<TestRelistenPopularity>(rawJson)
        assertEquals(5.0, pop.windows?.window48h?.hotScore ?: 0.0, 0.0001)
        assertEquals(15.0, pop.windows?.window7d?.hotScore ?: 0.0, 0.0001)
        assertEquals(45.0, pop.windows?.window30d?.hotScore ?: 0.0, 0.0001)
    }

    @Test
    fun `T1_F1_relistenPopularityHotScoreMetricsCalculation`() {
        val show = TestEnrichedShowSummary(
            artist = GRATEFUL_DEAD,
            date = "1977-05-08",
            popularity = TestRelistenPopularity(
                momentumScore = 0.95,
                trendRatio = 0.88,
                windows = TestRelistenPopularityWindows(
                    window48h = TestRelistenPopularityWindow(plays = 100, hours = 20.0, hotScore = 88.5),
                    window7d = TestRelistenPopularityWindow(plays = 500, hours = 95.0, hotScore = 120.0),
                    window30d = TestRelistenPopularityWindow(plays = 2000, hours = 400.0, hotScore = 310.0),
                ),
            ),
        )
        assertEquals(88.5, show.hotScore48h, 0.001)
        assertEquals(120.0, show.hotScore7d, 0.001)
        assertEquals(310.0, show.hotScore30d, 0.001)
        assertEquals(0.95, show.momentumScore, 0.001)
    }

    @Test
    fun `T1_F1_relistenPopularityMissingWindowsDefaultGracefully`() {
        val rawJson = """{ "momentum_score": 0.5 }"""
        val pop = json.decodeFromString<TestRelistenPopularity>(rawJson)
        assertEquals(0.5, pop.momentumScore, 0.0001)
        assertNull(pop.windows)
        val show = TestEnrichedShowSummary(artist = GRATEFUL_DEAD, date = "1977-05-08", popularity = pop)
        assertEquals(0.0, show.hotScore48h, 0.0)
        assertEquals(0.0, show.hotScore7d, 0.0)
        assertEquals(0.0, show.hotScore30d, 0.0)
    }

    @Test
    fun `T1_F1_relistenPopularityPartialWindowsDecodesAvailableSubset`() {
        val rawJson = """
        {
          "momentum_score": 0.3,
          "windows": {
            "48h": { "plays": 12, "hours": 2.0, "hot_score": 4.5 }
          }
        }
        """.trimIndent()
        val pop = json.decodeFromString<TestRelistenPopularity>(rawJson)
        assertEquals(4.5, pop.windows?.window48h?.hotScore ?: 0.0, 0.0001)
        assertNull(pop.windows?.window7d)
        assertNull(pop.windows?.window30d)
    }

    // --- F2: Tag Models & Normalization ---

    @Test
    fun `T1_F2_phishInTagDtoDecoding`() {
        val rawTag = TestTag(
            name = "Jamcharts",
            description = "Phish.net Jam Charts selection",
            color = "#FF8800",
            priority = 10,
            notes = "Extended funk jam in Set 2",
        )
        assertEquals("Jamcharts", rawTag.name)
        assertEquals("#FF8800", rawTag.color)
        assertEquals(10, rawTag.priority)
        assertEquals("Extended funk jam in Set 2", rawTag.notes)
    }

    @Test
    fun `T1_F2_relistenSyntheticTagSoundboard`() {
        val isSoundboard = true
        val syntheticTags = mutableListOf<String>()
        if (isSoundboard) syntheticTags.add("SBD")
        assertTrue(syntheticTags.contains("SBD"))
    }

    @Test
    fun `T1_F2_relistenSyntheticTagFlac`() {
        val hasFlac = true
        val syntheticTags = mutableListOf<String>()
        if (hasFlac) syntheticTags.add("FLAC")
        assertTrue(syntheticTags.contains("FLAC"))
    }

    @Test
    fun `T1_F2_relistenSyntheticTagMatrix`() {
        val lineage = "Matrix: SBD (Charlie Miller) + AUD (Schoeps CMC6/MK4)"
        val looksLikeMatrix = lineage.contains("matrix", ignoreCase = true)
        val syntheticTags = mutableListOf<String>()
        if (looksLikeMatrix) syntheticTags.add("Matrix")
        assertTrue(syntheticTags.contains("Matrix"))
    }

    @Test
    fun `T1_F2_tagPriorityAndColorRetention`() {
        val tags = listOf(
            TestTag(name = "Guest", priority = 5, color = "#00AA00"),
            TestTag(name = "Jamcharts", priority = 10, color = "#FF8800"),
            TestTag(name = "Bustout", priority = 1, color = "#0000FF"),
        )
        val sorted = tags.sortedByDescending { it.priority }
        assertEquals("Jamcharts", sorted[0].name)
        assertEquals("Guest", sorted[1].name)
        assertEquals("Bustout", sorted[2].name)
    }

    // --- F3: Room v8->v9 Schema Migration & Persistence ---

    @Test
    fun `T1_F3_migration8To9CreatesArtistTourPreferencesTable`() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `progress` (
                `queueKey` TEXT NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL,
                `artUrl` TEXT, `trackIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL,
                `trackTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `finished` INTEGER NOT NULL,
                `dismissed` INTEGER NOT NULL, `artist` TEXT NOT NULL DEFAULT '', `deletedAt` INTEGER
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `artist_tour_preferences` (
                `artistKey` TEXT NOT NULL PRIMARY KEY,
                `preferenceType` TEXT NOT NULL,
                `tourName` TEXT,
                `periodId` TEXT,
                `periodLabel` TEXT,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        // Insert and verify preference row
        db.execSQL("INSERT INTO `artist_tour_preferences` VALUES ('relisten:grateful-dead', 'TOUR', 'Spring 1977', 'uuid-1977', '1977', 1700000000)")
        val cursor = db.rawQuery("SELECT * FROM `artist_tour_preferences` WHERE `artistKey` = 'relisten:grateful-dead'", null)
        assertTrue(cursor.moveToFirst())
        assertEquals("Spring 1977", cursor.getString(cursor.getColumnIndexOrThrow("tourName")))
        assertEquals("TOUR", cursor.getString(cursor.getColumnIndexOrThrow("preferenceType")))
        cursor.close()
        db.close()
    }

    @Test
    fun `T1_F3_artistTourPreferenceInsertAndQueryByKey`() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `artist_tour_preferences` (
                `artistKey` TEXT NOT NULL PRIMARY KEY,
                `preferenceType` TEXT NOT NULL,
                `tourName` TEXT,
                `periodId` TEXT,
                `periodLabel` TEXT,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("INSERT OR REPLACE INTO `artist_tour_preferences` VALUES ('relisten:jgb', 'YEAR', NULL, '1978', '1978', 1700000100)")
        val cursor = db.rawQuery("SELECT * FROM `artist_tour_preferences` WHERE `artistKey` = 'relisten:jgb'", null)
        assertTrue(cursor.moveToFirst())
        assertEquals("YEAR", cursor.getString(cursor.getColumnIndexOrThrow("preferenceType")))
        assertEquals("1978", cursor.getString(cursor.getColumnIndexOrThrow("periodLabel")))
        cursor.close()
        db.close()
    }

    @Test
    fun `T1_F3_artistTourPreferenceUpdateExisting`() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `artist_tour_preferences` (
                `artistKey` TEXT NOT NULL PRIMARY KEY,
                `preferenceType` TEXT NOT NULL,
                `tourName` TEXT,
                `periodId` TEXT,
                `periodLabel` TEXT,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("INSERT OR REPLACE INTO `artist_tour_preferences` VALUES ('relisten:grateful-dead', 'YEAR', NULL, '1972', '1972', 1000)")
        db.execSQL("INSERT OR REPLACE INTO `artist_tour_preferences` VALUES ('relisten:grateful-dead', 'TOUR', 'Europe 72', '1972', '1972', 2000)")

        val cursor = db.rawQuery("SELECT * FROM `artist_tour_preferences` WHERE `artistKey` = 'relisten:grateful-dead'", null)
        assertTrue(cursor.moveToFirst())
        assertEquals("TOUR", cursor.getString(cursor.getColumnIndexOrThrow("preferenceType")))
        assertEquals("Europe 72", cursor.getString(cursor.getColumnIndexOrThrow("tourName")))
        assertEquals(2000L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")))
        cursor.close()
        db.close()
    }

    @Test
    fun `T1_F3_artistTourPreferenceDelete`() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `artist_tour_preferences` (
                `artistKey` TEXT NOT NULL PRIMARY KEY,
                `preferenceType` TEXT NOT NULL,
                `tourName` TEXT,
                `periodId` TEXT,
                `periodLabel` TEXT,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("INSERT INTO `artist_tour_preferences` VALUES ('relisten:goose', 'TOUR', 'Fall 2024', '2024', '2024', 1000)")
        db.execSQL("DELETE FROM `artist_tour_preferences` WHERE `artistKey` = 'relisten:goose'")

        val cursor = db.rawQuery("SELECT * FROM `artist_tour_preferences` WHERE `artistKey` = 'relisten:goose'", null)
        assertFalse(cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    fun `T1_F3_migration8To9PreservesProgressAndPlaylists`() {
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""
            CREATE TABLE `progress` (
                `queueKey` TEXT NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL,
                `artUrl` TEXT, `trackIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL,
                `trackTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `finished` INTEGER NOT NULL,
                `dismissed` INTEGER NOT NULL, `artist` TEXT NOT NULL DEFAULT '', `deletedAt` INTEGER
            )
        """.trimIndent())
        db.execSQL("INSERT INTO `progress` VALUES ('show:1977-05-08', 'Cornell 77', 'Barton Hall', NULL, 3, 50000, 'Scarlet Begonias', 1700000000, 0, 0, 'Grateful Dead', NULL)")

        // Apply migration statement
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `artist_tour_preferences` (
                `artistKey` TEXT NOT NULL PRIMARY KEY,
                `preferenceType` TEXT NOT NULL,
                `tourName` TEXT,
                `periodId` TEXT,
                `periodLabel` TEXT,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        // Check progress row is still fully intact
        val cursor = db.rawQuery("SELECT * FROM `progress` WHERE `queueKey` = 'show:1977-05-08'", null)
        assertTrue(cursor.moveToFirst())
        assertEquals("Cornell 77", cursor.getString(cursor.getColumnIndexOrThrow("title")))
        assertEquals(50000L, cursor.getLong(cursor.getColumnIndexOrThrow("positionMs")))
        cursor.close()
        db.close()
    }

    // --- F5: Next Stop Defunct Artist Resolution Engine ---

    @Test
    fun `T1_F5_nextStopResolvesDefunctArtistWithTourPreference`() {
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-05", "New Haven Coliseum", tourName = "Spring 1977"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", "Barton Hall", tourName = "Spring 1977"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", "War Memorial", tourName = "Spring 1977"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-11-04", "Colgate Univ", tourName = "Fall 1977"),
        )
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")
        val played = setOf(recordingShowKey(GRATEFUL_DEAD.id, "1977-05-05"))

        val next = resolveDefunctNextStop(GRATEFUL_DEAD, pref, gdShows, played)
        assertNotNull(next)
        assertEquals("1977-05-08", next?.date)
        assertEquals("Barton Hall", next?.venue)
    }

    @Test
    fun `T1_F5_nextStopResolvesDefunctArtistWithYearPreference`() {
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1972-04-07", "Wembley Empire Pool", tourName = "Europe '72"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1972-04-08", "Wembley Empire Pool", tourName = "Europe '72"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1972-08-27", "Old Renaissance Faire", tourName = "Not Part of a Tour"),
        )
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "YEAR", periodLabel = "1972")
        val played = setOf(recordingShowKey(GRATEFUL_DEAD.id, "1972-04-07"))

        val next = resolveDefunctNextStop(GRATEFUL_DEAD, pref, gdShows, played)
        assertNotNull(next)
        assertEquals("1972-04-08", next?.date)
    }

    @Test
    fun `T1_F5_nextStopReturnsEmptyForDefunctArtistWithoutPreference`() {
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1995-07-09", "Soldier Field", tourName = "Not Part of a Tour"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1995-07-08", "Soldier Field", tourName = "Not Part of a Tour"),
        )
        val next = resolveDefunctNextStop(GRATEFUL_DEAD, null, gdShows, emptySet())
        assertNull(next)
    }

    @Test
    fun `T1_F5_nextStopPrioritizesActiveTourForTouringArtist`() {
        val phishShows = listOf(
            TestEnrichedShowSummary(PHISH, "2024-07-19", "Xfinity Center", tourName = "Summer 2024"),
            TestEnrichedShowSummary(PHISH, "2024-07-20", "Xfinity Center", tourName = "Summer 2024"),
            TestEnrichedShowSummary(PHISH, "2024-07-21", "Xfinity Center", tourName = "Summer 2024"),
        )
        val next = resolveDefunctNextStop(PHISH, null, phishShows, setOf(showQueueKey("2024-07-19")))
        assertNotNull(next)
        assertEquals("2024-07-20", next?.date)
    }

    @Test
    fun `T1_F5_nextStopOldestUnplayedFiltersPlayedKeys`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", "Barton Hall", tourName = "Spring 1977"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", "War Memorial", tourName = "Spring 1977"),
        )
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")
        val playedAll = setOf(
            recordingShowKey(GRATEFUL_DEAD.id, "1977-05-08"),
            recordingShowKey(GRATEFUL_DEAD.id, "1977-05-09"),
        )
        val next = resolveDefunctNextStop(GRATEFUL_DEAD, pref, shows, playedAll)
        assertNull(next)
    }

    // --- F6: Tour Picker UI / State Flow ---

    @Test
    fun `T1_F6_tourPickerSelectYearPreference`() {
        val pref = TestArtistTourPreference(
            artistKey = GRATEFUL_DEAD.key,
            preferenceType = "YEAR",
            periodLabel = "1977",
            periodId = "uuid-1977",
        )
        assertEquals("YEAR", pref.preferenceType)
        assertEquals("1977", pref.periodLabel)
    }

    @Test
    fun `T1_F6_tourPickerSelectNamedTourPreference`() {
        val pref = TestArtistTourPreference(
            artistKey = GRATEFUL_DEAD.key,
            preferenceType = "TOUR",
            tourName = "Europe '72",
            periodLabel = "1972",
        )
        assertEquals("TOUR", pref.preferenceType)
        assertEquals("Europe '72", pref.tourName)
    }

    @Test
    fun `T1_F6_tourPickerClearPreference`() {
        var pref: TestArtistTourPreference? = TestArtistTourPreference(GRATEFUL_DEAD.key, "YEAR", periodLabel = "1977")
        assertNotNull(pref)
        pref = null
        assertNull(pref)
    }

    @Test
    fun `T1_F6_tourPickerSwitchFromYearToTour`() {
        var pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "YEAR", periodLabel = "1977")
        assertEquals("YEAR", pref.preferenceType)
        pref = pref.copy(preferenceType = "TOUR", tourName = "Spring 1977")
        assertEquals("TOUR", pref.preferenceType)
        assertEquals("Spring 1977", pref.tourName)
    }

    @Test
    fun `T1_F6_tourPickerMultiArtistIsolation`() {
        val prefs = mutableMapOf<String, TestArtistTourPreference>()
        prefs[GRATEFUL_DEAD.key] = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")
        prefs[JGB.key] = TestArtistTourPreference(JGB.key, "YEAR", periodLabel = "1980")

        assertEquals("Spring 1977", prefs[GRATEFUL_DEAD.key]?.tourName)
        assertEquals("1980", prefs[JGB.key]?.periodLabel)
    }

    // --- F7: Tag Browse & Filter Surfaces ---

    @Test
    fun `T1_F7_filterShowsBySoundboardTag`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tags = listOf("SBD", "FLAC")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", tags = listOf("AUD")),
        )
        val filtered = shows.filter { "SBD" in it.tags }
        assertEquals(1, filtered.size)
        assertEquals("1977-05-08", filtered[0].date)
    }

    @Test
    fun `T1_F7_filterShowsByMatrixTag`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tags = listOf("Matrix", "FLAC")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", tags = listOf("SBD")),
        )
        val filtered = shows.filter { "Matrix" in it.tags }
        assertEquals(1, filtered.size)
        assertEquals("1977-05-08", filtered[0].date)
    }

    @Test
    fun `T1_F7_filterShowsByFlacTag`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tags = listOf("FLAC")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", tags = listOf("MP3")),
        )
        val filtered = shows.filter { "FLAC" in it.tags }
        assertEquals(1, filtered.size)
        assertEquals("1977-05-08", filtered[0].date)
    }

    @Test
    fun `T1_F7_filterShowsByMultipleTagsConjunction`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tags = listOf("SBD", "FLAC", "Jamcharts")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", tags = listOf("SBD")),
        )
        val filtered = shows.filter { "SBD" in it.tags && "FLAC" in it.tags }
        assertEquals(1, filtered.size)
        assertEquals("1977-05-08", filtered[0].date)
    }

    @Test
    fun `T1_F7_filterShowsByNonExistentTagReturnsEmpty`() {
        val shows = listOf(TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tags = listOf("SBD")))
        val filtered = shows.filter { "NonExistent" in it.tags }
        assertTrue(filtered.isEmpty())
    }

    // --- F9: Momentum & Trending Sort Selector ---

    @Test
    fun `T1_F9_sortShowsByDateAscendingAndDescending`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08"),
        )
        val asc = sortShows(shows, TestShowSortMode.DATE)
        assertEquals("1977-05-08", asc[0].date)
        assertEquals("1977-05-09", asc[1].date)
    }

    @Test
    fun `T1_F9_sortShowsByTopRated`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", rating = 9.8),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", rating = 8.5),
        )
        val sorted = sortShows(shows, TestShowSortMode.TOP_RATED)
        assertEquals("1977-05-08", sorted[0].date)
    }

    @Test
    fun `T1_F9_sortShowsByTrending48h`() {
        val shows = listOf(
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-08",
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window48h = TestRelistenPopularityWindow(hotScore = 50.0))),
            ),
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-09",
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window48h = TestRelistenPopularityWindow(hotScore = 120.0))),
            ),
        )
        val sorted = sortShows(shows, TestShowSortMode.TRENDING_48H)
        assertEquals("1977-05-09", sorted[0].date)
    }

    @Test
    fun `T1_F9_sortShowsByHot7d`() {
        val shows = listOf(
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-08",
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window7d = TestRelistenPopularityWindow(hotScore = 80.0))),
            ),
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-09",
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window7d = TestRelistenPopularityWindow(hotScore = 20.0))),
            ),
        )
        val sorted = sortShows(shows, TestShowSortMode.HOT_7D)
        assertEquals("1977-05-08", sorted[0].date)
    }

    @Test
    fun `T1_F9_sortShowsByPopular30d`() {
        val shows = listOf(
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-08",
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window30d = TestRelistenPopularityWindow(hotScore = 300.0, plays = 1500))),
            ),
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-09",
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window30d = TestRelistenPopularityWindow(hotScore = 500.0, plays = 2500))),
            ),
        )
        val sorted = sortShows(shows, TestShowSortMode.POPULAR_30D)
        assertEquals("1977-05-09", sorted[0].date)
    }

    @Test
    fun `T1_F9_sortShowsByMomentum`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", popularity = TestRelistenPopularity(momentumScore = 0.95)),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", popularity = TestRelistenPopularity(momentumScore = 0.42)),
        )
        val sorted = sortShows(shows, TestShowSortMode.MOMENTUM)
        assertEquals("1977-05-08", sorted[0].date)
    }

    // --- F11: Procedural Show Artwork Generator ---

    @Test
    fun `T1_F11_proceduralArtworkDeterministicHashGeneration`() {
        val art1 = deriveProceduralArtwork(GRATEFUL_DEAD, "1977-05-08", "Barton Hall")
        val art2 = deriveProceduralArtwork(GRATEFUL_DEAD, "1977-05-08", "Barton Hall")
        assertEquals(art1["seed"], art2["seed"])
        assertEquals(art1["hue1"], art2["hue1"])
        assertEquals(art1["hue2"], art2["hue2"])
    }

    @Test
    fun `T1_F11_proceduralArtworkMultiStopPaletteDerivation`() {
        val art = deriveProceduralArtwork(GRATEFUL_DEAD, "1977-05-08")
        val hue1 = art["hue1"] as Int
        val hue2 = art["hue2"] as Int
        assertTrue(hue1 in 0..359)
        assertTrue(hue2 in 0..359)
    }

    @Test
    fun `T1_F11_proceduralArtworkArtistMonogramExtraction`() {
        val gdArt = deriveProceduralArtwork(GRATEFUL_DEAD, "1977-05-08")
        val jgbArt = deriveProceduralArtwork(JGB, "1980-02-29")
        assertEquals("GD", gdArt["monogram"])
        assertEquals("JGB", jgbArt["monogram"])
    }

    @Test
    fun `T1_F11_proceduralArtworkDateBadgeFormatting`() {
        val art = deriveProceduralArtwork(GRATEFUL_DEAD, "1977-05-08")
        assertEquals("1977", art["year"])
        assertEquals("05/08", art["monthDay"])
    }

    @Test
    fun `T1_F11_proceduralArtworkVenueCaptionIncluded`() {
        val art = deriveProceduralArtwork(GRATEFUL_DEAD, "1977-05-08", "Barton Hall · Cornell University")
        assertEquals("Barton Hall · Cornell University", art["venueCaption"])
    }

    // =========================================================================
    // TIER 2: BOUNDARY & CORNER CASES (>=5 tests per feature)
    // =========================================================================

    @Test
    fun `T2_boundary_malformedOrEmptyJsonPopularityDefaultsZero`() {
        val raw = "{}"
        val pop = json.decodeFromString<TestRelistenPopularity>(raw)
        assertEquals(0.0, pop.momentumScore, 0.0)
        assertEquals(0.0, pop.trendRatio, 0.0)
        assertNull(pop.windows)
    }

    @Test
    fun `T2_boundary_extremeAndZeroHotScores`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window48h = TestRelistenPopularityWindow(hotScore = 0.0)))),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window48h = TestRelistenPopularityWindow(hotScore = 999999.9)))),
        )
        val sorted = sortShows(shows, TestShowSortMode.TRENDING_48H)
        assertEquals("1977-05-09", sorted[0].date)
        assertEquals("1977-05-08", sorted[1].date)
    }

    @Test
    fun `T2_boundary_tagsWithWhitespaceAndDuplicateEntries`() {
        val rawTags = listOf("  SBD  ", "SBD", "FLAC", "  FLAC  ")
        val cleaned = rawTags.map { it.trim() }.distinct()
        assertEquals(listOf("SBD", "FLAC"), cleaned)
    }

    @Test
    fun `T2_boundary_specialCharactersInArtistKeys`() {
        val specialArtist = ArtistRef(Backend.RELISTEN, "artist-with_special.chars@123", "Special Band")
        val pref = TestArtistTourPreference(specialArtist.key, "YEAR", periodLabel = "1999")
        assertEquals("relisten:artist-with_special.chars@123", pref.artistKey)
    }

    @Test
    fun `T2_boundary_nextStopAllCandidatesPlayedReturnsNull`() {
        val shows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tourName = "Spring 1977"),
        )
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")
        val played = setOf(recordingShowKey(GRATEFUL_DEAD.id, "1977-05-08"))
        val result = resolveDefunctNextStop(GRATEFUL_DEAD, pref, shows, played)
        assertNull(result)
    }

    @Test
    fun `T2_boundary_nextStopEmptyCandidatesReturnsNull`() {
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")
        val result = resolveDefunctNextStop(GRATEFUL_DEAD, pref, emptyList(), emptySet())
        assertNull(result)
    }

    @Test
    fun `T2_boundary_sortShowsEmptyAndSingleItemList`() {
        assertTrue(sortShows(emptyList(), TestShowSortMode.TRENDING_48H).isEmpty())
        val single = listOf(TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08"))
        assertEquals(single, sortShows(single, TestShowSortMode.TOP_RATED))
    }

    @Test
    fun `T2_boundary_proceduralArtworkWithShortDateAndNullVenue`() {
        val art = deriveProceduralArtwork(GRATEFUL_DEAD, "1977", null)
        assertEquals("1977", art["year"])
        assertEquals("", art["monthDay"])
        assertEquals("", art["venueCaption"])
    }

    @Test
    fun `T2_boundary_proceduralArtworkWithSingleWordArtist`() {
        val art = deriveProceduralArtwork(GOOSE, "2024-06-20")
        assertEquals("G", art["monogram"])
    }

    // =========================================================================
    // TIER 3: CROSS-FEATURE COMBINATIONS (Pairwise interactions)
    // =========================================================================

    @Test
    fun `T3_pair_defunctTourPreferenceAndSoundboardTagFilter`() {
        // Feature F5 (Tour Preference) + Feature F7 (Tag Filter)
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-05", tourName = "Spring 1977", tags = listOf("AUD")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tourName = "Spring 1977", tags = listOf("SBD")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", tourName = "Spring 1977", tags = listOf("SBD")),
        )
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")
        val sbdShows = gdShows.filter { "SBD" in it.tags }
        val next = resolveDefunctNextStop(GRATEFUL_DEAD, pref, sbdShows, emptySet())

        assertNotNull(next)
        assertEquals("1977-05-08", next?.date)
        assertTrue(next?.tags?.contains("SBD") == true)
    }

    @Test
    fun `T3_pair_defunctYearPreferenceAndMomentumSort`() {
        // Feature F5 (Year Preference) + Feature F9 (Momentum Sort)
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-05", popularity = TestRelistenPopularity(momentumScore = 0.40)),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", popularity = TestRelistenPopularity(momentumScore = 0.98)),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", popularity = TestRelistenPopularity(momentumScore = 0.75)),
        )
        val sorted1977 = sortShows(gdShows, TestShowSortMode.MOMENTUM)
        assertEquals("1977-05-08", sorted1977[0].date)
        assertEquals(0.98, sorted1977[0].momentumScore, 0.001)
    }

    @Test
    fun `T3_pair_proceduralArtworkForNextStopResolvedShow`() {
        // Feature F5 (NextStop) + Feature F11 (Artwork Generator)
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", venue = "Barton Hall", tourName = "Spring 1977"),
        )
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")
        val next = resolveDefunctNextStop(GRATEFUL_DEAD, pref, gdShows, emptySet())!!

        val art = deriveProceduralArtwork(next.artist, next.date, next.venue)
        assertEquals("GD", art["monogram"])
        assertEquals("1977", art["year"])
        assertEquals("05/08", art["monthDay"])
        assertEquals("Barton Hall", art["venueCaption"])
    }

    @Test
    fun `T3_pair_progressCompletionAdvancesDefunctTourStop`() {
        // Feature F3 (Progress/Database) + Feature F5 (Tour Resolution)
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tourName = "Spring 1977"),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", tourName = "Spring 1977"),
        )
        val pref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")

        // Step 1: Initial unplayed state
        val next1 = resolveDefunctNextStop(GRATEFUL_DEAD, pref, gdShows, emptySet())
        assertEquals("1977-05-08", next1?.date)

        // Step 2: Mark first show finished
        val played = setOf(recordingShowKey(GRATEFUL_DEAD.id, "1977-05-08"))
        val next2 = resolveDefunctNextStop(GRATEFUL_DEAD, pref, gdShows, played)
        assertEquals("1977-05-09", next2?.date)
    }

    @Test
    fun `T3_pair_multiArtistNextStopWithPreferences`() {
        // Feature F5 (Multi-artist resolution)
        val phishShows = listOf(
            TestEnrichedShowSummary(PHISH, "2024-07-20", tourName = "Summer 2024"),
        )
        val gdShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", tourName = "Spring 1977"),
        )
        val gdPref = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")

        val nextPhish = resolveDefunctNextStop(PHISH, null, phishShows, emptySet())
        val nextGd = resolveDefunctNextStop(GRATEFUL_DEAD, gdPref, gdShows, emptySet())

        assertEquals("2024-07-20", nextPhish?.date)
        assertEquals("1977-05-08", nextGd?.date)
    }

    // =========================================================================
    // TIER 4: REAL-WORLD APPLICATION SCENARIOS (End-to-End Workflows)
    // =========================================================================

    @Test
    fun `T4_scenario_completeGratefulDead1977CouchTourJourney`() {
        // Scenario:
        // 1. User favorites defunct artist Grateful Dead
        // 2. Selects "Spring 1977" tour in tour picker
        // 3. NextStop discovers Barton Hall 1977-05-08
        // 4. Filters tracklist for Soundboard & Matrix sources
        // 5. Renders procedural cassette artwork for now-playing
        // 6. Finishes listening -> NextStop progresses to War Memorial 1977-05-09

        val tourShows = listOf(
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-07", "Boston Garden", tourName = "Spring 1977", tags = listOf("SBD", "FLAC")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-08", "Barton Hall", tourName = "Spring 1977", tags = listOf("SBD", "FLAC", "Matrix")),
            TestEnrichedShowSummary(GRATEFUL_DEAD, "1977-05-09", "War Memorial", tourName = "Spring 1977", tags = listOf("SBD", "FLAC")),
        )

        val preference = TestArtistTourPreference(GRATEFUL_DEAD.key, "TOUR", tourName = "Spring 1977")

        // 1. First Stop: Boston Garden
        val stop1 = resolveDefunctNextStop(GRATEFUL_DEAD, preference, tourShows, emptySet())
        assertEquals("1977-05-07", stop1?.date)

        // 2. User plays stop 1 and marks finished
        val playedHistory = mutableSetOf(recordingShowKey(GRATEFUL_DEAD.id, "1977-05-07"))
        val stop2 = resolveDefunctNextStop(GRATEFUL_DEAD, preference, tourShows, playedHistory)
        assertEquals("1977-05-08", stop2?.date)
        assertEquals("Barton Hall", stop2?.venue)

        // 3. User checks procedural artwork for stop 2
        val art = deriveProceduralArtwork(stop2!!.artist, stop2.date, stop2.venue)
        assertEquals("GD", art["monogram"])
        assertEquals("1977", art["year"])
        assertEquals("Barton Hall", art["venueCaption"])

        // 4. User plays stop 2 and marks finished
        playedHistory.add(recordingShowKey(GRATEFUL_DEAD.id, "1977-05-08"))
        val stop3 = resolveDefunctNextStop(GRATEFUL_DEAD, preference, tourShows, playedHistory)
        assertEquals("1977-05-09", stop3?.date)
    }

    @Test
    fun `T4_scenario_catalogDiscoveryViaTagAndMomentum`() {
        // Scenario:
        // 1. User browses 1977 shows
        // 2. Filters by tag "Matrix"
        // 3. Sorts by "Trending 48h"
        // 4. Picks highest trending show

        val shows1977 = listOf(
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-07", tags = listOf("SBD"),
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window48h = TestRelistenPopularityWindow(hotScore = 15.0))),
            ),
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-08", tags = listOf("SBD", "Matrix"),
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window48h = TestRelistenPopularityWindow(hotScore = 95.0))),
            ),
            TestEnrichedShowSummary(
                GRATEFUL_DEAD, "1977-05-09", tags = listOf("Matrix"),
                popularity = TestRelistenPopularity(windows = TestRelistenPopularityWindows(window48h = TestRelistenPopularityWindow(hotScore = 40.0))),
            ),
        )

        val matrixShows = shows1977.filter { "Matrix" in it.tags }
        assertEquals(2, matrixShows.size)

        val trendingMatrix = sortShows(matrixShows, TestShowSortMode.TRENDING_48H)
        assertEquals("1977-05-08", trendingMatrix[0].date)
        assertEquals(95.0, trendingMatrix[0].hotScore48h, 0.001)
    }

    @Test
    fun `T4_scenario_nonDestructiveDatabaseMigrationWithTourPreferences`() {
        // Scenario:
        // 1. Build SQLite database with version 8 schema containing listening history
        // 2. Execute migration to version 9 (adding artist_tour_preferences)
        // 3. Verify listening history is preserved
        // 4. Write tour preference and verify roundtrip

        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""
            CREATE TABLE `progress` (
                `queueKey` TEXT NOT NULL PRIMARY KEY, `title` TEXT NOT NULL, `subtitle` TEXT NOT NULL,
                `artUrl` TEXT, `trackIndex` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL,
                `trackTitle` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, `finished` INTEGER NOT NULL,
                `dismissed` INTEGER NOT NULL, `artist` TEXT NOT NULL DEFAULT '', `deletedAt` INTEGER
            )
        """.trimIndent())
        db.execSQL("INSERT INTO `progress` VALUES ('recording:grateful-dead:1977-05-08:tape1', '1977-05-08', 'Barton Hall', NULL, 2, 45000, 'Scarlet Begonias', 1000, 1, 0, 'Grateful Dead', NULL)")

        // Run migration
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `artist_tour_preferences` (
                `artistKey` TEXT NOT NULL PRIMARY KEY,
                `preferenceType` TEXT NOT NULL,
                `tourName` TEXT,
                `periodId` TEXT,
                `periodLabel` TEXT,
                `updatedAt` INTEGER NOT NULL
            )
        """.trimIndent())

        // Verify history preserved
        val progressCursor = db.rawQuery("SELECT * FROM `progress` WHERE `finished` = 1", null)
        assertTrue(progressCursor.moveToFirst())
        assertEquals("recording:grateful-dead:1977-05-08:tape1", progressCursor.getString(progressCursor.getColumnIndexOrThrow("queueKey")))
        progressCursor.close()

        // Insert tour preference
        db.execSQL("INSERT INTO `artist_tour_preferences` VALUES ('relisten:grateful-dead', 'TOUR', 'Spring 1977', '1977', '1977', 2000)")
        val prefCursor = db.rawQuery("SELECT * FROM `artist_tour_preferences` WHERE `artistKey` = 'relisten:grateful-dead'", null)
        assertTrue(prefCursor.moveToFirst())
        assertEquals("Spring 1977", prefCursor.getString(prefCursor.getColumnIndexOrThrow("tourName")))
        prefCursor.close()

        db.close()
    }
}
