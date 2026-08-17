package dev.mike.couchtour

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// ------------------------------------------------------------------- wire types
//
// Field names and shapes mirror sync/src/types.ts's ProgressFields exactly — this is the
// contract both clients and the Worker share, not just a Kotlin convenience.

@Serializable
data class SyncProgressWire(
    val queueKey: String,
    val title: String,
    val subtitle: String,
    val artUrl: String? = null,
    val trackIndex: Int,
    val positionMs: Long,
    val trackTitle: String,
    val updatedAt: Long,
    val finished: Boolean,
    val dismissed: Boolean,
    val artist: String,
    val deletedAt: Long? = null,
)

@Serializable
private data class PairStartRequest(val deviceName: String, val platform: String)

@Serializable
data class PairStartResponse(
    val code: String,
    val expiresAt: Long,
    val deviceId: String? = null,
    val deviceToken: String? = null,
)

/**
 * Looked up server-side by the code alone (D127) — no separate pairing id, so the whole
 * thing is short enough for a human to type.
 */
@Serializable
private data class PairClaimRequest(
    val code: String,
    val deviceName: String,
    val platform: String,
)

@Serializable
data class PairClaimResponse(val deviceId: String, val deviceToken: String)

@Serializable
private data class SyncRequest(val since: Long, val changes: List<SyncProgressWire>)

@Serializable
data class SyncResponse(val seq: Long, val changes: List<SyncProgressWire>)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val createdAt: Long,
    val lastSeenAt: Long?,
    val isSelf: Boolean,
)

@Serializable
private data class DevicesResponse(val devices: List<DeviceInfo>)

@Serializable
private data class ErrorResponse(val error: String = "")

class SyncException(message: String, val code: Int = 0) : Exception(message) {
    val unauthorized: Boolean get() = code == 401
    val gone: Boolean get() = code == 410
}

// ------------------------------------------------------------------------ network client

/**
 * Client for the sync backend (sync/, D119-D126) at
 * https://couch-tour-sync.mkastellec.workers.dev. Deliberately its own OkHttp client and
 * `Authorization: Bearer` scheme, separate from [PhishInApi]'s `X-Auth-Token` JWT — this is
 * an unrelated service with an unrelated identity.
 */
object SyncApi {
    private val DEFAULT_BASE = "https://couch-tour-sync.mkastellec.workers.dev".toHttpUrl()

    /** Overridden by tests to point at a local mock server. */
    internal var baseUrl: HttpUrl = DEFAULT_BASE

    private val JSON_MEDIA = "application/json".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    // encodeDefaults matters here in a way it doesn't for PhishInApi, which only ever decodes:
    // without it, kotlinx.serialization omits any property still equal to its default, so a row
    // with no artwork sent `artUrl`/`deletedAt` as absent keys rather than explicit nulls. The
    // server's D1 `.bind()` rejects `undefined`, so every push containing such a row 500'd —
    // which was every push, since artUrl is null for all Relisten rows.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun path(vararg segments: String) =
        baseUrl.newBuilder().apply { segments.forEach { addPathSegment(it) } }

    private data class HttpResult(val code: Int, val body: String, val rotatedToken: String?)

    private suspend fun execute(request: Request): HttpResult = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            HttpResult(resp.code, resp.body?.string().orEmpty(), resp.header("X-Sync-Token-Rotated"))
        }
    }

    private fun Request.Builder.withToken(token: String?) = apply {
        header("Accept", "application/json")
        token?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun post(url: HttpUrl, payload: String, token: String? = null): HttpResult =
        execute(Request.Builder().url(url).withToken(token).post(payload.toRequestBody(JSON_MEDIA)).build())

    private suspend fun get(url: HttpUrl, token: String): HttpResult =
        execute(Request.Builder().url(url).withToken(token).build())

    private suspend fun delete(url: HttpUrl, token: String): HttpResult =
        execute(Request.Builder().url(url).withToken(token).delete().build())

    private fun HttpResult.orThrow(): HttpResult {
        if (code !in 200..299) {
            val message = runCatching { json.decodeFromString<ErrorResponse>(body).error }
                .getOrDefault("")
                .ifEmpty { "HTTP $code" }
            throw SyncException(message, code)
        }
        return this
    }

    suspend fun pairStart(deviceName: String, platform: String, existingToken: String?): PairStartResponse {
        val payload = json.encodeToString(PairStartRequest.serializer(), PairStartRequest(deviceName, platform))
        val result = post(path("pair", "start").build(), payload, existingToken).orThrow()
        return json.decodeFromString(result.body)
    }

    suspend fun pairClaim(code: String, deviceName: String, platform: String): PairClaimResponse {
        val payload = json.encodeToString(PairClaimRequest.serializer(), PairClaimRequest(code, deviceName, platform))
        val result = post(path("pair", "claim").build(), payload).orThrow()
        return json.decodeFromString(result.body)
    }

    /** The `String?` half of the pair is the rotated token header, when the server sent one. */
    suspend fun sync(token: String, since: Long, changes: List<SyncProgressWire>): Pair<SyncResponse, String?> {
        val payload = json.encodeToString(SyncRequest.serializer(), SyncRequest(since, changes))
        val result = post(path("sync").build(), payload, token).orThrow()
        return json.decodeFromString<SyncResponse>(result.body) to result.rotatedToken
    }

    suspend fun devices(token: String): List<DeviceInfo> {
        val result = get(path("devices").build(), token).orThrow()
        return json.decodeFromString<DevicesResponse>(result.body).devices
    }

    suspend fun revokeDevice(token: String, deviceId: String) {
        delete(path("devices", deviceId).build(), token).orThrow()
    }
}

// --------------------------------------------------------------------------- token storage

private const val SYNC_PREFS = "couchtour_sync"
private const val KEY_DEVICE_TOKEN = "deviceToken"
private const val KEY_DEVICE_ID = "deviceId"
private const val KEY_LAST_SEQ = "lastSeq"
private const val KEY_LAST_PUSH_WATERMARK = "lastPushWatermark"

/**
 * Encrypted-at-rest storage for the sync device token — its own prefs file, deliberately NOT
 * [TokenStore]'s `phishin_auth`: signing out of phish.in must not unpair this device from
 * sync, and vice versa. Same fallback shape as [TokenStore] for the same reason: an
 * unrecoverable keystore degrades to in-memory-only rather than crashing the app.
 */
class SyncTokenStore(context: Context) {

    private val prefs: SharedPreferences? = open(context) ?: run {
        context.deleteSharedPreferences(SYNC_PREFS)
        open(context)
    }

    private var memoryToken: String? = null
    private var memoryDeviceId: String? = null
    private var memoryLastSeq: Long = 0L
    private var memoryLastPushWatermark: Long = 0L

    private fun open(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            SYNC_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w("SyncTokenStore", "Encrypted prefs unavailable, sync will not persist", e)
        null
    }

    var deviceToken: String?
        get() = prefs?.getString(KEY_DEVICE_TOKEN, null) ?: memoryToken
        set(value) {
            memoryToken = value
            prefs?.edit()?.apply { if (value == null) remove(KEY_DEVICE_TOKEN) else putString(KEY_DEVICE_TOKEN, value) }?.apply()
        }

    var deviceId: String?
        get() = prefs?.getString(KEY_DEVICE_ID, null) ?: memoryDeviceId
        set(value) {
            memoryDeviceId = value
            prefs?.edit()?.apply { if (value == null) remove(KEY_DEVICE_ID) else putString(KEY_DEVICE_ID, value) }?.apply()
        }

    /**
     * The pull cursor: the highest `seq` this device has already applied. Not sensitive, kept
     * here purely for convenience — same memory fallback as [deviceToken], so an unavailable
     * encrypted store costs an extra full pull next launch rather than every sync forgetting
     * its own cursor mid-process.
     */
    var lastSeq: Long
        get() = prefs?.getLong(KEY_LAST_SEQ, 0L) ?: memoryLastSeq
        set(value) {
            memoryLastSeq = value
            prefs?.edit()?.putLong(KEY_LAST_SEQ, value)?.apply()
        }

    /** The push watermark: the highest local `updatedAt` already sent to the server. */
    var lastPushWatermark: Long
        get() = prefs?.getLong(KEY_LAST_PUSH_WATERMARK, 0L) ?: memoryLastPushWatermark
        set(value) {
            memoryLastPushWatermark = value
            prefs?.edit()?.putLong(KEY_LAST_PUSH_WATERMARK, value)?.apply()
        }

    fun clear() {
        memoryToken = null
        memoryDeviceId = null
        memoryLastSeq = 0L
        memoryLastPushWatermark = 0L
        prefs?.edit()?.clear()?.apply()
    }
}

// ---------------------------------------------------------------------------- orchestration

/**
 * Pairing and the push/pull sync cycle. A device with no stored token is simply unpaired —
 * [sync] is then a no-op, not an error, so it's always safe to call from a launch hook or a
 * periodic job without checking [paired] first.
 */
object SyncSession {

    private lateinit var store: SyncTokenStore

    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    fun init(context: Context) {
        store = SyncTokenStore(context.applicationContext)
        _paired.value = store.deviceToken != null
    }

    /**
     * Bootstraps a new group if unpaired, or mints a fresh code inside the existing group if
     * already paired (adding a further device). Either way, returns the code to show.
     */
    suspend fun startPairing(): PairStartResponse {
        val response = SyncApi.pairStart(Build.MODEL, "android", store.deviceToken)
        if (response.deviceToken != null && response.deviceId != null) {
            store.deviceToken = response.deviceToken
            store.deviceId = response.deviceId
            _paired.value = true
        }
        return response
    }

    /**
     * Claims a code shown on another device, joining its group. Callers should follow this
     * with a [sync] — pairing that leaves both sides looking empty until some later timer
     * fires reads as "it didn't work", which is exactly how this landed the first time.
     */
    suspend fun claimPairing(code: String) {
        val response = SyncApi.pairClaim(code, Build.MODEL, "android")
        store.deviceToken = response.deviceToken
        store.deviceId = response.deviceId
        store.lastSeq = 0
        store.lastPushWatermark = 0
        _paired.value = true
    }

    suspend fun devices(): List<DeviceInfo> {
        val token = store.deviceToken ?: return emptyList()
        return SyncApi.devices(token)
    }

    /** Revokes any device in the group, including this one, from the settings screen. */
    suspend fun revoke(deviceId: String) {
        val token = store.deviceToken ?: return
        SyncApi.revokeDevice(token, deviceId)
        if (deviceId == store.deviceId) unlink()
    }

    /** Wipes local pairing state without contacting the server — "unlink this device". */
    fun unlink() {
        store.clear()
        _paired.value = false
    }

    /**
     * One push-then-pull cycle. Pushes every local row touched since the last successful
     * push (tombstones included — see [ProgressDao.changedSince]), applies whatever the
     * server sends back, and advances both cursors only on success.
     */
    suspend fun sync(progressDao: ProgressDao) {
        val token = store.deviceToken ?: return
        val toPush = progressDao.changedSince(store.lastPushWatermark).map { it.toWire() }

        try {
            val (response, rotatedToken) = SyncApi.sync(token, store.lastSeq, toPush)
            rotatedToken?.let { store.deviceToken = it }

            response.changes.forEach { progressDao.put(it.toEntity()) }
            store.lastSeq = response.seq
            if (toPush.isNotEmpty()) store.lastPushWatermark = toPush.maxOf { it.updatedAt }
        } catch (e: SyncException) {
            when {
                // Revoked from another device (or the token is simply bad): stop trying
                // until the user re-pairs, rather than retrying a request that can't succeed.
                e.unauthorized -> unlink()
                // Cursor predates the tombstone retention floor: start over from scratch.
                // since = 0 never 410s (D126), so this terminates in one extra round trip.
                e.gone -> { store.lastSeq = 0; sync(progressDao) }
                else -> throw e
            }
        }
    }
}

private fun Progress.toWire() = SyncProgressWire(
    queueKey = queueKey, title = title, subtitle = subtitle, artUrl = artUrl,
    trackIndex = trackIndex, positionMs = positionMs, trackTitle = trackTitle,
    updatedAt = updatedAt, finished = finished, dismissed = dismissed, artist = artist,
    deletedAt = deletedAt,
)

private fun SyncProgressWire.toEntity() = Progress(
    queueKey = queueKey, title = title, subtitle = subtitle, artUrl = artUrl,
    trackIndex = trackIndex, positionMs = positionMs, trackTitle = trackTitle,
    updatedAt = updatedAt, finished = finished, dismissed = dismissed, artist = artist,
    deletedAt = deletedAt,
)

// ---------------------------------------------------------------------------- scheduling

private const val SYNC_WORK_NAME = "sync-periodic"

/**
 * Background catch-up for when the app isn't in the foreground. 15 minutes is WorkManager's
 * own floor for periodic work — there's no shorter built-in interval to ask for. Retrying on
 * failure (a network blip, the device offline) rather than surfacing an error: nothing here
 * is user-initiated, so there's no UI to report it to anyway.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        SyncSession.sync(PhishInDb.get(applicationContext).progressDao())
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}

/**
 * Registers the periodic sync job. Safe to call on every launch: [ExistingPeriodicWorkPolicy.KEEP]
 * leaves an already-scheduled job alone rather than resetting its timer.
 *
 * [WorkManager.getInstance] throws if WorkManager's own initialization hasn't run — true of
 * every Robolectric test (there's no [androidx.work.Configuration.Provider] wired up for
 * tests, on purpose, to keep every other test from needing to know sync exists), and
 * conceivably true on a real device in some unanticipated state. Either way, background
 * scheduling failing is not a reason to crash app startup — the on-launch sync in
 * [CouchTourApp] still runs regardless.
 */
fun schedulePeriodicSync(context: Context) {
    try {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    } catch (e: Exception) {
        Log.w("Sync", "Could not schedule periodic sync", e)
    }
}
