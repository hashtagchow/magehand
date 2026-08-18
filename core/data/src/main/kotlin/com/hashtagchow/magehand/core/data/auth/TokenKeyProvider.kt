package com.hashtagchow.magehand.core.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Supplies the AES key [AesGcmTokenCodec] encrypts with.
 *
 * An interface for one reason: the production key lives in hardware-backed
 * Keystore and cannot be exported, so a test cannot construct or inspect it. Every
 * behaviour that is *ours* (envelope framing, tamper handling, multi-account
 * keying, delete/clear semantics) is therefore tested against an in-memory
 * provider, and only the ten lines of [AndroidKeystoreTokenKeyProvider] need a
 * device — which the WP8 emulator probe supplies.
 */
interface TokenKeyProvider {
    /** The key for this install, creating it on first use. */
    fun key(): SecretKey

    /** Forget the key entirely. Every stored ciphertext becomes unreadable. */
    fun deleteKey()
}

/**
 * The production provider: a non-exportable AES-256 key in the **Android
 * Keystore**, hardware-backed on every device this app supports (minSdk 31).
 *
 * This replaces `androidx.security:security-crypto`'s `MasterKey` +
 * `EncryptedSharedPreferences`, which Jetpack deprecated in 1.1.0 without shipping
 * a replacement (docs/verification/WP3.md deviation 8, resolved in WP8). The
 * security properties that matter are unchanged or better:
 *
 * | | security-crypto 1.1.0 | this |
 * |---|---|---|
 * | key location | Android Keystore | Android Keystore |
 * | key exportable | no | no |
 * | value cipher | AES-256-GCM | AES-256-GCM |
 * | IV | per-value, random | per-value, random (Keystore-generated) |
 * | pref *key* names | AES-256-SIV encrypted | plaintext — see below |
 *
 * The one difference is the pref key names. They are `token/<Account.id>` where
 * `Account.id` is a locally generated `UUID.randomUUID()` — it is not a username,
 * an email, a server, or anything an attacker with the file could correlate. The
 * expiry timestamp is likewise stored as a plain `Long`: it is not a secret, and
 * encrypting it would only hide how many accounts exist, which the Room `accounts`
 * table states in the clear anyway.
 *
 * [setRandomizedEncryptionRequired] is left at its default (`true`), which is why
 * [AesGcmTokenCodec.encrypt] never supplies an IV — the Keystore refuses a
 * caller-chosen one, and that refusal is the guarantee that no IV is ever reused
 * under this key.
 */
class AndroidKeystoreTokenKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) : TokenKeyProvider {

    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // No user-authentication requirement: the DDP client reconnects and
                // re-logs-in from a background coroutine while the screen is off, so a
                // key that needed the lock screen would break the app's core loop.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    override fun deleteKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256

        /**
         * Not a shared/default alias: a dedicated one means a future encrypted store
         * can be rotated independently of tokens. (Same reasoning as the WP3 store's
         * `magehand_token_master_key`, and deliberately a *different* string so the
         * two schemes can never share a key.)
         */
        const val DEFAULT_ALIAS: String = "magehand_token_key_v1"
    }
}
