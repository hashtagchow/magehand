package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.ChargeConsumer
import com.hashtagchow.magehand.ui.screens.characterhome.actions.ActionsUiState
import com.hashtagchow.magehand.ui.screens.characterhome.actions.UseAffordance
import com.hashtagchow.magehand.ui.screens.characterhome.actions.UnusableReason
import com.hashtagchow.magehand.ui.screens.characterhome.actions.detailFor
import com.hashtagchow.magehand.ui.screens.characterhome.actions.labelRes
import com.hashtagchow.magehand.ui.theme.DisabledContent

/**
 * The chooser a spend opens on a charge with an action behind it (FR-59 stage 1,
 * docs/design/22-charge-consumers.md R3/R4).
 *
 * ### Built from the board and the Actions tab's own state, never from either alone
 *
 * The consumers come off the tracker row ([PipRowState.consumers], discovered in `:core:data`).
 * Whether each one can be used **right now**, and why not, comes from the Actions tab's state —
 * [ActionsUiState.detailFor], the same lookup the Actions detail sheet makes — so the chooser and
 * the Actions tab cannot disagree about a consumer: a use that tab would refuse is disabled here
 * with that tab's own sentence (R4), and the [UseAffordance] a row hands on is the very value the
 * Actions tab's Use button would have used (R5).
 *
 * @property rowId the resource row's `propertyId` — what *Just spend a charge* spends.
 * @property title the resource's own name (R4: the sheet is titled with it).
 * @property justSpendEnabled `false` while any of the row's consumers has a use in flight (design
 *   22 addendum 2 item 7): that use is about to take a charge itself, and a second spend beside it
 *   would take two. The row's own pip/stepper spend is gated by the same rule
 *   ([PipRowState.spendBlockedBy]).
 */
data class ChargeChooserState(
    val rowId: String,
    val title: String,
    val consumers: List<ConsumerChoiceState>,
    val justSpendEnabled: Boolean = true,
)

/**
 * One consumer row of the chooser.
 *
 * @property use the Actions tab's affordance for this action, or `null` when that tab offers no
 *   Use for it — 17 decision 2's *absent, not disabled* there, which becomes *shown, disabled, with
 *   the reason* here, because a chooser row that vanished would leave the player wondering where
 *   their form went.
 * @property unusableReason the Actions tab's own reason when [use] is `null`.
 * @property resolved whether the Actions state had a row for this consumer at all. When it did
 *   not, the app has computed no reason, so the row says the neutral *"Not available right now"*
 *   rather than borrowing a cost reason it did not establish (addendum 2 item 8).
 */
data class ConsumerChoiceState(
    val actionId: String,
    val name: String,
    val applies: String?,
    val use: UseAffordance?,
    val unusableReason: UnusableReason? = null,
    val resolved: Boolean = true,
) {
    /** Whether a tap does anything. Exactly the Actions tab's Use button's `enabled`. */
    val enabled: Boolean get() = use?.enabled == true

    /**
     * The sentence a disabled row carries, as the Actions tab words it — `null` when [enabled].
     *
     * No Actions row at all → *"Not available right now"*; absent use → the detail sheet's reason
     * (unprepared, no uses, can't afford it); present but not enabled → the Use button's own label
     * for that state (*Using…* while one is on the wire, *Use — you're offline* off LIVE).
     */
    @get:StringRes
    val disabledReasonRes: Int?
        get() = when {
            enabled -> null
            !resolved -> R.string.tracker_consumer_unavailable
            use == null -> unusableReason.labelRes()
            use.inFlight -> R.string.action_use_in_flight
            else -> R.string.action_use_offline
        }
}

/**
 * The chooser for [row], or `null` when the row has no consumers — the "unchanged" case R2
 * insists on, made structural: a `null` here is a row whose spend writes exactly as before.
 *
 * A consumer the Actions state cannot find (the two engines disagreeing about a live action,
 * which nothing on the evidence produces) is shown disabled, with the neutral reason, rather than
 * dropped, for [ConsumerChoiceState.use]'s reason. A blank `applies` is treated as absent.
 */
fun chargeChooserFor(row: PipRowState, actions: ActionsUiState): ChargeChooserState? {
    if (!row.spendOpensChooser) return null
    return ChargeChooserState(
        rowId = row.propertyId,
        title = row.label,
        consumers = row.consumers.map { it.toChoice(actions) },
        justSpendEnabled = !row.spendBlockedBy(actions.usesInFlight),
    )
}

private fun ChargeConsumer.toChoice(actions: ActionsUiState): ConsumerChoiceState {
    val detail = actions.detailFor(actionId)
    return ConsumerChoiceState(
        actionId = actionId,
        name = name,
        applies = applies?.takeIf { it.isNotBlank() },
        use = detail?.use,
        unusableReason = detail?.unusableReason,
        resolved = detail != null,
    )
}

/**
 * R4's bottom sheet.
 *
 * Dismissing it does nothing (R4) — it only closes. Every row is one 48 dp target whose spoken
 * line is one sentence (*"Use <action>, applies <buff>"*, or *"Can't use <action>: <reason>"*),
 * set with `clearAndSetSemantics` so the visible name and subtitle are not read a second time.
 * The title is a heading, so a screen reader can jump to it.
 *
 * @param onUse handed the consumer's [UseAffordance] — the caller runs the Actions tab's own
 *   confirm dialog with it (R5). Never a property id: an id could be built for any row.
 * @param onJustSpend today's spend, on this row.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ChargeChooserSheet(
    state: ChargeChooserState,
    onUse: (UseAffordance) -> Unit,
    onJustSpend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        ChargeChooserContent(state = state, onUse = onUse, onJustSpend = onJustSpend)
    }
}

/** The sheet's body: the title, the consumers (scrolling), a divider, and *Just spend a charge* (fixed). */
@Composable
private fun ChargeChooserContent(
    state: ChargeChooserState,
    onUse: (UseAffordance) -> Unit,
    onJustSpend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Addendum 2 item 7's double-tap half: two taps landing before the sheet recomposes closed
    // would otherwise send two spends. A synchronous check-and-set, `UseConfirmDialog`'s latch.
    var spent by remember { mutableStateOf(false) }
    // Closing review L: the fixed-footer layout below cannot always fit. At 240 dp tall and system
    // font scale 2.0 a two-line title, the footer and the padding leave the list under one row,
    // and a weighted list squeezed below its floor shows no consumer and has nothing to scroll.
    // When the list is measured below [CONSUMER_LIST_MIN_HEIGHT_DP] the WHOLE body scrolls
    // instead — title, consumers, divider and *Just spend a charge* in one column — so nothing is
    // ever clipped. Latched for the life of the sheet: flipping back and forth on every
    // measurement would make the layout jump under the player's thumb.
    var wholeBodyScrolls by remember { mutableStateOf(false) }
    val listFloorPx = with(LocalDensity.current) { CONSUMER_LIST_MIN_HEIGHT_DP.dp.roundToPx() }

    val title: @Composable () -> Unit = {
        // Capped at two lines so a long resource name cannot eat the list on a short screen at a
        // large font (closing review L); the full name stays in the spoken title.
        Text(
            text = state.title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .semantics {
                    heading()
                    contentDescription = state.title
                },
        )
    }
    val consumers: @Composable () -> Unit = {
        state.consumers.forEach { consumer -> ConsumerRow(consumer = consumer, onUse = onUse) }
    }
    val footer: @Composable () -> Unit = {
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        val justSpend = stringResource(R.string.tracker_consumer_just_spend)
        ChoiceRow(
            title = justSpend,
            subtitle = null,
            spoken = justSpend,
            enabled = state.justSpendEnabled && !spent,
            testTag = "tracker:consumer:spend",
            onClick = {
                if (!spent && state.justSpendEnabled) {
                    spent = true
                    onJustSpend()
                }
            },
        )
    }

    val body = modifier
        .fillMaxWidth()
        .padding(bottom = 24.dp)
        .semantics { testTag = "tracker:consumers:${state.rowId}" }

    if (wholeBodyScrolls) {
        Column(body.verticalScroll(rememberScrollState())) {
            title()
            consumers()
            footer()
        }
    } else {
        Column(body) {
            title()
            // FR-59 stage 2 is delivered sheet-side as one action per known form, so a charge can
            // carry a dozen consumers. They scroll; *Just spend a charge* does NOT — it is fixed
            // below the list, so the plain spend (today's write, the one a player reaches for at a
            // busy table) is never behind a scroll. `weight(fill = false)` lets a short list take
            // only its own height and a long one take what is left, inside the sheet's bounded
            // height; `heightIn(min)` asks for at least one row, and when even that is refused the
            // size check flips to the whole-body scroll above.
            Column(
                Modifier
                    .heightIn(min = CONSUMER_LIST_MIN_HEIGHT_DP.dp)
                    .weight(1f, fill = false)
                    .onSizeChanged { if (it.height < listFloorPx) wholeBodyScrolls = true }
                    .verticalScroll(rememberScrollState()),
            ) {
                consumers()
            }
            footer()
        }
    }
}

@Composable
private fun ConsumerRow(consumer: ConsumerChoiceState, onUse: (UseAffordance) -> Unit) {
    val reason = consumer.disabledReasonRes?.let { stringResource(it) }
    val spoken = when {
        reason != null && consumer.applies != null ->
            stringResource(R.string.tracker_consumer_spoken_disabled_applies, consumer.name, consumer.applies, reason)
        reason != null -> stringResource(R.string.tracker_consumer_spoken_disabled, consumer.name, reason)
        consumer.applies != null ->
            stringResource(R.string.tracker_consumer_spoken_applies, consumer.name, consumer.applies)
        else -> stringResource(R.string.tracker_consumer_spoken, consumer.name)
    }
    ChoiceRow(
        title = consumer.name,
        subtitle = listOfNotNull(
            consumer.applies?.let { stringResource(R.string.tracker_consumer_applies, it) },
            reason,
        ).joinToString("\n").ifEmpty { null },
        spoken = spoken,
        enabled = consumer.enabled,
        testTag = "tracker:consumer:${consumer.actionId}",
        onClick = { consumer.use?.let(onUse) },
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
    spoken: String,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                this.testTag = testTag
                if (enabled) {
                    onClick { onClick(); true }
                } else {
                    disabled()
                }
            }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DisabledContent.MATERIAL_ALPHA)
            },
        )
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The scrolling consumer list's floor — one row's touch target, the 48 dp [ChoiceRow] already
 * guarantees every row (closing review L). Every row is at least this tall, so a list measured
 * shorter than it has been squeezed, never merely short — which is what makes it a safe trigger
 * for the whole-body scroll.
 */
private const val CONSUMER_LIST_MIN_HEIGHT_DP = 48
