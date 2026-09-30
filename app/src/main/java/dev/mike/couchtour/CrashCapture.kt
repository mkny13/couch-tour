package dev.mike.couchtour

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * On-device uncaught exception capture (#374).
 * Surfaces previous crash notices to the diagnostics viewer (#376).
 */
object CrashCapture {

    private val _lastCrashNotice = MutableStateFlow<String?>(null)
    val lastCrashNotice: StateFlow<String?> = _lastCrashNotice.asStateFlow()

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    internal var crashFile: File? = null

    @Synchronized
    fun install(context: Context, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): Job {
        val dir = File(context.filesDir, "diagnostics")
        crashFile = File(dir, "last_crash.txt")

        val existing = Thread.getDefaultUncaughtExceptionHandler()
        if (existing !is CrashCaptureHandler) {
            previousHandler = existing
            Thread.setDefaultUncaughtExceptionHandler(CrashCaptureHandler(context.applicationContext, existing))
        }

        // Asynchronously check for previous crash notice on Dispatchers.IO (#376, D293)
        // to prevent any blocking disk I/O in Application.onCreate.
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

    internal fun setLastCrashNoticeForTest(notice: String?) {
        _lastCrashNotice.value = notice
    }

    internal fun resetForTest(context: Context? = null) {
        _lastCrashNotice.value = null
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
            try {
                DiagnosticsLog.init(context)
                val detail = "${thread.name} · ${throwable.stackTraceToString()}"
                DiagnosticsLog.recordCrash("crash", detail)
                runCatching {
                    val dir = File(context.filesDir, "diagnostics")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "last_crash.txt")
                    file.writeText(detail, Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                Log.w("CrashCapture", "Failed to capture crash", t)
            } finally {
                previousHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
