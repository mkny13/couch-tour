package dev.mike.couchtour

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS = "phishin_auth"
private const val KEY_JWT = "jwt"
private const val KEY_USERNAME = "username"

/**
 * Encrypted-at-rest storage for the phish.in JWT.
 *
 * The Android keystore can end up in an unrecoverable state (device restore, key reset), in
 * which case opening the encrypted store throws [KeyPermanentlyInvalidatedException] — that's
 * the one case where the previously-written file can never be decrypted again, so we wipe and
 * retry once. Any other exception (e.g. the keystore not yet unlocked right after a reboot,
 * which often coincides with installing an update) is treated as transient: we fall back to
 * holding the token in memory for this launch *without* deleting the file, so a later launch
 * can still recover the persisted session instead of the user being silently logged out.
 * Nothing sensitive is ever written in the clear either way.
 */
class TokenStore(context: Context) {

    private val prefs: SharedPreferences? = try {
        open(context)
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.w("TokenStore", "Keystore key permanently invalidated, wiping and retrying", e)
        context.deleteSharedPreferences(PREFS)
        try {
            open(context)
        } catch (e2: Exception) {
            Log.w("TokenStore", "Encrypted prefs unavailable after wipe, session will not persist", e2)
            null
        }
    } catch (e: Exception) {
        Log.w("TokenStore", "Encrypted prefs unavailable, session will not persist this launch", e)
        null
    }

    private var memoryJwt: String? = null
    private var memoryUsername: String? = null

    private fun open(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
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
