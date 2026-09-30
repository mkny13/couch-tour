package dev.mike.couchtour

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CouchTourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashCapture.install(this)
        DiagnosticsLog.init(this)
        detectPreviousCrash()
        // Restore the session before any screen or the playback service issues a request.
        Session.init(this)
        CuratedMatches.init(this)
        HeuristicMatches.init(this)
        Favorites.init(this)
        LikedTracks.init(this)
        SavedShows.init(this)
        PlaybackSettings.init(this)
        ThemeSettings.init(this)
        FeedbackSettings.init(this)
        // Asynchronous and best-effort: the playback service picks Cast up whenever it
        // turns up, and never, on a device without Play services.
        Casting.init(this)

        SyncApi.applyConfiguredBaseUrl(this)
        SyncSession.init(this)
        schedulePeriodicSync(this)
    }

    internal fun detectPreviousCrash(scope: CoroutineScope = CoroutineScope(Dispatchers.IO)): Job {
        return scope.launch(Dispatchers.IO) {
            CrashCapture.detectPreviousCrash()
        }
    }
}
