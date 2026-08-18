package com.hashtagchow.magehand.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.account.AccountRepository
import com.hashtagchow.magehand.core.data.api.ApiException
import javax.inject.Inject

/**
 * @param error the server-facing failure, verbatim from [ApiException.message].
 * @param hint the "wrong server vs wrong password" disambiguation
 *   docs/design/04-screens-ux.md §1 asks for, or `null` when the error speaks for
 *   itself.
 */
data class CredentialsUiState(
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val hint: String? = null,
)

/**
 * Screen 1b.
 *
 * The password never reaches this class's state: it is passed straight into
 * [signIn] as a parameter, handed to `AccountRepository.addAccount`, and dropped
 * (docs/design/05-security.md §"Token & credential handling"). Holding it in a
 * `StateFlow` would put it in a `ViewModel` that survives configuration changes
 * and can be dumped by tooling; there is no reason to.
 */
@HiltViewModel
class CredentialsViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CredentialsUiState())
    val uiState: StateFlow<CredentialsUiState> = _uiState.asStateFlow()

    /**
     * @param serverInput the custom-server field, verbatim; blank means the
     *   official [DEFAULT_SERVER_URL]. Normalization/validation stays in
     *   `normalizeServerUrl` down in the repository path.
     */
    fun signIn(
        serverInput: String,
        usernameOrEmail: String,
        password: String,
        onSignedIn: () -> Unit,
    ) {
        if (_uiState.value.isSubmitting) return
        val serverUrl = serverInput.trim().ifEmpty { DEFAULT_SERVER_URL }
        _uiState.update { it.copy(isSubmitting = true, error = null, hint = null) }
        viewModelScope.launch {
            accountRepository.addAccount(serverUrl, usernameOrEmail.trim(), password)
                .onSuccess {
                    _uiState.update { it.copy(isSubmitting = false) }
                    onSignedIn()
                }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = throwable.message ?: GENERIC_ERROR,
                            hint = hintFor(throwable),
                        )
                    }
                }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null, hint = null) }
    }

    /**
     * The single most useful thing this screen can do is tell the user *which*
     * field is wrong. WP3 made that decidable by giving every failure its own
     * type; this turns the type into a sentence.
     */
    private fun hintFor(throwable: Throwable): String? = when (throwable) {
        is ApiException.InvalidCredentials ->
            "Double-check your username (or email) and password — they are the ones you use on the DiceCloud website."

        is ApiException.ServerUnreachable ->
            "The address is probably wrong, or this device has no connection. Go back and check the server."

        is ApiException.NotADiceCloudServer ->
            "That address answered, but it isn't DiceCloud. Go back and check the server."

        is ApiException.InvalidServerUrl ->
            "Go back and fix the server address."

        is ApiException.TooManyRequests ->
            "Wait a few seconds before trying again."

        is ApiException.ServerError ->
            "Nothing is wrong with your details — the server itself is unhappy. Try again shortly."

        is ApiException.SecureStorageUnavailable ->
            "Your details were correct — this device's keystore refused to store the session. Restart the device and try again."

        else -> null
    }

    companion object {
        /** The official DiceCloud instance — the only server the app ships knowledge of. */
        const val DEFAULT_SERVER_URL = "https://dicecloud.com"
        private const val GENERIC_ERROR = "Sign-in failed."
    }
}
