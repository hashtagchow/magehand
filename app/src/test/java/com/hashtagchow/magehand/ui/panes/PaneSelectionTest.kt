package com.hashtagchow.magehand.ui.panes

import com.hashtagchow.magehand.core.data.settings.PaneLayoutEntry
import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.mainSourceFiles
import com.hashtagchow.magehand.ui.navigation.CharacterHomeTab
import com.hashtagchow.magehand.ui.navigation.LocalCharacterHomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-17's chrome rules (docs/design/14-large-screen-arc.md decisions 5-10).
 *
 * ### Why these are pure functions with a test, rather than an `if` in a composable
 *
 * Extracting the rules into `PaneSelection.kt` is what makes decisions 6, 9 and 10 ordinary
 * assertions on pure functions. What cannot be extracted is asserted by reading the source, in the
 * manner of `UiScaleProviderTest` and `WritePostureTest`.
 *
 * FR-34 moved part of that residue into a real composition: `HomeTabRowTest` now renders the tab
 * row and asserts what it draws, selects and dispatches. What is left here is the claim a render
 * of the row cannot make — that each *home screen* composes the row inside its non-expanded branch
 * and the picker inside its expanded one, which would need the whole Hilt-wired screen — plus the
 * measuring rule below, which stays a scan because a rule that today's pixels happen to satisfy is
 * still a rule the next edit deletes as noise.
 *
 * ### The defect each group is about
 *
 * - **resolvePanes**: a stored value that renders *nothing*. An empty pane row is not a visual
 *   glitch, it is a character screen with no character on it, reachable from a preferences file
 *   written by a newer build.
 * - **togglePane**: the same screen, reached by tapping. Decision 6's minimum of one is the only
 *   thing between a player and a blank tablet.
 * - **characterHomeChrome**: decision 10's "nothing is lost either direction". The way this
 *   feature loses state is by *converting* one kind of selection into the other on the way
 *   across the gate; the way it does not is by never converting.
 * - **sheetWanted**: a WebView that outlives its column, holding a renderer and a live socket for
 *   a pane nobody can see.
 * - **resolvePaneLayout / movePane** (FR-27): an arrangement that silently loses a surface. The
 *   two shapes are a *reorder* that forgets a surface the character does not have right now (the
 *   L1 lesson, decision 5) and a *reorder* that opens panes the player never ticked (decision 2's
 *   "select/deselect unchanged"). Both are silent, permanent, and impossible to notice on the
 *   screen that caused them.
 * - **HomeTabRow** (BUG-4): a tab label that wraps mid-word. Not a rule a pure function can
 *   carry, so it is read out of the source in `UiScaleProviderTest`'s manner — and, since FR-34,
 *   also photographed: `HomeTabRowGoldenTest` captures the 3- and 4-tab rows at 100 % and 150 %,
 *   which is what makes a re-wrap visible rather than merely absent from a scan.
 */
class PaneSelectionTest {

    // FR-26 made availability a function of the character's data. These two are the "has
    // spells or actions" answer and its absence — the fixtures every pre-FR-26 test below now
    // names explicitly rather than relying on a constant.
    private val server = serverPaneSurfaces(hasActions = true)
    private val serverNoActions = serverPaneSurfaces(hasActions = false)
    private val serverTabs = serverHomeTabs(hasActions = true)
    // FR-29 made the local side a function too, for the same reason FR-26 made the server side
    // one: the Actions tab is discovery-gated on both screens now. `local` is a character WITH
    // action rows, which is what every pre-FR-29 fixture below means by "a local character".
    private val local = localPaneSurfaces(hasActions = true)
    private val localNoActions = localPaneSurfaces(hasActions = false)

    /**
     * An arrangement of open surfaces, in this order — the shape every value a released build
     * wrote had, and the shape most of these fixtures want.
     */
    private fun open(vararg surfaces: PaneSurface): List<PaneLayoutEntry> =
        surfaces.map { PaneLayoutEntry(it, selected = true) }

    /** The DiceCloud screen's own call, with a caster's surfaces — the common fixture. */
    private fun chromeFor(
        expandedWidth: Boolean,
        selectedTab: CharacterHomeTab,
        stored: List<PaneLayoutEntry>,
    ): CharacterHomeChrome<CharacterHomeTab> = characterHomeChrome(
        expandedWidth = expandedWidth,
        selectedTab = selectedTab,
        layout = resolvePaneLayout(stored, server),
        availableTabs = serverTabs,
        surfaceOf = { it.surface },
    )

    // ---- the vocabulary agrees with the tab rows (decision 6) ----------------

    @Test
    fun `the persisted surfaces and the tab row are the same list in the same order`() {
        // Decision 6's display order is only "fixed" if these agree. They are two enums in two
        // modules that must stay in step, and nothing but this makes them.
        assertEquals(
            listOf(
                PaneSurface.TRACKER,
                PaneSurface.INVENTORY,
                // FR-26 (16 decision 1): "Tracker → Inventory → Actions → Sheet". Inserted, not
                // appended — an appended constant would have drawn the Actions column to the
                // right of the Sheet's WebView with nothing failing.
                PaneSurface.ACTIONS,
                PaneSurface.SHEET,
            ),
            server,
        )
        assertEquals(CharacterHomeTab.entries.map { it.surface }, server)
        assertEquals(
            "PaneSurface's own ordinals are the display order too",
            PaneSurface.entries.toList(),
            server,
        )
    }

    @Test
    fun `an on-device character has no Sheet surface`() {
        // 09 decision 8: the WebView is never instantiated on that screen. `localPaneSurfaces` is
        // derived from a tab enum that has no Sheet constant, so this is structural — but it is
        // the structure a future edit to `LocalCharacterHomeTab` would quietly undo.
        assertEquals(listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.ACTIONS), local)
        assertFalse(PaneSurface.SHEET in local)
        assertFalse(PaneSurface.SHEET in localNoActions)
        assertEquals(LocalCharacterHomeTab.entries.map { it.surface }, local)
    }

    /**
     * FR-29 decision 3, and the retirement of 16 decision 1's local exclusion.
     *
     * That decision read *"Local characters: no Actions surface in v1 (**no local model**)"*, and
     * this test used to assert the surface could not exist at all. 18 decision 1 supplies the
     * model, so the guarantee **changes shape rather than weakening**: it is no longer "a local
     * character never has an Actions pane", it is the same discovery gate the server side has had
     * since FR-26 — *"the tab/pane appears when ≥1 action row exists"*.
     *
     * Both directions, because only one of them is the interesting one: a character with actions
     * gets the surface, and — the half that would rot silently — one without does not, which is
     * what keeps a blank column off the screen of every local character who never typed an action.
     */
    @Test
    fun `an on-device character's Actions surface is discovery-gated in both directions`() {
        assertTrue(PaneSurface.ACTIONS in local)
        assertFalse(PaneSurface.ACTIONS in localNoActions)
        assertEquals(listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY), localNoActions)
    }

    /**
     * The tab row and the pane list agree, for both answers — `serverHomeTabs`' own pin, applied
     * to the local screen.
     *
     * A tab drawn with no pane behind it, or a pane with no tab, is the bug the two-list shape
     * would otherwise introduce; both lists are derived from the same enum, so the property that
     * matters is that the *gate* is applied identically to each.
     */
    @Test
    fun `the local tab row and the local pane list agree about Actions`() {
        listOf(true, false).forEach { hasActions ->
            assertEquals(
                "tabs and panes disagree for hasActions=$hasActions",
                localPaneSurfaces(hasActions),
                localHomeTabs(hasActions).map { it.surface },
            )
        }
    }

    // ---- resolvePaneLayout / resolvePanes (decisions 6, 7, 8; FR-27 decisions 1 and 3) ------

    @Test
    fun `panes render in the player's stored order`() {
        // FR-27 decision 1. This is the one assertion whose *expected value* the feature changed:
        // before it, a stored `sheet,tracker` rendered Tracker first, because the order was the
        // enum's and the stored value was a set.
        assertEquals(
            listOf(PaneSurface.SHEET, PaneSurface.TRACKER),
            resolvePanes(open(PaneSurface.SHEET, PaneSurface.TRACKER), server),
        )
    }

    @Test
    fun `panes are still places, not history - the picker never writes an order`() {
        // Decision 6's surviving half, and where it now lives. A player who ticks the Sheet on a
        // Tracker-only character has not asked for the Sheet on the left; only an arrow does
        // that. The claim is about the WRITE, so it is asserted on `nextStoredPanes`.
        val stored = open(PaneSurface.TRACKER)
        val resolved = resolvePaneLayout(stored, server)

        val next = nextStoredPanes(resolved, stored, PaneSurface.SHEET)

        assertEquals(
            "the tapped surface is opened where it already sat, never moved to the front",
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.ACTIONS, PaneSurface.SHEET),
            next.map { it.surface },
        )
        assertEquals(
            listOf(PaneSurface.TRACKER, PaneSurface.SHEET),
            resolvePanes(next, server),
        )
    }

    @Test
    fun `a surface the player never arranged lands at its DEFAULT position`() {
        // FR-27 decision 3's "missing keys append at their DEFAULT position", which is what makes
        // the Actions surface — added to the enum in 1.9.0, absent from every string written
        // before it — appear between Inventory and Sheet rather than after the Sheet.
        val stored = open(PaneSurface.SHEET, PaneSurface.TRACKER)

        assertEquals(
            listOf(
                PaneSurface.SHEET,
                PaneSurface.TRACKER,
                // Woven in after Tracker, its nearest earlier default-order neighbour that the
                // stored list has — not appended to the end, and not at index 1.
                PaneSurface.INVENTORY,
                PaneSurface.ACTIONS,
            ),
            resolvePaneLayout(stored, server).map { it.surface },
        )
    }

    @Test
    fun `a woven-in surface is in the order but not open`() {
        // Decision 8's default survives FR-27: the tab row draws all four, the pane row draws
        // one. If a woven-in surface arrived `selected`, opening any character on a tablet would
        // show four columns.
        val resolved = resolvePaneLayout(open(PaneSurface.TRACKER), server)

        assertEquals(4, resolved.size)
        assertEquals(listOf(PaneSurface.TRACKER), resolved.filter { it.selected }.map { it.surface })
    }

    @Test
    fun `a surface this character does not have is dropped, not drawn empty`() {
        // Reachable: a preferences file edited by hand, or — the real case — a future release
        // that lets a local character have a sheet, downgraded. Rendering an empty column would
        // be worse than not rendering it.
        val stored = open(PaneSurface.TRACKER, PaneSurface.SHEET)

        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(stored, local))
        assertEquals(
            "and it is absent from the ORDER too, so no tab is drawn for it either",
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.ACTIONS),
            resolvePaneLayout(stored, local).map { it.surface },
        )
    }

    @Test
    fun `no stored preference is decision 8's Tracker-only default`() {
        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(emptyList(), server))
        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(emptyList(), local))
        // ...and FR-27 decision 3's other half: no preference is also the DEFAULT ORDER.
        assertEquals(server, resolvePaneLayout(emptyList(), server).map { it.surface })
        assertEquals(local, resolvePaneLayout(emptyList(), local).map { it.surface })
    }

    @Test
    fun `a stored set of nothing available still renders one pane`() {
        // The blank-screen case, and the reason the minimum is enforced here as well as at the
        // gesture: `resolvePanes` is the last thing between a corrupt or future-versioned
        // `pane_layout` and the screen.
        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(open(PaneSurface.SHEET), local))
    }

    @Test
    fun `the last-resort pane is the first of the PLAYER's order, not of the default one`() {
        // Rule 4, on a character who has both reordered and ended up with nothing open — the
        // wizard who kept Actions as their only pane, reordered Inventory to the front, and then
        // lost their last spell. "The first available" is still the rule; whose "first" it is is
        // the question, and the player's own leftmost surface is the more honest answer.
        val stored = listOf(
            PaneLayoutEntry(PaneSurface.INVENTORY, selected = false),
            PaneLayoutEntry(PaneSurface.TRACKER, selected = false),
            PaneLayoutEntry(PaneSurface.ACTIONS, selected = true),
        )

        assertEquals(listOf(PaneSurface.INVENTORY), resolvePanes(stored, serverNoActions))
    }

    @Test
    fun `every pane appears at most once`() {
        // The property `PaneRow`'s `key(surface)` rests on (decision 7). A `Set` in, a list with
        // no duplicates out — so two columns can never key the same scroll or collapse state,
        // and `key()` is never handed the same value twice, which Compose treats as an error
        // rather than as two siblings.
        val resolved = resolvePanes(open(*server.toTypedArray()), server)

        assertEquals(resolved.size, resolved.toSet().size)
        assertEquals(server, resolved)
        // ...including when the stored value repeats one, which the codec collapses first-wins
        // but which this must survive independently — `key()` treats a duplicate as an error.
        val repeated = resolvePaneLayout(open(PaneSurface.SHEET, PaneSurface.SHEET), server)
        assertEquals(repeated.size, repeated.map { it.surface }.toSet().size)
    }

    // ---- togglePane (decision 6) --------------------------------------------

    @Test
    fun `tapping an unchecked surface adds it`() {
        assertEquals(
            setOf(PaneSurface.TRACKER, PaneSurface.SHEET),
            togglePane(setOf(PaneSurface.TRACKER), PaneSurface.SHEET),
        )
    }

    @Test
    fun `tapping a checked surface removes it, while others remain`() {
        assertEquals(
            setOf(PaneSurface.TRACKER),
            togglePane(setOf(PaneSurface.TRACKER, PaneSurface.SHEET), PaneSurface.SHEET),
        )
    }

    @Test
    fun `deselecting the last pane is refused, and refused by returning the input`() {
        val only = setOf(PaneSurface.INVENTORY)

        val result = togglePane(only, PaneSurface.INVENTORY)

        // Decision 6's "a SET, minimum one". Identity, not just equality: the caller skips the
        // write when nothing changed, so a refused gesture must be indistinguishable from no
        // gesture all the way down to the store.
        assertSame(only, result)
    }

    // ---- nextStoredPanes (the L1 fix: persist against the STORED arrangement) ----------

    @Test
    fun `toggling a pane on a non-caster preserves a filtered-out stored preference`() {
        // The player kept Tracker + Actions on their wizard; the STORED arrangement still says
        // so. Opened on a fighter, `resolvePaneLayout` filters Actions out — and toggling
        // Inventory on must not carry that filtered view back into the store, or the wizard's
        // Actions preference is gone for good.
        val stored = open(PaneSurface.TRACKER, PaneSurface.ACTIONS)
        val resolved = resolvePaneLayout(stored, serverNoActions)
        assertEquals(
            "sanity: Actions is filtered from what renders",
            listOf(PaneSurface.TRACKER),
            openPanes(resolved),
        )

        val next = nextStoredPanes(resolved, stored, PaneSurface.INVENTORY)

        assertEquals(
            "the filtered-out Actions preference survives, AND keeps the place it had",
            listOf(PaneSurface.TRACKER, PaneSurface.ACTIONS, PaneSurface.INVENTORY, PaneSurface.SHEET),
            next.map { it.surface },
        )
        assertTrue(
            "and it is still open, not quietly deselected",
            next.single { it.surface == PaneSurface.ACTIONS }.selected,
        )
        assertEquals(
            "what renders is still just the resolved surfaces this character has",
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY),
            resolvePanes(next, serverNoActions),
        )
        // On re-discovery (the player reopens the wizard) Actions is back, unharmed and in place.
        assertEquals(
            listOf(PaneSurface.TRACKER, PaneSurface.ACTIONS, PaneSurface.INVENTORY),
            resolvePanes(next, server),
        )
    }

    @Test
    fun `REORDERING on a non-caster preserves a filtered-out stored preference and its position`() {
        // FR-27 decision 5, and the reason the L1 lesson is restated in the FR: a reorder writes
        // the WHOLE arrangement, so it is the gesture most able to erase a surface that is not on
        // screen. The wizard's Actions pane sits second; a fighter dragging the Sheet up must
        // leave it there.
        val stored = open(PaneSurface.TRACKER, PaneSurface.ACTIONS, PaneSurface.INVENTORY)
        val resolved = resolvePaneLayout(stored, serverNoActions)
        assertEquals(
            "sanity: the fighter's row has no Actions in it to move",
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.SHEET),
            resolved.map { it.surface },
        )

        val next = movePane(resolved, stored, PaneSurface.SHEET, -1)

        assertEquals(
            "Sheet moved up past Inventory; Actions kept BOTH its entry and its place",
            listOf(PaneSurface.TRACKER, PaneSurface.ACTIONS, PaneSurface.SHEET, PaneSurface.INVENTORY),
            next.map { it.surface },
        )
        assertEquals(
            "the fighter sees the move they made",
            listOf(PaneSurface.TRACKER, PaneSurface.SHEET, PaneSurface.INVENTORY),
            resolvePaneLayout(next, serverNoActions).map { it.surface },
        )
        assertEquals(
            "and the wizard's row is unchanged where it was not touched",
            listOf(PaneSurface.TRACKER, PaneSurface.ACTIONS, PaneSurface.SHEET, PaneSurface.INVENTORY),
            resolvePaneLayout(next, server).map { it.surface },
        )
    }

    @Test
    fun `a reorder opens no panes`() {
        // Decision 2's "the picker's select/deselect behavior unchanged", asserted against the
        // gesture most likely to break it. A phone player reordering their tab row has never seen
        // a pane; if an arrow ticked one, their tablet layout would silently grow a column.
        val stored = open(PaneSurface.TRACKER)
        val resolved = resolvePaneLayout(stored, server)

        val next = movePane(resolved, stored, PaneSurface.SHEET, -1)

        assertEquals(
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.SHEET, PaneSurface.ACTIONS),
            next.map { it.surface },
        )
        assertEquals(
            "still Tracker only, exactly as before the arrow was tapped",
            listOf(PaneSurface.TRACKER),
            resolvePanes(next, server),
        )
    }

    @Test
    fun `a bounce off either end of the order is not a write`() {
        // `InventoryLayoutPlan.move`'s contract, and the same signal: empty is unambiguous
        // because a real arrangement always has at least one entry.
        val stored = open(PaneSurface.TRACKER)
        val resolved = resolvePaneLayout(stored, server)

        assertTrue(movePane(resolved, stored, PaneSurface.TRACKER, -1).isEmpty())
        assertTrue(movePane(resolved, stored, PaneSurface.SHEET, 1).isEmpty())
        assertTrue(movePane(resolved, stored, PaneSurface.INVENTORY, 0).isEmpty())
        // ...and a surface this character does not have cannot be moved by a stale gesture.
        assertTrue(
            movePane(resolvePaneLayout(stored, serverNoActions), stored, PaneSurface.ACTIONS, -1)
                .isEmpty(),
        )
    }

    @Test
    fun `moving a surface shifts it, leaving every other surface in relative order`() {
        val stored = open(PaneSurface.TRACKER)
        val resolved = resolvePaneLayout(stored, server)

        val down = movePane(resolved, stored, PaneSurface.TRACKER, 1)

        // A shift, not a swap: Tracker lands between Inventory and Actions and nothing else
        // moves relative to anything else. See `movePane`'s KDoc for why that is the choice.
        assertEquals(
            listOf(PaneSurface.INVENTORY, PaneSurface.TRACKER, PaneSurface.ACTIONS, PaneSurface.SHEET),
            down.map { it.surface },
        )
    }

    @Test
    fun `resolving what a gesture wrote is a fixed point`() {
        // The app half of the spot-check's "reorder round-trip across force-stop": what comes
        // back out of the store on the next cold open must resolve to the arrangement the player
        // just made, not to one weave away from it. (`PaneLayoutStoreTest` owns the other half —
        // that the string survives the file.) A resolver that was not idempotent here would drift
        // one position per launch, which is the shape of bug nobody reproduces.
        val stored = open(PaneSurface.TRACKER)
        val moved = movePane(resolvePaneLayout(stored, server), stored, PaneSurface.SHEET, -2)

        assertEquals(moved, resolvePaneLayout(moved, server))
        assertEquals(moved, resolvePaneLayout(resolvePaneLayout(moved, server), server))
    }

    @Test
    fun `nextStoredPanes closes a currently-visible surface without moving it`() {
        val stored = open(PaneSurface.TRACKER, PaneSurface.SHEET)
        val resolved = resolvePaneLayout(stored, server)

        val next = nextStoredPanes(resolved, stored, PaneSurface.SHEET)

        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(next, server))
        assertEquals(
            "the whole order is untouched; only the Sheet's flag changed",
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.ACTIONS, PaneSurface.SHEET),
            next.map { it.surface },
        )
    }

    @Test
    fun `nextStoredPanes refuses to persist deselecting the last visible pane`() {
        val stored = open(PaneSurface.INVENTORY)
        val resolved = resolvePaneLayout(stored, server)

        assertTrue(nextStoredPanes(resolved, stored, PaneSurface.INVENTORY).isEmpty())
    }

    @Test
    fun `selecting every surface is allowed`() {
        // "maximum all" (decision 6) — there is no upper bound to enforce, and this is what would
        // notice if somebody added one.
        val all = server.fold(setOf(PaneSurface.TRACKER)) { acc, s -> togglePane(acc, s) }

        assertEquals(server.toSet(), all)
    }

    // ---- characterHomeChrome (decisions 5 and 10) ---------------------------

    @Test
    fun `a compact or medium window renders today's tab row`() {
        // Decision 5: "Medium and compact keep today's tab row untouched (this includes most
        // landscape phones — width MEDIUM)."
        val chrome = chromeFor(
            expandedWidth = false,
            selectedTab = CharacterHomeTab.Inventory,
            stored = open(PaneSurface.TRACKER, PaneSurface.SHEET),
        )

        // Note what is *not* here: which panes are OPEN is completely ignored on this path. The
        // stored ORDER is not — see the tab-order test below — which is FR-27 decision 1.
        assertEquals(CharacterHomeChrome.Tabs(serverTabs, CharacterHomeTab.Inventory), chrome)
    }

    @Test
    fun `the tab row is drawn in the player's order, and every tab is still in it`() {
        // FR-27 decision 1's phone half. The player arranged Sheet first on a tablet (or on the
        // order sheet, which is the same key); the tab row has to agree, or the two chromes are
        // two features again.
        val chrome = chromeFor(
            expandedWidth = false,
            selectedTab = CharacterHomeTab.Tracker,
            stored = open(PaneSurface.SHEET, PaneSurface.TRACKER),
        )

        assertEquals(
            // Sheet and Tracker where the player put them; Inventory and Actions woven in at
            // their default positions, and DRAWN — a tab row shows every surface, whether or not
            // the pane picker has ticked it.
            listOf(
                CharacterHomeTab.Sheet,
                CharacterHomeTab.Tracker,
                CharacterHomeTab.Inventory,
                CharacterHomeTab.Actions,
            ),
            (chrome as CharacterHomeChrome.Tabs).tabs,
        )
    }

    @Test
    fun `the tab row and the pane row agree about order`() {
        // The "ONE mechanism" claim (decision 1), stated as the property it buys: the panes are a
        // sub-sequence of the tabs, never a differently-ordered subset of them.
        val stored = listOf(
            PaneLayoutEntry(PaneSurface.SHEET, selected = true),
            PaneLayoutEntry(PaneSurface.INVENTORY, selected = false),
            PaneLayoutEntry(PaneSurface.TRACKER, selected = true),
        )
        val tabs = chromeFor(false, CharacterHomeTab.Tracker, stored) as CharacterHomeChrome.Tabs
        val panes = chromeFor(true, CharacterHomeTab.Tracker, stored) as CharacterHomeChrome.Panes

        assertEquals(
            panes.panes,
            tabs.tabs.map { it.surface }.filter { it in panes.panes },
        )
    }

    @Test
    fun `an expanded window renders the stored pane set`() {
        val chrome = chromeFor(
            expandedWidth = true,
            selectedTab = CharacterHomeTab.Inventory,
            stored = open(PaneSurface.TRACKER, PaneSurface.SHEET),
        )

        assertEquals(
            CharacterHomeChrome.Panes(listOf(PaneSurface.TRACKER, PaneSurface.SHEET)),
            chrome,
        )
    }

    @Test
    fun `crossing the gate and back preserves both the tab and the pane set`() {
        // Decision 10, in full: "entering pane mode shows the stored set, leaving it shows the
        // last single tab. Nothing is lost either direction."
        //
        // The mechanism is that these are two independent pieces of state and this function
        // reads both — so the round trip below is not a *conversion* that happens to be
        // reversible, it is two values neither of which was ever touched.
        val tab = CharacterHomeTab.Sheet
        val panes = open(PaneSurface.TRACKER, PaneSurface.INVENTORY)

        val onPhone = chromeFor(false, tab, panes)
        val onTablet = chromeFor(true, tab, panes)
        val backOnPhone = chromeFor(false, tab, panes)
        val backOnTablet = chromeFor(true, tab, panes)

        assertEquals(CharacterHomeChrome.Tabs(serverTabs, CharacterHomeTab.Sheet), onPhone)
        assertEquals(
            CharacterHomeChrome.Panes(listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY)),
            onTablet,
        )
        assertEquals("the tab selection survived the tablet layout", onPhone, backOnPhone)
        assertEquals("the pane set survived the phone layout", onTablet, backOnTablet)
    }

    @Test
    fun `entering pane mode does not seed the pane set from the selected tab`() {
        // The design that *would* lose state, named so it cannot be reintroduced as a
        // convenience: if opening a wide window seeded the panes from the current tab, every
        // rotation would overwrite the arrangement the player chose for that character.
        val chrome = chromeFor(
            expandedWidth = true,
            selectedTab = CharacterHomeTab.Sheet,
            stored = open(PaneSurface.INVENTORY),
        )

        assertEquals(CharacterHomeChrome.Panes(listOf(PaneSurface.INVENTORY)), chrome)
    }

    // ---- sheetWanted (decision 9) -------------------------------------------

    @Test
    fun `in tab mode the WebView still outlives a tab switch`() {
        // Unchanged from before FR-17: `sheetEverOpened` is sticky, so switching to the Tracker
        // tab detaches the host and keeps the booted Meteor client (04 §4).
        val tabs = CharacterHomeChrome.Tabs(serverTabs, CharacterHomeTab.Tracker)

        assertTrue(sheetWanted(tabs, sheetEverOpened = true))
        assertFalse(sheetWanted(tabs, sheetEverOpened = false))
    }

    @Test
    fun `in pane mode a deselected Sheet means no WebView at all`() {
        // Decision 9: "its lifecycle is pinned to selection (deselected = WebView destroyed)".
        // `sheetEverOpened` is deliberately true here — in pane mode it must not matter, or a
        // player who once opened the sheet would carry a renderer for the rest of the session.
        val withSheet = CharacterHomeChrome.Panes(listOf(PaneSurface.TRACKER, PaneSurface.SHEET))
        val without = CharacterHomeChrome.Panes(listOf(PaneSurface.TRACKER))

        assertTrue(sheetWanted(withSheet, sheetEverOpened = false))
        assertFalse(sheetWanted(without, sheetEverOpened = true))
    }

    // ---- the phone path, structurally (decision 5) --------------------------

    @Test
    fun `both home screens still compose the tab row, under the non-expanded branch`() {
        // Decision 5: "Phones structurally unaffected: the tab row code path does not change."
        // That is a claim about *which branch of which screen* composes the row, and the screens
        // are Hilt-wired — so it stays a source read (`UiScaleProviderTest`'s precedent) even now
        // that `:app` has a Compose harness. What the row itself *does* once composed moved to
        // `HomeTabRowTest` under FR-34; this is the half that could not go with it.
        //
        // FR-27 moved the row itself into `HomeTabRow`; the branch it sits in did not move, and
        // that is what this still asserts.
        listOf("CharacterHomeScreen.kt", "LocalCharacterHomeScreen.kt").forEach { name ->
            val source = mainSourceFiles().single { it.name == name }.readText()

            val tabsBranch = source.indexOf("is CharacterHomeChrome.Tabs ->")
            val tabRow = source.indexOf("HomeTabRow(")
            val panesBranch = source.indexOf("is CharacterHomeChrome.Panes ->")
            val picker = source.indexOf("PanePicker(")

            assertTrue("$name no longer composes a HomeTabRow", tabRow >= 0)
            assertTrue("$name no longer composes a PanePicker", picker >= 0)
            assertTrue(
                "$name must keep the tab row inside the non-expanded branch",
                tabsBranch in 0 until tabRow && tabRow < panesBranch,
            )
            assertTrue(
                "$name must keep the pane picker inside the expanded branch",
                panesBranch in 0 until picker,
            )
            assertFalse(
                "$name must not compose its own PrimaryTabRow — BUG-4's measuring rule and " +
                    "FR-27's ordering both live in HomeTabRow, and a second row would carry " +
                    "neither",
                source.contains("PrimaryTabRow("),
            )
        }
    }

    /**
     * ### BUG-4: the operator's phone drew "Inventory" as "Inventor / y"
     *
     * `PrimaryTabRow` gives every tab an equal share of the width, and FR-26's Actions tab made
     * that share a quarter. Material's own `Tab(text = …)` then spends 32 dp of it on padding and
     * lets the label **wrap** into the two-line tab slot, so on a 360-411 dp phone the longest
     * label breaks mid-word — and every UI-scale step above 100 % makes it worse.
     *
     * The ruling was *truncation over wrapping, never mid-word wrapping*. `maxLines = 1` alone
     * does not deliver it: the word still breaks and one line of the result is shown, which is
     * the same bug in a smaller box. `softWrap = false` is the half that makes wrapping
     * unrepresentable, so both are pinned, and pinned **structurally** — there is no Compose test
     * harness in `:app` (`StartDestinationNavigationTest` says why), and a measuring rule that
     * lives only inside a composable is a rule the next edit deletes as noise.
     *
     * The pane picker's segments are checked too. They are a different control with the same
     * geometry — equal-width cells over the same four labels — so they are the same defect
     * waiting on a tablet.
     */
    @Test
    fun `every equal-width chrome label is single-line and truncates rather than wrapping`() {
        val source = mainSourceFiles().single { it.name == "PaneChrome.kt" }.readText()

        listOf("HomeTabRow(", "fun PanePicker(").forEach { declaration ->
            val start = source.indexOf(declaration)
            assertTrue("PaneChrome.kt no longer declares $declaration", start >= 0)
            // Bounded by the next KDoc, so one composable's label cannot satisfy the assertion
            // for the other — every top-level declaration in this file carries one.
            val end = source.indexOf("\n/**", start).let { if (it < 0) source.length else it }
            val body = source.substring(start, end)

            listOf("maxLines = 1", "softWrap = false", "TextOverflow.Ellipsis").forEach { rule ->
                assertTrue(
                    "$declaration must carry `$rule` on its label — BUG-4: a four-tab phone " +
                        "row wrapped \"Inventory\" mid-word, and truncation is the ruling",
                    body.contains(rule),
                )
            }
        }
    }

    @Test
    fun `the pane row gives each column a stable identity`() {
        val source = mainSourceFiles().single { it.name == "PaneChrome.kt" }.readText()

        val paneRow = source.indexOf("fun PaneRow(")
        assertTrue("PaneChrome.kt no longer declares PaneRow", paneRow >= 0)

        val body = source.substring(paneRow)
        val keyed = body.indexOf("key(surface)")
        val column = body.indexOf("testTag(\"panes:column:")

        assertTrue("PaneRow must key each column by its surface, not by position", keyed >= 0)
        assertTrue("the key must wrap the column, not sit after it", keyed < column)
    }

    @Test
    fun `each home screen reads the width gate exactly once`() {
        // The gate is a `staticCompositionLocalOf` defaulting to false, so a screen that forgot
        // to read it renders the phone path — safe, but silently. A screen that read it *twice*
        // would be the beginning of two different width questions, which is what
        // `LocalExpandedWidth`'s KDoc argues against.
        listOf("CharacterHomeScreen.kt", "LocalCharacterHomeScreen.kt").forEach { name ->
            val source = mainSourceFiles().single { it.name == name }.readText()

            assertEquals(
                "$name must read LocalExpandedWidth exactly once",
                1,
                Regex("LocalExpandedWidth\\.current").findAll(source).count(),
            )
        }
    }

    @Test
    fun `exactly one place in the app provides the width gate`() {
        // `UiScaleProviderTest`'s companion assertion, for the same class of defect: a second
        // provider somewhere down the tree would make the gate mean something different on
        // different screens, and nothing would crash.
        val providers = mainSourceFiles()
            .filter { it.readText().contains("LocalExpandedWidth provides") }
            .map { it.name }

        assertEquals(
            "LocalExpandedWidth must be provided in exactly one place (found: $providers)",
            listOf("WindowSizeGate.kt"),
            providers.sorted(),
        )
    }

    /**
     * ### The B1 lesson this now defends
     *
     * This assertion used to read `scale < gate` — it *required* the gate to be composed inside
     * `ProvideUiScale`, on the reasoning that "the panes measure at the user's chosen density
     * like everything else". That reasoning is true of the panes and false of the gate, and the
     * test was pinning the bug shut.
     *
     * `currentWindowAdaptiveInfoV2()` converts the window's **pixels** with
     * `LocalDensity.current.density` (adaptive 1.3.0). Under `ProvideUiScale` that density is
     * `deviceDensity * factor`, so the gate sees a window shrunk by the user's text-size
     * preference: at 150 % every window from 840 dp to 1259 dp reports under the breakpoint, and
     * a device sitting exactly on 840 dp is demoted by the smallest step there is. Below 840 dp
     * the app silently drops to the phone tab row and the FR-19 DM entry disappears (decision 12
     * gates it on this same local) — on hardware that qualifies, with nothing in the log.
     *
     * So: **the gate must read the UNSCALED density, and changing the app scale must not change
     * the device class.** That is what the inverted ordering below buys, and
     * `WindowSizeGateTest.a scaled density shrinks the apparent window past the breakpoint` pins
     * the arithmetic behind it so this ordering cannot be read as arbitrary again.
     */
    @Test
    fun `the width gate is mounted above the scale provider, at the activity root`() {
        val root = mainSourceFiles().single { it.name == "MainActivity.kt" }.readText()

        val gate = root.indexOf("ProvideWindowSizeGate {")
        val theme = root.indexOf("MageHandTheme {")
        val scale = root.indexOf("ProvideUiScale(")

        assertTrue("the root no longer mounts ProvideWindowSizeGate", gate >= 0)
        assertTrue("the root no longer mounts ProvideUiScale", scale >= 0)
        // Above the theme, because `CharacterHomeScreen` re-enters `MageHandTheme` for its accent
        // colour and anything provided inside it would be provided twice. Both providers are.
        assertTrue("ProvideWindowSizeGate must wrap MageHandTheme, not sit inside it", gate < theme)
        assertTrue("ProvideUiScale must wrap MageHandTheme, not sit inside it", scale < theme)
        // The B1 fix, in one character: `<`, not `>`. See the KDoc.
        assertTrue(
            "ProvideWindowSizeGate must wrap ProvideUiScale — the gate reads window pixels " +
                "through LocalDensity, so composing it under the scale provider lets a text-size " +
                "setting demote the device class",
            gate < scale,
        )
    }

    /**
     * The other half of the same rule: the gate's own source must not reach for the scale.
     *
     * The ordering above is defeatable from inside `WindowSizeGate.kt` — reading `LocalUiScale`
     * or building a `Density` there would reintroduce B1 while `MainActivity` still looked
     * right. Nothing in a *window* measurement has any business naming the app's text-size
     * preference, so the honest pin is that the name does not appear.
     */
    @Test
    fun `the width gate names neither the ui scale nor a density`() {
        val source = mainSourceFiles().single { it.name == "WindowSizeGate.kt" }.readText()
        val code = source.replace(Regex("""/\*\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")

        listOf("LocalUiScale", "UiScale", "LocalDensity", "Density(").forEach { forbidden ->
            assertFalse(
                "WindowSizeGate.kt must not reference $forbidden — the gate reads the device's " +
                    "own density, never the scaled one (see the B1 lesson above)",
                code.contains(forbidden),
            )
        }
    }

    // ---- FR-26's discovery gate (16 decision 1) ------------------------------

    @Test
    fun `a character with nothing to act with has no Actions surface and no Actions tab`() {
        // The one-tab-drop rule. Until FR-26 every surface a server character could have, it
        // had — so `resolvePanes`' first rule only ever fired on the local screen. This is the
        // first data-dependent surface, and both halves have to drop together.
        assertEquals(
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.SHEET),
            serverNoActions,
        )
        assertFalse(CharacterHomeTab.Actions in serverHomeTabs(hasActions = false))

        // ...and reappear together.
        assertTrue(PaneSurface.ACTIONS in server)
        assertTrue(CharacterHomeTab.Actions in serverTabs)
    }

    @Test
    fun `the gated tab list and the gated pane list always agree`() {
        // A tab drawn without its pane, or a pane without its tab, is the bug this pair of
        // functions could introduce. They are two lists over two enums; nothing but this makes
        // them the same list.
        for (hasActions in listOf(true, false)) {
            assertEquals(
                "hasActions=$hasActions",
                serverHomeTabs(hasActions).map { it.surface },
                serverPaneSurfaces(hasActions),
            )
        }
    }

    @Test
    fun `a stored actions pane is filtered for a character without one and is not rewritten`() {
        // The player keeps Tracker + Actions on their wizard, then opens a fighter. The Actions
        // column must not render as an empty pane — and the preference must survive, so it is
        // still there when they go back to the wizard.
        val stored = open(PaneSurface.TRACKER, PaneSurface.ACTIONS)

        val onFighter = characterHomeChrome(
            expandedWidth = true,
            selectedTab = CharacterHomeTab.Tracker,
            layout = resolvePaneLayout(stored, serverNoActions),
            availableTabs = serverHomeTabs(hasActions = false),
            surfaceOf = { it.surface },
        )
        val onWizard = chromeFor(true, CharacterHomeTab.Tracker, stored)

        assertEquals(CharacterHomeChrome.Panes(listOf(PaneSurface.TRACKER)), onFighter)
        assertEquals(
            "the stored set was filtered on read, never rewritten",
            CharacterHomeChrome.Panes(listOf(PaneSurface.TRACKER, PaneSurface.ACTIONS)),
            onWizard,
        )
    }

    @Test
    fun `an actions-only stored set falls back to the tracker rather than a blank screen`() {
        // `resolvePanes` rule 3, reached for the first time on the server path.
        val chrome = characterHomeChrome(
            expandedWidth = true,
            selectedTab = CharacterHomeTab.Tracker,
            layout = resolvePaneLayout(open(PaneSurface.ACTIONS), serverNoActions),
            availableTabs = serverHomeTabs(hasActions = false),
            surfaceOf = { it.surface },
        )
        assertEquals(CharacterHomeChrome.Panes(listOf(PaneSurface.TRACKER)), chrome)
    }

    @Test
    fun `a saved Actions tab resolves to the tracker on a character that has none`() {
        // Three real ways to get here, all ordinary: backing out of a caster into a fighter, a
        // live edit removing the last spell, and EVERY cold restore onto this tab (discovery has
        // not answered yet). Without `resolveTab` the row would draw no selected tab and the
        // body would render a surface the player cannot navigate away from.
        val noActionTabs = serverHomeTabs(hasActions = false)
        val chrome = characterHomeChrome(
            expandedWidth = false,
            selectedTab = CharacterHomeTab.Actions,
            layout = resolvePaneLayout(emptyList(), serverNoActions),
            availableTabs = noActionTabs,
            surfaceOf = { it.surface },
        )
        assertEquals(CharacterHomeChrome.Tabs(noActionTabs, CharacterHomeTab.Tracker), chrome)
    }

    @Test
    fun `a saved Actions tab is kept once discovery answers`() {
        // The transient case above must not be a one-way door: `resolveTab` reads, it does not
        // write, so the saved selection is still Actions when the board arrives a frame later.
        val chrome = chromeFor(false, CharacterHomeTab.Actions, emptyList())
        assertEquals(CharacterHomeChrome.Tabs(serverTabs, CharacterHomeTab.Actions), chrome)
    }

    @Test
    fun `resolveTab leaves an available tab alone and falls back to the first otherwise`() {
        assertEquals(
            CharacterHomeTab.Sheet,
            resolveTab(CharacterHomeTab.Sheet, serverTabs),
        )
        assertEquals(
            "the fallback is 'the first available', not a named constant",
            CharacterHomeTab.Tracker,
            resolveTab(CharacterHomeTab.Actions, serverHomeTabs(hasActions = false)),
        )
    }

    @Test
    fun `the stored token for the actions surface is stable`() {
        // It is on disk on user devices from 1.9.0 onward. `PaneLayoutCodec` is keyed on this
        // string, not on the ordinal, which is what made inserting the constant safe.
        assertEquals("actions", PaneSurface.ACTIONS.key)
        assertEquals(PaneSurface.ACTIONS, PaneSurface.fromKey("actions"))
    }

    // ---- 1.9.1's app-bar decrowding (HomeOverflowMenu) -----------------------

    /**
     * The operator's screenshot: the back arrow overlapping "Short" on a bar that could carry
     * up to nine controls at once. The fix collapses the low-frequency ones behind one overflow
     * menu (`HomeOverflowMenu` in `PaneChrome.kt`, whose KDoc carries the "six elements max"
     * arithmetic) — pinned by reading the source, `PaneSelectionTest`'s own precedent for a
     * shape no pure function can carry and `:app` has no Compose test harness to click through.
     */
    @Test
    fun `both home screens route their low-frequency actions through one overflow menu`() {
        listOf("CharacterHomeScreen.kt", "LocalCharacterHomeScreen.kt").forEach { name ->
            val source = mainSourceFiles().single { it.name == name }.readText()

            assertTrue("$name no longer composes HomeOverflowMenu", source.contains("HomeOverflowMenu("))
            // The wrench/pane-order icons this screen used to draw directly for those actions —
            // now that they live behind HomeOverflowMenu, an import of either here means a
            // second control landed back on the bar itself.
            listOf("Icons.Filled.Build", "Icons.Filled.Menu").forEach { icon ->
                assertFalse("$name must not import $icon directly anymore", source.contains(icon))
            }
        }

        // The two screens' own trailing item — Settings on the DiceCloud screen, Edit on the
        // local one (09's "an Edit action in place of ... Settings") — for the same reason.
        val characterHome = mainSourceFiles().single { it.name == "CharacterHomeScreen.kt" }.readText()
        assertFalse(
            "CharacterHomeScreen.kt must not import Icons.Filled.Settings directly anymore",
            characterHome.contains("Icons.Filled.Settings"),
        )
        val localHome = mainSourceFiles().single { it.name == "LocalCharacterHomeScreen.kt" }.readText()
        assertFalse(
            "LocalCharacterHomeScreen.kt must not import Icons.Filled.Edit directly anymore",
            localHome.contains("Icons.Filled.Edit"),
        )
    }

    /**
     * FR-29's fourth `LocalRowKind` (Action) is what tipped both of the local editor's
     * `LocalRowKind.entries`-iterating rows over: a plain `Row` neither shrinks nor wraps its
     * children, so the last of four unconstrained buttons/chips squeezed toward zero width and
     * its label wrapped one character per line — the same defect class as BUG-4, on a shape no
     * pure function can carry, hence the source read rather than a Compose harness `:app` does
     * not have.
     */
    @Test
    fun `the local editor's row-kind chips and add-row buttons wrap instead of squeezing vertical`() {
        val source = mainSourceFiles().single { it.name == "LocalCharacterEditorScreen.kt" }.readText()

        assertEquals(
            "both the add-row footer and the row-kind chips must lay out LocalRowKind.entries " +
                "in a FlowRow, not a plain Row, so a fourth item wraps to a new line rather " +
                "than squeezing the others' labels vertical",
            2,
            Regex("FlowRow\\(").findAll(source).count(),
        )
    }
}
