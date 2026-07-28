package dev.mike.couchtour

import android.app.Application

class CouchTourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore the session before any screen or the playback service issues a request.
        Session.init(this)
        // Asynchronous and best-effort: the playback service picks Cast up whenever it
        // turns up, and never, on a device without Play services.
        Casting.init(this)
    }
}
