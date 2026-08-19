package dev.mike.couchtour

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CouchTourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore the session before any screen or the playback service issues a request.
        Session.init(this)
        Favorites.init(this)
        LikedTracks.init(this)
        // Asynchronous and best-effort: the playback service picks Cast up whenever it
        // turns up, and never, on a device without Play services.
        Casting.init(this)

        SyncSession.init(this)
        // An immediate catch-up on launch, on top of the periodic background job — a device
        // that was just opened shouldn't have to wait up to 15 minutes to see what changed
        // elsewhere. Fire-and-forget: sync() is a no-op if unpaired.
        //
        // The catch is load bearing, not defensive padding: an exception escaping a bare
        // `launch` reaches the default uncaught handler and takes the whole process down. A
        // failing sync crashed the app on every launch once paired — a server error, an
        // offline device, or a captive portal is a thing to log and let the periodic job
        // retry, never a reason to make the app unopenable.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SyncSession.sync(PhishInDb.get(this@CouchTourApp).progressDao())
            } catch (e: Exception) {
                Log.w("Sync", "Launch sync failed; the periodic job will retry", e)
            }
        }
        schedulePeriodicSync(this)
    }
}
