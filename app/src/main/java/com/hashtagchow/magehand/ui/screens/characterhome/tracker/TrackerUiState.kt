package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.annotation.StringRes
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.ConditionToggle
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.DamageDefense
import com.hashtagchow.magehand.core.model.DefenseKind
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.core.model.RollAdvantage
import com.hashtagchow.magehand.core.model.RollModifier
import com.hashtagchow.magehand.core.model.TrackedResource
import com.hashtagchow.magehand.core.model.TrackerBoard
import com.hashtagchow.magehand.core.model.TrackerKind
import com.hashtagchow.magehand.core.model.TrackerWrite
import com.hashtagchow.magehand.core.model.TrackerWriteFailure
import com.hashtagchow.magehand.core.model.TrackerWriteKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The tracker tab's rendered state (docs/design/04-screens-ux.md §3).
 *
 * Everything here is derived from a [TrackerBoard] plus four session signals by
 * [toTrackerUiState], which is a pure function on purpose: the board → UI mapping and the
 * connection derivation are the two things most likely to go quietly wrong, and both are
 * unit-tested in `TrackerUiStateTest` without a device, a Compose runtime or a clock.
 */

/** The connection, as the tracker talks about it. 04 §3 names three; 06 adds the fourth. */
enum class ConnectionTone {
    /** DDP connected, logged in **and** the `singleCharacter` sub ready. */
    LIVE,

    /** Socket down and retrying — 04 calls this "Reconnecting". */
    RECONNECTING,

    /** No network, or retries exhausted-for-now. Read the Room snapshot. */
    OFFLINE,

    /** The token was rejected; re-login is the only way out. */
    SIGNED_OUT,
}

/**
 * The connection, and **how the tracker is allowed to mention it**.
 *
 * ### Why this is no longer a strip
 *
 * 04 §3 specified a permanent "connection state chip (Live / Reconnecting / Offline —
 * cached HH:MM)" across the top of the tab. In practice it was a band of chrome that said
 * "Live" for ~100% of a session at the table — it spent its whole life reporting the
 * absence of a problem, while costing a row of vertical space on the one screen where
 * vertical space is pips. The rule now is *quiet when healthy*: nothing renders at all
 * while [isLive], and everything the strip used to say moves into a details sheet behind
 * a dot that only exists when there is something to say. This class is where that rule
 * lives — not in the composable — so `TrackerUiStateTest` can pin it without a device.
 *
 * The read-only consequence of being offline is unchanged; only its presentation moved.
 * It is still stated in the list itself (`ReadOnlyNote`, driven by
 * [TrackerUiState.canWrite]) *and* restated in the sheet — see [warnsWritesDisabled].
 *
 * @param syncedAt `"HH:MM"` of the cached snapshot, or `null` when nothing has ever
 *   synced for this character. Formatted here rather than in the composable so the
 *   time zone is an argument and the result is assertable.
 * @param showingSnapshot true when the board being rendered came from Room, not the
 *   live mirror. Independent of [tone]: a screen can be `RECONNECTING` and still be
 *   showing a snapshot, and that is exactly when the user most needs to be told.
 */
data class ConnectionStatus(
    val tone: ConnectionTone = ConnectionTone.RECONNECTING,
    val syncedAt: String? = null,
    val showingSnapshot: Boolean = false,
) {
    val isLive: Boolean get() = tone == ConnectionTone.LIVE

    /**
     * Whether the connection is in a state worth *mentioning*. Necessary for the dot but
     * not sufficient — the board also has to be on screen for "not live" to mean anything
     * to the user. [TrackerUiState.showConnectionIndicator] is the whole rule.
     */
    val isWorthMentioning: Boolean get() = !isLive

    /**
     * Whether this state is one that **waiting cannot fix** — the token was rejected, or
     * there is no network and the retries have stopped for now. The user has to do
     * something (sign in, turn the network on) or nothing will change.
     *
     * The opposite — `RECONNECTING` — is genuinely transient: a redial is already in
     * flight and the most likely next event is that it succeeds.
     *
     * This is not [canRetry] restated. `canRetry` asks "would pressing the button do
     * anything?", and `OFFLINE` answers yes to that *and* yes to this: a restart is worth
     * offering, but nothing happens on its own while the network is down.
     * [TrackerUiState.showConnectionIndicator] is the only consumer.
     */
    val isTerminalUntilActedOn: Boolean
        get() = tone == ConnectionTone.OFFLINE || tone == ConnectionTone.SIGNED_OUT

    /** Two or three words for the sheet's heading — never a sentence. */
    @get:StringRes
    val stateLabelRes: Int
        get() = when (tone) {
            ConnectionTone.LIVE -> R.string.connection_state_live
            ConnectionTone.RECONNECTING -> R.string.connection_state_reconnecting
            ConnectionTone.OFFLINE -> R.string.connection_state_offline
            ConnectionTone.SIGNED_OUT -> R.string.connection_state_signed_out
        }

    /** One sentence saying what is actually wrong and whether waiting will fix it. */
    @get:StringRes
    val explanationRes: Int
        get() = when (tone) {
            ConnectionTone.LIVE -> R.string.connection_details_live
            ConnectionTone.RECONNECTING -> R.string.connection_details_reconnecting
            ConnectionTone.OFFLINE -> R.string.connection_details_offline
            ConnectionTone.SIGNED_OUT -> R.string.connection_details_signed_out
        }

    /**
     * The dot's `contentDescription`. A bare "red dot" would be useless to TalkBack, so
     * each state names itself *and* says the dot is tappable — it is the only route to
     * the details, and nothing else on screen mentions the connection any more.
     */
    @get:StringRes
    val indicatorDescriptionRes: Int
        get() = when (tone) {
            ConnectionTone.LIVE -> R.string.connection_indicator_live
            ConnectionTone.RECONNECTING -> R.string.connection_indicator_reconnecting
            ConnectionTone.OFFLINE -> R.string.connection_indicator_offline
            ConnectionTone.SIGNED_OUT -> R.string.connection_indicator_signed_out
        }

    /**
     * Whether the sheet offers "Try reconnecting" — i.e. whether
     * `DdpConnectionManager.restart()` could plausibly help.
     *
     * `SIGNED_OUT` is excluded on purpose: the resume token was *rejected*, so a restart
     * would redial, re-present the same dead token and land back here. Offering a button
     * that cannot work is worse than offering none, so that state gets the "sign in
     * again" sentence instead. `LIVE` is excluded because there is nothing to fix.
     */
    val canRetry: Boolean
        get() = tone == ConnectionTone.RECONNECTING || tone == ConnectionTone.OFFLINE

    /**
     * Whether the sheet repeats "writes are unavailable". Same condition as the queue's
     * own LIVE-only rule, so the two cannot drift into disagreeing.
     */
    val warnsWritesDisabled: Boolean get() = !isLive
}

/** 04 §3's HP block. The steppers and the number pad render disabled in WP6. */
data class HpState(
    val propertyId: String,
    val current: Int,
    val max: Int,
    val tempHp: Int,
) {
    val hasTempHp: Boolean get() = tempHp > 0

    /** Clamped, so a server value briefly out of range cannot draw a bar past its track. */
    val fraction: Float get() = if (max <= 0) 0f else (current.toFloat() / max).coerceIn(0f, 1f)
}

/** One pip row: a spell-slot level, or a resource. */
data class PipRowState(
    val propertyId: String,
    val label: String,
    /**
     * The server's reset rule, kept as the enum rather than as pre-rendered text: the rest
     * confirm dialog has to *filter* on it ("what does a short rest put back?"), and a
     * dialog that matched on the string "Short rest" would be one rename from resetting
     * the wrong rows. [resetLabel] is the display form.
     */
    val reset: ResetRule?,
    val value: Int,
    val total: Int,
    val pinned: Boolean,
    val kind: TrackerKind,
) {
    val spent: Int get() = (total - value).coerceAtLeast(0)

    /** "Long rest" / "Short rest", or `null` when the server gives no reset rule. */
    val resetLabel: String? get() = reset?.label()

    /**
     * Pips stop being readable — and stop fitting a 48 dp target across a phone — long
     * before the count gets silly. Above the threshold the row renders `value / total`
     * with a bar instead, which is what DiceCloud's own UI does for large resources.
     */
    val usePips: Boolean get() = total in 1..MAX_PIPS

    companion object {
        /** Eight 48 dp targets plus gaps still fit a 360 dp-wide phone. */
        const val MAX_PIPS = 8
    }
}

/** A pinned inventory item with its quantity (04 §3, "Consumables"). */
data class ConsumableState(
    val propertyId: String,
    val name: String,
    val quantity: Int,
)

/**
 * A discovered `toggle` (04 §3, "Condition chips").
 *
 * @param canFlip whether the server will accept `flipToggle` on it. A **computed** toggle
 *   — one whose state comes from its `condition` calculation — is still worth rendering
 *   ("0 HP?" being on is information), but it is not a control, and tapping it would earn
 *   a guaranteed `Computed toggle` error. See [ConditionToggle.flippable].
 */
data class ConditionChipState(
    val propertyId: String,
    val name: String,
    val enabled: Boolean,
    val canFlip: Boolean = false,
)

/**
 * One line of the read-only Defenses section — *"Resistant · Fire, Poison"*.
 *
 * One row per [DefenseKind], **not** one per discovered property: a character with three
 * separate features granting fire resistance has one fact to read at the table, not three
 * lines saying the same thing. The merge and the de-duplication happen in [toDefenseRows].
 *
 * There is no `propertyId` here for the same reason there is no callback: nothing about
 * this row is addressable. It is the only part of the tracker that is purely reference.
 */
data class DefenseRowState(
    val kind: DefenseKind,
    /** Display-cased damage types, de-duplicated and alphabetical. Never empty. */
    val types: List<String>,
) {
    /** `"Immune"` / `"Resistant"` / `"Vulnerable"`. */
    val label: String get() = kind.label()

    /** `"Fire, Poison"` — the whole right-hand side of the row. */
    val text: String get() = types.joinToString(", ")
}

/**
 * One entry in the Rolls dropdown. Name and identity, and deliberately nothing else — the
 * numbers belong to [RollDisplayState], which is only built for the *selected* one.
 */
data class RollOptionState(val id: String, val name: String)

/**
 * The read-out beside the dropdown: what the player adds, and whether they roll twice.
 *
 * @param modifier already signed — see [formatSignedModifier]. `"+0"`, never `"0"`.
 * @param advantageLabel `"Advantage"` / `"Disadvantage"`, or `null` when the sheet expresses
 *   neither. Null rather than a third word ("Normal") on purpose: a line that reads *"+5 ·
 *   Normal"* on every roll of every character trains the eye to skip the very field that
 *   matters on the one roll where it says something else.
 */
data class RollDisplayState(
    val name: String,
    val modifier: String,
    val advantageLabel: String?,
) {
    /** The whole row as one sentence, for TalkBack — see the section's composable. */
    val spoken: String get() = listOfNotNull(name, modifier, advantageLabel).joinToString(", ")
}

/**
 * The Rolls section (FR-7): a dropdown of every roll the character has, and a read-out for the
 * one currently picked.
 *
 * ### Why the selection is resolved here rather than stored resolved
 *
 * The remembered selection is an **id**, persisted across restarts, and the board it points
 * into is rebuilt on every sync. So the id can outlive the roll: a feature is switched off, a
 * sheet is re-authored, a character is edited. [selected] being `null` is the honest answer to
 * that — the section renders its placeholder, exactly as it does before the player has ever
 * picked — and the stored id is deliberately *not* cleared: the property may well be back
 * after the next sync (a toggled-off feature is one tap from returning), and silently
 * forgetting the player's choice because a buff lapsed is worse than a placeholder.
 */
data class RollPickerState(
    val options: List<RollOptionState> = emptyList(),
    val selected: RollDisplayState? = null,
) {
    /**
     * Whether the section renders at all. Empty means *absent*, header and all — the same rule
     * the defenses section uses, and the reason a character whose data expresses no rolls
     * does not get an empty dropdown to open.
     */
    val isPresent: Boolean get() = options.isNotEmpty()
}

/**
 * One row of the undo-history sheet (04 §3), already turned into display text.
 *
 * @param canUndo true on the **single** newest reversible entry. Undo is a stack, not a
 *   basket: reversing an older spend while a newer `set` sits on top of it would apply the
 *   inverse to a value the user has since replaced. So the sheet lists everything and
 *   offers UNDO on the top of the stack only.
 */
data class HistoryRowState(
    val id: Long,
    val label: String,
    val at: String,
    val canUndo: Boolean,
    val undone: Boolean,
)

data class TrackerUiState(
    val creatureId: String = "",
    val status: ConnectionStatus = ConnectionStatus(),
    /** 04 §3: `Concentrating: Bless ✕`. */
    val concentratingOn: String? = null,
    val hp: HpState? = null,
    /**
     * The Defenses section, or empty when the character has none — in which case the
     * section is *absent*, not empty. Sits between HP and the spell slots: it is combat
     * reference, so it belongs next to the other combat reference, and it is read-only, so
     * it costs nothing to scroll past.
     */
    val defenses: List<DefenseRowState> = emptyList(),
    /**
     * FR-7's Rolls section, or [RollPickerState.isPresent] `false` when the character has no
     * rolls. Sits after the defenses and before the spell slots: everything above it is
     * glance-reference that answers a question mid-turn, everything below it is a resource the
     * player spends, and this belongs with the first group.
     */
    val rolls: RollPickerState = RollPickerState(),
    val slots: List<PipRowState> = emptyList(),
    val resources: List<PipRowState> = emptyList(),
    val consumables: List<ConsumableState> = emptyList(),
    val conditions: List<ConditionChipState> = emptyList(),
    /**
     * The switched-off toggles the conditions section files behind its "N inactive"
     * expander ([ConditionToggle.shownByDefault]).
     *
     * A second list rather than a flag on [ConditionChipState] because the screen renders
     * them as a separate, collapsed group — and because "how many are hidden right now" is
     * the expander's own label, which a filter at the call site would have to recompute.
     */
    val inactiveConditions: List<ConditionChipState> = emptyList(),
    /** `"#RRGGBB"` from `theme_prefs`, or `null` for the app default (04 §6). */
    val accentColor: String? = null,
    /**
     * Whether taps may reach the server. Every write control is dimmed and inert when this
     * is false — 06's rule that writes require `LIVE`, made visible instead of surprising.
     */
    val canWrite: Boolean = false,
    val canUndo: Boolean = false,
    val history: List<HistoryRowState> = emptyList(),
    /**
     * The concentration source's toggle id, when the banner's source happens to be one of
     * the discovered flippable toggles. `null` disables the ✕ — see [toTrackerUiState].
     *
     * Resolved from the **board**, not from [conditions]. 09 decision 9 says FR-6's switch
     * leaves the concentration banner alone ("property-driven and unaffected"), and the ✕
     * honours that: it issues `flipToggle(propertyId)`, a write against a property, which does
     * not care whether a chip for that property is currently being drawn. Resolving it from
     * the filtered chip list made "hide the conditions section" silently also mean "you can no
     * longer drop concentration" — a banner still saying *Concentrating: Web* with a dead ✕
     * beside it, and no way to stop concentrating short of the Settings screen.
     *
     * One thing genuinely is lost with the section hidden, and it is the smaller half: a
     * failed flip's rollback **shake** has no chip to play on, because none is composed. The
     * snackbar still says the write did not save, which is the half carrying the meaning —
     * `shakeIsHiddenByExpander` documents the same trade for the collapsed drawer, where it
     * could be bought back by auto-expanding. Here it cannot: there is no section to expand.
     */
    val concentrationToggleId: String? = null,
    /**
     * Whether this character *has* a connection worth reporting at all
     * (docs/design/09-local-characters.md decision 8: "no connection dot for local
     * characters" — "connection status is meaningless locally and must not render").
     *
     * ### Why this is a field and not "the tone happens to be LIVE"
     *
     * `LocalOpenCharacter.connectionState` is a constant `LIVE`, so `showConnectionIndicator`
     * would already be false today by arithmetic. That is exactly the kind of guarantee that
     * evaporates: it holds only while nothing else can move the tone, and the whole point of
     * `ConnectionTone` is that things move it. A future "local save failed" state, or a
     * refactor that seeds the flow from a shared default, would put a red dot on a character
     * that has nothing to be disconnected from — and no test would notice, because no test
     * would be asserting about the dot.
     *
     * So the suppression is stated rather than derived, one boolean the local view model sets
     * and `TrackerUiStateTest` pins in both directions. Belt and braces is the correct posture
     * for a rule whose failure mode is invisible.
     */
    val hasConnection: Boolean = true,
) {
    /** Nothing discovered yet — a cold open with no snapshot and no live sub. */
    val isEmpty: Boolean
        get() = hp == null && slots.isEmpty() && resources.isEmpty() &&
            consumables.isEmpty() && conditions.isEmpty() && inactiveConditions.isEmpty() &&
            defenses.isEmpty() && !rolls.isPresent

    /**
     * Distinguishes "this character genuinely has no tracker rows" from "we have not
     * loaded anything yet", which is what stops an empty-state message flashing on every
     * open (the same rule screen 2 uses).
     */
    val isLoading: Boolean
        get() = isEmpty && status.tone != ConnectionTone.LIVE && !status.showingSnapshot

    /**
     * Whether the tracker floats its bottom-right connection dot.
     *
     * Two conditions, and the second one is the interesting half:
     *
     *  1. **The connection is worth mentioning** ([ConnectionStatus.isWorthMentioning]) —
     *     anything but `LIVE`. Quiet when healthy: a working session shows nothing.
     *  2. **The board is on screen, _or_ waiting will not fix this.** The dot's meaning is
     *     *"what you are looking at is not live"*, and during a cold open the user is not
     *     looking at sheet data at all — they are looking at a spinner that already says
     *     what it is doing. Since `isLoading` implies non-`LIVE` by construction,
     *     condition 1 alone would put a red mark on the screen for the first moments of
     *     **every** cold open, which is the fastest way to teach someone that the red mark
     *     means nothing.
     *
     * But `!isLoading` alone was too blunt, and the difference is
     * [ConnectionStatus.isTerminalUntilActedOn]. Suppressing the dot while loading is
     * right for `RECONNECTING`, because a redial is in flight and the spinner is telling
     * the truth: waiting may well finish the job. It is wrong for `OFFLINE` and
     * `SIGNED_OUT`, where nothing is coming. A character with no cached snapshot and a
     * rejected token is `isLoading` forever — and with the old rule it showed an
     * indefinite spinner, no dot, no route to the sheet, and therefore no way to find out
     * that the app wanted the user to sign in again. A dot that is the only exit is worth
     * more than the tidiness of a bare spinner.
     *
     * It lives here rather than on [ConnectionStatus] because it needs both halves, and
     * only this class knows whether there are rows yet.
     *
     * [hasConnection] gates the whole rule rather than joining it: a character with no
     * connection has no connection *state*, so neither half of the question applies.
     */
    val showConnectionIndicator: Boolean
        get() = hasConnection &&
            status.isWorthMentioning &&
            (!isLoading || status.isTerminalUntilActedOn)
}

/**
 * Board → UI, and the status-strip derivation, in one pure step.
 *
 * @param zone injected so the formatted `HH:MM` is deterministic in tests. Production
 *   passes the device zone.
 * @param showToggles FR-6's `show_toggles` (docs/design/09-local-characters.md decision 9).
 *   **False empties both condition lists**, which is what makes the section, its header and
 *   its inactive drawer *absent* from the tracker rather than merely collapsed: the screen
 *   already renders the whole group only `if (conditions.isNotEmpty() || inactive…)`, so the
 *   gate reuses the rule the screen has always had for a character with no toggles. Doing it
 *   here rather than in the composable is what lets `TrackerUiStateTest` pin FR-6 in both
 *   directions without a device — and what stops a second screen from re-deriving it.
 *
 *   It **does not** reach [TrackerUiState.concentrationToggleId], which is resolved from the
 *   board rather than from these lists — 09 decision 9: *"the concentration banner is
 *   property-driven and unaffected"*.
 * @param hasConnection false for a character with no server (09 decision 8). See
 *   [TrackerUiState.hasConnection].
 * @param selectedRollId FR-7's remembered dropdown selection, as a raw id. Resolved against
 *   `board.rolls` here — see [toRollPicker] — which is what makes a stale id a placeholder
 *   rather than a crash or a silently wrong number.
 */
fun toTrackerUiState(
    creatureId: String,
    board: TrackerBoard,
    connection: ConnectionState,
    lastSyncedAt: Long?,
    isShowingSnapshot: Boolean,
    accentColor: String? = null,
    canWrite: Boolean = false,
    canUndo: Boolean = false,
    history: List<TrackerWrite> = emptyList(),
    zone: ZoneId = ZoneId.systemDefault(),
    showToggles: Boolean = true,
    hasConnection: Boolean = true,
    selectedRollId: String? = null,
): TrackerUiState {
    // The one place the board's toggles are split, using the rule itself rather than a
    // re-statement of it — `partition` keeps both halves in the board's order.
    val toggles = if (showToggles) board.activeToggles else emptyList()
    val (shown, inactive) = toggles.partition { it.shownByDefault }
    val conditions = shown.map { it.toChip() }
    // Only the newest still-reversible entry gets an UNDO button; see [HistoryRowState].
    val topOfStack = history.firstOrNull { it.undoable }?.id
    return TrackerUiState(
        creatureId = creatureId,
        status = ConnectionStatus(
            tone = connection.toTone(),
            syncedAt = formatSyncedAt(lastSyncedAt, zone),
            showingSnapshot = isShowingSnapshot,
        ),
        concentratingOn = board.concentratingOn,
        hp = board.hp?.toHpState(tempHp = board.tempHp?.value ?: 0),
        defenses = toDefenseRows(board.defenses),
        rolls = toRollPicker(board.rolls, selectedRollId),
        slots = board.slots.map { it.toPipRow() },
        resources = board.resources.map { it.toPipRow() },
        consumables = board.pinnedItems.map { it.toConsumable() },
        conditions = conditions,
        inactiveConditions = inactive.map { it.toChip() },
        accentColor = accentColor,
        canWrite = canWrite,
        canUndo = canUndo && canWrite,
        history = history.map { it.toHistoryRow(canUndo = canWrite && it.id == topOfStack, zone = zone) },
        // Resolved from `board.activeToggles`, deliberately **not** from `conditions` — the
        // banner is property-driven (09 decision 9), so what the chip row happens to be
        // drawing must not arm or disarm its ✕. See [TrackerUiState.concentrationToggleId].
        //
        // The narrowing that *is* real is WP7's and it is unchanged: 03 §5 lets the banner
        // come from a `buff` as well as a `toggle`, and `flipToggle` rejects anything that is
        // not a toggle — so the ✕ is live only when the source *is* a flippable, switched-on
        // toggle. That is what these three clauses say, against the board.
        concentrationToggleId = board.concentratingOn?.let { name ->
            board.activeToggles
                .firstOrNull { it.name == name && it.enabled && it.flippable }
                ?.propertyId
        },
        hasConnection = hasConnection,
    )
}

/**
 * Discovered defenses → the section's lines.
 *
 * Three decisions, all of which exist to make the section a *glance* rather than a list:
 *
 *  1. **Merged by kind.** Several properties can grant the same protection; the player
 *     needs the union, once. The board's order decides which kinds appear first
 *     (immunities → resistances → vulnerabilities, see `DefenseKind`), so this walks the
 *     list in order rather than iterating the enum.
 *  2. **De-duplicated case-insensitively**, because the wire strings are whatever the
 *     sheet's author typed: `"Fire"` from one feature and `"fire"` from another are one
 *     resistance, and printing both would look like a bug in the app rather than a
 *     quirk of the sheet.
 *  3. **Alphabetical within a line.** The server's `order` is the order features were
 *     added to the character, which is meaningless to a reader scanning for one word.
 *
 * Display casing is a first-letter capitalization and nothing cleverer: the wire values
 * are lowercase single words (`"radiant"`, `"necrotic"` on the live capture), and a
 * homebrew type that arrives already capitalized survives unchanged.
 */
fun toDefenseRows(defenses: List<DamageDefense>): List<DefenseRowState> = defenses
    .groupBy { it.kind }
    .map { (kind, group) ->
        DefenseRowState(
            kind = kind,
            types = group
                .flatMap { it.damageTypes }
                .distinctBy { it.lowercase() }
                .map { it.replaceFirstChar(Char::uppercase) }
                .sorted(),
        )
    }

/**
 * Discovered rolls + the remembered id → the section's state.
 *
 * The board's order is kept verbatim: `TrackerEngine` already put the rolls in the sheet's own
 * order (and `LocalTrackerBoard` in sheet order for the six checks), so re-sorting here would
 * be a second opinion about something already decided — and the wrong layer to have it in.
 *
 * A `null` or unmatched [selectedId] yields a `null` [RollPickerState.selected]; see that
 * class for why that is the same state as "not picked yet" and why nothing is cleared.
 */
fun toRollPicker(rolls: List<RollModifier>, selectedId: String?): RollPickerState =
    RollPickerState(
        options = rolls.map { RollOptionState(id = it.id, name = it.name) },
        selected = rolls.firstOrNull { it.id == selectedId }?.let { roll ->
            RollDisplayState(
                name = roll.name,
                modifier = formatSignedModifier(roll.modifier),
                advantageLabel = roll.advantage.label(),
            )
        },
    )

/**
 * `3` → `"+3"`, `-2` → `"−2"`, `0` → `"+0"`.
 *
 * **The one copy of this rule.** A signed modifier is always signed on a character sheet —
 * "+0" is how 5e prints a score of 10, and a bare "0" reads as a missing value rather than a
 * real one — and the negative uses U+2212 MINUS SIGN rather than a hyphen, which at these type
 * sizes is the difference between a minus and a stray mark.
 *
 * It lives in the tracker's vocabulary because both screens that print one are tracker screens:
 * this section, and the local reference strip
 * ([com.hashtagchow.magehand.ui.screens.local.LocalReferenceState], which delegates here). The
 * two showing the same character's Dexterity as `+1` and `1` would be visible in one glance,
 * because on a local character they are eight lines apart.
 */
fun formatSignedModifier(value: Int): String = if (value < 0) "−${-value}" else "+$value"

/**
 * Display text for advantage, or `null` for [RollAdvantage.NONE].
 *
 * Same "not a string resource" reasoning as [ResetRule.label]. `null` is the deliberate half —
 * see [RollDisplayState.advantageLabel].
 */
fun RollAdvantage.label(): String? = when (this) {
    RollAdvantage.NONE -> null
    RollAdvantage.ADVANTAGE -> "Advantage"
    RollAdvantage.DISADVANTAGE -> "Disadvantage"
}

/**
 * Display text for a defense kind. Same "not a string resource" reasoning as
 * [ResetRule.label] — asserted in a JVM unit test, and v1 is English-only.
 */
fun DefenseKind.label(): String = when (this) {
    DefenseKind.IMMUNE -> "Immune"
    DefenseKind.RESISTANT -> "Resistant"
    DefenseKind.VULNERABLE -> "Vulnerable"
}

/**
 * 06's `ConnectionState` is the single source of truth; this is only the rename 04 asks
 * for on screen ("Reconnecting" reads better than "Connecting" once a sheet is already
 * on screen, and `AUTH_FAILED` is not a connection problem the user can wait out).
 */
fun ConnectionState.toTone(): ConnectionTone = when (this) {
    ConnectionState.LIVE -> ConnectionTone.LIVE
    ConnectionState.CONNECTING -> ConnectionTone.RECONNECTING
    ConnectionState.OFFLINE -> ConnectionTone.OFFLINE
    ConnectionState.AUTH_FAILED -> ConnectionTone.SIGNED_OUT
}

/** The inverse of [toTone]; lossless, because the rename is 1:1. */
fun ConnectionTone.toConnectionState(): ConnectionState = when (this) {
    ConnectionTone.LIVE -> ConnectionState.LIVE
    ConnectionTone.RECONNECTING -> ConnectionState.CONNECTING
    ConnectionTone.OFFLINE -> ConnectionState.OFFLINE
    ConnectionTone.SIGNED_OUT -> ConnectionState.AUTH_FAILED
}

/** `1755463920000` → `"14:52"`. `null` in, `null` out — never a fake "00:00". */
fun formatSyncedAt(epochMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): String? =
    epochMillis?.takeIf { it > 0L }
        ?.let { HOUR_MINUTE.format(Instant.ofEpochMilli(it).atZone(zone)) }

private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun TrackedResource.toHpState(tempHp: Int) = HpState(
    propertyId = propertyId,
    current = value,
    max = total,
    tempHp = tempHp,
)

private fun TrackedResource.toPipRow() = PipRowState(
    propertyId = propertyId,
    label = name,
    reset = reset,
    value = value.coerceAtLeast(0),
    total = total.coerceAtLeast(0),
    pinned = pinned,
    kind = kind,
)

private fun TrackedResource.toConsumable() = ConsumableState(
    propertyId = propertyId,
    name = name,
    quantity = value,
)

private fun ConditionToggle.toChip() = ConditionChipState(
    propertyId = propertyId,
    name = name,
    enabled = enabled,
    canFlip = flippable,
)

/**
 * Display text for a reset rule. Deliberately not a string resource: it is asserted in a
 * JVM unit test, and the tracker is English-only in v1 (docs/design/00-DESIGN.md).
 */
fun ResetRule.label(): String = when (this) {
    ResetRule.SHORT_REST -> "Short rest"
    ResetRule.LONG_REST -> "Long rest"
}

private fun TrackerWrite.toHistoryRow(canUndo: Boolean, zone: ZoneId) = HistoryRowState(
    id = id,
    label = describe(),
    at = formatSyncedAt(at, zone).orEmpty(),
    canUndo = canUndo,
    undone = undone,
)

/**
 * What one dispatched write reads as in the snackbar and the history sheet.
 *
 * Same "not a string resource" reasoning as [label] — these are asserted in a JVM unit
 * test with no Android context, and v1 is English-only. [TrackerWrite.amount] is the
 * *coalesced* amount, so three quick taps read as "Spent 3 × 1st Level", which is exactly
 * what one server call did.
 */
fun TrackerWrite.describe(): String = when (kind) {
    TrackerWriteKind.SPEND -> "Spent $amount × $targetName"
    TrackerWriteKind.RESTORE -> "Restored $amount × $targetName"
    TrackerWriteKind.TAKE_DAMAGE -> "Took $amount damage"
    TrackerWriteKind.HEAL -> "Healed $amount"
    TrackerWriteKind.SET_VALUE -> "Set $targetName"
    TrackerWriteKind.ITEM_USE -> "Used $amount × $targetName"
    TrackerWriteKind.ITEM_ADD -> "Added $amount × $targetName"
    TrackerWriteKind.ITEM_SET -> "Set the number of $targetName"
    // FR-8. `amount` is 1 for an equip and says nothing worth printing — the interesting
    // fact is which way the item went, which the verb already carries.
    TrackerWriteKind.EQUIP -> "Equipped $targetName"
    TrackerWriteKind.UNEQUIP -> "Unequipped $targetName"
    // Not "Added", which is [ITEM_ADD]'s sentence: picking up two more arrows and putting a
    // quiver on the sheet for the first time are different events, and the history sheet is
    // read to answer "what did I do?". Only one of the two can be undone, too.
    TrackerWriteKind.ITEM_CREATE -> "New item: $amount × $targetName"
    // FR-9. "Deleted" and not "Removed": the sheet's own vocabulary for a soft-removed
    // property is `removed`, and reusing that word here would invite the reading that the item
    // is somewhere the player could go and look at. It is off the sheet, and the only route
    // back is the UNDO this entry offers (on a DiceCloud character — see
    // `OpenCharacter.removeItem` for why a local delete files the same sentence with no
    // button). `amount` is 1 and says nothing: you delete the item, not some of it.
    TrackerWriteKind.ITEM_DELETE -> "Deleted $targetName"
    TrackerWriteKind.ITEM_RESTORE -> "Restored $targetName"
    // The destination is deliberately absent. It is resolved inside `:core:data` against the
    // live sheet — this layer never learns the folder's name — and "Moved Belt Pouch" is the
    // fact a player is scanning the history for anyway. The undo puts it back regardless.
    TrackerWriteKind.ITEM_MOVE -> "Moved $targetName"
    TrackerWriteKind.TOGGLE -> "Toggled $targetName"
    TrackerWriteKind.SHORT_REST -> "Short rest"
    TrackerWriteKind.LONG_REST -> "Long rest"
}

/**
 * What a rolled-back write reads as. The server's own `reason` is preferred where it gave
 * one — a DiceCloud validation message tells the user more than "couldn't save" — with the
 * two cases that have their own copy handled first.
 */
fun TrackerWriteFailure.describe(): String = when {
    refusedOffline -> "Not saved — you're offline"
    rateLimited -> "Too fast — that one didn't save"
    reason != null -> "Not saved: $reason"
    else -> "That didn't save"
}
