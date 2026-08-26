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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import com.hashtagchow.magehand.core.data.fake.FakeEquippableOverrideStore
import com.hashtagchow.magehand.core.data.fake.FakeInventoryLayoutStore
import com.hashtagchow.magehand.core.data.fake.FakePaneLayoutStore
import com.hashtagchow.magehand.core.data.fake.FakeSelectedRollStore
import com.hashtagchow.magehand.core.data.session.OpenCharacter
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.ExactQuantity
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWriteKind

/**
 * The intent surface of a local character (docs/design/09-local-characters.md decision 5).
 *
 * ### What is asserted, and against what
 *
 * The writes are asserted **against Room**, not against the board flow, for two reasons.
 * Room is where the write actually lands — a board assertion could pass on an in-memory cache
 * that never persisted — and the intents are `fun`, not `suspend fun`, so the only honest way
 * to observe "the write finished" is [LocalOpenCharacter.awaitIdle], which exists for exactly
 * this. Board *shape* is tested where it is built, in [LocalTrackerBoardTest]: it is a pure
 * function, and testing it through a coroutine scope would prove less, more slowly.
 *
 * The rows handed to `spend`/`restore`/`adjustItem` below are deliberately **stale** — built
 * by hand with the wrong `value`. That is not laziness: it pins the claim that the clamps
 * read committed state inside the write rather than trusting the row the composable happened
 * to be rendering, which is what makes a press-and-hold correct.
 *
 * Real dispatchers and a real scope, not a `TestScope`: the ordering guarantee under test
 * ([LocalOpenCharacter.dispatch]'s undispatched start plus the mutex) is a claim about real
 * concurrency, and a single-threaded virtual scheduler would make it pass for free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalOpenCharacterTest {

    private val characterId = "local-1"

    private lateinit var database: MageHandDatabase
    private lateinit var dao: LocalCharacterDao
    private lateinit var scope: CoroutineScope
    private var clock = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.localCharacterDao()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /**
     * Join before closing, not just cancel.
     *
     * The board is a Room flow collected on [scope]; cancelling is a *request*, and closing
     * the database while an invalidation collector is still unwinding throws on a background
     * thread — which surfaces as an "uncaught exception before the test started" against
     * whichever test happens to run next. `cancelAndJoin` is the same guarantee
     * [LocalOpenCharacter.close] gives, for the same reason.
     */
    @After
    fun tearDown() = runBlocking {
        scope.coroutineContext.job.cancelAndJoin()
        database.close()
    }

    private suspend fun seed(
        maxHp: Int = 20,
        currentHp: Int = maxHp,
        rows: List<LocalTrackerRowEntity> = emptyList(),
        deathSuccesses: Int = 0,
        deathFailures: Int = 0,
    ) {
        dao.save(
            LocalCharacterEntity(
                id = characterId,
                name = "Brambles",
                level = 3,
                strength = 10, dexterity = 12, constitution = 14,
                intelligence = 8, wisdom = 13, charisma = 16,
                maxHp = maxHp,
                currentHp = currentHp,
                armorClass = 15,
                deathSuccesses = deathSuccesses,
                deathFailures = deathFailures,
                createdAt = 1,
                updatedAt = 1,
            ),
            rows,
        )
    }

    private fun rowEntity(
        id: String,
        kind: LocalRowKind = LocalRowKind.RESOURCE,
        total: Int = 4,
        current: Int = total,
        reset: ResetRule? = null,
        sortIndex: Int = 0,
    ) = LocalTrackerRowEntity(
        id = id,
        characterId = characterId,
        kind = kind.storedValue,
        label = "row-$id",
        total = total,
        current = current,
        resetRule = reset?.wireValue ?: LocalTrackerRowEntity.RESET_NONE,
        sortIndex = sortIndex,
    )

    /** A row handle as a composable would hold it — see the class KDoc on staleness. */
    private fun handle(id: String, kind: TrackerKind = TrackerKind.RESOURCE) = TrackedResource(
        propertyId = id,
        kind = kind,
        name = "row-$id",
        value = 999,
        total = 999,
    )

    /** Shared, so a test can assert what a delete did to it. */
    private val equippableOverrides = FakeEquippableOverrideStore()

    private fun open(): LocalOpenCharacter =
        LocalOpenCharacter(characterId, dao, equippableOverrides, scope, now = { clock })

    // --- posture ------------------------------------------------------------

    @Test
    fun `it is an OpenCharacter, so the tracker screen needs no fork`() = runTest {
        seed()
        val character: OpenCharacter = open()

        assertEquals(characterId, character.creatureId)
        assertEquals("no sentinel account", LocalOpenCharacter.NO_ACCOUNT, character.accountId)
        assertEquals("no server to point a WebView at", "", character.serverOrigin)
    }

    @Test
    fun `connection is a constant that never reports a problem and writes are never gated`() =
        runTest {
            seed()
            val character = open()

            assertEquals(ConnectionState.LIVE, character.connectionState.value)
            assertTrue(character.canWrite.value)
            assertNull("nothing was ever synced", character.lastSyncedAt.value)
            assertFalse("Room is the source, not a cached copy", character.isShowingSnapshot.value)
            assertNull("theme_prefs is account-keyed", character.accentColor.value)
            assertFalse("nothing to capture", character.captureSnapshot())
        }

    @Test
    fun `there is no hide layer, so both boards are the same object`() = runTest {
        seed()
        val character = open()
        assertSame(character.board, character.boardIgnoringHidden)
    }

    @Test
    fun `close is idempotent`() = runTest {
        seed()
        val character = open()

        character.close()
        character.close()

        assertTrue(character.isClosed.value)
    }

    // --- spend / restore clamping -------------------------------------------

    @Test
    fun `spend reduces the row and stops at zero`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 4, current = 4)))
        val character = open()

        character.spend(handle("r-1"), amount = 3)
        character.awaitIdle()
        assertEquals(1, dao.findRow("r-1")?.current)

        // Asks for five more than exist: clamps to what is left rather than going negative.
        character.spend(handle("r-1"), amount = 5)
        character.awaitIdle()
        assertEquals(0, dao.findRow("r-1")?.current)

        // Spending an empty row is a no-op, not a negative.
        character.spend(handle("r-1"), amount = 1)
        character.awaitIdle()
        assertEquals(0, dao.findRow("r-1")?.current)
    }

    @Test
    fun `restore refills the row and stops at total`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 4, current = 1)))
        val character = open()

        character.restore(handle("r-1"), amount = 10)
        character.awaitIdle()

        assertEquals("never above total", 4, dao.findRow("r-1")?.current)
    }

    @Test
    fun `non-positive amounts are ignored on both directions`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 4, current = 2)))
        val character = open()

        character.spend(handle("r-1"), amount = 0)
        character.spend(handle("r-1"), amount = -3)
        character.restore(handle("r-1"), amount = 0)
        character.restore(handle("r-1"), amount = -3)
        character.awaitIdle()

        assertEquals(2, dao.findRow("r-1")?.current)
    }

    @Test
    fun `a burst of taps spends every one of them`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 6, current = 6)))
        val character = open()

        repeat(5) { character.spend(handle("r-1"), amount = 1) }
        character.awaitIdle()

        // Nothing coalesces here — five taps are five Room writes — and nothing races: the
        // undispatched start plus the mutex make the reads see each other's writes.
        assertEquals(1, dao.findRow("r-1")?.current)
        assertEquals(5, character.writeHistory.value.size)
    }

    @Test
    fun `writing to a row that does not exist is a no-op`() = runTest {
        seed(rows = listOf(rowEntity("r-1")))
        val character = open()

        character.spend(handle("ghost"), amount = 1)
        character.adjustItem(handle("ghost", TrackerKind.ITEM), delta = 1)
        character.awaitIdle()

        assertNull(dao.findRow("ghost"))
        assertTrue("nothing happened, so nothing to undo", character.writeHistory.value.isEmpty())
    }

    // --- hit points ---------------------------------------------------------

    @Test
    fun `hit points clamp between zero and max`() = runTest {
        seed(maxHp = 20, currentHp = 20)
        val character = open()

        character.changeHitPoints(-50)
        character.awaitIdle()
        assertEquals("damage stops at zero", 0, dao.find(characterId)?.currentHp)

        character.changeHitPoints(500)
        character.awaitIdle()
        assertEquals("healing stops at max", 20, dao.find(characterId)?.currentHp)
    }

    @Test
    fun `setting hit points clamps and a no-change set writes nothing`() = runTest {
        seed(maxHp = 20, currentHp = 20)
        val character = open()

        character.setHitPoints(7)
        character.awaitIdle()
        assertEquals(7, dao.find(characterId)?.currentHp)

        character.setHitPoints(99)
        character.awaitIdle()
        assertEquals(20, dao.find(characterId)?.currentHp)

        character.setHitPoints(-5)
        character.awaitIdle()
        assertEquals(0, dao.find(characterId)?.currentHp)

        val before = character.writeHistory.value.size
        character.setHitPoints(0)
        character.awaitIdle()
        assertEquals("a set to the value it already has is not a write", before, character.writeHistory.value.size)
    }

    /**
     * The history records what happened, not what was asked for. Taking 40 damage at 12 HP is
     * 12 damage; an entry saying 40 would offer an undo that heals past the maximum.
     */
    @Test
    fun `a clamped hit point change is journalled at the amount that actually landed`() = runTest {
        seed(maxHp = 20, currentHp = 12)
        val character = open()

        character.changeHitPoints(-40)
        character.awaitIdle()

        with(character.writeHistory.value.first()) {
            assertEquals(TrackerWriteKind.TAKE_DAMAGE, kind)
            assertEquals(12, amount)
        }
    }

    // --- items --------------------------------------------------------------

    @Test
    fun `item quantities are unbounded above and floored at zero`() = runTest {
        seed(rows = listOf(rowEntity("i-1", kind = LocalRowKind.ITEM, total = 2, current = 2)))
        val character = open()
        val item = handle("i-1", TrackerKind.ITEM)

        character.adjustItem(item, delta = 40)
        character.awaitIdle()
        with(dao.findRow("i-1")!!) {
            assertEquals("an item has no ceiling, exactly as on the server path", 42, current)
            assertEquals(42, total)
        }

        character.adjustItem(item, delta = -100)
        character.awaitIdle()
        assertEquals(0, dao.findRow("i-1")?.current)

        character.adjustItem(item, delta = -1)
        character.awaitIdle()
        assertEquals("an empty item cannot go negative", 0, dao.findRow("i-1")?.current)
    }

    @Test
    fun `a zero item adjustment is ignored`() = runTest {
        seed(rows = listOf(rowEntity("i-1", kind = LocalRowKind.ITEM, total = 2, current = 2)))
        val character = open()

        character.adjustItem(handle("i-1", TrackerKind.ITEM), delta = 0)
        character.awaitIdle()

        assertTrue(character.writeHistory.value.isEmpty())
    }

    // --- FR-22 direct entry (15 decisions 5-7) -------------------------------

    /**
     * The absolute overload lands the typed number, floored at zero and with no ceiling.
     *
     * The row handle is deliberately stale (see the class KDoc) — 999/999 — which is the point:
     * an absolute owes the caller's frame nothing, where a delta computed from it would be
     * nonsense. That is the same property `DefaultOpenCharacter`'s barrier buys on the server
     * path, arrived at here for free because every local write re-reads inside its own lock.
     */
    @Test
    fun `a direct entry sets an item quantity outright`() = runTest {
        seed(rows = listOf(rowEntity("i-1", kind = LocalRowKind.ITEM, total = 2, current = 2)))
        val character = open()

        character.adjustItem(handle("i-1", TrackerKind.ITEM), ExactQuantity(17))
        character.awaitIdle()

        with(dao.findRow("i-1")!!) {
            assertEquals(17, current)
            assertEquals("an item's total tracks its quantity", 17, total)
        }
        assertEquals(TrackerWriteKind.ITEM_SET, character.writeHistory.value.first().kind)
    }

    @Test
    fun `a direct entry below zero is floored`() = runTest {
        seed(rows = listOf(rowEntity("i-1", kind = LocalRowKind.ITEM, total = 2, current = 2)))
        val character = open()

        character.adjustItem(handle("i-1", TrackerKind.ITEM), ExactQuantity(-5))
        character.awaitIdle()

        assertEquals(0, dao.findRow("i-1")?.current)
    }

    /** Setting a row to what it already reads is genuinely nothing, locally — see the KDoc. */
    @Test
    fun `a direct entry matching the stored quantity writes nothing`() = runTest {
        seed(rows = listOf(rowEntity("i-1", kind = LocalRowKind.ITEM, total = 2, current = 2)))
        val character = open()

        character.adjustItem(handle("i-1", TrackerKind.ITEM), ExactQuantity(2))
        character.awaitIdle()

        assertTrue(character.writeHistory.value.isEmpty())
    }

    /**
     * The wallet's absolute path. No insert branch and no head stack: locally a denomination is
     * an integer column, which is the whole of what `LocalOpenCharacter.adjustCoins` documents.
     */
    @Test
    fun `a direct entry sets a coin column outright and is undoable`() = runTest {
        seed()
        val character = open()
        character.adjustCoins(character.inventory.value.wallet.row(CoinKind.GOLD), +7)
        character.awaitIdle()

        character.adjustCoins(character.inventory.value.wallet.row(CoinKind.GOLD), ExactQuantity(120))
        character.awaitIdle()
        assertEquals(120, dao.find(characterId)?.gp)

        character.undoLastWrite()
        character.awaitIdle()
        assertEquals("the undo restores the count the set replaced", 7, dao.find(characterId)?.gp)
    }

    // --- FR-23 death saves (15 decisions 13 and 20) --------------------------

    /** Both columns in one write, clamped to the three pips a row can show. */
    @Test
    fun `setDeathSaves writes both columns and clamps them`() = runTest {
        seed(currentHp = 0)
        val character = open()

        character.setDeathSaves(successes = 9, failures = 2)
        character.awaitIdle()

        with(dao.find(characterId)!!) {
            assertEquals(3, deathSuccesses)
            assertEquals(2, deathFailures)
        }
    }

    /** Decision 13's parity: the marks reach the board the tracker renders. */
    @Test
    fun `death save marks reach the board with stable synthetic ids`() = runTest {
        seed(currentHp = 0, deathSuccesses = 1, deathFailures = 2)
        val character = open()

        val saves = character.board.first { it.deathSaves != null }.deathSaves!!
        assertEquals(1, saves.successes)
        assertEquals(2, saves.failures)
        assertEquals(LocalTrackerBoard.DEATH_SUCCESS_ROW_ID, saves.successesPropertyId)
        assertEquals(LocalTrackerBoard.DEATH_FAILURE_ROW_ID, saves.failuresPropertyId)
    }

    /** The pair is undone as a pair — half an undo is a state no tap could have produced. */
    @Test
    fun `undoing a death save write restores both counts`() = runTest {
        seed(currentHp = 0, deathSuccesses = 1, deathFailures = 1)
        val character = open()

        character.setDeathSaves(successes = 3, failures = 0)
        character.awaitIdle()
        character.undoLastWrite()
        character.awaitIdle()

        with(dao.find(characterId)!!) {
            assertEquals(1, deathSuccesses)
            assertEquals(1, deathFailures)
        }
    }

    /**
     * **Decision 20 locally**: the clear rides on a heal that takes hit points off zero.
     *
     * Decision 13's *"local rest clears them on any heal above 0"* resolves to this — a local
     * `rest` does not touch `currentHp` at all (09 decision 7), so whatever heals is what clears.
     */
    @Test
    fun `healing off zero clears both death save columns`() = runTest {
        seed(currentHp = 0, deathSuccesses = 2, deathFailures = 1)
        val character = open()

        character.changeHitPoints(+6)
        character.awaitIdle()

        with(dao.find(characterId)!!) {
            assertEquals(6, currentHp)
            assertEquals(0, deathSuccesses)
            assertEquals(0, deathFailures)
        }
    }

    /** FR-22's direct entry on HP is a heal too, and clears by the same path. */
    @Test
    fun `a direct entry taking hit points off zero clears the marks`() = runTest {
        seed(currentHp = 0, deathSuccesses = 0, deathFailures = 3)
        val character = open()

        character.setHitPoints(12)
        character.awaitIdle()

        assertEquals(0, dao.find(characterId)?.deathFailures)
    }

    /** A heal that did not start at zero leaves them alone; so does damage. */
    @Test
    fun `only a heal off zero clears the marks`() = runTest {
        seed(maxHp = 20, currentHp = 8, deathSuccesses = 1, deathFailures = 1)
        val character = open()

        character.changeHitPoints(+5)
        character.awaitIdle()
        assertEquals("nothing to clear — the character was never down", 1, dao.find(characterId)?.deathFailures)

        character.changeHitPoints(-13)
        character.awaitIdle()
        assertEquals(0, dao.find(characterId)?.currentHp)
        assertEquals("damage is not a heal", 1, dao.find(characterId)?.deathFailures)
    }

    // --- rest (09 decision 7) -----------------------------------------------

    private fun restRows() = listOf(
        rowEntity("short-1", total = 4, current = 0, reset = ResetRule.SHORT_REST, sortIndex = 0),
        rowEntity("long-1", total = 3, current = 0, reset = ResetRule.LONG_REST, sortIndex = 1),
        rowEntity("none-1", total = 2, current = 0, reset = null, sortIndex = 2),
    )

    @Test
    fun `a short rest refills short-rest rows only`() = runTest {
        seed(rows = restRows())
        val character = open()

        character.rest(RestKind.SHORT)
        character.awaitIdle()

        assertEquals(4, dao.findRow("short-1")?.current)
        assertEquals(0, dao.findRow("long-1")?.current)
        assertEquals(0, dao.findRow("none-1")?.current)
        assertEquals(TrackerWriteKind.SHORT_REST, character.writeHistory.value.first().kind)
    }

    @Test
    fun `a long rest refills short and long rows and never a none row`() = runTest {
        seed(rows = restRows())
        val character = open()

        character.rest(RestKind.LONG)
        character.awaitIdle()

        assertEquals(4, dao.findRow("short-1")?.current)
        assertEquals(3, dao.findRow("long-1")?.current)
        assertEquals("a no-reset row survives every rest", 0, dao.findRow("none-1")?.current)
        assertEquals(TrackerWriteKind.LONG_REST, character.writeHistory.value.first().kind)
    }

    /** A rest does not touch hit points — 09 says nothing about it, so nothing invents it. */
    @Test
    fun `a rest leaves hit points where they were`() = runTest {
        seed(maxHp = 20, currentHp = 4, rows = restRows())
        val character = open()

        character.rest(RestKind.LONG)
        character.awaitIdle()

        assertEquals(4, dao.find(characterId)?.currentHp)
    }

    // --- undo (09 decision 5) -----------------------------------------------

    @Test
    fun `undo restores the exact value the write replaced`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 4, current = 3)))
        val character = open()

        character.spend(handle("r-1"), amount = 2)
        character.awaitIdle()
        assertEquals(1, dao.findRow("r-1")?.current)
        assertTrue(character.canUndo.first { it })

        assertTrue(character.undoLastWrite())

        assertEquals(3, dao.findRow("r-1")?.current)
        with(character.writeHistory.value.first()) {
            assertTrue(undone)
            assertFalse(undoable)
        }
    }

    @Test
    fun `undo walks back through the stack newest first`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 5, current = 5)))
        val character = open()

        character.spend(handle("r-1"), amount = 1)
        character.awaitIdle()
        character.spend(handle("r-1"), amount = 2)
        character.awaitIdle()
        assertEquals(2, dao.findRow("r-1")?.current)

        assertTrue(character.undoLastWrite())
        assertEquals(4, dao.findRow("r-1")?.current)

        assertTrue(character.undoLastWrite())
        assertEquals(5, dao.findRow("r-1")?.current)

        assertFalse("nothing left to undo", character.undoLastWrite())
    }

    @Test
    fun `hit point writes undo too, including an absolute set`() = runTest {
        seed(maxHp = 20, currentHp = 20)
        val character = open()

        character.setHitPoints(3)
        character.awaitIdle()

        assertTrue(character.undoLastWrite())
        assertEquals(20, dao.find(characterId)?.currentHp)
    }

    @Test
    fun `an item adjustment undoes to its previous quantity`() = runTest {
        seed(rows = listOf(rowEntity("i-1", kind = LocalRowKind.ITEM, total = 3, current = 3)))
        val character = open()

        character.adjustItem(handle("i-1", TrackerKind.ITEM), delta = -1)
        character.awaitIdle()
        assertEquals(2, dao.findRow("i-1")?.current)

        assertTrue(character.undoLastWrite())
        with(dao.findRow("i-1")!!) {
            assertEquals(3, current)
            assertEquals(3, total)
        }
    }

    // --- undo across a form edit --------------------------------------------
    //
    // 09 decision 4 makes re-opening the form the editor, so a row's total and the character's
    // max HP can *move* between a write and its undo. The stored "previous absolute value" is
    // then a value from a sheet that no longer exists, and restoring it unclamped puts the
    // board into a state — "4 / 2" — that every clamp in `LocalOpenCharacter` exists to rule
    // out. The edits below go through `LocalCharacterRepository.save`, because that is the
    // only way a player can cause this.

    @Test
    fun `undo cannot restore a row above a total the form has since lowered`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 4, current = 4)))
        val character = open()

        character.spend(handle("r-1"), amount = 3)
        character.awaitIdle()
        assertEquals(1, dao.findRow("r-1")?.current)

        // The player edits the sheet: this row is a 2-charge row now.
        val repository = LocalCharacterRepository(dao, FakeSelectedRollStore(), FakeEquippableOverrideStore(), FakeInventoryLayoutStore(), FakePaneLayoutStore(), now = { clock })
        val form = repository.formFor(characterId)!!
        repository.save(form.copy(rows = form.rows.map { it.copy(total = 2) }))

        assertTrue(character.undoLastWrite())

        with(dao.findRow("r-1")!!) {
            assertEquals("restored to the new ceiling, not the old value", 2, current)
            assertEquals(2, total)
        }
    }

    @Test
    fun `undo cannot heal past a max HP the form has since lowered`() = runTest {
        seed(maxHp = 20, currentHp = 20)
        val character = open()

        character.changeHitPoints(-8)
        character.awaitIdle()
        assertEquals(12, dao.find(characterId)?.currentHp)

        val repository = LocalCharacterRepository(dao, FakeSelectedRollStore(), FakeEquippableOverrideStore(), FakeInventoryLayoutStore(), FakePaneLayoutStore(), now = { clock })
        val form = repository.formFor(characterId)!!
        repository.save(form.copy(maxHp = 10))

        assertTrue(character.undoLastWrite())

        assertEquals(10, dao.find(characterId)?.currentHp)
    }

    @Test
    fun `undo of a write to a row the form deleted answers, and writes nothing`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 4, current = 4)))
        val character = open()

        character.spend(handle("r-1"), amount = 1)
        character.awaitIdle()

        val repository = LocalCharacterRepository(dao, FakeSelectedRollStore(), FakeEquippableOverrideStore(), FakeInventoryLayoutStore(), FakePaneLayoutStore(), now = { clock })
        val form = repository.formFor(characterId)!!
        repository.save(form.copy(rows = emptyList()))

        // True, and the entry stops offering UNDO: the request was answered as fully as it
        // can be. Leaving it undoable would be a button that does nothing, forever.
        assertTrue(character.undoLastWrite())
        assertNull("the row stays deleted", dao.findRow("r-1"))
        with(character.writeHistory.value.first()) {
            assertTrue(undone)
            assertFalse(undoable)
        }
    }

    /**
     * The same rule the server path states on [com.hashtagchow.magehand.core.model.TrackerWrite]:
     * undoing a spend after a rest would apply damage to a row the rest already refilled.
     */
    @Test
    fun `a rest is not undoable and invalidates everything above it`() = runTest {
        seed(rows = listOf(rowEntity("r-1", total = 4, current = 4, reset = ResetRule.SHORT_REST)))
        val character = open()

        character.spend(handle("r-1"), amount = 3)
        character.awaitIdle()
        character.rest(RestKind.SHORT)
        character.awaitIdle()

        assertFalse(character.canUndo.value)
        assertFalse(character.undoLastWrite())
        assertTrue(character.writeHistory.value.none { it.undoable })
        assertEquals("the rest itself stands", 4, dao.findRow("r-1")?.current)
    }

    @Test
    fun `history is newest first and never reports a failure`() = runTest {
        seed(maxHp = 20, currentHp = 20, rows = listOf(rowEntity("r-1", total = 4, current = 4)))
        val character = open()

        character.spend(handle("r-1"), amount = 1)
        character.awaitIdle()
        character.changeHitPoints(-2)
        character.awaitIdle()

        assertEquals(
            listOf(TrackerWriteKind.TAKE_DAMAGE, TrackerWriteKind.SPEND),
            character.writeHistory.value.map { it.kind },
        )
        assertEquals(clock, character.writeHistory.value.first().at)
    }

    // --- customize sheet (09 decision 8) ------------------------------------

    @Test
    fun `setting all overrides reorders the rows in one go`() = runTest {
        seed(
            rows = listOf(
                rowEntity("r-1", sortIndex = 0),
                rowEntity("r-2", sortIndex = 1),
                rowEntity("r-3", sortIndex = 2),
            ),
        )
        val character = open()

        character.setOverrides(
            listOf(
                TrackerOverride("r-3", sortIndex = 0),
                TrackerOverride("r-1", sortIndex = 1),
                TrackerOverride("r-2", sortIndex = 2),
            ),
        )

        assertEquals(listOf("r-3", "r-1", "r-2"), dao.getRows(characterId).map { it.id })
    }

    @Test
    fun `pin and hide are ignored because a local row has no meaning for either`() = runTest {
        seed(rows = listOf(rowEntity("r-1", sortIndex = 0), rowEntity("r-2", sortIndex = 1)))
        val character = open()

        character.setOverride(TrackerOverride("r-2", pinned = true, hidden = true, sortIndex = 0))
        character.clearOverride("r-1")

        // The reorder landed; nothing disappeared and nothing grew a second override layer.
        assertEquals(0, dao.findRow("r-2")?.sortIndex)
        assertEquals(2, dao.getRows(characterId).size)
    }

    @Test
    fun `an override with no sort index changes nothing`() = runTest {
        seed(rows = listOf(rowEntity("r-1", sortIndex = 7)))
        val character = open()

        character.setOverride(TrackerOverride("r-1", pinned = true))

        assertEquals(7, dao.findRow("r-1")?.sortIndex)
    }

    // --- factory ------------------------------------------------------------

    @Test
    fun `the factory refuses an id that names no local character`() = runTest {
        seed()
        val factory = LocalOpenCharacterFactory(dao, FakeEquippableOverrideStore())

        assertNull(factory.open("nope"))
        val opened = factory.open(characterId)
        assertEquals(characterId, opened?.creatureId)
        opened?.close()
    }
}
