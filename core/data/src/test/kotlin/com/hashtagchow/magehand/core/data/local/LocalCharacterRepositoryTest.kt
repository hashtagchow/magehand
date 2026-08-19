package com.hashtagchow.magehand.core.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.LocalCharacterDao
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.settings.DataStoreEquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.DataStoreSelectedRollStore
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import java.io.File
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The creation form's save path (docs/design/09-local-characters.md decision 4).
 *
 * The clock and the id minter are injected, so a save can be asserted against exact values
 * rather than against "something non-null" — a test that cannot name what it expects is a
 * test that would pass on the wrong answer.
 *
 * The load-bearing claim here is **"the form is the editor"**: `formFor` round-trips into
 * `save` without losing play state. Every assertion about `current` and `currentHp` surviving
 * an edit is guarding the bug where re-opening the form to fix a typo silently heals the
 * character and refills its spell slots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalCharacterRepositoryTest {

    @get:Rule
    val temp: TemporaryFolder = TemporaryFolder()

    private lateinit var database: MageHandDatabase
    private lateinit var dao: LocalCharacterDao
    private lateinit var repository: LocalCharacterRepository

    /**
     * A **real** Preferences-DataStore on a real file, the shape `SelectedRollStoreTest` uses.
     *
     * The delete path's claim is that a key stops existing, and a fake map is the wrong
     * instrument for it: the fake is a second implementation of `remove`, so a test written
     * against it can only prove that the fake agrees with itself. This one goes through the
     * store that actually ships.
     */
    private lateinit var storeScope: CoroutineScope
    private lateinit var selectedRolls: SelectedRollStore
    private lateinit var equippableOverrides: EquippableOverrideStore

    private var clock = 1_000L
    private var nextId = 0

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.localCharacterDao()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        selectedRolls = DataStoreSelectedRollStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(temp.root, "selected-rolls.preferences_pb")
            },
        )
        equippableOverrides = DataStoreEquippableOverrideStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(temp.root, "equippable-overrides.preferences_pb")
            },
        )
        repository = LocalCharacterRepository(
            dao = dao,
            selectedRollStore = selectedRolls,
            equippableOverrideStore = equippableOverrides,
            now = { clock },
            newId = { "id-${++nextId}" },
        )
    }

    @After
    fun tearDown() {
        // DataStore refuses two live instances over one file, so the scope must be provably
        // gone before the next test's store opens over the next TemporaryFolder.
        runBlocking { storeScope.coroutineContext.job.cancelAndJoin() }
        database.close()
    }

    private fun form(
        name: String = "Brambles",
        maxHp: Int = 20,
        rows: List<LocalRowForm> = emptyList(),
    ) = LocalCharacterForm(
        name = name,
        level = 3,
        abilities = AbilityScores(strength = 8, dexterity = 14, constitution = 15),
        maxHp = maxHp,
        armorClass = 15,
        rows = rows,
    )

    private suspend fun saveOrFail(form: LocalCharacterForm): String =
        when (val result = repository.save(form)) {
            is LocalSaveResult.Saved -> result.id
            is LocalSaveResult.Invalid -> error("expected a save, got ${result.errors}")
            is LocalSaveResult.Missing -> error("expected a save, got a missing character")
        }

    // --- create -------------------------------------------------------------

    @Test
    fun `saving a new form mints an id and stores the sheet`() = runTest {
        val id = saveOrFail(form())

        assertEquals("id-1", id)
        with(repository.find(id)!!) {
            assertEquals("Brambles", name)
            assertEquals(3, level)
            assertEquals(8, abilities.strength)
            assertEquals(14, abilities.dexterity)
            assertEquals(15, abilities.constitution)
            assertEquals(20, maxHp)
            assertEquals("a new character arrives at full health", 20, currentHp)
            assertEquals(15, armorClass)
            assertEquals(1_000L, createdAt)
            assertEquals(1_000L, updatedAt)
        }
    }

    @Test
    fun `the name is trimmed on the way in`() = runTest {
        val id = saveOrFail(form(name = "  Brambles  "))
        assertEquals("Brambles", repository.find(id)?.name)
    }

    @Test
    fun `rows are minted, ordered by their position on the form, and stored full`() = runTest {
        val id = saveOrFail(
            form(
                rows = listOf(
                    LocalRowForm(kind = LocalRowKind.SLOT, label = "1st Level", total = 4, reset = ResetRule.LONG_REST),
                    LocalRowForm(kind = LocalRowKind.RESOURCE, label = " Ki ", total = 3, reset = ResetRule.SHORT_REST),
                    LocalRowForm(kind = LocalRowKind.ITEM, label = "Potion", total = 2),
                ),
            ),
        )

        val rows = repository.rows(id)
        assertEquals(listOf("1st Level", "Ki", "Potion"), rows.map { it.label })
        assertEquals(listOf(0, 1, 2), rows.map { it.sortIndex })
        assertEquals(listOf(4, 3, 2), rows.map { it.current })
        assertEquals(
            listOf(ResetRule.LONG_REST, ResetRule.SHORT_REST, null),
            rows.map { it.reset },
        )
        assertEquals(
            listOf(LocalRowKind.SLOT, LocalRowKind.RESOURCE, LocalRowKind.ITEM),
            rows.map { it.kind },
        )
    }

    @Test
    fun `an invalid form writes nothing at all`() = runTest {
        val result = repository.save(form(name = "", maxHp = 0))

        assertTrue(result is LocalSaveResult.Invalid)
        assertEquals(
            listOf(LocalCharacterFormError.NameRequired, LocalCharacterFormError.MaxHpTooLow),
            (result as LocalSaveResult.Invalid).errors,
        )
        assertEquals("the database must be untouched", 0, repository.count())
    }

    @Test
    fun `two saves are two characters`() = runTest {
        val first = saveOrFail(form(name = "Brambles"))
        val second = saveOrFail(form(name = "Thistle"))

        assertNotEquals(first, second)
        assertEquals(2, repository.count())
        assertEquals(setOf("Brambles", "Thistle"), repository.observeAll().first().map { it.name }.toSet())
    }

    // --- edit (the form is the editor) --------------------------------------

    @Test
    fun `formFor round-trips a saved character`() = runTest {
        val id = saveOrFail(
            form(
                rows = listOf(
                    LocalRowForm(kind = LocalRowKind.RESOURCE, label = "Ki", total = 3, reset = ResetRule.SHORT_REST),
                ),
            ),
        )

        val reopened = repository.formFor(id)!!

        assertEquals(id, reopened.id)
        assertEquals("Brambles", reopened.name)
        assertEquals(3, reopened.level)
        assertEquals(AbilityScores(strength = 8, dexterity = 14, constitution = 15), reopened.abilities)
        assertEquals(20, reopened.maxHp)
        assertEquals(15, reopened.armorClass)
        with(reopened.rows.single()) {
            assertEquals(LocalRowKind.RESOURCE, kind)
            assertEquals("Ki", label)
            assertEquals(3, total)
            assertEquals(ResetRule.SHORT_REST, reset)
        }
    }

    @Test
    fun `formFor is null for an id that names nothing`() = runTest {
        assertNull(repository.formFor("nope"))
    }

    @Test
    fun `re-saving keeps createdAt and moves updatedAt`() = runTest {
        val id = saveOrFail(form())
        clock = 9_999

        saveOrFail(repository.formFor(id)!!.copy(name = "Brambles the Second"))

        with(repository.find(id)!!) {
            assertEquals("Brambles the Second", name)
            assertEquals("an edit is not a re-creation", 1_000L, createdAt)
            assertEquals(9_999L, updatedAt)
        }
        assertEquals("no second character was created", 1, repository.count())
    }

    @Test
    fun `editing does not refill a spent row`() = runTest {
        val id = saveOrFail(
            form(rows = listOf(LocalRowForm(kind = LocalRowKind.RESOURCE, label = "Ki", total = 4))),
        )
        val rowId = repository.rows(id).single().id
        dao.setRowCurrent(rowId, 1)

        // The player re-opens the form only to fix the label.
        val reopened = repository.formFor(id)!!
        saveOrFail(reopened.copy(rows = reopened.rows.map { it.copy(label = "Ki Points") }))

        with(repository.rows(id).single()) {
            assertEquals("Ki Points", label)
            assertEquals("editing a label must not refill the row", 1, current)
            assertEquals(4, total)
        }
    }

    @Test
    fun `lowering a row's total clamps what is left into the new ceiling`() = runTest {
        val id = saveOrFail(
            form(rows = listOf(LocalRowForm(kind = LocalRowKind.RESOURCE, label = "Ki", total = 6))),
        )
        val reopened = repository.formFor(id)!!

        saveOrFail(reopened.copy(rows = reopened.rows.map { it.copy(total = 2) }))

        with(repository.rows(id).single()) {
            assertEquals(2, total)
            assertEquals("current can never exceed total", 2, current)
        }
    }

    @Test
    fun `editing does not heal the character, and lowering max HP clamps it`() = runTest {
        val id = saveOrFail(form(maxHp = 20))
        dao.setCurrentHp(id, 6, at = clock)

        val reopened = repository.formFor(id)!!
        saveOrFail(reopened.copy(name = "Brambles II"))
        assertEquals("an edit must not heal", 6, repository.find(id)?.currentHp)

        saveOrFail(repository.formFor(id)!!.copy(maxHp = 4))
        with(repository.find(id)!!) {
            assertEquals(4, maxHp)
            assertEquals("current HP can never exceed max HP", 4, currentHp)
        }
    }

    @Test
    fun `an item's quantity is whatever the form last said`() = runTest {
        val id = saveOrFail(
            form(rows = listOf(LocalRowForm(kind = LocalRowKind.ITEM, label = "Potion", total = 2))),
        )
        val reopened = repository.formFor(id)!!

        saveOrFail(reopened.copy(rows = reopened.rows.map { it.copy(total = 5) }))

        with(repository.rows(id).single()) {
            assertEquals(5, total)
            assertEquals(5, current)
        }
    }

    @Test
    fun `removing a row on the form deletes it and reindexes the rest`() = runTest {
        val id = saveOrFail(
            form(
                rows = listOf(
                    LocalRowForm(kind = LocalRowKind.SLOT, label = "1st Level", total = 4),
                    LocalRowForm(kind = LocalRowKind.RESOURCE, label = "Ki", total = 3),
                    LocalRowForm(kind = LocalRowKind.ITEM, label = "Potion", total = 2),
                ),
            ),
        )
        val reopened = repository.formFor(id)!!

        saveOrFail(reopened.copy(rows = reopened.rows.filterNot { it.label == "Ki" }))

        val rows = repository.rows(id)
        assertEquals(listOf("1st Level", "Potion"), rows.map { it.label })
        assertEquals(listOf(0, 1), rows.map { it.sortIndex })
    }

    @Test
    fun `reordering the form's rows reorders the tracker`() = runTest {
        val id = saveOrFail(
            form(
                rows = listOf(
                    LocalRowForm(kind = LocalRowKind.SLOT, label = "1st Level", total = 4),
                    LocalRowForm(kind = LocalRowKind.SLOT, label = "2nd Level", total = 3),
                ),
            ),
        )
        val reopened = repository.formFor(id)!!

        saveOrFail(reopened.copy(rows = reopened.rows.reversed()))

        assertEquals(listOf("2nd Level", "1st Level"), repository.rows(id).map { it.label })
    }

    // --- delete -------------------------------------------------------------

    @Test
    fun `deleting takes the rows with it and leaves other characters alone`() = runTest {
        val doomed = saveOrFail(
            form(name = "Brambles", rows = listOf(LocalRowForm(kind = LocalRowKind.RESOURCE, label = "Ki", total = 3))),
        )
        val survivor = saveOrFail(form(name = "Thistle"))

        repository.delete(doomed)

        assertNull(repository.find(doomed))
        assertTrue(repository.rows(doomed).isEmpty())
        assertEquals("Thistle", repository.find(survivor)?.name)
    }

    @Test
    fun `deleting takes the character's remembered roll with it, and only that one`() = runTest {
        val doomed = saveOrFail(form(name = "Brambles"))
        val survivor = saveOrFail(form(name = "Thistle"))
        val serverCharacter = SelectedRollStore.serverKey("acct-1", "creature-1")

        selectedRolls.setSelectedRollId(SelectedRollStore.localKey(doomed), "roll-1")
        selectedRolls.setSelectedRollId(SelectedRollStore.localKey(survivor), "roll-2")
        selectedRolls.setSelectedRollId(serverCharacter, "roll-3")

        repository.delete(doomed)

        // Local ids are UUIDs and never recur, so a key left here is unreachable forever —
        // nothing cascades it (it is not a row) and sign-out is forbidden from reaping the
        // local namespace (09 decision 10). This path is the only one that can.
        assertNull(selectedRolls.selectedRollId(SelectedRollStore.localKey(doomed)).first())
        assertEquals(
            "another on-device character's selection is not this character's business",
            "roll-2",
            selectedRolls.selectedRollId(SelectedRollStore.localKey(survivor)).first(),
        )
        assertEquals(
            "and a DiceCloud character's selection least of all",
            "roll-3",
            selectedRolls.selectedRollId(serverCharacter).first(),
        )
    }

    @Test
    fun `deleting takes the character's equippability overrides with it, and only those`() = runTest {
        val doomed = saveOrFail(form(name = "Brambles"))
        val survivor = saveOrFail(form(name = "Thistle"))
        val serverCharacter = EquippableOverrideStore.serverKey("acct-1", "creature-1")

        equippableOverrides.setOverridden(EquippableOverrideStore.localKey(doomed), "prop-1", true)
        equippableOverrides.setOverridden(EquippableOverrideStore.localKey(doomed), "prop-2", true)
        equippableOverrides.setOverridden(EquippableOverrideStore.localKey(survivor), "prop-3", true)
        equippableOverrides.setOverridden(serverCharacter, "prop-4", true)

        repository.delete(doomed)

        // 11 decision 2's second reaping path, and the same argument as the roll selection
        // above: a DataStore key, not a row, in a namespace sign-out is forbidden to touch.
        // The whole set goes, not one entry — the character it belonged to is gone.
        assertTrue(
            equippableOverrides.overrides(EquippableOverrideStore.localKey(doomed)).first().isEmpty(),
        )
        assertEquals(
            "another on-device character's overrides are not this character's business",
            setOf("prop-3"),
            equippableOverrides.overrides(EquippableOverrideStore.localKey(survivor)).first(),
        )
        assertEquals(
            "and a DiceCloud character's least of all",
            setOf("prop-4"),
            equippableOverrides.overrides(serverCharacter).first(),
        )
    }

    @Test
    fun `saving a form whose character was deleted does not resurrect it`() = runTest {
        // The sequence: open the editor, delete the character from somewhere else (another
        // entry point, or a back stack restored after the deletion), then hit Save.
        val id = saveOrFail(form(name = "Brambles"))
        val open = repository.formFor(id)!!
        repository.delete(id)

        val result = repository.save(open.copy(name = "Brambles the Undying"))

        assertEquals(LocalSaveResult.Missing, result)
        assertNull("the deletion stands", repository.find(id))
        assertEquals("and no second character was minted either", 0, repository.count())
    }

    @Test
    fun `a form with no id is still a create, and is unaffected by the guard`() = runTest {
        // The other side of the same condition: `id == null` means create, and nothing about
        // "no such row" applies to it.
        assertTrue(repository.save(form(name = "Thistle")) is LocalSaveResult.Saved)
    }

    // --- observation --------------------------------------------------------

    @Test
    fun `the list observes as domain types, newest first`() = runTest {
        clock = 10
        saveOrFail(form(name = "First"))
        clock = 20
        saveOrFail(form(name = "Second"))

        assertEquals(listOf("Second", "First"), repository.observeAll().first().map { it.name })
    }

    @Test
    fun `observing one character yields its abilities as a domain type`() = runTest {
        val id = saveOrFail(form())

        with(repository.observe(id).first()!!) {
            assertEquals(AbilityScores(strength = 8, dexterity = 14, constitution = 15), abilities)
            assertEquals(-1, abilities.modifier(com.hashtagchow.magehand.core.model.Ability.STR))
        }
    }
}
