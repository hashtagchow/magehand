package com.hashtagchow.magehand.ui.panes

import com.hashtagchow.magehand.core.data.settings.PaneSurface
import com.hashtagchow.magehand.ui.navigation.CharacterHomeTab
import com.hashtagchow.magehand.ui.navigation.LocalCharacterHomeTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FR-17's chrome rules (docs/design/14-large-screen-arc.md decisions 5-10).
 *
 * ### Why these are pure functions with a test, rather than an `if` in a composable
 *
 * `:app` has no Compose test harness — `StartDestinationNavigationTest` says why there is none —
 * so a rule that lives only inside a `@Composable` can be checked in exactly two ways: on a
 * device, once, by a human; or by reading the source. Extracting the rules into
 * `PaneSelection.kt` is what makes decisions 6, 9 and 10 ordinary assertions. What genuinely
 * *cannot* be extracted — that the phone path still composes the tab row it always did — is
 * asserted by reading the source, in the manner of `UiScaleProviderTest` and `WritePostureTest`.
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
 */
class PaneSelectionTest {

    private val server = serverPaneSurfaces
    private val local = localPaneSurfaces

    // ---- the vocabulary agrees with the tab rows (decision 6) ----------------

    @Test
    fun `the persisted surfaces and the tab row are the same list in the same order`() {
        // Decision 6's display order is only "fixed" if these agree. They are two enums in two
        // modules that must stay in step, and nothing but this makes them.
        assertEquals(
            listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY, PaneSurface.SHEET),
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
        assertEquals(listOf(PaneSurface.TRACKER, PaneSurface.INVENTORY), local)
        assertFalse(PaneSurface.SHEET in local)
        assertEquals(LocalCharacterHomeTab.entries.map { it.surface }, local)
    }

    // ---- resolvePanes (decisions 6, 7, 8) -----------------------------------

    @Test
    fun `panes render in fixed display order, never in the order they were chosen`() {
        // Decision 6: "panes are places, not history". A player who added the Sheet first and the
        // Tracker second still gets Tracker on the left, because that is where the Tracker is.
        val chosenBackwards = linkedSetOf(PaneSurface.SHEET, PaneSurface.TRACKER)

        assertEquals(listOf(PaneSurface.TRACKER, PaneSurface.SHEET), resolvePanes(chosenBackwards, server))
    }

    @Test
    fun `a surface this character does not have is dropped, not drawn empty`() {
        // Reachable: a preferences file edited by hand, or — the real case — a future release
        // that lets a local character have a sheet, downgraded. Rendering an empty column would
        // be worse than not rendering it.
        val stored = setOf(PaneSurface.TRACKER, PaneSurface.SHEET)

        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(stored, local))
    }

    @Test
    fun `no stored preference is decision 8's Tracker-only default`() {
        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(emptySet(), server))
        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(emptySet(), local))
    }

    @Test
    fun `a stored set of nothing available still renders one pane`() {
        // The blank-screen case, and the reason the minimum is enforced here as well as at the
        // gesture: `resolvePanes` is the last thing between a corrupt or future-versioned
        // `pane_layout` and the screen.
        assertEquals(listOf(PaneSurface.TRACKER), resolvePanes(setOf(PaneSurface.SHEET), local))
    }

    @Test
    fun `every pane appears at most once`() {
        // The property `PaneRow`'s `key(surface)` rests on (decision 7). A `Set` in, a list with
        // no duplicates out — so two columns can never key the same scroll or collapse state,
        // and `key()` is never handed the same value twice, which Compose treats as an error
        // rather than as two siblings.
        val resolved = resolvePanes(server.toSet(), server)

        assertEquals(resolved.size, resolved.toSet().size)
        assertEquals(server, resolved)
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
        val chrome = characterHomeChrome(
            expandedWidth = false,
            selectedTab = CharacterHomeTab.Inventory,
            storedPanes = setOf(PaneSurface.TRACKER, PaneSurface.SHEET),
            available = server,
        )

        // Note what is *not* here: the stored pane set is non-empty and completely ignored.
        assertEquals(CharacterHomeChrome.Tabs(CharacterHomeTab.Inventory), chrome)
    }

    @Test
    fun `an expanded window renders the stored pane set`() {
        val chrome = characterHomeChrome(
            expandedWidth = true,
            selectedTab = CharacterHomeTab.Inventory,
            storedPanes = setOf(PaneSurface.TRACKER, PaneSurface.SHEET),
            available = server,
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
        val panes = setOf(PaneSurface.TRACKER, PaneSurface.INVENTORY)

        val onPhone = characterHomeChrome(false, tab, panes, server)
        val onTablet = characterHomeChrome(true, tab, panes, server)
        val backOnPhone = characterHomeChrome(false, tab, panes, server)
        val backOnTablet = characterHomeChrome(true, tab, panes, server)

        assertEquals(CharacterHomeChrome.Tabs(CharacterHomeTab.Sheet), onPhone)
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
        val chrome = characterHomeChrome(
            expandedWidth = true,
            selectedTab = CharacterHomeTab.Sheet,
            storedPanes = setOf(PaneSurface.INVENTORY),
            available = server,
        )

        assertEquals(CharacterHomeChrome.Panes(listOf(PaneSurface.INVENTORY)), chrome)
    }

    // ---- sheetWanted (decision 9) -------------------------------------------

    @Test
    fun `in tab mode the WebView still outlives a tab switch`() {
        // Unchanged from before FR-17: `sheetEverOpened` is sticky, so switching to the Tracker
        // tab detaches the host and keeps the booted Meteor client (04 §4).
        val tabs = CharacterHomeChrome.Tabs(CharacterHomeTab.Tracker)

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
        // That is a claim about the *shape of the composable*, which no pure function can carry,
        // so it is read out of the source — `UiScaleProviderTest`'s precedent, for its reason.
        listOf("CharacterHomeScreen.kt", "LocalCharacterHomeScreen.kt").forEach { name ->
            val source = mainSourceFiles().single { it.name == name }.readText()

            val tabsBranch = source.indexOf("is CharacterHomeChrome.Tabs ->")
            val tabRow = source.indexOf("PrimaryTabRow(")
            val panesBranch = source.indexOf("is CharacterHomeChrome.Panes ->")
            val picker = source.indexOf("PanePicker(")

            assertTrue("$name no longer composes a PrimaryTabRow", tabRow >= 0)
            assertTrue("$name no longer composes a PanePicker", picker >= 0)
            assertTrue(
                "$name must keep the tab row inside the non-expanded branch",
                tabsBranch in 0 until tabRow && tabRow < panesBranch,
            )
            assertTrue(
                "$name must keep the pane picker inside the expanded branch",
                panesBranch in 0 until picker,
            )
        }
    }

    /**
     * ### The defect: a pane that rebuilds because a *different* pane appeared
     *
     * `PaneRow` walks the list with `forEachIndexed`, and Compose's default identity for that is
     * **positional**. Panes render in a fixed display order (decision 6), so inserting one shifts
     * every pane to its right into the next slot — and a slot whose content changed is a subtree
     * Compose disposes and recreates. Turning on the Inventory pane while Tracker + Sheet are up
     * therefore destroys the Sheet's WebView and re-boots Meteor in a fresh one, several seconds
     * of blank column on the pane nobody touched, plus every scroll offset left of the insert.
     *
     * `key(surface)` is the whole fix, which is exactly why it is easy to delete as noise. There
     * is no Compose test harness in `:app` (`StartDestinationNavigationTest` says why), so this
     * is read out of the source in `UiScaleProviderTest`'s manner — structural, but structural is
     * what the property is.
     */
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

    // --- source access -------------------------------------------------------

    /** `:app`'s main source tree — `UiScaleProviderTest`'s walk, for its reason. */
    private fun mainSourceFiles(): List<File> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val root = File(dir, "src/main/java/com/hashtagchow/magehand")
            if (root.isDirectory) {
                return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not find :app sources from ${System.getProperty("user.dir")}")
    }
}
