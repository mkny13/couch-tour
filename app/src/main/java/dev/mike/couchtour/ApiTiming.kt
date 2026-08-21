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

    private fun elapsedMs() = (System.nanoTime() - callStartNanos) / 1_000_000

    // android.util.Log is the unmocked SDK stub in the plain-JUnit tests that exercise the
    // real OkHttpClient (ApiRequestTest, RelistenRequestTest, SearchFanOutTest — none use
    // Robolectric, to keep pure API-logic tests fast) — it throws there instead of logging.
    // Swallowing that is safe: on a real device Log never throws, so nothing is ever lost.
    private fun log(logAction: () -> Unit) = runCatching(logAction)

    override fun callStart(call: Call) {
        callStartNanos = System.nanoTime()
        connecting = false
        log { Log.d(tag, "${call.request().url.encodedPath}: call start") }
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connecting = true
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        val reused = !connecting
        connecting = false
        log { Log.d(tag, "${call.request().url.encodedPath}: connection acquired after ${elapsedMs()}ms (${if (reused) "reused" else "new"})") }
    }

    override fun callEnd(call: Call) {
        log { Log.d(tag, "${call.request().url.encodedPath}: call end after ${elapsedMs()}ms") }
    }

    override fun callFailed(call: Call, ioe: IOException) {
        log { Log.w(tag, "${call.request().url.encodedPath}: call failed after ${elapsedMs()}ms (${ioe.javaClass.simpleName}: ${ioe.message})") }
    }
}
