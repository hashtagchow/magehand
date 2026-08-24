package com.hashtagchow.magehand.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.hashtagchow.magehand.ui.screens.characterhome.CharacterCreatorScreen
import com.hashtagchow.magehand.ui.screens.characterhome.CharacterHomeScreen
import com.hashtagchow.magehand.ui.screens.characterlist.CharacterListScreen
import com.hashtagchow.magehand.ui.screens.dmview.DmViewScreen
import com.hashtagchow.magehand.ui.screens.local.LocalCharacterEditorScreen
import com.hashtagchow.magehand.ui.screens.local.LocalCharacterHomeScreen
import com.hashtagchow.magehand.ui.screens.login.CredentialsScreen
import com.hashtagchow.magehand.ui.screens.settings.SettingsScreen

/**
 * The app's single navigation graph (docs/design/04-screens-ux.md).
 *
 * [startDestination] is resolved by `MainViewModel` before this is composed:
 * [MainGraph] when a valid account exists, [LoginGraph] otherwise.
 *
 * @param initialCreatureId 04's "start destination: last-used character's Tracker".
 *   Implemented as a one-shot navigation on top of [CharacterList] rather than by making
 *   [CharacterHome] the graph's start: the list has to stay underneath, or the first Back
 *   press from a character the user was auto-dropped into leaves the app.
 */
@Composable
fun MageHandNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: Destination = LoginGraph,
    initialCreatureId: String? = null,
) {
    /**
     * Whether the auto-open has already happened — **saved**, and that is the whole fix for the
     * 1.4.0 sweep's rotation defect.
     *
     * ### What went wrong
     *
     * The guard used to be `LaunchedEffect(initialCreatureId)` and nothing else, on the
     * reasoning that keying the effect makes it run once. It does — *once per composition*. An
     * Activity recreation (rotation, and a uiMode change for the same reason) tears the whole
     * composition down and builds a new one, so the effect fired **again**, with the same
     * non-null id, and called `navigate` a second time.
     *
     * `rememberNavController` had meanwhile restored the back stack, so the user was already on
     * `CharacterHome`. The second navigate therefore pushed a *duplicate* entry on top of the
     * restored one — visually identical, and a different [androidx.navigation.NavBackStackEntry].
     * Every `rememberSaveable` inside the screen is scoped to that entry's
     * `SaveableStateProvider`, so a brand-new entry starts them all at their defaults: the
     * selected tab snapped back to Tracker, and the open detail sheet, the "sheet ever opened"
     * latch and the wallet's expansion went with it. Back would also have returned to the same
     * character rather than to the list.
     *
     * Nothing was wrong with the tab state itself — the enum saves fine, and
     * `LocalCharacterHomeScreen` (which nothing auto-navigates to) never showed the defect. It
     * was the *entry underneath it* being replaced.
     *
     * ### Why a saved flag rather than `launchSingleTop`
     *
     * `launchSingleTop = true` would also have avoided the duplicate, but only while
     * `CharacterHome` happened to be on top: rotating after backing out to the list would
     * re-open the character the user had just left. The claim this feature makes is "open the
     * last-used character **when the app starts**", which is a thing that happens once per
     * launch and not once per configuration — so the flag is the honest expression of it, and it
     * survives real process death for the same reason it survives a rotation.
     */
    var initialCharacterOpened by rememberSaveable { mutableStateOf(false) }

    // Still keyed on the id, which is what stops a re-fire on an ordinary recomposition; the
    // saved flag is what stops one on an Activity recreation. Two different lifetimes, two
    // different guards — see [shouldOpenInitialCharacter].
    LaunchedEffect(initialCreatureId) {
        if (shouldOpenInitialCharacter(initialCreatureId, initialCharacterOpened)) {
            initialCharacterOpened = true
            navController.navigate(CharacterHome(requireNotNull(initialCreatureId)))
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        // ---- LoginGraph: Credentials (official server default) ----
        navigation<LoginGraph>(startDestination = Credentials) {
            composable<Credentials> {
                CredentialsScreen(
                    onSignedIn = {
                        navController.navigate(MainGraph) {
                            popUpTo(LoginGraph) { inclusive = true }
                        }
                    },
                    // FR-5's whole premise is "use the app without a DiceCloud login", and
                    // this is the only door to it on a fresh install: without it a signed-out
                    // user cannot reach the character list, and 09 decision 3's "the list
                    // screen must render with zero accounts" would be unreachable code.
                    // Same navigation as a successful sign-in, because the destination is
                    // the same place — the difference is only how you got there.
                    onContinueWithoutAccount = {
                        navController.navigate(MainGraph) {
                            popUpTo(LoginGraph) { inclusive = true }
                        }
                    },
                )
            }
        }

        // ---- MainGraph: CharacterList <-> CharacterHome, plus Settings ----
        navigation<MainGraph>(startDestination = CharacterList) {
            composable<CharacterList> {
                CharacterListScreen(
                    onCharacterClick = { creatureId ->
                        navController.navigate(CharacterHome(creatureId))
                    },
                    onSettingsClick = { navController.navigate(Settings) },
                    onNewCharacterClick = { navController.navigate(CharacterCreator) },
                    onLocalCharacterClick = { characterId ->
                        navController.navigate(LocalCharacterHome(characterId))
                    },
                    onNewLocalCharacterClick = { navController.navigate(LocalCharacterEditor()) },
                    // FR-19 (14 decisions 11 and 12). The list is the entry point because it is
                    // where the membership is chosen — the picker needs the `characterList` rows
                    // this screen already holds, and decision 17 requires the set to be settled
                    // *before* the dashboard subscribes to it. See `DmPickerState`.
                    onDmViewClick = { navController.navigate(DmView) },
                )
            }

            composable<CharacterHome> {
                // creatureId reaches the ViewModel through SavedStateHandle, so the
                // screen never has to thread it down by hand.
                CharacterHomeScreen(
                    onBack = { navController.popBackStack() },
                    onSettingsClick = { navController.navigate(Settings) },
                )
            }

            composable<CharacterCreator> {
                CharacterCreatorScreen(onClose = { navController.popBackStack() })
            }

            // ---- FR-5: local characters (docs/design/09-local-characters.md) ----
            composable<LocalCharacterHome> {
                // characterId reaches the ViewModel through SavedStateHandle, exactly as
                // CharacterHome's creatureId does.
                LocalCharacterHomeScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { characterId ->
                        navController.navigate(LocalCharacterEditor(characterId))
                    },
                )
            }

            composable<LocalCharacterEditor> {
                LocalCharacterEditorScreen(
                    onBack = { navController.popBackStack() },
                    // Both jobs pop: a create lands back on the list with the new character
                    // in "On this device", and an edit lands back on the tracker it came
                    // from. One rule, and neither leaves a form on the back stack that a
                    // Back press would re-enter after it has already been saved.
                    onSaved = { navController.popBackStack() },
                    // Delete cannot pop: the screen underneath an edit is the deleted
                    // character's own tracker, which would render an empty board with its
                    // name gone. Back out to the list instead.
                    onDeleted = { navController.popBackStack(CharacterList, inclusive = false) },
                )
            }

            // ---- FR-19: the DM dashboard (docs/design/14-large-screen-arc.md) ----
            composable<DmView> {
                DmViewScreen(
                    onBack = { navController.popBackStack() },
                    // Decision 12: "tapping a card opens the full character as today" — the very
                    // same destination the list opens, so a character reached from the dashboard
                    // is not a second kind of character screen with its own state and its own
                    // bugs. Back from it lands on the dashboard, which is still subscribed.
                    onCharacterClick = { creatureId ->
                        navController.navigate(CharacterHome(creatureId))
                    },
                )
            }

            composable<Settings> {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onSignedOut = {
                        navController.navigate(LoginGraph) {
                            popUpTo(MainGraph) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

/**
 * Whether the start-destination auto-open should fire (04's "last-used character's Tracker").
 *
 * Extracted from the composable so the *rule* has a test. What it states is the thing the
 * 1.4.0 sweep found missing: the auto-open is once per **app start**, not once per composition
 * — so a second ask with the flag already set must answer `false`, whatever the id says.
 *
 * ### What this pins, and what it honestly cannot
 *
 * It pins the predicate. It does **not** pin that `rememberSaveable` actually restores the flag
 * across an Activity recreation — that is Compose's own machinery talking to the Activity's
 * saved-state bundle, `:app` has no Compose or Robolectric harness, and a JVM test asserting it
 * would be asserting a mock. **The device is the proof**, and it is on the sweep as L10 with
 * the pre-fix failure recorded beside it.
 *
 * @param alreadyOpened the saved flag: `false` on a genuine app start, `true` on every
 *   composition that follows one within the same task.
 */
internal fun shouldOpenInitialCharacter(
    initialCreatureId: String?,
    alreadyOpened: Boolean,
): Boolean = initialCreatureId != null && !alreadyOpened
