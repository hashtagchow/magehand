package com.hashtagchow.magehand.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.CoinKind

/**
 * FR-22's direct number entry (docs/design/15-polish-batch.md decisions 5–8).
 *
 * ### Why a text field here and a number pad on HP
 *
 * `HpNumberPadDialog` argues its keys at length: damage and heal are taken *mid-combat*, one
 * handed, at arm's length across a table, and a 56 dp key beats a cursor. Direct entry is the
 * opposite gesture. It is what a player reaches for **between** turns, when the sheet and the
 * table have drifted and they want to type "12" — usually with both hands and usually while
 * looking at the number they are correcting. A field with the current value selected turns that
 * into one keystroke and a tap, where the pad would be a clear-then-two-keys.
 *
 * So this does not replace the pad and does not compete with it: decision 5's gesture is a
 * **long press on the value**, and every stepper on every surface is untouched (*"Steppers
 * unchanged; this is additive"*).
 *
 * ### The clamp shows itself (decision 7)
 *
 * *"Out-of-range input clamps with the field showing the clamped value before send (no silent
 * adjustment after)."* [onChange] therefore rewrites the field as the player types rather than
 * letting them commit a number and discover a different one on the row afterwards — typing `99`
 * into a 20-point HP row leaves `20` on screen before Set is ever pressed. Silent post-hoc
 * clamping is what makes a control feel like it ignored you.
 *
 * An **empty** field is allowed while editing (it is the state after clearing) and Set is
 * disabled there; without that, backspacing the last digit would snap the field to `0` and the
 * next keystroke would append to it.
 *
 * ### Why the dialog is a plain `AlertDialog`
 *
 * Decision 8: *"the dialog is a standard Compose dialog (inherits FR-4 inset handling)"*. It
 * declares [testTagsAsResourceId] itself for `HpNumberPadDialog`'s stated reason — a dialog is
 * its own window, so the tab's opt-in does not reach it and the emulator probe would otherwise
 * have to tap blind coordinates.
 *
 * @param label the row's own name, as the player reads it. Used in the title and nowhere else;
 *   the value is the field's.
 * @param current what the row reads now. Prefilled **and selected**, so the first keystroke
 *   replaces it (decision 5).
 * @param max the ceiling, or `null` where the row has none. Decision 7: slots, resources and HP
 *   have one; quantities and coins do not. The floor is always zero and is not a parameter,
 *   because there is no row in this app that may go negative.
 * @param onSet the clamped value. Never called with the field empty.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DirectEntryDialog(
    label: String,
    current: Int,
    max: Int?,
    onSet: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // `rememberSaveable` for the same reason every other in-progress gesture in this app uses it:
    // a rotation mid-type must not throw the number away. `TextFieldValue` carries the selection,
    // which is the half that makes "prefilled and selected" survive the rebuild too.
    var entry by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(
            TextFieldValue(
                text = current.toString(),
                selection = TextRange(0, current.toString().length),
            ),
        )
    }
    val focusRequester = remember { FocusRequester() }
    // The IME has to be up when the dialog appears or the gesture is two taps, not one — and the
    // selection above is only useful while the field has focus.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val parsed = entry.text.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.direct_entry_title, label)) },
        text = {
            OutlinedTextField(
                value = entry,
                onValueChange = { next -> entry = clampEntry(next, max) },
                singleLine = true,
                label = { Text(stringResource(R.string.direct_entry_label)) },
                supportingText = max?.let { ceiling ->
                    { Text(stringResource(R.string.direct_entry_range, ceiling)) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .semantics { testTagsAsResourceId = true }
                    .testTag("entry:field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onSet); onDismiss() },
                enabled = parsed != null,
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("entry:set"),
            ) { Text(stringResource(R.string.tracker_pad_set)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * One keystroke's worth of decision 7, as a pure function so `DirectEntryStateTest` can pin it
 * without a Compose runtime.
 *
 * Three rules, in order:
 *
 * 1. **Non-digits are dropped, not rejected.** A numeric IME still offers `-`, `.` and `,` on
 *    most keyboards, and a field that refuses the whole edit because one character was a comma
 *    reads as broken. Dropping is also what makes a paste of `"12 gp"` land as `12`.
 * 2. **Empty stays empty.** See the dialog KDoc: it is the state after clearing, and snapping it
 *    to `0` would make the next digit append rather than replace.
 * 3. **The clamp is applied to the text.** Over the ceiling rewrites the field *now*, so the
 *    player sees what will be sent before they send it. There is no floor rule because rule 1
 *    has already made a negative number unrepresentable.
 *
 * A number too long to be an `Int` clamps to [max] where there is one and is otherwise truncated
 * to [MAX_DIGITS] — an unbounded field overflows the display long before it overflows `Int`, and
 * `HpNumberPadDialog` caps its own entry at the same figure for the same reason.
 */
internal fun clampEntry(next: TextFieldValue, max: Int?): TextFieldValue {
    val digits = next.text.filter(Char::isDigit).take(MAX_DIGITS)
    if (digits.isEmpty()) return next.copy(text = "", selection = TextRange(0))
    val parsed = digits.toIntOrNull() ?: max ?: Int.MAX_VALUE
    val clamped = if (max != null) parsed.coerceAtMost(max) else parsed
    val text = clamped.toString()
    // Keep the caret at the end whenever the text was rewritten; otherwise honour the edit's own
    // selection, so mid-string edits behave like an ordinary field.
    return if (text == next.text) next else next.copy(text = text, selection = TextRange(text.length))
}

/**
 * Decision 5's gesture and decision 8's affordance, in one modifier.
 *
 * ### Why long-press *and* tap, and why HP is the exception
 *
 * Decision 5 names the gesture — *"long-press the VALUE"* — and decision 8 requires the value to
 * become *"a real button"* whose spoken sentence carries the offer. Those pull in slightly
 * different directions: a long press is not a thing TalkBack performs by default, so a value
 * that *only* long-pressed would be a button a screen-reader user could hear and not operate.
 * Wiring both to the same dialog resolves it — sighted users get the gesture the design names,
 * everyone else gets the ordinary click that the announcement promises.
 *
 * The HP block is the one surface that keeps a *different* tap ([onClick] supplied), because its
 * number already opens `HpNumberPadDialog` and has since WP7. Taking that away to standardise
 * would be removing a shipped control to satisfy a symmetry nobody asked for; there, the long
 * press is the whole of the new gesture.
 *
 * ### Why `clearAndSetSemantics` is not used
 *
 * The node keeps its own text and gains a description. [spoken] is the full house sentence
 * (`direct_entry_spoken*`), which is what a merged clickable node has to carry — see
 * `WalletUiState.spokenLabel` for the argument in its general form.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.directEntry(
    enabled: Boolean,
    spoken: String,
    onOpen: () -> Unit,
    onClick: (() -> Unit)? = null,
): Modifier = this
    .combinedClickable(
        enabled = enabled,
        role = Role.Button,
        onClick = onClick ?: onOpen,
        onLongClick = onOpen,
    )
    .semantics { contentDescription = spoken }

/** `HpNumberPadDialog`'s cap, shared so the two entry surfaces agree on what is too long. */
private const val MAX_DIGITS = 4
/**
 * Which intent an FR-22 direct entry resolves to — decision 5's four surfaces, minus the two
 * that share one.
 *
 * [ITEM] covers the tracker's consumable rows, the inventory list rows and the detail sheet,
 * because all three edit the same thing (a `creatureProperties` quantity) through the same
 * intent. What differs between them is only which tab hosts the dialog, and that is the
 * composable's problem rather than this enum's.
 */
enum class DirectEntryKind { HIT_POINTS, RESOURCE, ITEM, COIN }

/**
 * One resolved direct-entry gesture: what is being edited, what it reads now, and how far it may
 * go.
 *
 * @param label the row's own name, blank for HP — whose name is a fixed string
 *   (`tracker_hit_points`) rather than something off the sheet, so the composable supplies it.
 * @param max `null` where the row has no ceiling (decision 7).
 */
data class DirectEntryTarget(
    val kind: DirectEntryKind,
    val propertyId: String,
    val label: String,
    val current: Int,
    val max: Int?,
)

/**
 * The key space `TrackerUiState.directEntryTarget` and `InventoryUiState.directEntryTarget` read.
 *
 * Prefixed rather than bare ids for `InventorySectionState.key`'s reason: a slot's `_id` and a
 * consumable's live in one namespace and the *kind* decides which intent the Set button reaches,
 * so the kind has to survive the trip through `rememberSaveable`.
 */
object DirectEntryKeys {
    const val HIT_POINTS: String = "hp"
    const val RESOURCE_PREFIX: String = "row:"
    const val ITEM_PREFIX: String = "item:"

    /**
     * A denomination rather than a property id, matching `InventoryActions.onCoinDelta`: a
     * wallet row may have **no** backing property yet (`WalletRow.isAbsent`), so the coin is the
     * only stable name it has.
     */
    const val COIN_PREFIX: String = "coin:"

    fun resource(propertyId: String): String = "$RESOURCE_PREFIX$propertyId"

    fun item(propertyId: String): String = "$ITEM_PREFIX$propertyId"

    fun coin(coin: CoinKind): String = "$COIN_PREFIX${coin.name}"
}
