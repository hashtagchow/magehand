package com.hashtagchow.magehand.core.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.hashtagchow.magehand.core.data.api.ApiException
import java.io.File
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [KeystoreTokenStore] against a real `SharedPreferences` file (Robolectric) with
 * an in-memory key standing in for the Keystore one — see
 * [AesGcmTokenCodecTest]'s header for why the key is swappable.
 *
 * The assertions worth having are the ones the deprecated
 * `EncryptedSharedPreferences` used to give for free: the file never contains the
 * token, multi-account keying works, delete is per-account, and an unreadable
 * entry degrades to "absent" instead of poisoning the account forever.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreTokenStoreTest {

    private class InMemoryKeyProvider(var key: SecretKey = generate()) : TokenKeyProvider {
        override fun key(): SecretKey = key
        override fun deleteKey() { key = generate() }

        companion object {
            fun generate(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keys = InMemoryKeyProvider()
    private val prefsFile = "magehand_tokens_test"

    private fun store(provider: TokenKeyProvider = keys) = KeystoreTokenStore(
        context = context,
        keyProvider = provider,
        // Robolectric's main looper is the test thread; keep everything on it so the
        // asserts see the writes.
        ioDispatcher = Dispatchers.Unconfined,
        prefsFileName = prefsFile,
    )

    private fun prefsFileOnDisk(): File =
        File(File(context.applicationInfo.dataDir, "shared_prefs"), "$prefsFile.xml")

    @Test
    fun `saves and reads a token with its expiry`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("resume-token-1", 1_700_000_000_000L))

        assertEquals(StoredToken("resume-token-1", 1_700_000_000_000L), store.read("acct-1"))
    }

    @Test
    fun `a null expiry round-trips as null, not as zero`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("t", null))
        assertNull(store.read("acct-1")!!.expiresAtEpochMillis)
    }

    @Test
    fun `saving again replaces the expiry rather than leaving the old one`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("t", 42L))
        store.save("acct-1", StoredToken("t2", null))

        assertEquals(StoredToken("t2", null), store.read("acct-1"))
    }

    @Test
    fun `the token never appears in the prefs file`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("resume-token-1", null))

        val onDisk = prefsFileOnDisk()
        assertTrue("expected $onDisk to exist", onDisk.exists())
        val text = onDisk.readText()
        assertFalse("plaintext token on disk:\n$text", text.contains("resume-token-1"))
        // …but the entry really is there, so the assertion above is not vacuous.
        assertTrue(text.contains("token/acct-1"))
    }

    @Test
    fun `accounts do not share a slot`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("alice", null))
        store.save("acct-2", StoredToken("bob", null))

        assertEquals("alice", store.read("acct-1")!!.token)
        assertEquals("bob", store.read("acct-2")!!.token)
    }

    @Test
    fun `delete removes one account and leaves the others`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("alice", 1L))
        store.save("acct-2", StoredToken("bob", 2L))

        store.delete("acct-1")

        assertNull(store.read("acct-1"))
        assertNotNull(store.read("acct-2"))
    }

    @Test
    fun `clear removes everything`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("alice", null))
        store.save("acct-2", StoredToken("bob", null))

        store.clear()

        assertNull(store.read("acct-1"))
        assertNull(store.read("acct-2"))
    }

    @Test
    fun `an unknown account reads as null`() = runTest {
        assertNull(store().read("nobody"))
    }

    @Test
    fun `a token sealed under a lost key reads as absent and is evicted`() = runTest {
        // The real-world shape of this: the user removed and re-added their secure
        // lock screen, or the app data was moved to another device. The Keystore key
        // is gone; the ciphertext is not.
        val store = store()
        store.save("acct-1", StoredToken("resume-token-1", 99L))
        keys.deleteKey() // new key, same prefs file

        assertNull(store.read("acct-1"))
        // Evicted, so it cannot fail forever — and the expiry went with it.
        assertFalse(prefsFileOnDisk().readText().contains("tokenExpires/acct-1"))
    }

    // -----------------------------------------------------------------------
    // The key provider itself failing
    //
    // Distinct from "the key changed": here the platform hands back no key at all.
    // `KeyStore.getEntry` throws `UnrecoverableKeyException` on an invalidated key and
    // `KeyGenerator` throws `ProviderException` when the keymaster or StrongBox is
    // wedged. Both used to escape the store and crash the app.
    // -----------------------------------------------------------------------

    private class ThrowingKeyProvider(private val thrown: () -> Throwable) : TokenKeyProvider {
        var deleted = false
        override fun key(): SecretKey = throw thrown()
        override fun deleteKey() { deleted = true }
    }

    @Test
    fun `an unrecoverable key reads as absent rather than throwing`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("resume-token-1", 99L))

        val broken = store(ThrowingKeyProvider { UnrecoverableKeyException("key invalidated") })
        assertNull(broken.read("acct-1"))
        // Same eviction as an undecryptable blob: never fail forever.
        assertFalse(prefsFileOnDisk().readText().contains("token/acct-1"))
    }

    /**
     * The asymmetry with the test above is the entire point, and it is now real
     * behaviour rather than an omission.
     *
     * `UnrecoverableKeyException` means the key material is destroyed: the blob is dead
     * and evicting it is the only way to stop it failing forever. `ProviderException`
     * means the keymaster is *wedged* — on this fleet's own S25, for one boot. Evicting
     * there would be strictly worse than the crash 1.0.2 had: a valid token silently
     * deleted, every account signed out, and nothing to restore from, because the pref
     * file is deliberately excluded from backup and device transfer.
     */
    @Test
    fun `a wedged keymaster reads as absent but must NOT evict the token`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("resume-token-1", 99L))

        val broken = store(ThrowingKeyProvider { ProviderException("keystore died") })
        assertNull(broken.read("acct-1"))

        val onDisk = prefsFileOnDisk().readText()
        assertTrue("the token blob was evicted by a transient failure:\n$onDisk", onDisk.contains("token/acct-1"))
        assertTrue("the expiry went with it:\n$onDisk", onDisk.contains("tokenExpires/acct-1"))
    }

    @Test
    fun `a token survives a wedged keymaster and reads back once it clears`() = runTest {
        // The self-healing claim, end to end: one bad boot must cost nothing.
        val store = store()
        store.save("acct-1", StoredToken("resume-token-1", 99L))

        assertNull(store(ThrowingKeyProvider { ProviderException("keystore died") }).read("acct-1"))

        assertEquals(StoredToken("resume-token-1", 99L), store.read("acct-1"))
    }

    // -----------------------------------------------------------------------
    // The *cipher* failing, which is a different moment from the key failing
    //
    // A Keystore key can hand out perfectly well and then refuse to operate: the SPI
    // throws ProviderException from Cipher.init/doFinal. A readableKey()-shaped guard
    // never sees this one, so 1.0.2 still crashed on it. See [WedgedKeystore].
    // -----------------------------------------------------------------------

    private class NonExportableKeyProvider : TokenKeyProvider {
        override fun key(): SecretKey = NonExportableAesKey
        override fun deleteKey() = Unit
    }

    @Test
    fun `a wedged cipher reads as absent, keeps the blob, and heals`() = runTest {
        val store = store()
        store.save("acct-1", StoredToken("resume-token-1", 99L))

        val wedged = store(NonExportableKeyProvider())
        val read = withWedgedKeystore { wedged.read("acct-1") }

        assertNull("a wedged cipher must not surface as a token", read)
        val onDisk = prefsFileOnDisk().readText()
        assertTrue("the token blob was evicted by a transient failure:\n$onDisk", onDisk.contains("token/acct-1"))
        // …and the blob really was still good all along.
        assertEquals(StoredToken("resume-token-1", 99L), store.read("acct-1"))
    }

    /**
     * The other direction. Reporting "absent" on save would leave an `accounts` row
     * with no token behind it; the login screen has a typed error channel for exactly
     * this, so the failure goes down it.
     */
    @Test
    fun `saving under a wedged keymaster fails with the typed login-screen error`() = runTest {
        val broken = store(ThrowingKeyProvider { ProviderException("keystore died") })

        val thrown = runCatching { broken.save("acct-1", StoredToken("t", null)) }.exceptionOrNull()
        assertTrue("expected SecureStorageUnavailable, got $thrown", thrown is ApiException.SecureStorageUnavailable)
    }

    @Test
    fun `saving with an unrecoverable key fails the same way`() = runTest {
        val broken = store(ThrowingKeyProvider { UnrecoverableKeyException("key invalidated") })

        val thrown = runCatching { broken.save("acct-1", StoredToken("t", null)) }.exceptionOrNull()
        assertTrue("expected SecureStorageUnavailable, got $thrown", thrown is ApiException.SecureStorageUnavailable)
    }

    @Test
    fun `a failed save leaves nothing half-written`() = runTest {
        val broken = store(ThrowingKeyProvider { ProviderException("keystore died") })
        runCatching { broken.save("acct-1", StoredToken("t", 5L)) }

        assertNull(store().read("acct-1"))
        assertFalse(prefsFileOnDisk().let { if (it.exists()) it.readText() else "" }.contains("acct-1"))
    }
}
