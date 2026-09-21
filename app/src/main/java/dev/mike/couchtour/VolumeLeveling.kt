package dev.mike.couchtour

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Version of the measurement pipeline that wrote a [SourceLoudnessEntity] row. Bump it
 * whenever the measurement changes meaningfully — a stale row is a cache miss, not a wrong
 * answer ([SourceLoudnessDao.getCurrent]).
 */
const val LEVELING_ALGORITHM_VERSION = 1

// ---------------------------------------------------------------------------
// LevelingAudioProcessor — the playback gain (#267)
// ---------------------------------------------------------------------------

/**
 * A linear gain applied to the player's PCM output, ramped over ~50 ms to avoid clicks.
 *
 * `player.volume` alone can't do this: it tops out at 1.0, so it could never *boost* a
 * quiet AUD tape — and it's stomped by audio-focus ducking besides. The gain lives in the
 * render pipeline instead, installed through `DefaultRenderersFactory.buildAudioSink` in
 * [PlaybackService]; because the sink sits after decode, it applies equally to the MP3
 * and FLAC paths.
 *
 * Not thread-safe, deliberately: [setGainDb] is called from the main thread and
 * [queueInput] from the playback thread, and the shared gain state is plain `Float`
 * fields — a 32-bit JVM write is atomic, so the worst a racing read costs is one stale
 * sample during a ramp, which is inaudible. A lock in the realtime path would cost more
 * than that stale sample ever could (same tradeoff as the macOS tap, D259).
 */
class LevelingAudioProcessor : BaseAudioProcessor() {

    /** Gain ramp duration in seconds — long enough to mask a step, short enough to track segues. */
    private val rampSeconds = 0.05

    // Gain state, shared across threads (see class doc).
    private var currentGain = 1.0f
    private var rampFrom = 1.0f
    private var rampTo = 1.0f
    private var rampFramesTotal = 0L
    private var rampFramesDone = 0L

    private var channels = 0
    private var encoding = C.ENCODING_INVALID

    /** Set the playback gain in dB, ramped from wherever the audio currently is. */
    fun setGainDb(db: Double) {
        val target = 10.0.pow(db / 20.0).toFloat()
        rampFrom = currentGain
        rampTo = target
        rampFramesDone = 0
        val sr = inputAudioFormat.sampleRate
        rampFramesTotal = if (sr > 0) (rampSeconds * sr).toLong() else 0
        if (rampFramesTotal == 0L) currentGain = target
    }

    /** Jump straight back to 0 dB (setting toggled off) — still ramped, no click. */
    fun resetGain() = setGainDb(0.0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount
        encoding = enc
        return inputAudioFormat
    }

    override fun queueInput(input: ByteBuffer) {
        val out = replaceOutputBuffer(input.remaining())
        out.order(input.order())
        when (encoding) {
            C.ENCODING_PCM_FLOAT -> {
                // One frame = [channels] floats; the ramp advances per frame.
                while (input.remaining() >= 4 * max(1, channels)) {
                    val g = nextGain()
                    repeat(channels) { out.putFloat(input.float * g) }
                }
                out.put(input) // trailing partial frame, if the sink ever sends one
            }
            else -> {
                while (input.remaining() >= 2 * max(1, channels)) {
                    val g = nextGain()
                    repeat(channels) {
                        val s = input.short
                        // Clamp, don't wrap: a boosted sample past full scale must pin at
                        // ±32767, not overflow into the opposite sign.
                        out.putShort((s * g).roundToInt().coerceIn(-32768, 32767).toShort())
                    }
                }
                out.put(input)
            }
        }
        out.flip()
    }

    /** The gain for this frame, advancing the ramp by one. */
    private fun nextGain(): Float {
        if (rampFramesDone >= rampFramesTotal) return rampTo
        val g = rampFrom + (rampTo - rampFrom) * (rampFramesDone.toFloat() / rampFramesTotal)
        rampFramesDone++
        currentGain = if (rampFramesDone >= rampFramesTotal) rampTo else g
        return g
    }

    override fun onReset() {
        currentGain = 1.0f
        rampFrom = 1.0f
        rampTo = 1.0f
        rampFramesTotal = 0
        rampFramesDone = 0
    }
}

// ---------------------------------------------------------------------------
// Decode-ahead measurement
// ---------------------------------------------------------------------------

/** Interleaved float PCM a [SegmentDecoder] hands to the meter. */
class DecodedPcm(val sampleRate: Int, val channels: Int, val samples: FloatArray)

/** One track to sample: its MP3 URL (never FLAC — same master, much bigger) and length. */
data class LevelingSample(val url: String, val durationMs: Long)

/**
 * Turns a downloaded audio segment (an MP3 slice on disk) into float PCM for the meter.
 * Injectable so tests can measure against fixture PCM without `MediaCodec` in the loop —
 * Robolectric can't run a real decoder.
 */
fun interface SegmentDecoder {
    fun decode(file: File): DecodedPcm
}

/**
 * Which track indices get measured for one source: up to [max], spread evenly across the
 * track list so one measurement stands for the whole mix rather than its opening three
 * tracks (set 1 of a show is typically tighter than set 2). Matches the macOS measurer.
 */
fun levelingSampleIndices(total: Int, max: Int = LoudnessMeasurer.MAX_SAMPLED_TRACKS): List<Int> {
    if (total <= 0 || max <= 0) return emptyList()
    val count = min(max, total)
    if (count == total) return (0 until total).toList()
    return (0 until count).map { min(total - 1, ((it + 1) * total) / (count + 1)) }
}

/** One source's pooled measurement, ready to be written into the cache. */
data class LoudnessMeasurement(
    val lufs: Double,
    val peakDb: Double,
    val sampledTracks: Int,
)

/**
 * Background decode-ahead measurement of one source for volume leveling (#267).
 *
 * Pulls a 30 s slice from the middle of up to three of the source's tracks over HTTP
 * `Range` requests (a HEAD probe first asks the file size; a mid-file MP3 range decodes
 * because MP3 frames resync on their own), decodes each slice through the injected
 * [SegmentDecoder], and pools the segments into one BS.1770-4 measurement
 * ([LoudnessMeter]). Pure: it neither touches the cache nor the player — [VolumeLeveler]
 * owns both.
 *
 * A segment that fails to fetch or decode is skipped, and the segments that did measure
 * still feed the pooled result; only a measurement with *no* usable segment (or with every
 * block gated out, i.e. silence) returns null — and then nothing is written anywhere, so
 * the next queue load retries.
 */
class LoudnessMeasurer(
    private val client: OkHttpClient = defaultClient(),
    private val decoder: SegmentDecoder,
    private val tempDir: File,
) {
    companion object {
        /** Seconds of audio pulled from the middle of each sampled track. */
        const val SEGMENT_SECONDS = 30.0

        /** Most tracks sampled into one source measurement. */
        const val MAX_SAMPLED_TRACKS = 3

        /**
         * Byte-rate assumption when a HEAD probe yields no Content-Length — roughly a
         * 128 kbps MP3. Only affects where the Range starts, and only when the server
         * refuses to tell us the file size; the meter is rate-agnostic.
         */
        const val ASSUMED_BYTES_PER_SECOND = 16_000

        private fun defaultClient() = OkHttpClient.Builder()
            // Bounded so a hung host can't stall the worker past the queue that cancelled it.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Measure one source. Returns null when nothing usable was measured — the caller
     * writes nothing to the cache in that case, so the next queue load retries.
     */
    suspend fun measure(tracks: List<LevelingSample>): LoudnessMeasurement? {
        val indices = levelingSampleIndices(tracks.size)
        if (indices.isEmpty()) return null

        var meter: LoudnessMeter? = null
        var meterSampleRate = 0
        var meterChannels = 0
        var measured = 0
        for (i in indices) {
            val track = tracks[i]
            if (track.url.isBlank()) continue
            try {
                val bytes = fetchSegment(track.url, track.durationMs)
                val pcm = withContext(Dispatchers.IO) {
                    // MediaExtractor needs a seekable file; written under cache, deleted
                    // on the way out even when the decode throws.
                    val temp = File(tempDir, "leveling-${System.nanoTime()}.bin")
                    try {
                        temp.writeBytes(bytes)
                        decoder.decode(temp)
                    } finally {
                        temp.delete()
                    }
                }
                // The meter only supports 1..2 channels at 22050..96000 Hz; the first
                // usable segment fixes the format and any mismatching later one is skipped
                // (mixing formats through one meter would corrupt the pooled measurement).
                if (pcm.channels !in 1..2 || pcm.sampleRate !in 22050..96000) continue
                val m = meter ?: LoudnessMeter(pcm.sampleRate, pcm.channels).also {
                    meter = it
                    meterSampleRate = pcm.sampleRate
                    meterChannels = pcm.channels
                }
                if (pcm.sampleRate != meterSampleRate || pcm.channels != meterChannels) continue
                m.push(pcm.samples)
                measured++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One bad segment out of three shouldn't cost the source its measurement.
                continue
            }
        }

        val result = meter?.result() ?: return null
        val lufs = result.lufs ?: return null // every block gated out (silence)
        return LoudnessMeasurement(lufs, result.peakDbfs, measured)
    }

    /** Fetch the middle [SEGMENT_SECONDS] of [url] with one Range request. */
    private suspend fun fetchSegment(url: String, durationMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val (start, end) = segmentByteRange(url, durationMs)
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            response.body?.bytes()?.takeIf { it.isNotEmpty() } ?: throw IOException("empty body for $url")
        }
    }

    private suspend fun segmentByteRange(url: String, durationMs: Long): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val head = Request.Builder().url(url).head().build()
        val contentLength = try {
            client.newCall(head).execute().use { response ->
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            }
        } catch (e: Exception) {
            null
        }

        val durationSec = max(1.0, durationMs / 1000.0)
        val bytesPerSecond = contentLength?.let { it / durationSec } ?: ASSUMED_BYTES_PER_SECOND.toDouble()
        val totalBytes = contentLength?.toDouble() ?: bytesPerSecond * durationSec

        val segmentBytes = min(totalBytes, bytesPerSecond * SEGMENT_SECONDS)
        val start = max(0, ((totalBytes - segmentBytes) / 2).toLong())
        val end = min(totalBytes.toLong() - 1, start + segmentBytes.toLong())
        start to max(start, end)
    }
}

/**
 * The real decoder: opens the segment file with `MediaExtractor`, runs its audio track
 * through `MediaCodec`, and interleaves the PCM out as floats. Never unit-tested —
 * Robolectric can't run a codec; verified on device, per UAT.
 */
class MediaCodecSegmentDecoder : SegmentDecoder {
    override fun decode(file: File): DecodedPcm {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        try {
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            val audioFormat = format ?: throw IOException("no audio track in ${file.name}")
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()
            try {
                return drain(codec, extractor)
            } finally {
                codec.stop()
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    /** Standard queue/drain loop until EOS, collecting decoded PCM as interleaved floats. */
    private fun drain(codec: MediaCodec, extractor: MediaExtractor): DecodedPcm {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val samples = ArrayList<Float>()
        var sampleRate = 0
        var channels = 0
        // Hard stop: a corrupt segment can otherwise spin the loop forever.
        var guard = 0

        while (!outputDone && guard++ < 10_000) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buffer = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                outIndex >= 0 -> {
                    codec.getOutputBuffer(outIndex)?.let { buf ->
                        buf.order(ByteOrder.nativeOrder())
                        if (channels == 0) {
                            // Some decoders emit PCM before a format change; assume CD stereo.
                            channels = 2
                            sampleRate = 44_100
                        }
                        val encoding = codec.outputFormat.getInteger(
                            MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = buf.asFloatBuffer()
                            while (fb.hasRemaining()) samples.add(fb.get())
                        } else {
                            val sb = buf.asShortBuffer()
                            while (sb.hasRemaining()) samples.add(sb.get() / 32768f)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        }
        if (samples.isEmpty()) throw IOException("decoder produced no PCM")
        return DecodedPcm(sampleRate.takeIf { it > 0 } ?: 44_100, channels.coerceAtLeast(1), samples.toFloatArray())
    }
}

// The VolumeLeveler coordinator lives in VolumeLeveler.kt — one class per file keeps the
// measurer (pure) apart from the coordinator (db + scope), and keeps both readable.
