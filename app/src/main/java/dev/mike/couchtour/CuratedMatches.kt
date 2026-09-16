package dev.mike.couchtour

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

object CuratedMatches {
    private var mappings: Map<String, ExternalRelease> = emptyMap()

    fun init(context: Context) {
        runCatching {
            val jsonStr = context.assets.open("curated_releases.json").bufferedReader().use { it.readText() }
            val format = Json { ignoreUnknownKeys = true }
            mappings = format.decodeFromString(jsonStr)
        }.onFailure { e ->
            Log.e("CuratedMatches", "Failed to load curated releases", e)
        }
    }

    fun match(backend: Backend, artistId: String, date: String): ExternalRelease? {
        return mappings["${backend.id}:$artistId:$date"]
    }
}
