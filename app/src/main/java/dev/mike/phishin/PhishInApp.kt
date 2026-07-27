package dev.mike.phishin

import android.app.Application

class PhishInApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restores the saved JWT before any screen or the playback service can issue a request.
        Session.init(this)
    }
}
