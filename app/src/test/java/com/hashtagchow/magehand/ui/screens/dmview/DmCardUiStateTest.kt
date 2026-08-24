package com.hashtagchow.magehand.ui.screens.dmview

import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.InventoryItem
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConditionChipState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionStatus
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.ConnectionTone
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PipRowState
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerUiState
import com.hashtagchow.magehand.core.model.TrackerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * FR-19's card (docs/design/14-large-screen-arc.md decisions 12, 14, 18 and 19).
 *
 * ### The defect each group is about
 *
 * - **availability**: decision 19's whole reason — an unviewable creature's subscription returns
 *   ready-and-empty, which is indistinguishable from an empty creature. A card that drew an
 *   empty tracker for that would read at a table as *"this character is fine"*.
 * - **the write gate**: a write control on a sheet whose owner did not share write access, or on
 *   a card the server has already refused. This is the group the feature has to be able to prove,
 *   because the failure is somebody else's character being changed.
 * - **the spoken sentence**: a screen-reader user swiping through ~40 unlabelled fragments to
 *   find out whether anybody is concentrating.
 * - **isEditPermissionDenied**: a match too broad, which would drop a card to read-only over an
 *   unrelated failure the DM cannot undo without leaving the screen.
 */
class DmCardUiStateTest {

    private fun tracker(
        hp: HpState? = HpState("hp", current = 24, max = 38, tempHp = 0),
        slots: List<PipRowState> = emptyList(),
        conditions: List<ConditionChipState> = emptyList(),
        concentratingOn: String? = null,
        tone: ConnectionTone = ConnectionTone.LIVE,
        showingSnapshot: Boolean = false,
        canWrite: Boolean = true,
    ) = TrackerUiState(
        creatureId = "c1",
        status = ConnectionStatus(tone = tone, showingSnapshot = showingSnapshot),
        concentratingOn = concentratingOn,
        hp = hp,
        slots = slots,
        conditions = conditions,
        canWrite = canWrite,
    )

    private fun slot(level: String, value: Int, total: Int) = PipRowState(
        propertyId = "slot-$level",
        label = level,
        reset = null,
        value = value,
        total = total,
        pinned = false,
        kind = TrackerKind.SPELL_SLOT,
    )

    private fun card(
        tracker: TrackerUiState = tracker(),
        inventory: DmInventorySummary? = null,
        isEditableByMe: Boolean = true,
        editingEnabled: Boolean = false,
        permissionDenied: Boolean = false,
    ) = toDmCardUiState(
        creatureId = "c1",
        name = "Sabriel",
        tracker = tracker,
        inventory = inventory,
        isEditableByMe = isEditableByMe,
        editingEnabled = editingEnabled,
        permissionDenied = permissionDenied,
    )

    // ---- availability (decision 19) -----------------------------------------

    @Test
    fun `a subscription that readied with nothing is Not available, not an empty card`() {
        // The probe's quirk, and decision 19's whole content. `CreatureSession` publishes LIVE
        // only once `singleCharacter` is ready, so LIVE-and-empty is exactly "the server has told
        // us everything it is going to".
        val state = card(tracker(hp = null, tone = ConnectionTone.LIVE))

        assertEquals(DmCardAvailability.NOT_AVAILABLE, state.availability)
    }

    @Test
    fun `an empty card that is still connecting is loading, because waiting will fix it`() {
        // The other side of the same coin: rendering "Not available" at a character whose data is
        // merely late is the same lie in the opposite direction.
        val state = card(tracker(hp = null, tone = ConnectionTone.RECONNECTING))

        assertEquals(DmCardAvailability.LOADING, state.availability)
    }

    @Test
    fun `an empty snapshot on screen during a reconnect is loading, not unavailable`() {
        // The clause that needs both halves. A board can be non-empty from Room while the sub
        // catches up — and an *empty* snapshot can be on screen too, which is still a wait.
        val state = card(tracker(hp = null, tone = ConnectionTone.LIVE, showingSnapshot = true))

        assertEquals(DmCardAvailability.LOADING, state.availability)
    }

    @Test
    fun `a card with rows is available even while showing a cached sheet`() {
        // A DM reading a cached sheet is reading something. The connection state is what says it
        // is not live; "Not available" would claim there is nothing, which is false.
        val state = card(
            tracker(tone = ConnectionTone.OFFLINE, showingSnapshot = true, canWrite = false),
        )

        assertEquals(DmCardAvailability.AVAILABLE, state.availability)
    }

    @Test
    fun `an unavailable card carries no tracker rows at all`() {
        // Decision 19: "never an empty tracker card". Enforced by dropping the rows rather than
        // trusting them to be empty — they are, today, by the same arithmetic `dmCardAvailability`
        // used, and that is precisely the guarantee that evaporates when a future release adds a
        // source the emptiness check does not cover.
        val state = card(
            tracker(hp = null, tone = ConnectionTone.LIVE, concentratingOn = "Bless"),
            inventory = DmInventorySummary(itemCount = 3, weight = "7", overCapacity = false),
        )

        assertEquals(DmCardAvailability.NOT_AVAILABLE, state.availability)
        assertNull(state.hp)
        assertNull(state.concentratingOn)
        assertNull(state.inventory)
        assertTrue(state.slots.isEmpty())
        assertTrue(state.conditions.isEmpty())
    }

    // ---- the write gate (decisions 14 and 18) -------------------------------

    @Test
    fun `the dashboard opens observe-only`() {
        // Decision 14: "The dashboard opens observe-only: no write controls rendered." The toggle
        // is off, the character is the DM's own, and there are still no controls.
        val state = card(isEditableByMe = true, editingEnabled = false)

        assertFalse(state.showsWriteControls)
    }

    @Test
    fun `the toggle alone does not grant controls on a sheet nobody shared`() {
        // Decision 18's client-computed capability. A DM who is only a *reader* gets no controls
        // even with editing on, because every tap would earn a refusal.
        val state = card(isEditableByMe = false, editingEnabled = true)

        assertFalse(state.showsWriteControls)
    }

    @Test
    fun `a refused card stays read-only for the rest of the session`() {
        // Decision 18's honesty clause: admin overrides and server-side share changes are
        // invisible to clients, so the client-computed capability can be wrong, and the server's
        // answer wins. The grant is deliberately still true here — this is the case where the two
        // disagree.
        val state = card(isEditableByMe = true, editingEnabled = true, permissionDenied = true)

        assertFalse(state.showsWriteControls)
        assertTrue(state.permissionDenied)
    }

    @Test
    fun `an unavailable card offers no controls whatever the toggle says`() {
        // Decision 19 meeting decision 14: there is no row to aim a write at, so a stepper over
        // "Not available" would be a control with no referent.
        val state = card(
            tracker(hp = null, tone = ConnectionTone.LIVE),
            isEditableByMe = true,
            editingEnabled = true,
        )

        assertFalse(state.showsWriteControls)
    }

    @Test
    fun `all four conditions together are what grants controls`() {
        val state = card(isEditableByMe = true, editingEnabled = true, permissionDenied = false)

        assertTrue(state.showsWriteControls)
        assertTrue(state.writeControlsEnabled)
    }

    @Test
    fun `controls are shown but dimmed when the connection cannot carry a write`() {
        // The tracker's own rule, reused: "you may not edit this character" is *absence*, "not
        // this second" is a dimmed control. Collapsing the two would make a wifi blip look like a
        // revoked share.
        val state = card(
            tracker(canWrite = false),
            isEditableByMe = true,
            editingEnabled = true,
        )

        assertTrue("the capability is unchanged by the connection", state.showsWriteControls)
        assertFalse(state.writeControlsEnabled)
    }

    @Test
    fun `couldBeEdited answers the toggle's question while the toggle is off`() {
        // What the toggle's own spoken label counts. Counting `showsWriteControls` would report
        // zero every time the toggle is off — which is every time the label is first read.
        val grantedButOff = card(isEditableByMe = true, editingEnabled = false)
        val notGranted = card(isEditableByMe = false, editingEnabled = false)

        assertTrue(grantedButOff.couldBeEdited)
        assertFalse(notGranted.couldBeEdited)
    }

    // ---- the inventory summary (decision 12) --------------------------------

    @Test
    fun `the summary counts items once and formats the weight the way the inventory tab does`() {
        val board = InventoryBoard(
            carried = listOf(item("i1", 1), item("i2", 2)),
            carriedWeightLb = 47.0,
        )

        val summary = toDmInventorySummary(board)

        assertEquals(2, summary.itemCount)
        // `formatAmount`'s rule — "47", not "47.0". The one copy of it, shared with the inventory
        // tab, so the same character's carried weight cannot read two ways eight inches apart.
        assertEquals("47", summary.weight)
        assertFalse(summary.overCapacity)
    }

    @Test
    fun `over capacity is carried on the summary because a card cannot print the fraction`() {
        val board = InventoryBoard(carriedWeightLb = 142.0, capacityLb = 135)

        assertTrue(toDmInventorySummary(board).overCapacity)
    }

    private fun item(id: String, quantity: Int) = InventoryItem(
        propertyId = id,
        name = id,
        quantity = quantity,
        weightLb = null,
        valueGp = null,
        equipped = false,
        description = null,
    )

    // ---- the spoken sentence (a11y) -----------------------------------------

    @Test
    fun `a card speaks one sentence in the order the eye takes it`() {
        val state = card(
            tracker(
                slots = listOf(slot("1st", value = 1, total = 4)),
                conditions = listOf(ConditionChipState("t1", "Prone", enabled = true)),
                concentratingOn = "Bless",
            ),
            inventory = DmInventorySummary(itemCount = 12, weight = "47", overCapacity = false),
        )

        val spoken = state.spokenLabel(
            unavailableLabel = "Not available",
            loadingLabel = "Loading",
            hpLabel = "24 of 38 hit points",
            slotsLabel = "3 spell slots spent",
            conditionsLabel = "conditions: Prone",
            concentrationLabel = "concentrating on Bless",
            inventoryLabel = "12 items, 47 lb",
            readOnlyLabel = null,
        )

        assertEquals(
            "Sabriel, 24 of 38 hit points, 3 spell slots spent, conditions: Prone, " +
                "concentrating on Bless, 12 items, 47 lb",
            spoken,
        )
    }

    @Test
    fun `absent facts are dropped rather than spoken as absences`() {
        // "Sabriel, no conditions, not concentrating" spends two clauses saying nothing, on the
        // majority of cards on every table. `spokenEquipLabel`'s rule, applied here.
        val state = card()

        val spoken = state.spokenLabel(
            unavailableLabel = "Not available",
            loadingLabel = "Loading",
            hpLabel = "24 of 38 hit points",
            slotsLabel = null,
            conditionsLabel = null,
            concentrationLabel = null,
            inventoryLabel = null,
            readOnlyLabel = null,
        )

        assertEquals("Sabriel, 24 of 38 hit points", spoken)
    }

    @Test
    fun `an unavailable card says so instead of reading as a healthy character`() {
        // Decision 19, in the one place it can be heard. The name survives, because "Not
        // available" without a name is a mystery rather than a fact the DM can act on.
        val state = card(tracker(hp = null, tone = ConnectionTone.LIVE))

        val spoken = state.spokenLabel(
            unavailableLabel = "Not available",
            loadingLabel = "Loading",
            // Deliberately non-null: an unavailable card must not speak them even when a caller
            // hands them over.
            hpLabel = "24 of 38 hit points",
            slotsLabel = "3 spell slots spent",
            conditionsLabel = "conditions: Prone",
            concentrationLabel = "concentrating on Bless",
            inventoryLabel = "12 items, 47 lb",
            readOnlyLabel = "read only",
        )

        assertEquals("Sabriel, Not available", spoken)
    }

    @Test
    fun `spent slots are summed across levels, because a card cannot carry five pip rows`() {
        val state = card(
            tracker(slots = listOf(slot("1st", 1, 4), slot("2nd", 0, 3), slot("3rd", 2, 2))),
        )

        // 3 + 3 + 0 = 6 spent. This is what a DM scanning six cards for "who is out of
        // resources?" is looking for; the per-level detail is one tap away.
        assertEquals(6, state.spentSlots)
    }

    // ---- the refusal match (decision 18) ------------------------------------

    @Test
    fun `the server's refusal drops the card to read-only`() {
        assertTrue(isEditPermissionDenied("Edit permission denied"))
        // Observed prefixed, and matched case-insensitively, because the server's exact casing is
        // not something this client controls.
        assertTrue(isEditPermissionDenied("Error: edit permission denied"))
    }

    @Test
    fun `an unrelated failure does not take a capability away`() {
        // The direction that matters. A match too broad would drop a card to read-only over a
        // rate limit or a socket failure, which the DM cannot undo without leaving the screen —
        // where a match too narrow merely produces the ordinary "Not saved" snackbar.
        assertFalse(isEditPermissionDenied(null))
        assertFalse(isEditPermissionDenied("Too fast"))
        assertFalse(isEditPermissionDenied("Computed toggle"))
        assertFalse(isEditPermissionDenied(""))
    }

    // ---- the card's counted sentences ---------------------------------------

    /**
     * Every counted fragment on a card is a `<plurals>`, and both arms read correctly.
     *
     * `inventory_section_items`' precedent and its reason, applied where it applies twice as
     * hard. The spoken label built by `DmCardUiState.spoken()` concatenates these fragments into
     * one sentence per card, and a DM using TalkBack hears six of them in a row — so a format
     * string saying *"1 items"* and *"1 spell slots spent"* is not a typo seen once, it is the
     * texture of the whole screen. Both counts genuinely reach 1 in ordinary play: a character
     * carrying one thing, and the first slot spent in a fight.
     *
     * Read out of the file that ships and formatted the way the card formats it — two literals
     * agreeing would only prove they were typed the same day.
     */
    @Test
    fun `the card's item and slot counts are plurals that read correctly at one`() {
        val strings = stringsXml().readText()

        fun forms(name: String): Map<String, String> {
            val block = Regex("""<plurals name="$name">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
                .find(strings)
                ?.groupValues
                ?.get(1)
            assertNotNull(
                "$name must be a <plurals>, not a <string> — a format string makes every " +
                    "single-item card say '1 items' out loud",
                block,
            )
            return Regex("""<item quantity="(\w+)">(.*?)</item>""")
                .findAll(block!!)
                .associate { it.groupValues[1] to it.groupValues[2] }
        }

        listOf(
            "dm_view_card_slots_spent",
            "dm_view_card_inventory",
            "dm_view_card_inventory_over",
        ).forEach { name ->
            assertEquals("$name: English needs exactly one and other", setOf("one", "other"), forms(name).keys)
        }

        val slots = forms("dm_view_card_slots_spent")
        assertEquals("1 spell slot spent", String.format(Locale.US, slots.getValue("one"), 1))
        assertEquals("2 spell slots spent", String.format(Locale.US, slots.getValue("other"), 2))

        // Two arguments, and the second is the weight — so the *order* is pinned too: a plural
        // whose arms disagreed about which placeholder is which would compile and read wrong.
        val inventory = forms("dm_view_card_inventory")
        assertEquals("1 item, 3.5 lb", String.format(Locale.US, inventory.getValue("one"), 1, "3.5"))
        assertEquals("12 items, 47 lb", String.format(Locale.US, inventory.getValue("other"), 12, "47"))

        val over = forms("dm_view_card_inventory_over")
        assertEquals("1 item, 3.5 lb, over capacity", String.format(Locale.US, over.getValue("one"), 1, "3.5"))
        assertEquals("12 items, 47 lb, over capacity", String.format(Locale.US, over.getValue("other"), 12, "47"))
    }

    /**
     * The `value/total` on a resource or slot row is a resource, not string interpolation.
     *
     * `"${row.value}/${row.total}"` renders ASCII digits on every device, whatever locale it is
     * set to; a `%1$d` in `strings.xml` is formatted through the current locale and gets that
     * locale's digits — `inventory_attuned`'s existing pattern, which is the same fragment on
     * the character screen. The card had the interpolated form, which is the sort of thing that
     * only shows up on somebody else's phone.
     */
    @Test
    fun `the card's value over total pair goes through a string resource`() {
        val source = dmCardSource()

        assertFalse(
            "DmCard.kt must not interpolate the ratio — use R.string.dm_view_card_ratio",
            source.contains("\"\${row.value}/\${row.total}\""),
        )
        assertEquals(
            "both rows (the read-only line and the stepper row) must use the resource",
            2,
            Regex("""R\.string\.dm_view_card_ratio""").findAll(source).count(),
        )
        assertTrue(
            "and the resource must exist",
            stringsXml().readText().contains("""<string name="dm_view_card_ratio">"""),
        )
    }

    /** `DmCard.kt`, found by walking up — `InventoryUiStateTest.stringsXml`'s reason. */
    private fun dmCardSource(): String = walkUpFor(
        "app/src/main/java/com/hashtagchow/magehand/ui/screens/dmview/DmCard.kt",
        "src/main/java/com/hashtagchow/magehand/ui/screens/dmview/DmCard.kt",
    ).readText()

    /**
     * `app/src/main/res/values/strings.xml`, found by walking up from the working directory.
     *
     * Gradle runs a unit test with the *module* directory as its working directory, but that is
     * a default rather than a promise and an IDE runner may disagree — `InventoryUiStateTest`
     * carries the same walk for the same reason.
     */
    private fun stringsXml(): File = walkUpFor(
        "app/src/main/res/values/strings.xml",
        "src/main/res/values/strings.xml",
    )

    private fun walkUpFor(fromRoot: String, fromModule: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            File(dir, fromRoot).takeIf { it.isFile }?.let { return it }
            File(dir, fromModule).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw AssertionError("could not find $fromRoot from ${System.getProperty("user.dir")}")
    }
}
