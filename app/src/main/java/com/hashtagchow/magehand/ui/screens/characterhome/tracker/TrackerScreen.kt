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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.DeathSaves
import com.hashtagchow.magehand.ui.components.DirectEntryDialog
import com.hashtagchow.magehand.ui.components.DirectEntryKeys
import com.hashtagchow.magehand.ui.components.DirectEntryKind
import com.hashtagchow.magehand.ui.components.directEntry
import com.hashtagchow.magehand.ui.theme.DisabledContent
import com.hashtagchow.magehand.ui.theme.mageHandIconButtonColors

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

    /**
     * FR-22 direct entry on the HP row (15 decisions 5 and 6): set hit points to an absolute
     * value.
     *
     * The same intent the number pad's Set button already calls, reached by the long press
     * decision 5 names. It is a *separate* callback from [onHpTap] because that one opens a
     * dialog and this one is a write — see [TrackerTab] for why the long press does not simply
     * reopen the pad.
     */
    val onHpSet: (value: Int) -> Unit = {},

    /**
     * FR-22 direct entry on a slot or resource row (decision 6: delta-shaped, one op).
     *
     * The value is absolute — what the row should read — and the view model turns it into the
     * single `spend` or `restore` that gets there. The composable does not do that arithmetic,
     * for the reason every other callback here carries an id and nothing else: the row is
     * re-resolved against the live board before anything is written.
     */
    val onResourceSet: (propertyId: String, value: Int) -> Unit = { _, _ -> },

    /** A consumable's − / + stepper. */
    val onItemDelta: (propertyId: String, delta: Int) -> Unit = { _, _ -> },

    /** FR-22 direct entry on a consumable's quantity (decision 6: absolute-shaped, one op). */
    val onItemSet: (propertyId: String, value: Int) -> Unit = { _, _ -> },

    /**
     * FR-23 (15 decisions 19–21): a death-save pip was tapped, carrying the marks the block
     * should read afterwards.
     *
     * **Both counts, always**, even though a tap only ever moves one of them. The intent behind
     * it is pair-shaped — `OpenCharacter.setDeathSaves` writes two properties and skips the half
     * that did not change — and a callback that carried only the moved half would have made the
     * composable responsible for remembering the other, on a screen that re-derives everything
     * from state on every frame.
     */
    val onDeathSaves: (successes: Int, failures: Int) -> Unit = { _, _ -> },

    /** A condition chip, or the concentration banner's ✕ (the same `flipToggle` write). */
    val onToggle: (propertyId: String) -> Unit = {},

    /**
     * FR-7: an entry was picked in the Rolls dropdown.
     *
     * The odd one out in this class, and worth saying why it is here anyway: every other
     * callback carries a `creatureProperties._id` towards a *server* write, while this one
     * carries a `RollModifier.id` towards a local preference. It belongs in the same parameter
     * object because it is the same kind of thing to the composable — "the tab needs to tell
     * the ViewModel that something was tapped" — and a second lambda parameter on `TrackerTab`
     * for one intent would be the start of the eight the class KDoc says it replaced.
     */
    val onSelectRoll: (rollId: String) -> Unit = {},

    /**
     * The bottom-right not-live dot was tapped: open the connection details sheet.
     *
     * Hoisted like the history and customize sheets rather than owned by [TrackerTab],
     * because the sheet's retry action is a ViewModel call and the screen is where the
     * ViewModel is.
     */
    val onConnectionDetails: () -> Unit = {},
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
 * Whether a [ShakeSignal] is aimed at a chip the collapsed *"N inactive"* drawer is
 * currently hiding — in which case [InactiveConditions] has to open, or the apology plays
 * to an empty stage.
 *
 * ### The sequence this exists for
 *
 * A failed `flipToggle` gets two pieces of feedback: a snackbar, and a shake on the row
 * that snapped back. The shake is a `LaunchedEffect` on the row's own modifier, so it can
 * only fire on a row that is *composed*, and a condition chip's section is decided by
 * `ConditionToggle.shownByDefault` — `enabled || pinned`. Turning an unpinned condition on
 * from inside the drawer therefore moves it out of the drawer while the write is in
 * flight, and the rollback moves it back in. If the drawer is shut by then, the snackbar
 * says "that didn't save" and nothing on screen says *what*.
 *
 * The drawer is shut by then more often than it sounds. Flipping the drawer's **last**
 * chip on empties the list, which removes the whole `conditions-inactive` item from the
 * `LazyColumn` — taking its `rememberSaveable` expansion flag with it — so the row that
 * reappears on failure comes back into a drawer that has been reset to collapsed. Closing
 * the drawer by hand after the tap does the same thing more directly.
 *
 * ### Why a function rather than three lines in the composable
 *
 * Because the bug it fixes is invisible: the failing version renders identically, minus an
 * animation nobody can screenshot. Pure, so `TrackerShakeTargetTest` pins the decision on
 * the JVM instead of trusting the composable.
 *
 * Auto-expanding is a deliberate, narrow exception to that drawer being the user's to
 * open — see [InactiveConditions] on why the flag is not persisted. A rollback is the one
 * moment the app has something to say about a chip in there, and it says it once, in
 * response to the user's own tap.
 */
fun shakeIsHiddenByExpander(
    shake: ShakeSignal?,
    inactiveChips: List<ConditionChipState>,
    expanded: Boolean,
): Boolean {
    if (expanded) return false
    // A failure with no propertyId (a rest, say) belongs to no row and shakes nothing.
    val target = shake?.propertyId ?: return false
    return inactiveChips.any { it.propertyId == target }
}

/**
 * The Tracker tab (docs/design/04-screens-ux.md §3) — **writable as of WP7**.
 *
 * ### Layout, top→bottom, exactly as 04 §3 orders it
 *
 * concentration banner · HP block · defenses · spell slots · resources · consumables ·
 * condition chips (the active ones, then an "N inactive" expander — see
 * [InactiveConditions]).
 *
 * Defenses are the one addition to 04 §3's order. They are read-only reference rather than
 * a tracked resource, and they sit directly under HP because that is the question they
 * answer — "how much of that hit actually lands?" — asked at the same moment.
 *
 * 04 §3's permanent connection strip used to sit above all of that. It is gone: the tab
 * now says nothing at all about the connection while it is healthy, and floats a single
 * [ConnectionDot] over the bottom-right corner when it is not. The strip's whole content
 * moved into `TrackerConnectionSheet`, one tap away. See [ConnectionStatus] for why.
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
    // FR-22 (15 decision 5). The dialog is hoisted to the tab rather than owned by each row so
    // that only one can be open, and it is keyed by **id** rather than by a captured row for
    // `InventoryUiState.row`'s reason: an open dialog stays live against a sync, and a row that
    // stops existing underneath it closes the dialog instead of freezing a stale number.
    //
    // `rememberSaveable` of a `String?` rather than of the row: a rotation mid-type must not
    // throw the gesture away (the field's own text is saved by `DirectEntryDialog`), and a key
    // is the only part of this that a `Bundle` can carry.
    var entryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val entry = entryKey?.let { state.directEntryTarget(it) }
    // A key naming a row the board no longer has: drop the gesture rather than leave a dialog
    // that cannot resolve what it is editing.
    if (entryKey != null && entry == null) entryKey = null

    entry?.let { target ->
        DirectEntryDialog(
            // HP has no name on the sheet — `directEntryTarget` leaves it blank on purpose and
            // the copy is resolved here, which is this screen's standing split between rules
            // (testable, in the state) and words (`strings.xml`, in the composable).
            label = target.label.ifEmpty { stringResource(R.string.tracker_hit_points) },
            current = target.current,
            max = target.max,
            onSet = { value ->
                when (target.kind) {
                    DirectEntryKind.HIT_POINTS -> actions.onHpSet(value)
                    DirectEntryKind.RESOURCE -> actions.onResourceSet(target.propertyId, value)
                    DirectEntryKind.ITEM -> actions.onItemSet(target.propertyId, value)
                    // The tracker has no wallet — `directEntryTarget` never mints a coin key
                    // here — but the enum is shared with the inventory tab, so the branch is
                    // named rather than swept into an `else` that would silently absorb a
                    // future kind this tab *does* need to handle.
                    DirectEntryKind.COIN -> Unit
                }
            },
            onDismiss = { entryKey = null },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        TrackerContent(
            state = state,
            actions = actions,
            shake = shake,
            onDirectEntry = { key -> entryKey = key },
        )

        // Quiet when healthy, and quiet over the spinner while a redial is in flight —
        // but never over a spinner that is never going to end (offline / signed out),
        // where the dot is the only route to the explanation. The whole rule, and why,
        // is TrackerUiState.showConnectionIndicator.
        if (state.showConnectionIndicator) {
            ConnectionDot(
                status = state.status,
                onClick = actions.onConnectionDetails,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

/**
 * The tab's actual content, extracted only so [TrackerTab]'s root can be the [Box] that
 * the connection dot floats in. Everything about this function is what the tab always was.
 */
@Composable
private fun TrackerContent(
    state: TrackerUiState,
    actions: TrackerActions,
    shake: ShakeSignal?,
    onDirectEntry: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
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
            // The extra bottom inset is the floating dot's footprint: without it the last
            // row — which when disconnected is the read-only note — scrolls to rest
            // *underneath* the very control that explains why it is there.
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = if (state.showConnectionIndicator) 72.dp else 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // FR-23 decision 18: "Unmissable, above HP." Above rather than inside the HP block
            // and above rather than below it — a player at 0 HP is looking for one thing, and it
            // is not their hit points. `state.deathSaves` is already the whole trigger (pair
            // discovered AND the HP row reads zero); see `TrackerUiState.deathSaves`.
            state.deathSaves?.let { saves ->
                item(key = "death-saves") {
                    DeathSaveBlock(
                        saves = saves,
                        canWrite = state.canWrite,
                        onMark = actions.onDeathSaves,
                    )
                }
            }

            state.hp?.let { hp ->
                item(key = "hp") {
                    HpBlock(
                        hp = hp,
                        canWrite = state.canWrite,
                        onDelta = actions.onHpDelta,
                        onTapNumber = actions.onHpTap,
                        onDirectEntry = { onDirectEntry(DirectEntryKeys.HIT_POINTS) },
                        modifier = Modifier.shakeOn(shake, hp.propertyId),
                    )
                }
            }

            // Between HP and the slots: combat reference next to the other combat
            // reference. Absent — header and all — for the many characters with none.
            if (state.defenses.isNotEmpty()) {
                item(key = "defenses-header") {
                    SectionHeader(stringResource(R.string.tracker_section_defenses))
                }
                item(key = "defenses") { DefenseRows(rows = state.defenses) }
            }

            // FR-7, and it sits here for the same reason the defenses do: it answers a
            // question asked mid-turn rather than tracking something the player spends.
            // Absent — header and all — for a character whose data expresses no rolls.
            if (state.rolls.isPresent) {
                item(key = "rolls-header") {
                    SectionHeader(stringResource(R.string.tracker_section_rolls))
                }
                item(key = "rolls") {
                    RollPicker(state = state.rolls, onSelect = actions.onSelectRoll)
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
                        onDirectEntry = { onDirectEntry(DirectEntryKeys.resource(row.propertyId)) },
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
                        onDirectEntry = { onDirectEntry(DirectEntryKeys.resource(row.propertyId)) },
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
                        onDirectEntry = { onDirectEntry(DirectEntryKeys.item(row.propertyId)) },
                        modifier = Modifier.shakeOn(shake, row.propertyId),
                    )
                }
            }

            if (state.conditions.isNotEmpty() || state.inactiveConditions.isNotEmpty()) {
                item(key = "conditions-header") {
                    SectionHeader(stringResource(R.string.tracker_section_conditions))
                }
                if (state.conditions.isNotEmpty()) {
                    item(key = "conditions") {
                        ConditionChips(
                            chips = state.conditions,
                            canWrite = state.canWrite,
                            onToggle = actions.onToggle,
                            shake = shake,
                        )
                    }
                }
                if (state.inactiveConditions.isNotEmpty()) {
                    item(key = "conditions-inactive") {
                        InactiveConditions(
                            chips = state.inactiveConditions,
                            canWrite = state.canWrite,
                            onToggle = actions.onToggle,
                            shake = shake,
                        )
                    }
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
 * The whole of the tracker's connection chrome: one dot, bottom-right, only when the
 * sheet is **not** live.
 *
 * ### Why a dot and not the strip it replaced
 *
 * See [ConnectionStatus]. The short version: the strip reported "no problem" for almost
 * every second it was ever on screen, and charged a row of the tracker for it.
 *
 * ### The 48 dp that is not the 10 dp
 *
 * The visual is deliberately tiny — it is a hint, not an alarm — but the *target* is the
 * app's usual 48 dp (`PIP_TARGET_DP`, the same figure every stepper and pip uses). Sizing
 * the touch area to the paint would make the only route to the connection details a 10 dp
 * bullseye at the corner of the screen, which is where thumbs are least accurate.
 *
 * The disc behind the dot is not decoration either: this floats over arbitrary scrolling
 * content, and an unbacked error-coloured dot can land on an error-coloured pip. The
 * surface disc guarantees it reads as a control at any scroll position.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConnectionDot(
    status: ConnectionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(status.indicatorDescriptionRes)
    Box(
        modifier = modifier
            .padding(DOT_MARGIN_DP.dp)
            .size(PIP_TARGET_DP.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = description }
            .testTag("tracker:connection"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
            modifier = Modifier.size(DOT_DISC_DP.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(DOT_DP.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
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
                // BUG-3: this ✕ is disabled whenever the banner's source is not a flippable
                // toggle, which is the common case — the same raised disabled tint the
                // customize sheet's chevrons now use.
                colors = mageHandIconButtonColors(),
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
    onDirectEntry: () -> Unit,
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
                        // FR-22 decisions 5 and 8. The **tap keeps the number pad** — it has
                        // opened it since WP7 and removing a shipped control to make four
                        // surfaces symmetrical would be a regression dressed as consistency —
                        // so here the long press is the whole of the new gesture. The pad's own
                        // Set button reaches the same intent, which is why the spoken sentence
                        // is true of both.
                        .directEntry(
                            enabled = canWrite,
                            spoken = stringResource(
                                R.string.direct_entry_spoken_of,
                                stringResource(R.string.tracker_hit_points),
                                hp.current,
                                hp.max,
                            ),
                            onOpen = onDirectEntry,
                            onClick = onTapNumber,
                        )
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
 * FR-23's death-save block (docs/design/15-polish-batch.md decisions 18–21).
 *
 * ### Why it is at the top and why it is loud
 *
 * Decision 18: *"Unmissable, above HP."* A character at 0 HP is the one moment in a session
 * where the tracker has something urgent to say, and the block is only ever on screen then — so
 * it can afford the error container and the full width. Nothing is competing with it, because
 * the trigger guarantees the situation.
 *
 * ### Two rows of pips, and why they are not [PipRow]s
 *
 * A `PipRow` means "you have N of these left and spending one takes it away": its filled pips
 * are what remains, its taps go to `spend`/`restore`, and it draws a progress bar past eight.
 * Death saves invert every one of those — the pips fill as things get *worse*, there is no
 * resource being consumed, the write is an absolute `set`, and there are always exactly three.
 * Reusing the row would have meant four exceptions inside a composable built on the opposite
 * reading; six `Pip`s and the shared 48 dp target is the part actually worth sharing.
 *
 * ### What a tap does (decision 20's "one tap fixes")
 *
 * Tapping pip *i* sets that row to `i + 1`, **except** when the row already reads `i + 1`, in
 * which case it sets `i` — so the last filled pip toggles itself off and every count from 0 to 3
 * is one tap away. That is what makes a stale-marks sheet correctable: the design accepts that a
 * character healed by another client keeps their marks, on the explicit grounds that the pips
 * are tappable, so "clear these" has to be reachable and cheap. Clearing both rows is two taps.
 *
 * Taps are refused off-LIVE like every other write on this screen, and the pips dim rather than
 * disappear (04 §UX principles).
 *
 * ### Stable and dead are a *line*, not a colour
 *
 * `hpBarColor`'s rule, applied here: colour is the second signal and never the only one. A
 * player at three failures in a dim room reads the sentence.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeathSaveBlock(
    saves: DeathSaves,
    canWrite: Boolean,
    onMark: (successes: Int, failures: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("tracker:deathsaves"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.tracker_section_death_saves).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )

            DeathSaveRow(
                label = stringResource(R.string.death_saves_successes),
                marks = saves.successes,
                canWrite = canWrite,
                descriptionRes = R.string.death_saves_mark_success,
                testTag = "tracker:deathsaves:successes",
                onSet = { onMark(it, saves.failures) },
            )
            DeathSaveRow(
                label = stringResource(R.string.death_saves_failures),
                marks = saves.failures,
                canWrite = canWrite,
                descriptionRes = R.string.death_saves_mark_failure,
                testTag = "tracker:deathsaves:failures",
                onSet = { onMark(saves.successes, it) },
            )

            // Derived, never read off the sheet — `creature.deathSave` is vestigial (decision
            // 19). Both can be true at once on a sheet another client left inconsistent, and
            // both are then printed rather than one being chosen: the block's job here is to
            // report what the marks say, and "3 and 3" is a state a player has to see to fix.
            if (saves.isStable) {
                Text(
                    text = stringResource(R.string.death_saves_stable),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("tracker:deathsaves:stable"),
                )
            }
            if (saves.isDead) {
                Text(
                    text = stringResource(R.string.death_saves_dead),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("tracker:deathsaves:dead"),
                )
            }
        }
    }
}

/**
 * One labelled row of three death-save pips.
 *
 * [onSet] receives the count this row should hold — see [DeathSaveBlock] for the toggle rule.
 * The description names the row, the count and the number the tap will produce, because a pip
 * carries no text and "button" is all a screen reader would otherwise have to work with.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeathSaveRow(
    label: String,
    marks: Int,
    canWrite: Boolean,
    @androidx.annotation.StringRes descriptionRes: Int,
    testTag: String,
    onSet: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .weight(1f)
                // The count is on the pips; naming it here too is what lets a screen-reader
                // user hear the row without stepping through three buttons to count them.
                .semantics { contentDescription = "$label, $marks / ${DeathSaves.MAX}" }
                .testTag(testTag),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(DeathSaves.MAX) { index ->
                val filled = index < marks
                val next = if (marks == index + 1) index else index + 1
                Pip(
                    filled = filled,
                    enabled = canWrite,
                    onClick = { onSet(next) },
                    contentDescription = stringResource(descriptionRes, marks, DeathSaves.MAX, next),
                    modifier = Modifier.testTag("$testTag:pip:$index"),
                )
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
 * One spell-slot level or one resource: name and count on the header line, FR-20's reset badge
 * on the secondary line when the row has a rule, and `total` pips under both.
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
    onDirectEntry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // FR-20 decision 2. The rule is spoken here, with the name, rather than left to
                // the badge below — see [PipRowState.spokenLabel] for why the count stays its
                // own node instead of the three being merged into one sentence.
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = row.spokenLabel },
            )
            Text(
                // The probe's parity anchor: one string, "value / total", per row.
                //
                // FR-22 adds a gesture to this node and deliberately changes **nothing** about
                // it otherwise. The FR-20 wave's constraint is that the probe reads `text` and
                // `resource-id` off `adb uiautomator dump`: `directEntry` writes
                // `contentDescription` (a different attribute) and adds a click action, so the
                // string and the tag the probe compares against the REST snapshot are byte-for-
                // byte what they were. The tag must stay on this modifier chain for the same
                // reason — a tag set after `clearAndSetSemantics` would be erased, which is the
                // trap `ResetBadge` documents.
                text = "${row.value} / ${row.total}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .directEntry(
                        enabled = canWrite,
                        spoken = stringResource(
                            R.string.direct_entry_spoken_of,
                            row.label,
                            row.value,
                            row.total,
                        ),
                        onOpen = onDirectEntry,
                    )
                    .testTag(testTag),
            )
        }

        // FR-20 decision 1's badge, on the row's secondary line and only when the row carries a
        // rule. It moved off the header line, where it used to sit between the name and the
        // count: there it competed with the two things the eye comes to this row for, and on a
        // narrow phone it ate the name's ellipsis budget. Under them it is what it actually is
        // — an annotation on the row, read after the row.
        row.resetLabel?.let { badge ->
            Spacer(Modifier.height(2.dp))
            ResetBadge(text = badge)
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
 * FR-20 decision 1's reset badge: *"Long rest"* / *"Short rest"* under a row's name.
 *
 * A tinted chip rather than a bare line of text, because it has to read as an annotation *on*
 * the row and not as a second row — [SectionHeader]'s colour already means "new group", so a
 * plain small line under a name is ambiguous at a glance in a dim room.
 *
 * ### Why it is silent to TalkBack
 *
 * [PipRowState.spokenLabel] already states the rule, as a verb phrase, on the row's name one
 * line above. Leaving this composable in the accessibility tree would have the row read
 * *"1st Level, restores on a long rest"* and then *"Long rest"* — the same fact twice, the
 * second time as a fragment. `clearAndSetSemantics {}` is the narrow, stated version of that:
 * it removes this node only, and the sentence that replaced it is asserted in
 * `TrackerUiStateTest`.
 *
 * It therefore carries no `testTag` either: a tag *is* a semantics property, so one set here
 * would be cleared on the next line. Nothing is lost — whether the badge exists at all is
 * [PipRowState.resetLabel]'s decision, and that is where it is pinned.
 */
@Composable
private fun ResetBadge(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.clearAndSetSemantics {},
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
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
    onDirectEntry: () -> Unit,
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
                // FR-22. No ceiling: an item has no maximum (decision 7). See [PipRow]'s note
                // for why this leaves the probe's anchor intact.
                .directEntry(
                    enabled = canWrite,
                    spoken = stringResource(R.string.direct_entry_spoken, row.name, row.quantity),
                    onOpen = onDirectEntry,
                )
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
 * The Defenses section: one dense row per kind, *"Resistant · Fire, Poison"*.
 *
 * ### Why rows and not chips
 *
 * The conditions section next door is chips because each chip is a control with its own
 * on/off state. Nothing here is a control — there is no DiceCloud method that changes a
 * damage multiplier, and no tap that would want one — so chips would advertise an
 * interaction that does not exist. A label/value row is the same visual language the HP
 * block and the section headers already use, and it puts the three kinds under each other
 * where they are compared, rather than flowing them into a paragraph.
 *
 * The kind label carries the section's only colour, for the same reason [SectionHeader]
 * does: it is what the eye lands on when scanning for one word mid-combat.
 *
 * Each row is one `contentDescription` rather than two adjacent `Text`s, so TalkBack reads
 * "Resistant: Fire, Poison" as a fact instead of announcing a stray word and then a list.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DefenseRows(rows: List<DefenseRowState>, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            val spoken = "${row.label}: ${row.text}"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = spoken }
                    .testTag("tracker:defense:${row.kind.name.lowercase()}"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = row.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The Rolls section (FR-7): pick a roll on the left, read what it costs you on the right.
 *
 * ### Why a dropdown and not thirty rows
 *
 * A real sheet expresses upwards of thirty rolls. Printing them all would be the single
 * longest thing on the tracker — longer than every resource the player actually spends — to
 * answer a question that is about *one* of them at a time. So the section is one row tall
 * whatever the character, and the list is behind the control that opens it.
 *
 * ### Why the selection is persisted and the *menu* is not
 *
 * The picked roll survives an app restart ([TrackerActions.onSelectRoll] → `SelectedRollStore`)
 * because a player asked to make the same check twice in a round should not have to hunt for
 * it twice. Whether the menu happens to be *open* is not a preference at all — it is a
 * half-finished gesture — so it is plain `remember`, not `rememberSaveable`: a rotation
 * mid-combat should leave the player looking at their modifier, not at a reopened menu they
 * have to dismiss.
 *
 * ### Read-only, and why there is no tap target on the right
 *
 * Nothing here writes to the sheet. The right-hand read-out is not a control and does not
 * pretend to be one — matching [DefenseRows], the tracker's other purely-reference block. The
 * app does not roll dice; it tells the player what to add to the ones on the table.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun RollPicker(
    state: RollPickerState,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                // The field is a *display* of the selection, never an input: `readOnly` plus
                // an ignored `onValueChange` is Material3's own recipe for an exposed dropdown
                // whose menu is the only way to change the value. An editable one would offer
                // a keyboard that could type a roll the character does not have.
                value = state.selected?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.tracker_rolls_label)) },
                placeholder = { Text(stringResource(R.string.tracker_rolls_placeholder)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .testTag("tracker:roll:dropdown"),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                state.options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        onClick = {
                            expanded = false
                            onSelect(option.id)
                        },
                        modifier = Modifier.testTag("tracker:roll:option:${option.id}"),
                    )
                }
            }
        }

        // Absent rather than blank when nothing is picked: an empty column beside the
        // placeholder would read as a roll with no modifier.
        state.selected?.let { selected ->
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier
                    .semantics(mergeDescendants = true) { contentDescription = selected.spoken }
                    .testTag("tracker:roll:display"),
            ) {
                Text(
                    text = selected.modifier,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                selected.advantageLabel?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
 * The conditions section's tail: *"N inactive ⌄"*, and the switched-off chips it opens.
 *
 * ### Why the off toggles are behind anything at all
 *
 * A real sheet's `toggle` list is mostly build plumbing — "Racial ASI Disabler", "Load
 * Wizard Spells" — and at the table the question is "what is running on me right now",
 * which fifty off chips answer badly. `ConditionToggle.shownByDefault` is the rule; this
 * is the affordance that keeps it from being a *deletion*: turning a buff **on** is a tap
 * on an off chip, so the off chips have to stay reachable, and one tap away.
 *
 * ### Why the expanded flag lives here and is not persisted
 *
 * It is a glance, not a preference: the user opens the drawer, flips Bless on — at which
 * point the chip leaves this list for the one above — and moves on. Persisting it would
 * quietly re-create the noisy list this feature exists to remove, on a device where the
 * user last happened to be poking around. `rememberSaveable` so a rotation mid-combat does
 * not slam the drawer shut; the `LazyColumn` item key scopes it, so scrolling past the
 * section and back does not either.
 */
@Composable
private fun InactiveConditions(
    chips: List<ConditionChipState>,
    canWrite: Boolean,
    onToggle: (String) -> Unit,
    shake: ShakeSignal?,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    // The one thing allowed to open this drawer other than a tap: a rollback whose row is
    // in here. Keyed on the token rather than on `expanded`, so re-collapsing it by hand
    // does not immediately re-open it — only the *next* failure may.
    LaunchedEffect(shake?.token) {
        if (shakeIsHiddenByExpander(shake, chips, expanded)) expanded = true
    }

    // "3 inactive" is the label a sighted user reads off a chevron; a screen reader gets
    // the whole sentence, because "3 inactive" alone does not say it can be opened.
    val action = if (expanded) {
        stringResource(R.string.tracker_conditions_hide_inactive)
    } else {
        stringResource(R.string.tracker_conditions_show_inactive, chips.size)
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { expanded = !expanded }
                .semantics { contentDescription = action }
                .padding(vertical = 4.dp)
                .testTag("tracker:conditions:inactive"),
        ) {
            Text(
                text = stringResource(R.string.tracker_conditions_inactive, chips.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (expanded) {
            ConditionChips(
                chips = chips,
                canWrite = canWrite,
                onToggle = onToggle,
                shake = shake,
            )
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
internal fun StepperButton(
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
internal const val MINUS = "\u2212"
internal const val PLUS = "+"

private const val PIP_TARGET_DP = 48
private const val PIP_DOT_DP = 28
private const val CHIPS_PER_ROW = 2

/** The connection dot: a 10 dp mark on a 24 dp disc, inset from the screen corner. */
private const val DOT_DP = 10
private const val DOT_DISC_DP = 24
private const val DOT_MARGIN_DP = 8

/**
 * Material's own disabled-content alpha; used where a control is drawn by hand.
 *
 * Deliberately **not** BUG-3's raised icon alpha. These are pips and typed glyphs rather than
 * icons, and each sits beside a live sibling that makes the disabled one legible by
 * comparison — see [DisabledContent.ICON_ALPHA] for the full argument. Named through the
 * shared object so the two figures are stated in one file rather than drifting in two.
 */
private const val DISABLED_ALPHA = DisabledContent.MATERIAL_ALPHA

/** How far a rolled-back row travels, in layout pixels, and for how long. */
private const val SHAKE_DP = 18f
private const val SHAKE_MILLIS = 260

/** Hold-to-repeat: a beat before it starts, then 300 ms steps accelerating to 60 ms. */
private const val HOLD_DELAY_MILLIS = 350L
private const val HOLD_START_INTERVAL_MILLIS = 300L
private const val HOLD_MIN_INTERVAL_MILLIS = 60L
private const val HOLD_ACCELERATION = 0.82
