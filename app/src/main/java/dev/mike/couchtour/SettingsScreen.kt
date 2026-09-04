package dev.mike.couchtour

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch

/**
 * Settings screen matching the Ledger handoff:
 * Grouped sections (PLAYBACK, DOWNLOADS & STORAGE, SYNC, ACCOUNT, ABOUT)
 * with toggle switches, value rows with chevron, and live sync status.
 */
@Composable
fun SettingsScreen(vm: PlayerViewModel, nav: NavHostController) {
    val ledger = LocalLedgerColors.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var gaplessEnabled by remember { mutableStateOf(true) }
    var wifiOnlyDownloads by remember { mutableStateOf(true) }
    val isPaired by SyncSession.paired.collectAsState()
    val lastSyncedAt by SyncSession.lastSyncedAt.collectAsState()
    val isSyncing by SyncSession.syncing.collectAsState()
    val username by Session.username.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ledger.appBackground)
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp)
    ) {
        // Header
        Text(
            text = "Settings",
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-0.01).sp,
            color = ledger.textPrimary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
        )

        // PLAYBACK Section
        SectionEyebrow("PLAYBACK")
        SettingsValueRow(
            label = "Audio quality",
            value = "FLAC over Wi-Fi",
            onClick = {}
        )
        SettingsValueRow(
            label = "Crossfade",
            value = "Off",
            onClick = {}
        )
        SettingsToggleRow(
            label = "Gapless playback",
            checked = gaplessEnabled,
            onCheckedChange = { gaplessEnabled = it }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // DOWNLOADS & STORAGE Section
        SectionEyebrow("DOWNLOADS & STORAGE")
        SettingsValueRow(
            label = "Downloaded shows",
            value = "0 shows · 0 MB",
            onClick = {}
        )
        SettingsToggleRow(
            label = "Wi-Fi only downloads",
            checked = wifiOnlyDownloads,
            onCheckedChange = { wifiOnlyDownloads = it }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // SYNC Section
        SectionEyebrow("SYNC")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Sync,
                contentDescription = "Sync",
                tint = ledger.accentBase,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isPaired) "Synced with desktop" else "Pair device to sync",
                    fontSize = 15.sp,
                    color = ledger.textPrimary
                )
                Text(
                    text = if (lastSyncedAt > 0) relativeTime(lastSyncedAt) else if (isPaired) "Ready" else "Unpaired",
                    fontSize = 12.sp,
                    color = ledger.textSubtle,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }
            Text(
                text = if (isSyncing) "Syncing…" else if (isPaired) "Sync now" else "Pair",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = ledger.accentIcon,
                modifier = Modifier
                    .clickable {
                        if (isPaired) {
                            scope.launch { SyncSession.sync(vm.progressDao) }
                        } else {
                            nav.navigate("sync")
                        }
                    }
                    .padding(vertical = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(1.dp)
                .background(ledger.listDivider)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ACCOUNT Section
        SectionEyebrow("ACCOUNT")
        SettingsValueRow(
            label = if (!username.isNullOrEmpty()) "Signed in" else "phish.in Account",
            value = username ?: "Sign in",
            onClick = {
                if (username.isNullOrEmpty()) nav.navigate("login")
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // ABOUT Section
        SectionEyebrow("ABOUT")
        SettingsValueRow(
            label = "Version",
            value = BuildConfig.VERSION_NAME,
            showChevron = false,
            onClick = {}
        )
        SettingsValueRow(
            label = "Show & tape data",
            value = "phish.in · relisten.net",
            showChevron = false,
            onClick = {}
        )
    }
}

@Composable
private fun SectionEyebrow(title: String) {
    val ledger = LocalLedgerColors.current
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.4.sp,
        color = ledger.textSubtle,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    showChevron: Boolean = true,
    onClick: () -> Unit
) {
    val ledger = LocalLedgerColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = ledger.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                fontSize = 14.sp,
                color = ledger.textMuted
            )
            if (showChevron) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = ledger.textSubtle,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ledger.listDivider)
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val ledger = LocalLedgerColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 15.sp,
                color = ledger.textPrimary,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = if (ledger.isDark) Color(0xFFF3F5FE) else Color(0xFFFFFFFF),
                    checkedTrackColor = ledger.accentBase,
                    uncheckedThumbColor = ledger.textSubtle,
                    uncheckedTrackColor = ledger.cardSurface,
                )
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ledger.listDivider)
        )
    }
}
