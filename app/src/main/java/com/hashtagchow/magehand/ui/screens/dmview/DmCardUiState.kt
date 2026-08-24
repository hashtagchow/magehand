package com.hashtagchow.magehand.ui.screens.dmview

import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.ui.screens.characterhome.inventory.formatAmount
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConditionChipState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionTone
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PipRowState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerUiState

/**
 * What one condensed card on the DM dashboard is doing (docs/design/14-large-screen-arc.md
 * decision 19).
 *
 * ### Why "not available" is a state and not an empty card
 *
 * The live probe found a quirk with no client-side workaround: *"an unviewable creature's sub
 * returns ready+empty, indistinguishable from an empty creature"*. So a card whose subscription
 * has gone ready and delivered nothing is in one of two situations — the share was revoked, or
 * the sheet genuinely has no tracker rows — and the app cannot tell which. What it must not do
 * is draw a tracker card with an HP bar reading nothing, because at a table that reads as *"this
 * character is fine"* rather than as *"this app has no idea"*.
 */
enum class DmCardAvailability {
    /** Nothing has arrived yet, and waiting is still the right thing to do. */
    LOADING,

    /** The subscription readied and delivered rows. The card draws them. */
    AVAILABLE,

    /**
     * Decision 19: the subscription readied with **zero** documents.
     *
     * The card says so in as many words and draws no tracker at all — never an HP bar, never an
     * empty pip row, and never a write control, whatever the toggle says.
     */
    NOT_AVAILABLE,
}

/**
 * The inventory half of decision 12's card: *"an inventory summary line (item count · total
 * weight) per card"*.
 *
 * A summary and not a list, and that is decision 12's own fence — the "Out of scope" section says
 * *"DM-side inventory writes (cards summarize only — editing inventory means opening the
 * character)"*. What a DM needs from six inventories at a glance is whether somebody is about to
 * be encumbered; what they need to actually *change* one is the character screen, which is one
 * tap away.
 *
 * @param itemCount every item on the board counted once, containers' contents included. The
 *   wallet is deliberately excluded: coins are items on this server, but "37 items" jumping to
 *   "41 items" because a purse holds four denominations is a number that means nothing.
 * @param weight already formatted by [formatAmount] — the one copy of that rule, shared with the
 *   inventory tab so the same character's carried weight cannot read `142` on one screen and
 *   `142.0` on another eight inches away.
 * @param overCapacity `InventoryBoard.isOverCapacity`. Carried on the summary because it is the
 *   one inventory fact worth a colour at a glance, and because a card has no room to print
 *   `142 / 135 lb` and be read.
 */
data class DmInventorySummary(
    val itemCount: Int,
    val weight: String,
    val overCapacity: Boolean,
)

/**
 * `InventoryBoard` → the card's one line.
 *
 * Pure, and separate from [toDmCardUiState], because the inventory arrives on its own flow and
 * folding the mapping into the card builder would mean every HP tick re-derived a weight sum.
 */
fun toDmInventorySummary(board: InventoryBoard): DmInventorySummary = DmInventorySummary(
    itemCount = board.allItems.size,
    weight = formatAmount(board.carriedWeightLb),
    overCapacity = board.isOverCapacity,
)

/**
 * One card on the dashboard (decision 12), and the decision-14/18 write posture that goes with
 * it.
 *
 * ### Why the card renders from [TrackerUiState] rather than from a `TrackerBoard`
 *
 * Decision 12: *"the tracker discovery engine reused per character, rendering a **card**
 * composable, not the full screen"*. The discovery engine's output is a `TrackerBoard`, but every
 * rule about how a board becomes something a human reads — the pip/bar threshold, the
 * active/inactive condition split, the clamped HP fraction, FR-6's toggle gate — lives in
 * `toTrackerUiState` and is tested there. Re-deriving any of it here would be a second opinion
 * about a question already answered, and the two would drift in exactly the way that is invisible
 * until a DM and a player look at the same character and disagree.
 *
 * What this type does instead is **narrow**: it drops the sections a card has no room for
 * (defenses, the rolls picker, consumables, the undo history) and keeps the five decision 12
 * names, plus the inventory line.
 *
 * @param showsWriteControls decision 14's whole posture, resolved to one boolean. See
 *   [dmCardShowsWriteControls] for the four conditions behind it; the card composable is
 *   forbidden from asking any of them itself, which is what makes "read-only cards render NO
 *   write controls" assertable at the state layer.
 * @param writeControlsEnabled false when the connection cannot carry a write right now. The
 *   controls are *dimmed* rather than absent, which is the tracker's own rule (04 §UX
 *   principles: connection state is visible, never a surprise error dialog) — the difference from
 *   [showsWriteControls] is "you may not" versus "not this second".
 * @param permissionDenied decision 18: the server refused a write on this card, so it has dropped
 *   to read-only for the rest of the session. Kept as its own field rather than folded into
 *   [showsWriteControls] because the card has to *say* why the controls went away.
 * @param grantedEditing decision 18's client-computed grant alone — `owner == me ||
 *   writers.contains(me)`, with the toggle deliberately **not** in it. Its own field rather than
 *   something inferable from [showsWriteControls] because the *toggle's* label has to count the
 *   cards it would affect, and that count has to be honest while the toggle is still off — which
 *   is precisely when the label is first read. See [couldBeEdited].
 */
data class DmCardUiState(
    val creatureId: String,
    val name: String,
    val availability: DmCardAvailability = DmCardAvailability.LOADING,
    val hp: HpState? = null,
    val slots: List<PipRowState> = emptyList(),
    val resources: List<PipRowState> = emptyList(),
    val conditions: List<ConditionChipState> = emptyList(),
    /** Decision 12's concentration banner. `null` when the character is not concentrating. */
    val concentratingOn: String? = null,
    val inventory: DmInventorySummary? = null,
    val showsWriteControls: Boolean = false,
    val writeControlsEnabled: Boolean = false,
    val permissionDenied: Boolean = false,
    val grantedEditing: Boolean = false,
) {
    /** Decision 19's card state, as the one question the composable asks. */
    val isAvailable: Boolean get() = availability == DmCardAvailability.AVAILABLE

    /**
     * Whether this card *would* offer controls if the toggle went on — the capability with the
     * toggle taken out of it.
     *
     * The three non-toggle conditions of [dmCardShowsWriteControls], and it reads them off this
     * card rather than restating them: the grant, the server's refusal, and decision 19's
     * availability. That is what makes "the toggle affects exactly these N cards" a fact about
     * the same rule the cards are drawn from, rather than a second count that could disagree
     * with what appears when the DM flips it.
     */
    val couldBeEdited: Boolean
        get() = dmCardShowsWriteControls(
            editingEnabled = true,
            isEditableByMe = grantedEditing,
            permissionDenied = permissionDenied,
            isAvailable = isAvailable,
        )

    /**
     * The whole card as **one sentence**, for TalkBack.
     *
     * ### Why a card is one node and not eleven
     *
     * The house pattern (`WalletUiState.spokenLabel`, `InventorySectionState.spokenLabel`, the
     * tracker's defense rows): a group of adjacent `Text`s that together state one fact is merged
     * into a single accessibility node carrying the whole fact. On this screen the argument is at
     * its strongest — a dashboard is six characters × HP + slots + conditions + concentration +
     * inventory, and swiping through ~40 unlabelled fragments to find out whether anybody is
     * concentrating is not a screen a screen-reader user can use at a table.
     *
     * So the read half of a card speaks once, in the order a sighted DM's eye takes it: who,
     * how hurt, what is spent, what is wrong with them, what they are carrying.
     *
     * ### What is deliberately NOT in this sentence
     *
     * The write controls. They are separate nodes outside the merged region, because a merged
     * node swallows its descendants' actions — a card that spoke its summary *and* absorbed the
     * steppers would be a card a screen-reader user could hear and not operate. That is the same
     * trade `InventoryScreen`'s summary rows make, in the direction that keeps the controls
     * reachable.
     *
     * ### Fragments arrive resolved
     *
     * Nothing here names a resource, so this stays a pure function `DmCardUiStateTest` can assert
     * — `spokenEquipLabel`'s contract. A `null` fragment is **dropped**, not spoken as an absence:
     * "Sabriel, no conditions, not concentrating" spends two clauses saying nothing on the
     * majority of cards on every table.
     *
     * @param unavailableLabel spoken *instead of* everything else when the card is
     *   [DmCardAvailability.NOT_AVAILABLE] — decision 19, in the one place it can be heard. A card
     *   the app cannot show must not read as a healthy character with no problems.
     * @param loadingLabel likewise for [DmCardAvailability.LOADING]. A separate word from
     *   [unavailableLabel] because the two mean opposite things about whether waiting helps.
     * @param hpLabel "24 of 38 hit points", or `null` when the sheet expresses no HP.
     * @param slotsLabel "3 spell slots spent", or `null` when nothing is spent — an unspent
     *   caster and a character with no slots at all are the same silence, which is correct: both
     *   are "nothing to report".
     * @param conditionsLabel the active conditions, already joined, or `null` when there are none.
     * @param concentrationLabel "concentrating on Bless", or `null`.
     * @param inventoryLabel "12 items, 47 pounds", or `null` before the inventory has loaded.
     * @param readOnlyLabel spoken last when editing is on elsewhere but not here — the answer to
     *   "why has this card no controls?", which is otherwise a silence a screen-reader user has no
     *   way to distinguish from a rendering bug.
     */
    fun spokenLabel(
        unavailableLabel: String,
        loadingLabel: String,
        hpLabel: String?,
        slotsLabel: String?,
        conditionsLabel: String?,
        concentrationLabel: String?,
        inventoryLabel: String?,
        readOnlyLabel: String?,
    ): String = when (availability) {
        DmCardAvailability.NOT_AVAILABLE -> listOf(name, unavailableLabel)
        DmCardAvailability.LOADING -> listOf(name, loadingLabel)
        DmCardAvailability.AVAILABLE -> listOfNotNull(
            name,
            hpLabel,
            slotsLabel,
            conditionsLabel,
            concentrationLabel,
            inventoryLabel,
            readOnlyLabel,
        )
    }.joinToString(SPOKEN_SEPARATOR)

    /**
     * How many spell slots are spent across every level, for the card's one-line slot summary.
     *
     * Summed rather than listed per level because a card cannot carry five pip rows and stay a
     * glance. The pips themselves are drawn for the levels that fit; this is what the spoken
     * sentence says (see `DmCardScreen`), and what a DM scanning six cards for "who is out of
     * resources?" is actually looking for.
     */
    val spentSlots: Int get() = slots.sumOf { it.spent }
}

/**
 * Decision 14 + 18's write gate, in **one** place.
 *
 * Four conditions, and each closes a different hole:
 *
 *  1. **[editingEnabled]** — decision 14's top-bar toggle. Default OFF on every entry, per
 *     session, never persisted (see `DmViewUiState.editingEnabled`). This is the condition that
 *     makes the dashboard *observe-only* until somebody deliberately says otherwise.
 *  2. **[isEditableByMe]** — decision 18's client-computed capability, `owner == me ||
 *     writers.contains(me)`, taken from `characterList` (see `CharacterSummary.isEditableByMe`).
 *     A DM who is only a *reader* on a player's sheet gets no controls even with the toggle on,
 *     because every tap would earn a refusal.
 *  3. **[permissionDenied]** — decision 18's honesty clause. Admin overrides and server-side
 *     changes are invisible to clients, so the client-computed capability can be wrong; when the
 *     server says so, that card drops to read-only rather than going on offering controls that
 *     cannot work.
 *  4. **[isAvailable]** — decision 19. A card that cannot show the character cannot offer to
 *     change them; there is no row to aim a write at, and a stepper over "Not available" is a
 *     control with no referent.
 *
 * ### Why this is a function rather than four `&&`s in the composable
 *
 * Because *"read-only cards render no write controls"* is the claim this feature has to be able
 * to prove, and a claim spread across four conditions inside a `@Composable` cannot be proved at
 * all in this module. Here it is one function with one test per condition, and `DmCardScreen`
 * reads the resulting boolean and nothing else.
 */
fun dmCardShowsWriteControls(
    editingEnabled: Boolean,
    isEditableByMe: Boolean,
    permissionDenied: Boolean,
    isAvailable: Boolean,
): Boolean = editingEnabled && isEditableByMe && !permissionDenied && isAvailable

/**
 * Decision 19's question, asked of a tracker state.
 *
 * ### The three branches, and why the middle one needs both clauses
 *
 * A card is [DmCardAvailability.NOT_AVAILABLE] only when the subscription has **gone ready** and
 * still has nothing: `CreatureSession` publishes `LIVE` only once `singleCharacter` is ready (see
 * its `connectionState`), so `tone == LIVE` is exactly "the server has told us everything it is
 * going to tell us for now".
 *
 * `showingSnapshot` is the second clause because a board can be non-empty *from Room* while the
 * subscription is still catching up — and, more subtly, an **empty** snapshot can be on screen
 * during a reconnect. Rendering "Not available" at a character whose data is merely stale would
 * be the same lie in the other direction: waiting will fix that one, so it is [LOADING].
 *
 * Everything else with rows on it is [AVAILABLE], including a card showing a snapshot — a DM
 * reading a cached sheet is reading something, and the connection dot is what says it is not
 * live.
 */
fun dmCardAvailability(tracker: TrackerUiState): DmCardAvailability = when {
    !tracker.isEmpty -> DmCardAvailability.AVAILABLE
    tracker.status.tone == ConnectionTone.LIVE && !tracker.status.showingSnapshot ->
        DmCardAvailability.NOT_AVAILABLE

    else -> DmCardAvailability.LOADING
}

/**
 * Tracker state + inventory + the write posture → one card (decisions 12, 18 and 19).
 *
 * Pure, so `DmCardUiStateTest` can pin every rule above without a device, a Compose runtime or a
 * DDP connection — the same contract `toTrackerUiState` and `toInventoryUiState` have, and for
 * the same reason: this is the mapping most likely to go quietly wrong, because "quietly wrong"
 * here means a write control on a sheet its owner did not share.
 *
 * @param name from the **character list**, not from the subscription. The list is already correct
 *   and on screen before any `singleCharacter` goes ready (`CharacterHomeViewModel` takes the
 *   name the same way), which is what lets a [DmCardAvailability.NOT_AVAILABLE] card still say
 *   *whose* card it is — the one thing that turns "Not available" from a mystery into a fact the
 *   DM can act on.
 */
fun toDmCardUiState(
    creatureId: String,
    name: String,
    tracker: TrackerUiState,
    inventory: DmInventorySummary?,
    isEditableByMe: Boolean,
    editingEnabled: Boolean,
    permissionDenied: Boolean,
): DmCardUiState {
    val availability = dmCardAvailability(tracker)
    val isAvailable = availability == DmCardAvailability.AVAILABLE
    val showsWriteControls = dmCardShowsWriteControls(
        editingEnabled = editingEnabled,
        isEditableByMe = isEditableByMe,
        permissionDenied = permissionDenied,
        isAvailable = isAvailable,
    )
    return DmCardUiState(
        creatureId = creatureId,
        name = name,
        availability = availability,
        // Every row is dropped when the card is not available, rather than trusted to be empty.
        // They *are* empty by construction today — that is what `dmCardAvailability` measured —
        // but "empty because the thing that decided we are unavailable also looked at these"
        // is a guarantee that evaporates the moment a future release adds a source the emptiness
        // check does not cover. Decision 19 says "never an empty tracker card"; this is the
        // difference between that being enforced and being true by arithmetic.
        hp = tracker.hp.takeIf { isAvailable },
        slots = if (isAvailable) tracker.slots else emptyList(),
        resources = if (isAvailable) tracker.resources else emptyList(),
        conditions = if (isAvailable) tracker.conditions else emptyList(),
        concentratingOn = tracker.concentratingOn.takeIf { isAvailable },
        inventory = inventory.takeIf { isAvailable },
        showsWriteControls = showsWriteControls,
        // The tracker's own dimming rule, reused rather than restated: `canWrite` is
        // `connectionState == LIVE`, which is also what `WriteQueue` refuses on. A control that
        // looked live while the queue would refuse it is 04's "surprise error dialog".
        writeControlsEnabled = showsWriteControls && tracker.canWrite,
        permissionDenied = permissionDenied,
        grantedEditing = isEditableByMe,
    )
}

/**
 * Whether a write failure is decision 18's *"Edit permission denied"* — the refusal that drops a
 * card to read-only for the rest of the session.
 *
 * ### Why this matches on the server's words, and why that is acceptable here
 *
 * DiceCloud reports this as a `Meteor.Error` whose `reason` is the sentence below; there is no
 * distinct error *code* to key on, which the probe recorded. Matching on prose is normally a bad
 * trade, and the reason it is the right one here is the direction of the failure:
 *
 *  - a match that is **too narrow** (the server rewords the message) leaves the card editable
 *    and every tap earns the ordinary "Not saved: …" snackbar — annoying, honest, recoverable;
 *  - a match that is **too broad** would drop a card to read-only over an unrelated failure,
 *    which the DM cannot undo without leaving the screen.
 *
 * So the comparison is case-insensitive and substring — the server has been observed to prefix
 * the sentence — but nothing looser. A null reason (a socket failure, an offline refusal) is not
 * a permission answer and must never be read as one; those already have their own copy.
 */
fun isEditPermissionDenied(reason: String?): Boolean =
    reason != null && reason.contains(EDIT_PERMISSION_DENIED, ignoreCase = true)

/**
 * The server's own sentence. Not a `strings.xml` entry: it is a **wire value** being matched, not
 * copy being shown — the copy the DM reads is `dm_view_permission_denied`, which is this app's
 * words about what happened.
 */
private const val EDIT_PERMISSION_DENIED = "Edit permission denied"

/**
 * What separates the facts inside a **spoken** card summary.
 *
 * A comma and a space, matching the inventory tab's `SPOKEN_SEPARATOR` and the tracker's defense
 * rows, because a screen reader pauses on a comma. Deliberately not the middle dot the *visible*
 * summary line uses: that one is seen, this one is heard, and a screen reader either skips a
 * middle dot or announces it as "middle dot".
 *
 * Its own constant rather than an import because the inventory tab's is `private` to its file and
 * deliberately so — the value is a house convention, not an API, and two screens agreeing about
 * it by sharing a symbol would be the first step towards a `Punctuation` object.
 */
private const val SPOKEN_SEPARATOR = ", "
