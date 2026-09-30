package dev.mike.couchtour

import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsScreenTest {

    @get:Rule
    val compose = createComposeRule()

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
            override fun getApplicationContext(): Context = this
        }
        DiagnosticsLog.resetForTest()
        DiagnosticsLog.init(context)
        DiagnosticsLog.flushBlocking()
        CrashCapture.resetForTest(context)
    }

    @After
    fun tearDown() {
        DiagnosticsLog.clear()
        DiagnosticsLog.resetForTest()
        CrashCapture.resetForTest()
    }

    @Test
    fun `read path returns seeded log lines newest-last in order with summary count and size`() {
        runBlocking {
        // Seed 250 log events
        for (i in 0 until 250) {
            DiagnosticsLog.log("event_$i", DiagnosticsLog.Level.INFO, "idx" to i)
        }
        DiagnosticsLog.mark("Test mark", "seeded value")
        DiagnosticsLog.flushBlocking()

        val data = readDiagnosticsData()

        // Bounded at 200 lines
        assertEquals(200, data.lines.size)

        // Newest line is last
        val lastLine = data.lines.last()
        assertTrue("Last line must contain newest event_249: $lastLine", lastLine.contains("event_249"))

        // First line is chronological window start (event_50)
        val firstLine = data.lines.first()
        assertTrue("First line must contain event_50: $firstLine", firstLine.contains("event_50"))

        // Summary header contains marked values, entry count, and on-disk size
        assertTrue("Summary must include marked value", data.summary.contains("Test mark: seeded value"))
        assertTrue("Summary must include total entries", data.summary.contains("Total entries: 251")) // 250 + log.start
        assertTrue("Summary must include on-disk size", data.summary.contains("On-disk size:"))
        assertEquals(251L, data.count)
        assertTrue("On-disk size in bytes must be positive", data.bytes > 0L)
        }
    }

    @Test
    fun `copy puts exportText on clipboard and share builds ACTION_SEND intent with text plain`() {
        DiagnosticsLog.log("test.copy_share", DiagnosticsLog.Level.INFO, "foo" to "bar")
        DiagnosticsLog.flushBlocking()

        val export = DiagnosticsLog.exportText(context)
        assertTrue("Export text should contain logged event", export.contains("test.copy_share"))

        // Copy to clipboard
        val copied = copyDiagnosticsToClipboard(context)
        assertEquals(export, copied)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull("Primary clip should not be null", clip)
        assertEquals(1, clip!!.itemCount)
        assertEquals(export, clip.getItemAt(0).text.toString())

        // Share intent
        val intent = createDiagnosticsShareIntent(context)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Couch Tour diagnostics", intent.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(export, intent.getStringExtra(Intent.EXTRA_TEXT))

        // Launch share in Activity chooser
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        launchDiagnosticsShare(activity)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started.action)
        val wrapped = started.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, wrapped.action)
        assertEquals("text/plain", wrapped.type)
        assertEquals(export, wrapped.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `clear calls through to DiagnosticsLog clear and consumePreviousCrash removing files and yielding empty state`() {
        runBlocking {
            DiagnosticsLog.log("to_be_cleared", DiagnosticsLog.Level.INFO)
            DiagnosticsLog.flushBlocking()

            val diagDir = File(testFilesDir, "diagnostics")
            val crashFile = File(diagDir, "last_crash.txt")
            crashFile.writeText("2026-09-30T01:00:00.000Z\tERROR\tcrash\tdetail=SampleCrash", Charsets.UTF_8)
            CrashCapture.setLastCrashNoticeForTest(crashFile.readText())

            assertTrue("Log file should exist before clear", File(diagDir, "diagnostics.log").exists())
            assertTrue("Crash file should exist before clear", crashFile.exists())
            assertNotNull("Crash notice should be present", CrashCapture.lastCrashNotice.value)

            val dataAfterClear = clearDiagnosticsData()

            assertFalse("Log file should be deleted after clear", File(diagDir, "diagnostics.log").exists())
            assertFalse("Crash file should be deleted after clear", crashFile.exists())
            assertNull("Crash notice should be cleared", CrashCapture.lastCrashNotice.value)
            assertTrue("Lines should be empty after clear", dataAfterClear.lines.isEmpty())
            assertEquals(0L, dataAfterClear.count)
        }
    }

    @Test
    fun `lastCrashNotice that has been consumed does not reappear on second composition`() = runBlocking {
        val diagDir = File(testFilesDir, "diagnostics")
        val crashFile = File(diagDir, "last_crash.txt")
        val crashContent = "2026-09-30T02:00:00.000Z\tERROR\tcrash\tdetail=main · java.lang.RuntimeException: seeded\n\tat Test.run"
        crashFile.writeText(crashContent, Charsets.UTF_8)
        CrashCapture.setLastCrashNoticeForTest(crashContent)

        assertEquals(crashContent, CrashCapture.lastCrashNotice.value)

        // Consume crash notice
        val consumed = CrashCapture.consumePreviousCrash()
        assertEquals(crashContent, consumed)
        assertNull("Crash notice should be null after consume", CrashCapture.lastCrashNotice.value)
        assertFalse("Crash file should be deleted after consume", crashFile.exists())

        // Second call returns null and remains null
        val secondConsume = CrashCapture.consumePreviousCrash()
        assertNull("Second consume should return null", secondConsume)
        assertNull("Crash notice should still be null", CrashCapture.lastCrashNotice.value)
    }

    @Test
    fun `parseCrashNotice handles tab-separated lines, ISO timestamps, and raw traces`() {
        val tabSeparated = "2026-09-30T02:00:00.000Z\tERROR\tcrash\tdetail=main · java.lang.NullPointerException: null\n\tat Foo.bar"
        val (ts1, trace1) = parseCrashNotice(tabSeparated)
        assertEquals("2026-09-30T02:00:00.000Z", ts1)
        assertTrue("Trace should contain exception", trace1.contains("java.lang.NullPointerException") || trace1.contains("Foo.bar"))

        val colonFormatted = "2026-09-30T02:00:00.000Z: java.lang.IllegalStateException: bad state\n\tat Bar.baz"
        val (ts2, trace2) = parseCrashNotice(colonFormatted)
        assertEquals("2026-09-30T02:00:00.000Z", ts2)
        assertEquals("java.lang.IllegalStateException: bad state", trace2)

        val plainTrace = "java.lang.RuntimeException: something broke\n\tat Baz.qux"
        val (ts3, trace3) = parseCrashNotice(plainTrace)
        assertEquals("", ts3)
        assertEquals("java.lang.RuntimeException: something broke", trace3)
    }

    @Test
    fun `compose ui renders diagnostics screen and allows dismissing crash card`() {
        DiagnosticsLog.log("ui_test_event", DiagnosticsLog.Level.WARN, "step" to "initial")
        DiagnosticsLog.flushBlocking()
        CrashCapture.setLastCrashNoticeForTest("2026-09-30T03:00:00.000Z: java.lang.Exception: UI crash")

        var backInvoked = false

        compose.setContent {
            DiagnosticsScreen(onBack = { backInvoked = true })
        }

        // Top bar Back button
        compose.onNodeWithTag("diagnostics.back_button").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics.back_button").performClick()
        assertTrue("Back should be invoked when clicked", backInvoked)

        // Crash card displayed
        compose.onNodeWithTag("diagnostics.crash_card").assertIsDisplayed()
        compose.onNodeWithText("Previous run crashed").assertIsDisplayed()

        // Dismiss crash notice
        compose.onNodeWithTag("diagnostics.crash_dismiss").performClick()
        compose.waitForIdle()
        assertNull("CrashCapture.lastCrashNotice should be null after clicking dismiss", CrashCapture.lastCrashNotice.value)

        // Summary header displayed
        compose.onNodeWithTag("diagnostics.summary_header").assertIsDisplayed()

        // Copy, Share, Clear buttons displayed
        compose.onNodeWithTag("diagnostics.copy_button").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics.share_button").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics.clear_button").assertIsDisplayed()
    }

    @Test
    fun `compose ui shows empty state when no diagnostics exist`() {
        runBlocking {
            clearDiagnosticsData()
        }

        compose.setContent {
            DiagnosticsScreen(onBack = {})
        }

        compose.onNodeWithTag("diagnostics.empty_state").assertIsDisplayed()
        compose.onNodeWithText("No diagnostics yet. They appear after you use the app for a while.").assertIsDisplayed()
    }

    @Test
    fun `exportText and copy and share bound text to MAX_EXPORT_BYTES avoiding TransactionTooLargeException`() {
        val diagDir = File(testFilesDir, "diagnostics")
        val current = File(diagDir, "diagnostics.log")
        val previous = File(diagDir, "diagnostics.log.1")

        // Seed 300 KiB across both files (exceeding MAX_EXPORT_BYTES = 256 KiB)
        val sb1 = StringBuilder()
        for (i in 0 until 3500) {
            sb1.append("2026-09-30T01:00:00.000Z\tINFO\tlog1.event_$i\tindex=$i\n")
        }
        previous.writeText(sb1.toString(), Charsets.UTF_8)

        val sb0 = StringBuilder()
        for (i in 0 until 3500) {
            sb0.append("2026-09-30T02:00:00.000Z\tINFO\tlog0.event_$i\tindex=$i\n")
        }
        current.writeText(sb0.toString(), Charsets.UTF_8)

        val exported = DiagnosticsLog.exportText(context)
        assertTrue("Exported size must be <= MAX_EXPORT_BYTES", exported.length <= DiagnosticsLog.MAX_EXPORT_BYTES)
        assertTrue("Exported size must be substantial", exported.length > 200 * 1024)
        // Verify line alignment (first line must start at timestamp)
        assertTrue("Exported text must start on a clean line boundary", exported.startsWith("2026-09-30T"))
        assertTrue("Exported text must contain recent log0 events", exported.contains("log0.event_1499"))

        // Copy does not crash and populates clipboard with bounded text
        val copied = copyDiagnosticsToClipboard(context)
        assertEquals(exported, copied)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull(clip)
        assertEquals(exported, clip!!.getItemAt(0).text.toString())

        // Share intent has bounded text
        val intent = createDiagnosticsShareIntent(context)
        assertEquals(exported, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `CrashCapture install loads previous crash asynchronously without blocking main thread`() = runBlocking {
        val diagDir = File(testFilesDir, "diagnostics")
        if (!diagDir.exists()) diagDir.mkdirs()
        val crashFile = File(diagDir, "last_crash.txt")
        val crashContent = "2026-09-30T00:30:00.000Z\tERROR\tcrash\tdetail=startup crash"
        crashFile.writeText(crashContent, Charsets.UTF_8)

        // Install with test scope and join
        val job = CrashCapture.install(context, this)
        job.join()

        assertEquals(crashContent, CrashCapture.lastCrashNotice.value)
    }
}
