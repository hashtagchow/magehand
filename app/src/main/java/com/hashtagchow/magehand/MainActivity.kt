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
