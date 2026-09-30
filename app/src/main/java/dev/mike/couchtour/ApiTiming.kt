package dev.mike.couchtour

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener

/**
 * Logs each request's connection reuse and elapsed time under [tag] — added to chase down
 * reports of playback resume taking 30+ seconds despite phish.in's API responding in well
 * under a second and neither [PhishInApi] nor [RelistenApi] having any retry/backoff logic
 * (issue: slow Android playback resume). The one thing that fits "30+ seconds, good wifi" is
 * a pooled keep-alive connection that went silently dead while the app was backgrounded
 * (radio doze, NAT re-mapping) and isn't detected until the 30s read timeout expires — this
 * listener's `connectionAcquired` without a preceding `connectStart` is exactly that signal.
 */
internal class TimingEventListener(private val tag: String) : EventListener() {
    private var callStartNanos = 0L
    private var connecting = false
    private var connectionReused = false

    private fun elapsedMs() = (System.nanoTime() - callStartNanos) / 1_000_000

    // android.util.Log is the unmocked SDK stub in the plain-JUnit tests that exercise the
    // real OkHttpClient (ApiRequestTest, RelistenRequestTest, SearchFanOutTest — none use
    // Robolectric, to keep pure API-logic tests fast) — it throws there instead of logging.
    // Swallowing that is safe: on a real device Log never throws, so nothing is ever lost.
    private fun log(logAction: () -> Unit) = runCatching(logAction)

    override fun callStart(call: Call) {
        callStartNanos = System.nanoTime()
        connecting = false
        connectionReused = false
        log { Log.d(tag, "${call.request().url.encodedPath}: call start") }
        DiagnosticsLog.log(
            "api.call",
            "path" to call.request().url.encodedPath,
            "phase" to "start",
            "ms" to 0L,
            "reused" to false
        )
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connecting = true
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val reused = !connecting
        connecting = false
        connectionReused = reused
        val ms = elapsedMs()
        log { Log.d(tag, "${call.request().url.encodedPath}: connection acquired after ${ms}ms (${if (reused) "reused" else "new"})") }
        DiagnosticsLog.log(
            "api.call",
            "path" to call.request().url.encodedPath,
            "phase" to "connected",
            "ms" to ms,
            "reused" to reused
        )
    }

    override fun callEnd(call: Call) {
        val ms = elapsedMs()
        log { Log.d(tag, "${call.request().url.encodedPath}: call end after ${ms}ms") }
        DiagnosticsLog.log(
            "api.call",
            "path" to call.request().url.encodedPath,
            "phase" to "end",
            "ms" to ms,
            "reused" to connectionReused
        )
    }

    override fun callFailed(call: Call, ioe: IOException) {
        val ms = elapsedMs()
        log { Log.w(tag, "${call.request().url.encodedPath}: call failed after ${ms}ms (${ioe.javaClass.simpleName}: ${ioe.message})") }
        DiagnosticsLog.log(
            "api.call",
            DiagnosticsLog.Level.WARN,
            "path" to call.request().url.encodedPath,
            "phase" to "failed",
            "ms" to ms,
            "reused" to connectionReused,
            "error" to ioe.javaClass.simpleName
        )
    }
}
