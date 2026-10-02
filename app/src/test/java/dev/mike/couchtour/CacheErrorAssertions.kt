package dev.mike.couchtour

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Asserts a cache-backed [load] treats a failed fetch as an error, not as "empty" (#445, the
 * class of bug behind #346/#352): the server answers 500 first, then 200 with [successBody].
 *
 *  1. The first call must throw. Returning an empty/default value would hide the failure.
 *  2. The second call must hit the network again and return the data, which proves the
 *     failure wasn't written to the cache.
 *
 * Returns the second call's result so the caller can assert on the data itself.
 */
suspend fun <T> assertErrorNotCachedAsEmpty(
    server: MockWebServer,
    successBody: String,
    load: suspend () -> T,
): T {
    val before = server.requestCount
    server.enqueue(MockResponse().setResponseCode(500))
    server.enqueue(MockResponse().setBody(successBody))

    try {
        val result = load()
        fail("a failed load must surface an error, but returned $result")
    } catch (e: AssertionError) {
        throw e
    } catch (_: Exception) {
        // expected: the 500 propagated
    }
    assertEquals("the failing call should have made one request", before + 1, server.requestCount)

    val second = load()
    assertEquals("the retry must re-fetch, not replay a cached failure", before + 2, server.requestCount)
    assertTrue("the retry must return the data", (second as? Collection<*>)?.isNotEmpty() ?: true)
    return second
}
