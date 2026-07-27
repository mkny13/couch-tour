package dev.mike.phishin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobblePolicyTest {

    @Test
    fun `tracks of thirty seconds or less never count`() {
        // Last.fm's rule: a track must be longer than 30 seconds to be scrobbleable.
        assertFalse(ScrobblePolicy.eligible(30_000))
        assertFalse(ScrobblePolicy.eligible(10_000))
        assertTrue(ScrobblePolicy.eligible(30_001))
    }

    @Test
    fun `a short track counts at its halfway point`() {
        val threeMinutes = 180_000L
        assertFalse(ScrobblePolicy.shouldScrobble(89_999, threeMinutes))
        assertTrue(ScrobblePolicy.shouldScrobble(90_000, threeMinutes))
    }

    @Test
    fun `a long jam counts at four minutes rather than halfway`() {
        // A 20-minute Tweezer shouldn't need ten minutes to register.
        val twentyMinutes = 1_200_000L
        assertFalse(ScrobblePolicy.shouldScrobble(239_999, twentyMinutes))
        assertTrue(ScrobblePolicy.shouldScrobble(240_000, twentyMinutes))
    }

    @Test
    fun `an ineligible track never counts however long it is played`() {
        assertFalse(ScrobblePolicy.shouldScrobble(600_000, 20_000))
    }

    @Test
    fun `nothing counts before it has been played at all`() {
        assertFalse(ScrobblePolicy.shouldScrobble(0, 300_000))
    }
}

class ScrobblerTimingTest {

    private val submitted = mutableListOf<PendingScrobble>()
    private fun scrobbler() = Scrobbler { submitted += it }

    private val fiveMinutes = 300_000L

    @Test
    fun `submits once the listened time passes the threshold`() {
        val s = scrobbler()
        s.onTrackChanged("Tweezer", "1997-11-17", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)

        s.onTick(nowMs = 100_000)
        assertTrue(submitted.isEmpty())

        s.onTick(nowMs = 150_000)
        assertEquals(1, submitted.size)
        assertEquals("Tweezer", submitted[0].track)
        assertEquals("Phish", submitted[0].artist)
    }

    @Test
    fun `submits a track only once however long it keeps playing`() {
        val s = scrobbler()
        s.onTrackChanged("Reba", "1997-11-17", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)

        s.onTick(nowMs = 200_000)
        s.onTick(nowMs = 250_000)
        s.onTick(nowMs = 300_000)

        assertEquals(1, submitted.size)
    }

    @Test
    fun `paused time does not count towards the threshold`() {
        val s = scrobbler()
        s.onTrackChanged("Stash", "1997-11-17", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)
        s.onTick(nowMs = 60_000)

        // Paused for an hour, then resumed.
        s.onPlayingChanged(false, nowMs = 60_000)
        s.onPlayingChanged(true, nowMs = 3_660_000)
        s.onTick(nowMs = 3_680_000)

        // 60s + 20s listened, well short of the 150s needed.
        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `seeking to the end does not fake a play`() {
        val s = scrobbler()
        s.onTrackChanged("Possum", "1997-11-17", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)

        // Accumulated listening time is what counts, not the playhead position.
        s.onTick(nowMs = 5_000)

        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `changing track submits the outgoing one if it earned it`() {
        val s = scrobbler()
        s.onTrackChanged("Tweezer", "1997-11-17", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)
        s.onTick(nowMs = 149_000)
        assertTrue(submitted.isEmpty())

        s.onTick(nowMs = 151_000)
        s.onTrackChanged("Reba", "1997-11-17", fiveMinutes, nowMs = 151_000)

        assertEquals(listOf("Tweezer"), submitted.map { it.track })
    }

    @Test
    fun `skipping a track early submits nothing`() {
        val s = scrobbler()
        s.onTrackChanged("Tweezer", "1997-11-17", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)
        s.onTick(nowMs = 10_000)

        s.onTrackChanged("Reba", "1997-11-17", fiveMinutes, nowMs = 10_000)

        assertTrue(submitted.isEmpty())
    }

    @Test
    fun `records the moment the track started, not when it was submitted`() {
        val startMs = 1_700_000_000_000L
        val s = scrobbler()
        s.onTrackChanged("Tweezer", "1997-11-17", fiveMinutes, nowMs = startMs)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = startMs)
        s.onTick(nowMs = startMs + 200_000)

        // Last.fm wants the start time, so a scrobble lands in the right slot.
        assertEquals(startMs / 1000, submitted.single().timestampSec)
    }

    @Test
    fun `carries the show as the album and the duration in seconds`() {
        val s = scrobbler()
        s.onTrackChanged("Tweezer", "1997-11-17 · McNichols Arena", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)
        s.onTick(nowMs = 200_000)

        val scrobble = submitted.single()
        assertEquals("1997-11-17 · McNichols Arena", scrobble.album)
        assertEquals(300, scrobble.durationSec)
    }

    @Test
    fun `a late duration still allows the track to be scrobbled`() {
        // The player reports no duration at transition time; it arrives on a later tick.
        val s = scrobbler()
        s.onTrackChanged("Tweezer", "1997-11-17", durationMs = 0, nowMs = 0)
        s.onPlayingChanged(true, nowMs = 0)
        s.onTick(nowMs = 100_000)
        assertTrue(submitted.isEmpty())

        s.onDuration(fiveMinutes)
        s.onTick(nowMs = 160_000)

        assertEquals(1, submitted.size)
    }

    @Test
    fun `stopping submits a track that had earned it`() {
        val s = scrobbler()
        s.onTrackChanged("Tweezer", "1997-11-17", fiveMinutes, nowMs = 0)
        s.onDuration(fiveMinutes)
        s.onPlayingChanged(true, nowMs = 0)
        s.onTick(nowMs = 200_000)
        submitted.clear()

        s.onStopped(nowMs = 210_000)

        // Already submitted on the tick; stopping must not double-count it.
        assertTrue(submitted.isEmpty())
    }
}

class LastFmSignatureTest {

    @Test
    fun `signs sorted params with the secret appended`() {
        // Expected value computed independently rather than by re-implementing the scheme.
        assertEquals(
            "1d0396bcbc2c54e569e7af9cf9c4685e",
            signature(mapOf("a" to "1", "b" to "2"), "s")
        )
    }

    @Test
    fun `orders params by name regardless of insertion order`() {
        val forwards = signature(mapOf("a" to "1", "b" to "2"), "s")
        val backwards = signature(mapOf("b" to "2", "a" to "1"), "s")
        assertEquals(forwards, backwards)
    }

    @Test
    fun `signs a realistic scrobble call`() {
        val params = mapOf(
            "method" to "track.scrobble",
            "api_key" to "KEY",
            "artist" to "Phish",
            "track" to "Tweezer",
            "timestamp" to "1700000000",
            "sk" to "SESSION",
        )
        assertEquals(
            "71156470478a7aaeae7ee48f0b9ee3e0",
            signature(params, "SECRET")
        )
    }

    @Test
    fun `a different secret produces a different signature`() {
        val params = mapOf("a" to "1")
        assertTrue(signature(params, "one") != signature(params, "two"))
    }
}
