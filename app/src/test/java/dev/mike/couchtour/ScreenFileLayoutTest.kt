package dev.mike.couchtour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-layout gate for the #495 split of `MainActivity.kt` (#608): each extracted screen group
 * must stay in its focused file, so a later edit can't quietly grow `MainActivity.kt` back into
 * a 4800-line file or leave two copies of a screen around. Reads sources relative to the `app/`
 * module, like [CuratedMatchesTest].
 */
class ScreenFileLayoutTest {

    private val sourceDir = File("src/main/java/dev/mike/couchtour")

    private val groups: Map<String, List<String>> = mapOf(
        "HomeScreen.kt" to listOf("HomeScreen", "InProgressLedgerRow"),
        "CatalogScreens.kt" to listOf(
            "ArtistsScreen", "ArtistScreen", "ArtistShowsScreen", "ShowsScreen",
            "TourPickerDialog", "YouTubeSectionContent", "YouTubeVideoScreen",
        ),
        "ShowScreens.kt" to listOf(
            "ShowScreen", "RecordingScreen", "SourceRow", "SourceBadge",
            "TagBadge", "AddTracksToPlaylistDialog",
        ),
        "LibraryScreens.kt" to listOf(
            "PlaylistsScreen", "PlaylistScreen", "PlaylistRow", "LocalPlaylistsScreen",
            "LocalPlaylistScreen", "MyShowsScreen", "MyTracksScreen", "HistoryScreen",
        ),
        "AccountScreens.kt" to listOf("LoginScreen", "SyncScreen"),
    )

    private val funName = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")

    /** File name -> composable function names declared in it (overloads collapse to one name). */
    private fun composablesByFile(): Map<String, Set<String>> {
        val files = sourceDir.listFiles { f -> f.extension == "kt" }.orEmpty()
        assertTrue("no Kotlin sources found under ${sourceDir.absolutePath}", files.isNotEmpty())
        return files.associate { it.name to composablesIn(it.readLines()) }
    }

    private fun composablesIn(lines: List<String>): Set<String> {
        val names = mutableSetOf<String>()
        lines.forEachIndexed { i, raw ->
            val line = raw.trim()
            if (!line.startsWith("@Composable")) return@forEachIndexed
            // `@Composable fun X(` on one line.
            funName.find(line.removePrefix("@Composable"))?.let { names += it.groupValues[1]; return@forEachIndexed }
            // Otherwise the declaration follows, possibly after more annotations.
            var j = i + 1
            while (j < lines.size) {
                val next = lines[j].trim()
                if (next.isEmpty() || next.startsWith("@")) { j++; continue }
                funName.find(next)?.let { names += it.groupValues[1] }
                break
            }
        }
        return names
    }

    @Test
    fun `screen groups are declared in their focused files`() {
        val byFile = composablesByFile()
        groups.forEach { (file, names) ->
            val declared = byFile[file] ?: error("$file is missing")
            names.forEach { name ->
                assertTrue("$name should be declared in $file", name in declared)
                val elsewhere = byFile.filter { (other, decl) -> other != file && name in decl }.keys
                assertEquals("$name should be declared only in $file", emptySet<String>(), elsewhere)
            }
        }
    }

    @Test
    fun `MainActivity declares none of the extracted screen composables`() {
        val main = composablesByFile()["MainActivity.kt"] ?: error("MainActivity.kt is missing")
        val leaked = groups.values.flatten().filter { it in main }
        assertEquals("extracted composables found in MainActivity.kt", emptyList<String>(), leaked)
    }

    @Test
    fun `no composable name is declared in more than one file`() {
        val owners = mutableMapOf<String, MutableList<String>>()
        composablesByFile().forEach { (file, names) -> names.forEach { owners.getOrPut(it) { mutableListOf() } += file } }
        val duplicates = owners.filterValues { it.size > 1 }.mapValues { it.value.sorted() }
        assertEquals("composables declared in more than one file", emptyMap<String, List<String>>(), duplicates)
    }
}
