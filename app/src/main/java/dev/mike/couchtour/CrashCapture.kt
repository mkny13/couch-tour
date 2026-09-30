package dev.mike.couchtour

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

        val prev = previousCrash()
        if (prev != null) {
            _lastCrashNotice.value = prev
        }

        return scope.launch(Dispatchers.IO) {
            if (_lastCrashNotice.value == null) {
                val asyncPrev = readCrashFileInternal()
                if (asyncPrev != null) {
                    _lastCrashNotice.value = asyncPrev
                }
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

    fun previousCrash(): String? = readCrashFileInternal()

    fun consumePreviousCrash(): String? {
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

    fun consumePreviousCrashBlocking(): String? = consumePreviousCrash()

    fun detectPreviousCrash(): String? {
        val crash = previousCrash() ?: return null
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
                "${thread.name} · ${throwable.stackTraceToString()}"
            }.getOrDefault("${thread.name} · ${throwable.message}")

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

