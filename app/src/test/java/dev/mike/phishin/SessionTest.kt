package dev.mike.phishin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        PhishInApi.baseUrl = server.url("/api/v2")
        Session.init(ApplicationProvider.getApplicationContext<Context>())
        Session.logout()
    }

    @After
    fun tearDown() {
        Session.logout()
        server.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
    }

    @Test
    fun `starts signed out`() {
        assertFalse(Session.signedIn)
        assertNull(Session.username.value)
        assertNull(PhishInApi.authToken)
    }

    @Test
    fun `a successful login arms the api token and username`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"jwt":"the-jwt","username":"mike","email":"m@example.com"}""")
        )

        Session.login("m@example.com", "hunter2")

        assertTrue(Session.signedIn)
        assertEquals("mike", Session.username.value)
        assertEquals("the-jwt", PhishInApi.authToken)
    }

    @Test
    fun `the password is sent once and never retained`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"jwt":"the-jwt","username":"mike","email":"m@example.com"}""")
        )

        Session.login("m@example.com", "hunter2")

        // One request, and nothing the session exposes afterwards contains the password.
        assertEquals(1, server.requestCount)
        assertFalse(PhishInApi.authToken!!.contains("hunter2"))
        assertFalse(Session.username.value!!.contains("hunter2"))
    }

    @Test
    fun `a failed login leaves the session signed out`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))

        runCatching { Session.login("nobody@example.invalid", "wrong") }

        assertFalse(Session.signedIn)
        assertNull(Session.username.value)
        assertNull(PhishInApi.authToken)
    }

    @Test
    fun `logout clears the token and the username`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"jwt":"the-jwt","username":"mike","email":"m@example.com"}""")
        )
        Session.login("m@example.com", "hunter2")

        Session.logout()

        assertFalse(Session.signedIn)
        assertNull(Session.username.value)
        assertNull(PhishInApi.authToken)
    }

    @Test
    fun `an expired token logs the session out on the next call`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"jwt":"the-jwt","username":"mike","email":"m@example.com"}""")
        )
        Session.login("m@example.com", "hunter2")
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}"""))

        runCatching { PhishInApi.currentUser() }

        // Otherwise the app looks signed in while every personal screen errors.
        assertFalse(Session.signedIn)
        assertNull(Session.username.value)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TokenStoreTest {

    private fun store() = TokenStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `round-trips a token`() {
        val store = store()
        store.jwt = "abc.def.ghi"
        assertEquals("abc.def.ghi", store.jwt)
    }

    @Test
    fun `round-trips a username`() {
        val store = store()
        store.username = "mike"
        assertEquals("mike", store.username)
    }

    @Test
    fun `clears values when set to null`() {
        val store = store()
        store.jwt = "abc"
        store.username = "mike"

        store.jwt = null
        store.username = null

        assertNull(store.jwt)
        assertNull(store.username)
    }

    @Test
    fun `overwrites a previous token`() {
        val store = store()
        store.jwt = "first"
        store.jwt = "second"
        assertEquals("second", store.jwt)
    }
}
