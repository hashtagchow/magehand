package com.hashtagchow.magehand.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.hashtagchow.magehand.ui.screens.characterhome.CharacterCreatorScreen
import com.hashtagchow.magehand.ui.screens.characterhome.CharacterHomeScreen
import com.hashtagchow.magehand.ui.screens.characterlist.CharacterListScreen
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
    // Keyed on the id and run once: `LaunchedEffect` inside the NavHost's own composition
    // would re-fire on every recomposition of the graph.
    LaunchedEffect(initialCreatureId) {
        if (initialCreatureId != null) navController.navigate(CharacterHome(initialCreatureId))
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
