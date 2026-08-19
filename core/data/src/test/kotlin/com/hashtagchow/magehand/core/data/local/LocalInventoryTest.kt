package com.hashtagchow.magehand.core.data.local

import android.content.Context
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.LocalCharacterDao
import com.hashtagchow.magehand.core.data.db.LocalCharacterEntity
import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.CoinPurse
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.ItemCatalog
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.InventoryMoveTarget
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * The local half of FR-8 (docs/design/10-inventory.md decision 10): [LocalInventoryBoard]'s
 * shape and [LocalOpenCharacter]'s three new intents.
 *
 * Split the way `LocalTrackerBoardTest` and `LocalOpenCharacterTest` are split, and for the
 * reason stated there: the board is a **pure function** and is tested as one, with no database
 * and no coroutine scope; the intents are asserted **against Room**, because Room is where the
 * write actually lands and a board assertion could pass on a cache that never persisted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalInventoryTest {

    // -----------------------------------------------------------------------
    // The board — pure, no database
    // -----------------------------------------------------------------------

    private fun character(
        strength: Int = 10,
        coins: CoinPurse = CoinPurse.EMPTY,
    ) = LocalCharacter(
        id = "local-1",
        name = "Brambles",
        level = 3,
        abilities = AbilityScores(strength = strength),
        maxHp = 20,
        currentHp = 20,
        armorClass = 15,
        coins = coins,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun itemRow(
        id: String,
        label: String,
        quantity: Int = 1,
        weightLb: Double? = null,
        valueGp: Double? = null,
        equipped: Boolean = false,
        sortIndex: Int = 0,
    ) = LocalTrackerRow(
        id = id,
        characterId = "local-1",
        kind = LocalRowKind.ITEM,
        label = label,
        total = quantity,
        current = quantity,
        reset = null,
        sortIndex = sortIndex,
        weightLb = weightLb,
        valueGp = valueGp,
        equipped = equipped,
    )

    @Test
    fun `only item rows reach the inventory - slots and resources are the tracker's`() {
        val board = LocalInventoryBoard.build(
            character(),
            listOf(
                itemRow("i1", "Torch", quantity = 3),
                LocalTrackerRow("s1", "local-1", LocalRowKind.SLOT, "1st Level", 4, 4, null, 1),
                LocalTrackerRow("r1", "local-1", LocalRowKind.RESOURCE, "Rage", 3, 3, null, 2),
            ),
        )

        assertEquals(listOf("Torch"), board.carried.map { it.name })
        assertEquals(1, board.allItems.size)
    }

    @Test
    fun `equipped and carried split the same way the server board does`() {
        val board = LocalInventoryBoard.build(
            character(),
            listOf(
                itemRow("i1", "Quarterstaff", equipped = true),
                itemRow("i2", "Bedroll", sortIndex = 1),
            ),
        )

        assertEquals(listOf("Quarterstaff"), board.equipped.map { it.name })
        assertEquals(listOf("Bedroll"), board.carried.map { it.name })
    }

    /**
     * There is no local container concept, and that is a decision rather than a gap: 09
     * decision 8's "ONE mechanism" is a flat `sortIndex`, so there is no tree for a container
     * to be a node in. Wave B gets an absent section by the rule it already has for a sheet
     * with no containers.
     */
    @Test
    fun `a local character has no containers and no attunement`() {
        val board = LocalInventoryBoard.build(character(), listOf(itemRow("i1", "Torch")))

        assertTrue(board.containers.isEmpty())
        assertFalse(
            "the form captures no attunement, so a confident 0 of 3 would be an invention",
            board.hasAttunementData,
        )
        assertEquals(0, board.attunedCount)
    }

    @Test
    fun `the wallet is four rows from four columns, never absent`() {
        val board = LocalInventoryBoard.build(
            character(coins = CoinPurse(platinum = 1, gold = 109, silver = 57, copper = 351)),
            emptyList(),
        )

        assertEquals(4, board.wallet.rows.size)
        assertEquals(109, board.wallet.row(CoinKind.GOLD).quantity)
        assertEquals(0.0, board.wallet.totalGp - 128.21, 1e-9)
        assertTrue(
            "the column exists whether it reads zero or not, so there is no insert path",
            board.wallet.rows.none { it.isAbsent },
        )
    }

    @Test
    fun `a zero coin row is present with a real id rather than absent`() {
        val board = LocalInventoryBoard.build(character(), emptyList())
        val gold = board.wallet.row(CoinKind.GOLD)

        assertEquals(0, gold.quantity)
        assertFalse(gold.isAbsent)
        assertEquals(LocalInventoryBoard.walletRowId(CoinKind.GOLD), gold.propertyId)
        assertTrue(
            "a local id must never be mistakable for a Meteor one",
            gold.propertyId!!.startsWith(LocalInventoryBoard.WALLET_ROW_ID_PREFIX),
        )
    }

    @Test
    fun `carried weight sums the items and the coins`() {
        val board = LocalInventoryBoard.build(
            character(coins = CoinPurse(gold = 100)),
            listOf(
                itemRow("i1", "Rations", quantity = 5, weightLb = 2.0),
                itemRow("i2", "Quarterstaff", weightLb = 4.0, equipped = true, sortIndex = 1),
                itemRow("i3", "Lamp"), // no recorded weight
            ),
        )

        // 10 + 4 + 0, plus 100 coins at the 50-to-the-pound constant
        assertEquals(16.0, board.carriedWeightLb, 1e-9)
    }

    @Test
    fun `capacity is strength times fifteen and always renders`() {
        assertEquals(120, LocalInventoryBoard.build(character(strength = 8), emptyList()).capacityLb)
        assertEquals(
            "the form defaults every score, so a local character always has a Strength",
            10 * InventoryBoard.CAPACITY_PER_STRENGTH,
            LocalInventoryBoard.build(character(), emptyList()).capacityLb,
        )
    }

    @Test
    fun `a local item carries no tags, so it can never be pulled into the wallet`() {
        val board = LocalInventoryBoard.build(
            character(coins = CoinPurse(gold = 5)),
            listOf(itemRow("i1", "Gold piece", quantity = 99)),
        )

        assertEquals("the columns are the money, not the row", 5, board.wallet.row(CoinKind.GOLD).quantity)
        assertEquals(listOf("Gold piece"), board.carried.map { it.name })
    }

    @Test
    fun `a missing character renders an empty board`() {
        assertEquals(InventoryBoard.EMPTY, LocalInventoryBoard.build(null, listOf(itemRow("i1", "X"))))
    }

    @Test
    fun `rows keep the player's order`() {
        val board = LocalInventoryBoard.build(
            character(),
            listOf(itemRow("b", "Zither", sortIndex = 5), itemRow("a", "Anvil", sortIndex = 1)),
        )
        assertEquals(listOf("Anvil", "Zither"), board.carried.map { it.name })
    }

    // -----------------------------------------------------------------------
    // The intents — asserted against Room
    // -----------------------------------------------------------------------

    private val characterId = "local-1"
    private lateinit var database: MageHandDatabase
    private lateinit var dao: LocalCharacterDao
    private lateinit var scope: CoroutineScope
    private val clock = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.localCharacterDao()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** Join before closing — see `LocalOpenCharacterTest.tearDown` for why cancel is not enough. */
    @After
    fun tearDown() = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        database.close()
    }

    private suspend fun seed(coins: CoinPurse = CoinPurse.EMPTY, rows: List<LocalTrackerRowEntity> = emptyList()) {
        dao.save(
            LocalCharacterEntity(
                id = characterId,
                name = "Brambles",
                level = 3,
                strength = 10, dexterity = 12, constitution = 14,
                intelligence = 8, wisdom = 13, charisma = 16,
                maxHp = 20,
                currentHp = 20,
                armorClass = 15,
                pp = coins.platinum, gp = coins.gold, sp = coins.silver, cp = coins.copper,
                createdAt = 1,
                updatedAt = 1,
            ),
            rows,
        )
    }

    private fun itemEntity(id: String, label: String, quantity: Int = 1, equipped: Boolean = false) =
        LocalTrackerRowEntity(
            id = id,
            characterId = characterId,
            kind = LocalRowKind.ITEM.storedValue,
            label = label,
            total = quantity,
            current = quantity,
            resetRule = LocalTrackerRowEntity.RESET_NONE,
            sortIndex = 0,
            equipped = equipped,
        )

    private fun open(): LocalOpenCharacter = LocalOpenCharacter(characterId, dao, scope, now = { clock })

    /**
     * The board once Room has actually emitted it.
     *
     * `inventory.value` immediately after `open()` is [InventoryBoard.EMPTY] — the seed the
     * `stateIn` starts with, before the DAO's first emission arrives. Reading it eagerly is
     * how a test asserts against the placeholder and calls it a result; every assertion about
     * board *content* goes through here.
     *
     * Tests that only need a row's [CoinKind] can and do read `.value` directly: `adjustCoins`
     * identifies the column by [WalletRow.coin], not by the id, so the placeholder row is a
     * perfectly good handle for it — which is itself worth knowing about the local model.
     */
    private suspend fun LocalOpenCharacter.loadedInventory(): InventoryBoard =
        inventory.first { it != InventoryBoard.EMPTY }

    // --- equip --------------------------------------------------------------

    @Test
    fun `equip is a plain flag with no reparenting to lose`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Quarterstaff")))
        val character = open()

        character.setEquipped("i1", equipped = true, currentlyEquipped = false)
        character.awaitIdle()

        assertEquals(true, dao.findRow("i1")!!.equipped)
        assertEquals(TrackerWriteKind.EQUIP, character.writeHistory.value.single().kind)
        assertEquals("Quarterstaff", character.writeHistory.value.single().targetName)
    }

    /**
     * Unlike the server path — whose undo cannot restore the folder the server moved the item
     * out of — the local undo is **complete**. There is nothing to lose, so nothing is lost.
     */
    @Test
    fun `undoing an equip puts the flag back, completely`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Cloak", equipped = true)))
        val character = open()

        character.setEquipped("i1", equipped = false, currentlyEquipped = true)
        character.awaitIdle()
        assertEquals(false, dao.findRow("i1")!!.equipped)

        assertTrue(character.undoLastWrite())
        assertEquals(true, dao.findRow("i1")!!.equipped)
        assertTrue(character.writeHistory.value.single().undone)
    }

    /**
     * The state is re-read inside the write, not trusted from the caller — the same discipline
     * every clamp in `LocalOpenCharacter` follows. A composable's idea of the flag can be one
     * frame stale; the committed row cannot.
     */
    @Test
    fun `equipping something already equipped writes nothing and files nothing`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Cloak", equipped = true)))
        val character = open()

        // Deliberately lying about the current state, as a stale composable would.
        character.setEquipped("i1", equipped = true, currentlyEquipped = false)
        character.awaitIdle()

        assertTrue("no history entry for a change that did not happen", character.writeHistory.value.isEmpty())
        assertFalse(character.canUndo.value)
    }

    @Test
    fun `equipping a row that no longer exists is dropped rather than crashing`() = runTest {
        seed()
        val character = open()

        character.setEquipped("vanished", equipped = true, currentlyEquipped = false)
        character.awaitIdle()

        assertTrue(character.writeHistory.value.isEmpty())
    }

    // --- addItem ------------------------------------------------------------

    @Test
    fun `adding a catalog item stores every field it carries`() = runTest {
        seed()
        val character = open()

        character.addItem(NewItemSpec.of(ItemCatalog.byId("torch")!!, quantity = 4))
        character.awaitIdle()

        val row = dao.getRows(characterId).single()
        assertEquals("Torch", row.label)
        assertEquals(4, row.current)
        assertEquals("an item's quantity is both its value and its (absent) ceiling", 4, row.total)
        assertEquals(1.0, row.weight)
        assertEquals(0.01, row.value)
        assertNotNull(row.description)
        assertEquals(false, row.equipped)
        assertEquals(LocalRowKind.ITEM.storedValue, row.kind)
    }

    @Test
    fun `a custom item stores only what the player typed`() = runTest {
        seed()
        val character = open()

        character.addItem(NewItemSpec(name = "A curious key", quantity = 1))
        character.awaitIdle()

        val row = dao.getRows(characterId).single()
        assertEquals("A curious key", row.label)
        assertNull("a blank weight field is an absence, not a zero", row.weight)
        assertNull(row.value)
        assertNull(row.description)
    }

    @Test
    fun `a new item lands at the end of the list`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Rope").copy(sortIndex = 7)))
        val character = open()

        character.addItem(NewItemSpec(name = "Torch"))
        character.awaitIdle()

        assertEquals(8, dao.getRows(characterId).single { it.label == "Torch" }.sortIndex)
    }

    /**
     * **Not undoable**, and this implementation knows perfectly well how to reverse it — which
     * is exactly why it must not. Item deletion is fenced out of this release (10 decision 12),
     * and an UNDO that deleted a row would ship the fenced capability through the one door
     * nobody was watching. The server path reaches the same answer from the other direction.
     */
    @Test
    fun `adding an item is not undoable, matching the server path`() = runTest {
        seed()
        val character = open()

        character.addItem(NewItemSpec(name = "Torch"))
        character.awaitIdle()

        val entry = character.writeHistory.value.single()
        assertEquals(TrackerWriteKind.ITEM_CREATE, entry.kind)
        assertFalse("an UNDO here would be a delete, which FR-8 does not ship", entry.undoable)
        assertFalse(character.canUndo.value)
        assertFalse(character.undoLastWrite())
        assertEquals("and nothing was removed", 1, dao.getRows(characterId).size)
    }

    /**
     * An add invalidates nothing before it — the one way it differs from a rest, which clears
     * the stack because it rewrites every row.
     */
    @Test
    fun `an add leaves earlier writes undoable`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Rope", quantity = 5)))
        val character = open()

        character.adjustCoins(character.inventory.value.wallet.row(CoinKind.GOLD), 3)
        character.awaitIdle()
        character.addItem(NewItemSpec(name = "Torch"))
        character.awaitIdle()

        assertTrue("undoing a coin change after an add is still correct", character.canUndo.value)
        assertTrue(character.undoLastWrite())
        assertEquals(0, dao.find(characterId)!!.gp)
    }

    @Test
    fun `an invalid spec writes nothing`() = runTest {
        seed()
        val character = open()

        character.addItem(NewItemSpec(name = "   "))
        character.addItem(NewItemSpec(name = "Torch", quantity = 0))
        character.awaitIdle()

        assertTrue(dao.getRows(characterId).isEmpty())
        assertTrue(character.writeHistory.value.isEmpty())
    }

    // --- adjustCoins --------------------------------------------------------

    @Test
    fun `the wallet stepper moves one column and leaves the others alone`() = runTest {
        seed(coins = CoinPurse(platinum = 1, gold = 10, silver = 5, copper = 2))
        val character = open()

        character.adjustCoins(character.inventory.value.wallet.row(CoinKind.GOLD), 15)
        character.awaitIdle()

        with(dao.find(characterId)!!) {
            assertEquals(25, gp)
            assertEquals(1, pp)
            assertEquals(5, sp)
            assertEquals(2, cp)
        }
    }

    /**
     * Clamped against a **fresh read**, not against the row the caller was holding — the same
     * reason `spend` re-reads. A press-and-hold on "−" must stop at zero against committed
     * truth.
     */
    @Test
    fun `coins are floored at zero against committed state, not against a stale row`() = runTest {
        seed(coins = CoinPurse(gold = 3))
        val character = open()

        // A row claiming 999 gold, as a composable rendering a stale board would hold.
        val stale = character.inventory.value.wallet.row(CoinKind.GOLD).copy(quantity = 999)
        character.adjustCoins(stale, -50)
        character.awaitIdle()

        assertEquals(0, dao.find(characterId)!!.gp)
        assertEquals(
            "the history must say what happened, not what was asked for",
            3,
            character.writeHistory.value.single().amount,
        )
    }

    @Test
    fun `a decrement on an empty column does nothing at all`() = runTest {
        seed()
        val character = open()

        character.adjustCoins(character.inventory.value.wallet.row(CoinKind.SILVER), -1)
        character.awaitIdle()

        assertEquals(0, dao.find(characterId)!!.sp)
        assertTrue(character.writeHistory.value.isEmpty())
    }

    @Test
    fun `undoing a coin change restores the previous count exactly`() = runTest {
        seed(coins = CoinPurse(gold = 7))
        val character = open()

        character.adjustCoins(character.inventory.value.wallet.row(CoinKind.GOLD), -5)
        character.awaitIdle()
        assertEquals(2, dao.find(characterId)!!.gp)

        assertTrue(character.undoLastWrite())
        assertEquals(7, dao.find(characterId)!!.gp)
    }

    @Test
    fun `a local wallet row never takes the insert path`() = runTest {
        seed()
        val character = open()

        val row = character.loadedInventory().wallet.row(CoinKind.PLATINUM)
        assertFalse("the column exists; there is nothing to create", row.isAbsent)

        character.adjustCoins(row, 2)
        character.awaitIdle()

        assertEquals(2, dao.find(characterId)!!.pp)
        assertTrue("and no item row was created for it", dao.getRows(characterId).isEmpty())
    }

    // --- the board flow -----------------------------------------------------

    @Test
    fun `the inventory flow re-emits after a write`() = runTest {
        seed()
        val character = open()

        character.addItem(NewItemSpec(name = "Torch", quantity = 2, weightLb = 1.0))
        character.adjustCoins(character.loadedInventory().wallet.row(CoinKind.GOLD), 4)
        character.awaitIdle()

        assertEquals(2, dao.getRows(characterId).single().current)
        assertEquals(4, dao.find(characterId)!!.gp)

        // Room's invalidation is asynchronous, so the assertion is on the flow *arriving* at
        // the committed state rather than on whatever it holds at this instant.
        val board = character.inventory.first { it.carried.isNotEmpty() && it.wallet.totalGp > 0 }
        assertEquals(listOf("Torch"), board.carried.map { it.name })
        assertEquals(2, board.carried.single().quantity)
        assertEquals(4.0, board.wallet.totalGp, 1e-9)
        assertNotNull(board.capacityLb)
    }

    @Test
    fun `strength drives the capacity line on a local character too`() = runTest {
        seed()
        val character = open()
        assertEquals(
            AbilityScores().score(Ability.STR) * InventoryBoard.CAPACITY_PER_STRENGTH,
            character.loadedInventory().capacityLb,
        )
    }

    // --- removeItem / moveItem (FR-9, 12 decisions 7 and 8) -----------------

    /**
     * The local delete is a **real** delete: the row leaves `local_tracker_rows`.
     *
     * The server path soft-removes because that is the only deletion DiceCloud offers, and the
     * reversibility that falls out of it is a genuine gain. Copying the shape here would mean a
     * `removed` column, a filter on every local query and a tombstone the player can never see
     * — a schema migration bought to back one button. So: gone.
     */
    @Test
    fun `removing an item deletes the local row outright`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Torch"), itemEntity("i2", "Rope")))
        val character = open()

        character.removeItem("i1")
        character.awaitIdle()

        assertEquals(listOf("Rope"), dao.getRows(characterId).map { it.label })
    }

    /**
     * **Honestly not undoable** — the asymmetry decision 7 accepts, and the reason the confirm
     * dialog's copy differs by character kind (`InventoryRowState.deleteWarningRes`).
     *
     * The entry is still *filed*: "what did I do?" is answered either way, and the history
     * sheet is read for that as much as for its buttons. It simply offers no UNDO, which is
     * the shape `addItem` already established here.
     */
    @Test
    fun `a local delete is honestly not undoable`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Torch")))
        val character = open()

        character.removeItem("i1")
        character.awaitIdle()

        val entry = character.writeHistory.value.single()
        assertEquals(TrackerWriteKind.ITEM_DELETE, entry.kind)
        assertEquals("Torch", entry.targetName)
        assertFalse("there is no row left to restore", entry.undoable)
        assertFalse(character.canUndo.value)
        assertFalse(character.undoLastWrite())
        assertTrue("and nothing came back", dao.getRows(characterId).isEmpty())
    }

    /**
     * A delete is not a rest: it falsifies nothing above it, so an earlier spend stays
     * undoable. (Contrast `a rest is not undoable and invalidates everything above it`.)
     */
    @Test
    fun `a local delete leaves earlier writes undoable`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Torch", quantity = 3), itemEntity("i2", "Rope")))
        val character = open()

        character.adjustItem(
            TrackedResource(propertyId = "i1", kind = TrackerKind.ITEM, name = "Torch", value = 3, total = 3),
            -1,
        )
        character.awaitIdle()
        character.removeItem("i2")
        character.awaitIdle()

        assertTrue("deleting a rope says nothing about the torch you used", character.canUndo.value)
        assertTrue(character.undoLastWrite())
        assertEquals(3, dao.findRow("i1")!!.current)
    }

    /**
     * **Items only** — the gate LOW-1 was the absence of.
     *
     * `local_tracker_rows` holds slots, resources and items in one table, and `findRow` is
     * keyed on the id alone, so an id from the *tracker* board would otherwise reach the app's
     * one irreversible operation and destroy a spell-slot row. The two boards share an id
     * space (`TrackedResource.propertyId` == `InventoryItem.propertyId`), which is what makes
     * that reachable rather than hypothetical.
     *
     * Note the asymmetry this closes: the server path is gated twice over — its board lookup
     * holds only items, and its delete is a reversible `softRemove` — while this path is the
     * one place a mistake cannot be taken back.
     */
    @Test
    fun `local removeItem cannot reach a SLOT or RESOURCE row`() = runTest {
        seed(
            rows = listOf(
                itemEntity("i1", "Torch"),
                itemEntity("s1", "1st Level").copy(kind = LocalRowKind.SLOT.storedValue, total = 4, current = 4),
                itemEntity("r1", "Rage").copy(kind = LocalRowKind.RESOURCE.storedValue, total = 3, current = 3),
            ),
        )
        val character = open()

        character.removeItem("s1")
        character.removeItem("r1")
        character.awaitIdle()

        assertEquals(
            "the one irreversible op in the app must not be reachable from the tracker's ids",
            listOf("i1", "s1", "r1").sorted(),
            dao.getRows(characterId).map { it.id }.sorted(),
        )
        assertTrue("and nothing is filed as having happened", character.writeHistory.value.isEmpty())

        // The same call on the item beside them still works — this is a gate, not a wall.
        character.removeItem("i1")
        character.awaitIdle()
        assertEquals(listOf("r1", "s1"), dao.getRows(characterId).map { it.id }.sorted())
    }

    @Test
    fun `removing a row the character does not have does nothing`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Torch")))
        val character = open()

        character.removeItem("vanished")
        character.awaitIdle()

        assertEquals(1, dao.getRows(characterId).size)
        assertTrue(character.writeHistory.value.isEmpty())
    }

    /**
     * A no-op, by decision 8: local characters have no containers. The UI omits the control
     * entirely (`InventoryRowState.showsMoveControl` is false whenever `isLocal`), and this
     * pins that the second gate holds too — including that it does **not** quietly reinterpret
     * a move as a `sortIndex` reorder, which would be this class inventing a fenced feature.
     */
    @Test
    fun `moving an item locally does nothing at all`() = runTest {
        seed(rows = listOf(itemEntity("i1", "Torch").copy(sortIndex = 5)))
        val character = open()

        character.moveItem("i1", InventoryMoveTarget.Container("nowhere"))
        character.moveItem("i1", InventoryMoveTarget.Carried)
        character.awaitIdle()

        val row = dao.findRow("i1")!!
        assertEquals("the row is untouched — sortIndex included", 5, row.sortIndex)
        assertTrue("and nothing is filed as having happened", character.writeHistory.value.isEmpty())
        assertFalse(character.canUndo.value)
    }
}
