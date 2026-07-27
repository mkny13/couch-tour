package dev.mike.phishin

import android.app.Application

class PhishInApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Restore both sessions before any screen or the playback service issues a request.
        Session.init(this)
        LastFmSession.init(this)
    }
}
