package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FR-6's `show_toggles` against a **real** DataStore on a real file, pinning FR-57's two rulings
 * (operator, 2026-11-15) where they actually live — in what an upgraded install reads off disk.
 *
 * R1: the default is **on**. R2: retroactive without a migration — an install that never touched
 * the switch has no entry and so reads the new default, while a stored `false` is honoured,
 * because the only thing that ever writes the key is the player's own tap on the Settings switch.
 * The raw writes below stand in for a file left behind by an earlier build: they bypass the
 * store's API, which is the only shape in which "what an earlier build wrote, this build reads" is asserted.
 */
class AppSettingsShowTogglesTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /** The key by its real name (09 decision 9), written out here so this is not a tautology. */
    private val storedKey = booleanPreferencesKey("show_toggles")

    /** See `AppSettingsUiScaleTest.withStore` — the `cancelAndJoin` is load-bearing. */
    private fun <T> withStore(file: File, block: suspend (AppSettingsStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                block(DataStoreAppSettingsStore(dataStore))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    /** Writes a raw value under the real key, as an earlier build's tap on the switch did. */
    private fun writeRaw(file: File, value: Boolean) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            PreferenceDataStoreFactory.create(scope = scope) { file }
                .edit { it[storedKey] = value }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun prefsFile(): File = File(temp.root, "test.preferences_pb")

    @Test
    fun `an unset key reads on, so an install that never touched the switch turns on at upgrade`() {
        // FR-57 R1 + R2. Before FR-57 this read `false`; with the default flipped, the absence of
        // an entry is what carries every untouched install over, with no migration to run.
        assertTrue(withStore(prefsFile()) { it.showToggles.first() })
    }

    @Test
    fun `a stored false is honoured, because only the player's own tap ever writes one`() {
        // FR-57 R2. A player who turned the section on and then off again chose that; it must
        // survive the upgrade. If the key ever gains a second writer (a seed, an import, a
        // restore), this stops being true and R2's one-time reset becomes necessary instead.
        val file = prefsFile()
        writeRaw(file, false)
        assertFalse(withStore(file) { it.showToggles.first() })
    }

    @Test
    fun `a stored true reads on`() {
        val file = prefsFile()
        writeRaw(file, true)
        assertTrue(withStore(file) { it.showToggles.first() })
    }

    @Test
    fun `a choice made through the store survives a restart, in both directions`() {
        listOf(true, false).forEach { value ->
            val file = File(temp.root, "$value.preferences_pb")
            withStore(file) { it.setShowToggles(value) }
            assertEquals(value, withStore(file) { it.showToggles.first() })
        }
    }
}
