package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FR-7's remembered dropdown selection, against a **real** DataStore on a real file.
 *
 * ### Why not a fake
 *
 * The claim this feature makes is "the selection survives an app restart", and a fake map
 * cannot fail that claim — it would pass whether or not anything was ever written to disk.
 * So the round-trip test below closes the store's scope and opens a *second* store over the
 * same file, which is as close to a process restart as a JVM test gets and is the only shape
 * in which "persisted" is actually asserted.
 *
 * Preferences-DataStore is plain JVM code (the Android-specific part is only
 * `Context.preferencesDataStoreFile`), so no Robolectric is needed here.
 */
class SelectedRollStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /**
     * Runs [block] against a store over [file], then **fully tears the store down**.
     *
     * The `cancelAndJoin` is not tidiness: DataStore refuses two live instances over one file,
     * by design, and the restart test below is precisely two instances over one file. Joining
     * the cancellation is what makes the first one provably gone before the second opens —
     * which is also the only reason that test proves anything about a disk.
     */
    private fun <T> withStore(file: File, block: suspend (SelectedRollStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                block(DataStoreSelectedRollStore(dataStore))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    private fun prefsFile(): File = File(temp.root, "test.preferences_pb")

    @Test
    fun `a selection survives a restart of the store`() {
        val file = prefsFile()
        val key = SelectedRollStore.serverKey("acct-1", "creature-1")

        withStore(file) { it.setSelectedRollId(key, "roll-42") }

        // A *second* instance over the same file — the closest a unit test gets to the app
        // being killed and reopened. A fake would pass this without touching a disk.
        assertEquals("roll-42", withStore(file) { it.selectedRollId(key).first() })
    }

    @Test
    fun `each character remembers its own roll`() = withStore(prefsFile()) { store ->
        val alice = SelectedRollStore.serverKey("acct-1", "creature-1")
        val bob = SelectedRollStore.serverKey("acct-1", "creature-2")

        store.setSelectedRollId(alice, "roll-a")
        store.setSelectedRollId(bob, "roll-b")

        assertEquals("roll-a", store.selectedRollId(alice).first())
        assertEquals("roll-b", store.selectedRollId(bob).first())
    }

    @Test
    fun `the same creature under two accounts is two selections`() = withStore(prefsFile()) { store ->
        // Matching `tracker_prefs` and `theme_prefs`, which are account-keyed for the same
        // reason: two accounts that can both reach one creature are two rows everywhere else.
        val first = SelectedRollStore.serverKey("acct-1", "shared-creature")
        val second = SelectedRollStore.serverKey("acct-2", "shared-creature")

        store.setSelectedRollId(first, "roll-a")

        assertNull(store.selectedRollId(second).first())
    }

    @Test
    fun `nothing picked reads as null rather than as an empty string`() = withStore(prefsFile()) { store ->
        assertNull(store.selectedRollId(SelectedRollStore.localKey("never-touched")).first())
    }

    @Test
    fun `null clears the selection`() = withStore(prefsFile()) { store ->
        val key = SelectedRollStore.localKey("local-1")

        store.setSelectedRollId(key, "roll-1")
        store.setSelectedRollId(key, null)

        assertNull(store.selectedRollId(key).first())
    }

    @Test
    fun `signing out reaps that account's selections and nothing else`() = withStore(prefsFile()) { store ->
        val doomed = SelectedRollStore.serverKey("acct-1", "creature-1")
        val alsoDoomed = SelectedRollStore.serverKey("acct-1", "creature-2")
        val sibling = SelectedRollStore.serverKey("acct-2", "creature-3")
        val onDevice = SelectedRollStore.localKey("local-1")

        store.setSelectedRollId(doomed, "roll-1")
        store.setSelectedRollId(alsoDoomed, "roll-2")
        store.setSelectedRollId(sibling, "roll-3")
        store.setSelectedRollId(onDevice, "roll-4")

        store.deleteForAccount("acct-1")

        assertNull(store.selectedRollId(doomed).first())
        assertNull(store.selectedRollId(alsoDoomed).first())
        assertEquals("a sibling account must be untouched", "roll-3", store.selectedRollId(sibling).first())
        // 09 decision 10: sign-out cannot reach local characters. The key namespace is what
        // guarantees that here, rather than a comment asking the prefix match to behave.
        assertEquals("sign-out must not touch a local character", "roll-4", store.selectedRollId(onDevice).first())
    }

    @Test
    fun `an account id that is a prefix of another does not reap its neighbour`() = withStore(prefsFile()) { store ->
        // The separator in the key is what makes this true; without it, "acct" would eat
        // "acct-2". Account ids are UUIDs in production, but the key format must not rely
        // on that to be correct.
        val short = SelectedRollStore.serverKey("acct", "creature-1")
        val longer = SelectedRollStore.serverKey("acct-2", "creature-1")

        store.setSelectedRollId(short, "roll-1")
        store.setSelectedRollId(longer, "roll-2")

        store.deleteForAccount("acct")

        assertNull(store.selectedRollId(short).first())
        assertEquals("roll-2", store.selectedRollId(longer).first())
    }
}
