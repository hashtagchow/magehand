package com.hashtagchow.magehand.ui.screens.local

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.core.data.local.LocalCharacterRepository
import com.hashtagchow.magehand.core.data.local.LocalSaveResult
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule
import javax.inject.Inject

/**
 * The creation/edit screen (docs/design/09-local-characters.md decision 4).
 *
 * **One screen, both jobs.** 09 is explicit that there is no separate sheet editor: editing is
 * re-opening this form via `LocalCharacterRepository.formFor(id)` and saving it again. So this
 * has no "update" path — [save] calls the same repository method either way, and the only
 * thing `characterId` changes is whether [LocalCharacterFormState.id] is null.
 *
 * Validation is *not* re-implemented here. [LocalCharacterFormState.errors] delegates to
 * `LocalCharacterForm.validate`, and [save] does not pre-check at all: it hands the form over
 * and reads [LocalSaveResult.Invalid] back. That is what stops the screen's idea of "valid"
 * from drifting from the repository's, which is the drift that lets a form show green and
 * write nothing.
 */
@HiltViewModel
class LocalCharacterEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LocalCharacterRepository,
) : ViewModel() {

    /** `null` when creating. Type-safe routes store each component under its property name. */
    private val characterId: String? = savedStateHandle["characterId"]

    private val _uiState = MutableStateFlow(LocalCharacterFormState(isLoading = characterId != null))
    val uiState: StateFlow<LocalCharacterFormState> = _uiState.asStateFlow()

    init {
        if (characterId != null) {
            viewModelScope.launch {
                val form = repository.formFor(characterId)
                _uiState.value = if (form == null) {
                    // Deleted from under this screen (another entry point, or a back stack
                    // restored after the character was removed). Backing out is the only
                    // honest answer: there is nothing to edit and creating a *new* character
                    // out of an edit intent would be a surprise.
                    LocalCharacterFormState(isMissing = true)
                } else {
                    LocalCharacterFormState.from(form)
                }
            }
        }
    }

    // --- field edits ------------------------------------------------------------

    fun setName(value: String) = edit { it.copy(name = value) }

    fun setLevel(value: String) = edit { it.copy(level = value) }

    fun setAbility(ability: Ability, value: String) =
        edit { it.copy(abilities = it.abilities + (ability to value)) }

    fun setMaxHp(value: String) = edit { it.copy(maxHp = value) }

    fun setArmorClass(value: String) = edit { it.copy(armorClass = value) }

    // --- rows -------------------------------------------------------------------

    fun addRow(kind: LocalRowKind) = edit { it.copy(rows = it.rows + LocalRowFormState.new(kind)) }

    /**
     * Removes a row by position.
     *
     * By index and not by id because a freshly added row has no id yet, and 09 decision 4's
     * error indices are positional too — a remove that shifted the list without the errors
     * following would point every message below it at the wrong field. The whole list is
     * re-validated on the next read, so they follow.
     */
    fun removeRow(index: Int) = edit { state ->
        state.copy(rows = state.rows.filterIndexed { i, _ -> i != index })
    }

    fun setRowKind(index: Int, kind: LocalRowKind) = editRow(index) { it.copy(kind = kind) }

    fun setRowLabel(index: Int, label: String) = editRow(index) { it.copy(label = label) }

    fun setRowTotal(index: Int, total: String) = editRow(index) { it.copy(total = total) }

    fun setRowReset(index: Int, reset: ResetRule?) = editRow(index) { it.copy(reset = reset) }

    /** FR-10b (13 decision 9). Kept on the state for every kind; dropped on save for all but
     * an item — see [LocalRowFormState.toRowForm]. */
    fun setRowCategory(index: Int, category: CatalogCategory) =
        editRow(index) { it.copy(category = category) }

    // --- save / delete ----------------------------------------------------------

    /**
     * Validates and writes, in that order, in `:core:data`.
     *
     * [LocalCharacterFormState.showErrors] is turned on **before** the result comes back, so a
     * rejected save paints the fields immediately rather than a frame later; on success it is
     * irrelevant, because the screen has left.
     */
    fun save() {
        val state = _uiState.value
        _uiState.update { it.copy(showErrors = true) }
        viewModelScope.launch {
            when (val result = repository.save(state.toForm())) {
                is LocalSaveResult.Saved -> _uiState.update { it.copy(savedId = result.id) }
                // Nothing to copy across: `errors` is derived from the state the user is
                // looking at, and it says the same thing this result does. Storing the
                // returned list as well would give the screen two sources for one question.
                is LocalSaveResult.Invalid -> Unit
                // Deleted between opening this form and saving it. The same answer the load
                // path gives for the same condition (see `init`), so the screen backs out
                // rather than leaving the player editing something that is not there.
                is LocalSaveResult.Missing -> _uiState.value = LocalCharacterFormState(isMissing = true)
            }
        }
    }

    /** The confirm dialog is the screen's job and has already been answered by the time this runs. */
    fun delete(onDeleted: () -> Unit) {
        val id = characterId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDeleted()
        }
    }

    /** Any edit clears nothing but the value it touches; see [LocalCharacterFormState.showErrors]. */
    private fun edit(block: (LocalCharacterFormState) -> LocalCharacterFormState) {
        _uiState.update(block)
    }

    private fun editRow(index: Int, block: (LocalRowFormState) -> LocalRowFormState) = edit { state ->
        state.copy(rows = state.rows.mapIndexed { i, row -> if (i == index) block(row) else row })
    }
}
