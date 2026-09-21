package dev.mike.couchtour

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The leveling coordinator (#267): decides *which* source is playing, gets its cached
 * measurement (or measures it once), and hands the resulting gain to the caller — the
 * service applies it to [LevelingAudioProcessor].
 *
 * One source at a time. A queue change cancels any in-flight measurement; a measurement
 * that never finishes (cancel, all segments failed) leaves the cache untouched, so the
 * next queue load retries. Unknown loudness means no gain: a fresh source plays at 0 dB
 * until its measurement lands (the #18 gain rule, same as the macOS measurer).
 *
 * Main-thread only — the service calls this from its main dispatcher and the callbacks
 * come back on it too; [LoudnessMeasurer] hops to IO internally.
 */
class VolumeLeveler(
    private val scope: CoroutineScope,
    private val db: PhishInDb,
    private val measurer: LoudnessMeasurer,
) {
    private var activeKey: String? = null
    private var measurement: Job? = null

    /**
     * The playing queue changed. [key] is the queue's leveling identity ([Keys.LEVELING_KEY])
     * or null for an unleveled queue (shuffled, a Relisten row of a local playlist). A null
     * key only stops measurement — the gain stays where it is until the next keyed queue.
     */
    fun onQueueChanged(key: String?, samples: List<LevelingSample>, onGain: (Double) -> Unit) {
        if (key == null) {
            measurement?.cancel()
            measurement = null
            activeKey = null
            return
        }
        if (key == activeKey) return // repeated transitions inside the same queue
        measurement?.cancel()
        activeKey = key
        if (samples.isEmpty()) {
            onGain(0.0)
            return
        }
        measurement = scope.launch {
            // New source: play at unity until its loudness is known.
            onGain(0.0)
            val dao = db.sourceLoudnessDao()
            val cached = runCatching { dao.getCurrent(key, LEVELING_ALGORITHM_VERSION) }.getOrNull()
            if (cached != null) {
                onGain(levelingGainDb(cached.lufs, cached.peakDb))
                return@launch
            }
            val result = measurer.measure(samples) ?: return@launch // stays at 0 dB, no cache write
            runCatching {
                dao.upsert(
                    SourceLoudnessEntity(
                        key = key,
                        lufs = result.lufs,
                        peakDb = result.peakDb,
                        sampledTracks = result.sampledTracks,
                        algorithmVersion = LEVELING_ALGORITHM_VERSION,
                        measuredAt = System.currentTimeMillis(),
                    )
                )
            }
            onGain(levelingGainDb(result.lufs, result.peakDb))
        }
    }

    /** The setting turned off: stop measuring, drop the source memory, back to unity. */
    fun onDisabled(onGain: (Double) -> Unit) {
        measurement?.cancel()
        measurement = null
        activeKey = null
        onGain(0.0)
    }
}
