package com.hashtagchow.magehand

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import com.hashtagchow.magehand.ui.navigation.LoginGraph
import com.hashtagchow.magehand.ui.navigation.MageHandNavHost
import com.hashtagchow.magehand.ui.navigation.MainGraph
import com.hashtagchow.magehand.ui.scale.ProvideUiScale
import com.hashtagchow.magehand.ui.window.ProvideWindowSizeGate
import com.hashtagchow.magehand.ui.theme.MageHandTheme

/**
 * The single activity hosting the whole Compose navigation graph.
 *
 * Single-activity by design: the Sheet tab's WebView instance is retained across
 * tab switches (Meteor boot is expensive) — see docs/design/01-architecture.md.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // FR-17 (14 decision 5). Outermost, and **above `ProvideUiScale`** — that order is
            // the fix for a shipped-looking bug, not a style choice.
            //
            // `currentWindowAdaptiveInfoV2()` does not read the window in dp; it reads it in
            // pixels and divides by `LocalDensity.current.density` (verified against adaptive
            // 1.3.0's bytecode). Composed *inside* the scale provider, that divisor is the
            // user's factor times the device density, so the gate sees a window shrunk by a
            // text-size preference: at 150 % every window from 840 dp up to 1259 dp reports
            // under the breakpoint, and a device sitting exactly on 840 dp is demoted by the
            // smallest step there is (110 % → 763 dp). The window did not change size; a
            // preference silently demoted the device class, which takes away pane mode and the
            // FR-19 DM entry (decision 12 gates it on this same local).
            //
            // Above the scale provider the divisor is the real device density, so the gate
            // answers a question about the *display*, which is the question decision 5 asks.
            // `PaneSelectionTest` pins the ordering and `WindowSizeGateTest` pins the
            // arithmetic that makes it matter.
            ProvideWindowSizeGate {
                // FR-18 (14 decision 1). The only composition every screen, dialog and bottom
                // sheet in the app is inside, so it is the only place a density can be provided
                // *once* — and once is the requirement, because a second nested provider would
                // compound the factor. Still outside the theme: the character screen re-enters
                // `MageHandTheme` for its accent colour, and anything provided in there would be
                // provided twice.
                val uiScale by viewModel.uiScale.collectAsStateWithLifecycle()
                ProvideUiScale(uiScale) {
                    MageHandTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background,
                        ) {
                            val start by viewModel.startDestination.collectAsStateWithLifecycle()
                            when (start.state) {
                                // One frame at most: the account store is a Room read plus a
                                // DataStore read. Better than flashing the login screen at a
                                // user who is already signed in.
                                StartState.RESOLVING -> Box(
                                    Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }

                                StartState.LOGIN -> MageHandNavHost(startDestination = LoginGraph)
                                StartState.MAIN -> MageHandNavHost(
                                    startDestination = MainGraph,
                                    initialCreatureId = start.initialCreatureId,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
