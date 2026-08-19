package com.hashtagchow.magehand.ui.screens.local

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import com.hashtagchow.magehand.core.data.db.LocalCharacterDao
import com.hashtagchow.magehand.core.data.db.LocalCharacterEntity
import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
import com.hashtagchow.magehand.core.data.local.LocalOpenCharacter
import com.hashtagchow.magehand.core.data.local.LocalOpenCharacterFactory
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore

/**
 * Who closes the `LocalOpenCharacter`, when the screen is popped mid-open.
 *
 * ### The leak this is about
 *
 * `LocalOpenCharacterFactory.open` suspends on a Room read, so a view model can be cleared
 * *between* that read resolving and the character being published. The two participants used
 * to be a `@Volatile` flag and a `MutableStateFlow`, read and written independently, and the
 * interleaving that costs is: the init coroutine reads `cleared == false`; `onCleared` then
 * sets the flag and reads a still-null `open.value`, so it closes nothing; the init coroutine
 * publishes. Neither side closed the character, and a `LocalOpenCharacter` nobody holds keeps
 * its private scope and two Room collectors for the life of the process.
 *
 * ### What is and is not asserted here
 *
 * The **race itself is not reproducible in a unit test** — it needs two threads to be
 * suspended at chosen instructions, and asserting it by hammering would buy a flaky test that
 * passes for the wrong reason. What *is* deterministic, and what a future edit would actually
 * break, is the contract the two halves keep: run them in either order and **exactly one hands
 * the character back to be closed**. Both orders are run below; the lock is what makes the
 * real interleaving reduce to one of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalCharacterHomeLifecycleTest {

    private val characterId = "local-1"
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun viewModel(): LocalCharacterHomeViewModel {
        val dao = EmptyLocalCharacterDao
        return LocalCharacterHomeViewModel(
            savedStateHandle = SavedStateHandle(mapOf("characterId" to characterId)),
            repository = LocalCharacterRepository(dao, FakeSelectedRollStore),
            appSettingsStore = FakeAppSettingsStore,
            selectedRollStore = FakeSelectedRollStore,
            factory = LocalOpenCharacterFactory(dao),
        )
    }

    /** A character built directly, so the test owns both ends of the hand-off. */
    private fun character() =
        LocalOpenCharacter(characterId, EmptyLocalCharacterDao, scope)

    @Test
    fun `a character adopted before the screen is popped is handed back by onCleared`() = runTest {
        val vm = viewModel()
        val opened = character()

        // The ordinary order: the open resolves first, the user leaves second.
        assertNull("adopted, so nothing to close here", vm.adoptOrOrphan(opened))
        assertSame("onCleared owns it now", opened, vm.markCleared())
    }

    @Test
    fun `a character that resolves after the screen is popped is handed straight back`() = runTest {
        val vm = viewModel()
        val opened = character()

        // The leaking order: the user leaves while `factory.open` is still on the disk read.
        assertNull("nothing had been adopted yet", vm.markCleared())
        assertSame(
            "the late arrival must be handed back, not published to a screen that is gone",
            opened,
            vm.adoptOrOrphan(opened),
        )
    }

    @Test
    fun `a cleared view model never adopts, however many characters arrive late`() = runTest {
        val vm = viewModel()

        vm.markCleared()

        // Not a loop for its own sake: it says the flag is a one-way latch rather than a
        // token the first late arrival consumes.
        repeat(2) {
            val opened = character()
            assertSame(opened, vm.adoptOrOrphan(opened))
        }
        // …and a factory that found no character still says "nothing to close".
        assertNull(vm.adoptOrOrphan(null))
    }
}

/**
 * Enough `LocalCharacterDao` to construct the view model and nothing more.
 *
 * The subject here is a hand-off between two callbacks, not a query: every read answers
 * "no such character", which is the cheapest honest answer, and every write throws rather
 * than silently pretending — a test that reached one would be testing something else.
 */
private object EmptyLocalCharacterDao : LocalCharacterDao {
    override fun observeAll(): Flow<List<LocalCharacterEntity>> = flowOf(emptyList())
    override suspend fun getAll(): List<LocalCharacterEntity> = emptyList()
    override fun observe(id: String): Flow<LocalCharacterEntity?> = flowOf(null)
    override suspend fun find(id: String): LocalCharacterEntity? = null
    override suspend fun count(): Int = 0
    override fun observeRows(characterId: String): Flow<List<LocalTrackerRowEntity>> =
        flowOf(emptyList())

    override suspend fun getRows(characterId: String): List<LocalTrackerRowEntity> = emptyList()
    override suspend fun findRow(rowId: String): LocalTrackerRowEntity? = null

    override suspend fun upsert(character: LocalCharacterEntity) = unreachable()
    override suspend fun upsertRows(rows: List<LocalTrackerRowEntity>) = unreachable()
    override suspend fun delete(id: String) = unreachable()
    override suspend fun deleteRowsMissing(characterId: String, keep: List<String>) = unreachable()
    override suspend fun deleteAllRows(characterId: String) = unreachable()
    override suspend fun setCurrentHp(id: String, currentHp: Int, at: Long) = unreachable()
    override suspend fun setRowCurrent(rowId: String, current: Int) = unreachable()
    override suspend fun setRowQuantity(rowId: String, quantity: Int) = unreachable()
    override suspend fun touch(id: String, at: Long) = unreachable()
    override suspend fun setRowSortIndex(characterId: String, rowId: String, sortIndex: Int) =
        unreachable()

    // FR-8's three. `maxSortIndex` is a read, so it answers like the other reads rather than
    // throwing: "no rows" is the honest answer from a DAO that has none.
    override suspend fun setCoins(id: String, pp: Int, gp: Int, sp: Int, cp: Int, at: Long) =
        unreachable()

    override suspend fun setRowEquipped(rowId: String, equipped: Boolean) = unreachable()
    override suspend fun maxSortIndex(characterId: String): Int? = null

    override suspend fun refillRows(characterId: String, rules: List<String>) = unreachable()

    private fun unreachable(): Nothing =
        throw UnsupportedOperationException("LocalCharacterHomeLifecycleTest writes nothing")
}

/** FR-7's store, as a constant; this class is about the open/close hand-off, not the picker. */
private object FakeSelectedRollStore : SelectedRollStore {
    override fun selectedRollId(characterKey: String): Flow<String?> = flowOf(null)
    override suspend fun setSelectedRollId(characterKey: String, rollId: String?) = Unit
    override suspend fun deleteForAccount(accountId: String) = Unit
}

/** FR-6's store, as a constant; nothing here reads the tracker's shape. */
private object FakeAppSettingsStore : AppSettingsStore {
    override val showToggles: Flow<Boolean> = flowOf(false)
    override suspend fun setShowToggles(value: Boolean) = Unit
}
