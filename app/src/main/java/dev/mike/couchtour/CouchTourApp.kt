package dev.mike.couchtour

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CouchTourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore the session before any screen or the playback service issues a request.
        Session.init(this)
        // Asynchronous and best-effort: the playback service picks Cast up whenever it
        // turns up, and never, on a device without Play services.
        Casting.init(this)

        SyncSession.init(this)
        // An immediate catch-up on launch, on top of the periodic background job — a device
        // that was just opened shouldn't have to wait up to 15 minutes to see what changed
        // elsewhere. Fire-and-forget: sync() is a no-op if unpaired, and any failure here is
        // covered by the periodic job's own retry.
        CoroutineScope(Dispatchers.IO).launch {
            SyncSession.sync(PhishInDb.get(this@CouchTourApp).progressDao())
        }
        schedulePeriodicSync(this)
    }
}
