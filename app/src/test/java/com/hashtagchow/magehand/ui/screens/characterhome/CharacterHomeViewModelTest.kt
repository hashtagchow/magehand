package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
import com.hashtagchow.magehand.core.data.feed.ActivityFeedRepository
import com.hashtagchow.magehand.core.model.FeedLine
import com.hashtagchow.magehand.core.model.FeedEntry
import com.hashtagchow.magehand.core.data.settings.AppSettingsStore
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneLayoutEntry
import com.hashtagchow.magehand.ui.panes.resolvePaneLayout
import com.hashtagchow.magehand.ui.panes.resolvePanes
import com.hashtagchow.magehand.ui.panes.serverPaneSurfaces
import com.hashtagchow.magehand.core.data.settings.PaneLayoutStore
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.core.model.Account
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.ActionBoard
import com.hashtagchow.magehand.core.model.ActionEntry
import com.hashtagchow.magehand.core.model.ActionType
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.EquipGroup
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryContainer
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.core.model.SpellEntry
import com.hashtagchow.magehand.core.model.RestKind
import com.hashtagchow.magehand.core.model.RollModifier
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerOverride
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import com.hashtagchow.magehand.core.model.Wallet
import com.hashtagchow.magehand.core.model.WalletRow
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionTone
import com.hashtagchow.magehand.ui.screens.characterhome.actions.detailFor
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
        /**
         * FR-6's switch. **True by default here, false by default in production** — and that
         * asymmetry is deliberate: every assertion in this class predates FR-6 and is about
         * the shipped FR-1/2 behaviour, which decision 9 defines as "toggles on". A fake that
         * defaulted to the production value would have silently rewritten what those tests
         * mean. The production default is pinned separately, in `LocalCharacterHomePostureTest`.
         */
        showToggles: Boolean = true,
    ): Pair<CharacterHomeViewModel, FakeOpenCharacterFactory> {
        val factory = FakeOpenCharacterFactory(character)
        val vm = CharacterHomeViewModel(
            savedStateHandle = SavedStateHandle(mapOf("creatureId" to creatureId)),
            characterListRepository = FakeCharacterListRepository(listState),
            sheetSessionFactory = SheetSessionFactory(StubAccountRepository, StubTokenStore),
            appSettingsStore = FakeAppSettingsStore(showToggles),
            selectedRollStore = selectedRolls,
            equippableOverrideStore = equippableOverrides,
            inventoryLayoutStore = inventoryLayouts,
            paneLayoutStore = paneLayouts,
            openCharacterFactory = factory,
            connectionManager = connectionManager,
            activityFeedRepository = activityFeed,
        )
        return vm to factory
    }

    private val connectionManager = RecordingConnectionManager()

    /** FR-28 decision 6's best-effort error look. See [FakeActivityFeedRepository]. */
    private val activityFeed = FakeActivityFeedRepository()

    /** FR-7's per-character selection. In memory; the persistence itself is `:core:data`'s. */
    private val selectedRolls = FakeSelectedRollStore()

    /** FR-14's per-character arrangement, likewise in memory. */
    private val inventoryLayouts = FakeInventoryLayoutStore()

    /** FR-17's per-character pane choice, likewise in memory (14 decision 8). */
    private val paneLayouts = FakePaneLayoutStore()

    /**
     * The surfaces a caster has, which the *screen* passes to `resolvePaneLayout` and the view
     * model deliberately never sees — availability is a UI-layer fact (FR-26's discovery gate).
     * These tests stand in for the screen, so they resolve with it here.
     */
    private val allSurfaces = serverPaneSurfaces(hasActions = true)

    /** FR-10's per-item overrides. In memory, for the same reason as the selection above. */
    private val equippableOverrides = FakeEquippableOverrideStore()

    /** `stateIn(WhileSubscribed)` never runs without a collector. */
    private fun kotlinx.coroutines.test.TestScope.collecting(vm: CharacterHomeViewModel) {
        backgroundScope.launch { vm.uiState.collect {} }
        // FR-17's panes are a second `stateIn(WhileSubscribed)` (see the view model for why they
        // are not a field on `uiState`), so they need their own collector or they never start —
        // and a test asserting a value that was never produced fails for the wrong reason.
        backgroundScope.launch { vm.panes.collect {} }
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

    // --- FR-7: the Rolls dropdown --------------------------------------------

    @Test
    fun `a discovered roll reaches the tracker state, unselected`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        character.board.value = TrackerBoard(rolls = listOf(RollModifier("r1", "Stealth", 5)))
        advanceUntilIdle()

        val rolls = vm.uiState.value.tracker.rolls
        assertEquals(listOf("Stealth"), rolls.options.map { it.name })
        assertNull("nothing is picked until the player picks it", rolls.selected)
    }

    @Test
    fun `selecting a roll stores it under the account-scoped key and comes back out`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            advanceUntilIdle()

            character.board.value = TrackerBoard(rolls = listOf(RollModifier("r1", "Stealth", 5)))
            advanceUntilIdle()

            vm.selectRoll("r1")
            advanceUntilIdle()

            // Account-scoped, matching every other per-character store — a creature id alone
            // would make two accounts on one shared creature share a selection.
            assertEquals(
                setOf(SelectedRollStore.serverKey(character.accountId, creatureId)),
                selectedRolls.keys,
            )
            assertEquals("+5", vm.uiState.value.tracker.rolls.selected?.modifier)
        }

    @Test
    fun `a selection made before the board arrived resolves once it does`() = runTest(dispatcher) {
        // The cold-open ordering: DataStore answers before the socket does. The remembered id
        // is not a roll yet, and must not be forgotten while it waits for one.
        val character = FakeOpenCharacter(creatureId = creatureId)
        selectedRolls.setSelectedRollId(
            SelectedRollStore.serverKey(character.accountId, creatureId),
            "r1",
        )
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        assertNull(vm.uiState.value.tracker.rolls.selected)

        character.board.value = TrackerBoard(rolls = listOf(RollModifier("r1", "Stealth", 5)))
        advanceUntilIdle()

        assertEquals("Stealth", vm.uiState.value.tracker.rolls.selected?.name)
    }

    @Test
    fun `selecting a roll before the character opens is dropped, not stored under a wrong key`() =
        runTest(dispatcher) {
            // The key needs the account id, which only the opened character knows. No character,
            // no key — writing one anyway would put a row under an empty account id that
            // sign-out could never reach.
            val (vm, _) = viewModel(character = null)
            collecting(vm)

            vm.selectRoll("r1")
            advanceUntilIdle()

            assertTrue(selectedRolls.keys.isEmpty())
        }

    // --- FR-10: the per-item equippability override (11 decision 2) ------------

    @Test
    fun `an override is stored under the account-scoped key and reaches the tab`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            advanceUntilIdle()

            character.inventory.value = InventoryBoard(
                carried = listOf(
                    InventoryItem(
                        propertyId = "knife",
                        name = "A Small Knife",
                        quantity = 1,
                        weightLb = null,
                        valueGp = null,
                        description = null,
                        equipped = false,
                        // The 11 decision 1 residual: tagless and unequipped, so the engine
                        // calls it non-equippable and the row carries no equip control.
                        isEquippable = false,
                    ),
                ),
            )
            advanceUntilIdle()

            assertFalse(vm.uiState.value.inventory.row("knife")!!.showsEquipControl)

            vm.setEquippableOverride("knife", canEquip = true)
            advanceUntilIdle()

            // Account-scoped, matching the roll selection above and every other per-character
            // store: two accounts reaching one shared creature must not share an override.
            assertEquals(
                setOf(EquippableOverrideStore.serverKey(character.accountId, creatureId)),
                equippableOverrides.keys,
            )
            // …and it is a *live* read, not a write into a void: the tab re-renders with the
            // control back on.
            val row = vm.uiState.value.inventory.row("knife")!!
            assertTrue(row.showsEquipControl)
            assertTrue(row.equippableOverridden)
        }

    @Test
    fun `an override set before the character opens is dropped, not stored under a wrong key`() =
        runTest(dispatcher) {
            // The roll selection's argument, unchanged: the key needs the account id, and only
            // the opened character knows it. A key under an empty account id is one sign-out
            // could never reach.
            val (vm, _) = viewModel(character = null)
            collecting(vm)

            vm.setEquippableOverride("knife", canEquip = true)
            advanceUntilIdle()

            assertTrue(equippableOverrides.keys.isEmpty())
        }

    // --- FR-14: the inventory customize sheet (12 decisions 3 and 5) -------------

    /** A board with all four hideable kinds of section, so a gesture has somewhere to go. */
    private fun arrangeableBoard() = InventoryBoard(
        equipped = listOf(inventoryItem("eq1", "Longsword")),
        containers = listOf(
            InventoryContainer(
                propertyId = "cont1",
                name = "Backpack",
                quantity = 1,
                weightLb = 5.0,
                valueGp = 1.0,
                rollupWeightLb = 6.0,
                rollupValueGp = 2.0,
                contents = listOf(inventoryItem("in1", "Rations")),
            ),
        ),
        carried = listOf(
            inventoryItem("w1", "Dagger", equipGroup = EquipGroup.WEAPON),
            inventoryItem("g1", "Torch"),
        ),
    )

    private fun inventoryItem(
        id: String,
        name: String,
        equipGroup: EquipGroup = EquipGroup.GEAR,
    ) = InventoryItem(
        propertyId = id,
        name = name,
        quantity = 1,
        weightLb = 1.0,
        valueGp = null,
        description = null,
        equipped = false,
        equipGroup = equipGroup,
    )

    @Test
    fun `moving an inventory section persists the whole arrangement under the account key`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            character.inventory.value = arrangeableBoard()
            advanceUntilIdle()

            vm.moveInventorySection("wallet", delta = 1)
            advanceUntilIdle()

            // Account-scoped, matching the roll selection and the equippability overrides: two
            // accounts reaching one shared creature must not share an arrangement.
            val key = InventoryLayoutStore.serverKey(character.accountId, creatureId)
            assertEquals(setOf(key), inventoryLayouts.keys)
            // The **whole** order, not a delta — the value is an order, and an order cannot be
            // edited one element at a time. See `InventoryLayoutStore.setLayout`.
            assertEquals(
                listOf("equipped", "wallet", "weapons", "container:cont1", "gear"),
                inventoryLayouts.layoutFor(key).map { it.key },
            )
            // …and it is a live read: the tab re-renders in the new order.
            assertEquals(
                listOf("equipped", "wallet", "weapons", "container:cont1", "gear"),
                vm.uiState.value.inventory.blocks.map { it.key },
            )
        }

    @Test
    fun `a bounce off the end of the list writes nothing at all`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        character.inventory.value = arrangeableBoard()
        advanceUntilIdle()

        vm.moveInventorySection("wallet", delta = -1)
        advanceUntilIdle()

        // Not "wrote the same order back" — wrote *nothing*, so a character who has never
        // customized does not acquire a stored key by tapping a dead arrow.
        assertTrue(inventoryLayouts.keys.isEmpty())
    }

    @Test
    fun `folding a section moves its items into Gear and never off the tab`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        character.inventory.value = arrangeableBoard()
        advanceUntilIdle()

        vm.setInventorySectionHidden("container:cont1", hidden = true)
        advanceUntilIdle()

        val inventory = vm.uiState.value.inventory
        assertTrue("the container's header is gone", inventory.blocks.none { it.key == "container:cont1" })
        // 12 decision 3's invariant, end to end through the view model: the Rations are in Gear,
        // not missing. This is the one failure on this tab that would be a data-loss bug from
        // the player's point of view.
        assertEquals(
            listOf("g1", "in1"),
            inventory.sections.single { it.key == "gear" }.rows.map { it.propertyId },
        )
        assertEquals(listOf("container:cont1"), inventory.customize.hidden.map { it.key })
    }

    @Test
    fun `resetting deletes the key rather than storing today's default order`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        character.inventory.value = arrangeableBoard()
        advanceUntilIdle()

        vm.setInventorySectionHidden("wallet", hidden = true)
        advanceUntilIdle()
        assertTrue(inventoryLayouts.keys.isNotEmpty())

        vm.resetInventoryLayout()
        advanceUntilIdle()

        // A deletion, which is decision 5's own wording and the meaningfully different one: a
        // stored copy of the default would freeze *today's* default into that character, so a
        // later release that changed decision 1 would change the order for every new character
        // and for nobody who had ever pressed Reset.
        assertTrue(inventoryLayouts.keys.isEmpty())
        assertEquals(
            listOf("wallet", "equipped", "weapons", "container:cont1", "gear"),
            vm.uiState.value.inventory.blocks.map { it.key },
        )
    }

    @Test
    fun `collapsing a section persists it in the same key and re-renders the tab`() =
        runTest(dispatcher) {
            // FR-16 (13 decision 3): collapse is a preference, so it rides in the FR-14 layout
            // key rather than in a `rememberSaveable`. That is what makes it survive a
            // force-stop, and it is why this is a view-model test rather than a state one.
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            character.inventory.value = arrangeableBoard()
            advanceUntilIdle()

            vm.setInventorySectionCollapsed("weapons", collapsed = true)
            advanceUntilIdle()

            val key = InventoryLayoutStore.serverKey(character.accountId, creatureId)
            assertEquals(
                listOf("weapons"),
                inventoryLayouts.layoutFor(key).filter { it.collapsed }.map { it.key },
            )
            // The header is still on the tab — this is a collapse, not a hide — and its rows
            // are still filed under it, one tap away.
            val weapons = vm.uiState.value.inventory.sections.single { it.key == "weapons" }
            assertTrue(weapons.collapsed)
            assertTrue(weapons.rows.isNotEmpty())
        }

    @Test
    fun `Reset opens every section again, because collapse lives in the key it deletes`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            character.inventory.value = arrangeableBoard()
            advanceUntilIdle()

            vm.setInventorySectionCollapsed("weapons", collapsed = true)
            vm.setInventorySectionCollapsed("gear", collapsed = true)
            advanceUntilIdle()

            vm.resetInventoryLayout()
            advanceUntilIdle()

            // 13 decision 3's "Reset clears collapse too", end to end. A separate collapse store
            // would have needed a second call here, and a Reset that left every section shut is
            // exactly the failure that would have shipped.
            assertTrue(inventoryLayouts.keys.isEmpty())
            assertTrue(vm.uiState.value.inventory.sections.none { it.collapsed })
        }

    @Test
    fun `collapsing the wallet writes nothing, because its state is not a preference`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            character.inventory.value = arrangeableBoard()
            advanceUntilIdle()

            vm.setInventorySectionCollapsed("wallet", collapsed = true)
            advanceUntilIdle()

            // 13 decision 3's exception. The tab never routes the wallet's chevron here — it is
            // a `rememberSaveable` in the composable — and the plan refuses the key regardless,
            // so a character who has never customized does not acquire a stored key from it.
            assertTrue(inventoryLayouts.keys.isEmpty())
        }

    @Test
    fun `a move made while a section is collapsed keeps it collapsed`() = runTest(dispatcher) {
        // The silent regression: every gesture persists the whole arrangement, so a move
        // computed against a state that had dropped the collapse flags would quietly re-open
        // every shut section the first time the player reordered anything.
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        character.inventory.value = arrangeableBoard()
        advanceUntilIdle()

        vm.setInventorySectionCollapsed("weapons", collapsed = true)
        advanceUntilIdle()
        vm.moveInventorySection("wallet", delta = 1)
        advanceUntilIdle()

        val key = InventoryLayoutStore.serverKey(character.accountId, creatureId)
        assertEquals(
            listOf("weapons"),
            inventoryLayouts.layoutFor(key).filter { it.collapsed }.map { it.key },
        )
        assertTrue(vm.uiState.value.inventory.sections.single { it.key == "weapons" }.collapsed)
    }

    // ---- FR-17 panes (14 decisions 6 and 8) + FR-27's order ------------------

    @Test
    fun `a character nobody has arranged reports no pane preference`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        // The empty list is "use the default", not "no panes" — see `PaneLayoutStore`. Asserted
        // here because the *view model* is where a well-meaning `?: listOf(TRACKER)` would go,
        // and that would freeze today's default order and set into every character the moment it
        // was touched.
        assertEquals(emptyList<PaneLayoutEntry>(), vm.panes.value)
        assertTrue("nothing is written just by opening a character", paneLayouts.keys.isEmpty())
    }

    @Test
    fun `toggling a pane writes the account-scoped key`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.togglePane(resolvePaneLayout(vm.panes.value, allSurfaces), PaneSurface.SHEET)
        advanceUntilIdle()

        // Account-scoped, not bare-creature: the same creature reached from two accounts is two
        // rows everywhere else in this app, and the scoping is what makes sign-out's reap a
        // prefix match rather than a scan.
        val key = PaneLayoutStore.serverKey(character.accountId, creatureId)
        assertEquals(setOf(key), paneLayouts.keys)
        assertEquals(
            listOf(PaneSurface.TRACKER, PaneSurface.SHEET),
            resolvePanes(paneLayouts.panesFor(key), allSurfaces),
        )
        assertEquals(listOf(PaneSurface.TRACKER, PaneSurface.SHEET), resolvePanes(vm.panes.value, allSurfaces))
    }

    @Test
    fun `reordering writes the same account-scoped key, and opens nothing`() = runTest(dispatcher) {
        // FR-27 decisions 2 and 4: one key, one lifecycle, and a reorder that leaves the pane
        // selection exactly where it was. The view model is where a `movePane` wired to
        // `setPanes` with the wrong key — or one that persisted the resolved list directly —
        // would land.
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.movePane(resolvePaneLayout(vm.panes.value, allSurfaces), PaneSurface.SHEET, -1)
        advanceUntilIdle()

        val key = PaneLayoutStore.serverKey(character.accountId, creatureId)
        assertEquals(setOf(key), paneLayouts.keys)
        assertEquals(
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.SHEET, PaneSurface.ACTIONS),
            resolvePaneLayout(vm.panes.value, allSurfaces).map { it.surface },
        )
        assertEquals(
            "still the Tracker-only default — an arrow is not a tick",
            listOf(PaneSurface.TRACKER),
            resolvePanes(vm.panes.value, allSurfaces),
        )
    }

    @Test
    fun `resetting the arrangement deletes the key rather than writing a default`() =
        runTest(dispatcher) {
            // FR-27 decision 3. A reset that *wrote* today's default would freeze it into the
            // character, so a later release changing the default would change it for nobody —
            // `PaneLayoutStore`'s argument, asserted at the one call site that could get it wrong.
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            advanceUntilIdle()
            vm.movePane(resolvePaneLayout(vm.panes.value, allSurfaces), PaneSurface.SHEET, -1)
            advanceUntilIdle()
            assertTrue("sanity: something was stored to reset", paneLayouts.keys.isNotEmpty())

            vm.resetPaneLayout()
            advanceUntilIdle()

            assertTrue(paneLayouts.keys.isEmpty())
            assertEquals(emptyList<PaneLayoutEntry>(), vm.panes.value)
        }

    @Test
    fun `deselecting the last pane writes nothing at all`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.togglePane(resolvePaneLayout(vm.panes.value, allSurfaces), PaneSurface.TRACKER)
        advanceUntilIdle()

        // Decision 6's minimum of one, all the way down: a refused gesture is not a write of the
        // same value, it is no write — the same no-op contract `mutateInventoryLayout` has, and
        // the reason `nextStoredPanes` returns the empty list.
        assertEquals(0, paneLayouts.writes)
        assertTrue(paneLayouts.keys.isEmpty())
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
        val events = mutableListOf<TrackerEvent.Wrote>()
        val collector = launch { vm.writeEvents.collect { events += it } }
        collecting(vm)
        advanceUntilIdle()

        character.writeHistory.value = listOf(historyEntry(1))
        advanceUntilIdle()
        character.writeHistory.value = listOf(historyEntry(2), historyEntry(1))
        advanceUntilIdle()

        assertEquals(2, events.size)
        assertEquals(listOf(1L, 2L), events.map { it.write.id })
        collector.cancel()
    }

    @Test
    fun `an undo marks an entry rather than adding one, so it raises no snackbar`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            val events = mutableListOf<TrackerEvent.Wrote>()
            val collector = launch { vm.writeEvents.collect { events += it } }
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
            val events = mutableListOf<TrackerEvent.Failed>()
            val collector = launch { vm.failureEvents.collect { events += it } }
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

            assertEquals("slot1", events.single().failure.propertyId)
            collector.cancel()
        }

    /**
     * A failure can never be starved by a backlog of confirmations.
     *
     * The two kinds used to share one 16-deep `MutableSharedFlow` with a suspending overflow
     * policy, shown by one `when` in the screen — and `showSnackbar` suspends for the life of
     * each snackbar. So a burst of taps filled the buffer with confirmations, and the *emitter*
     * of a failure then parked waiting for room, behind up to sixteen four-second snackbars.
     * Over a minute in which the one event the player needs — "that write did not stick" —
     * could not reach the screen at all, on precisely the press-and-hold that produces
     * rate-limit refusals in the first place.
     *
     * The two collectors below are the screen's two, with the confirmation one modelling the
     * snackbar's suspension. **Foreground** launches, deliberately: `advanceUntilIdle` does not
     * advance virtual time for `backgroundScope` work, so a background collector asleep in
     * `delay` would never wake and the test would pass by never running.
     *
     * The assertion after the failure is made without advancing time at all — "it arrived while
     * the backlog was still there", not "it arrived eventually".
     */
    @Test
    fun `a failure is not starved by a backlog of confirmations`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        val confirmations = mutableListOf<Long>()
        var failure: TrackerWriteFailure? = null

        val receipts = launch {
            vm.writeEvents.collect {
                confirmations += it.write.id
                delay(4_000) // one Short snackbar
            }
        }
        val rollbacks = launch { vm.failureEvents.collect { failure = it.failure } }
        collecting(vm)
        advanceUntilIdle()

        // Twenty confirmations while the receipt collector is asleep on the first one.
        repeat(20) { i -> character.writeHistory.value = listOf(historyEntry(i + 1L)); runCurrent() }

        character.writeFailures.emit(
            TrackerWriteFailure(
                id = 99,
                kind = TrackerWriteKind.ITEM_USE,
                propertyId = "item1",
                targetName = "Potion of Healing",
                reason = "too-many-requests",
                refusedOffline = false,
                rateLimited = true,
            ),
        )
        runCurrent()

        assertEquals("the failure must not wait behind a queue of receipts", 99L, failure?.id)
        assertEquals(
            "and the receipts must not have piled up either — one shown, the rest conflated",
            listOf(1L),
            confirmations,
        )
        receipts.cancel()
        rollbacks.cancel()
    }

    /**
     * The other half of the conflation: dropping receipts must not drop the *newest* one.
     *
     * Discarding stale receipts is honest — an UNDO offered eighty seconds late is for a write
     * the player stopped thinking about — but only if what finally shows is the write that just
     * happened, and only because nothing else is lost: every dropped receipt is still a row in
     * the history sheet with its own UNDO.
     *
     * The number to look at is the clock. Twenty confirmations at four seconds each is eighty
     * seconds of snackbar; conflated it is eight, and the second one is about write 20 rather
     * than about write 2.
     */
    @Test
    fun `a conflated burst still delivers the newest confirmation`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        val confirmations = mutableListOf<Long>()

        val receipts = launch {
            vm.writeEvents.collect {
                confirmations += it.write.id
                delay(4_000)
            }
        }
        collecting(vm)
        advanceUntilIdle()

        repeat(20) { i -> character.writeHistory.value = listOf(historyEntry(i + 1L)); runCurrent() }
        advanceUntilIdle()

        assertEquals("the one being shown, then the newest — not all twenty", listOf(1L, 20L), confirmations)
        assertEquals(
            "and nothing is lost that the history sheet does not still hold",
            20L,
            character.writeHistory.value.first().id,
        )
        receipts.cancel()
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

    // --- FR-8: the inventory tab (docs/design/10-inventory.md) -----------------

    private fun inventoryItem(
        id: String,
        name: String,
        quantity: Int = 1,
        equipped: Boolean = false,
    ) = InventoryItem(
        propertyId = id,
        name = name,
        quantity = quantity,
        weightLb = 1.0,
        valueGp = 1.0,
        description = null,
        equipped = equipped,
    )

    @Test
    fun `the inventory board flows into its own tab state`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        character.inventory.value = InventoryBoard(
            equipped = listOf(inventoryItem("i1", "Longsword", equipped = true)),
            carried = listOf(inventoryItem("i2", "Torch", quantity = 3)),
            carriedWeightLb = 4.0,
            capacityLb = 225,
        )
        character.connectionState.value = ConnectionState.LIVE
        advanceUntilIdle()

        val inventory = vm.uiState.value.inventory
        assertEquals(listOf("Longsword", "Torch"), inventory.sections.flatMap { s -> s.rows.map { it.name } })
        assertEquals("4", inventory.carriedWeight)
        assertEquals("225", inventory.capacityWeight)
    }

    @Test
    fun `the equip control sends the requested state and the current one`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        character.inventory.value = InventoryBoard(
            carried = listOf(inventoryItem("i2", "Torch", equipped = false)),
        )
        advanceUntilIdle()

        vm.setEquipped("i2", equipped = true)
        advanceUntilIdle()

        assertEquals(listOf("equip i2 true"), character.writes)
    }

    @Test
    fun `an equip aimed at an item the board does not have is dropped, not written blind`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            advanceUntilIdle()

            // The row was on screen a frame ago and a re-sync has since removed it.
            vm.setEquipped("gone", equipped = true)
            advanceUntilIdle()

            assertTrue(character.writes.isEmpty())
        }

    @Test
    fun `the quantity stepper resolves against the inventory board, not the tracker's`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            advanceUntilIdle()

            // Deliberately absent from `board.allItems`: that list is override-filtered, so
            // an item the player hid from the tracker is not in it. It is still an item they
            // own, so its stepper has to work.
            character.board.value = TrackerBoard.EMPTY
            character.inventory.value = InventoryBoard(
                carried = listOf(inventoryItem("hidden-item", "Rations (1 day)", quantity = 5)),
            )
            advanceUntilIdle()

            vm.adjustItemQuantity("hidden-item", -1)
            advanceUntilIdle()

            assertEquals(listOf("item hidden-item -1"), character.writes)
        }

    @Test
    fun `a wallet stepper is re-resolved from the live wallet before it is sent`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)
            advanceUntilIdle()

            character.inventory.value = InventoryBoard(
                wallet = Wallet(
                    listOf(
                        WalletRow(CoinKind.PLATINUM, 0, null),
                        WalletRow(CoinKind.GOLD, 5, "coin-gp"),
                        WalletRow(CoinKind.SILVER, 0, null),
                        WalletRow(CoinKind.COPPER, 0, null),
                    ),
                ),
            )
            advanceUntilIdle()

            vm.adjustCoins(CoinKind.GOLD, +1)
            // An absent denomination reaches the intent too — that is the insert path, and
            // nothing on screen distinguishes it.
            vm.adjustCoins(CoinKind.SILVER, +1)
            advanceUntilIdle()

            assertEquals(listOf("coins GOLD 1", "coins SILVER 1"), character.writes)
        }

    @Test
    fun `the add flow hands the spec straight to the intent`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.addItem(NewItemSpec(name = "Silvered dagger", quantity = 2))
        advanceUntilIdle()

        assertEquals(listOf("add Silvered dagger 2"), character.writes)
    }

    @Test
    fun `no character open means every inventory intent is a no-op rather than a crash`() =
        runTest(dispatcher) {
            val (vm, _) = viewModel(character = null)
            collecting(vm)
            advanceUntilIdle()

            vm.setEquipped("i1", equipped = true)
            vm.adjustItemQuantity("i1", 1)
            vm.adjustCoins(CoinKind.GOLD, 1)
            vm.addItem(NewItemSpec(name = "Torch"))
            advanceUntilIdle()

            assertFalse(vm.uiState.value.inventory.canWrite)
        }

    // =======================================================================
    // FR-28 — Use (docs/design/17-use-action.md decisions 2, 3 and 6)
    // =======================================================================

    private fun usableAction(id: String = "a1", name: String = "Rage") =
        ActionEntry(propertyId = id, name = name, type = ActionType.BONUS)

    private fun usableSpell(id: String = "s1", name: String = "Fireball", level: Int = 3) =
        SpellEntry(propertyId = id, name = name, level = level, prepared = true)

    /**
     * The one gesture, routed by target type.
     *
     * `vm.use` takes a `UseTarget` and dispatches on its shape, which is the only place in `:app`
     * that decides between the two DDP methods — and it decides from the type rather than from a
     * flag, so there is no combination of arguments that could send a cast down the action path.
     */
    @Test
    fun `using an action and casting a spell reach the two different intents`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.use(usableAction().useTarget!!)
        vm.use(usableSpell().useTarget!!, slotId = "slot-4", ritual = false)
        advanceUntilIdle()

        assertEquals(
            listOf("use a1", "cast s1 slot=slot-4 ritual=false"),
            character.writes,
        )
    }

    /** A ritual cast passes no slot — 17 decision 3's honest checkbox, end to end. */
    @Test
    fun `a ritual cast passes no slot through`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.use(usableSpell().useTarget!!, slotId = null, ritual = true)
        advanceUntilIdle()

        assertEquals(listOf("cast s1 slot=none ritual=true"), character.writes)
    }

    /**
     * The Actions state carries the three FR-28 inputs, live.
     *
     * Asserted together because they arrive through one `combine` and the failure mode of getting
     * that wrong is a state that renders but whose Use button is permanently disabled, or whose
     * slot picker is permanently empty — neither of which any other test would notice.
     */
    @Test
    fun `the actions state carries the live slots, the in-flight set and canWrite`() =
        runTest(dispatcher) {
            val character = FakeOpenCharacter(creatureId = creatureId)
            val (vm, _) = viewModel(character)
            collecting(vm)

            character.actions.value = ActionBoard(
                spells = listOf(usableSpell()),
                actions = listOf(usableAction()),
            )
            character.board.value = TrackerBoard(
                slots = listOf(
                    TrackedResource("l3", TrackerKind.SPELL_SLOT, "3rd", 2, 4, spellSlotLevel = 3),
                ),
            )
            character.canWrite.value = true
            character.usesInFlight.value = setOf("a1")
            advanceUntilIdle()

            val state = vm.uiState.value.actions
            assertEquals(listOf("l3"), state.spellSlots.map { it.propertyId })
            assertEquals(setOf("a1"), state.usesInFlight)
            assertTrue(state.canWrite)

            // …and they arrive where the sheet reads them.
            assertFalse("the in-flight row's button is disabled", state.detailFor("a1")!!.use!!.enabled)
            assertEquals("l3", state.detailFor("s1")!!.use!!.defaultSlotId)
        }

    /**
     * Decision 6's best-effort look: an "Error" entry logged for **this creature** after the tap
     * becomes a failure snackbar carrying the server's own words.
     *
     * `doAction` returns null for every outcome (probe U1), so this log entry is the only signal
     * a refusal ever produces. The feed is asked for exactly one creature, which is asserted —
     * the DM panel's party-wide merge would attribute another player's error to this tap.
     */
    @Test
    fun `an Error logged after a use surfaces as a failure`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        val failures = mutableListOf<TrackerEvent.Failed>()
        // A foreground collector, as every other failure test here uses: `advanceUntilIdle`
        // drains foreground work, and a `backgroundScope` collector is not resumed by it — the
        // emit lands in the SharedFlow's buffer and the assertion reads an empty list.
        val collector = launch { vm.failureEvents.collect { failures += it } }
        advanceUntilIdle()

        vm.use(usableAction().useTarget!!)
        // Pushed BEFORE the clock is advanced: `advanceUntilIdle` runs virtual time to
        // quiescence, which includes expiring the watcher's 3 s window. In wall-clock terms the
        // log lands a fraction of a second after the tap; in virtual time "inside the window"
        // means "before anything has been allowed to run".
        activityFeed.entries.value = listOf(
            FeedEntry(
                logId = "log-1",
                creatureId = creatureId,
                creatureName = "Scratch",
                lines = listOf(FeedLine(name = "Error", value = "Not enough rage")),
                // After the tap: `use` stamps `System.currentTimeMillis()`, and the test's own
                // virtual clock is unrelated to it, so a far-future date is what "after" means
                // here. The rule being pinned is that the timestamp is COMPARED at all.
                dateMillis = Long.MAX_VALUE,
            ),
        )
        advanceUntilIdle()

        assertEquals(setOf(setOf(creatureId)), activityFeed.requested.toSet())
        val failure = failures.single()
        assertEquals("Not enough rage", failure.failure.reason)
        assertEquals(TrackerWriteKind.USE_ACTION, failure.failure.kind)
        assertNull("nothing rolled back, so no row shakes", failure.failure.propertyId)
        collector.cancel()
    }

    /**
     * An "Error" already sitting in the feed from before the tap is **not** reported as this
     * tap's.
     *
     * Without the timestamp comparison every use would surface the last error the creature ever
     * logged — and the 20-entry window keeps them for a long time, so it would fire constantly
     * and mean nothing.
     */
    @Test
    fun `an Error older than the tap is ignored`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        val failures = mutableListOf<TrackerEvent.Failed>()
        val collector = launch { vm.failureEvents.collect { failures += it } }
        activityFeed.entries.value = listOf(
            FeedEntry(
                logId = "old",
                creatureId = creatureId,
                creatureName = "Scratch",
                lines = listOf(FeedLine(name = "Error", value = "ancient history")),
                dateMillis = 1L,
            ),
        )
        advanceUntilIdle()

        vm.use(usableAction().useTarget!!)
        advanceUntilIdle()

        assertTrue("an old error is not this tap's", failures.isEmpty())
        collector.cancel()
    }

    /**
     * A **cast** starts no watcher: `doCastSpell` reports its own refusals atomically and
     * verbatim through `writeFailures` (probe U2), so a second best-effort channel would either
     * double-report or attribute a stray log to a call that already told us the truth.
     */
    @Test
    fun `a cast starts no log watcher`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        val (vm, _) = viewModel(character)
        collecting(vm)
        advanceUntilIdle()

        vm.use(usableSpell().useTarget!!, slotId = "slot-4", ritual = false)
        advanceUntilIdle()

        assertTrue(
            "doCastSpell throws — it needs no feed watcher",
            activityFeed.requested.isEmpty(),
        )
    }

    /** With no character open, a Use is a no-op rather than a crash. */
    @Test
    fun `a use with no character open does nothing`() = runTest(dispatcher) {
        val (vm, _) = viewModel(character = null)
        collecting(vm)
        advanceUntilIdle()

        vm.use(usableAction().useTarget!!)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.actions.sections.isEmpty())
    }

    /**
     * M3/M4 [architect ruling]: `:core:data`'s gate or single-flight latch can drop a Confirm
     * tap before it reaches the wire. That used to be a silent `Unit` return; it must now
     * surface through the existing failure lane (M3) — never a fresh one — AND must start no
     * settle-window watch (M4), because there is no call in flight for it to watch for.
     */
    @Test
    fun `a dropped use surfaces a failure and starts no watch window`() = runTest(dispatcher) {
        val character = FakeOpenCharacter(creatureId = creatureId)
        character.useDispatches = false
        val (vm, _) = viewModel(character)
        collecting(vm)
        val failures = mutableListOf<TrackerEvent.Failed>()
        val collector = launch { vm.failureEvents.collect { failures += it } }
        advanceUntilIdle()

        vm.use(usableAction().useTarget!!)
        advanceUntilIdle()

        assertTrue("a dropped use must send nothing", character.writes.isEmpty())
        assertTrue("a dropped use starts no watch window (M4)", activityFeed.requested.isEmpty())
        val failure = failures.single().failure
        assertTrue("M3's snackbar reads the dropped flag, not a reason", failure.dropped)
        assertNull(failure.reason)
        assertNull("nothing rolled back, so no row shakes", failure.propertyId)
        assertEquals(TrackerWriteKind.USE_ACTION, failure.kind)
        // A3: this id shares no namespace with `:core:data`'s own failure-id counter (which
        // starts at 0 and is the one a real write failure counts from) — disjoint by range.
        assertTrue("USE_ERROR_IDS must not collide with the write-failure id space", failure.id >= 1_000_000_000L)
        collector.cancel()
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
/**
 * FR-28 decision 6's feed, drivable.
 *
 * Cold and driven by a `MutableStateFlow`, so a test can push an "Error" entry *after* the tap
 * that is supposed to be watching for one — which is the whole shape of the thing being tested.
 * [requested] records which creature set was asked for, so the "one creature, not the party"
 * claim is asserted rather than assumed.
 */
private class FakeActivityFeedRepository : ActivityFeedRepository {
    val entries = MutableStateFlow<List<FeedEntry>>(emptyList())
    val requested = mutableListOf<Set<String>>()

    override fun feed(creatureIds: Set<String>): Flow<List<FeedEntry>> {
        requested += creatureIds
        return entries
    }
}

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

/**
 * FR-7's store. Recording rather than constant: what this class asserts about the feature is
 * that the view model writes the *account-scoped* key, which a constant could not show.
 */
private class FakeSelectedRollStore : SelectedRollStore {
    private val entries = MutableStateFlow<Map<String, String>>(emptyMap())

    val keys: Set<String> get() = entries.value.keys

    override fun selectedRollId(characterKey: String): Flow<String?> =
        entries.map { it[characterKey] }

    override suspend fun setSelectedRollId(characterKey: String, rollId: String?) {
        entries.value = entries.value.toMutableMap().apply {
            if (rollId == null) remove(characterKey) else put(characterKey, rollId)
        }
    }

    override suspend fun deleteForAccount(accountId: String) = Unit
}

/**
 * FR-14's store. Recording, for the same reason as the two around it: the claim worth asserting
 * is that the view model writes the *account-scoped* key and the arrangement the plan produced,
 * neither of which a constant could show.
 */
private class FakeInventoryLayoutStore : InventoryLayoutStore {
    private val entries = MutableStateFlow<Map<String, List<InventoryLayoutEntry>>>(emptyMap())

    val keys: Set<String> get() = entries.value.keys

    fun layoutFor(characterKey: String): List<InventoryLayoutEntry> =
        entries.value[characterKey].orEmpty()

    override fun layout(characterKey: String): Flow<List<InventoryLayoutEntry>> =
        entries.map { it[characterKey].orEmpty() }

    override suspend fun setLayout(characterKey: String, layout: List<InventoryLayoutEntry>) {
        entries.value = entries.value.toMutableMap().apply {
            if (layout.isEmpty()) remove(characterKey) else put(characterKey, layout)
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        entries.value = entries.value - characterKey
    }

    override suspend fun deleteForAccount(accountId: String) = Unit
}

/**
 * FR-17's store (14 decision 8). Recording for the same reason as the two around it: the claims
 * worth asserting are that the view model writes the *account-scoped* key and that a gesture the
 * minimum-of-one rule refuses is not written at all — neither of which a constant could show.
 */
private class FakePaneLayoutStore : PaneLayoutStore {
    private val entries = MutableStateFlow<Map<String, List<PaneLayoutEntry>>>(emptyMap())

    val keys: Set<String> get() = entries.value.keys

    fun panesFor(characterKey: String): List<PaneLayoutEntry> = entries.value[characterKey].orEmpty()

    /** How many times anything was written, so a refused gesture can be shown to write nothing. */
    var writes: Int = 0
        private set

    override fun panes(characterKey: String): Flow<List<PaneLayoutEntry>> =
        entries.map { it[characterKey].orEmpty() }

    override suspend fun setPanes(characterKey: String, panes: List<PaneLayoutEntry>) {
        writes++
        entries.value = entries.value.toMutableMap().apply {
            if (panes.isEmpty()) remove(characterKey) else put(characterKey, panes)
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        entries.value = entries.value - characterKey
    }

    override suspend fun deleteForAccount(accountId: String) = Unit
}

/**
 * FR-10's store. Recording for the same reason as the one above: the claim worth asserting is
 * that the view model writes the *account-scoped* key, which a constant could not show.
 */
private class FakeEquippableOverrideStore : EquippableOverrideStore {
    private val entries = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    val keys: Set<String> get() = entries.value.keys

    fun overridesFor(characterKey: String): Set<String> = entries.value[characterKey].orEmpty()

    override fun overrides(characterKey: String): Flow<Set<String>> =
        entries.map { it[characterKey].orEmpty() }

    override suspend fun setOverridden(
        characterKey: String,
        propertyId: String,
        overridden: Boolean,
    ) {
        entries.value = entries.value.toMutableMap().apply {
            val next = this[characterKey].orEmpty().let {
                if (overridden) it + propertyId else it - propertyId
            }
            if (next.isEmpty()) remove(characterKey) else put(characterKey, next)
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        entries.value = entries.value - characterKey
    }

    override suspend fun deleteForAccount(accountId: String) = Unit
}

/**
 * FR-6's store, as a constant. Nothing in this class flips it mid-test, and FR-18's scale is
 * pinned at the default because no view model reads it — it reaches the UI through the root
 * density provider.
 */
private class FakeAppSettingsStore(showToggles: Boolean) : AppSettingsStore {
    override val showToggles: Flow<Boolean> = flowOf(showToggles)
    override suspend fun setShowToggles(value: Boolean) = Unit
    override val uiScale: Flow<UiScale> = flowOf(UiScale.DEFAULT)
    override suspend fun setUiScale(value: UiScale) = Unit
}

private object StubTokenStore : TokenStore {
    override suspend fun save(accountId: String, token: StoredToken) = Unit
    override suspend fun read(accountId: String): StoredToken? = null
    override suspend fun delete(accountId: String) = Unit
    override suspend fun clear() = Unit
}
