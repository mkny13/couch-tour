package dev.mike.couchtour

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LikeButton] composed for real under Robolectric, at the show level the Show Detail header
 * uses (#431). The states worth composing are the ones a DTO test can't see: a count that
 * moves the moment you tap before the server has answered, a like that reverts when the
 * request fails, and a signed-out button that still shows the public count but takes no
 * taps. The request itself is asserted against MockWebServer so the toggle can't pass by
 * updating local state alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LikeButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer

    /** MockWebServer's requestCount never resets, so requests made before the tap are the baseline. */
    private var baseline = 0

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

    /** Signs in through the real API path, so [Session.username] — the gate [LikeButton] reads — is armed. */
    private fun signIn() {
        server.enqueue(
            MockResponse().setBody("""{"jwt":"the-jwt","username":"tester","email":"t@example.com"}""")
        )
        runBlocking { Session.login("t@example.com", "password") }
        server.takeRequest()
        baseline = server.requestCount
    }

    private fun setContent(initiallyLiked: Boolean = false, initialCount: Int = 3) {
        compose.setContent {
            MaterialTheme {
                LikeButton(
                    type = Likable.Show,
                    id = SHOW_ID,
                    initiallyLiked = initiallyLiked,
                    initialCount = initialCount,
                )
            }
        }
    }

    @Test
    fun `an unliked show renders a border heart and its count`() {
        setContent(initiallyLiked = false, initialCount = 3)

        compose.onNodeWithContentDescription("Like").assertExists()
        compose.onNodeWithText("3").assertExists()
    }

    @Test
    fun `a liked show renders a filled heart`() {
        setContent(initiallyLiked = true, initialCount = 12)

        compose.onNodeWithContentDescription("Unlike").assertExists()
        compose.onAllNodesWithContentDescription("Like").assertCountEquals(0)
        compose.onNodeWithText("12").assertExists()
    }

    @Test
    fun `a show with no likes renders the heart without a count`() {
        setContent(initiallyLiked = false, initialCount = 0)

        compose.onNodeWithContentDescription("Like").assertExists()
        compose.onAllNodesWithText("0").assertCountEquals(0)
    }

    @Test
    fun `tapping while signed out is inert`() {
        setContent(initiallyLiked = false, initialCount = 3)

        compose.onNodeWithContentDescription("Like").performTouchInput { click() }
        compose.waitForIdle()

        // Still unliked, still 3, and nothing was asked of the server: likes are account
        // state, so a signed-out tap must not pretend to have worked.
        compose.onNodeWithContentDescription("Like").assertExists()
        compose.onNodeWithText("3").assertExists()
        assertEquals(baseline, server.requestCount)
    }

    @Test
    fun `tapping while signed in likes the show server-side and moves the count`() {
        signIn()
        server.enqueue(MockResponse().setBody("{}"))
        setContent(initiallyLiked = false, initialCount = 3)

        compose.onNodeWithContentDescription("Like").performTouchInput { click() }
        compose.waitUntil { compose.onAllNodesWithContentDescription("Unlike").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("4").assertExists()

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v2/likes", request.path)
        val body = request.body.readUtf8()
        assertEquals("Show", Regex("\"likable_type\":\"(\\w+)\"").find(body)!!.groupValues[1])
        assertEquals(SHOW_ID.toString(), Regex("\"likable_id\":(\\d+)").find(body)!!.groupValues[1])
        assertEquals("the-jwt", request.getHeader("X-Auth-Token"))
    }

    @Test
    fun `tapping a liked show unlikes it server-side and drops the count`() {
        signIn()
        server.enqueue(MockResponse().setBody("{}"))
        setContent(initiallyLiked = true, initialCount = 12)

        compose.onNodeWithContentDescription("Unlike").performTouchInput { click() }
        compose.waitUntil { compose.onAllNodesWithContentDescription("Like").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("11").assertExists()

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("Show", request.requestUrl!!.queryParameter("likable_type"))
        assertEquals(SHOW_ID.toString(), request.requestUrl!!.queryParameter("likable_id"))
    }

    @Test
    fun `a failed like rolls the optimistic state back`() {
        signIn()
        server.enqueue(MockResponse().setResponseCode(500))
        setContent(initiallyLiked = false, initialCount = 3)

        compose.onNodeWithContentDescription("Like").performTouchInput { click() }
        compose.waitUntil { server.requestCount >= 1 }
        // The request went out; the button has to fall back to where it started rather than
        // keep a heart the server never accepted.
        compose.waitUntil { compose.onAllNodesWithContentDescription("Like").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("3").assertExists()
        assertEquals(baseline + 1, server.requestCount)
    }

    @Test
    fun `a failed unlike rolls the optimistic state back`() {
        signIn()
        server.enqueue(MockResponse().setResponseCode(500))
        setContent(initiallyLiked = true, initialCount = 12)

        compose.onNodeWithContentDescription("Unlike").performTouchInput { click() }
        compose.waitUntil { server.requestCount >= 1 }
        compose.waitUntil { compose.onAllNodesWithContentDescription("Unlike").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("12").assertExists()
        assertEquals(baseline + 1, server.requestCount)
    }

    private companion object {
        const val SHOW_ID = 42L
    }
}
