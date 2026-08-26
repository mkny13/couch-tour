package dev.mike.couchtour

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Adversarial and empirical stress test suite for Phase 2 Batch 2 Milestone M1:
 * - Synthetic tag derivation under missing, partial, or conflicting metadata.
 * - Popularity window parsing and score computation under missing windows, zero plays, negative scores, null popularity, NaN/Infinity safety.
 * - Multi-tier sorting stability under equal scores, tie-breakers, null ratings, and reverse date sorting.
 */
class Milestone1StressTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val deadArtist = ArtistRef(
        backend = Backend.RELISTEN,
        id = "grateful-dead",
        name = "Grateful Dead",
        hasSets = false,
        hasMultipleSources = true,
    )

    private val phishArtist = PHISH

    // =========================================================================
    // 1. SYNTHETIC TAG DERIVATION UNDER MISSING, PARTIAL, OR CONFLICTING DATA
    // =========================================================================

    @Test
    fun `tag derivation handles whitespace, blank, and null taper and lineage strings`() {
        val blanks = listOf("", "   ", "\t\n", "   \r\n  ")
        for (blank in blanks) {
            val rec = RecordingRef(
                id = "test-blank",
                label = "Tape",
                taper = blank,
                lineage = blank,
                isSoundboard = false,
                hasFlac = false,
            )
            assertFalse("Blank string '$blank' should not trigger matrix", rec.looksLikeMatrix)
            val derived = deriveSyntheticTags(rec)
            assertTrue("No tags should be derived for purely blank non-sbd non-flac recording", derived.isEmpty())
        }
    }

    @Test
    fun `tag derivation handles matrix variations and case variations`() {
        val matrixVariations = listOf(
            "Matrix",
            "matrix",
            "MATRIX",
            "SBD/AUD Matrix",
            "Matrix by Charlie Miller",
            "Matrix 4-Source",
            "Rematrixed by Dusborne",
            "A fine matrix blend",
            "Lineage: Matrix > DAT",
        )

        for (v in matrixVariations) {
            val recTaper = RecordingRef(id = "rec-taper", label = "Tape", taper = v)
            assertTrue("Taper '$v' should match looksLikeMatrix", recTaper.looksLikeMatrix)

            val recLineage = RecordingRef(id = "rec-lineage", label = "Tape", lineage = v)
            assertTrue("Lineage '$v' should match looksLikeMatrix", recLineage.looksLikeMatrix)

            val tags = deriveSyntheticTags(recTaper)
            assertEquals("Should have exactly 1 Matrix tag", 1, tags.count { it.name == "Matrix" })
        }
    }

    @Test
    fun `tag derivation does not duplicate Matrix tag when both taper and lineage match`() {
        val rec = RecordingRef(
            id = "both",
            label = "Tape",
            taper = "Seamons Matrix",
            lineage = "SBD + AUD Matrix 5.1",
            isSoundboard = true,
            hasFlac = true,
        )
        val tags = deriveSyntheticTags(rec)
        assertEquals(3, tags.size)
        assertEquals(1, tags.count { it.name == "SBD" })
        assertEquals(1, tags.count { it.name == "Matrix" })
        assertEquals(1, tags.count { it.name == "FLAC" })
    }

    @Test
    fun `RelistenSource mapping cleans blank taper and falls back to SBD or Audience`() {
        val sbdBlankTaper = RelistenSource(uuid = "s1", isSoundboard = true, taper = "   ", lineage = "")
        val sbdRec = sbdBlankTaper.toRecordingRef()
        assertEquals("Soundboard", sbdRec.label)
        assertNull(sbdRec.taper)
        assertNull(sbdRec.lineage)
        assertTrue(sbdRec.tags.any { it.name == "SBD" })
        assertFalse(sbdRec.tags.any { it.name == "Matrix" })

        val audBlankTaper = RelistenSource(uuid = "s2", isSoundboard = false, taper = "", lineage = "   ")
        val audRec = audBlankTaper.toRecordingRef()
        assertEquals("Audience", audRec.label)
        assertNull(audRec.taper)
        assertNull(audRec.lineage)
        assertTrue(audRec.tags.isEmpty())
    }

    @Test
    fun `track FLAC tag is not duplicated if sourceTags already contains FLAC`() {
        val track = RelistenSourceTrack(
            uuid = "t1",
            title = "Dark Star",
            duration = 1200,
            flacUrl = "https://relisten.net/flac/darkstar.flac",
        )
        val initialTags = listOf(SYNTHETIC_TAG_FLAC, SYNTHETIC_TAG_SBD)
        val playable = track.toPlayableTrack(
            artist = deadArtist,
            showDate = "1972-04-08",
            venueName = "Wembley",
            setName = "Set 2",
            sourceTags = initialTags,
        )
        assertEquals("Should not duplicate FLAC tag", 2, playable.tags.size)
        assertEquals(1, playable.tags.count { it.name.equals("FLAC", ignoreCase = true) })
    }

    @Test
    fun `tag filtering handles special regex characters and case variations`() {
        val shows = listOf(
            ShowSummary(artist = phishArtist, date = "1997-11-22", tags = listOf(TagRef(name = "Type-II (Extended)"), TagRef(name = "Jamcharts"))),
            ShowSummary(artist = phishArtist, date = "1997-12-30", tags = listOf(TagRef(name = "Jamcharts"))),
            ShowSummary(artist = phishArtist, date = "1998-04-03", tags = listOf(TagRef(name = "Bustout*"))),
        )

        assertEquals(1, shows.filterByTag("Type-II (Extended)").size)
        assertEquals(1, shows.filterByTag("type-ii (extended)").size)
        assertEquals(2, shows.filterByTag("JAMCHARTS").size)
        assertEquals(1, shows.filterByTag("Bustout*").size)
        assertEquals(3, shows.filterByTag("").size)
        assertEquals(3, shows.filterByTag("   ").size)
        assertEquals(3, shows.filterByTag("All").size)
        assertEquals(3, shows.filterByTag("all").size)
        assertEquals(0, shows.filterByTag("NonExistent").size)
    }

    // =========================================================================
    // 2. POPULARITY WINDOW PARSING & NUMERICAL SAFETY
    // =========================================================================

    @Test
    fun `parses empty and sparse popularity json without crashing`() {
        val emptyPopJson = """
        {
            "display_date": "1977-05-08",
            "popularity": {}
        }
        """
        val parsed = json.decodeFromString<RelistenShowSummary>(emptyPopJson)
        val domain = parsed.toShowSummary(deadArtist)
        assertNotNull(domain.popularity)
        assertEquals(0.0, domain.momentumScore, 0.0)
        assertEquals(0.0, domain.hotScore48h, 0.0)
        assertEquals(0.0, domain.hotScore7d, 0.0)
        assertEquals(0.0, domain.hotScore30d, 0.0)
    }

    @Test
    fun `parses partial windows where some windows are missing or null`() {
        val partialPopJson = """
        {
            "display_date": "1977-05-08",
            "popularity": {
                "momentum_score": 0.42,
                "windows": {
                    "7d": {
                        "plays": 50,
                        "hours": 12.5,
                        "hot_score": 8.5
                    }
                }
            }
        }
        """
        val parsed = json.decodeFromString<RelistenShowSummary>(partialPopJson)
        val domain = parsed.toShowSummary(deadArtist)
        assertNotNull(domain.popularity)
        assertEquals(0.42, domain.momentumScore, 0.0001)
        assertEquals(0.0, domain.hotScore48h, 0.0)
        assertEquals(8.5, domain.hotScore7d, 0.0001)
        assertEquals(0.0, domain.hotScore30d, 0.0)
    }

    @Test
    fun `handles negative and zero popularity scores safely`() {
        val negPopJson = """
        {
            "display_date": "1977-05-08",
            "popularity": {
                "momentum_score": -0.85,
                "trend_ratio": -1.2,
                "windows": {
                    "48h": { "plays": 0, "hours": 0.0, "hot_score": -10.5 },
                    "7d": { "plays": 0, "hours": 0.0, "hot_score": 0.0 },
                    "30d": { "plays": 1000000, "hours": 50000.0, "hot_score": 99999.9 }
                }
            }
        }
        """
        val parsed = json.decodeFromString<RelistenShowSummary>(negPopJson)
        val domain = parsed.toShowSummary(deadArtist)
        assertEquals(-0.85, domain.momentumScore, 0.0001)
        assertEquals(-10.5, domain.hotScore48h, 0.0001)
        assertEquals(0.0, domain.hotScore7d, 0.0001)
        assertEquals(99999.9, domain.hotScore30d, 0.0001)
    }

    @Test
    fun `handles NaN and Infinite popularity values during sorting without throwing`() {
        val normal = ShowSummary(artist = deadArtist, date = "1977-05-08", popularity = Popularity(hotScore48h = 25.0, momentumScore = 0.5))
        val nanShow = ShowSummary(artist = deadArtist, date = "1977-05-09", popularity = Popularity(hotScore48h = Double.NaN, momentumScore = Double.NaN))
        val posInfShow = ShowSummary(artist = deadArtist, date = "1977-05-10", popularity = Popularity(hotScore48h = Double.POSITIVE_INFINITY, momentumScore = 1.0))
        val negInfShow = ShowSummary(artist = deadArtist, date = "1977-05-11", popularity = Popularity(hotScore48h = Double.NEGATIVE_INFINITY, momentumScore = -1.0))

        val list = listOf(normal, nanShow, posInfShow, negInfShow)

        // Verify all sort modes execute stably without exceptions
        for (mode in ShowSortMode.values()) {
            val sorted = list.sortedByMode(mode)
            assertEquals(4, sorted.size)
        }
    }

    // =========================================================================
    // 3. MULTI-TIER SORTING STABILITY & DETERMINISM
    // =========================================================================

    @Test
    fun `sorts empty list and single element list safely across all modes`() {
        for (mode in ShowSortMode.values()) {
            val emptySorted = emptyList<ShowSummary>().sortedByMode(mode)
            assertTrue(emptySorted.isEmpty())

            val single = listOf(ShowSummary(artist = phishArtist, date = "1997-11-22"))
            val singleSorted = single.sortedByMode(mode)
            assertEquals(1, singleSorted.size)
            assertEquals("1997-11-22", singleSorted.first().date)
        }
    }

    @Test
    fun `multi-tier tie breaking resolves all 4 tiers in trending 48h`() {
        // Tier 1: hotScore48h
        // Tier 2: momentumScore
        // Tier 3: rating
        // Tier 4: date
        val s1 = ShowSummary(artist = deadArtist, date = "1977-05-01", rating = 8.0, popularity = Popularity(hotScore48h = 10.0, momentumScore = 0.5))
        val s2 = ShowSummary(artist = deadArtist, date = "1977-05-02", rating = 8.0, popularity = Popularity(hotScore48h = 20.0, momentumScore = 0.5)) // higher hot48
        val s3 = ShowSummary(artist = deadArtist, date = "1977-05-03", rating = 8.0, popularity = Popularity(hotScore48h = 20.0, momentumScore = 0.9)) // higher momentum
        val s4 = ShowSummary(artist = deadArtist, date = "1977-05-04", rating = 9.5, popularity = Popularity(hotScore48h = 20.0, momentumScore = 0.9)) // higher rating
        val s5 = ShowSummary(artist = deadArtist, date = "1977-05-05", rating = 9.5, popularity = Popularity(hotScore48h = 20.0, momentumScore = 0.9)) // higher date

        val list = listOf(s1, s3, s5, s2, s4)
        val sorted = list.sortedByMode(ShowSortMode.TRENDING_48H)

        assertEquals(listOf("1977-05-05", "1977-05-04", "1977-05-03", "1977-05-02", "1977-05-01"), sorted.map { it.date })
    }

    @Test
    fun `multi-tier tie breaking resolves all 4 tiers in hot 7d and popular 30d`() {
        val s1 = ShowSummary(artist = deadArtist, date = "1977-05-01", rating = 7.0, popularity = Popularity(hotScore7d = 50.0, hotScore30d = 100.0, momentumScore = 0.2))
        val s2 = ShowSummary(artist = deadArtist, date = "1977-05-02", rating = 7.0, popularity = Popularity(hotScore7d = 50.0, hotScore30d = 100.0, momentumScore = 0.8))
        val s3 = ShowSummary(artist = deadArtist, date = "1977-05-03", rating = 9.0, popularity = Popularity(hotScore7d = 50.0, hotScore30d = 100.0, momentumScore = 0.8))
        val s4 = ShowSummary(artist = deadArtist, date = "1977-05-04", rating = 9.0, popularity = Popularity(hotScore7d = 50.0, hotScore30d = 100.0, momentumScore = 0.8))

        val list = listOf(s1, s4, s2, s3)
        val sorted7d = list.sortedByMode(ShowSortMode.HOT_7D)
        assertEquals(listOf("1977-05-04", "1977-05-03", "1977-05-02", "1977-05-01"), sorted7d.map { it.date })

        val sorted30d = list.sortedByMode(ShowSortMode.POPULAR_30D)
        assertEquals(listOf("1977-05-04", "1977-05-03", "1977-05-02", "1977-05-01"), sorted30d.map { it.date })
    }

    @Test
    fun `momentum sort tie-breaks by hotScore48h, then rating, then date`() {
        val s1 = ShowSummary(artist = deadArtist, date = "1977-05-01", rating = 6.0, popularity = Popularity(momentumScore = 0.8, hotScore48h = 10.0))
        val s2 = ShowSummary(artist = deadArtist, date = "1977-05-02", rating = 6.0, popularity = Popularity(momentumScore = 0.8, hotScore48h = 30.0))
        val s3 = ShowSummary(artist = deadArtist, date = "1977-05-03", rating = 9.0, popularity = Popularity(momentumScore = 0.8, hotScore48h = 30.0))
        val s4 = ShowSummary(artist = deadArtist, date = "1977-05-04", rating = 9.0, popularity = Popularity(momentumScore = 0.8, hotScore48h = 30.0))

        val list = listOf(s2, s1, s4, s3)
        val sorted = list.sortedByMode(ShowSortMode.MOMENTUM)
        assertEquals(listOf("1977-05-04", "1977-05-03", "1977-05-02", "1977-05-01"), sorted.map { it.date })
    }

    @Test
    fun `sorting 1000 randomized shows is strictly deterministic`() {
        val rng = Random(42)
        val dates = (1970..2024).flatMap { year ->
            (1..12).map { month ->
                String.format("%04d-%02d-15", year, month)
            }
        }

        val generated = (1..1000).map { i ->
            val date = dates[rng.nextInt(dates.size)]
            val rating = rng.nextDouble(0.0, 10.0)
            val pop = if (rng.nextBoolean()) {
                Popularity(
                    momentumScore = rng.nextDouble(0.0, 1.0),
                    trendRatio = rng.nextDouble(0.0, 2.0),
                    hotScore48h = rng.nextDouble(0.0, 100.0),
                    hotScore7d = rng.nextDouble(0.0, 500.0),
                    hotScore30d = rng.nextDouble(0.0, 2000.0),
                )
            } else null
            ShowSummary(
                artist = if (rng.nextBoolean()) deadArtist else phishArtist,
                date = date,
                rating = rating,
                popularity = pop,
            )
        }

        for (mode in ShowSortMode.values()) {
            val pass1 = generated.sortedByMode(mode)
            val pass2 = generated.sortedByMode(mode)
            assertEquals("Sort mode $mode must be 100% deterministic", pass1.map { it.date to it.rating }, pass2.map { it.date to it.rating })
        }
    }
}
