package dev.mike.couchtour

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(nav: NavHostController) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Header("Log in to phish.in", nav)
        Text(
            "phish.in is a separate website hosting the Phish archive. Logging in shows your " +
                "liked shows, tracks, and playlists — for Phish only, not the other artists in " +
                "this app. The password is sent once to get a token and is never stored; only " +
                "the token is kept, encrypted on this device.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; error = null },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        )
        Button(
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { Session.login(email.trim(), password) }
                        .onSuccess { nav.popBackStack() }
                        .onFailure {
                            error = if (it is ApiException && it.unauthorized) {
                                "Email or password not recognised."
                            } else {
                                "Couldn't log in: ${it.message}"
                            }
                            busy = false
                        }
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(if (busy) "Logging in…" else "Log in") }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }
}

/** Pairing and device management for progress sync (D119-D127, QR pairing D145). */
@Composable
fun SyncScreen(vm: PlayerViewModel, nav: NavHostController) {
    val paired by SyncSession.paired.collectAsState()
    var pairingResult by remember { mutableStateOf<PairStartResponse?>(null) }
    var claimCode by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var devicesState by remember { mutableStateOf<Result<List<DeviceInfo>>?>(null) }
    val scope = rememberCoroutineScope()

    // Compose Navigation's standard way to get a result back from a pushed screen: the
    // scanner writes into *this* entry's SavedStateHandle before popping itself off, since
    // it can't hand a return value back through the composable call itself.
    val scannedCode = nav.currentBackStackEntry
        ?.savedStateHandle
        ?.getStateFlow<String?>("scannedCode", null)
        ?.collectAsState()
    LaunchedEffect(scannedCode?.value) {
        scannedCode?.value?.let {
            claimCode = it
            error = null
            nav.currentBackStackEntry?.savedStateHandle?.set("scannedCode", null)
        }
    }

    // Live refresh the device list while this screen is open, with exponential backoff (#209).
    // Only while the app is on screen (#505): repeatOnLifecycle cancels the loop on stop and
    // restarts it, with an immediate refresh, on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(paired, refreshKey) {
        if (!paired) return@LaunchedEffect
        devicesState = null
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var intervalMs = DeviceListBackoff.INITIAL_MS
            while (true) {
                val res = runCatching { SyncSession.devices() }
                val newList = res.getOrNull()
                val unchanged = devicesState?.isSuccess == true && res.isSuccess &&
                    devicesState?.getOrNull() == newList
                if (!unchanged) devicesState = res
                intervalMs = DeviceListBackoff.next(intervalMs, changed = !unchanged)
                delay(intervalMs)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Header("Sync", nav)
        Text(
            "Sync keeps listening history and resume position in step across your paired " +
                "devices. Pairing is one-time; after that, devices sync on their own.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        val lastError by SyncSession.lastError.collectAsState()
        // Unconditional, not inside `if (paired)`: an auto-unlink on a bad token flips paired
        // to false in the same beat this message is set, so keeping it scoped to the paired
        // block would erase the explanation at exactly the moment it's needed (D172).
        lastError?.let {
            Text(
                it,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (paired) {
            RowItem("This device is paired", "Tap to unlink", null) {
                SyncSession.unlink()
                SyncSession.clearError()
                pairingResult = null
            }

            val syncing by SyncSession.syncing.collectAsState()
            val lastSyncedAt by SyncSession.lastSyncedAt.collectAsState()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (lastSyncedAt == 0L) "Never synced" else "Last synced ${relativeTime(lastSyncedAt)}",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !syncing,
                    onClick = { scope.launch { runCatching { SyncSession.sync(vm.progressDao) } } }
                ) { Text(if (syncing) "Syncing…" else "Sync now") }
            }
        }

        pairingResult?.let { result ->
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Enter this code on the other device:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    result.code,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                Text(
                    "Expires in 10 minutes",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // White backing behind the QR itself — the surrounding theme is dark, and a
                // QR scanner needs real light/dark contrast, not whatever the app's palette is.
                Surface(color = Color.White, modifier = Modifier.padding(top = 16.dp)) {
                    Image(
                        bitmap = remember(result.code) { qrCodeBitmap(result.code) },
                        contentDescription = "QR code for pairing code ${result.code}",
                        modifier = Modifier.padding(12.dp).size(200.dp)
                    )
                }
            }
        }

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    runCatching { SyncSession.startPairing() }
                        .onSuccess { pairingResult = it }
                        .onFailure { error = "Couldn't start pairing: ${it.message}" }
                    busy = false
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        ) { Text(if (paired) "Add another device" else "Pair this device") }

        if (!paired) {
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.10f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
            Text(
                "Have a code from another device?",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            OutlinedTextField(
                value = claimCode,
                onValueChange = { claimCode = it.uppercase(); error = null },
                label = { Text("Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            TextButton(
                onClick = { nav.navigate("scan") },
                modifier = Modifier.padding(horizontal = 8.dp)
            ) { Text("Scan QR code instead") }
            Button(
                enabled = !busy && claimCode.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        runCatching { SyncSession.claimPairing(claimCode.trim()) }
                            .onSuccess {
                                claimCode = ""
                                // Sync straight away rather than leaving both devices looking
                                // empty until a later timer fires — see claimPairing's note.
                                runCatching { SyncSession.sync(vm.progressDao) }
                                    .onFailure { error = "Paired, but the first sync failed: ${it.message}" }
                            }
                            .onFailure { error = "Couldn't join: ${it.message}" }
                        busy = false
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(if (busy) "Joining…" else "Join") }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }

        if (paired) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Devices",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Default.Refresh, "Refresh devices")
                }
            }
            HorizontalDivider()
            Loaded(devicesState) { list ->
                list.forEach { device ->
                    RowItem(
                        title = device.name + if (device.isSelf) " (this device)" else "",
                        subtitle = device.platform,
                        artUrl = null,
                        trailing = "Revoke",
                        onClick = {
                            scope.launch {
                                runCatching { SyncSession.revoke(device.deviceId) }
                                refreshKey++
                            }
                        }
                    )
                }
            }
        }

        Text(
            "Couch Tour ${BuildConfig.VERSION_NAME}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 16.dp)
        )
    }
}
