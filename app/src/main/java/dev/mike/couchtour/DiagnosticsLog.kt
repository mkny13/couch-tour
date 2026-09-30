package dev.mike.couchtour

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device rotating diagnostics log (D292).
 * Writes asynchronously via a single-writer coroutine on Dispatchers.IO.
 * Never blocks or throws on caller threads. The single synchronous write path is [recordCrash].
 */
object DiagnosticsLog {

    enum class Level {
        INFO, WARN, ERROR
    }

    private const val DEFAULT_CAP_BYTES = 1024 * 1024L // 1 MiB
    private const val MAX_TAIL_READ_BYTES = 256 * 1024L // 256 KiB
    private const val RETENTION_DAYS = 7L
    private const val RETENTION_LINE_LIMIT = 2000

    internal var capBytes: Long = DEFAULT_CAP_BYTES
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val writeLock = Any()
    private val marksLock = Any()
    private val marks = LinkedHashMap<String, String>()

    private val initialized = AtomicBoolean(false)
    private val disabled = AtomicBoolean(false)

    private var diagnosticsDir: File? = null
    private var logFile: File? = null
    private var logFile1: File? = null
    private var lastCrashFile: File? = null

    private sealed interface WriterMessage {
        data class Line(val text: String) : WriterMessage
        data class Flush(val deferred: CompletableDeferred<Unit>) : WriterMessage
    }

    private var channel: Channel<WriterMessage> = Channel(capacity = 1000)
    private var writerJob: Job? = null

    private val timestampFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC)

    private val REDACTED_KEYWORDS = listOf(
        "token", "secret", "password", "passwd", "auth",
        "credential", "cookie", "pairing", "code", "key"
    )

    private fun safeLog(action: () -> Unit) = runCatching(action)

    fun redactValue(value: Any?): String = "***"

    internal fun isRedactedKey(key: String): Boolean {
        val lower = key.lowercase()
        val tokens = lower.split(Regex("(?<=[a-z])(?=[A-Z])|[^a-z0-9]+")).filter { it.isNotEmpty() }
        return tokens.any { token ->
            REDACTED_KEYWORDS.any { kw ->
                token == kw || (kw.length >= 4 && token.startsWith(kw))
            }
        }
    }

    private fun sanitize(value: Any?): String {
        if (value == null) return "null"
        val s = value.toString()
        return s.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
    }

    internal fun formatLine(
        timestamp: String,
        level: Level,
        event: String,
        fields: Array<out Pair<String, Any?>>
    ): String {
        val sb = StringBuilder()
        sb.append(timestamp).append('\t').append(level.name).append('\t').append(event)
        if (fields.isNotEmpty()) {
            sb.append('\t')
            sb.append(fields.joinToString(" ") { (key, value) ->
                val v = if (isRedactedKey(key)) "***" else sanitize(value)
                "$key=$v"
            })
        }
        sb.append('\n')
        return sb.toString()
    }

    @Synchronized
    fun init(context: Context) {
        val dir = File(context.filesDir, "diagnostics")
        initDirectory(dir, BuildConfig.VERSION_NAME)
    }

    internal fun initDirectory(dir: File, appVersion: String, now: Instant = Instant.now()) {
        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            diagnosticsDir = dir
            val current = File(dir, "diagnostics.log")
            val previous = File(dir, "diagnostics.log.1")
            val crash = File(dir, "last_crash.txt")
            logFile = current
            logFile1 = previous
            lastCrashFile = crash

            // Retention on init if file is over cap
            runRetention(current, now)

            writerJob?.cancel()
            val newChannel = Channel<WriterMessage>(capacity = 1000)
            channel = newChannel
            disabled.set(false)
            initialized.set(true)

            writerJob = scope.launch {
                drainWriter(newChannel)
            }

            log("log.start", Level.INFO, "version" to appVersion)
        } catch (t: Throwable) {
            disabled.set(true)
            safeLog { Log.w("DiagnosticsLog", "Failed to initialize diagnostics log", t) }
        }
    }

    private suspend fun drainWriter(ch: Channel<WriterMessage>) {
        for (msg in ch) {
            try {
                when (msg) {
                    is WriterMessage.Line -> {
                        synchronized(writeLock) {
                            appendLineInternal(msg.text)
                        }
                    }
                    is WriterMessage.Flush -> {
                        msg.deferred.complete(Unit)
                    }
                }
            } catch (t: Throwable) {
                disabled.set(true)
                safeLog { Log.w("DiagnosticsLog", "Diagnostics writer failed, disabling log", t) }
                break
            }
        }
    }

    private fun rotateInternal(current: File, previous: File) {
        if (previous.exists()) {
            previous.delete()
        }
        current.renameTo(previous)
        val rotatedLine = formatLine(
            timestampFormatter.format(Instant.now()),
            Level.INFO,
            "log.rotated",
            emptyArray()
        )
        FileOutputStream(current, false).use { out ->
            out.write(rotatedLine.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    private fun appendLineInternal(line: String) {
        val current = logFile ?: return
        val previous = logFile1 ?: return

        if (current.exists() && current.length() >= capBytes) {
            rotateInternal(current, previous)
        }

        FileOutputStream(current, true).use { out ->
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
        }
    }

    fun log(event: String, level: Level = Level.INFO, vararg fields: Pair<String, Any?>) {
        if (!initialized.get() || disabled.get()) return
        val timestamp = timestampFormatter.format(Instant.now())
        val line = formatLine(timestamp, level, event, fields)
        try {
            channel.trySend(WriterMessage.Line(line))
        } catch (_: Throwable) {
            // Must never throw or block
        }
    }

    fun recordCrash(event: String, detail: String) {
        if (disabled.get()) return
        runCatching {
            synchronized(writeLock) {
                val timestamp = timestampFormatter.format(Instant.now())
                val line = formatLine(timestamp, Level.ERROR, event, arrayOf("detail" to detail))
                appendLineInternal(line)
            }
        }
    }

    fun mark(name: String, value: String) {
        synchronized(marksLock) {
            marks[name] = value
        }
    }

    fun mark(event: String) {
        synchronized(marksLock) {
            if (event.contains(": ")) {
                val idx = event.indexOf(": ")
                val name = event.substring(0, idx).trim()
                val value = event.substring(idx + 2).trim()
                marks[name] = value
            } else if (event.contains('=')) {
                val idx = event.indexOf('=')
                val name = event.substring(0, idx).trim()
                val value = event.substring(idx + 1).trim()
                marks[name] = value
            } else {
                marks[event.trim()] = ""
            }
        }
    }

    fun tailLines(n: Int): List<String> {
        if (n <= 0) return emptyList()
        val file = logFile ?: return emptyList()
        if (!file.exists() || file.length() == 0L) return emptyList()

        return runCatching {
            synchronized(writeLock) {
                val length = file.length()
                val readBytes = minOf(length, MAX_TAIL_READ_BYTES)
                val startPos = length - readBytes

                val bytes = ByteArray(readBytes.toInt())
                RandomAccessFile(file, "r").use { raf ->
                    raf.seek(startPos)
                    raf.readFully(bytes)
                }

                val text = String(bytes, Charsets.UTF_8)
                val lines = text.lines().toMutableList()
                if (lines.isNotEmpty() && lines.last().isEmpty()) {
                    lines.removeAt(lines.size - 1)
                }
                val validLines = if (startPos > 0 && lines.isNotEmpty()) {
                    lines.drop(1)
                } else {
                    lines
                }
                validLines.takeLast(n)
            }
        }.getOrDefault(emptyList())
    }

    fun summaryLines(): String {
        val lines = mutableListOf<String>()
        synchronized(marksLock) {
            for ((name, value) in marks) {
                if (value.isNotEmpty()) {
                    lines.add("$name: $value")
                } else {
                    lines.add(name)
                }
            }
        }
        val totalEntries = countEntries()
        val diskBytes = onDiskBytes()
        lines.add("Total entries: $totalEntries")
        lines.add("On-disk size: $diskBytes bytes")
        return lines.joinToString("\n")
    }

    fun exportText(context: Context): String {
        val dir = File(context.filesDir, "diagnostics")
        val file1 = File(dir, "diagnostics.log.1")
        val file = File(dir, "diagnostics.log")

        val sb = StringBuilder()
        synchronized(writeLock) {
            if (file1.exists()) {
                runCatching {
                    sb.append(file1.readText(Charsets.UTF_8))
                }
            }
            if (file.exists()) {
                runCatching {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) {
                        sb.append('\n')
                    }
                    sb.append(file.readText(Charsets.UTF_8))
                }
            }
        }
        return sb.toString()
    }

    fun clear() {
        synchronized(writeLock) {
            logFile?.let { if (it.exists()) it.delete() }
            logFile1?.let { if (it.exists()) it.delete() }
            lastCrashFile?.let { if (it.exists()) it.delete() }
            synchronized(marksLock) {
                marks.clear()
            }
        }
    }

    fun onDiskBytes(): Long {
        val dir = diagnosticsDir ?: return 0L
        if (!dir.exists()) return 0L
        return runCatching {
            dir.listFiles()?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    internal fun countEntries(): Long {
        var count = 0L
        synchronized(writeLock) {
            for (f in listOf(logFile1, logFile)) {
                if (f != null && f.exists()) {
                    runCatching {
                        f.forEachLine(Charsets.UTF_8) { line ->
                            if (line.isNotBlank()) count++
                        }
                    }
                }
            }
        }
        return count
    }

    internal fun runRetention(file: File, now: Instant = Instant.now()) {
        if (!file.exists() || file.length() < capBytes) return

        runCatching {
            synchronized(writeLock) {
                val lines = file.readLines(Charsets.UTF_8)
                val cutoff = now.minus(RETENTION_DAYS, ChronoUnit.DAYS)

                var filtered = lines.filter { line ->
                    val tsStr = line.substringBefore('\t')
                    val parsed = runCatching { Instant.parse(tsStr) }.getOrNull()
                    parsed == null || !parsed.isBefore(cutoff)
                }

                val byteCount = filtered.sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }
                if (byteCount >= capBytes) {
                    filtered = filtered.takeLast(RETENTION_LINE_LIMIT)
                }

                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.bufferedWriter(Charsets.UTF_8).use { writer ->
                    for (l in filtered) {
                        writer.write(l)
                        writer.write("\n")
                    }
                }
                if (tmp.exists()) {
                    Files.move(
                        tmp.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
        }
    }

    internal suspend fun flush() {
        val deferred = CompletableDeferred<Unit>()
        channel.send(WriterMessage.Flush(deferred))
        deferred.await()
    }

    internal fun flushBlocking() = runBlocking { flush() }

    internal fun resetForTest() {
        writerJob?.cancel()
        writerJob = null
        initialized.set(false)
        disabled.set(false)
        synchronized(marksLock) { marks.clear() }
        capBytes = DEFAULT_CAP_BYTES
        diagnosticsDir = null
        logFile = null
        logFile1 = null
        lastCrashFile = null
    }
}
