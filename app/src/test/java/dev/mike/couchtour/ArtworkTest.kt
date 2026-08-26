package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for procedural artwork generation, deterministic hashing,
 * color palette mapping, and monogram/date parsing.
 */
class ArtworkTest {

    private val gratefulDead = ArtistRef(Backend.RELISTEN, "grateful-dead", "Grateful Dead")
    private val phish = ArtistRef(Backend.PHISHIN, "phish", "Phish")
    private val wsp = ArtistRef(Backend.RELISTEN, "wsp", "Widespread Panic")
    private val billyStrings = ArtistRef(Backend.RELISTEN, "billy-strings", "Billy Strings")

    // ---------------------------------------------------------------- seed derivation

    @Test
    fun `deriveArtworkSeed pairs artist key and date deterministically`() {
        val seed = deriveArtworkSeed(artistKey = gratefulDead.key, date = "1977-05-08")
        assertEquals("relisten:grateful-dead:1977-05-08", seed)
    }

    @Test
    fun `deriveArtworkSeed falls back to artist name when artist key is null`() {
        val seed = deriveArtworkSeed(artistName = "Grateful Dead", date = "1977-05-08")
        assertEquals("grateful dead:1977-05-08", seed)
    }

    @Test
    fun `deriveArtworkSeed trims whitespace and normalizes case`() {
        val seed = deriveArtworkSeed(artistName = "  Phish  ", date = " 1997-11-17 ")
        assertEquals("phish:1997-11-17", seed)
    }

    @Test
    fun `deriveArtworkSeed handles artist-only and date-only inputs`() {
        assertEquals("phish", deriveArtworkSeed(artistName = "Phish", date = null))
        assertEquals("couchtour:1977-05-08", deriveArtworkSeed(artistName = null, date = "1977-05-08"))
    }

    @Test
    fun `deriveArtworkSeed returns default fallback for null or empty inputs`() {
        assertEquals("couchtour:default", deriveArtworkSeed(null, null, null))
        assertEquals("couchtour:default", deriveArtworkSeed("", "", ""))
        assertEquals("couchtour:default", deriveArtworkSeed("   ", "   ", "   "))
    }

    // ---------------------------------------------------------------- hashing

    @Test
    fun `hashArtworkSeed is deterministic across repeated executions`() {
        val seed = "relisten:grateful-dead:1977-05-08"
        val hash1 = hashArtworkSeed(seed)
        val hash2 = hashArtworkSeed(seed)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `distinct show dates produce distinct hashes`() {
        val hashCornell = hashArtworkSeed("relisten:grateful-dead:1977-05-08")
        val hashBuffalo = hashArtworkSeed("relisten:grateful-dead:1977-05-09")
        val hashVeneta = hashArtworkSeed("relisten:grateful-dead:1972-08-27")

        assertNotEquals(hashCornell, hashBuffalo)
        assertNotEquals(hashCornell, hashVeneta)
        assertNotEquals(hashBuffalo, hashVeneta)
    }

    @Test
    fun `distinct artists on same date produce distinct hashes`() {
        val hashDead = hashArtworkSeed("relisten:grateful-dead:1997-11-17")
        val hashPhish = hashArtworkSeed("phishin:phish:1997-11-17")
        assertNotEquals(hashDead, hashPhish)
    }

    // ---------------------------------------------------------------- palette mapping

    @Test
    fun `curated palettes count is 16 and all IDs match index`() {
        assertEquals(16, ARTWORK_PALETTES.size)
        ARTWORK_PALETTES.forEachIndexed { index, palette ->
            assertEquals(index, palette.id)
            assertTrue(palette.name.isNotBlank())
            assertTrue(palette.backgroundStart != 0L)
            assertTrue(palette.backgroundMid != 0L)
            assertTrue(palette.backgroundEnd != 0L)
            assertTrue(palette.accentColor != 0L)
            assertTrue(palette.tapeShellColor != 0L)
            assertTrue(palette.labelColor != 0L)
            assertTrue(palette.textColor != 0L)
            assertTrue(palette.subtextColor != 0L)
            assertTrue(palette.reelColor != 0L)
        }
    }

    @Test
    fun `getArtworkPalette returns valid palette and is deterministic`() {
        val seed = "relisten:grateful-dead:1977-05-08"
        val palette1 = getArtworkPalette(seed)
        val palette2 = getArtworkPalette(seed)

        assertEquals(palette1.id, palette2.id)
        assertEquals(palette1.name, palette2.name)
        assertNotNull(palette1.bgStartColor)
        assertNotNull(palette1.bgMidColor)
        assertNotNull(palette1.bgEndColor)
        assertNotNull(palette1.accent)
        assertNotNull(palette1.tapeShell)
        assertNotNull(palette1.label)
        assertNotNull(palette1.text)
        assertNotNull(palette1.subtext)
        assertNotNull(palette1.reel)
    }

    @Test
    fun `palette selection distributes across curated palettes`() {
        val sampleShows = listOf(
            "relisten:grateful-dead:1977-05-08",
            "relisten:grateful-dead:1972-08-27",
            "relisten:grateful-dead:1989-07-07",
            "phishin:phish:1997-11-17",
            "phishin:phish:1998-10-31",
            "relisten:wsp:2001-04-22",
            "relisten:billy-strings:2023-10-31",
            "relisten:goose:2022-06-25",
            "relisten:disco-biscuits:2009-06-06",
            "relisten:moe:2000-11-11",
        )
        val selectedPalettes = sampleShows.map { getArtworkPalette(it).id }.toSet()
        assertTrue(selectedPalettes.size >= 4)
    }

    // ---------------------------------------------------------------- artist monogram

    @Test
    fun `deriveArtistMonogram extracts two initials for multi-word artists`() {
        assertEquals("GD", deriveArtistMonogram("Grateful Dead"))
        assertEquals("WP", deriveArtistMonogram("Widespread Panic"))
        assertEquals("BS", deriveArtistMonogram("Billy Strings"))
        assertEquals("DB", deriveArtistMonogram("Disco Biscuits"))
        assertEquals("KG", deriveArtistMonogram("King Gizzard & The Lizard Wizard"))
        assertEquals("JG", deriveArtistMonogram("Jerry Garcia"))
    }

    @Test
    fun `deriveArtistMonogram extracts first two characters for single-word artists`() {
        assertEquals("PH", deriveArtistMonogram("Phish"))
        assertEquals("GO", deriveArtistMonogram("Goose"))
        assertEquals("MO", deriveArtistMonogram("Moe"))
    }

    @Test
    fun `deriveArtistMonogram handles edge cases and nulls`() {
        assertEquals("CT", deriveArtistMonogram(null))
        assertEquals("CT", deriveArtistMonogram(""))
        assertEquals("CT", deriveArtistMonogram("   "))
        assertEquals("A", deriveArtistMonogram("A"))
    }

    // ---------------------------------------------------------------- date components

    @Test
    fun `parseArtworkDateComponents formats full ISO date strings correctly`() {
        val parsed = parseArtworkDateComponents("1977-05-08")
        assertEquals("1977", parsed.year)
        assertEquals("MAY 08", parsed.monthDay)
        assertEquals("1977 · MAY 08", parsed.fullBadge)
    }

    @Test
    fun `parseArtworkDateComponents handles each month properly`() {
        assertEquals("JAN 01", parseArtworkDateComponents("2000-01-01").monthDay)
        assertEquals("FEB 15", parseArtworkDateComponents("1995-02-15").monthDay)
        assertEquals("MAR 20", parseArtworkDateComponents("1992-03-20").monthDay)
        assertEquals("APR 22", parseArtworkDateComponents("2001-04-22").monthDay)
        assertEquals("JUN 18", parseArtworkDateComponents("1994-06-18").monthDay)
        assertEquals("JUL 07", parseArtworkDateComponents("1989-07-07").monthDay)
        assertEquals("AUG 27", parseArtworkDateComponents("1972-08-27").monthDay)
        assertEquals("SEP 12", parseArtworkDateComponents("1990-09-12").monthDay)
        assertEquals("OCT 31", parseArtworkDateComponents("1998-10-31").monthDay)
        assertEquals("NOV 17", parseArtworkDateComponents("1997-11-17").monthDay)
        assertEquals("DEC 31", parseArtworkDateComponents("1995-12-31").monthDay)
    }

    @Test
    fun `parseArtworkDateComponents handles non-ISO or null dates gracefully`() {
        assertEquals("1977", parseArtworkDateComponents("1977").year)
        assertEquals("", parseArtworkDateComponents("1977").monthDay)
        assertEquals("1977", parseArtworkDateComponents("1977").fullBadge)

        val nullParsed = parseArtworkDateComponents(null)
        assertEquals("LIVE", nullParsed.year)
        assertEquals("", nullParsed.monthDay)
        assertEquals("LIVE", nullParsed.fullBadge)
    }

    // ---------------------------------------------------------------- ShowSummary integration

    @Test
    fun `ShowSummary artwork seed and palette derivation matches direct call`() {
        val show = ShowSummary(
            artist = gratefulDead,
            date = "1977-05-08",
            venue = "Barton Hall, Cornell University",
            location = "Ithaca, NY",
        )
        val seedFromShow = deriveArtworkSeed(artistKey = show.artist.key, date = show.date)
        val seedDirect = deriveArtworkSeed("relisten:grateful-dead", date = "1977-05-08")
        assertEquals(seedDirect, seedFromShow)

        val palette = getArtworkPalette(seedFromShow)
        assertEquals(palette, getArtworkPalette(seedDirect))
    }
}
