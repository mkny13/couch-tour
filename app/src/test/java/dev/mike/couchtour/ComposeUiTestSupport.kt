package dev.mike.couchtour

import android.app.Application
import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.Shadows.shadowOf

/**
 * Shared plumbing for the Compose UI tests (#250). They run under Robolectric in
 * `testDebugUnitTest`, like the rest of the suite, so they need no emulator — but they render
 * the app's real composables and drive them through the semantics tree the way a user would:
 * tapping chips, typing into fields, long-pressing rows.
 */

/** A phone-width but very tall screen, so a lazy list composes every row a test cares about. */
const val UI_TEST_QUALIFIERS = "w400dp-h2400dp"

/**
 * `createAndroidComposeRule<ComponentActivity>()` launches a bare `ComponentActivity`, which
 * normally comes from the `ui-test-manifest` artifact. That artifact would have to be a
 * `debugImplementation` dependency — and beta builds are debug builds, so it would ship an
 * exported activity to real devices. Registering the activity with Robolectric's package
 * manager gets the same result without touching the APK.
 */
private class RegisterHostActivity : ExternalResource() {
    override fun before() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager)
            .addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        // Every screen Header carries CastButton, whose androidx MediaRouter leans on the
        // platform android.media.MediaRouter — and that one's static state only exists once
        // something has asked for the system service. On a device the system does that
        // before the app starts; under Robolectric nothing does, so discovery NPEs.
        app.getSystemService(Context.MEDIA_ROUTER_SERVICE)
    }
}

/** The compose rule plus the activity registration it depends on, in the right order. */
class ComposeUiRule {
    val compose = createAndroidComposeRule<ComponentActivity>()
    val chain: RuleChain = RuleChain.outerRule(RegisterHostActivity()).around(compose)

    /**
     * Renders [content] as the start destination of a real NavHost, with placeholder
     * destinations for every route the screens under test navigate to — so a tap that
     * navigates can be asserted on via [NavHostController.currentRoute], and one that names a
     * route nobody declared fails loudly instead of passing silently.
     */
    fun setScreen(content: @Composable (NavHostController) -> Unit): () -> NavHostController {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            CouchTourTheme(themeMode = ThemeMode.DARK) {
                NavHost(nav, startDestination = "start") {
                    composable("start") { content(nav) }
                    NAV_TARGETS.forEach { route -> composable(route) {} }
                }
            }
        }
        return { nav }
    }

    companion object {
        private val NAV_TARGETS = listOf(
            "home",
            "player",
            "artist/{backend}/{id}",
            "artist/{backend}/{id}/{period}?label={label}",
            "recording/{backend}/{artistId}/{date}?src={src}",
            "show/{date}",
            "playlist/{slug}",
            "local-playlist/{id}",
        )
    }
}

val NavHostController.currentRoute: String?
    get() = currentBackStackEntry?.destination?.route

fun NavHostController.arg(name: String): String? = currentBackStackEntry?.arguments?.getString(name)

/**
 * Titles of every node with one of [candidates] as its text, top to bottom — how a test reads
 * "what order is this list in" off the screen rather than off the data it fed in.
 */
fun SemanticsNodeInteractionsProvider.onScreenOrder(candidates: List<String>): List<String> =
    candidates
        .mapNotNull { text ->
            val nodes = onAllNodesWithText(text).fetchSemanticsNodes()
            nodes.firstOrNull()?.let { text to it.boundsInRoot.top }
        }
        .sortedBy { it.second }
        .map { it.first }

/**
 * A FilterChip labelled [label]. Chip labels routinely collide with other text on the same
 * screen — "SBD" is both a tag-filter chip and a badge on every soundboard row — so matching
 * on selectability is what tells the control apart from the decoration.
 */
fun chip(label: String): SemanticsMatcher = hasText(label) and isSelectable()

fun SemanticsNodeInteractionsProvider.onChip(label: String): SemanticsNodeInteraction =
    onNode(chip(label))

fun SemanticsNodeInteraction.exists(): Boolean =
    runCatching { fetchSemanticsNode() }.isSuccess

/**
 * Stands both catalog backends up on local [MockWebServer]s, answering by path so a screen's
 * requests can arrive in any order (they fan out concurrently). Unmatched paths 404, which the
 * screens treat as a backend failure — so a test that forgets a fixture sees an error state,
 * not a hang.
 */
class CatalogServers(
    private val phishIn: Map<String, String> = emptyMap(),
    private val relisten: Map<String, String> = emptyMap(),
) {
    private val phishInServer = MockWebServer()
    private val relistenServer = MockWebServer()

    fun start() {
        phishInServer.dispatcher = byPath("/api/v2", phishIn)
        relistenServer.dispatcher = byPath("/api", relisten)
        phishInServer.start()
        relistenServer.start()
        PhishInApi.baseUrl = phishInServer.url("/api/v2")
        RelistenApi.baseUrl = relistenServer.url("/api")
        resetCaches()
    }

    fun shutdown() {
        phishInServer.shutdown()
        relistenServer.shutdown()
        PhishInApi.baseUrl = "https://phish.in/api/v2".toHttpUrl()
        RelistenApi.baseUrl = "https://api.relisten.net/api".toHttpUrl()
        resetCaches()
    }

    private fun resetCaches() {
        PhishInSource.resetCache()
        RelistenCatalogSource.resetCache()
    }

    private fun byPath(prefix: String, bodies: Map<String, String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty().removePrefix(prefix)
            val body = bodies[path] ?: return MockResponse().setResponseCode(404)
            return MockResponse().setBody(body)
        }
    }
}
