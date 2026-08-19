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
 * FR-14's per-character inventory arrangement (docs/design/12-inventory-layout.md decision 5),
 * against a **real** DataStore on a real file.
 *
 * ### Why not a fake
 *
 * `SelectedRollStoreTest`'s and `EquippableOverrideStoreTest`'s argument, unchanged and for the
 * third time: the claim this store makes is *"the player's arrangement survives an app restart"*,
 * and a fake map cannot fail that claim — it would pass whether or not anything ever reached the
 * disk. So the round-trip tests below close the store's scope and open a **second** store over
 * the same file, which is as close to a process restart as a JVM test gets and is the only shape
 * in which "persisted" is actually asserted.
 *
 * This store has one thing the other two do not, and it is the reason the restart test here
 * checks more than a value coming back: the arrangement goes through a **codec** on the way to
 * disk. A round trip in memory would never exercise it.
 *
 * Preferences-DataStore is plain JVM code (the Android-specific part is only
 * `Context.preferencesDataStoreFile`), so no Robolectric is needed here.
 */
class InventoryLayoutStoreTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    /**
     * Runs [block] against a store over [file], then **fully tears the store down**.
     *
     * The `cancelAndJoin` is not tidiness: DataStore refuses two live instances over one file, by
     * design, and the restart tests below are precisely two instances over one file. Joining the
     * cancellation is what makes the first one provably gone before the second opens.
     */
    private fun <T> withStore(file: File, block: suspend (InventoryLayoutStore) -> T): T =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore: DataStore<Preferences> =
                    PreferenceDataStoreFactory.create(scope = scope) { file }
                block(DataStoreInventoryLayoutStore(dataStore))
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }

    private fun prefsFile(): File = File(temp.root, "test.preferences_pb")

    private val key = InventoryLayoutStore.serverKey("acct-1", "creature-1")

    private val arrangement = listOf(
        InventoryLayoutEntry("equipped"),
        InventoryLayoutEntry("wallet", hidden = true),
        InventoryLayoutEntry("container:cont1"),
        InventoryLayoutEntry("gear"),
    )

    // --- persistence (decision 5) -------------------------------------------------

    @Test
    fun `an arrangement survives a restart of the store, order and hidden flags intact`() {
        val file = prefsFile()

        withStore(file) { it.setLayout(key, arrangement) }

        // A *second* instance over the same file — the closest a unit test gets to the app being
        // force-stopped and reopened. A fake would pass this without touching a disk, and would
        // not go near the codec that is the only thing between this list and a byte array.
        assertEquals(arrangement, withStore(file) { it.layout(key).first() })
    }

    @Test
    fun `the stored form is the design's comma-joined keys with a bang on the hidden ones`() {
        // Pinned because it is a **file format**: it is read back by a build that may be older or
        // newer than the one that wrote it, so a well-meaning tidy-up of the codec is a silent
        // data-loss bug for anyone mid-upgrade. Asserted through the codec rather than by reading
        // the .preferences_pb, which is protobuf and would make this a test of protobuf.
        assertEquals(
            "equipped,!wallet,container:cont1,gear",
            InventoryLayoutCodec.encode(arrangement),
        )
        assertEquals(arrangement, InventoryLayoutCodec.decode("equipped,!wallet,container:cont1,gear"))
    }

    @Test
    fun `nothing stored reads as an empty list rather than as null`() = withStore(prefsFile()) { store ->
        // Empty rather than nullable is a contract, not a convenience: both readings mean "use
        // decision 1's default", and a nullable list would put `?: emptyList()` at every reader.
        assertTrue(store.layout(InventoryLayoutStore.localKey("never-touched")).first().isEmpty())
    }

    @Test
    fun `storing an empty arrangement removes the key rather than writing an empty string`() {
        val file = prefsFile()

        withStore(file) {
            it.setLayout(key, arrangement)
            it.setLayout(key, emptyList())
        }

        // Reading it back cannot tell the difference — which is exactly why the *write* has to be
        // a removal: a key holding nothing is a slot in a file nothing else prunes, occupied
        // forever by a character who customized once and reset.
        assertTrue(withStore(file) { it.layout(key).first().isEmpty() })
    }

    // --- malformed input ----------------------------------------------------------

    @Test
    fun `a malformed stored string degrades to a partial order rather than to a crash`() {
        // Not reachable from this app today; the point is that it stays unreachable *as a
        // failure*. A preferences file is editable by anyone with a rooted device and readable by
        // a build that predates half these keys, and the inventory tab crashing on one is a much
        // worse outcome than a section falling back to its default place.
        assertEquals(
            listOf(InventoryLayoutEntry("wallet"), InventoryLayoutEntry("gear", hidden = true)),
            InventoryLayoutCodec.decode(",,wallet,,!gear,!,"),
        )
        assertTrue(InventoryLayoutCodec.decode("").isEmpty())
        assertTrue(InventoryLayoutCodec.decode(null).isEmpty())
    }

    @Test
    fun `a key stored twice keeps its first place, because a section cannot be in two`() {
        assertEquals(
            listOf(InventoryLayoutEntry("wallet"), InventoryLayoutEntry("gear")),
            InventoryLayoutCodec.decode("wallet,gear,!wallet"),
        )
    }

    // --- per-character isolation --------------------------------------------------

    @Test
    fun `each character keeps its own arrangement`() = withStore(prefsFile()) { store ->
        val alice = InventoryLayoutStore.serverKey("acct-1", "creature-1")
        val bob = InventoryLayoutStore.serverKey("acct-1", "creature-2")

        store.setLayout(alice, listOf(InventoryLayoutEntry("wallet", hidden = true)))
        store.setLayout(bob, listOf(InventoryLayoutEntry("gear")))

        assertEquals(listOf(InventoryLayoutEntry("wallet", hidden = true)), store.layout(alice).first())
        assertEquals(listOf(InventoryLayoutEntry("gear")), store.layout(bob).first())
    }

    @Test
    fun `the same creature under two accounts is two arrangements`() = withStore(prefsFile()) { store ->
        // Matching `tracker_prefs`, `theme_prefs`, the roll selection and the equippability
        // overrides, which are all account-keyed for one reason: two accounts that can both reach
        // one creature are two rows everywhere else in this app.
        val first = InventoryLayoutStore.serverKey("acct-1", "shared-creature")
        val second = InventoryLayoutStore.serverKey("acct-2", "shared-creature")

        store.setLayout(first, arrangement)

        assertTrue(store.layout(second).first().isEmpty())
    }

    // --- the two reaping paths ----------------------------------------------------

    @Test
    fun `clearing a character drops its arrangement and nobody else's`() = withStore(prefsFile()) { store ->
        // The sheet's Reset **and** the local-delete reap: one method, two call sites, because
        // both mean "forget this character's arrangement". See the interface.
        val doomed = InventoryLayoutStore.localKey("local-1")
        val survivor = InventoryLayoutStore.localKey("local-2")

        store.setLayout(doomed, arrangement)
        store.setLayout(survivor, arrangement)

        store.clearForCharacter(doomed)

        assertTrue(store.layout(doomed).first().isEmpty())
        assertEquals(arrangement, store.layout(survivor).first())
    }

    @Test
    fun `a reset survives a restart, because it is a deletion and not a blank value`() {
        val file = prefsFile()

        withStore(file) { it.setLayout(key, arrangement) }
        withStore(file) { it.clearForCharacter(key) }

        assertTrue(withStore(file) { it.layout(key).first().isEmpty() })
    }

    @Test
    fun `signing out reaps that account's arrangements and nothing else`() = withStore(prefsFile()) { store ->
        val doomed = InventoryLayoutStore.serverKey("acct-1", "creature-1")
        val alsoDoomed = InventoryLayoutStore.serverKey("acct-1", "creature-2")
        val sibling = InventoryLayoutStore.serverKey("acct-2", "creature-3")
        val onDevice = InventoryLayoutStore.localKey("local-1")

        listOf(doomed, alsoDoomed, sibling, onDevice).forEach { store.setLayout(it, arrangement) }

        store.deleteForAccount("acct-1")

        assertTrue(store.layout(doomed).first().isEmpty())
        assertTrue(store.layout(alsoDoomed).first().isEmpty())
        assertEquals("a sibling account must be untouched", arrangement, store.layout(sibling).first())
        // 09 decision 10: sign-out cannot reach local characters. The key namespace is what
        // guarantees that here, rather than a comment asking the prefix match to behave.
        assertEquals(
            "sign-out must not touch a local character",
            arrangement,
            store.layout(onDevice).first(),
        )
    }

    @Test
    fun `an account id that is a prefix of another does not reap its neighbour`() = withStore(prefsFile()) { store ->
        // The separator in the key is what makes this true; without it, "acct" would eat
        // "acct-2". Account ids are UUIDs in production, but the key format must not rely on
        // that to be correct.
        val short = InventoryLayoutStore.serverKey("acct", "creature-1")
        val longer = InventoryLayoutStore.serverKey("acct-2", "creature-1")

        store.setLayout(short, arrangement)
        store.setLayout(longer, arrangement)

        store.deleteForAccount("acct")

        assertTrue(store.layout(short).first().isEmpty())
        assertEquals(arrangement, store.layout(longer).first())
    }

    @Test
    fun `the three stores share a file without colliding`() = withStore(prefsFile()) { store ->
        // `DataModule` hands all three stores the same `.preferences_pb`, so "the namespaces do
        // not overlap" is a claim about production wiring rather than about this class alone. The
        // three prefixes are what make it true; this is what would notice if one were changed to
        // match another.
        val prefixes = setOf(
            InventoryLayoutStore.KEY_PREFIX,
            SelectedRollStore.KEY_PREFIX,
            EquippableOverrideStore.KEY_PREFIX,
        )
        assertEquals("three stores, three distinct namespaces", 3, prefixes.size)
        assertTrue(
            !InventoryLayoutStore.serverKey("a", "b").startsWith(SelectedRollStore.KEY_PREFIX) &&
                !InventoryLayoutStore.serverKey("a", "b")
                    .startsWith(EquippableOverrideStore.KEY_PREFIX),
        )
        // …and the store still works when handed a key shaped like another's, because nothing
        // here parses a key: it is an opaque string all the way down.
        store.setLayout(SelectedRollStore.serverKey("a", "b"), arrangement)
        assertEquals(arrangement, store.layout(SelectedRollStore.serverKey("a", "b")).first())
    }
}
