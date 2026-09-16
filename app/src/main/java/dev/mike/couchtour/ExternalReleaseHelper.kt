package dev.mike.couchtour

import android.content.Intent
import android.net.Uri

object ExternalReleaseHelper {
    fun buildIntent(release: ExternalRelease): Intent {
        val webUrl = release.url
        val uri = Uri.parse(webUrl)
        
        val deepLinkStr = when (release.platform) {
            ExternalReleasePlatform.SPOTIFY -> {
                val path = uri.path?.removePrefix("/")?.replace("/", ":")
                if (path != null && uri.host?.contains("spotify") == true) {
                    "spotify:$path"
                } else webUrl
            }
            ExternalReleasePlatform.TIDAL -> {
                val path = uri.path?.removePrefix("/browse/")?.removePrefix("/")
                if (path != null && uri.host?.contains("tidal") == true) {
                    "tidal://$path"
                } else webUrl
            }
        }
        
        val deepLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkStr))
        
        // Add fallback intent or something? Actually Intent.createChooser?
        // Let's just return the deep link intent. If ActivityNotFoundException is thrown, we launch the web URL.
        return deepLinkIntent
    }
    
    fun getWebUrl(release: ExternalRelease): String = release.url
}
