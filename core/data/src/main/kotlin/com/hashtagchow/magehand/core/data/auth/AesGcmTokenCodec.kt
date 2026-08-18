package com.hashtagchow.magehand.core.data.auth

import android.util.Base64
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The AES-256-GCM envelope that protects a resume token at rest.
 *
 * Deliberately separated from *where the key comes from* ([TokenKeyProvider]) so
 * that the part with the sharp edges — IV handling, tag length, framing, tamper
 * behaviour — is plain JVM code that a `./gradlew test` run actually exercises,
 * while the Android-only part is a dozen lines of `KeyGenParameterSpec`.
 *
 * Wire format of [encrypt], before Base64:
 *
 * ```
 *   +---------+------------------+-------------------------------+
 *   | version | IV (12 bytes)    | ciphertext ‖ GCM tag (16 B)   |
 *   |  1 byte |                  |                               |
 *   +---------+------------------+-------------------------------+
 * ```
 *
 * The version byte exists so a future scheme change can be detected rather than
 * mis-parsed: [decrypt] returns `null` for anything it does not recognise, which
 * the store treats as "no token" (the user signs in again) rather than crashing.
 */
internal object AesGcmTokenCodec {

    /** Current envelope version. Bump only together with a new [decrypt] branch. */
    const val VERSION: Byte = 1

    /**
     * GCM's recommended nonce length. We never choose the IV ourselves: the
     * Keystore requires randomized encryption, so [Cipher.getIV] is the source of
     * truth and this constant only validates what comes back.
     */
    const val IV_LENGTH: Int = 12

    /** 128-bit authentication tag — the maximum GCM offers. */
    const val TAG_LENGTH_BITS: Int = 128

    const val TRANSFORMATION: String = "AES/GCM/NoPadding"

    /**
     * @return Base64 (no wrap, no padding-free tricks) of `version ‖ iv ‖ ct‖tag`.
     * @throws GeneralSecurityException if the platform refuses the operation — a
     *   caller-visible failure, because silently storing a plaintext token would be
     *   exactly the bug this class exists to prevent.
     */
    fun encrypt(key: SecretKey, plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "unexpected IV length ${iv.size}" }
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val out = ByteArray(1 + iv.size + body.size)
        out[0] = VERSION
        iv.copyInto(out, 1)
        body.copyInto(out, 1 + iv.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * The outcome of [unseal]. **Three** states, not two, because "this blob is dead"
     * and "this blob cannot be opened *right now*" have opposite consequences: the
     * first justifies deleting it, the second makes deleting it data loss. Collapsing
     * them is what turned a one-boot StrongBox wedge into a permanent sign-out.
     */
    internal sealed interface Unsealed {

        /** The token, recovered intact. */
        class Plaintext(val value: String) : Unsealed

        /**
         * Permanently unreadable, and no retry will change that: wrong version byte,
         * truncated blob, non-Base64, a failed GCM tag check, or a key that no longer
         * matches the one the blob was sealed under (removing the secure lock screen
         * wipes auth-bound keys; a restored-from-backup app has a different key).
         */
        object Unreadable : Unsealed

        /**
         * The platform refused to *perform* the operation. The blob is very probably
         * fine — we never got far enough to find out.
         *
         * AndroidKeyStore's cipher SPI throws [ProviderException] (an unchecked
         * `RuntimeException`, so nothing in the signature hints at it) straight out of
         * `Cipher.init`/`doFinal` when the keymaster or StrongBox is wedged. That is a
         * *different* moment from the key handout failing, and it is the one that
         * survived the 1.0.2 fix: a Keystore key hands out fine and then will not
         * operate, so a `readableKey()`-shaped guard never sees it.
         */
        class Unavailable(val cause: Throwable) : Unsealed
    }

    /**
     * The inverse of [encrypt] — never throws, for any input or platform state.
     *
     * See [Unsealed] for why the failure half is two states rather than one.
     */
    internal fun unseal(key: SecretKey, encoded: String): Unsealed {
        val blob = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return Unsealed.Unreadable
        }
        if (blob.size <= 1 + IV_LENGTH) return Unsealed.Unreadable
        if (blob[0] != VERSION) return Unsealed.Unreadable

        val iv = blob.copyOfRange(1, 1 + IV_LENGTH)
        val body = blob.copyOfRange(1 + IV_LENGTH, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            Unsealed.Plaintext(String(cipher.doFinal(body), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            Unsealed.Unreadable
        } catch (_: IllegalStateException) {
            Unsealed.Unreadable
        } catch (e: ProviderException) {
            // Deliberately *not* folded in with the two above: this is the transient one.
            Unsealed.Unavailable(e)
        }
    }

    /**
     * [unseal] flattened to "the token, or nothing".
     *
     * **Not for the store's read path.** This collapses [Unsealed.Unavailable] into
     * `null`, which is precisely the conflation [Unsealed] exists to prevent — a caller
     * that then evicts on `null` deletes a good token because the keymaster hiccuped.
     * It survives because the framing, tamper and round-trip assertions in
     * `AesGcmTokenCodecTest` genuinely do not care why a blob failed to open.
     */
    fun decrypt(key: SecretKey, encoded: String): String? =
        (unseal(key, encoded) as? Unsealed.Plaintext)?.value
}
