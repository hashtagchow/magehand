package com.hashtagchow.magehand.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CatalogCategory

/**
 * The Weapon / Armor / Gear chooser
 * (FR-10b, docs/design/13-collapsible-sections-local-gear.md decision 9).
 *
 * ### Why it lives in `ui.components` and not beside either form
 *
 * Decision 9 names **two** capture points that are not the catalog — the custom item form on the
 * inventory tab, and the local editor's row form — and they are in different packages on
 * different screens. A control that decides what an item *is* has to look and read identically
 * in both, because a player who learns it in one place will meet it in the other: the same three
 * words in the same order, the same default, and the same spoken sentence. Two copies would drift
 * on the first edit to either.
 *
 * ### Why segmented buttons and not the `FilterChip` rows elsewhere in the app
 *
 * Decision 9 says segmented, and the reason is that this is the one chooser in the app whose
 * options are **mutually exclusive and exhaustive** — a thing is a weapon, or armor, or gear, and
 * it is always exactly one of them. `FilterChip` is the app's vocabulary for *filters and
 * toggles*, which is what the tracker's kind chips and the reset chips are: sets you add to and
 * take from. A segmented control says "pick one of these three" in its shape, before any label is
 * read, and it cannot render the state where none is selected — which is a state this value does
 * not have.
 *
 * ### Accessibility
 *
 * `SingleChoiceSegmentedButtonRow` announces each segment's selected state on its own. What it
 * cannot say is what the row is *for* — three bare nouns with no question in front of them — so
 * the label above carries that in text and each segment folds it into its own sentence. That is
 * the same reason `InventorySectionState.spokenLabel` folds a title into a control's sentence: a
 * screen reader lands on the control, not on the paragraph above it.
 *
 * The sentence is on the **segments** and not on the row, which is BUG-6 and worth the paragraph
 * because the broken shape looks identical to the working one at a glance. Until the 2026-08-31
 * review this file put one `contentDescription` on the row itself; the row does not merge its
 * descendants and its segments are each focusable, so TalkBack stopped on the children and the
 * row's words were never spoken by anything. A test asserting the description merely *existed*
 * passed throughout — `onNodeWithContentDescription` searches the whole tree without asking
 * whether focus can reach what it finds. `CategoryChooserTest` therefore asserts **reachability**
 * (a merged node carrying a click action) rather than existence, and asserts the row carries no
 * description at all; the identical fix and the identical test shape are on FR-35's sort
 * direction, which is where the class was first caught.
 *
 * @param testTagPrefix the caller's own namespace, e.g. `"inventory:add:custom:category"`. Each
 *   segment tags itself as `"<prefix>:<stored value>"`, so the emulator probe can tap one by name
 *   — Compose test tags are invisible to `adb uiautomator dump` unless a parent opts into
 *   `testTagsAsResourceId`, which both call sites already do.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun CategoryChooser(
    category: CatalogCategory,
    onCategory: (CatalogCategory) -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier,
    @StringRes labelRes: Int = R.string.inventory_category_label,
) {
    val label = stringResource(labelRes)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // No `contentDescription` on the row, for the reason the KDoc's Accessibility section
        // gives at length: it does not merge and its segments are focusable, so nothing written
        // here was ever reached. The sentence lives on each segment, which is where focus lands.
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTagPrefix),
        ) {
            CatalogCategory.entries.forEachIndexed { index, entry ->
                val optionLabel = stringResource(entry.labelRes)
                SegmentedButton(
                    selected = category == entry,
                    onClick = { onCategory(entry) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = CatalogCategory.entries.size,
                    ),
                    label = { Text(optionLabel) },
                    modifier = Modifier
                        // On the button, not on the row: a `SegmentedButton` is a selectable
                        // control that merges its own content, so this is reached by
                        // construction. It names the control ("What is it?"), which the
                        // segment's one noun does not.
                        .semantics {
                            contentDescription = spokenCategoryOptionLabel(label, optionLabel)
                        }
                        .testTag("$testTagPrefix:${entry.storedValue}"),
                )
            }
        }
    }
}

/**
 * What one segment says when a screen reader stops on it: the control's question, then this
 * segment's noun.
 *
 * A function rather than a string template at the call site so the composition rule is stated
 * once and can be asserted directly — the same shape as
 * `InventoryCustomizeState.spokenDirectionOptionLabel`, which is the sentence this one is modelled
 * on. It has no conditional clause because this control has no unavailable state: unlike the sort
 * direction, a category is always choosable.
 */
internal fun spokenCategoryOptionLabel(title: String, optionLabel: String): String =
    title + SPOKEN_SEPARATOR + optionLabel

/**
 * What separates the facts inside this file's spoken sentence.
 *
 * A comma, the same one `InventoryCustomizeState` and `InventoryUiState`'s builders use and for
 * the same reason — these strings are read aloud and a comma is the pause a sentence needs. Both
 * twins are file-private in their own files, so this is a third declaration rather than a shared
 * constant; the alternative is widening a file-private punctuation choice into API for the sake
 * of one character.
 */
private const val SPOKEN_SEPARATOR = ", "

/**
 * The three words, in `strings.xml` because they are copy.
 *
 * Deliberately **not** reusing `inventory_section_weapons` / `inventory_section_armor` /
 * `inventory_section_gear`, even though two of the three read identically today: those are
 * section *headings* naming a list of several things, these are singular choices naming what one
 * item is, and a translator is entitled to render "Weapons" and "Weapon" differently. That is the
 * same split `inventory_chip_equipped` already draws against `inventory_section_equipped`.
 */
private val CatalogCategory.labelRes: Int
    @StringRes get() = when (this) {
        CatalogCategory.WEAPON -> R.string.inventory_category_weapon
        CatalogCategory.ARMOR -> R.string.inventory_category_armor
        CatalogCategory.GEAR -> R.string.inventory_category_gear
    }
