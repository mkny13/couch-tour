package dev.mike.couchtour

import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuratedMatchesTest {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesBundledCuratedMatches() {
        val jsonStr = File("src/main/assets/curated_releases.json").readText()
        val mappings: Map<String, ExternalRelease> = jsonFormat.decodeFromString(jsonStr)

        val match = mappings["phishin:phish:1995-11-14"]
        assertNotNull(match)
        assertEquals(ExternalReleasePlatform.SPOTIFY, match?.platform)
    }

    @Test
    fun decodesBundledHeuristicMatches() {
        val jsonStr = File("src/main/assets/heuristic_matches.json").readText()
        val mappings: Map<String, ExternalRelease> = jsonFormat.decodeFromString(jsonStr)

        val phishMatch = mappings["phishin:phish:1994-06-22"]
        assertNotNull(phishMatch)
        assertEquals(ExternalReleasePlatform.SPOTIFY, phishMatch?.platform)
        assertTrue(phishMatch?.isHeuristic == true)

        val deadMatch = mappings["relisten:grateful-dead:1970-02-13"]
        assertNotNull(deadMatch)
        assertEquals(ExternalReleasePlatform.SPOTIFY, deadMatch?.platform)
        assertTrue(deadMatch?.isHeuristic == true)
    }

    @Test
    fun decodesTidalLiteral() {
        val jsonStr = """
            {
              "test:artist:date": {
                "platform": "tidal",
                "url": "https://tidal.com/browse/album/12345"
              }
            }
        """.trimIndent()
        
        val mappings: Map<String, ExternalRelease> = jsonFormat.decodeFromString(jsonStr)
        val match = mappings["test:artist:date"]
        
        assertNotNull(match)
        assertEquals(ExternalReleasePlatform.TIDAL, match?.platform)
        assertEquals("https://tidal.com/browse/album/12345", match?.url)
    }
}
