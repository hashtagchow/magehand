package com.hashtagchow.magehand.ui.screens.characterhome

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets
import com.hashtagchow.magehand.ui.webview.SheetSession
import com.hashtagchow.magehand.ui.webview.SheetSessionFactory
import com.hashtagchow.magehand.ui.webview.SheetWebViewHost
import com.hashtagchow.magehand.ui.webview.rememberSheetWebViewState
import javax.inject.Inject

/**
 * The character list's "New character" FAB target (docs/design/04-screens-ux.md
 * §2): the DiceCloud PWA's own creator, in the same token-injected WebView the
 * Sheet tab uses. A native creator is explicitly out of v1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterCreatorScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CharacterCreatorViewModel = hiltViewModel(),
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val sheetState = rememberSheetWebViewState(session)

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_new_character)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            if (sheetState == null) {
                CircularProgressIndicator()
            } else {
                SheetWebViewHost(state = sheetState, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@HiltViewModel
class CharacterCreatorViewModel @Inject constructor(
    sheetSessionFactory: SheetSessionFactory,
) : ViewModel() {

    val session: StateFlow<SheetSession?> =
        sheetSessionFactory.sessions { SheetSessionFactory.CREATOR_PATH }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
