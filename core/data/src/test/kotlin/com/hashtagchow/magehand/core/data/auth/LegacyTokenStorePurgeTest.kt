package com.hashtagchow.magehand.core.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.hashtagchow.magehand.core.data.db.AccountEntity
import com.hashtagchow.magehand.core.data.fake.FakeAccountDao
import com.hashtagchow.magehand.core.data.fake.FakeActiveAccountStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The WP3 → WP8 token-store cut-over (docs/verification/WP8.md §3).
 *
 * The purge is destructive by design, so both directions matter: it must fire when
 * the legacy file is there, and it must be invisible when it is not — that second
 * case runs on every launch of every install forever.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyTokenStorePurgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dao = FakeAccountDao()
    private val active = FakeActiveAccountStore()

    private val purge = LegacyTokenStorePurge(context, dao, active)

    private fun account(id: String) = AccountEntity(
        id = id,
        serverUrl = "https://dicecloud.com",
        userId = "u-$id",
        username = id,
        addedAt = 1L,
        lastUsedAt = 1L,
    )

    private fun writeLegacyFile() {
        val file = KeystoreTokenStore.legacyPrefsFile(context)
        file.parentFile!!.mkdirs()
        file.writeText("<?xml version='1.0'?><map />")
    }

    @Test
    fun `does nothing when there is no legacy file`() = runTest {
        dao.upsert(account("acct-1"))
        active.setActiveAccountId("acct-1")

        assertFalse(purge.runIfNeeded())

        assertEquals(1, dao.getAll().size)
        assertEquals("acct-1", active.current())
    }

    @Test
    fun `drops every account and the selection when the legacy file is present`() = runTest {
        dao.upsert(account("acct-1"))
        dao.upsert(account("acct-2"))
        active.setActiveAccountId("acct-2")
        writeLegacyFile()

        assertTrue(purge.runIfNeeded())

        assertTrue(dao.getAll().isEmpty())
        assertNull(active.current())
    }

    @Test
    fun `deletes the legacy file so it runs exactly once`() = runTest {
        writeLegacyFile()

        assertTrue(purge.runIfNeeded())
        assertFalse(KeystoreTokenStore.legacyPrefsFile(context).exists())
        assertFalse("second launch must be a no-op", purge.runIfNeeded())
    }
}
