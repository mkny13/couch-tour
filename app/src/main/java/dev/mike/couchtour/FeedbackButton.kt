package dev.mike.couchtour

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

fun launchFeedback(context: Context, routeName: String?) {
    val title = "Feedback (Couch Tour ${BuildConfig.VERSION_NAME})"
    val body = """
        ## Feedback
        [Describe your feedback, suggestion, or issue here]

        ---
        ## Environment
        - App Version: ${BuildConfig.VERSION_NAME}
        - Screen Route: ${routeName ?: "Unknown"}
        - Device: ${Build.MANUFACTURER} ${Build.MODEL}
        - Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
    """.trimIndent()

    val url = "https://github.com/mkny13/couch-tour/issues/new" +
            "?title=${Uri.encode(title)}" +
            "&body=${Uri.encode(body)}"

    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
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
