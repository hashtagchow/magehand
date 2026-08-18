package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.auth.StoredToken
import com.hashtagchow.magehand.core.data.auth.TokenStore
import com.hashtagchow.magehand.core.data.characters.CharacterListRepository
import com.hashtagchow.magehand.core.data.characters.CharacterListState
import com.hashtagchow.magehand.core.data.connection.AccountConnection
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.model.Account
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionTone
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.CustomizeSection
import com.hashtagchow.magehand.ui.webview.SheetSessionFactory

/**
 * The character-home ViewModel: session lifecycle, board → UI plumbing, and the customize
 * actions (which are the only mutations WP6 ships).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CharacterHomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val creatureId = "FakeCreature23456"

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        character: FakeOpenCharacter? = FakeOpenCharacter(creatureId = creatureId),
        listState: CharacterListState = CharacterListState(
            characters = listOf(
                CharacterSummary(
                    creatureId = creatureId,
                    name = "Elowen Brightmantle",
                    alignment = "Chaotic Good",
                    gender = "Female",
                    picture = null,
                    owner = "someone-else",
                    isOwnedByMe = false,
                ),
            ),
            connection = ConnectionState.LIVE,
        ),
    ): Pair<CharacterHomeViewModel, FakeOpenCharacterFactory> {
        val factory = FakeOpenCharacterFactory(character)
        val vm = CharacterHomeViewModel(
            savedStateHandle = SavedStateHandle(mapOf("creatureId" to creatureId)),
            characterListRepository = FakeCharacterListRepository(listState),
            sheetSessionFactory = SheetSessionFactory(StubAccountRepository, StubTokenStore),
            openCharacterFactory = factory,
            connectionManager = connectionManager,
        )
        return vm to factory
    }

    private val connectionManager = RecordingConnectionManager()

    /** `stateIn(WhileSubscribed)` never runs without a collector. */
    private fun kotlinx.coroutines.test.TestScope.collecting(vm: CharacterHomeViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
    }

    @Test
    fun `the character is opened exactly once, on entering the screen`() = runTest(dispatcher) {
        val (vm, factory) = viewModel()
        collecting(vm)
        advanceUntilIdle()

        assertEquals(1, factory.opened)
        assertEquals(creatureId, vm.uiState.value.creatureId)
    }

    @Test
    fun `the board flows into the tracker state`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        character.board.value = TrackerBoard(
            hp = TrackedResource("hp", TrackerKind.HIT_POINTS, "Hit Points", 12, 17),
            slots = listOf(
                TrackedResource("s1", TrackerKind.SPELL_SLOT, "1st Level", 3, 4, spellSlotLevel = 1),
            ),
        )
        character.connectionState.value = ConnectionState.LIVE
        advanceUntilIdle()

        val tracker = vm.uiState.value.tracker
        val hp = tracker.hp!!
        assertEquals(12, hp.current)
        assertEquals(17, hp.max)
        assertEquals(listOf("1st Level"), tracker.slots.map { it.label })
        assertEquals(3 to 4, tracker.slots.single().let { it.value to it.total })
        assertEquals(ConnectionTone.LIVE, tracker.status.tone)
    }

    @Test
    fun `the connection status follows the session, not the character list`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        // The list can be LIVE while *this character's* subscription is not ready — that
        // is the whole reason the tracker derives its own connection status.
        character.connectionState.value = ConnectionState.OFFLINE
        character.isShowingSnapshot.value = true
        character.lastSyncedAt.value = 1_786_991_520_000L
        advanceUntilIdle()

        val status = vm.uiState.value.tracker.status
        assertEquals(ConnectionTone.OFFLINE, status.tone)
        assertTrue(status.showingSnapshot)
        assertEquals(ConnectionState.OFFLINE, vm.uiState.value.connection)
    }

    /**
     * The connection sheet's retry button, end to end: it may do exactly one thing, and
     * that thing is the `restart()` that already existed. If this ever grows a second
     * call the feature has invented reconnection machinery it was told not to.
     */
    @Test
    fun `retrying the connection asks the existing manager to restart, and nothing else`() =
        runTest(dispatcher) {
            val (vm, _) = viewModel()
            collecting(vm)
            advanceUntilIdle()

            assertEquals(0, connectionManager.restarts)
            vm.reconnect()
            advanceUntilIdle()
            assertEquals(1, connectionManager.restarts)
        }

    @Test
    fun `the character name comes from the list the user came from`() = runTest(dispatcher) {
        val (vm, _) = viewModel()
        collecting(vm)
        advanceUntilIdle()
        assertEquals("Elowen Brightmantle", vm.uiState.value.characterName)
    }

    @Test
    fun `the accent colour reaches the ui state`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.setAccentColor("#7E57C2")
        advanceUntilIdle()

        assertEquals("#7E57C2", vm.uiState.value.accentColor)
    }

    @Test
    fun `hiding a row writes one override`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.setRowHidden("s2", hidden = true)
        advanceUntilIdle()

        assertEquals(listOf(TrackerOverride("s2", hidden = true)), character.written)
    }

    @Test
    fun `pinning an item writes one override`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.setRowPinned("i1", pinned = true)
        advanceUntilIdle()

        assertEquals(listOf(TrackerOverride("i1", pinned = true)), character.written)
    }

    @Test
    fun `moving a row re-indexes its whole section`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)

        character.boardIgnoringHidden.value = TrackerBoard(
            slots = listOf(
                TrackedResource("s1", TrackerKind.SPELL_SLOT, "1st", 1, 2, spellSlotLevel = 1),
                TrackedResource("s2", TrackerKind.SPELL_SLOT, "2nd", 1, 2, spellSlotLevel = 2),
                TrackedResource("s3", TrackerKind.SPELL_SLOT, "3rd", 1, 2, spellSlotLevel = 3),
            ),
        )
        advanceUntilIdle()

        vm.moveRow(CustomizeSection.SPELL_SLOTS, "s3", delta = -1)
        advanceUntilIdle()

        assertEquals(listOf("s1", "s3", "s2"), character.written.map { it.propertyId })
        assertEquals(listOf(0, 1, 2), character.written.map { it.sortIndex })
    }

    @Test
    fun `a move that would fall off the list writes nothing`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)

        character.boardIgnoringHidden.value = TrackerBoard(
            slots = listOf(
                TrackedResource("s1", TrackerKind.SPELL_SLOT, "1st", 1, 2, spellSlotLevel = 1),
            ),
        )
        advanceUntilIdle()

        vm.moveRow(CustomizeSection.SPELL_SLOTS, "s1", delta = -1)
        advanceUntilIdle()

        assertTrue(character.written.isEmpty())
    }

    @Test
    fun `backgrounding captures a snapshot`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.captureSnapshot()
        advanceUntilIdle()

        assertEquals(1, character.snapshotsCaptured)
    }

    @Test
    fun `leaving the screen closes the session`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.callOnCleared()
        // `close()` runs on a scope of its own, so give the real dispatcher a moment.
        repeat(20) {
            if (character.closedCount > 0) return@repeat
            Thread.sleep(10)
        }

        assertEquals(1, character.closedCount)
    }

    @Test
    fun `no session means an empty tracker rather than a crash`() = runTest(dispatcher) {
        val (vm, _) = viewModel(character = null)
        collecting(vm)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.tracker.isEmpty)
        assertNull(vm.uiState.value.tracker.hp)
        // Every customize action is a no-op with nothing open.
        vm.setRowHidden("s1", true)
        vm.setAccentColor("#7E57C2")
        advanceUntilIdle()
        assertNull(vm.uiState.value.accentColor)
    }

    // --- WP7: the tracker writes ------------------------------------------------

    /** A board with the three shapes the write intents take. */
    private fun writableBoard() = TrackerBoard(
        hp = TrackedResource("hp", TrackerKind.HIT_POINTS, "Hit Points", value = 12, total = 20),
        slots = listOf(TrackedResource("slot1", TrackerKind.SPELL_SLOT, "1st Level", 3, 3)),
        pinnedItems = listOf(TrackedResource("item1", TrackerKind.ITEM, "Potion", 5, 5)),
        allItems = listOf(TrackedResource("item1", TrackerKind.ITEM, "Potion", 5, 5)),
        activeToggles = listOf(ConditionToggle("tog1", "Bless", enabled = false)),
    )

    @Test
    fun `a tapped row is resolved on the live board and handed to the matching intent`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            character.board.value = writableBoard()
            val (vm, _) = viewModel(character)
            collecting(vm)
            advanceUntilIdle()

            vm.spend("slot1")
            vm.restore("slot1")
            vm.adjustItem("item1", -1)
            vm.toggleCondition("tog1")
            vm.changeHitPoints(-3)
            vm.setHitPoints(9)
            vm.rest(RestKind.LONG)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "spend slot1 1",
                    "restore slot1 1",
                    "item item1 -1",
                    "toggle tog1",
                    "hp -3",
                    "hp= 9",
                    "rest LONG",
                ),
                character.writes,
            )
        }

    @Test
    fun `a tap on a row the board no longer has writes nothing`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        character.board.value = writableBoard()
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        // The row was removed on the sheet while the user's thumb was in the air.
        vm.spend("gone")
        vm.adjustItem("gone", -1)
        vm.toggleCondition("gone")
        advanceUntilIdle()

        assertTrue("a stale id must not be written blind: ${character.writes}", character.writes.isEmpty())
    }

    @Test
    fun `a fresh history entry raises exactly one undo snackbar`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        val events = mutableListOf<TrackerEvent>()
        val collector = launch { vm.events.collect { events += it } }
        collecting(vm)
        advanceUntilIdle()

        character.writeHistory.value = listOf(historyEntry(1))
        advanceUntilIdle()
        character.writeHistory.value = listOf(historyEntry(2), historyEntry(1))
        advanceUntilIdle()

        assertEquals(2, events.size)
        assertEquals(listOf(1L, 2L), events.map { (it as TrackerEvent.Wrote).write.id })
        collector.cancel()
    }

    @Test
    fun `an undo marks an entry rather than adding one, so it raises no snackbar`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            val events = mutableListOf<TrackerEvent>()
            val collector = launch { vm.events.collect { events += it } }
            collecting(vm)
            advanceUntilIdle()

            character.writeHistory.value = listOf(historyEntry(1))
            advanceUntilIdle()
            // What `WriteQueue.undo()` does to the list: same id, now struck through.
            character.writeHistory.value = listOf(historyEntry(1, undoable = false, undone = true))
            advanceUntilIdle()

            assertEquals("an undo must not offer to undo itself", 1, events.size)
            collector.cancel()
        }

    @Test
    fun `a rolled-back write becomes a failure event carrying the row to shake`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            val events = mutableListOf<TrackerEvent>()
            val collector = launch { vm.events.collect { events += it } }
            collecting(vm)
            advanceUntilIdle()

            character.writeFailures.emit(
                TrackerWriteFailure(
                    id = 7,
                    kind = TrackerWriteKind.SPEND,
                    propertyId = "slot1",
                    targetName = "1st Level",
                    reason = "Nope",
                    refusedOffline = false,
                    rateLimited = false,
                ),
            )
            advanceUntilIdle()

            val failed = events.single() as TrackerEvent.Failed
            assertEquals("slot1", failed.failure.propertyId)
            collector.cancel()
        }

    @Test
    fun `undo is delegated to the session's inverse-op stack`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.undoLastWrite()
        advanceUntilIdle()

        assertEquals(1, character.undoCount)
    }

    @Test
    fun `canWrite and the history reach the tracker state`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        character.connectionState.value = ConnectionState.LIVE
        character.canWrite.value = true
        character.canUndo.value = true
        character.writeHistory.value = listOf(historyEntry(1))
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        val tracker = vm.uiState.value.tracker
        assertTrue(tracker.canWrite)
        assertTrue(tracker.canUndo)
        assertEquals(1, tracker.history.size)
        assertTrue(tracker.history.single().canUndo)
    }

    @Test
    fun `every write intent is inert before the character has opened`() = runTest(dispatcher) {
        val (vm, _) = viewModel(character = null)
        collecting(vm)
        advanceUntilIdle()

        vm.spend("slot1")
        vm.changeHitPoints(-1)
        vm.setHitPoints(1)
        vm.rest(RestKind.SHORT)
        vm.undoLastWrite()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.tracker.canWrite)
    }

    private fun historyEntry(id: Long, undoable: Boolean = true, undone: Boolean = false) =
        TrackerWrite(
            id = id,
            kind = TrackerWriteKind.SPEND,
            targetName = "1st Level",
            amount = 1,
            at = 1_755_463_920_000L,
            undoable = undoable,
            undone = undone,
        )
}


/** `onCleared` is protected; the test needs to simulate the nav entry going away. */
private fun CharacterHomeViewModel.callOnCleared() {
    val method = androidx.lifecycle.ViewModel::class.java
        .getDeclaredMethod("onCleared")
        .apply { isAccessible = true }
    method.invoke(this)
}

/**
 * Counts [restart] calls, which is the whole of what the connection sheet's retry button
 * is allowed to do — see `CharacterHomeViewModel.reconnect`.
 */
private class RecordingConnectionManager : DdpConnectionManager {
    var restarts = 0
        private set

    override val connection: StateFlow<AccountConnection?> = MutableStateFlow(null)

    override fun restart() {
        restarts++
    }
}

private class FakeCharacterListRepository(state: CharacterListState) : CharacterListRepository {
    override val state: StateFlow<CharacterListState> = MutableStateFlow(state)
    override fun refresh() = Unit
}

/**
 * The Sheet tab is WP5's and is not what this test is about, so the session factory is fed
 * an account store with nothing in it — `sessions()` then emits `null` and the Sheet tab
 * would show its spinner.
 */
private object StubAccountRepository : AccountRepository {
    override val accounts: Flow<List<Account>> = flowOf(emptyList())
    override val activeAccountId: Flow<String?> = flowOf(null)
    override val activeAccount: Flow<Account?> = flowOf(null)
    override suspend fun getAccount(accountId: String): Account? = null
    override suspend fun addAccount(
        serverUrlInput: String,
        usernameOrEmail: String,
        password: String,
    ): Result<Account> = error("not used")

    override suspend fun adoptToken(
        serverUrlInput: String,
        userId: String,
        username: String,
        token: String,
        tokenExpiresAt: Long?,
    ): Result<Account> = error("not used")

    override suspend fun reLogin(accountId: String, password: String): Result<Account> =
        error("not used")

    override suspend fun setActiveAccount(accountId: String) = Unit
    override suspend fun signOut(accountId: String) = Unit
    override suspend fun tokenFor(accountId: String): String? = null
}

private object StubTokenStore : TokenStore {
    override suspend fun save(accountId: String, token: StoredToken) = Unit
    override suspend fun read(accountId: String): StoredToken? = null
    override suspend fun delete(accountId: String) = Unit
    override suspend fun clear() = Unit
}
