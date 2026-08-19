package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CatalogItem
import com.hashtagchow.magehand.core.model.NewItemSpec
import com.hashtagchow.magehand.ui.components.CategoryChooser

/**
 * The add-item flow (docs/design/10-inventory.md decision 6): a curated catalog with a search
 * box, and a custom form for everything else.
 *
 * ### Why there is no SRD library search
 *
 * Because it cannot work. The probe established that `searchLibraryNodes` matches on `name`
 * or an exact tag, and SRD items are `reference` nodes carrying **no top-level name** — so a
 * name search for "torch" returns nothing, for every item in the library. Decision 6 records
 * that path as deliberately out of scope rather than shipping a search box that is empty by
 * construction. What the search box here filters is `ItemCatalog`, thirty-odd entries already
 * in the app; see [catalogMatches].
 *
 * ### Why a catalog tap adds immediately
 *
 * The catalog exists to be *faster than typing*, and a confirm step on a list whose entries
 * already carry a name, a weight, a price and a sensible default quantity would give that
 * back. Bundled goods (arrows, pitons) carry `defaultQuantity`, so the common case needs no
 * number at all, and the detail sheet's stepper is one tap away for the uncommon one.
 *
 * ### Why the sheet says the add cannot be undone
 *
 * Because it cannot, and the player has no other way to find out. `addItem` is deliberately
 * not undoable — the inverse would be a soft-remove and item deletion is fenced out of this
 * release (decision 12) — so the confirmation snackbar carries no UNDO action. A control that
 * silently behaves differently from every other write in the app is worth one sentence.
 *
 * ### Why the category chooser is on one kind of character only
 *
 * 13 decision 9 collects a category so that a **local** row can be filed under Weapons, Armor
 * or Gear; a DiceCloud item is classified by its `tags`, and 13 lists server-side category
 * editing as explicitly **out of scope**. `NewItemSpec.category` is therefore discarded on the
 * server path — `WriteOp.insertItem` builds the insert body from the tags and never writes it
 * — so a chooser drawn there would be a control that reads back a state nothing stored: the
 * player picks *Weapon*, saves, and the item lands in Gear with no explanation.
 *
 * Wave FR-10b offered it on both kinds on the argument that a control appearing and
 * disappearing is a second add form to explain. That trade is the wrong way round here,
 * because the majority path is the server one: the commit's own rule is that a control must
 * not lie, and this one lied on the path most players are on. So it is gated on [isLocal], and
 * the gate lives in [AddItemFormState] rather than in this `if` — see `offersCategoryChooser`.
 *
 * @param onAdd handed a validated [NewItemSpec]. Both paths produce one; `OpenCharacter.addItem`
 *   neither knows nor cares which it came from.
 * @param isLocal whether the character being added to is an on-device one. `true` from
 *   `LocalCharacterHomeScreen`, `false` from `CharacterHomeScreen`. It decides one thing — the
 *   category chooser above — and it is a required parameter rather than a defaulted one so a
 *   third call site cannot silently take the wrong half of that decision.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AddItemSheet(
    isLocal: Boolean,
    onAdd: (NewItemSpec) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    // Which half of the sheet is showing. `rememberSaveable` so a rotation mid-form does not
    // throw the player back to the list — but the *form* below is plain `remember`, matching
    // the query: a half-typed item is not a preference, and the sheet is dismissed on save.
    var custom by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var form by remember { mutableStateOf(AddItemFormState(isLocal = isLocal)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        LazyColumn(
            // The probe caught the same defect on the customize sheet: without `imePadding`
            // the keyboard covers the very fields the player is typing into.
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .testTag("inventory:add:sheet"),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item("title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.inventory_add_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { custom = !custom },
                        modifier = Modifier.testTag("inventory:add:mode"),
                    ) {
                        Text(
                            stringResource(
                                if (custom) {
                                    R.string.inventory_add_from_list
                                } else {
                                    R.string.inventory_add_custom
                                },
                            ),
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.inventory_add_not_undoable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            if (custom) {
                item("custom") {
                    CustomItemForm(
                        form = form,
                        onChange = { form = it },
                        onSave = {
                            val spec = form.toSpec()
                            if (spec == null) {
                                // First save attempt with a bad field: turn the messages on
                                // and stay put. Same posture as the local character form.
                                form = form.copy(showErrors = true)
                            } else {
                                onAdd(spec)
                                onDismiss()
                            }
                        },
                    )
                }
                return@LazyColumn
            }

            item("search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.inventory_add_search)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .testTag("inventory:add:search"),
                )
            }

            val matches = catalogMatches(query)
            if (matches.isEmpty()) {
                item("none") {
                    Text(
                        text = stringResource(R.string.inventory_add_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .testTag("inventory:add:none"),
                    )
                }
            } else {
                items(matches, key = { it.id }) { entry ->
                    CatalogRow(
                        entry = entry,
                        onPick = {
                            onAdd(NewItemSpec.of(entry))
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One catalog entry: name, its one-line description, and what it costs to carry.
 *
 * The whole row is the target — 04's "large touch targets" applied to a list whose rows are
 * the only control on them — and the default quantity is printed rather than hidden, because
 * "Arrows (20)" adding twenty arrows is a surprise exactly once and a convenience thereafter
 * only if the player was told.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CatalogRow(entry: CatalogItem, onPick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onPick)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("inventory:add:catalog:${entry.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                if (entry.defaultQuantity != 1) {
                    Text(
                        text = stringResource(R.string.inventory_quantity, entry.defaultQuantity),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = stringResource(R.string.inventory_weight, formatAmount(entry.weightLb)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.inventory_value, formatAmount(entry.valueGp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}

/**
 * The custom path (decision 6): name required, quantity defaulted to 1, the rest optional.
 *
 * Optional means **blank is allowed and blank is not zero** — a field left empty is omitted
 * from the insert body entirely, so an item created here arrives on the sheet the way a
 * hand-made one does. See [AddItemFormState].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CustomItemForm(
    form: AddItemFormState,
    onChange: (AddItemFormState) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormField(
            value = form.name,
            onValueChange = { onChange(form.copy(name = it)) },
            label = stringResource(R.string.inventory_custom_name),
            errorRes = form.nameError,
            testTag = "inventory:add:custom:name",
        )
        FormField(
            value = form.quantity,
            // Digits only, so the sentinel path in the state is reached by a blank box and
            // nothing else — the same input filtering the local character form uses.
            onValueChange = { text -> onChange(form.copy(quantity = text.filter(Char::isDigit))) },
            label = stringResource(R.string.inventory_custom_quantity),
            errorRes = form.quantityError,
            errorArgs = listOf(
                AddItemFormState.QUANTITY_RANGE.first,
                AddItemFormState.QUANTITY_RANGE.last,
            ),
            keyboardType = KeyboardType.Number,
            testTag = "inventory:add:custom:quantity",
        )
        FormField(
            value = form.weight,
            onValueChange = { onChange(form.copy(weight = it.filterDecimal())) },
            label = stringResource(R.string.inventory_custom_weight),
            errorRes = form.weightError,
            errorArgs = listOf(formatAmount(AddItemFormState.MAX_WEIGHT_LB)),
            keyboardType = KeyboardType.Decimal,
            testTag = "inventory:add:custom:weight",
        )
        FormField(
            value = form.value,
            onValueChange = { onChange(form.copy(value = it.filterDecimal())) },
            label = stringResource(R.string.inventory_custom_value),
            errorRes = form.valueError,
            errorArgs = listOf(formatAmount(AddItemFormState.MAX_VALUE_GP)),
            keyboardType = KeyboardType.Decimal,
            testTag = "inventory:add:custom:value",
        )
        FormField(
            value = form.description,
            onValueChange = { onChange(form.copy(description = it)) },
            label = stringResource(R.string.inventory_custom_description),
            errorRes = null,
            singleLine = false,
            testTag = "inventory:add:custom:description",
        )
        // FR-10b (13 decision 9). Last, under the optional text fields, because it is the one
        // control here that is never *wrong*: it always has an answer, so it never holds the
        // save up, and putting it above the required name field would make the form open on a
        // question the player has not got to yet.
        //
        // Local characters only — the state decides, not this composable. See the sheet's
        // "Why the category chooser is on one kind of character only".
        if (form.offersCategoryChooser) {
            CategoryChooser(
                category = form.category,
                onCategory = { onChange(form.copy(category = it)) },
                testTagPrefix = "inventory:add:custom:category",
            )
        }

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inventory:add:custom:save"),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    errorRes: Int?,
    testTag: String,
    modifier: Modifier = Modifier,
    errorArgs: List<Any> = emptyList(),
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = errorRes != null,
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = errorRes?.let {
            { Text(stringResource(it, *errorArgs.toTypedArray())) }
        },
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

/**
 * Keeps digits and at most one decimal point.
 *
 * Filtering at the field rather than validating after the fact is what stops the player from
 * ever seeing "1.2.3 is not a number" — the keystroke that would have produced it simply does
 * not land. The state still validates, because a filter is a convenience and validation is
 * the rule (see [AddItemFormState]).
 */
private fun String.filterDecimal(): String {
    var seenDot = false
    return buildString {
        this@filterDecimal.forEach { char ->
            when {
                char.isDigit() -> append(char)
                (char == '.') && !seenDot -> {
                    seenDot = true
                    append(char)
                }
            }
        }
    }
}
