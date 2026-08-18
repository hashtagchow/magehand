package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.R

/**
 * Everything the tracker can ask of the ViewModel.
 *
 * One parameter object rather than eight lambdas, so adding an intent is one edit in three
 * places instead of one edit in every preview and test. Each callback carries a
 * `creatureProperties._id` and nothing else: the row is re-resolved against the live board
 * in the ViewModel, so a tap that raced a re-sync writes to nothing rather than to a stale
 * value (see `CharacterHomeViewModel.withRow`).
 */
data class TrackerActions(
    /** A filled pip was tapped. */
    val onSpend: (propertyId: String) -> Unit = {},

    /** An empty pip was tapped. */
    val onRestore: (propertyId: String) -> Unit = {},

    /** The HP steppers. Negative damages, positive heals. */
    val onHpDelta: (delta: Int) -> Unit = {},

    /** The HP number was tapped — 04 §3's "damage/heal number pad on tap". */
    val onHpTap: () -> Unit = {},

    /** A consumable's − / + stepper. */
    val onItemDelta: (propertyId: String, delta: Int) -> Unit = { _, _ -> },

    /** A condition chip, or the concentration banner's ✕ (the same `flipToggle` write). */
    val onToggle: (propertyId: String) -> Unit = {},
)

/**
 * "This write was rolled back — shake the row it belonged to."
 *
 * [token] is what makes the animation re-fire: two identical failures in a row are the same
 * `propertyId`, and a `LaunchedEffect` keyed on the id alone would animate once and then sit
 * still while the user's taps kept failing.
 */
data class ShakeSignal(val propertyId: String?, val token: Long)

/**
 * The Tracker tab (docs/design/04-screens-ux.md §3) — **writable as of WP7**.
 *
 * ### Layout, top→bottom, exactly as 04 §3 orders it
 *
 * status strip · concentration banner · HP block · spell slots · resources ·
 * consumables · condition chips.
 *
 * ### What a tap can and cannot do
 *
 * Every control here calls a [TrackerActions] lambda with a property id. There is no DDP
 * method name in this file, no `WriteOp`, no queue: the vocabulary lives in `:core:data`
 * behind `OpenCharacter`'s named intents, which is what keeps the rate limiting, the
 * coalescing, the optimistic overlay and the undo stack unbypassable. `WritePostureTest`
 * asserts that mechanically rather than trusting this paragraph.
 *
 * Controls are dimmed and inert unless [TrackerUiState.canWrite] — the queue refuses
 * non-LIVE writes anyway, and 04 §UX principles wants that visible rather than surprising.
 *
 * ### Rollback
 *
 * There is no rollback code here either. `WriteQueue`'s optimistic overlay is *derived*
 * from the unresolved ops, so a failed write simply stops contributing and the number
 * changes back on its own (docs/verification/WP4.md deviation 14). What this file owns is
 * the part a user can see: the [shake] on the row that snapped back.
 *
 * ### testTagsAsResourceId
 *
 * The emulator probe reads rendered numbers back with `adb uiautomator dump`, which sees
 * `resource-id` but not Compose test tags. Opting in maps one to the other, so the probe
 * asserts `tracker:slot:<id> == "1 / 3"` against a REST snapshot fetched at the same
 * moment, rather than eyeballing a screenshot.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TrackerTab(
    state: TrackerUiState,
    modifier: Modifier = Modifier,
    actions: TrackerActions = TrackerActions(),
    shake: ShakeSignal? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        StatusStrip(state.status)

        state.concentratingOn?.let { name ->
            ConcentrationBanner(
                name = name,
                canDrop = state.canWrite && state.concentrationToggleId != null,
                onDrop = { state.concentrationToggleId?.let(actions.onToggle) },
            )
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        if (state.isEmpty) {
            EmptyBoard()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.hp?.let { hp ->
                item(key = "hp") {
                    HpBlock(
                        hp = hp,
                        canWrite = state.canWrite,
                        onDelta = actions.onHpDelta,
                        onTapNumber = actions.onHpTap,
                        modifier = Modifier.shakeOn(shake, hp.propertyId),
                    )
                }
            }

            if (state.slots.isNotEmpty()) {
                item(key = "slots-header") { SectionHeader(stringResource(R.string.tracker_section_slots)) }
                items(state.slots, key = { "slot-${it.propertyId}" }) { row ->
                    PipRow(
                        row = row,
                        testTag = "tracker:slot:${row.propertyId}",
                        canWrite = state.canWrite,
                        onSpend = actions.onSpend,
                        onRestore = actions.onRestore,
                        modifier = Modifier.shakeOn(shake, row.propertyId),
                    )
                }
            }

            if (state.resources.isNotEmpty()) {
                item(key = "resources-header") {
                    SectionHeader(stringResource(R.string.tracker_section_resources))
                }
                items(state.resources, key = { "resource-${it.propertyId}" }) { row ->
                    PipRow(
                        row = row,
                        testTag = "tracker:resource:${row.propertyId}",
                        canWrite = state.canWrite,
                        onSpend = actions.onSpend,
                        onRestore = actions.onRestore,
                        modifier = Modifier.shakeOn(shake, row.propertyId),
                    )
                }
            }

            if (state.consumables.isNotEmpty()) {
                item(key = "consumables-header") {
                    SectionHeader(stringResource(R.string.tracker_section_consumables))
                }
                items(state.consumables, key = { "item-${it.propertyId}" }) { row ->
                    ConsumableRow(
                        row = row,
                        canWrite = state.canWrite,
                        onDelta = actions.onItemDelta,
                        modifier = Modifier.shakeOn(shake, row.propertyId),
                    )
                }
            }

            if (state.conditions.isNotEmpty()) {
                item(key = "conditions-header") {
                    SectionHeader(stringResource(R.string.tracker_section_conditions))
                }
                item(key = "conditions") {
                    ConditionChips(
                        chips = state.conditions,
                        canWrite = state.canWrite,
                        onToggle = actions.onToggle,
                        shake = shake,
                    )
                }
            }

            if (!state.canWrite) item(key = "read-only-note") { ReadOnlyNote() }
        }
    }
}

/**
 * 04 §3's rollback shake.
 *
 * A short horizontal wobble rather than a colour flash: the row's *number* has already
 * changed back by the time this runs (the overlay dropped it), so what the animation has to
 * convey is "that just moved and it wasn't you" — and motion says that where a tint on a
 * pip row competes with the accent colour the user chose.
 */
@Composable
private fun Modifier.shakeOn(shake: ShakeSignal?, propertyId: String): Modifier {
    val offset = remember { Animatable(0f, Float.VectorConverter) }
    val active = shake?.propertyId == propertyId
    LaunchedEffect(shake?.token, active) {
        if (!active) return@LaunchedEffect
        offset.animateTo(
            targetValue = 0f,
            animationSpec = keyframes {
                durationMillis = SHAKE_MILLIS
                0f at 0
                -SHAKE_DP at 60
                SHAKE_DP at 120
                -SHAKE_DP / 2 at 180
                0f at SHAKE_MILLIS
            },
        )
    }
    return this.graphicsLayer { translationX = offset.value }
}

/**
 * 04 §3's connection chip, plus 06's snapshot banner.
 *
 * The two are separate lines because they answer different questions: the chip says
 * whether the socket is up, the banner says whether what you are looking at came off the
 * wire or out of Room. A reconnecting screen showing a two-hour-old snapshot has to say
 * both, and a single blended string cannot.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun StatusStrip(state: StatusStripState, modifier: Modifier = Modifier) {
    val (label, container, onContainer) = when (state.tone) {
        ConnectionTone.LIVE -> Triple(
            stringResource(R.string.connection_live),
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )

        ConnectionTone.RECONNECTING -> Triple(
            stringResource(R.string.connection_reconnecting),
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )

        ConnectionTone.OFFLINE -> Triple(
            state.syncedAt?.let { stringResource(R.string.connection_offline_synced, it) }
                ?: stringResource(R.string.connection_offline),
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionTone.SIGNED_OUT -> Triple(
            stringResource(R.string.connection_auth_failed),
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }

    Surface(color = container, contentColor = onContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.tone == ConnectionTone.LIVE) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                onContainer.copy(alpha = 0.5f)
                            },
                        ),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.testTag("tracker:status"),
                )
            }

            if (state.showingSnapshot) {
                Text(
                    text = state.syncedAt
                        ?.let { stringResource(R.string.tracker_snapshot_synced, it) }
                        ?: stringResource(R.string.tracker_snapshot),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("tracker:snapshot"),
                )
            }
        }
    }
}

/**
 * 04 §3: `Concentrating: Bless ✕`.
 *
 * The ✕ is live only when the banner's source is one of the discovered flippable toggles —
 * dropping concentration then *is* `flipToggle`, which is a write we can make correctly.
 * 03 §5 also lets the banner come from a `buff`, and 02 says `flipToggle` rejects anything
 * that is not a `toggle`; wiring the ✕ for that case would mean guessing at a method the
 * design does not specify, so it stays disabled instead. See [toTrackerUiState].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConcentrationBanner(
    name: String,
    canDrop: Boolean,
    onDrop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.tracker_concentrating, name),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .testTag("tracker:concentration"),
            )
            IconButton(
                onClick = onDrop,
                enabled = canDrop,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("tracker:concentration:drop"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.tracker_drop_concentration),
                )
            }
        }
    }
}

/**
 * 04 §3's HP block: "big current/max, −/+ steppers with press-and-hold acceleration,
 * temp-HP shield chip, damage/heal number pad on tap".
 *
 * **The temp-HP chip stays read-only, and that is the data model's decision, not an
 * omission.** 03 §Write semantics enumerates every tracker write, and temp HP is not among
 * them: DiceCloud stores it as its own `healthBar` attribute whose value the sheet's own
 * effects drive, and there is no documented method for "set temporary hit points". Guessing
 * that `damage` on it means what it means on the HP row is precisely the kind of unverified
 * write WP7 exists to avoid. It renders, it just does not take taps.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HpBlock(
    hp: HpState,
    canWrite: Boolean,
    onDelta: (Int) -> Unit,
    onTapNumber: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperButton(
                    glyph = MINUS,
                    contentDescription = stringResource(R.string.tracker_damage),
                    enabled = canWrite,
                    onStep = { onDelta(-1) },
                    testTag = "tracker:hp:minus",
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = canWrite, onClick = onTapNumber)
                        .testTag("tracker:hp:pad"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "${hp.current}",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("tracker:hp:current"),
                        )
                        Text(
                            text = " / ${hp.max}",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp).testTag("tracker:hp:max"),
                        )
                    }
                    Text(
                        text = stringResource(R.string.tracker_hit_points),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                StepperButton(
                    glyph = PLUS,
                    contentDescription = stringResource(R.string.tracker_heal),
                    enabled = canWrite,
                    onStep = { onDelta(+1) },
                    testTag = "tracker:hp:plus",
                )
            }

            Spacer(Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { hp.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(CircleShape),
                color = hpBarColor(hp.fraction),
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )

            if (hp.hasTempHp) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Text(
                        text = stringResource(R.string.tracker_temp_hp, hp.tempHp),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("tracker:hp:temp"),
                    )
                }
            }
        }
    }
}

/**
 * Red below a quarter, amber below a half, otherwise the accent.
 *
 * Colour is the *second* signal here, never the only one — the number is right above it —
 * which is what keeps this readable for a colour-blind player at a dim table.
 */
@Composable
private fun hpBarColor(fraction: Float): Color = when {
    fraction <= 0.25f -> MaterialTheme.colorScheme.error
    fraction <= 0.5f -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}

/**
 * One spell-slot level or one resource: name, reset rule, and `total` pips.
 *
 * Above [PipRowState.MAX_PIPS] the pips are replaced by `value / total` and a bar. Eight
 * 48 dp targets plus their gaps is already 400 dp — past the width of a 360 dp phone —
 * so a 9th pip would silently overflow rather than wrap into something tappable. That
 * fallback row keeps a pair of steppers instead, so a 20-charge resource is still spendable.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PipRow(
    row: PipRowState,
    testTag: String,
    canWrite: Boolean,
    onSpend: (String) -> Unit,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            row.resetLabel?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                // The probe's parity anchor: one string, "value / total", per row.
                text = "${row.value} / ${row.total}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(testTag),
            )
        }

        Spacer(Modifier.height(4.dp))

        if (row.usePips) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(row.total) { index ->
                    val filled = index < row.value
                    Pip(
                        filled = filled,
                        enabled = canWrite,
                        // 04 §3: "tap pip = spend, tap empty pip = restore". Which pip was
                        // tapped is irrelevant — only whether it was a full one — so the
                        // whole row behaves like one control with two halves.
                        onClick = { if (filled) onSpend(row.propertyId) else onRestore(row.propertyId) },
                        contentDescription = stringResource(
                            if (filled) R.string.tracker_spend_one else R.string.tracker_restore_one,
                            row.label,
                        ),
                        modifier = Modifier.testTag("$testTag:pip:$index"),
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperButton(
                    glyph = MINUS,
                    contentDescription = stringResource(R.string.tracker_spend_one, row.label),
                    enabled = canWrite && row.value > 0,
                    onStep = { onSpend(row.propertyId) },
                    testTag = "$testTag:minus",
                )
                LinearProgressIndicator(
                    progress = { if (row.total <= 0) 0f else (row.value.toFloat() / row.total).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
                StepperButton(
                    glyph = PLUS,
                    contentDescription = stringResource(R.string.tracker_restore_one, row.label),
                    enabled = canWrite && row.value < row.total,
                    onStep = { onRestore(row.propertyId) },
                    testTag = "$testTag:plus",
                )
            }
        }
    }
}

/**
 * One pip: a 48 dp touch target (04 §3, "Large touch targets (min 48 dp)") around a 28 dp
 * dot. The target is the box, not the dot, so a thumb at a dark table does not have to
 * find a small circle — but the dot stays small enough that eight of them read as a row
 * of charges rather than a row of buttons.
 */
@Composable
private fun Pip(
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(PIP_TARGET_DP.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(PIP_DOT_DP.dp)
                .clip(CircleShape)
                .then(
                    if (filled) {
                        Modifier.background(
                            MaterialTheme.colorScheme.primary
                                .copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
                        )
                    } else {
                        Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outline
                                .copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
                            shape = CircleShape,
                        )
                    },
                ),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConsumableRow(
    row: ConsumableState,
    canWrite: Boolean,
    onDelta: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StepperButton(
            glyph = MINUS,
            contentDescription = stringResource(R.string.tracker_use_one, row.name),
            enabled = canWrite && row.quantity > 0,
            onStep = { onDelta(row.propertyId, -1) },
            testTag = "tracker:item:${row.propertyId}:minus",
        )
        Text(
            text = "${row.quantity}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .width(48.dp)
                .testTag("tracker:item:${row.propertyId}"),
        )
        StepperButton(
            glyph = PLUS,
            contentDescription = stringResource(R.string.tracker_add_one, row.name),
            enabled = canWrite,
            onStep = { onDelta(row.propertyId, +1) },
            testTag = "tracker:item:${row.propertyId}:plus",
        )
    }
}

/**
 * 04 §3's condition chips — `tap = flipToggle`.
 *
 * `FilterChip` rather than `AssistChip` now that they are live: a filter chip has a
 * *selected* state built in, which is exactly what a toggle is, and it carries that state
 * into the accessibility tree instead of relying on the container colour alone. (It also
 * fixes WP6 §7's grumble that a disabled `AssistChip` still reported `clickable=true`.)
 *
 * A chip is tappable only when the server would accept the flip
 * ([ConditionChipState.canFlip]). On the party's real sheets that is currently *none* of
 * them — every toggle there is computed — so this is the difference between a chip row
 * that informs and a chip row that hands out errors.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConditionChips(
    chips: List<ConditionChipState>,
    canWrite: Boolean,
    onToggle: (String) -> Unit,
    shake: ShakeSignal?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.chunked(CHIPS_PER_ROW).forEach { rowChips ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowChips.forEach { chip ->
                    FilterChip(
                        selected = chip.enabled,
                        onClick = { onToggle(chip.propertyId) },
                        enabled = canWrite && chip.canFlip,
                        label = {
                            Text(
                                text = chip.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(),
                        modifier = Modifier
                            .shakeOn(shake, chip.propertyId)
                            .testTag("tracker:toggle:${chip.propertyId}"),
                    )
                }
            }
        }
    }
}

/**
 * Shown only while writes are refused: "you are looking at a sheet you cannot change yet".
 *
 * WP6 needed this permanently, because the whole tab was inert. Now it is the *offline*
 * explanation, and it disappears the moment the subscription goes ready — which is also
 * why it is a sentence in the list rather than a toast: there is nothing to tap.
 */
@Composable
private fun ReadOnlyNote(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.tracker_writes_need_live),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(12.dp)
                .testTag("tracker:offline-note"),
        )
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}

@Composable
private fun EmptyBoard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.tracker_empty),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.tracker_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A 48 dp `−` / `+` button with 04 §3's **press-and-hold acceleration**.
 *
 * `−` and `+` are typed rather than drawn because `material-icons-core` — the ~200 KB
 * subset this app deliberately depends on instead of the ~50 MB extended set (see the
 * version catalog) — ships `Add` but not `Remove`. Pulling in `icons-extended` for one
 * minus sign, or hand-rolling an `ImageVector` for it, would both be worse trades than a
 * character the display face already renders correctly at this size.
 *
 * ### Why hold-to-repeat is safe against the rate limiter
 *
 * It is not throttled here, and deliberately so. Every repeat is one `submit`, and the
 * queue coalesces everything that piles up behind its 250 ms `damage` gate into a single
 * `increment` with the summed value (docs/design/02-ddp-and-api.md §Client rule). So a
 * two-second hold on the damage stepper is ~15 taps, one server call, and one undo entry
 * that reverses all of it. Throttling the *gesture* instead would make the number crawl
 * while the queue sat idle.
 */
@Composable
private fun StepperButton(
    glyph: String,
    contentDescription: String,
    enabled: Boolean,
    onStep: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed, enabled) {
        if (!pressed || !enabled) return@LaunchedEffect
        // The first step already fired on press-down, so this loop is only the repeat.
        delay(HOLD_DELAY_MILLIS)
        var interval = HOLD_START_INTERVAL_MILLIS
        while (true) {
            onStep()
            delay(interval)
            interval = (interval * HOLD_ACCELERATION).toLong().coerceAtLeast(HOLD_MIN_INTERVAL_MILLIS)
        }
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    scope.launch { onStep() }
                    waitForUpOrCancellation()
                    pressed = false
                }
            }
            .semantics { this.contentDescription = contentDescription }
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
                .copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
        )
    }
}

/** U+2212 MINUS SIGN — not a hyphen; it matches the `+` optically at this size. */
private const val MINUS = "\u2212"
private const val PLUS = "+"

private const val PIP_TARGET_DP = 48
private const val PIP_DOT_DP = 28
private const val CHIPS_PER_ROW = 2

/** Material's own disabled-content alpha; used where a control is drawn by hand. */
private const val DISABLED_ALPHA = 0.38f

/** How far a rolled-back row travels, in layout pixels, and for how long. */
private const val SHAKE_DP = 18f
private const val SHAKE_MILLIS = 260

/** Hold-to-repeat: a beat before it starts, then 300 ms steps accelerating to 60 ms. */
private const val HOLD_DELAY_MILLIS = 350L
private const val HOLD_START_INTERVAL_MILLIS = 300L
private const val HOLD_MIN_INTERVAL_MILLIS = 60L
private const val HOLD_ACCELERATION = 0.82
