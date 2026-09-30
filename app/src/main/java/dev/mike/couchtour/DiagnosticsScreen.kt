package dev.mike.couchtour

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DiagnosticsData(
    val lines: List<String> = emptyList(),
    val summary: String = "",
    val count: Long = 0L,
    val bytes: Long = 0L,
)

suspend fun readDiagnosticsData(): DiagnosticsData = withContext(Dispatchers.IO) {
    DiagnosticsData(
        lines = DiagnosticsLog.tailLines(200),
        summary = DiagnosticsLog.summaryLines(),
        count = DiagnosticsLog.countEntries(),
        bytes = DiagnosticsLog.onDiskBytes(),
    )
}

suspend fun clearDiagnosticsData(): DiagnosticsData = withContext(Dispatchers.IO) {
    DiagnosticsLog.clear()
    CrashCapture.consumePreviousCrash()
    DiagnosticsData(
        lines = DiagnosticsLog.tailLines(200),
        summary = DiagnosticsLog.summaryLines(),
        count = DiagnosticsLog.countEntries(),
        bytes = DiagnosticsLog.onDiskBytes(),
    )
}

fun copyDiagnosticsToClipboard(context: Context): String {
    val text = DiagnosticsLog.exportText(context)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Couch Tour diagnostics", text)
    clipboard?.setPrimaryClip(clip)
    return text
}

fun createDiagnosticsShareIntent(context: Context): Intent {
    val text = DiagnosticsLog.exportText(context)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Couch Tour diagnostics")
        putExtra(Intent.EXTRA_TEXT, text)
    }
}

fun launchDiagnosticsShare(context: Context) {
    val intent = createDiagnosticsShareIntent(context)
    context.startActivity(Intent.createChooser(intent, null))
}

fun parseCrashNotice(notice: String): Pair<String, String> {
    val lines = notice.lines().map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return Pair("", "")
    val first = lines[0]
    if (first.contains("\t")) {
        val parts = first.split("\t")
        val timestamp = parts.firstOrNull().orEmpty()
        val detailPart = parts.find { it.startsWith("detail=") }?.removePrefix("detail=")
            ?: parts.drop(1).joinToString(" ")
        val firstTrace = if (lines.size > 1) lines[1] else detailPart
        return Pair(timestamp, firstTrace)
    }
    val match = Regex("""^(\d{4}-\d{2}-\d{2}T[0-9:.]+Z?)[\s:—–-]+(.*)$""").find(first)
    if (match != null) {
        val ts = match.groupValues[1]
        val rest = match.groupValues[2].trim()
        val trace = if (rest.isNotEmpty()) rest else (lines.getOrNull(1) ?: "")
        return Pair(ts, trace)
    }
    if (first.startsWith("20") && (first.endsWith("Z") || first.contains("T"))) {
        return Pair(first, lines.getOrNull(1) ?: "")
    }
    return Pair("", first)
}

@Composable
fun DiagnosticsScreen(
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    DiagnosticsScreen(
        onBack = { nav.popBackStack() },
        modifier = modifier,
    )
}

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ledger = LocalLedgerColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var data by remember { mutableStateOf(DiagnosticsData()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var showClearConfirmation by remember { mutableStateOf(false) }

    val lastCrashNotice by CrashCapture.lastCrashNotice.collectAsState()

    LaunchedEffect(refreshTrigger) {
        data = readDiagnosticsData()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ledger.appBackground)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("diagnostics.back_button")
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = ledger.textPrimary
                )
            }
            Text(
                text = "Diagnostics",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = ledger.textPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = { refreshTrigger++ },
                modifier = Modifier.testTag("diagnostics.refresh_button")
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh diagnostics",
                    tint = ledger.textMuted
                )
            }
        }

        // Crash Notice Card (dismissible)
        lastCrashNotice?.let { notice ->
            CrashNoticeCard(
                notice = notice,
                onDismiss = {
                    CrashCapture.consumePreviousCrash()
                }
            )
        }

        // Summary Header
        SummaryHeader(
            summary = data.summary,
            count = data.count,
            bytes = data.bytes,
        )

        // Log viewer body / Empty State
        if (data.lines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No diagnostics yet. They appear after you use the app for a while.",
                    color = ledger.textMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("diagnostics.empty_state")
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("diagnostics.log_list")
            ) {
                itemsIndexed(data.lines) { _, line ->
                    LogLineItem(line = line)
                }
            }
        }

        // Footer Actions: Copy, Share, Clear
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("diagnostics.footer"),
            color = ledger.elevatedBackground,
            border = BorderStroke(1.dp, ledger.listDivider)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val text = copyDiagnosticsToClipboard(context)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("diagnostics.copy_button")
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val intent = createDiagnosticsShareIntent(context)
                            withContext(Dispatchers.Main) {
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("diagnostics.share_button")
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share", fontSize = 13.sp)
                }

                OutlinedButton(
                    onClick = {
                        showClearConfirmation = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("diagnostics.clear_button")
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear", fontSize = 13.sp)
                }
            }
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("Clear diagnostics") },
            text = { Text("Delete all diagnostics logs and crash history? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        scope.launch {
                            data = clearDiagnosticsData()
                        }
                    },
                    modifier = Modifier.testTag("diagnostics.confirm_clear")
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearConfirmation = false },
                    modifier = Modifier.testTag("diagnostics.cancel_clear")
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CrashNoticeCard(
    notice: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ledger = LocalLedgerColors.current
    val (timestamp, firstTraceLine) = remember(notice) { parseCrashNotice(notice) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("diagnostics.crash_card"),
        shape = RoundedCornerShape(8.dp),
        color = if (ledger.isDark) Color(0x33CF6679) else Color(0x22B00020),
        border = BorderStroke(1.dp, if (ledger.isDark) Color(0x73CF6679) else Color(0x73B00020))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Previous run crashed",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
                if (timestamp.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = timestamp,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ledger.textMuted,
                    )
                }
                if (firstTraceLine.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = firstTraceLine,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ledger.textPrimary,
                        maxLines = 3,
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(24.dp)
                    .testTag("diagnostics.crash_dismiss")
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss crash notice",
                    tint = ledger.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SummaryHeader(
    summary: String,
    count: Long,
    bytes: Long,
    modifier: Modifier = Modifier,
) {
    val ledger = LocalLedgerColors.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("diagnostics.summary_header"),
        shape = RoundedCornerShape(8.dp),
        color = ledger.cardSurface,
        border = BorderStroke(1.dp, ledger.panelBorder)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SUMMARY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ledger.textMuted,
                    letterSpacing = 1.sp
                )
                Text(
                    text = formatDiagnosticsSummary(count, bytes),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ledger.textSubtle
                )
            }
            if (summary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = summary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ledger.textPrimary,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun LogLineItem(line: String) {
    val ledger = LocalLedgerColors.current
    val isError = line.contains("\tERROR\t")
    val isWarn = line.contains("\tWARN\t")
    val levelColor = when {
        isError -> MaterialTheme.colorScheme.error
        isWarn -> ledger.ratingAmber
        else -> ledger.textPrimary
    }

    val firstTab = line.indexOf('\t')
    val annotated = buildAnnotatedString {
        if (firstTab != -1) {
            withStyle(SpanStyle(color = ledger.textSubtle)) {
                append(line.substring(0, firstTab))
            }
            withStyle(SpanStyle(color = levelColor)) {
                append(line.substring(firstTab))
            }
        } else {
            withStyle(SpanStyle(color = levelColor)) {
                append(line)
            }
        }
    }

    Text(
        text = annotated,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    )
}
