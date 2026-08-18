package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.RestKind

/**
 * 04 §3's "damage/heal number pad on tap".
 *
 * ### Why a pad and not a text field
 *
 * A `TextField` would be fewer lines, but it summons the IME over the bottom half of the
 * screen — the half holding the buttons — and it puts a cursor between the user and a
 * number they already know. This is a table tool: three taps, one big button, done, one
 * hand. The keys are 56 dp because a phone held at arm's length across a table is not a
 * phone held 30 cm from your face.
 *
 * ### Why three actions and not two
 *
 * 04 names damage and heal. `Set` is here because it is the only way to reach an *exact*
 * value when the sheet has drifted (a DM adjusts HP in the PWA, someone forgets to log a
 * potion), and 03 §Write semantics already specifies the call for it — `damage set
 * (total − desired)`. Without it the user's only route to "make it say 14" is arithmetic.
 */
@Composable
fun HpNumberPadDialog(
    current: Int,
    max: Int,
    onDamage: (Int) -> Unit,
    onHeal: (Int) -> Unit,
    onSet: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by rememberSaveable { mutableStateOf("") }
    val amount = entry.toIntOrNull() ?: 0
    val armed = amount > 0 || entry == "0"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracker_hp_pad_title, current, max)) },
        text = {
            // A dialog is its own window, so `TrackerTab`'s opt-in does not reach it and
            // the probe would have to tap blind coordinates. Re-declared here.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.semantics { testTagsAsResourceId = true },
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = entry.ifEmpty { "0" },
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .testTag("tracker:pad:entry"),
                    )
                }

                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    KEYPAD.forEach { rowKeys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowKeys.forEach { key ->
                                PadKey(
                                    label = key,
                                    onClick = {
                                        entry = when (key) {
                                            BACKSPACE -> entry.dropLast(1)
                                            CLEAR -> ""
                                            // A cap, not a validation message: nobody
                                            // takes 10,000 damage, and an unbounded field
                                            // overflows the display before it overflows Int.
                                            else -> (entry + key).take(MAX_DIGITS)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { testTagsAsResourceId = true },
            ) {
                TextButton(
                    onClick = { onSet(amount); onDismiss() },
                    enabled = armed,
                    modifier = Modifier.testTag("tracker:pad:set"),
                ) { Text(stringResource(R.string.tracker_pad_set)) }

                OutlinedButton(
                    onClick = { onHeal(amount); onDismiss() },
                    enabled = amount > 0,
                    modifier = Modifier.testTag("tracker:pad:heal"),
                ) { Text(stringResource(R.string.tracker_heal)) }

                Button(
                    onClick = { onDamage(amount); onDismiss() },
                    enabled = amount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.testTag("tracker:pad:damage"),
                ) { Text(stringResource(R.string.tracker_damage)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PadKey(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        modifier = modifier
            .width(64.dp)
            .height(56.dp)
            .testTag("tracker:pad:key:$label"),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * 04 §3: "Short / Long → confirm dialog listing what will reset → `creature.methods.rest`.
 * Not undoable (say so in the dialog)."
 *
 * The list is computed from the board rather than described in general terms, because
 * "this will reset your resources" is exactly the sentence a player skims. Naming the rows
 * — *Rage 0/2, 1st Level 1/3* — is what makes the confirmation an actual decision.
 *
 * Not-undoable is stated in the dialog and enforced in the type system: `WriteOp.Rest`'s
 * `inverse` is `null`, so a rest physically cannot enter the undo stack
 * (docs/design/03-data-model.md §Write semantics).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun RestConfirmDialog(
    kind: RestKind,
    state: TrackerUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val affected = state.rowsRestoredBy(kind)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (kind) {
                        RestKind.SHORT -> R.string.tracker_short_rest
                        RestKind.LONG -> R.string.tracker_long_rest
                    },
                ),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { testTagsAsResourceId = true },
            ) {
                if (affected.isEmpty()) {
                    Text(stringResource(R.string.tracker_rest_nothing))
                } else {
                    Text(stringResource(R.string.tracker_rest_will_reset))
                    affected.forEach { row ->
                        Text(
                            text = "•  ${row.label}  —  ${row.value} / ${row.total}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("tracker:rest:row:${row.propertyId}"),
                        )
                    }
                }
                if (state.showsRestHpNote(kind)) Text(stringResource(R.string.tracker_rest_hp_note))
                Text(
                    text = stringResource(R.string.tracker_rest_not_undoable),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("tracker:rest:warning"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                modifier = Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("tracker:rest:confirm"),
            ) { Text(stringResource(R.string.tracker_rest_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Which rows a rest of this kind will put back.
 *
 * Pure and public so `TrackerUiStateTest` can assert it without a Compose runtime — the
 * dialog's whole value is that this list is *right*, and "a short rest silently restored my
 * long-rest slots" is a bug you only find at the table.
 *
 * Rows already at full are included: seeing "1st Level — 3 / 3" in the list is how the user
 * learns the rest will not gain them anything, which is information, not noise.
 */
fun TrackerUiState.rowsRestoredBy(kind: RestKind): List<PipRowState> =
    (slots + resources).filter { row -> kind.restores(row.reset) }

/**
 * Whether the long-rest confirm adds `tracker_rest_hp_note` — *"The server also applies …
 * hit points, hit dice and any rest triggers"*.
 *
 * Two conditions, and the second one is the fix. The note is deliberately hedged about what a
 * long rest does beyond the rows listed above it, because `creature.methods.rest` applies the
 * sheet's own reset rules and triggers and what those cover varies by character — the WP7
 * probe's bare test dummy had its slots and Rage restored and its hit points left alone,
 * because it has no hit dice to spend.
 *
 * But every word of that is about **the server**. A local character has none, and
 * `LocalOpenCharacter.rest` is `current = total` on the qualifying rows and nothing else — it
 * does not touch `currentHp` at all (09 decision 7). Shown unconditionally, the note promised
 * hit points back to the one flow that cannot deliver them, on the *primary* local surface, in
 * a dialog whose entire job is to say truthfully what the button is about to do.
 *
 * `hasConnection` and not `canWrite`: it is the same "is there a server behind this at all"
 * question 09 decision 8 answers for the connection dot, and it is already false for every
 * local character (see [TrackerUiState.hasConnection]). A DiceCloud character that is merely
 * offline still has a server whose rest rules the note describes — and cannot open this
 * dialog anyway, since the rest buttons are inert without `canWrite`.
 */
fun TrackerUiState.showsRestHpNote(kind: RestKind): Boolean =
    kind == RestKind.LONG && hasConnection

private val KEYPAD = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(CLEAR, "0", BACKSPACE),
)

private const val BACKSPACE = "⌫"
private const val CLEAR = "C"
private const val MAX_DIGITS = 4
