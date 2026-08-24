package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * FR-17's per-character pane choice (docs/design/14-large-screen-arc.md decision 8), against a
 * **real** DataStore on a real file.
 *
 * ### Why not a fake
 *
 * `InventoryLayoutStoreTest`'s argument, unchanged and for the fourth time: the claim this store
 * makes is *"the panes the player chose are still there after a restart"*, and a fake map cannot
 * fail that claim. The round-trip tests below therefore close the store's scope and open a
 * **second** store over the same file, which is as close to a process restart as a JVM test gets.
 *
 * ### The one thing this store does that the other three do not
 *
 * Its codec **drops** a token it does not recognise, where `InventoryLayoutCodec` keeps one. That
 * is not an inconsistency, it is the difference between an opaque key and a closed vocabulary
 * (see `PaneLayoutCodec`), and it is the behaviour a future release will depend on: a build that
 * adds a fourth surface writes a `pane_layout` this build has to read without losing the three
 * surfaces it does understand. That is asserted here by writing the future string by hand,
 * because there is no other way to produce one.
 */
class PaneLayoutStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /**
     * Runs [block] against a store over [file], then **fully tears the store down**.
     *
     * The `cancelAndJoin` is not tidiness: DataStore refuses two live instances over one file, by
     * design, and the restart tests below are precisely two instances over one file.
     */
    private fun <T> withStore(file: File, block: suspend (PaneLayoutStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                block(DataStorePaneLayoutStore(dataStore))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    /** Writes a raw string under [characterKey], for the "a newer build wrote this" cases. */
    private fun writeRaw(file: File, characterKey: String, value: String) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(scope = scope) { file }
            dataStore.edit { it[stringPreferencesKey(characterKey)] = value }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun prefsFile(): File = File(temp.root, "test.preferences_pb")

    private val key = PaneLayoutStore.serverKey("acct-1", "creature-1")

    // ---- the default ---------------------------------------------------------

    @Test
    fun `a character nobody has arranged has no preference`() = withStore(prefsFile()) { store ->
        // The empty set is decision 8's "Default: Tracker only" — expressed as *absence*, not as
        // a stored Tracker. The UI resolves it; this store must not pre-empt that, or every
        // release that changed the default would leave old characters on the old one.
        assertEquals(emptySet<PaneSurface>(), store.panes(key).first())
    }

    // ---- persistence ---------------------------------------------------------

    @Test
    fun `a chosen set survives the store being torn down and reopened`() {
        val file = prefsFile()
        withStore(file) { store ->
            store.setPanes(key, setOf(PaneSurface.TRACKER, PaneSurface.SHEET))
        }

        // A second store over the same file: the only shape in which "persisted" is asserted
        // rather than assumed, and the only one that exercises the codec in both directions.
        val restored = withStore(file) { store -> store.panes(key).first() }

        assertEquals(setOf(PaneSurface.TRACKER, PaneSurface.SHEET), restored)
    }

    @Test
    fun `selection order is not stored, so two writers of one set agree`() {
        val file = prefsFile()
        val other = PaneLayoutStore.serverKey("acct-1", "creature-2")

        withStore(file) { store ->
            // Same set, opposite insertion order. Decision 6: "panes are places, not history" —
            // if selection order reached the file, these two would produce different bytes and a
            // later `encode(decode(s))` would not be stable.
            store.setPanes(key, linkedSetOf(PaneSurface.SHEET, PaneSurface.TRACKER))
            store.setPanes(other, linkedSetOf(PaneSurface.TRACKER, PaneSurface.SHEET))
        }

        val (a, b) = withStore(file) { store ->
            store.panes(key).first() to store.panes(other).first()
        }
        assertEquals(a, b)
        assertEquals(
            "the encoding is canonical, in PaneSurface ordinal order",
            "tracker,sheet",
            PaneLayoutCodec.encode(linkedSetOf(PaneSurface.SHEET, PaneSurface.TRACKER)),
        )
    }

    // ---- the tolerant codec (decision 8: "unknown keys dropped") -------------

    @Test
    fun `a surface a newer build invented is dropped and the rest survives`() {
        val file = prefsFile()
        // What a 1.8.0 that added a "notes" pane would leave behind for this build to read.
        writeRaw(file, key, "tracker,notes,sheet")

        val panes = withStore(file) { store -> store.panes(key).first() }

        // Not a crash, not an empty column, and — the part that matters — not the loss of the
        // two surfaces this build *can* draw.
        assertEquals(setOf(PaneSurface.TRACKER, PaneSurface.SHEET), panes)
    }

    @Test
    fun `a value made entirely of unknown tokens reads as no preference`() {
        val file = prefsFile()
        writeRaw(file, key, "notes,spells")

        // Degrades to the default rather than to a blank screen: the empty set is what an
        // untouched character reads as, so this character opens on Tracker like a new one.
        assertEquals(emptySet<PaneSurface>(), withStore(file) { it.panes(key).first() })
    }

    @Test
    fun `a malformed value degrades rather than crashing`() {
        val file = prefsFile()
        writeRaw(file, key, ",,tracker,,,tracker,")

        // Blank segments dropped, the duplicate collapsed by the set — a surface cannot be in two
        // columns, which is the property `PaneRow`'s "no keyed-state collisions" argument rests on.
        assertEquals(setOf(PaneSurface.TRACKER), withStore(file) { it.panes(key).first() })
    }

    // ---- empty removes the key ----------------------------------------------

    @Test
    fun `the empty set removes the key rather than writing an empty string`() {
        val file = prefsFile()
        withStore(file) { store ->
            store.setPanes(key, setOf(PaneSurface.INVENTORY))
            store.setPanes(key, emptySet())
        }

        // Both readings are "no preference", which is exactly why one of them must not be
        // written: a key holding nothing is a slot in a file nothing else prunes.
        val raw = runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val ds: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                ds.data.first()[stringPreferencesKey(key)]
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
        assertEquals(null, raw)
    }

    // ---- reset, and the two reaping paths -----------------------------------

    @Test
    fun `clearing one character is the reset, and leaves the others alone`() =
        withStore(prefsFile()) { store ->
            val sibling = PaneLayoutStore.serverKey("acct-1", "creature-2")
            store.setPanes(key, setOf(PaneSurface.TRACKER, PaneSurface.INVENTORY))
            store.setPanes(sibling, setOf(PaneSurface.SHEET))

            store.clearForCharacter(key)

            // Decision 8's "reset-to-default = delete": back to no preference, which the UI
            // resolves to Tracker only.
            assertEquals(emptySet<PaneSurface>(), store.panes(key).first())
            assertEquals(setOf(PaneSurface.SHEET), store.panes(sibling).first())
        }

    @Test
    fun `signing an account out reaps its characters and nothing else`() =
        withStore(prefsFile()) { store ->
            val doomed = PaneLayoutStore.serverKey("acct-1", "creature-1")
            val alsoDoomed = PaneLayoutStore.serverKey("acct-1", "creature-2")
            val sibling = PaneLayoutStore.serverKey("acct-2", "creature-3")
            val onDevice = PaneLayoutStore.localKey("local-1")
            val chosen = setOf(PaneSurface.TRACKER, PaneSurface.INVENTORY)
            listOf(doomed, alsoDoomed, sibling, onDevice).forEach { store.setPanes(it, chosen) }

            store.deleteForAccount("acct-1")

            assertTrue(store.panes(doomed).first().isEmpty())
            assertTrue(store.panes(alsoDoomed).first().isEmpty())
            assertEquals(chosen, store.panes(sibling).first())
            // 09 decision 10: sign-out must not touch local data. The namespace is what
            // guarantees it, and this is what would notice if the prefix were changed.
            assertEquals(chosen, store.panes(onDevice).first())
        }

    @Test
    fun `an account id that prefixes another is not reaped with it`() =
        withStore(prefsFile()) { store ->
            // "acct" is a prefix of "acct-2" as a *string*; the trailing colon in the key shape
            // is what stops the sweep following it. Without it, signing out of one account would
            // silently reset another account's characters.
            val short = PaneLayoutStore.serverKey("acct", "creature-1")
            val longer = PaneLayoutStore.serverKey("acct-2", "creature-1")
            store.setPanes(short, setOf(PaneSurface.SHEET))
            store.setPanes(longer, setOf(PaneSurface.INVENTORY))

            store.deleteForAccount("acct")

            assertTrue(store.panes(short).first().isEmpty())
            assertEquals(setOf(PaneSurface.INVENTORY), store.panes(longer).first())
        }

    // ---- the shared file -----------------------------------------------------

    @Test
    fun `the four stores share a file without colliding`() = withStore(prefsFile()) { store ->
        // `DataModule` hands all four stores the same `.preferences_pb`, so "the namespaces do
        // not overlap" is a claim about production wiring rather than about this class alone.
        val prefixes = setOf(
            PaneLayoutStore.KEY_PREFIX,
            InventoryLayoutStore.KEY_PREFIX,
            SelectedRollStore.KEY_PREFIX,
            EquippableOverrideStore.KEY_PREFIX,
        )
        assertEquals("four stores, four distinct namespaces", 4, prefixes.size)
        val ours = PaneLayoutStore.serverKey("a", "b")
        assertFalse(
            ours.startsWith(InventoryLayoutStore.KEY_PREFIX) ||
                ours.startsWith(SelectedRollStore.KEY_PREFIX) ||
                ours.startsWith(EquippableOverrideStore.KEY_PREFIX),
        )
        // …and the store still works when handed a key shaped like another's, because nothing
        // here parses a key: it is an opaque string all the way down.
        store.setPanes(SelectedRollStore.serverKey("a", "b"), setOf(PaneSurface.SHEET))
        assertEquals(
            setOf(PaneSurface.SHEET),
            store.panes(SelectedRollStore.serverKey("a", "b")).first(),
        )
    }
}
