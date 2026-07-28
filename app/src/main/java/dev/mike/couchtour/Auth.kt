package dev.mike.couchtour

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "phishin_auth"
private const val KEY_JWT = "jwt"
private const val KEY_USERNAME = "username"
private const val KEY_LASTFM_KEY = "lastfm_key"
private const val KEY_LASTFM_USER = "lastfm_user"

/**
 * Encrypted-at-rest storage for the phish.in JWT.
 *
 * The Android keystore can end up in an unrecoverable state (device restore, key reset),
 * in which case opening the encrypted store throws. Rather than crash on launch, we wipe
 * and retry once, and failing that fall back to holding the token in memory only — the
 * session lasts until the process dies and nothing sensitive is ever written in the clear.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences? = open(context) ?: run {
        context.deleteSharedPreferences(PREFS)
        open(context)
    }

    private var memoryJwt: String? = null
    private var memoryUsername: String? = null

    private fun open(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w("TokenStore", "Encrypted prefs unavailable, session will not persist", e)
        null
    }

    var jwt: String?
        get() = prefs?.getString(KEY_JWT, null) ?: memoryJwt
        set(value) {
            memoryJwt = value
            prefs?.edit()?.apply { if (value == null) remove(KEY_JWT) else putString(KEY_JWT, value) }?.apply()
        }

    var username: String?
        get() = prefs?.getString(KEY_USERNAME, null) ?: memoryUsername
        set(value) {
            memoryUsername = value
            write(KEY_USERNAME, value) { memoryUsername = value }
        }

    /** Last.fm session key. Long-lived, so it gets the same encrypted storage as the JWT. */
    var lastFmKey: String?
        get() = prefs?.getString(KEY_LASTFM_KEY, null) ?: memoryLastFmKey
        set(value) = write(KEY_LASTFM_KEY, value) { memoryLastFmKey = value }

    var lastFmUser: String?
        get() = prefs?.getString(KEY_LASTFM_USER, null) ?: memoryLastFmUser
        set(value) = write(KEY_LASTFM_USER, value) { memoryLastFmUser = value }

    private var memoryLastFmKey: String? = null
    private var memoryLastFmUser: String? = null

    private inline fun write(key: String, value: String?, remember: () -> Unit) {
        remember()
        prefs?.edit()?.apply { if (value == null) remove(key) else putString(key, value) }?.apply()
    }
}

/** Holds the signed-in identity and keeps [PhishInApi.authToken] in sync with it. */
object Session {

    private lateinit var store: TokenStore

    private val _username = MutableStateFlow<String?>(null)
    val username: StateFlow<String?> = _username.asStateFlow()

    val signedIn: Boolean get() = PhishInApi.authToken != null

    fun init(context: Context) {
        store = TokenStore(context)
        PhishInApi.authToken = store.jwt
        _username.value = store.username
        PhishInApi.onUnauthorized = { logout() }
    }

    /** Password is used for this one request and never stored. */
    suspend fun login(email: String, password: String) {
        val response = PhishInApi.login(email, password)
        PhishInApi.authToken = response.jwt
        store.jwt = response.jwt
        store.username = response.username
        _username.value = response.username
    }

    fun logout() {
        PhishInApi.authToken = null
        store.jwt = null
        store.username = null
        _username.value = null
    }
}
