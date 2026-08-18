package dev.mike.couchtour

import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Standard Android sharing for a show or track (#19), Relisten parity — this app had no
 * `Intent.ACTION_SEND` at all before. The URLs themselves come from [showShareUrl]/
 * [trackShareUrl] in Catalog.kt, which know which backend they're for; everything here is
 * just formatting that into a message and handing it to the platform.
 */

/** "Phish · 1997-11-22\nhttps://phish.in/1997-11-22" */
fun showShareText(artist: ArtistRef, date: String): String =
    "${artist.name} · $date\n${showShareUrl(artist, date)}"

/**
 * "Mike's Song — Phish · 1997-11-22\nhttps://phish.in/1997-11-22/mikes-song"
 *
 * Falls back to the show's own link when the backend has no per-track page
 * ([trackShareUrl] returning null) — the track title still travels in the text even though
 * the link points at the show rather than that exact track.
 */
fun trackShareText(artist: ArtistRef, date: String, trackTitle: String, trackSlug: String?): String {
    val url = trackShareUrl(artist, date, trackSlug) ?: showShareUrl(artist, date)
    return "$trackTitle — ${artist.name} · $date\n$url"
}

fun launchShare(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** A row-trailing icon button, same shape as the like button next to it: self-contained,
 *  no lambda threaded up from the parent. */
@Composable
internal fun ShareButton(text: String) {
    val context = LocalContext.current
    IconButton(onClick = { launchShare(context, text) }) {
        Icon(Icons.Default.Share, contentDescription = "Share")
    }
}
