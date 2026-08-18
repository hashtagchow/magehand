package com.hashtagchow.magehand.core.data.auth

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The envelope that protects a resume token at rest (WP8 §3 — the replacement for
 * the deprecated `EncryptedSharedPreferences`).
 *
 * Runs under Robolectric only because `android.util.Base64` is an Android class;
 * everything under test is plain JCE. The key here is an ordinary in-memory
 * AES-256 key rather than a Keystore one, which is the whole reason
 * [TokenKeyProvider] is an interface: the Keystore key is non-exportable and
 * cannot be constructed off-device, so the framing, the tag check and the
 * failure modes get tested here and the Keystore itself gets tested on the
 * emulator (docs/verification/WP8.md §6).
 */
@RunWith(RobolectricTestRunner::class)
class AesGcmTokenCodecTest {

    private fun freshKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = freshKey()

    private val token = "FakeResumeToken0FakeResumeToken0-example-length-resume-token"

    @Test
    fun `round-trips a token`() {
        assertEquals(token, AesGcmTokenCodec.decrypt(key, AesGcmTokenCodec.encrypt(key, token)))
    }

    @Test
    fun `round-trips an empty string and unicode`() {
        for (plaintext in listOf("", "ﬁ✨ Sabriel — ünïcødé", "a".repeat(4096))) {
            val sealed = AesGcmTokenCodec.encrypt(key, plaintext)
            assertEquals(plaintext, AesGcmTokenCodec.decrypt(key, sealed))
        }
    }

    @Test
    fun `the ciphertext never contains the plaintext`() {
        val sealed = AesGcmTokenCodec.encrypt(key, token)
        assertFalse(sealed.contains(token))
        assertFalse(String(Base64.decode(sealed, Base64.NO_WRAP), Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun `encrypting the same token twice never repeats the IV`() {
        // GCM is catastrophically broken by IV reuse, so this is the single most
        // important property in the file.
        val ivs = (1..64).map {
            val blob = Base64.decode(AesGcmTokenCodec.encrypt(key, token), Base64.NO_WRAP)
            blob.copyOfRange(1, 1 + AesGcmTokenCodec.IV_LENGTH).toList()
        }
        assertEquals(64, ivs.toSet().size)
    }

    @Test
    fun `two encryptions of the same token differ`() {
        assertNotEquals(AesGcmTokenCodec.encrypt(key, token), AesGcmTokenCodec.encrypt(key, token))
    }

    @Test
    fun `the envelope is version byte, IV, then body`() {
        val blob = Base64.decode(AesGcmTokenCodec.encrypt(key, token), Base64.NO_WRAP)
        assertEquals(AesGcmTokenCodec.VERSION, blob[0])
        // 1 version byte + 12 IV + ciphertext (== plaintext length for GCM) + 16 tag.
        assertEquals(1 + 12 + token.toByteArray().size + 16, blob.size)
    }

    @Test
    fun `a different key cannot read it`() {
        assertNull(AesGcmTokenCodec.decrypt(freshKey(), AesGcmTokenCodec.encrypt(key, token)))
    }

    @Test
    fun `a flipped ciphertext bit is rejected by the tag, not returned mangled`() {
        val blob = Base64.decode(AesGcmTokenCodec.encrypt(key, token), Base64.NO_WRAP)
        blob[blob.size - 20] = (blob[blob.size - 20].toInt() xor 0x01).toByte()
        assertNull(AesGcmTokenCodec.decrypt(key, Base64.encodeToString(blob, Base64.NO_WRAP)))
    }

    @Test
    fun `a flipped IV bit is rejected too`() {
        val blob = Base64.decode(AesGcmTokenCodec.encrypt(key, token), Base64.NO_WRAP)
        blob[3] = (blob[3].toInt() xor 0x01).toByte()
        assertNull(AesGcmTokenCodec.decrypt(key, Base64.encodeToString(blob, Base64.NO_WRAP)))
    }

    @Test
    fun `an unknown version byte is rejected rather than mis-parsed`() {
        val blob = Base64.decode(AesGcmTokenCodec.encrypt(key, token), Base64.NO_WRAP)
        blob[0] = 99
        assertNull(AesGcmTokenCodec.decrypt(key, Base64.encodeToString(blob, Base64.NO_WRAP)))
    }

    @Test
    fun `garbage returns null instead of throwing`() {
        for (junk in listOf("", "not base64 at all !!!", "AAAA", Base64.encodeToString(ByteArray(5), Base64.NO_WRAP))) {
            assertNull("input: '$junk'", AesGcmTokenCodec.decrypt(key, junk))
        }
    }

    // -----------------------------------------------------------------------
    // Transient vs permanent — the distinction `decrypt`'s String? cannot carry
    //
    // A wedged keymaster throws ProviderException, an unchecked RuntimeException, out
    // of the *cipher operation* rather than out of the key handout. Nothing in
    // Cipher's signature hints at it, `catch (GeneralSecurityException)` does not cover
    // it, and it is the half of the 1.0.2 crash that a key-provider guard cannot see.
    // See [WedgedKeystore] for why the double is faithful.
    // -----------------------------------------------------------------------

    @Test
    fun `a wedged cipher is Unavailable, not Unreadable`() {
        val sealed = AesGcmTokenCodec.encrypt(key, token)

        val result = withWedgedKeystore { AesGcmTokenCodec.unseal(NonExportableAesKey, sealed) }

        assertTrue(
            "a transient platform failure must not be reported as a dead blob, got $result",
            result is AesGcmTokenCodec.Unsealed.Unavailable,
        )
    }

    @Test
    fun `a wedged cipher does not escape as an exception`() {
        val sealed = AesGcmTokenCodec.encrypt(key, token)
        // The regression itself: 1.0.2 let ProviderException out of here, through
        // KeystoreTokenStore.read(), and into the ViewModel that took the app down.
        val thrown = runCatching { withWedgedKeystore { AesGcmTokenCodec.decrypt(NonExportableAesKey, sealed) } }
        assertNull("decrypt threw ${thrown.exceptionOrNull()}", thrown.exceptionOrNull())
        assertNull(thrown.getOrNull())
    }

    @Test
    fun `the permanent failures stay Unreadable`() {
        // The other direction of the same fix: none of these may claim to be transient,
        // or the store would keep a genuinely dead blob forever.
        val sealed = AesGcmTokenCodec.encrypt(key, token)
        val tampered = Base64.decode(sealed, Base64.NO_WRAP).also {
            it[it.size - 20] = (it[it.size - 20].toInt() xor 0x01).toByte()
        }

        val permanent = listOf(
            "a key that no longer matches" to AesGcmTokenCodec.unseal(freshKey(), sealed),
            "a failed tag check" to AesGcmTokenCodec.unseal(key, Base64.encodeToString(tampered, Base64.NO_WRAP)),
            "not base64" to AesGcmTokenCodec.unseal(key, "not base64 at all !!!"),
            "a truncated blob" to AesGcmTokenCodec.unseal(key, Base64.encodeToString(ByteArray(5), Base64.NO_WRAP)),
        )
        permanent.forEach { (what, result) ->
            assertTrue("$what should be Unreadable, got $result", result is AesGcmTokenCodec.Unsealed.Unreadable)
        }
    }
}
