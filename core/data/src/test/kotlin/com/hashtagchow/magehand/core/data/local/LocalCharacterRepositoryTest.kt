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
import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.settings.DataStoreEquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.DataStoreSelectedRollStore
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.DataStoreInventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.DataStorePaneLayoutStore
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import java.io.File
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CatalogCategory
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
    private lateinit var inventoryLayouts: InventoryLayoutStore
    private lateinit var paneLayouts: PaneLayoutStore

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
        inventoryLayouts = DataStoreInventoryLayoutStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(temp.root, "inventory-layouts.preferences_pb")
            },
        )
        paneLayouts = DataStorePaneLayoutStore(
            PreferenceDataStoreFactory.create(scope = storeScope) {
                File(temp.root, "pane-layouts.preferences_pb")
            },
        )
        repository = LocalCharacterRepository(
            dao = dao,
            selectedRollStore = selectedRolls,
            equippableOverrideStore = equippableOverrides,
            inventoryLayoutStore = inventoryLayouts,
            paneLayoutStore = paneLayouts,
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

    // --- FR-10b: the category, and what an edit must not take with it -----------

    @Test
    fun `an item row's category round-trips through the form`() = runTest {
        // 13 decision 9's third capture point. `formFor` has to re-open the chooser on what the
        // row already says, or an untouched save would silently reset every item to gear.
        val id = saveOrFail(
            form(
                rows = listOf(
                    LocalRowForm(
                        kind = LocalRowKind.ITEM,
                        label = "Longsword",
                        total = 1,
                        category = CatalogCategory.WEAPON,
                    ),
                ),
            ),
        )

        assertEquals(CatalogCategory.WEAPON, repository.formFor(id)!!.rows.single().category)
        assertEquals(CatalogCategory.WEAPON.storedValue, dao.getRows(id).single().category)

        // …and re-saving what was re-opened is a no-op for it.
        saveOrFail(repository.formFor(id)!!)
        assertEquals(CatalogCategory.WEAPON.storedValue, dao.getRows(id).single().category)
    }

    @Test
    fun `a slot or a resource is stored as gear, whatever the form was carrying`() = runTest {
        // The same shape the reset rule already has: the form may keep a value the player picked
        // before switching the kind, and the save drops it. A spell slot that claimed to be a
        // sword would put a row in Weapons that the tracker owns.
        val id = saveOrFail(
            form(
                rows = listOf(
                    LocalRowForm(
                        kind = LocalRowKind.SLOT,
                        label = "1st Level",
                        total = 4,
                        category = CatalogCategory.WEAPON,
                    ),
                ),
            ),
        )

        assertEquals(CatalogCategory.GEAR.storedValue, dao.getRows(id).single().category)
    }

    /**
     * **The regression this section exists for.**
     *
     * `LocalCharacterDao.save` upserts *whole rows*, and the form has never had fields for the
     * four FR-8 inventory columns — so building the entity from the form alone wrote each one
     * back at its default, and saving the editor after changing a character's name silently
     * stripped every item's weight, price, note and equipped state. Found while wiring the
     * category chooser through this same path.
     *
     * Asserted through the item-adding path the player actually uses (`LocalOpenCharacter.addItem`
     * writes those four; the editor never does), because that is the only way to get a row into
     * the state the bug needed.
     */
    @Test
    fun `editing a character does not strip its items' weight, price, note or equipped state`() =
        runTest {
            val id = saveOrFail(form())
            dao.upsertRows(
                listOf(
                    LocalTrackerRowEntity(
                        id = "row-1",
                        characterId = id,
                        kind = LocalRowKind.ITEM.storedValue,
                        label = "Quarterstaff",
                        total = 1,
                        current = 1,
                        resetRule = LocalTrackerRowEntity.RESET_NONE,
                        sortIndex = 0,
                        weight = 4.0,
                        value = 0.2,
                        description = "A simple melee weapon.",
                        equipped = true,
                        category = CatalogCategory.WEAPON.storedValue,
                    ),
                ),
            )

            // The player opens the editor, renames the character, saves. They never saw the
            // weight field, because there is not one.
            val reopened = repository.formFor(id)!!
            saveOrFail(reopened.copy(name = "Brambles the Bold"))

            with(dao.getRows(id).single()) {
                assertEquals("Brambles the Bold", repository.find(id)!!.name)
                assertEquals(4.0, weight)
                assertEquals(0.2, value)
                assertEquals("A simple melee weapon.", description)
                assertEquals("an edit must not take an item off the character", true, equipped)
                assertEquals(CatalogCategory.WEAPON.storedValue, category)
            }
        }

    /**
     * L1 (1.6.0 review): a row that **stops being an item** drops all five inventory columns.
     *
     * The test above is the other half of the same rule and it is worth reading the two
     * together. Carrying `weight`, `value`, `description` and `equipped` across a save is right
     * for the case they were written for — the same item row, re-saved after an unrelated edit
     * — and wrong for a row whose *kind* changed: the four are claims about an object, and the
     * row is no longer one. `category` already said so in its own comment ("a slot that once was
     * an item must not keep claiming to be a sword"); the visible consequence of the other four
     * not saying it was worse, because `equipped = true` on a spell-slot row is a value the
     * tracker renders and the inventory board would file under Equipped.
     */
    @Test
    fun `a row that stops being an item drops its weight, price, note and equipped state`() =
        runTest {
            val id = saveOrFail(form())
            dao.upsertRows(
                listOf(
                    LocalTrackerRowEntity(
                        id = "row-1",
                        characterId = id,
                        kind = LocalRowKind.ITEM.storedValue,
                        label = "Quarterstaff",
                        total = 4,
                        current = 4,
                        resetRule = LocalTrackerRowEntity.RESET_NONE,
                        sortIndex = 0,
                        weight = 4.0,
                        value = 0.2,
                        description = "A simple melee weapon.",
                        equipped = true,
                        category = CatalogCategory.WEAPON.storedValue,
                    ),
                ),
            )

            // The player re-uses the row: same slot, retyped as a resource.
            val reopened = repository.formFor(id)!!
            saveOrFail(
                reopened.copy(
                    rows = listOf(
                        reopened.rows.single().copy(
                            kind = LocalRowKind.RESOURCE,
                            label = "Bardic Inspiration",
                            reset = ResetRule.LONG_REST,
                        ),
                    ),
                ),
            )

            with(dao.getRows(id).single()) {
                assertEquals("the row is the same row", "row-1", this.id)
                assertEquals(LocalRowKind.RESOURCE.storedValue, kind)
                assertNull("a use of Bardic Inspiration does not weigh 4 lb", weight)
                assertNull("…nor cost 0.2 gp", value)
                assertNull("…nor describe itself as a melee weapon", description)
                assertEquals("…and above all cannot be worn", false, equipped)
                assertEquals(CatalogCategory.GEAR.storedValue, category)
            }
        }

    // --- L2: a dropped row takes its equippability override with it -------------

    /**
     * The editor's delete path, which `ON DELETE CASCADE` cannot reach.
     *
     * `LocalCharacterDao.save` deletes rows the form no longer carries, and 11 decision 2's
     * override is a DataStore key rather than a row — so nothing followed it until the 1.6.0
     * review. Row ids are UUIDs and never recur, which is exactly the argument the *character*
     * delete path already makes about `SelectedRollStore.localKey`: a key left behind is
     * unreachable **forever** rather than merely stale.
     *
     * Against the real store, for this file's stated reason: the claim is that a key stops
     * existing, and a fake map can only prove that the fake agrees with itself.
     */
    @Test
    fun `a row the form drops takes its equippability override with it`() = runTest {
        val id = saveOrFail(
            form(
                rows = listOf(
                    LocalRowForm(id = null, kind = LocalRowKind.ITEM, label = "A Small Knife", total = 1),
                    LocalRowForm(id = null, kind = LocalRowKind.ITEM, label = "Bedroll", total = 1),
                ),
            ),
        )
        val key = EquippableOverrideStore.localKey(id)
        val rows = dao.getRows(id).sortedBy { it.sortIndex }
        val knife = rows.first().id
        val bedroll = rows.last().id

        equippableOverrides.setOverridden(key, knife, overridden = true)
        equippableOverrides.setOverridden(key, bedroll, overridden = true)
        assertEquals(setOf(knife, bedroll), equippableOverrides.overrides(key).first())

        // The player opens the editor and deletes the knife row.
        val reopened = repository.formFor(id)!!
        saveOrFail(reopened.copy(rows = reopened.rows.filter { it.label != "A Small Knife" }))

        assertEquals(
            "the surviving row keeps its override; the deleted row's is unreachable and gone",
            setOf(bedroll),
            equippableOverrides.overrides(key).first(),
        )
    }

    @Test
    fun `dropping every row empties the override key rather than leaving it behind`() = runTest {
        // The `rows.isEmpty()` branch of `LocalCharacterDao.save` is a different statement
        // (`deleteAllRows`), so it is walked separately — and an emptied set must remove the key,
        // which is the store's own rule about a key that holds nothing.
        val id = saveOrFail(
            form(rows = listOf(LocalRowForm(id = null, kind = LocalRowKind.ITEM, label = "Bedroll", total = 1))),
        )
        val key = EquippableOverrideStore.localKey(id)
        val bedroll = dao.getRows(id).single().id
        equippableOverrides.setOverridden(key, bedroll, overridden = true)

        saveOrFail(repository.formFor(id)!!.copy(rows = emptyList()))

        assertTrue(equippableOverrides.overrides(key).first().isEmpty())
    }

    @Test
    fun `an ordinary edit leaves every override alone`() = runTest {
        // The guard against over-reaping: a save that drops no row must reap nothing. Row ids
        // survive a `formFor` round trip, so this is the case that would break if the reap were
        // computed from the form's ids rather than from the difference.
        val id = saveOrFail(
            form(rows = listOf(LocalRowForm(id = null, kind = LocalRowKind.ITEM, label = "Bedroll", total = 1))),
        )
        val key = EquippableOverrideStore.localKey(id)
        val bedroll = dao.getRows(id).single().id
        equippableOverrides.setOverridden(key, bedroll, overridden = true)

        saveOrFail(repository.formFor(id)!!.copy(name = "Brambles the Bold"))

        assertEquals(setOf(bedroll), equippableOverrides.overrides(key).first())
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
    fun `deleting takes the character's pane choice with it, and only that`() = runTest {
        val doomed = saveOrFail(form(name = "Bracken"))
        val survivor = saveOrFail(form(name = "Sorrel"))
        val serverCharacter = PaneLayoutStore.serverKey("acct-1", "creature-1")
        val chosen = setOf(PaneSurface.TRACKER, PaneSurface.INVENTORY)

        paneLayouts.setPanes(PaneLayoutStore.localKey(doomed), chosen)
        paneLayouts.setPanes(PaneLayoutStore.localKey(survivor), chosen)
        paneLayouts.setPanes(serverCharacter, chosen)

        repository.delete(doomed)

        // 14 decision 8's second reaping path — the fourth store to need one, and the same
        // argument each time: a DataStore key rather than a row, in the local namespace that
        // sign-out is forbidden to reach, keyed by a UUID that will never recur.
        assertTrue(paneLayouts.panes(PaneLayoutStore.localKey(doomed)).first().isEmpty())
        assertEquals(
            "another on-device character's panes are not this character's business",
            chosen,
            paneLayouts.panes(PaneLayoutStore.localKey(survivor)).first(),
        )
        assertEquals(
            "and a DiceCloud character's least of all",
            chosen,
            paneLayouts.panes(serverCharacter).first(),
        )
    }

    @Test
    fun `deleting takes the character's inventory layout with it, and only that`() = runTest {
        val doomed = saveOrFail(form(name = "Brambles"))
        val survivor = saveOrFail(form(name = "Thistle"))
        val serverCharacter = InventoryLayoutStore.serverKey("acct-1", "creature-1")
        val arrangement = listOf(
            InventoryLayoutEntry("equipped"),
            InventoryLayoutEntry("wallet", hidden = true),
        )

        inventoryLayouts.setLayout(InventoryLayoutStore.localKey(doomed), arrangement)
        inventoryLayouts.setLayout(InventoryLayoutStore.localKey(survivor), arrangement)
        inventoryLayouts.setLayout(serverCharacter, arrangement)

        repository.delete(doomed)

        // 12 decision 5's second reaping path — the third store to need one, and the same
        // argument each time: a DataStore key rather than a row, in the local namespace that
        // sign-out is forbidden to reach, keyed by a UUID that will never recur.
        assertTrue(
            inventoryLayouts.layout(InventoryLayoutStore.localKey(doomed)).first().isEmpty(),
        )
        assertEquals(
            "another on-device character's arrangement is not this character's business",
            arrangement,
            inventoryLayouts.layout(InventoryLayoutStore.localKey(survivor)).first(),
        )
        assertEquals(
            "and a DiceCloud character's least of all",
            arrangement,
            inventoryLayouts.layout(serverCharacter).first(),
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
