package com.hashtagchow.magehand.core.data.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hashtagchow.magehand.core.data.api.ApiException
import java.io.File
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.SecretKey

/**
 * The production [TokenStore]: resume tokens sealed with AES-256-GCM under a
 * non-exportable **Android Keystore** key ([AndroidKeystoreTokenKeyProvider]),
 * kept in an ordinary `SharedPreferences` file.
 *
 * This is WP8's replacement for WP3's `EncryptedPrefsTokenStore`. See
 * [AndroidKeystoreTokenKeyProvider] for the property-by-property comparison with
 * the deprecated `androidx.security:security-crypto` it retires, and
 * docs/design/05-security.md §"Token & credential handling" for the requirement.
 *
 * Beyond "it's encrypted", the same two things still hold:
 * - the pref file is excluded from cloud backup and device transfer
 *   (`allowBackup="false"` plus `res/xml/data_extraction_rules.xml`), so the
 *   ciphertext never leaves the device whose Keystore holds its key;
 * - the token is only handed out through [read]; nothing here logs, and
 *   [StoredToken.toString] redacts.
 *
 * A token that cannot be decrypted — key invalidated, key unobtainable at all, file
 * restored onto a different device, blob truncated — reads as **absent**, so the user
 * is sent back to the login screen instead of into an unexplained `AUTH_FAILED`.
 * Whether the blob is also *evicted* depends on whether the failure can ever heal;
 * see [read]. Keystore work is real work (tens of ms cold), so the prefs handle is
 * lazy and every access hops to [ioDispatcher].
 *
 * The two directions are deliberately *not* symmetric:
 *
 * - **[read] never throws.** "Absent" is a state the whole app already handles (it
 *   is what a fresh install looks like), so a Keystore that will not hand over a key
 *   degrades into a login screen. See [readableKey].
 * - **[save] throws [ApiException.SecureStorageUnavailable].** Here "absent" would be
 *   a lie: the caller is mid-sign-in and about to write an `accounts` row whose token
 *   silently is not there. `DefaultAccountRepository.runApi` turns the typed failure
 *   into a failed `Result` and the login screen renders its message, which is the
 *   error path that already exists for every server-side rejection.
 */
class KeystoreTokenStore(
    private val context: Context,
    private val keyProvider: TokenKeyProvider = AndroidKeystoreTokenKeyProvider(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val prefsFileName: String = PREFS_FILE_NAME,
) : TokenStore {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE)
    }

    override suspend fun save(accountId: String, token: StoredToken) = withContext(ioDispatcher) {
        val sealed = try {
            // keyProvider.key() is inside the try on purpose: it is the call that
            // reaches the Keystore, so it is the one that fails on a wedged keymaster.
            AesGcmTokenCodec.encrypt(keyProvider.key(), token.token)
        } catch (e: GeneralSecurityException) {
            throw ApiException.SecureStorageUnavailable(e)
        } catch (e: ProviderException) {
            throw ApiException.SecureStorageUnavailable(e)
        }
        prefs.edit()
            .putString(tokenKey(accountId), sealed)
            .apply {
                if (token.expiresAtEpochMillis == null) {
                    remove(expiryKey(accountId))
                } else {
                    putLong(expiryKey(accountId), token.expiresAtEpochMillis)
                }
            }
            // commit(), not apply(): callers persist the matching Room row straight
            // after, and an account row without its token is a broken account.
            .commit()
        Unit
    }

    /**
     * Reads a token, or reports absent. **Never throws, and never evicts a blob that
     * might still be good.**
     *
     * Every failure reads as "absent" to the caller, but they are not the same failure
     * underneath, and the difference is a whole account's worth of data:
     *
     * - **Permanent** (key invalidated, blob sealed under a key that is gone, blob
     *   corrupt): the entry is evicted. Leaving it would mean failing forever, and the
     *   user has to sign in again either way.
     * - **Transient** (the keymaster or StrongBox is wedged — [ProviderException], at
     *   either the key handout *or* the cipher operation): absent is reported and the
     *   blob is **left alone**. 1.0.2 crashed here; evicting instead would be worse
     *   than the crash it replaced — a single bad boot would silently destroy a valid
     *   token and sign every account out permanently, with nothing to restore from
     *   (the pref file is excluded from backup on purpose). Leaving the blob makes the
     *   failure self-healing: the next read after the keymaster recovers succeeds.
     */
    override suspend fun read(accountId: String): StoredToken? = withContext(ioDispatcher) {
        val sealed = prefs.getString(tokenKey(accountId), null) ?: return@withContext null

        val plaintext = when (val keyState = readableKey()) {
            // Transient: report absent, evict nothing, try again next time.
            is KeyState.TemporarilyUnavailable -> return@withContext null
            is KeyState.PermanentlyGone -> null
            is KeyState.Available -> when (val opened = AesGcmTokenCodec.unseal(keyState.key, sealed)) {
                // The same transient verdict, one step later: a Keystore key can hand
                // out cleanly and still refuse to operate.
                is AesGcmTokenCodec.Unsealed.Unavailable -> return@withContext null
                is AesGcmTokenCodec.Unsealed.Unreadable -> null
                is AesGcmTokenCodec.Unsealed.Plaintext -> opened.value
            }
        }

        if (plaintext == null) {
            // Unreadable is indistinguishable from absent to every caller, so make it
            // actually absent rather than leaving a blob that will fail forever.
            prefs.edit().remove(tokenKey(accountId)).remove(expiryKey(accountId)).commit()
            return@withContext null
        }
        val expiry = if (prefs.contains(expiryKey(accountId))) {
            prefs.getLong(expiryKey(accountId), 0L)
        } else {
            null
        }
        StoredToken(plaintext, expiry)
    }

    override suspend fun delete(accountId: String) = withContext(ioDispatcher) {
        prefs.edit()
            .remove(tokenKey(accountId))
            .remove(expiryKey(accountId))
            .commit()
        Unit
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        prefs.edit().clear().commit()
        Unit
    }

    /**
     * What the platform had to say when asked for the key. See [readableKey].
     *
     * Three states rather than a nullable key: `null` cannot express the difference
     * between "gone" and "not now", and [read] must not treat them the same.
     */
    private sealed interface KeyState {
        class Available(val key: SecretKey) : KeyState

        /** The key will never come back. Anything sealed under it is dead weight. */
        object PermanentlyGone : KeyState

        /** The platform is wedged. The key — and the token — are probably still there. */
        class TemporarilyUnavailable(val cause: Throwable) : KeyState
    }

    /**
     * The key, classified by whether it can ever come back.
     *
     * `KeyStore.getEntry` throws `UnrecoverableKeyException` for a key the system
     * invalidated (removing the secure lock screen does this) — that is **permanent**;
     * the key material is destroyed and every blob sealed under it is unopenable
     * forever. `KeyGenerator`/`KeyStore` throw `ProviderException` when the keymaster —
     * or StrongBox — is wedged, which on this fleet's own S25 has been observed for
     * real; that is **transient** and typically clears on the next boot. Both are
     * unchecked or checked-but-undeclared from the caller's point of view, and both
     * used to escape [read] and take the app down on the path
     * `read() → SheetSessionFactory → ViewModel combine`.
     *
     * 1.0.2 fixed the crash by mapping both to `null`, which [read] then treated as
     * "evict". That traded a crash for something worse and quieter: one wedged boot
     * deleting a perfectly good token. The classification exists so the eviction can be
     * spent only where it is actually warranted.
     */
    private fun readableKey(): KeyState = try {
        KeyState.Available(keyProvider.key())
    } catch (_: GeneralSecurityException) {
        KeyState.PermanentlyGone
    } catch (e: ProviderException) {
        KeyState.TemporarilyUnavailable(e)
    }

    private fun tokenKey(accountId: String) = "token/$accountId"

    private fun expiryKey(accountId: String) = "tokenExpires/$accountId"

    companion object {
        /** Excluded from backup/transfer by res/xml/data_extraction_rules.xml. */
        const val PREFS_FILE_NAME: String = "magehand_tokens_v2"

        /**
         * WP3's `EncryptedSharedPreferences` file. WP8 cannot read it — doing so
         * would mean keeping the deprecated `security-crypto` dependency it exists to
         * retire — so the file is deleted and the affected accounts are dropped,
         * which lands the user on the login screen with a working app rather than on
         * a character list that can never connect.
         *
         * Blast radius is one sign-in: the only installs that can hold this file are
         * the table's WP5–WP7 debug sideloads. `versionCode 2` is the first release
         * build that has ever existed, so no store install can be affected.
         */
        const val LEGACY_PREFS_FILE_NAME: String = "magehand_tokens"

        /** `true` when a WP3-era encrypted pref file is still on disk. */
        fun legacyPrefsFile(context: Context): File =
            File(File(context.applicationInfo.dataDir, "shared_prefs"), "$LEGACY_PREFS_FILE_NAME.xml")
    }
}
