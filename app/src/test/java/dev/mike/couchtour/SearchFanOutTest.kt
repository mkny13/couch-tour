package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [searchAll] fans out across both backends independently — one going down must not cost the
 * other its results. Each backend gets its own [MockWebServer] so a failure on one can be
 * staged without touching the other's fixture.
 */
class SearchFanOutTest {

    private lateinit var phishInServer: MockWebServer
    private lateinit var relistenServer: MockWebServer

    @Before
    fun setUp() {
        phishInServer = MockWebServer().apply { start() }
        relistenServer = MockWebServer().apply { start() }
        PhishInApi.baseUrl = phishInServer.url("/api/v2")
        RelistenApi.baseUrl = relistenServer.url("/api")
        RelistenCatalogSource.resetCache()
    }

    @After
    fun tearDown() {
        phishInServer.shutdown()
        relistenServer.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
        RelistenApi.baseUrl = "https://api.relisten.net/api".toHttpUrl()
        RelistenCatalogSource.resetCache()
    }

    private fun emptyRelistenBody() = """{"Artists":[],"Shows":[],"Songs":[],"Venues":[]}"""

    @Test
    fun `merges hits from both backends`() = runBlocking {
        phishInServer.enqueue(
            MockResponse().setBody(
                """{"exact_show":null,"other_shows":[],"tracks":[],"playlists":[]}"""
            )
        )
        relistenServer.enqueue(
            MockResponse().setBody(
                """{"Artists":[{"uuid":"u","slug":"goose","name":"Goose","show_count":100}],
                    "Shows":[],"Songs":[],"Venues":[]}"""
            )
        )

        val hits = searchAll("goose")

        assertEquals(listOf("Goose"), hits.artists.map { it.name })
        assertTrue(hits.failed.isEmpty())
    }

    @Test
    fun `one backend failing doesn't cost the other's results`() = runBlocking {
        phishInServer.enqueue(MockResponse().setResponseCode(500))
        relistenServer.enqueue(
            MockResponse().setBody(
                """{"Artists":[{"uuid":"u","slug":"goose","name":"Goose","show_count":100}],
                    "Shows":[],"Songs":[],"Venues":[]}"""
            )
        )

        val hits = searchAll("goose")

        assertEquals(listOf("Goose"), hits.artists.map { it.name })
        assertEquals(setOf(Backend.PHISHIN), hits.failed)
    }

    @Test
    fun `both backends failing returns empty hits naming both`() = runBlocking {
        phishInServer.enqueue(MockResponse().setResponseCode(500))
        relistenServer.enqueue(MockResponse().setResponseCode(500))

        val hits = searchAll("goose")

        assertTrue(hits.isEmpty)
        assertEquals(setOf(Backend.PHISHIN, Backend.RELISTEN), hits.failed)
    }
}
