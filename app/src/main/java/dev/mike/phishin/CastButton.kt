package dev.mike.phishin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.mediarouter.media.MediaRouter

/**
 * The cast button, and the device picker behind it.
 *
 * Hand-rolled rather than the Cast SDK's `MediaRouteButton`: that one is a plain Android
 * view whose chooser dialog needs an AppCompat theme and a `FragmentActivity`, and this
 * app has neither — it's Compose on a `ComponentActivity` with a Material theme. Driving
 * `MediaRouter` directly is about the same amount of code and the dialog then matches the
 * rest of the app.
 *
 * Following the platform convention, the button is invisible when there is nothing to cast
 * to, so it costs nothing on a phone that never sees a Chromecast.
 */
@Composable
fun CastButton() {
    val context = LocalContext.current
    // MediaRouter is main-thread-only, which composition is. It throws on a device with no
    // media route service at all, so it is allowed to simply not exist.
    val router = remember {
        runCatching { MediaRouter.getInstance(context.applicationContext) }.getOrNull()
    } ?: return

    var open by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf(emptyList<MediaRouter.RouteInfo>()) }
    val device by Casting.deviceName.collectAsState()

    DisposableEffect(router, open) {
        val callback = object : MediaRouter.Callback() {
            fun refresh() {
                routes = router.routes.filter {
                    it.isEnabled && it.matchesSelector(Casting.routeSelector)
                }
            }

            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        // Active scanning is the expensive kind, so it runs only while the picker is open;
        // the rest of the time we ask providers to discover and then sit and listen.
        val flags = if (open) {
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        } else {
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
        }
        router.addCallback(Casting.routeSelector, callback, flags)
        callback.refresh()
        onDispose { router.removeCallback(callback) }
    }

    val connected = device != null
    if (routes.isEmpty() && !connected) return

    IconButton(onClick = { open = true }) {
        Icon(
            if (connected) Icons.Default.CastConnected else Icons.Default.Cast,
            if (connected) "Casting to $device" else "Cast",
            tint = if (connected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }

    if (open) {
        CastPicker(
            routes = routes,
            device = device,
            onSelect = {
                router.selectRoute(it)
                open = false
            },
            onStop = {
                Casting.stop()
                open = false
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun CastPicker(
    routes: List<MediaRouter.RouteInfo>,
    device: String?,
    onSelect: (MediaRouter.RouteInfo) -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (device != null) "Casting to $device" else "Cast to") },
        text = {
            Column {
                if (routes.isEmpty()) {
                    Text("Looking for devices…", color = Color.Gray)
                }
                for (route in routes) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(route) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (route.isSelected) Icons.Default.CastConnected else Icons.Default.Cast,
                            null,
                            tint = if (route.isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                LocalContentColor.current
                            },
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                route.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            route.description?.let {
                                Text(it, fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (device != null) {
                TextButton(onClick = onStop) { Text("Stop casting") }
            } else {
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
        dismissButton = {
            if (device != null) TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
