package dev.mike.couchtour

import android.content.ClipData
import androidx.core.content.edit
import androidx.core.net.toUri
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val FEEDBACK_PREFS = "feedback_settings"
private const val KEY_INCLUDE_DIAGNOSTICS = "include_diagnostics"

/**
 * Preferences for user feedback and issue reporting (#377, D296).
 *
 * Backed by plain `SharedPreferences` (matching [PlaybackSettings] / [ThemeSettings]).
 */
object FeedbackSettings {
    private var prefs: SharedPreferences? = null

    private val _includeDiagnostics = MutableStateFlow(true)
    val includeDiagnostics: StateFlow<Boolean> = _includeDiagnostics.asStateFlow()

    fun init(context: Context) {
        val sp = context.getSharedPreferences(FEEDBACK_PREFS, Context.MODE_PRIVATE)
        prefs = sp
        _includeDiagnostics.value = sp.getBoolean(KEY_INCLUDE_DIAGNOSTICS, true)
    }

    fun setIncludeDiagnostics(enabled: Boolean) {
        _includeDiagnostics.value = enabled
        prefs?.edit { putBoolean(KEY_INCLUDE_DIAGNOSTICS, enabled) }
    }

    internal fun resetForTest() {
        prefs = null
        _includeDiagnostics.value = true
    }
}

internal const val MAX_SUMMARY_CHARS = 1000

internal fun truncateSummary(summary: String, maxChars: Int = MAX_SUMMARY_CHARS): String {
    if (summary.length <= maxChars) return summary
    val lines = summary.lines()
    val kept = ArrayDeque<String>()
    var totalLen = 0

    for (i in lines.indices.reversed()) {
        val line = lines[i]
        val addedLen = line.length + (if (kept.isEmpty()) 0 else 1)
        if (totalLen + addedLen <= maxChars) {
            kept.addFirst(line)
            totalLen += addedLen
        } else {
            break
        }
    }

    if (kept.isEmpty() && lines.isNotEmpty()) {
        return lines.last().takeLast(maxChars)
    }

    return kept.joinToString("\n")
}

fun buildFeedbackBody(
    routeName: String?,
    includeDiagnostics: Boolean = false,
    summary: String = "",
    appVersion: String = BuildConfig.VERSION_NAME,
    device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
    os: String = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
): String {
    val envBlock = """
        ## Feedback
        [Describe your feedback, suggestion, or issue here]

        ---
        ## Environment
        - App Version: $appVersion
        - Screen Route: ${routeName ?: "Unknown"}
        - Device: $device
        - Android OS: $os
    """.trimIndent()

    if (!includeDiagnostics) {
        return envBlock
    }

    val cappedSummary = truncateSummary(summary, MAX_SUMMARY_CHARS)

    return buildString {
        append(envBlock)
        append("\n\n---\n## Diagnostics\n")
        if (cappedSummary.isNotBlank()) {
            append(cappedSummary)
            append("\n\n")
        }
        append("Recent diagnostics tail copied to clipboard; paste it under a ## Log heading below.\n\n## Log")
    }
}

fun buildFeedbackUrl(title: String, body: String): String {
    // blank issues are disabled on the repo, so GitHub rejects a template-less pre-filled URL
    // with "Unable to create issue" (#295): the template name must name a real file under
    // .github/ISSUE_TEMPLATE/.
    return "https://github.com/mkny13/couch-tour/issues/new" +
            "?template=bug_report.md" +
            "&title=${Uri.encode(title)}" +
            "&body=${Uri.encode(body)}"
}

fun launchFeedback(
    context: Context,
    routeName: String?,
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Job {
    val includeDiagnostics = FeedbackSettings.includeDiagnostics.value
    if (!includeDiagnostics) {
        val title = "Feedback (Couch Tour ${BuildConfig.VERSION_NAME})"
        val body = buildFeedbackBody(routeName, includeDiagnostics = false)
        val url = buildFeedbackUrl(title, body)
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val job = CompletableDeferred<Unit>()
        job.complete(Unit)
        return job
    }

    return scope.launch(ioDispatcher) {
        val summary = DiagnosticsLog.summaryLines()
        val tail = DiagnosticsLog.tailLines(200).joinToString("\n")
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Couch Tour diagnostics", tail)
        clipboard?.setPrimaryClip(clip)

        val title = "Feedback (Couch Tour ${BuildConfig.VERSION_NAME})"
        val body = buildFeedbackBody(routeName, includeDiagnostics = true, summary = summary)
        val url = buildFeedbackUrl(title, body)
        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "Diagnostics copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        context.startActivity(intent)
    }
}

@Composable
fun FeedbackButton(
    nav: NavController,
    modifier: Modifier = Modifier,
    iconSize: Dp = 26.dp,
    tint: Color = LocalContentColor.current,
) {
    val context = LocalContext.current
    IconButton(
        onClick = {
            val route = nav.currentDestination?.route
            launchFeedback(context, route)
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.Feedback,
            contentDescription = "Send feedback",
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}
