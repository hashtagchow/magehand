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

    // --- FR-16: the collapse mark (13 decision 3) ---------------------------------

    private val withCollapse = listOf(
        InventoryLayoutEntry("equipped"),
        InventoryLayoutEntry("weapons", hidden = true),
        InventoryLayoutEntry("armor", collapsed = true),
        InventoryLayoutEntry("container:cont1", hidden = true, collapsed = true),
        InventoryLayoutEntry("gear"),
    )

    @Test
    fun `a collapse survives a restart of the store, exactly as a fold does`() {
        // The claim decision 3 makes and the reason collapse is stored at all: "I never look at
        // Armor" is a durable fact about a character. A `rememberSaveable` cannot survive a
        // force-stop, so this has to be the shape that proves it — two store instances over one
        // file, with the codec in between.
        val file = prefsFile()

        withStore(file) { it.setLayout(key, withCollapse) }

        assertEquals(withCollapse, withStore(file) { it.layout(key).first() })
    }

    @Test
    fun `the stored form gains a caret, and emits the two marks in one canonical order`() {
        // A **file format**, pinned for the reason the hidden mark already is. `^` before `!`
        // on the way out so two writers cannot produce two strings for one arrangement; the way
        // back in accepts either (below), which is what makes this a choice rather than a rule.
        assertEquals(
            "equipped,!weapons,^armor,^!container:cont1,gear",
            InventoryLayoutCodec.encode(withCollapse),
        )
        assertEquals(
            withCollapse,
            InventoryLayoutCodec.decode("equipped,!weapons,^armor,^!container:cont1,gear"),
        )
    }

    @Test
    fun `the two marks decode in either order, because two booleans have no sequence`() {
        val both = InventoryLayoutEntry("armor", hidden = true, collapsed = true)

        assertEquals(listOf(both), InventoryLayoutCodec.decode("^!armor"))
        assertEquals(listOf(both), InventoryLayoutCodec.decode("!^armor"))
        // Repetition is not a third state either — the marks are read as a set.
        assertEquals(listOf(both), InventoryLayoutCodec.decode("!^^!armor"))
    }

    @Test
    fun `an unknown prefix is dropped and the section is kept`() {
        // Forward compatibility: a 1.6.0 build meeting some later release's mark keeps the
        // section rather than losing it — or, worse, ordering the tab by a key nobody can match.
        //
        // Note which direction this is. 1.5.0 did **not** do this with `^`: its decoder was
        // `startsWith('!')` / `removePrefix('!')`, so it would read `^armor` as the key `^armor`
        // and drop Armor out of the stored order. What makes 13 decision 3's "no schema change"
        // true is that no install is ever handed a newer build's file — `allowBackup="false"`,
        // and a downgrade requires an uninstall, which clears DataStore. See the codec's KDoc.
        assertEquals(
            listOf(
                InventoryLayoutEntry("armor"),
                InventoryLayoutEntry("gear", collapsed = true),
            ),
            InventoryLayoutCodec.decode("~armor,~^gear"),
        )
    }

    /**
     * L5: `encode(decode(x))` is **canonical**, over every permutation of the two marks.
     *
     * The codec's KDoc claims two things that only hold together: that `^!armor` and `!^armor`
     * mean the same arrangement, and that [InventoryLayoutCodec.encode] emits one canonical
     * string for it. Either claim alone is testable by inspection; the property that matters is
     * the composition — feed the codec *any* spelling of a state and it comes back in the one
     * spelling every writer agrees on, which is what makes "two writers cannot produce two
     * strings for one arrangement" true rather than aspirational.
     *
     * Stated as `encode(decode(x)) == encode(decode(encode(decode(x))))` would be weaker: that
     * holds for a codec that canonicalises to the *wrong* order. So the expected string is
     * written out, and the input set includes the review's own example, `!^armor` → `^!armor`.
     */
    @Test
    fun `encode of decode is canonical, whatever spelling it was given`() {
        val canonical = mapOf(
            "armor" to "armor",
            "!armor" to "!armor",
            "^armor" to "^armor",
            "^!armor" to "^!armor",
            // The permutation the 1.6.0 review named: written the other way round, read as the
            // same two booleans, re-emitted in the one order `encode` promises.
            "!^armor" to "^!armor",
            "!^^!armor" to "^!armor",
            "^container:abc123" to "^container:abc123",
            "!^container:abc123" to "^!container:abc123",
            // Whole arrangements, including the mixed one the format's own KDoc uses as its
            // example, and a duplicate key that `distinctBy` collapses to its first mention.
            "wallet,equipped,!weapons,^armor,^!container:abc,gear" to
                "wallet,equipped,!weapons,^armor,^!container:abc,gear",
            "wallet,equipped,!weapons,^armor,!^container:abc,gear" to
                "wallet,equipped,!weapons,^armor,^!container:abc,gear",
            "!^gear,^!gear" to "^!gear",
            // Blank segments are dropped rather than encoded as empty tokens.
            "gear,,^armor," to "gear,^armor",
        )

        canonical.forEach { (input, expected) ->
            val once = InventoryLayoutCodec.encode(InventoryLayoutCodec.decode(input))
            assertEquals("'$input' must canonicalise", expected, once)
            // …and it is a fixed point: running it again changes nothing, which is what makes a
            // re-save of an untouched arrangement a byte-identical write.
            assertEquals(
                "'$input' must be stable under a second round trip",
                once,
                InventoryLayoutCodec.encode(InventoryLayoutCodec.decode(once)),
            )
        }
    }

    /**
     * The other direction of the same property: `decode(encode(x))` returns `x`.
     *
     * Over all four flag combinations on a key of each shape the format carries. This is the one
     * a lossy `encode` would fail — dropping `collapsed` when `hidden` is also set, say, which
     * every assertion written in terms of a literal string would still pass.
     */
    @Test
    fun `decode of encode returns the same entries, for every flag combination`() {
        listOf("wallet", "equipped", "weapons", "armor", "gear", "container:abc123").forEach { key ->
            listOf(false, true).forEach { hidden ->
                listOf(false, true).forEach { collapsed ->
                    val entry = InventoryLayoutEntry(key, hidden = hidden, collapsed = collapsed)
                    assertEquals(
                        "$key hidden=$hidden collapsed=$collapsed",
                        listOf(entry),
                        InventoryLayoutCodec.decode(InventoryLayoutCodec.encode(listOf(entry))),
                    )
                }
            }
        }
    }

    @Test
    fun `a container key survives the prefix scan, colon and all`() {
        // The prefix run is defined as "everything before the first alphanumeric", which is
        // what lets it absorb an unknown mark. A container key contains a `:` — in the middle,
        // never at the front — so this is the case that would break if the scan were widened to
        // "every non-key character" instead.
        assertEquals(
            listOf(InventoryLayoutEntry("container:abc123", collapsed = true)),
            InventoryLayoutCodec.decode("^container:abc123"),
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
