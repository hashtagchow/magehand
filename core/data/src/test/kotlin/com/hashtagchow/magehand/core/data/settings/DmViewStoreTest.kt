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
 * FR-19's per-account DM-view membership (docs/design/14-large-screen-arc.md decisions 11 and
 * 16), against a **real** DataStore on a real file.
 *
 * ### Why not a fake
 *
 * `PaneLayoutStoreTest`'s argument, unchanged and for the fifth time: the claim this store makes
 * is *"the table the DM assembled is still there after a restart"*, and a fake map cannot fail
 * that claim. The round-trip tests below close the store's scope and open a **second** store over
 * the same file, which is as close to a process restart as a JVM test gets.
 *
 * ### The two things this store does that the other four do not
 *
 * 1. Its codec **keeps** a token it does not recognise, where `PaneLayoutCodec` drops one — see
 *    `DmViewCodec`. Creature ids are opaque, and the dropping decision 16 asks for happens
 *    against the live character list, one layer up. What is asserted here is that the store does
 *    not pre-empt that, because a membership silently erased from disk by a character being
 *    briefly unresolvable is not recoverable.
 * 2. Its reap is an **exact-key delete**, because the key *is* the account. That makes the
 *    prefix-collision case (`acct-1` inside `acct-10`) a real defect rather than a hypothetical
 *    one, and it is the reason the sweep the other four use would be wrong here.
 */
class DmViewStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /**
     * Runs [block] against a store over [file], then **fully tears the store down**.
     *
     * The `cancelAndJoin` is not tidiness: DataStore refuses two live instances over one file, by
     * design, and the restart tests below are precisely two instances over one file.
     */
    private fun <T> withStore(file: File, block: suspend (DmViewStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                block(DataStoreDmViewStore(dataStore))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    /** Writes a raw string under [accountKey], for the "a newer build wrote this" cases. */
    private fun writeRaw(file: File, accountKey: String, value: String) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore: DataStore<Preferences> =
                PreferenceDataStoreFactory.create(scope = scope) { file }
            dataStore.edit { it[stringPreferencesKey(accountKey)] = value }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    private fun prefsFile(): File = File(temp.root, "test.preferences_pb")

    private val key = DmViewStore.serverKey("acct-1")

    // ---- the default ---------------------------------------------------------

    @Test
    fun `an account that has never opened the DM view has no table`() = withStore(prefsFile()) { store ->
        // The empty set reads as "never chosen", which is what makes the entry point run its
        // multi-select rather than opening a dashboard with nothing on it.
        assertEquals(emptySet<String>(), store.members(key).first())
    }

    // ---- persistence ---------------------------------------------------------

    @Test
    fun `a chosen table survives the store being torn down and reopened`() {
        val file = prefsFile()
        withStore(file) { store -> store.setMembers(key, setOf("creature-a", "creature-b")) }

        // A second store over the same file: the only shape in which "persisted" is asserted
        // rather than assumed, and the only one that exercises the codec in both directions.
        val restored = withStore(file) { store -> store.members(key).first() }

        assertEquals(setOf("creature-a", "creature-b"), restored)
    }

    @Test
    fun `selection order is not stored, so two writers of one table agree`() {
        val file = prefsFile()
        val other = DmViewStore.serverKey("acct-2")

        withStore(file) { store ->
            // Same table, opposite ticking order. The dashboard's order is the live list's
            // (name-sorted) and never the DM's tapping history — if that history reached the
            // file, these two would produce different bytes.
            store.setMembers(key, linkedSetOf("creature-b", "creature-a"))
            store.setMembers(other, linkedSetOf("creature-a", "creature-b"))
        }

        val (a, b) = withStore(file) { store ->
            store.members(key).first() to store.members(other).first()
        }
        assertEquals(a, b)
        assertEquals(
            "the encoding is canonical",
            "creature-a,creature-b",
            DmViewCodec.encode(linkedSetOf("creature-b", "creature-a")),
        )
    }

    // ---- the tolerant codec (decision 16: unknown ids dropped *against the live list*) ----

    @Test
    fun `a creature id this build cannot resolve is kept, not silently erased`() {
        val file = prefsFile()
        writeRaw(file, key, "creature-a,creature-gone,creature-b")

        val members = withStore(file) { store -> store.members(key).first() }

        // The opposite of `PaneLayoutCodec`, and deliberately: a creature that is momentarily
        // absent from the list — a cold open before the subscription lands, a share revoked and
        // restored — must not lose its seat because it was unresolvable for one frame. Decision
        // 16's dropping happens where the live list is, and this is the store staying out of it.
        assertEquals(setOf("creature-a", "creature-gone", "creature-b"), members)
    }

    @Test
    fun `a malformed value degrades rather than crashing`() {
        val file = prefsFile()
        writeRaw(file, key, ",, creature-a ,,,creature-a,")

        // Blank segments dropped, whitespace trimmed, the duplicate collapsed by the set — a
        // character cannot occupy two cards, which is what stops one creature being opened twice
        // and paying for two subscriptions out of the shared budget (decision 17).
        assertEquals(setOf("creature-a"), withStore(file) { it.members(key).first() })
    }

    // ---- empty removes the key ----------------------------------------------

    @Test
    fun `the empty set removes the key rather than writing an empty string`() {
        val file = prefsFile()
        withStore(file) { store ->
            store.setMembers(key, setOf("creature-a"))
            store.setMembers(key, emptySet())
        }

        // Both readings are "never chosen", which is exactly why one of them must not be
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

    // ---- the reap ------------------------------------------------------------

    @Test
    fun `signing an account out reaps its table and nothing else`() = withStore(prefsFile()) { store ->
        val doomed = DmViewStore.serverKey("acct-1")
        val sibling = DmViewStore.serverKey("acct-2")
        store.setMembers(doomed, setOf("creature-a"))
        store.setMembers(sibling, setOf("creature-b"))

        store.deleteForAccount("acct-1")

        assertTrue(store.members(doomed).first().isEmpty())
        assertEquals(setOf("creature-b"), store.members(sibling).first())
    }

    @Test
    fun `an account id that prefixes another is not reaped with it`() = withStore(prefsFile()) { store ->
        // The defect this store's exact-key delete exists to prevent. The other four stores end
        // their account component with a `:` before the creature id, so a prefix sweep is safe
        // there; here the account id runs to the end of the key, so `dm_view:server:acct-1` is a
        // literal prefix of `dm_view:server:acct-10` and a sweep would take a second DM's table.
        val short = DmViewStore.serverKey("acct-1")
        val longer = DmViewStore.serverKey("acct-10")
        store.setMembers(short, setOf("creature-a"))
        store.setMembers(longer, setOf("creature-b"))

        store.deleteForAccount("acct-1")

        assertTrue(store.members(short).first().isEmpty())
        assertEquals(setOf("creature-b"), store.members(longer).first())
    }

    // ---- the shared file -----------------------------------------------------

    @Test
    fun `the five stores share a file without colliding`() = withStore(prefsFile()) { store ->
        // `DataModule` hands all five stores the same `.preferences_pb`, so "the namespaces do
        // not overlap" is a claim about production wiring rather than about this class alone.
        val prefixes = setOf(
            DmViewStore.KEY_PREFIX,
            PaneLayoutStore.KEY_PREFIX,
            InventoryLayoutStore.KEY_PREFIX,
            SelectedRollStore.KEY_PREFIX,
            EquippableOverrideStore.KEY_PREFIX,
        )
        assertEquals("five stores, five distinct namespaces", 5, prefixes.size)
        val ours = DmViewStore.serverKey("a")
        assertFalse(
            ours.startsWith(PaneLayoutStore.KEY_PREFIX) ||
                ours.startsWith(InventoryLayoutStore.KEY_PREFIX) ||
                ours.startsWith(SelectedRollStore.KEY_PREFIX) ||
                ours.startsWith(EquippableOverrideStore.KEY_PREFIX),
        )
        // …and the store still works when handed a key shaped like another's, because nothing
        // here parses a key: it is an opaque string all the way down.
        store.setMembers(PaneLayoutStore.serverKey("a", "b"), setOf("creature-a"))
        assertEquals(
            setOf("creature-a"),
            store.members(PaneLayoutStore.serverKey("a", "b")).first(),
        )
    }
}
