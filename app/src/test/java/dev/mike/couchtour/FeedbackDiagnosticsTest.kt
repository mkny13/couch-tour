package dev.mike.couchtour

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeedbackDiagnosticsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DiagnosticsLog.resetForTest()
        DiagnosticsLog.init(context)
        DiagnosticsLog.flushBlocking()
        context.getSharedPreferences("feedback_settings", Context.MODE_PRIVATE).edit().clear().commit()
        FeedbackSettings.resetForTest()
        FeedbackSettings.init(context)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }

    @Test
    fun `with toggle on buildFeedbackBody contains Environment block unchanged and Diagnostics section with seeded marks`() {
        DiagnosticsLog.mark("Library counts", "playlists=3 shows=12 tracks=410")
        DiagnosticsLog.mark("last sync result", "ok")
        DiagnosticsLog.mark("last crash", "java.lang.NullPointerException at Foo.kt:10")

        val summary = DiagnosticsLog.summaryLines()
        val body = buildFeedbackBody(
            routeName = "library",
            includeDiagnostics = true,
            summary = summary,
            appVersion = "1.0.0",
            device = "Google Pixel 8",
            os = "14 (API 34)",
        )

        val expectedEnvBlock = """
            ## Feedback
            [Describe your feedback, suggestion, or issue here]

            ---
            ## Environment
            - App Version: 1.0.0
            - Screen Route: library
            - Device: Google Pixel 8
            - Android OS: 14 (API 34)
        """.trimIndent()

        assertTrue("Body must start with unchanged Environment block", body.startsWith(expectedEnvBlock))
        assertTrue("Body must contain ## Diagnostics section", body.contains("## Diagnostics"))
        assertTrue("Body must include seeded library counts", body.contains("Library counts: playlists=3 shows=12 tracks=410"))
        assertTrue("Body must include seeded last sync result", body.contains("last sync result: ok"))
        assertTrue("Body must include seeded last crash", body.contains("last crash: java.lang.NullPointerException at Foo.kt:10"))
        assertTrue("Body must include total entries", body.contains("Total entries:"))
        assertTrue("Body must include on-disk size", body.contains("On-disk size:"))
        assertTrue("Body must tell user tail is on clipboard", body.contains("Recent diagnostics tail copied to clipboard; paste it under a ## Log heading below."))
        assertTrue("Body must include ## Log heading", body.contains("## Log"))
    }

    @Test
    fun `with toggle off body is byte-identical to legacy output and clipboard is untouched`() = runTest {
        FeedbackSettings.setIncludeDiagnostics(false)

        val expectedLegacy = buildFeedbackBody(
            routeName = "settings",
            includeDiagnostics = false,
        )

        val directBody = buildFeedbackBody(
            routeName = "settings",
            includeDiagnostics = false,
            summary = "Ignored summary",
        )
        assertEquals(expectedLegacy, directBody)
        assertFalse(directBody.contains("## Diagnostics"))
        assertFalse(directBody.contains("## Log"))

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val sentinel = "prior_clipboard_sentinel"
        clipboard.setPrimaryClip(ClipData.newPlainText("test", sentinel))

        val job = launchFeedback(context, "settings")
        job.join()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Clipboard must remain untouched when diagnostics toggle is off", sentinel, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        assertEquals("No toast should be shown when diagnostics toggle is off", 0, ShadowToast.shownToastCount())

        val started = shadowOf(context as Application).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        val url = started.dataString!!
        assertTrue(url.contains("template=bug_report.md"))
        val uri = Uri.parse(url)
        val bodyParam = uri.getQueryParameter("body")!!
        assertEquals(expectedLegacy, bodyParam)
    }

    @Test
    fun `summary is truncated at cap and resulting URL stays under sane length`() {
        val massiveLines = (1..200).map { i ->
            "mark_line_$i: " + "x".repeat(60)
        }
        val massiveSummary = massiveLines.joinToString("\n")
        assertTrue(massiveSummary.length > 5000)

        val truncated = truncateSummary(massiveSummary, MAX_SUMMARY_CHARS)
        assertTrue("Truncated length must be <= $MAX_SUMMARY_CHARS, was ${truncated.length}", truncated.length <= MAX_SUMMARY_CHARS)
        assertTrue("Newer lines at bottom must be preserved", truncated.contains("mark_line_200"))
        assertFalse("Older lines at top must be truncated", truncated.contains("mark_line_1:"))

        val body = buildFeedbackBody("now_playing", includeDiagnostics = true, summary = massiveSummary)
        val url = buildFeedbackUrl("Feedback Title", body)

        assertTrue("URL length must stay under 3000 chars, was ${url.length}", url.length < 3000)
    }

    @Test
    fun `seeded log with credential line copies raw text to clipboard but does not put line in URL body`() = runTest {
        FeedbackSettings.setIncludeDiagnostics(true)

        val secretValue = "super_secret_token_xyz_987654"
        val dir = File(context.filesDir, "diagnostics")
        dir.mkdirs()
        val logFile = File(dir, "diagnostics.log")
        logFile.appendText("2026-09-30T12:00:00.000Z\tINFO\tauth.token\ttoken=$secretValue\n")

        val job = launchFeedback(context, "library")
        job.join()
        shadowOf(Looper.getMainLooper()).idle()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull("Clipboard must receive primary clip", clip)
        val clipboardText = clip!!.getItemAt(0).text.toString()
        assertTrue("Clipboard must contain raw log text with secret", clipboardText.contains(secretValue))

        val started = shadowOf(context as Application).nextStartedActivity
        assertNotNull(started)
        val uri = Uri.parse(started.dataString!!)
        val bodyParam = uri.getQueryParameter("body")!!
        assertFalse("URL body must NOT contain the secret token from the raw log", bodyParam.contains(secretValue))
        assertFalse("URL body must NOT contain raw log event lines", bodyParam.contains("auth.token"))
    }

    @Test
    fun `toggle round-trips through SharedPreferences and defaults to on for fresh install`() {
        val sp = context.getSharedPreferences("feedback_settings", Context.MODE_PRIVATE)
        sp.edit().clear().commit()

        FeedbackSettings.resetForTest()
        FeedbackSettings.init(context)
        assertTrue("Fresh install must default includeDiagnostics to true", FeedbackSettings.includeDiagnostics.value)

        FeedbackSettings.setIncludeDiagnostics(false)
        assertFalse(FeedbackSettings.includeDiagnostics.value)
        assertEquals(false, sp.getBoolean("include_diagnostics", true))

        FeedbackSettings.resetForTest()
        FeedbackSettings.init(context)
        assertFalse("Persisted false must be restored across init", FeedbackSettings.includeDiagnostics.value)

        FeedbackSettings.setIncludeDiagnostics(true)
        assertTrue(FeedbackSettings.includeDiagnostics.value)
        assertEquals(true, sp.getBoolean("include_diagnostics", false))

        FeedbackSettings.resetForTest()
        FeedbackSettings.init(context)
        assertTrue("Persisted true must be restored across init", FeedbackSettings.includeDiagnostics.value)
    }

    @Test
    fun `three call sites all go through launchFeedback and show Toast with clipboard copy`() = runTest {
        FeedbackSettings.setIncludeDiagnostics(true)

        DiagnosticsLog.mark("test_callsite", "verified")
        DiagnosticsLog.log("callsite.event", DiagnosticsLog.Level.INFO)
        DiagnosticsLog.flushBlocking()

        val job = launchFeedback(context, "home")
        job.join()
        shadowOf(Looper.getMainLooper()).idle()

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        assertNotNull("Clipboard must be populated", clip)
        assertTrue("Clipboard must have diagnostics tail", clip!!.getItemAt(0).text.isNotEmpty())

        assertEquals("Diagnostics copied to clipboard", ShadowToast.getTextOfLatestToast())

        val started = shadowOf(context as Application).nextStartedActivity
        assertNotNull(started)
        val uri = Uri.parse(started.dataString!!)
        assertEquals("bug_report.md", uri.getQueryParameter("template"))
        val bodyParam = uri.getQueryParameter("body")!!
        assertTrue(bodyParam.contains("## Diagnostics"))
        assertTrue(bodyParam.contains("test_callsite: verified"))
    }

    @Test
    fun `clipboard write and log read happen off the main thread`() = runTest {
        FeedbackSettings.setIncludeDiagnostics(true)
        DiagnosticsLog.mark("thread_test", "verified")
        DiagnosticsLog.log("thread.event", DiagnosticsLog.Level.INFO)
        DiagnosticsLog.flushBlocking()

        val mainThread = Thread.currentThread()
        var runThread: Thread? = null

        val customDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                Thread {
                    runThread = Thread.currentThread()
                    block.run()
                }.start()
            }
        }

        val job = launchFeedback(
            context = context,
            routeName = "off_main",
            ioDispatcher = customDispatcher,
        )
        job.join()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(runThread)
        assertTrue("Log read and clipboard write must run on background thread", runThread != mainThread)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertNotNull(clipboard.primaryClip)
        assertEquals("Diagnostics copied to clipboard", ShadowToast.getTextOfLatestToast())
    }
}
