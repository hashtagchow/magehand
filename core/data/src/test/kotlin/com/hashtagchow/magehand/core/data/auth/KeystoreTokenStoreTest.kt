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
import java.io.File
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
}
