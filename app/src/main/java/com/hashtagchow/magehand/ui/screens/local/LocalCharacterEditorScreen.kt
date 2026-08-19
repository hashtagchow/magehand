package com.hashtagchow.magehand.ui.screens.local

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets
import com.hashtagchow.magehand.core.data.local.LocalCharacterForm
import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The local character form — **creation and editing, one screen**
 * (docs/design/09-local-characters.md decision 4).
 *
 * ### What it captures, and what it deliberately does not
 *
 * Identity (name, optional level), the sheet basics (six ability scores, max HP, AC) and the
 * player's own tracker rows. Nothing else: 09's out-of-scope list keeps spells, skills, saves
 * and proficiencies out of 1.1, and the reason is that this form is the *whole* editor — every
 * field added here is a field the player has to scroll past forever.
 *
 * ### Errors
 *
 * Inline, per field, in `CredentialsScreen`'s style: `isError` on the field plus the message
 * in `colorScheme.error` underneath it. What the two screens share is the timing rule — quiet
 * until the first submit, then live. Nothing is validated *here*; every message comes from
 * `LocalCharacterForm.validate()` by way of [LocalCharacterFormState]'s per-field lookups, so
 * the rule has one home and the screen only decides where to draw it.
 *
 * ### Numbers
 *
 * Every numeric field filters its input to digits and a length cap, so the only way to reach
 * an invalid value is to leave the box empty — which is a state the player can see and the
 * validator can name. There is no silent substitution of a default for a box the player
 * cleared.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun LocalCharacterEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalCharacterEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    // Keyed on the id so this fires once per save, not once per recomposition after one.
    LaunchedEffect(uiState.savedId) {
        if (uiState.savedId != null) onSaved()
    }
    LaunchedEffect(uiState.isMissing) {
        if (uiState.isMissing) onBack()
    }

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (uiState.isEditing) {
                                R.string.title_edit_local_character
                            } else {
                                R.string.title_new_local_character
                            },
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (uiState.isEditing) {
                        IconButton(
                            onClick = { confirmingDelete = true },
                            modifier = Modifier.testTag("local:delete"),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_delete),
                            )
                        }
                    }
                    TextButton(
                        onClick = viewModel::save,
                        modifier = Modifier.testTag("local:save"),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .semantics { testTagsAsResourceId = true },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::setName,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("local:field:name"),
                singleLine = true,
                label = { Text(stringResource(R.string.local_field_name)) },
                isError = uiState.nameErrorRes != null,
                supportingText = { FieldError(uiState.nameErrorRes) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            NumberField(
                value = uiState.level,
                onValueChange = viewModel::setLevel,
                labelRes = R.string.local_field_level,
                errorRes = uiState.levelErrorRes,
                maxDigits = 2,
                testTag = "local:field:level",
                // The only optional field on the form, and the only one whose empty box is
                // not an error — so it is the only one that says so.
                placeholderRes = R.string.local_field_optional,
            )

            SectionHeader(stringResource(R.string.local_section_abilities))

            // Two rows of three, in sheet order — the arrangement every 5e character sheet
            // prints, so the player finds CON where their eye already expects it.
            Ability.entries.chunked(3).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { ability ->
                        NumberField(
                            value = uiState.abilities[ability].orEmpty(),
                            onValueChange = { viewModel.setAbility(ability, it) },
                            label = ability.name,
                            errorRes = uiState.abilityErrorRes(ability),
                            maxDigits = 2,
                            testTag = "local:field:ability:${ability.name}",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            SectionHeader(stringResource(R.string.local_section_combat))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = uiState.maxHp,
                    onValueChange = viewModel::setMaxHp,
                    labelRes = R.string.local_field_max_hp,
                    errorRes = uiState.maxHpErrorRes,
                    maxDigits = 4,
                    testTag = "local:field:maxHp",
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    value = uiState.armorClass,
                    onValueChange = viewModel::setArmorClass,
                    labelRes = R.string.local_field_armor_class,
                    errorRes = uiState.armorClassErrorRes,
                    maxDigits = 2,
                    testTag = "local:field:armorClass",
                    modifier = Modifier.weight(1f),
                )
            }

            SectionHeader(stringResource(R.string.local_section_rows))
            Text(
                text = stringResource(R.string.local_rows_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            uiState.rows.forEachIndexed { index, row ->
                RowEditor(
                    index = index,
                    row = row,
                    labelErrorRes = uiState.rowLabelErrorRes(index),
                    totalErrorRes = uiState.rowTotalErrorRes(index),
                    onKind = { viewModel.setRowKind(index, it) },
                    onLabel = { viewModel.setRowLabel(index, it) },
                    onTotal = { viewModel.setRowTotal(index, it) },
                    onReset = { viewModel.setRowReset(index, it) },
                    onRemove = { viewModel.removeRow(index) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LocalRowKind.entries.forEach { kind ->
                    TextButton(
                        onClick = { viewModel.addRow(kind) },
                        modifier = Modifier.testTag("local:add-row:${kind.storedValue}"),
                    ) {
                        Text(stringResource(kind.addLabelRes))
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        // "no destructive action without undo or confirm" (04, UX principles), and this one
        // has no undo: 09 decision 10 keeps local characters out of every backup, so deleting
        // one is the end of it. Same shape as the rest confirm — say what will happen, say it
        // cannot be reversed, and put the warning in the error colour.
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.local_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.local_delete_body, uiState.name.trim()))
                    Text(
                        text = stringResource(R.string.local_delete_not_undoable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.delete(onDeleted)
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * One tracker row's editor (09 decision 4's three kinds).
 *
 * The kind chips are first because they change what the rest of the card means: a slot has a
 * total, an item has a quantity, and only a resource has a reset rule. Rendering the reset
 * chips for the other two would be offering a control whose value is discarded on save (see
 * [LocalRowFormState.toRowForm]).
 */
@Composable
private fun RowEditor(
    index: Int,
    row: LocalRowFormState,
    @StringRes labelErrorRes: Int?,
    @StringRes totalErrorRes: Int?,
    onKind: (LocalRowKind) -> Unit,
    onLabel: (String) -> Unit,
    onTotal: (String) -> Unit,
    onReset: (ResetRule?) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    LocalRowKind.entries.forEach { kind ->
                        FilterChip(
                            selected = row.kind == kind,
                            onClick = { onKind(kind) },
                            label = { Text(stringResource(kind.labelRes)) },
                        )
                    }
                }
                TextButton(
                    onClick = onRemove,
                    modifier = Modifier.testTag("local:row:$index:remove"),
                ) {
                    Text(stringResource(R.string.action_remove))
                }
            }

            OutlinedTextField(
                value = row.label,
                onValueChange = onLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("local:row:$index:label"),
                singleLine = true,
                label = { Text(stringResource(R.string.local_field_row_label)) },
                isError = labelErrorRes != null,
                supportingText = { FieldError(labelErrorRes) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            NumberField(
                value = row.total,
                onValueChange = onTotal,
                labelRes = if (row.kind == LocalRowKind.ITEM) {
                    R.string.local_field_quantity
                } else {
                    R.string.local_field_total
                },
                errorRes = totalErrorRes,
                maxDigits = row.totalRange.last.toString().length,
                testTag = "local:row:$index:total",
            )

            if (row.kind == LocalRowKind.RESOURCE) {
                Text(
                    text = stringResource(R.string.local_field_reset),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // `null` first: "none" is the default and the most common answer, and
                    // 09 decision 4 lists it as one of the three options rather than as the
                    // absence of the other two.
                    (listOf(null) + ResetRule.entries).forEach { rule ->
                        FilterChip(
                            selected = row.reset == rule,
                            onClick = { onReset(rule) },
                            label = { Text(stringResource(rule.resetLabelRes)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * A digits-only field with its inline error.
 *
 * The filter is the point: `keyboardType = Number` is a *hint* to the IME, not a constraint —
 * a hardware keyboard, a paste, or several third-party keyboards will all happily deliver
 * letters. Rejecting them at `onValueChange` is what makes "the box is empty" the only invalid
 * state the rest of the screen has to reason about.
 */
@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    errorRes: Int?,
    maxDigits: Int,
    testTag: String,
    modifier: Modifier = Modifier,
    @StringRes labelRes: Int? = null,
    label: String? = null,
    @StringRes placeholderRes: Int? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { typed ->
            if (typed.length <= maxDigits && typed.all(Char::isDigit)) onValueChange(typed)
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
        singleLine = true,
        label = { Text(label ?: stringResource(labelRes!!)) },
        placeholder = placeholderRes?.let { { Text(stringResource(it)) } },
        isError = errorRes != null,
        supportingText = { FieldError(errorRes) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next,
        ),
    )
}

/**
 * The inline message, or nothing.
 *
 * Always *called* — an `OutlinedTextField` that gains a `supportingText` only when it is wrong
 * grows a line of height at the moment of the error and shoves every field below it down the
 * screen, which is how a validation message ends up scrolled past. Rendering an empty slot
 * costs the same space either way and the layout stops moving.
 */
@Composable
private fun FieldError(@StringRes errorRes: Int?) {
    Text(
        text = errorRes?.let { stringResource(it, *errorArgs(it)) }.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * The range numbers a message needs, read off `LocalCharacterForm`'s own constants.
 *
 * So that "Level must be between 1 and 20" cannot go on saying 20 after the range moves: the
 * bound the validator enforces is the bound the sentence prints.
 */
private fun errorArgs(@StringRes errorRes: Int): Array<Any> = when (errorRes) {
    R.string.local_error_level -> arrayOf(
        LocalCharacterForm.LEVEL_RANGE.first,
        LocalCharacterForm.LEVEL_RANGE.last,
    )

    R.string.local_error_ability -> arrayOf(
        LocalCharacterForm.ABILITY_RANGE.first,
        LocalCharacterForm.ABILITY_RANGE.last,
    )

    R.string.local_error_max_hp -> arrayOf(LocalCharacterForm.MIN_MAX_HP)

    R.string.local_error_armor_class -> arrayOf(
        LocalCharacterForm.ARMOR_CLASS_RANGE.first,
        LocalCharacterForm.ARMOR_CLASS_RANGE.last,
    )

    R.string.local_error_row_total -> arrayOf(
        LocalCharacterForm.COUNTED_TOTAL_RANGE.first,
        LocalCharacterForm.COUNTED_TOTAL_RANGE.last,
    )

    R.string.local_error_row_quantity -> arrayOf(
        LocalCharacterForm.ITEM_QUANTITY_RANGE.first,
        LocalCharacterForm.ITEM_QUANTITY_RANGE.last,
    )

    else -> emptyArray()
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 4.dp),
    )
}

/** The kind chips' labels. The tracker's own words for the same three groups. */
private val LocalRowKind.labelRes: Int
    @StringRes get() = when (this) {
        LocalRowKind.SLOT -> R.string.local_kind_slot
        LocalRowKind.RESOURCE -> R.string.local_kind_resource
        LocalRowKind.ITEM -> R.string.local_kind_item
    }

/** The "add a row of this kind" buttons. */
private val LocalRowKind.addLabelRes: Int
    @StringRes get() = when (this) {
        LocalRowKind.SLOT -> R.string.local_add_slot
        LocalRowKind.RESOURCE -> R.string.local_add_resource
        LocalRowKind.ITEM -> R.string.local_add_item
    }

/** `null` is "none" — 09 decision 4's third reset option, named rather than implied. */
private val ResetRule?.resetLabelRes: Int
    @StringRes get() = when (this) {
        null -> R.string.local_reset_none
        ResetRule.SHORT_REST -> R.string.local_reset_short
        ResetRule.LONG_REST -> R.string.local_reset_long
    }
