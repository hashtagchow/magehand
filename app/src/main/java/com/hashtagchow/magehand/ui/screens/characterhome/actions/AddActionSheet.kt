package com.hashtagchow.magehand.ui.screens.characterhome.actions

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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
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
import com.hashtagchow.magehand.core.data.catalog.SpellCatalog
import com.hashtagchow.magehand.core.data.catalog.WeaponCatalog
import com.hashtagchow.magehand.core.model.CatalogSpell
import com.hashtagchow.magehand.core.model.CatalogWeapon
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.NewLocalRowSpec
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The local Actions tab's **Add** (FR-49, docs/design/20-local-spells-and-attacks.md decision 6),
 * built in `AddItemSheet`'s shape and departing from it in exactly two places, both argued below.
 *
 * ### The catalog is a template, not the truth
 *
 * This is decision 6's sharpest line and the one thing about this sheet worth reading twice. A
 * catalog tap does **not** add a row. It pre-fills the form — every field, editable — and the
 * player saves whatever they have then got. So there is **one form, one validation and one save**,
 * and the two halves of the sheet are a search box and a form the search box can fill in, rather
 * than two paths that have to be kept behaving identically.
 *
 * The saved row *copies* the values and keeps [NewLocalRowSpec.catalogId] as provenance. Nothing
 * re-reads the catalog to render, which is what makes the guarantee concrete: a catalog entry that
 * changes in a later release cannot reach a row a player already has, and a spell the player
 * rewrote stays as they wrote it. A future *"update from catalog"* can diff on the id; explicitly
 * not this wave.
 *
 * ### Two departures from `AddItemSheet`
 *
 * **A pick opens the form** rather than adding immediately. That sheet's argument for adding on
 * tap is that the catalog exists to be *faster than typing* and a confirm step gives that back —
 * true of a rope, and not of a spell: a spell is a longer decision, its entry carries a paragraph
 * of rules text the player may want to trim, and the level a slot cast matches on is a number they
 * may have a subclass reason to change.
 *
 * **The sheet closes on save**, for the same reason and decision 6's own words. `AddItemSheet`
 * stays open because a player adding rope is usually adding four other things too; a player adding
 * Fireball has added Fireball.
 *
 * ### What is deliberately not here
 *
 * The **cost** picker (FR-29's `costRowId`). It names another row of this character, so choosing
 * one needs the list of rows the editor already draws — and a second copy of that picker on a
 * sheet whose job is "add the spell" would be two places to fix one rule. A row added here starts
 * free and gains a cost in the editor, which is where the player is already looking at the
 * resource it would spend.
 *
 * @param onAdd handed a validated [NewLocalRowSpec]; `LocalOpenCharacter.addActionRow` neither
 *   knows nor cares which half of the sheet it came from.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AddActionSheet(
    onAdd: (NewLocalRowSpec) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    // Which kind the catalog is listing. Spell by default — decision 6 — because a player opening
    // this sheet on a fresh character is far more often adding their first spell than their first
    // weapon, and the weapon list is 38 entries they can reach in one tap.
    var kind by rememberSaveable { mutableStateOf(LocalRowKind.SPELL) }
    var query by rememberSaveable { mutableStateOf("") }

    // `null` while the catalog is showing; a form once the player has picked an entry or chosen to
    // write their own. **One** piece of state for both, which is what "one form" means in code:
    // there is no separate "custom mode" flag that could disagree with it.
    //
    // Plain `remember`, matching `AddItemSheet`'s form and for its stated reason: a half-typed
    // spell is not a preference, and the sheet is dismissed on save. The *catalog* half above is
    // `rememberSaveable`, because which list you were scrolling is worth surviving a rotation.
    var form by remember { mutableStateOf<AddActionFormState?>(null) }

    // L4 [review, 2026-09-12]. `search` is a linear, case-insensitive `contains` over 319 entries,
    // and this used to be called from inside the `LazyColumn`'s content lambda — which is not a
    // composable scope, so it could not be remembered there and was re-run on every frame that
    // recomposed the sheet, including every keystroke in the *form* half where the list is not
    // even drawn. Keyed on the only two inputs it has, so it runs when the player types in the
    // search box and at no other time.
    //
    // Two `remember`s and not one, because the two halves return different element types and a
    // `List<Any>` at the call site would have to be cast back down inside `items`. The unused
    // half is `emptyList()` rather than a computed list nobody reads.
    val spellMatches = remember(kind, query) {
        if (kind == LocalRowKind.SPELL) SpellCatalog.search(query) else emptyList()
    }
    val weaponMatches = remember(kind, query) {
        if (kind == LocalRowKind.SPELL) emptyList() else WeaponCatalog.search(query)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        LazyColumn(
            // `AddItemSheet`'s note, unchanged: without `imePadding` the keyboard covers the very
            // fields the player is typing into — the defect the FR-8 probe caught on the customize
            // sheet, and this form has nine text fields.
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .testTag("actions:add:sheet"),
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
                        text = stringResource(R.string.actions_add_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            // Back to the list from the form, or an empty form from the list.
                            // One control, because the two are one state — see `form`.
                            form = if (form == null) AddActionFormState(kind = kind) else null
                        },
                        modifier = Modifier.testTag("actions:add:mode"),
                    ) {
                        Text(
                            stringResource(
                                if (form == null) {
                                    R.string.actions_add_custom
                                } else {
                                    R.string.actions_add_from_list
                                },
                            ),
                        )
                    }
                }
            }

            val currentForm = form
            if (currentForm != null) {
                item("form") {
                    AddActionForm(
                        form = currentForm,
                        onChange = { form = it },
                        onSave = {
                            val spec = currentForm.toSpec()
                            if (spec == null) {
                                // First save attempt with a bad field: turn the messages on and
                                // stay put. The local character form's posture, and the add-item
                                // sheet's.
                                form = currentForm.copy(showErrors = true)
                            } else {
                                onAdd(spec)
                                onDismiss()
                            }
                        },
                    )
                }
                return@LazyColumn
            }

            item("filter") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Decision 6's Spell / Attack filter. `FilterChip`s rather than a segmented
                    // button, matching every other two-way choice in this app (the reset rules,
                    // the category chooser) — one visual vocabulary for "pick one of these".
                    listOf(LocalRowKind.SPELL, LocalRowKind.ATTACK).forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = { kind = option },
                            label = { Text(stringResource(option.filterLabelRes)) },
                            modifier = Modifier.testTag("actions:add:filter:${option.storedValue}"),
                        )
                    }
                }
            }

            item("search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.actions_add_search)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .testTag("actions:add:search"),
                )
            }

            if (kind == LocalRowKind.SPELL) {
                val matches = spellMatches
                if (matches.isEmpty()) {
                    item("none") { NoCatalogMatches() }
                } else {
                    items(matches, key = { it.id }) { entry ->
                        CatalogRow(
                            id = entry.id,
                            name = entry.name,
                            summary = entry.summaryLine(),
                            // The pick pre-fills the form; it does not save. See the class KDoc.
                            onPick = { form = AddActionFormState.of(entry) },
                        )
                    }
                }
            } else {
                val matches = weaponMatches
                if (matches.isEmpty()) {
                    item("none") { NoCatalogMatches() }
                } else {
                    items(matches, key = { it.id }) { entry ->
                        CatalogRow(
                            id = entry.id,
                            name = entry.name,
                            summary = entry.summaryLine(),
                            onPick = { form = AddActionFormState.of(entry) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One catalog entry: its name and a one-line summary of what it is.
 *
 * The whole row is the target — 04's *"large touch targets"* applied to a list whose rows are the
 * only control on them — and it is the `AddItemSheet` catalog row's shape with the price and
 * weight columns removed, because a spell has neither and a weapon's are not what the player is
 * choosing between here (20 decision 6: an ATTACK row is the attack, not the object).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CatalogRow(
    id: String,
    name: String,
    summary: String,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(onClick = onPick)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("actions:add:catalog:$id"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider()
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoCatalogMatches(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.actions_add_none),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .padding(horizontal = 24.dp, vertical = 12.dp)
            .testTag("actions:add:none"),
    )
}

/**
 * The one form (decision 6), reached either by picking a catalog entry or by choosing *Custom*.
 *
 * Which fields are drawn follows the kind, exactly as the editor's row form does and for the same
 * reason: rendering a casting time on an attack would be offering a control whose value the save
 * discards, and a control that lies about what it does is worse than a missing one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AddActionForm(
    form: AddActionFormState,
    onChange: (AddActionFormState) -> Unit,
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
            label = stringResource(R.string.actions_add_name),
            errorRes = form.nameError,
            testTag = "actions:add:custom:name",
        )

        if (form.kind == LocalRowKind.SPELL) {
            SpellLevelChips(
                level = form.spellLevel,
                onLevel = { onChange(form.copy(spellLevel = it)) },
                testTagPrefix = "actions:add:custom:level",
            )
            form.spellLevelError?.let {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            FormField(
                value = form.castingTime,
                onValueChange = { onChange(form.copy(castingTime = it)) },
                label = stringResource(R.string.local_field_casting_time),
                errorRes = null,
                testTag = "actions:add:custom:castingTime",
            )
            FormField(
                value = form.range,
                onValueChange = { onChange(form.copy(range = it)) },
                label = stringResource(R.string.local_field_range),
                errorRes = null,
                testTag = "actions:add:custom:range",
            )
            FormField(
                value = form.components,
                onValueChange = { onChange(form.copy(components = it)) },
                label = stringResource(R.string.local_field_components),
                errorRes = null,
                testTag = "actions:add:custom:components",
            )
            FormField(
                value = form.duration,
                onValueChange = { onChange(form.copy(duration = it)) },
                label = stringResource(R.string.local_field_duration),
                errorRes = null,
                testTag = "actions:add:custom:duration",
            )
            SwitchRow(
                label = stringResource(R.string.local_field_concentration),
                checked = form.concentration,
                onChange = { onChange(form.copy(concentration = it)) },
                testTag = "actions:add:custom:concentration",
            )
            SwitchRow(
                label = stringResource(R.string.local_field_ritual),
                checked = form.ritual,
                onChange = { onChange(form.copy(ritual = it)) },
                testTag = "actions:add:custom:ritual",
            )
        } else {
            FormField(
                value = form.damage,
                onValueChange = { onChange(form.copy(damage = it)) },
                label = stringResource(R.string.local_field_damage),
                errorRes = null,
                testTag = "actions:add:custom:damage",
            )
            FormField(
                value = form.properties,
                onValueChange = { onChange(form.copy(properties = it)) },
                label = stringResource(R.string.local_field_properties),
                errorRes = null,
                testTag = "actions:add:custom:properties",
            )
        }

        FormField(
            value = form.description,
            onValueChange = { onChange(form.copy(description = it)) },
            label = stringResource(R.string.local_field_description),
            errorRes = null,
            singleLine = false,
            testTag = "actions:add:custom:description",
        )

        if (form.kind == LocalRowKind.SPELL) {
            // Last of the spell fields, and deliberately below the description: it is a *rider* on
            // the rules text above it, and the detail sheet draws it in that order too.
            FormField(
                value = form.higherLevels,
                onValueChange = { onChange(form.copy(higherLevels = it)) },
                label = stringResource(R.string.local_field_higher_levels),
                errorRes = null,
                singleLine = false,
                testTag = "actions:add:custom:higherLevels",
            )
        }

        FormField(
            value = form.uses,
            // Digits only **and length-capped**, the local character editor's `NumberField` rule
            // (NIT 6 [review, 2026-09-12]). The filter alone left a third state: a digit string too
            // long for an `Int` passed it, `toIntOrNull` returned null, and the state read that as
            // an empty box — so a hundred-billion-use row saved as *unlimited* with no error shown.
            // The cap is what stops a player typing their way there; `AddActionFormState`'s
            // `INVALID_USES` is what catches the paste that gets there anyway.
            onValueChange = { typed ->
                val digits = typed.filter(Char::isDigit)
                if (digits.length <= AddActionFormState.USES_MAX_DIGITS) {
                    onChange(form.copy(uses = digits))
                }
            },
            label = stringResource(R.string.local_field_uses),
            errorRes = form.usesError,
            keyboardType = KeyboardType.Number,
            testTag = "actions:add:custom:uses",
        )

        // The reset rule only means something once there is something to reset. Absent rather than
        // disabled, matching the editor's cost amount: a control for a limit that does not exist is
        // a control with no subject.
        if (form.offersReset) {
            Text(
                text = stringResource(R.string.local_field_reset),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf(null) + ResetRule.entries).forEach { rule ->
                    FilterChip(
                        selected = form.reset == rule,
                        onClick = { onChange(form.copy(reset = rule)) },
                        label = { Text(stringResource(rule.resetLabelRes)) },
                        // NIT 8 [review, 2026-09-12]: every other control on this sheet is
                        // reachable by tag and these three were not, which makes them the one
                        // part of the form a render test or a sweep flow has to find by its
                        // words. `none` for the null rule, matching `resetLabelRes` naming it
                        // rather than implying it.
                        modifier = Modifier
                            .testTag("actions:add:custom:reset:${rule?.wireValue ?: "none"}"),
                    )
                }
            }
        }

        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("actions:add:custom:save"),
        ) {
            Text(stringResource(R.string.action_save))
        }
    }
}

/**
 * Decision 6's level chips, 0–9, with **Cantrip** where 0 would be.
 *
 * A chip row and not a number field, for the reason the reset rules are chips: there are exactly
 * ten answers, they are all on screen, and the one a player wants is one tap away. "Cantrip"
 * rather than "0" is the same special case `actions_spell_cantrips` already makes on the section
 * header — nobody at a table says "a level zero spell".
 *
 * `null` is reachable only as the *initial* state of a custom form (the player has not answered
 * yet) and is what `spellLevelError` names; there is no "None" chip, because a spell without a
 * level is not a state the player should be able to choose.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SpellLevelChips(
    level: Int?,
    onLevel: (Int) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    levels: IntRange = NewLocalRowSpec.SPELL_LEVELS,
    labelRes: Int = R.string.local_field_spell_level,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // `FlowRow` and not a horizontal scroll: ten chips do not fit a 360 dp phone, and a row
        // that scrolls sideways hides the chips a player is looking for behind a gesture they have
        // no reason to try. The editor's own kind chooser made the same call — see
        // `LocalCharacterEditorScreen`'s FlowRow note.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            levels.forEach { value ->
                FilterChip(
                    selected = level == value,
                    onClick = { onLevel(value) },
                    label = {
                        Text(
                            if (value == 0) {
                                stringResource(R.string.local_spell_level_cantrip)
                            } else {
                                stringResource(R.string.local_spell_level, value)
                            },
                        )
                    },
                    modifier = Modifier.testTag("$testTagPrefix:$value"),
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = errorRes != null,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 6,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = errorRes?.let { { Text(stringResource(it)) } },
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag),
    )
}

/** The filter chips' words. The editor's kind chips, reused — one vocabulary for one idea. */
private val LocalRowKind.filterLabelRes: Int
    get() = when (this) {
        LocalRowKind.SPELL -> R.string.local_kind_spell
        LocalRowKind.ATTACK -> R.string.local_kind_attack
        // Unreachable: the filter offers two kinds and this sheet adds no others. Named rather
        // than swept into an `else` so that a third addable kind is a compile error here.
        LocalRowKind.SLOT, LocalRowKind.RESOURCE, LocalRowKind.ITEM, LocalRowKind.ACTION ->
            R.string.local_kind_action
    }

/** `null` is "none", named rather than implied — the editor's own chip vocabulary. */
private val ResetRule?.resetLabelRes: Int
    get() = when (this) {
        null -> R.string.local_reset_none
        ResetRule.SHORT_REST -> R.string.local_reset_short
        ResetRule.LONG_REST -> R.string.local_reset_long
    }

/**
 * The picker's one-line summary of a spell — *"Level 3 · Evocation · Concentration"*.
 *
 * Built here rather than on [CatalogSpell] because it is display copy, and the catalog types are
 * data (`ItemCatalog`'s LOW-10 ruling: *UI chrome is copy; catalog entries are data*). The school
 * and the flags appear only here — they are how a player recognises the entry in a list of 319,
 * and neither is carried onto the row (see `NewLocalRowSpec.ofSpell`).
 */
@Composable
private fun CatalogSpell.summaryLine(): String = listOfNotNull(
    if (level == 0) {
        stringResource(R.string.local_spell_level_cantrip)
    } else {
        stringResource(R.string.action_detail_spell_level, level)
    },
    school.takeIf { it.isNotBlank() },
    stringResource(R.string.actions_concentration).takeIf { concentration },
    stringResource(R.string.actions_ritual).takeIf { ritual },
).joinToString(" · ")

/** *"1d8 slashing · Versatile (1d10)"* — the two facts the row will carry, before it carries them. */
@Composable
private fun CatalogWeapon.summaryLine(): String = listOfNotNull(
    damage.takeIf { it.isNotBlank() },
    properties.joinToString(", ").takeIf { it.isNotBlank() },
).joinToString(" · ")
