package com.hashtagchow.magehand.core.data.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
 * A token that cannot be decrypted — key invalidated, file restored onto a
 * different device, blob truncated — reads as **absent** and is evicted, so the
 * user is sent back to the login screen instead of into an unexplained
 * `AUTH_FAILED`. Keystore work is real work (tens of ms cold), so the prefs handle
 * is lazy and every access hops to [ioDispatcher].
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
        val sealed = AesGcmTokenCodec.encrypt(keyProvider.key(), token.token)
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

    override suspend fun read(accountId: String): StoredToken? = withContext(ioDispatcher) {
        val sealed = prefs.getString(tokenKey(accountId), null) ?: return@withContext null
        val plaintext = AesGcmTokenCodec.decrypt(keyProvider.key(), sealed)
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
