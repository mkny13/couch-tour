package dev.mike.couchtour

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashCaptureTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var testFilesDir: File
    private lateinit var context: Context
    private val originalHandler: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()

    private class RecordingExceptionHandler : Thread.UncaughtExceptionHandler {
        val invocations = mutableListOf<Pair<Thread, Throwable>>()
        override fun uncaughtException(t: Thread, e: Throwable) {
            invocations.add(t to e)
        }
    }

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
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
    }

    @Test
    fun `uncaught exception on background thread synchronously writes trace and calls previous handler`() {
        val fakeHandler = RecordingExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeHandler)

        CrashCapture.install(context)

        val crashThread = thread {
            throw IllegalStateException("Background crash test message")
        }
        crashThread.join()

        // 1. Assert previous handler was invoked with the exact thread and throwable
        assertEquals(1, fakeHandler.invocations.size)
        val (invokedThread, invokedThrowable) = fakeHandler.invocations[0]
        assertEquals(crashThread.name, invokedThread.name)
        assertEquals("Background crash test message", invokedThrowable.message)

        // 2. Assert stack trace is in diagnostics.log synchronously (no waiting, no coroutine drain)
        val diagDir = File(testFilesDir, "diagnostics")
        val logFile = File(diagDir, "diagnostics.log")
        assertTrue("diagnostics.log must exist", logFile.exists())
        val logContent = logFile.readText(Charsets.UTF_8)
        assertTrue(logContent.contains("\tERROR\tcrash\t"))
        assertTrue(logContent.contains("IllegalStateException: Background crash test message"))
        assertTrue(logContent.contains(crashThread.name))

        // 3. Assert last_crash.txt contains exception class and message
        val lastCrashFile = File(diagDir, "last_crash.txt")
        assertTrue("last_crash.txt must exist", lastCrashFile.exists())
        val crashContent = lastCrashFile.readText(Charsets.UTF_8)
        assertTrue(crashContent.contains("IllegalStateException: Background crash test message"))
        assertTrue(crashContent.contains(crashThread.name))
    }

    @Test
    fun `stack trace is written synchronously proven by immediate read without coroutine drain`() {
        val fakeHandler = RecordingExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeHandler)
        CrashCapture.install(context)

        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull("Handler should be installed", currentHandler)

        val ex = RuntimeException("Synchronous write test")
        val testThread = Thread.currentThread()
        currentHandler?.uncaughtException(testThread, ex)

        // Immediately read files without yielding or draining coroutines
        val diagDir = File(testFilesDir, "diagnostics")
        val logFile = File(diagDir, "diagnostics.log")
        val crashFile = File(diagDir, "last_crash.txt")

        assertTrue("diagnostics.log must exist immediately", logFile.exists())
        assertTrue("last_crash.txt must exist immediately", crashFile.exists())
        assertTrue(logFile.readText(Charsets.UTF_8).contains("Synchronous write test"))
        assertTrue(crashFile.readText(Charsets.UTF_8).contains("Synchronous write test"))
        assertEquals(1, fakeHandler.invocations.size)
    }

    @Test
    fun `crash before CouchTourApp finishes initializing is still captured`() {
        val fakeHandler = RecordingExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeHandler)

        // Reset DiagnosticsLog to simulate uninitialized state before CouchTourApp.onCreate
        DiagnosticsLog.resetForTest()

        // Call CrashCapture.install(context) first, as done in CouchTourApp.onCreate
        CrashCapture.install(context)

        // Simulate crash occurring before DiagnosticsLog.init or other services initialize
        val ex = IllegalStateException("Crash before CouchTourApp init completes")
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        handler?.uncaughtException(Thread.currentThread(), ex)

        // Crash handler initialized DiagnosticsLog and captured crash
        val diagDir = File(testFilesDir, "diagnostics")
        val logFile = File(diagDir, "diagnostics.log")
        val crashFile = File(diagDir, "last_crash.txt")

        assertTrue("diagnostics.log should be created and written", logFile.exists())
        assertTrue("last_crash.txt should be created and written", crashFile.exists())
        assertTrue(logFile.readText(Charsets.UTF_8).contains("Crash before CouchTourApp init completes"))
        assertTrue(crashFile.readText(Charsets.UTF_8).contains("Crash before CouchTourApp init completes"))
        assertEquals(1, fakeHandler.invocations.size)
    }

    @Test
    fun `chained exception is written with full caused by chain`() {
        val fakeHandler = RecordingExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeHandler)
        CrashCapture.install(context)

        val rootCause = IOException("Root disk failure")
        val midCause = IllegalStateException("Database failed to open", rootCause)
        val topException = RuntimeException("Application startup failure", midCause)

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        handler?.uncaughtException(Thread.currentThread(), topException)

        val diagDir = File(testFilesDir, "diagnostics")
        val crashFile = File(diagDir, "last_crash.txt")
        val logFile = File(diagDir, "diagnostics.log")

        val crashContent = crashFile.readText(Charsets.UTF_8)
        val logContent = logFile.readText(Charsets.UTF_8)

        // Both files must contain all three exception levels in the cause chain
        assertTrue(crashContent.contains("RuntimeException: Application startup failure"))
        assertTrue(crashContent.contains("Caused by: java.lang.IllegalStateException: Database failed to open"))
        assertTrue(crashContent.contains("Caused by: java.io.IOException: Root disk failure"))

        assertTrue(logContent.contains("RuntimeException: Application startup failure"))
        assertTrue(logContent.contains("Caused by: java.lang.IllegalStateException: Database failed to open"))
        assertTrue(logContent.contains("Caused by: java.io.IOException: Root disk failure"))

        assertEquals(1, fakeHandler.invocations.size)
    }

    @Test
    fun `handler survives broken sink without throwing and still calls previous handler`() {
        val fakeHandler = RecordingExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeHandler)

        // Point the diagnostics directory at a regular file so mkdirs() fails
        val brokenFile = File(tmpFolder.root, "broken_sink_file").apply {
            createNewFile()
        }
        val brokenContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = brokenFile
            override fun getApplicationContext(): Context = this
        }

        CrashCapture.install(brokenContext)

        val handler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull("Handler must be installed", handler)

        // Must not throw or propagate any exception out of uncaughtException
        val exception = RuntimeException("Crash against unwritable sink")
        var threw = false
        try {
            handler?.uncaughtException(Thread.currentThread(), exception)
        } catch (t: Throwable) {
            threw = true
        }

        assertFalse("Handler must never throw out of itself under sink failure", threw)
        assertEquals("Previous handler must still be called even when sink fails", 1, fakeHandler.invocations.size)
        assertEquals("Crash against unwritable sink", fakeHandler.invocations[0].second.message)
    }

    @Test
    fun `next-launch detection logs crash previous keeps file until consumed and second detection is no-op`() = runBlocking {
        val diagDir = File(testFilesDir, "diagnostics")
        if (!diagDir.exists()) diagDir.mkdirs()
        val crashFile = File(diagDir, "last_crash.txt")
        val crashTrace = "test-worker · java.lang.RuntimeException: Coroutine worker crashed\n\tat Test.kt:42"
        crashFile.writeText(crashTrace, Charsets.UTF_8)

        val app = CouchTourApp()

        // 1. Run detection path
        val job = app.detectPreviousCrash(this)
        job.join()

        // 2. Assert previousCrash() is non-null
        val detected = CrashCapture.previousCrash()
        assertNotNull("previousCrash() should be non-null", detected)
        assertEquals(crashTrace, detected)
        assertEquals(crashTrace, CrashCapture.lastCrashNotice.value)

        // 3. Assert crash.previous line is logged
        val logLines = DiagnosticsLog.tailLines(10)
        val crashPrevLine = logLines.find { it.contains("crash.previous") }
        assertNotNull("crash.previous event must be logged", crashPrevLine)
        assertTrue(crashPrevLine!!.contains("trace=test-worker · java.lang.RuntimeException: Coroutine worker crashed"))
        assertTrue(crashPrevLine.contains("timestamp="))

        // 4. Assert summary mark was updated
        val summary = DiagnosticsLog.summaryLines()
        assertTrue("Summary marks must contain Last crash", summary.contains("Last crash: test-worker · java.lang.RuntimeException: Coroutine worker crashed"))

        // 5. Assert last_crash.txt file still exists
        assertTrue("last_crash.txt must survive launch detection", crashFile.exists())

        // 6. consumePreviousCrash() clears it
        val consumed = CrashCapture.consumePreviousCrash()
        assertEquals(crashTrace, consumed)
        assertFalse("last_crash.txt must be deleted after consume", crashFile.exists())
        assertNull("previousCrash() must be null after consume", CrashCapture.previousCrash())
        assertNull("lastCrashNotice must be null after consume", CrashCapture.lastCrashNotice.value)

        // 7. Second detection is a no-op
        val linesCountBefore = DiagnosticsLog.tailLines(100).size
        val secondDetect = CrashCapture.detectPreviousCrash()
        assertNull("Second detection must return null", secondDetect)
        val linesCountAfter = DiagnosticsLog.tailLines(100).size
        assertEquals("Second detection must not add any new log lines", linesCountBefore, linesCountAfter)
    }

    @Test
    fun `last_crash survives relaunch until explicitly consumed`() = runBlocking {
        val diagDir = File(testFilesDir, "diagnostics")
        if (!diagDir.exists()) diagDir.mkdirs()
        val crashFile = File(diagDir, "last_crash.txt")
        crashFile.writeText("main · java.lang.Error: OutOfMemoryError", Charsets.UTF_8)

        val app = CouchTourApp()

        // Launch 1
        app.detectPreviousCrash(this).join()
        assertTrue("File survives launch 1", crashFile.exists())

        // Launch 2 (simulated restart without consuming)
        CrashCapture.setLastCrashNoticeForTest(null)
        app.detectPreviousCrash(this).join()
        assertTrue("File survives launch 2", crashFile.exists())

        // Explicitly consume
        CrashCapture.consumePreviousCrash()
        assertFalse("File removed after consumption", crashFile.exists())
    }

    @Test
    fun `uncaught exception embedding URL with query string strips query string from diagnostics log and last_crash`() {
        val fakeHandler = RecordingExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeHandler)
        CrashCapture.install(context)

        val sensitiveToken = "super_secret_jwt_token_12345"
        val sensitiveCode = "pairing_987654"
        val ex = IOException("Failed connecting to https://phish.in/api/v1/user?auth_token=$sensitiveToken&code=$sensitiveCode (timeout)")

        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull("Handler should be installed", currentHandler)
        currentHandler?.uncaughtException(Thread.currentThread(), ex)

        val diagDir = File(testFilesDir, "diagnostics")
        val logFile = File(diagDir, "diagnostics.log")
        val crashFile = File(diagDir, "last_crash.txt")

        assertTrue("diagnostics.log must exist", logFile.exists())
        assertTrue("last_crash.txt must exist", crashFile.exists())

        val logContent = logFile.readText(Charsets.UTF_8)
        val crashContent = crashFile.readText(Charsets.UTF_8)

        // Secrets and query strings must be stripped
        assertFalse("diagnostics.log must not contain auth token", logContent.contains(sensitiveToken))
        assertFalse("diagnostics.log must not contain pairing code", logContent.contains(sensitiveCode))
        assertFalse("diagnostics.log must not contain ?auth_token=", logContent.contains("?auth_token="))

        assertFalse("last_crash.txt must not contain auth token", crashContent.contains(sensitiveToken))
        assertFalse("last_crash.txt must not contain pairing code", crashContent.contains(sensitiveCode))
        assertFalse("last_crash.txt must not contain ?auth_token=", crashContent.contains("?auth_token="))

        // Base URL and exception details are preserved
        assertTrue("diagnostics.log must retain base URL", logContent.contains("https://phish.in/api/v1/user"))
        assertTrue("last_crash.txt must retain base URL", crashContent.contains("https://phish.in/api/v1/user"))
        assertEquals(1, fakeHandler.invocations.size)
    }

    @Test
    fun `handler survives OutOfMemoryError and delegates to previous handler`() {
        val fakeHandler = RecordingExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(fakeHandler)
        CrashCapture.install(context)

        val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull("Handler should be installed", currentHandler)

        val oom = OutOfMemoryError("Java heap space")
        currentHandler?.uncaughtException(Thread.currentThread(), oom)

        assertEquals("Previous handler must still be called under OOM", 1, fakeHandler.invocations.size)
        assertEquals("Java heap space", fakeHandler.invocations[0].second.message)

        val diagDir = File(testFilesDir, "diagnostics")
        val crashFile = File(diagDir, "last_crash.txt")
        assertTrue("last_crash.txt should be written", crashFile.exists())
        assertTrue(crashFile.readText(Charsets.UTF_8).contains("OutOfMemoryError"))
    }
}
