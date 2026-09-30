package dev.mike.couchtour

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsLogTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var testFilesDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        testFilesDir = tmpFolder.newFolder("filesDir")
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        context = object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = testFilesDir
        }
        DiagnosticsLog.resetForTest()
        DiagnosticsLog.init(context)
        DiagnosticsLog.flushBlocking()
    }

    @After
    fun tearDown() {
        DiagnosticsLog.clear()
        DiagnosticsLog.resetForTest()
    }

    @Test
    fun `a log at the cap rotates on the next append and bounded at 2 MiB`() {
        val diagnosticsDir = File(testFilesDir, "diagnostics")
        val current = File(diagnosticsDir, "diagnostics.log")
        val previous = File(diagnosticsDir, "diagnostics.log.1")

        // 1 MiB cap
        val cap = 1024 * 1024L
        DiagnosticsLog.capBytes = cap

        // Fill current file to 1 MiB with dummy data
        FileOutputStream(current, false).use { out ->
            val chunk = ByteArray(1024) { 'A'.code.toByte() }
            for (i in 0 until 1024) {
                out.write(chunk)
            }
        }
        assertEquals(cap, current.length())

        // Next append triggers rotation
        DiagnosticsLog.log("test.after_cap", DiagnosticsLog.Level.INFO, "key1" to "val1")
        DiagnosticsLog.flushBlocking()

        // diagnostics.log.1 should now exist with the rotated 1 MiB content
        assertTrue("Previous log file should exist after rotation", previous.exists())
        assertEquals(cap, previous.length())

        // Current file should start fresh with log.rotated and the new entry
        assertTrue("Current log file should exist", current.exists())
        val currentLines = current.readLines()
        assertTrue("Current file should carry log.rotated", currentLines.any { it.contains("log.rotated") })
        assertTrue("Current file should carry test.after_cap", currentLines.any { it.contains("test.after_cap") })

        // On-disk total should not exceed 2 MiB
        val totalBytes = DiagnosticsLog.onDiskBytes()
        assertTrue("Total footprint ($totalBytes bytes) must not exceed 2 MiB", totalBytes <= 2 * 1024 * 1024L)

        // Fill current to 1 MiB again and trigger another rotation to prove .1 is replaced and total remains bounded
        FileOutputStream(current, true).use { out ->
            val chunk = ByteArray(1024) { 'B'.code.toByte() }
            while (current.length() < cap) {
                out.write(chunk)
            }
        }
        DiagnosticsLog.log("test.second_rotation", DiagnosticsLog.Level.INFO)
        DiagnosticsLog.flushBlocking()

        val totalBytesAfterSecondRotation = DiagnosticsLog.onDiskBytes()
        assertTrue(
            "Footprint after second rotation ($totalBytesAfterSecondRotation bytes) must not exceed 2 MiB",
            totalBytesAfterSecondRotation <= 2 * 1024 * 1024L
        )
    }

    @Test
    fun `retention drops lines older than 7 days and truncates to 2000 lines if still over cap`() {
        val diagnosticsDir = File(testFilesDir, "diagnostics")
        val current = File(diagnosticsDir, "diagnostics.log")

        val now = Instant.parse("2026-09-30T12:00:00.000Z")
        val eightDaysAgo = now.minus(8, ChronoUnit.DAYS).toString()
        val oneDayAgo = now.minus(1, ChronoUnit.DAYS).toString()

        // Create file over cap with 1000 lines older than 7 days and 500 lines newer than 7 days
        DiagnosticsLog.capBytes = 50 * 1024L // 50 KB cap for this test
        current.bufferedWriter().use { writer ->
            for (i in 1..1000) {
                writer.write("$eightDaysAgo\tINFO\tevent.old\tindex=$i\n")
            }
            for (i in 1..500) {
                writer.write("$oneDayAgo\tINFO\tevent.new\tindex=$i\n")
            }
        }
        assertTrue("File is over cap before retention", current.length() >= DiagnosticsLog.capBytes)

        // Run retention
        DiagnosticsLog.runRetention(current, now)

        var lines = current.readLines()
        assertEquals(500, lines.size)
        assertTrue(lines.all { it.contains("event.new") })
        assertFalse(lines.any { it.contains("event.old") })

        // Now test truncation down to 2000 lines when still over cap
        // Create 2500 large lines newer than 7 days such that file exceeds capBytes
        current.bufferedWriter().use { writer ->
            val padding = "x".repeat(100)
            for (i in 1..2500) {
                writer.write("$oneDayAgo\tINFO\tevent.large\tindex=$i pad=$padding\n")
            }
        }
        assertTrue(current.length() >= DiagnosticsLog.capBytes)

        DiagnosticsLog.runRetention(current, now)

        lines = current.readLines()
        assertEquals(2000, lines.size)
        // Kept the last 2000 lines (index 501 to 2500)
        assertTrue(lines.first().contains("index=501"))
        assertTrue(lines.last().contains("index=2500"))
    }

    @Test
    fun `redaction replaces credentials with asterisks while keeping non-credential fields`() {
        DiagnosticsLog.log(
            "auth.test",
            DiagnosticsLog.Level.INFO,
            "access_token" to "secret_access_token_123",
            "pairing_code" to "998877",
            "sync_key" to "my_sync_key_456",
            "auth" to "bearer_auth_jwt",
            "password" to "super_secret_pw",
            "passwd" to "secret_passwd",
            "client_secret" to "xyz_secret",
            "user_credential" to "user_cred_data",
            "session_cookie" to "cookie_content",
            "track_id" to 411,
            "show_date" to "1997-11-17"
        )
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(5)
        assertEquals(2, lines.size) // log.start from init + auth.test
        val line = lines.last()

        // Redacted fields
        assertTrue(line.contains("access_token=***"))
        assertTrue(line.contains("pairing_code=***"))
        assertTrue(line.contains("sync_key=***"))
        assertTrue(line.contains("auth=***"))
        assertTrue(line.contains("password=***"))
        assertTrue(line.contains("passwd=***"))
        assertTrue(line.contains("client_secret=***"))
        assertTrue(line.contains("user_credential=***"))
        assertTrue(line.contains("session_cookie=***"))

        // Unredacted safe fields
        assertTrue(line.contains("track_id=411"))
        assertTrue(line.contains("show_date=1997-11-17"))

        // Secrets must not appear anywhere
        assertFalse(line.contains("secret_access_token_123"))
        assertFalse(line.contains("998877"))
        assertFalse(line.contains("my_sync_key_456"))
        assertFalse(line.contains("bearer_auth_jwt"))
        assertFalse(line.contains("super_secret_pw"))

        assertEquals("***", DiagnosticsLog.redactValue("anything"))
    }

    @Test
    fun `log returns without throwing or blocking when filesDir is not writable and recordCrash swallows error`() {
        val readOnlyDir = File(tmpFolder.root, "unwritable_dir").apply {
            mkdirs()
            setWritable(false)
            setReadable(false)
        }
        val unwritableContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = readOnlyDir
        }

        DiagnosticsLog.resetForTest()
        DiagnosticsLog.init(unwritableContext)

        // log() on caller thread does not throw
        DiagnosticsLog.log("test.unwritable", DiagnosticsLog.Level.INFO, "key" to "value")

        // recordCrash() executes synchronously and swallows any I/O error
        DiagnosticsLog.recordCrash("crash.unwritable", "simulated crash on read-only disk")

        // No exception escaped!
    }

    @Test
    fun `log is non-blocking on the main thread`() {
        assertEquals("Test must be on main thread", Looper.getMainLooper(), Looper.myLooper())

        val start = System.currentTimeMillis()
        DiagnosticsLog.log("main.thread.test", DiagnosticsLog.Level.INFO, "thread" to "main")
        val elapsed = System.currentTimeMillis() - start

        // trySend is non-blocking on main thread (< 50ms)
        assertTrue("log() on main thread took too long: ${elapsed}ms", elapsed < 50)
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(1)
        assertTrue(lines.first().contains("main.thread.test"))
    }

    @Test
    fun `tailLines returns the last n lines in order and bounds the read window to at most 256 KiB`() {
        val diagnosticsDir = File(testFilesDir, "diagnostics")
        val current = File(diagnosticsDir, "diagnostics.log")

        // Write a > 1 MiB file consisting of 10,000 distinct lines
        current.bufferedWriter().use { writer ->
            val padding = "x".repeat(120)
            for (i in 1..10000) {
                writer.write("2026-09-30T00:00:00.000Z\tINFO\tevent.item\tline=$i\tdata=$padding\n")
            }
        }
        assertTrue("Log file must be at least 1 MiB", current.length() >= 1024 * 1024L)

        // tailLines(5) returns last 5 lines in order
        val tail5 = DiagnosticsLog.tailLines(5)
        assertEquals(5, tail5.size)
        for (i in 0 until 5) {
            val expectedLineNum = 9996 + i
            assertTrue("Line $i should contain line=$expectedLineNum", tail5[i].contains("line=$expectedLineNum"))
        }

        // Requesting 100,000 lines should NOT return the lines from the beginning of the 1 MiB file,
        // because the read window is bounded at 256 KiB
        val tailLarge = DiagnosticsLog.tailLines(100000)
        assertFalse("First line of 1 MiB file must not be in 256 KiB window", tailLarge.any { it.contains("line=1\t") })
        assertFalse("Line 1000 of 1 MiB file must not be in 256 KiB window", tailLarge.any { it.contains("line=1000\t") })
        assertTrue("Recent lines must be in the window", tailLarge.any { it.contains("line=9999\t") })
    }

    @Test
    fun `summaryLines reports the most recent value of each mark and accurate metrics`() {
        DiagnosticsLog.mark("Library counts", "playlists=1 shows=5")
        DiagnosticsLog.mark("Library counts", "playlists=3 shows=12 tracks=410")

        DiagnosticsLog.mark("last sync result", "failed")
        DiagnosticsLog.mark("last sync result", "ok")

        DiagnosticsLog.mark("last crash: java.lang.NullPointerException at Foo.kt:10")

        val summary = DiagnosticsLog.summaryLines()

        // Most recent values reported, older overwritten
        assertTrue(summary.contains("Library counts: playlists=3 shows=12 tracks=410"))
        assertFalse(summary.contains("playlists=1 shows=5"))

        assertTrue(summary.contains("last sync result: ok"))
        assertFalse(summary.contains("last sync result: failed"))

        assertTrue(summary.contains("last crash: java.lang.NullPointerException at Foo.kt:10"))

        assertTrue(summary.contains("Total entries: 1")) // from log.start during init
        assertTrue(summary.contains("On-disk size:"))
    }

    @Test
    fun `exportText returns older generation followed by current and clear resets files`() {
        val diagnosticsDir = File(testFilesDir, "diagnostics")
        val current = File(diagnosticsDir, "diagnostics.log")
        val previous = File(diagnosticsDir, "diagnostics.log.1")
        val crash = File(diagnosticsDir, "last_crash.txt")

        previous.writeText("generation.1.line\n")
        current.writeText("generation.0.line\n")
        crash.writeText("crash info\n")

        val exported = DiagnosticsLog.exportText(context)
        assertEquals("generation.1.line\ngeneration.0.line\n", exported)

        assertTrue(DiagnosticsLog.onDiskBytes() > 0)

        DiagnosticsLog.clear()

        assertFalse(previous.exists())
        assertFalse(current.exists())
        assertFalse(crash.exists())
        assertEquals(0L, DiagnosticsLog.onDiskBytes())
    }

    @Test
    fun `recordCrash writes synchronously with level ERROR`() {
        DiagnosticsLog.recordCrash("uncaught.exception", "Divide by zero")

        val lines = DiagnosticsLog.tailLines(5)
        val crashLine = lines.last()
        assertTrue(crashLine.contains("\tERROR\tuncaught.exception\tdetail=Divide by zero"))
    }

    @Test
    fun `init is idempotent and writes log start with app version`() {
        // init was already called in setUp, call it again
        DiagnosticsLog.init(context)
        DiagnosticsLog.flushBlocking()

        val lines = DiagnosticsLog.tailLines(10)
        assertTrue(lines.any { it.contains("\tINFO\tlog.start\tversion=") })
    }
}
