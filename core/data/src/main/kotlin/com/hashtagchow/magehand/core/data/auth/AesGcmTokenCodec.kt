package com.hashtagchow.magehand.core.data.auth

import android.util.Base64
import java.security.GeneralSecurityException
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
     * The inverse of [encrypt].
     *
     * Returns `null` — never throws — for every "this is not our ciphertext, or not
     * ours any more" case: wrong version, truncated blob, non-Base64, a failed GCM
     * tag check, or a Keystore key that was invalidated (secure-lock-screen removal
     * wipes auth-bound keys, and a restored-from-backup app has no key at all).
     * The store's contract is that a token either reads back intact or is absent.
     */
    fun decrypt(key: SecretKey, encoded: String): String? {
        val blob = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (blob.size <= 1 + IV_LENGTH) return null
        if (blob[0] != VERSION) return null

        val iv = blob.copyOfRange(1, 1 + IV_LENGTH)
        val body = blob.copyOfRange(1 + IV_LENGTH, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }
}
