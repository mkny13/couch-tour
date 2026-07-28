package dev.mike.couchtour

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Exercises the outgoing requests against a local server. These cover the parts of the
 * contract that are invisible in the OpenAPI spec and that fail silently when wrong.
 */
class ApiRequestTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PhishInApi.baseUrl = server.url("/api/v2")
        PhishInApi.authToken = null
        PhishInApi.onUnauthorized = null
    }

    @After
    fun tearDown() {
        server.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
        PhishInApi.authToken = null
        PhishInApi.onUnauthorized = null
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun take(): RecordedRequest = server.takeRequest()

    // ------------------------------------------------------------------ auth

    @Test
    fun `sends the jwt as X-Auth-Token`() = runBlocking {
        PhishInApi.authToken = "the-jwt"
        enqueue("""{"username":"mike","email":"m@example.com"}""")

        PhishInApi.currentUser()

        assertEquals("the-jwt", take().getHeader("X-Auth-Token"))
    }

    @Test
    fun `never sends the token as an Authorization Bearer header`() = runBlocking {
        // Authorization: Bearer is phish.in's API-key mechanism, not user auth. Both
        // forms return an identical bare 401, so only an assertion catches the mix-up.
        PhishInApi.authToken = "the-jwt"
        enqueue("""{"username":"mike","email":"m@example.com"}""")

        PhishInApi.currentUser()

        assertNull(take().getHeader("Authorization"))
    }

    @Test
    fun `omits the auth header entirely when signed out`() = runBlocking {
        enqueue("[]")

        PhishInApi.years()

        assertNull(take().getHeader("X-Auth-Token"))
    }

    @Test
    fun `posts login credentials as json`() = runBlocking {
        enqueue("""{"jwt":"j","username":"mike","email":"m@example.com"}""")

        val response = PhishInApi.login("m@example.com", "hunter2")

        val request = take()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/auth/login"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals(
            """{"email":"m@example.com","password":"hunter2"}""",
            request.body.readUtf8()
        )
        assertEquals("j", response.jwt)
    }

    @Test
    fun `escapes credentials rather than breaking the json body`() = runBlocking {
        enqueue("""{"jwt":"j","username":"u","email":"e"}""")

        PhishInApi.login("a\"b@example.com", """pa"ss\word""")

        // Naive string concatenation would produce invalid JSON here.
        val body = take().body.readUtf8()
        assertTrue(body.contains("""a\"b@example.com"""))
        assertTrue(body.contains("""pa\"ss\\word"""))
    }

    @Test
    fun `surfaces a rejected login as an unauthorized ApiException`() = runBlocking {
        enqueue("""{"message":"Unauthorized"}""", code = 401)

        try {
            PhishInApi.login("nobody@example.invalid", "wrong")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.unauthorized)
            assertEquals(401, e.code)
        }
    }

    @Test
    fun `a 401 on a token-bearing request triggers the logout hook`() = runBlocking {
        var loggedOut = false
        PhishInApi.authToken = "expired-jwt"
        PhishInApi.onUnauthorized = { loggedOut = true }
        enqueue("""{"message":"Unauthorized"}""", code = 401)

        runCatching { PhishInApi.currentUser() }

        assertTrue(loggedOut)
    }

    @Test
    fun `a rejected login does not trigger the logout hook`() = runBlocking {
        // Signed out, a wrong password must not be mistaken for an expired session.
        var loggedOut = false
        PhishInApi.onUnauthorized = { loggedOut = true }
        enqueue("""{"message":"Unauthorized"}""", code = 401)

        runCatching { PhishInApi.login("nobody@example.invalid", "wrong") }

        assertFalse(loggedOut)
    }

    // ----------------------------------------------------------------- shows

    @Test
    fun `uses year for a single-year period`() = runBlocking {
        enqueue("""{"shows":[]}""")

        PhishInApi.showsForPeriod("1997")

        val url = take().requestUrl!!
        assertEquals("1997", url.queryParameter("year"))
        assertNull(url.queryParameter("year_range"))
    }

    @Test
    fun `uses year_range for a multi-year period`() = runBlocking {
        // Sending a range to year= returns an empty list with no error, so this
        // distinction is the difference between a populated screen and a blank one.
        enqueue("""{"shows":[]}""")

        PhishInApi.showsForPeriod("1983-1987")

        val url = take().requestUrl!!
        assertEquals("1983-1987", url.queryParameter("year_range"))
        assertNull(url.queryParameter("year"))
    }

    @Test
    fun `filters show lists to those with audio`() = runBlocking {
        enqueue("""{"shows":[]}""")

        PhishInApi.showsForPeriod("1997")

        // Most shows in the archive have none; without this the list looks broken.
        assertEquals("complete_or_partial", take().requestUrl!!.queryParameter("audio_status"))
    }

    @Test
    fun `requests shows in date order`() = runBlocking {
        enqueue("""{"shows":[]}""")

        PhishInApi.showsForPeriod("1997")

        assertEquals("date:asc", take().requestUrl!!.queryParameter("sort"))
    }

    @Test
    fun `puts the show date in the path`() = runBlocking {
        enqueue("""{"date":"1997-02-13"}""")

        PhishInApi.show("1997-02-13")

        assertEquals(
            listOf("api", "v2", "shows", "1997-02-13"),
            take().requestUrl!!.pathSegments
        )
    }

    // ---------------------------------------------------------------- search

    @Test
    fun `encodes a search term with spaces`() = runBlocking {
        enqueue("""{"other_shows":[],"tracks":[],"playlists":[]}""")

        PhishInApi.search("harry hood")

        val url = take().requestUrl!!
        assertEquals("harry hood", url.pathSegments.last())
        assertTrue(url.encodedPath.endsWith("harry%20hood"))
    }

    @Test
    fun `encodes a search term containing a slash`() = runBlocking {
        // A raw slash would silently become an extra path segment and 404.
        enqueue("""{"other_shows":[],"tracks":[],"playlists":[]}""")

        PhishInApi.search("mike's/groove")

        val url = take().requestUrl!!
        assertEquals("mike's/groove", url.pathSegments.last())
        assertEquals(4, url.pathSegments.size)
    }

    // -------------------------------------------------------------- playlists

    @Test
    fun `omits the filter parameter when browsing all playlists`() = runBlocking {
        enqueue("""{"playlists":[]}""")

        PhishInApi.playlists()

        assertNull(take().requestUrl!!.queryParameter("filter"))
    }

    @Test
    fun `passes the mine filter through`() = runBlocking {
        enqueue("""{"playlists":[]}""")

        PhishInApi.playlists(filter = "mine")

        assertEquals("mine", take().requestUrl!!.queryParameter("filter"))
    }

    @Test
    fun `passes the liked filter through`() = runBlocking {
        enqueue("""{"playlists":[]}""")

        PhishInApi.playlists(filter = "liked")

        assertEquals("liked", take().requestUrl!!.queryParameter("filter"))
    }

    // ------------------------------------------------------------------ likes

    @Test
    fun `liking posts the type and id`() = runBlocking {
        PhishInApi.authToken = "jwt"
        enqueue("{}")

        PhishInApi.like(Likable.Track, 8435)

        val request = take()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("/likes"))
        // likable_id is an integer in the API, not a quoted string.
        assertEquals("""{"likable_type":"Track","likable_id":8435}""", request.body.readUtf8())
    }

    @Test
    fun `unliking deletes with query parameters`() = runBlocking {
        PhishInApi.authToken = "jwt"
        enqueue("{}")

        PhishInApi.unlike(Likable.Show, 412)

        val request = take()
        assertEquals("DELETE", request.method)
        val url = request.requestUrl!!
        assertEquals("Show", url.queryParameter("likable_type"))
        assertEquals("412", url.queryParameter("likable_id"))
    }

    @Test
    fun `likes carry the auth token`() = runBlocking {
        PhishInApi.authToken = "jwt"
        enqueue("{}")

        PhishInApi.like(Likable.Playlist, 131)

        assertEquals("jwt", take().getHeader("X-Auth-Token"))
    }

    @Test
    fun `the likable type names match the API values`() {
        // Sent verbatim as likable_type; renaming the enum would silently break liking.
        assertEquals("Show", Likable.Show.name)
        assertEquals("Track", Likable.Track.name)
        assertEquals("Playlist", Likable.Playlist.name)
    }

    @Test
    fun `a rejected like surfaces as an exception so the UI can roll back`() = runBlocking {
        PhishInApi.authToken = "jwt"
        enqueue("""{"message":"Unauthorized"}""", code = 401)

        try {
            PhishInApi.like(Likable.Track, 1)
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.unauthorized)
        }
    }

    // ------------------------------------------------------------ liked lists

    @Test
    fun `asks for liked shows only`() = runBlocking {
        enqueue("""{"shows":[]}""")

        PhishInApi.likedShows()

        val url = take().requestUrl!!
        assertEquals("true", url.queryParameter("liked_by_user"))
        assertEquals("complete_or_partial", url.queryParameter("audio_status"))
    }

    @Test
    fun `asks for liked tracks only`() = runBlocking {
        enqueue("""{"tracks":[]}""")

        PhishInApi.likedTracks()

        assertEquals("true", take().requestUrl!!.queryParameter("liked_by_user"))
    }

    // ------------------------------------------------------------- transport

    @Test
    fun `raises the status code on a server error`() = runBlocking {
        enqueue("nope", code = 500)

        try {
            PhishInApi.years()
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertEquals(500, e.code)
            assertFalse(e.unauthorized)
        }
    }

    @Test
    fun `requests json`() = runBlocking {
        enqueue("[]")

        PhishInApi.years()

        assertEquals("application/json", take().getHeader("Accept"))
    }

    @Test
    fun `drops periods that have no audio at all`() = runBlocking {
        enqueue(
            """[{"period":"1997","shows_with_audio_count":81},
                {"period":"2020","shows_with_audio_count":0}]"""
        )

        val periods = PhishInApi.years()

        assertEquals(listOf("1997"), periods.map { it.period })
    }
}
