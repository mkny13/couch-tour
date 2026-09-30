package dev.mike.couchtour

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * On-device uncaught exception capture (#374, D295).
 * Synchronously writes stack traces on crash and surfaces previous crash notices
 * to the diagnostics viewer (#376, D293).
 */
object CrashCapture {

    private val _lastCrashNotice = MutableStateFlow<String?>(null)
    val lastCrashNotice: StateFlow<String?> = _lastCrashNotice.asStateFlow()

    internal var previousHandler: Thread.UncaughtExceptionHandler? = null
    internal var crashFile: File? = null

    private val URL_QUERY_REGEX = Regex("""(https?://[^\s"'<>?#()]+)\?([^\s"'<>()]*)""", RegexOption.IGNORE_CASE)
    private val PATH_QUERY_REGEX = Regex("""(/[\w\-./]+)\?([^\s"'<>()]*)""")
    private val USER_INFO_REGEX = Regex("""(https?://)[^\s:@/]+:[^\s:@/]+@""", RegexOption.IGNORE_CASE)

    internal fun sanitizeStackTrace(trace: String): String {
        var result = trace
        result = USER_INFO_REGEX.replace(result, "$1")
        result = URL_QUERY_REGEX.replace(result) { match ->
            val baseUrl = match.groupValues[1]
            val queryPart = match.groupValues[2]
            val trailingPunctuation = queryPart.takeLastWhile { it in ".,;:!)]\"'" }
            baseUrl + trailingPunctuation
        }
        result = PATH_QUERY_REGEX.replace(result) { match ->
            val basePath = match.groupValues[1]
            val queryPart = match.groupValues[2]
            val trailingPunctuation = queryPart.takeLastWhile { it in ".,;:!)]\"'" }
            basePath + trailingPunctuation
        }
        return result
    }

    @Synchronized
    fun install(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): Job {
        val dir = File(context.filesDir, "diagnostics")
        crashFile = File(dir, "last_crash.txt")

        val existing = Thread.getDefaultUncaughtExceptionHandler()
        if (existing !is CrashCaptureHandler) {
            previousHandler = existing
            Thread.setDefaultUncaughtExceptionHandler(
                CrashCaptureHandler(context.applicationContext ?: context, existing)
            )
        }

        return scope.launch(Dispatchers.IO) {
            val prev = readCrashFileInternal()
            if (prev != null) {
                _lastCrashNotice.value = prev
            }
        }
    }

    internal fun readCrashFileInternal(): String? {
        val file = crashFile
            ?: DiagnosticsLog.lastCrashFile
            ?: DiagnosticsLog.diagnosticsDir?.let { File(it, "last_crash.txt") }
            ?: return null

        return runCatching {
            if (file.exists() && file.length() > 0L) {
                file.readText(Charsets.UTF_8)
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun previousCrash(): String? = withContext(Dispatchers.IO) {
        readCrashFileInternal()
    }

    fun previousCrashBlocking(): String? = readCrashFileInternal()

    suspend fun consumePreviousCrash(): String? {
        val text = _lastCrashNotice.value
        _lastCrashNotice.value = null
        return withContext(Dispatchers.IO) {
            val resolvedText = text ?: readCrashFileInternal()
            val file = crashFile
                ?: DiagnosticsLog.lastCrashFile
                ?: DiagnosticsLog.diagnosticsDir?.let { File(it, "last_crash.txt") }

            file?.let {
                runCatching {
                    if (it.exists()) {
                        it.delete()
                    }
                }
            }
            resolvedText
        }
    }

    fun consumePreviousCrashBlocking(): String? {
        val text = _lastCrashNotice.value ?: readCrashFileInternal()
        _lastCrashNotice.value = null
        val file = crashFile
            ?: DiagnosticsLog.lastCrashFile
            ?: DiagnosticsLog.diagnosticsDir?.let { File(it, "last_crash.txt") }

        file?.let {
            runCatching {
                if (it.exists()) {
                    it.delete()
                }
            }
        }
        return text
    }

    suspend fun detectPreviousCrash(): String? = withContext(Dispatchers.IO) {
        detectPreviousCrashBlocking()
    }

    fun detectPreviousCrashBlocking(): String? {
        val crash = readCrashFileInternal() ?: return null
        _lastCrashNotice.value = crash
        val (parsedTs, parsedTrace) = parseCrashNotice(crash)
        val firstLine = if (parsedTrace.isNotEmpty()) parsedTrace else crash.lineSequence().firstOrNull()?.trim().orEmpty()
        val timestamp = if (parsedTs.isNotEmpty()) {
            parsedTs
        } else {
            val file = crashFile
                ?: DiagnosticsLog.lastCrashFile
                ?: DiagnosticsLog.diagnosticsDir?.let { File(it, "last_crash.txt") }
            file?.takeIf { it.exists() && it.lastModified() > 0L }?.let { f ->
                DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(f.lastModified()))
            } ?: DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        }
        DiagnosticsLog.mark("Last crash", firstLine)
        DiagnosticsLog.log(
            "crash.previous",
            DiagnosticsLog.Level.WARN,
            "timestamp" to timestamp,
            "trace" to firstLine
        )
        return crash
    }

    internal fun setLastCrashNoticeForTest(notice: String?) {
        _lastCrashNotice.value = notice
    }

    internal fun resetForTest(context: Context? = null) {
        _lastCrashNotice.value = null
        if (previousHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            previousHandler = null
        }
        if (context != null) {
            val dir = File(context.filesDir, "diagnostics")
            crashFile = File(dir, "last_crash.txt")
        } else {
            crashFile = null
        }
    }

    private class CrashCaptureHandler(
        private val context: Context,
        private val previousHandler: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            val detail = runCatching {
                val raw = "${thread.name} · ${throwable.stackTraceToString()}"
                sanitizeStackTrace(raw)
            }.getOrElse {
                runCatching {
                    val rawFallback = "${thread.name} · ${throwable.javaClass.name}: ${throwable.message}"
                    sanitizeStackTrace(rawFallback)
                }.getOrElse {
                    "unknown-thread · crash"
                }
            }

            runCatching {
                DiagnosticsLog.init(context)
            }

            runCatching {
                DiagnosticsLog.recordCrash("crash", detail)
            }

            runCatching {
                val file = crashFile
                    ?: DiagnosticsLog.lastCrashFile
                    ?: File(File(context.filesDir, "diagnostics"), "last_crash.txt")
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                file.writeText(detail, Charsets.UTF_8)
            }

            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

