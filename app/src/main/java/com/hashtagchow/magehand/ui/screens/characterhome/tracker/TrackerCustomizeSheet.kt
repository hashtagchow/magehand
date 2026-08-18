package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.ui.theme.AccentPalette

/**
 * The tracker customize sheet (docs/design/04-screens-ux.md §5) plus §6's per-character
 * accent picker.
 *
 * **Functional in WP6**, unlike everything on the tracker itself, because every control
 * here writes a local Room row (`tracker_prefs` / `theme_prefs`) and none of them reaches
 * the server. That is the same line 03 §6 draws: the override layer is applied last, on
 * top of discovery output, and never mutates server data.
 *
 * Reordering is ▲/▼ buttons rather than long-press drag. Two reasons, and the second is
 * the real one: drag-to-reorder inside a `ModalBottomSheet` fights the sheet's own drag
 * gesture, and 04's UX principle is "everything reachable one-handed" — a 48 dp arrow is
 * reachable with a thumb at a table, a long-press-then-drag is not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerCustomizeSheet(
    state: TrackerCustomizeState,
    onDismiss: () -> Unit,
    onMove: (CustomizeSection, String, Int) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
    onSetPinned: (String, Boolean) -> Unit,
    onAccentChosen: (String?) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var itemQuery by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
    ) {
        LazyColumn(
            // The probe caught this: without `imePadding`, typing in the item search
            // pushes nothing, so the keyboard covers the results the search just found.
            modifier = Modifier
                .fillMaxWidth()
                .imePadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item("title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.customize_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.customize_reset))
                    }
                }
                Text(
                    text = stringResource(R.string.customize_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            state.sections.forEach { section ->
                item("header-${section.section}") {
                    SheetHeader(stringResource(section.section.titleResId))
                }
                items(section.rows, key = { "row-${it.propertyId}" }) { row ->
                    val index = section.rows.indexOf(row)
                    CustomizeRowItem(
                        row = row,
                        canMoveUp = index > 0,
                        canMoveDown = index < section.rows.lastIndex,
                        onMoveUp = { onMove(section.section, row.propertyId, -1) },
                        onMoveDown = { onMove(section.section, row.propertyId, 1) },
                        onHide = { onSetHidden(row.propertyId, true) },
                        // Conditions only — see CustomizeRowItem's `onSetPinned` KDoc for
                        // why the other three sections do not get this control.
                        onSetPinned = if (section.section == CustomizeSection.CONDITIONS) {
                            { pinned -> onSetPinned(row.propertyId, pinned) }
                        } else {
                            null
                        },
                    )
                }
            }

            if (state.hasHiddenRows) {
                item("hidden-header") { SheetHeader(stringResource(R.string.customize_hidden)) }
                items(state.hidden, key = { "hidden-${it.propertyId}" }) { row ->
                    HiddenRowItem(row = row, onShow = { onSetHidden(row.propertyId, false) })
                }
            }

            item("items-header") { SheetHeader(stringResource(R.string.customize_items)) }
            item("items-search") {
                OutlinedTextField(
                    value = itemQuery,
                    onValueChange = { itemQuery = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.customize_items_search)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            val matches = state.items.filter { it.name.contains(itemQuery, ignoreCase = true) }
            if (matches.isEmpty()) {
                item("items-empty") {
                    Text(
                        text = stringResource(R.string.customize_items_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(matches.take(MAX_PICKER_ROWS), key = { "pick-${it.propertyId}" }) { item ->
                    ItemPickerRow(
                        item = item,
                        onTogglePin = { onSetPinned(item.propertyId, !item.pinned) },
                    )
                }
                if (matches.size > MAX_PICKER_ROWS) {
                    item("items-more") {
                        Text(
                            text = stringResource(
                                R.string.customize_items_more,
                                matches.size - MAX_PICKER_ROWS,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            item("accent-header") { SheetHeader(stringResource(R.string.customize_accent)) }
            item("accent") {
                AccentPicker(selected = state.accentColor, onChosen = onAccentChosen)
            }
        }
    }
}

@Composable
private fun SheetHeader(text: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

/**
 * One row of a customize section: name, detail, and the controls that apply to it.
 *
 * @param onSetPinned the pin override, or `null` for a section where pinning means
 *   nothing. Only the **conditions** section passes one, and that asymmetry is the point
 *   rather than an omission: for slots, resources and consumables a pin is what put the
 *   row in the section in the first place (an unpinned item is not a tracker row at all,
 *   it is a picker entry), so a pin toggle there would be a checkbox that deletes its own
 *   row. For a condition, `pinned` means something no other control expresses —
 *   `ConditionToggle.shownByDefault` is `enabled || pinned`, so a pin is the user saying
 *   "keep this one out of the *N inactive* expander even while it is off".
 *
 *   That override has been readable and persistable since WP6 and had **no UI**: the
 *   engine stamped it, three KDocs described it, and the only control that could set one
 *   was the item picker, which never offers a condition. This is the missing half.
 */
@Composable
private fun CustomizeRowItem(
    row: CustomizeRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    onSetPinned: ((Boolean) -> Unit)? = null,
) {
    // A bare checkbox would read to TalkBack as "checkbox, not checked" with no subject,
    // in a list where every row has one. It names its row and says what checking it does.
    val pinDescription = stringResource(R.string.customize_pin_condition, row.name)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        onSetPinned?.let { setPinned ->
            // A checkbox, not a pin glyph: `material-icons-extended` (the only artifact
            // that carries `PushPin`) is ~50 MB and is deliberately not a dependency of
            // this app — see gradle/libs.versions.toml. The item picker already renders
            // exactly this override with exactly this control, so a checkbox is also the
            // app's *existing* iconography for "pinned", which is worth more than a
            // prettier glyph that means the same thing somewhere else.
            Checkbox(
                checked = row.pinned,
                onCheckedChange = setPinned,
                modifier = Modifier.semantics {
                    contentDescription = pinDescription
                },
            )
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.customize_move_up, row.name),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.customize_move_down, row.name),
            )
        }
        IconButton(onClick = onHide, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Clear,
                contentDescription = stringResource(R.string.customize_hide, row.name),
            )
        }
    }
}

@Composable
private fun HiddenRowItem(row: CustomizeRow, onShow: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 24.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onShow) { Text(stringResource(R.string.customize_show)) }
    }
}

@Composable
private fun ItemPickerRow(
    item: ItemPickRow,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onTogglePin)
            .padding(start = 24.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.customize_item_quantity, item.quantity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = item.pinned, onCheckedChange = { onTogglePin() })
    }
}

/**
 * 04 §6's per-character accent picker.
 *
 * The swatches are 48 dp targets in a wrapped grid rather than a colour wheel: a wheel is
 * a fine desktop control and a terrible one-handed one, and eight named presets cover
 * "make my character's tab look like my character" without asking anyone to hunt a hex
 * value at a table. The first swatch clears the override back to the app palette.
 */
@Composable
private fun AccentPicker(
    selected: String?,
    onChosen: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedNormalized = AccentPalette.parse(selected)?.let { AccentPalette.toHex(it) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        (listOf(null) + AccentPalette.PRESETS).chunked(SWATCHES_PER_ROW).forEach { rowPresets ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowPresets.forEach { preset ->
                    val hex = preset?.hex
                    val isSelected = hex?.let {
                        AccentPalette.parse(it)?.let(AccentPalette::toHex)
                    } == selectedNormalized
                    Swatch(
                        color = hex?.let { Color(AccentPalette.parse(it)!!) }
                            ?: MaterialTheme.colorScheme.surfaceVariant,
                        label = preset?.name ?: stringResource(R.string.customize_accent_default),
                        selected = isSelected,
                        onClick = { onChosen(hex) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Swatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                // The tick has to stay legible on a swatch the user chose, including the
                // pale ones — so its colour is derived from the swatch, not fixed.
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color(AccentPalette.onColorFor(color.toArgb())),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CustomizeSection.titleResId: Int
    get() = when (this) {
        CustomizeSection.SPELL_SLOTS -> R.string.tracker_section_slots
        CustomizeSection.RESOURCES -> R.string.tracker_section_resources
        CustomizeSection.CONSUMABLES -> R.string.tracker_section_consumables
        CustomizeSection.CONDITIONS -> R.string.tracker_section_conditions
    }

/** A real sheet carries ~90 items; a picker that renders all of them is a scroll, not a picker. */
private const val MAX_PICKER_ROWS = 40
private const val SWATCHES_PER_ROW = 5
