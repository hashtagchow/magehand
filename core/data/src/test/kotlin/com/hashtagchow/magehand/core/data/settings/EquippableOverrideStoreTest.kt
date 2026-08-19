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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * FR-10's per-item equippability overrides (11 decision 2), against a **real** DataStore on a
 * real file.
 *
 * ### Why not a fake
 *
 * `SelectedRollStoreTest`'s argument, unchanged and for the same feature shape: the claim this
 * store makes is *"the player's correction survives an app restart"*, and a fake map cannot fail
 * that claim — it would pass whether or not anything was ever written to disk. So the round-trip
 * test below closes the store's scope and opens a **second** store over the same file, which is
 * as close to a process restart as a JVM test gets and is the only shape in which "persisted" is
 * actually asserted.
 *
 * Preferences-DataStore is plain JVM code (the Android-specific part is only
 * `Context.preferencesDataStoreFile`), so no Robolectric is needed here.
 */
class EquippableOverrideStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /**
     * Runs [block] against a store over [file], then **fully tears the store down**.
     *
     * The `cancelAndJoin` is not tidiness: DataStore refuses two live instances over one file,
     * by design, and the restart test below is precisely two instances over one file. Joining
     * the cancellation is what makes the first one provably gone before the second opens.
     */
    private fun <T> withStore(file: File, block: suspend (EquippableOverrideStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                block(DataStoreEquippableOverrideStore(dataStore))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    private fun prefsFile(): File = File(temp.root, "test.preferences_pb")

    @Test
    fun `an override survives a restart of the store`() {
        val file = prefsFile()
        val key = EquippableOverrideStore.serverKey("acct-1", "creature-1")

        withStore(file) { it.setOverridden(key, "prop-knife", overridden = true) }

        // A *second* instance over the same file — the closest a unit test gets to the app
        // being killed and reopened. A fake would pass this without touching a disk.
        assertEquals(setOf("prop-knife"), withStore(file) { it.overrides(key).first() })
    }

    @Test
    fun `several items can be overridden for one character`() = withStore(prefsFile()) { store ->
        val key = EquippableOverrideStore.serverKey("acct-1", "creature-1")

        store.setOverridden(key, "prop-knife", true)
        store.setOverridden(key, "prop-quill", true)

        assertEquals(setOf("prop-knife", "prop-quill"), store.overrides(key).first())
    }

    @Test
    fun `turning an override off removes only that item`() = withStore(prefsFile()) { store ->
        val key = EquippableOverrideStore.serverKey("acct-1", "creature-1")

        store.setOverridden(key, "prop-knife", true)
        store.setOverridden(key, "prop-quill", true)
        store.setOverridden(key, "prop-knife", false)

        assertEquals(setOf("prop-quill"), store.overrides(key).first())
    }

    @Test
    fun `nothing overridden reads as an empty set rather than as null`() = withStore(prefsFile()) { store ->
        // Empty rather than nullable is a contract, not a convenience: the UI unions this with
        // the engine's own answer, and a nullable set would put `?: emptySet()` at every reader.
        assertTrue(
            store.overrides(EquippableOverrideStore.localKey("never-touched")).first().isEmpty(),
        )
    }

    @Test
    fun `each character keeps its own overrides`() = withStore(prefsFile()) { store ->
        val alice = EquippableOverrideStore.serverKey("acct-1", "creature-1")
        val bob = EquippableOverrideStore.serverKey("acct-1", "creature-2")

        store.setOverridden(alice, "prop-a", true)
        store.setOverridden(bob, "prop-b", true)

        assertEquals(setOf("prop-a"), store.overrides(alice).first())
        assertEquals(setOf("prop-b"), store.overrides(bob).first())
    }

    @Test
    fun `the same creature under two accounts is two sets`() = withStore(prefsFile()) { store ->
        // Matching `tracker_prefs`, `theme_prefs` and the roll selection, which are all
        // account-keyed for the same reason: two accounts that can both reach one creature are
        // two rows everywhere else in this app.
        val first = EquippableOverrideStore.serverKey("acct-1", "shared-creature")
        val second = EquippableOverrideStore.serverKey("acct-2", "shared-creature")

        store.setOverridden(first, "prop-a", true)

        assertTrue(store.overrides(second).first().isEmpty())
    }

    @Test
    fun `clearing a character drops its whole set and nobody else's`() = withStore(prefsFile()) { store ->
        val doomed = EquippableOverrideStore.localKey("local-1")
        val survivor = EquippableOverrideStore.localKey("local-2")

        store.setOverridden(doomed, "prop-a", true)
        store.setOverridden(doomed, "prop-b", true)
        store.setOverridden(survivor, "prop-c", true)

        store.clearForCharacter(doomed)

        assertTrue(store.overrides(doomed).first().isEmpty())
        assertEquals(setOf("prop-c"), store.overrides(survivor).first())
    }

    @Test
    fun `signing out reaps that account's overrides and nothing else`() = withStore(prefsFile()) { store ->
        val doomed = EquippableOverrideStore.serverKey("acct-1", "creature-1")
        val alsoDoomed = EquippableOverrideStore.serverKey("acct-1", "creature-2")
        val sibling = EquippableOverrideStore.serverKey("acct-2", "creature-3")
        val onDevice = EquippableOverrideStore.localKey("local-1")

        store.setOverridden(doomed, "prop-a", true)
        store.setOverridden(alsoDoomed, "prop-b", true)
        store.setOverridden(sibling, "prop-c", true)
        store.setOverridden(onDevice, "prop-d", true)

        store.deleteForAccount("acct-1")

        assertTrue(store.overrides(doomed).first().isEmpty())
        assertTrue(store.overrides(alsoDoomed).first().isEmpty())
        assertEquals("a sibling account must be untouched", setOf("prop-c"), store.overrides(sibling).first())
        // 09 decision 10: sign-out cannot reach local characters. The key namespace is what
        // guarantees that here, rather than a comment asking the prefix match to behave.
        assertEquals(
            "sign-out must not touch a local character",
            setOf("prop-d"),
            store.overrides(onDevice).first(),
        )
    }

    @Test
    fun `an account id that is a prefix of another does not reap its neighbour`() = withStore(prefsFile()) { store ->
        // The separator in the key is what makes this true; without it, "acct" would eat
        // "acct-2". Account ids are UUIDs in production, but the key format must not rely on
        // that to be correct.
        val short = EquippableOverrideStore.serverKey("acct", "creature-1")
        val longer = EquippableOverrideStore.serverKey("acct-2", "creature-1")

        store.setOverridden(short, "prop-a", true)
        store.setOverridden(longer, "prop-b", true)

        store.deleteForAccount("acct")

        assertTrue(store.overrides(short).first().isEmpty())
        assertEquals(setOf("prop-b"), store.overrides(longer).first())
    }

    @Test
    fun `the two stores share a file without colliding`() = withStore(prefsFile()) { file ->
        // `DataModule` hands both stores the same `.preferences_pb`, so "the namespaces do not
        // overlap" is a claim about production wiring rather than about this class alone. The
        // two prefixes are what make it true; this is what would notice if one were changed to
        // match the other.
        assertTrue(
            EquippableOverrideStore.KEY_PREFIX != SelectedRollStore.KEY_PREFIX,
        )
        assertTrue(
            !EquippableOverrideStore.serverKey("a", "b")
                .startsWith(SelectedRollStore.KEY_PREFIX),
        )
        // …and the store still works when handed a key shaped like the other one's, because
        // nothing here parses a key: it is an opaque string all the way down.
        file.setOverridden(SelectedRollStore.serverKey("a", "b"), "prop-a", true)
        assertEquals(setOf("prop-a"), file.overrides(SelectedRollStore.serverKey("a", "b")).first())
    }
}
