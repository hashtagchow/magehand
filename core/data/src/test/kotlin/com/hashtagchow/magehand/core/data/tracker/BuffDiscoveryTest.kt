package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * FR-53 R1 — the buffs currently on the character (ledgered 2026-09-14, design 21).
 *
 * ### Why the exclusions are in every fixture
 *
 * `tracker-discovery`'s standing rule for this repo: *"a vector carrying only the survivors would
 * prove nothing about the filter"*. That bites harder here than anywhere else, because the two
 * populations this rule separates are **byte-for-byte the same document** apart from two boolean
 * fields — an applied buff is a copy of its library template with `inactive` and
 * `deactivatedByAncestor` dropped. A fixture of applied buffs alone would pass against a reader
 * that matched `type == "buff"` and stopped, which is the reader that puts eighteen of Sabriel's
 * spells on her tracker as if she had cast all of them.
 *
 * ### Generic names, deliberately
 *
 * *Shield* and *Bless* are SRD spells and name nobody. The committed capture's own twenty buffs
 * are all templates, so there was no applied one to transcribe even if the store-safety rule
 * allowed it — these are synthetic, and the shapes come from the probe rather than from the party.
 */
class BuffDiscoveryTest {

    private val creatureId = "c1"

    /**
     * A buff in the shape the 2026-09-14 probe recorded.
     *
     * @param inactive what makes it a **template**: the library writes `deactivatedByAncestor`
     *   and the server derives `inactive` from it. Both are set together because both are on the
     *   wire together, and a reader keying on either one alone must still pass.
     */
    private fun buff(
        id: String,
        name: String,
        order: Int = 10,
        inactive: Boolean = false,
        removed: Boolean = false,
        silent: Boolean = false,
        parentCollection: String = "creatures",
        description: String? = null,
        extra: String = "",
    ): String {
        val inactiveJson = if (inactive) ""","inactive":true,"deactivatedByAncestor":true""" else ""
        val removedJson = if (removed) ""","removed":true""" else ""
        val silentJson = if (silent) ""","silent":true""" else ""
        val descriptionJson = description.orEmpty()
        return """{"_id":"$id","type":"buff","name":"$name","order":$order,
            "target":"self","parent":{"id":"p1","collection":"$parentCollection"}
            $inactiveJson$removedJson$silentJson$descriptionJson$extra}"""
    }

    private fun sheetOf(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    private fun buffsOf(vararg properties: String) = TrackerEngine.build(sheetOf(*properties)).buffs

    // --- R1: the blanket rule, and only the blanket rule --------------------

    /**
     * The headline: an applied buff is discovered, the template it was copied from is not.
     *
     * The two documents differ only in the template's two flags — which is the probe's finding
     * stated as a fixture, and the reason no other rule is needed or wanted.
     */
    @Test
    fun `an applied buff is discovered and its library template is not`() {
        val buffs = buffsOf(
            buff("applied", "Shield", order = 10),
            buff("template", "Shield", order = 11, inactive = true, parentCollection = "creatureProperties"),
        )

        assertEquals(listOf("applied"), buffs.map { it.propertyId })
        assertEquals("Shield", buffs.single().name)
    }

    /** 03's other half of the blanket skip. A soft-removed buff is still delivered; it is not on. */
    @Test
    fun `a removed buff is not discovered`() {
        assertTrue(buffsOf(buff("gone", "Bless", removed = true)).isEmpty())
    }

    /**
     * `silent: true` is **not** a filter — the probe's sharpest near-miss.
     *
     * A probed sheet's applied *Shield* is `silent: true` and that character's AC reads 20
     * because of it. `silent` governs whether the buff announces itself in the party feed, not whether it is running, and
     * a reader that treated it as "hidden" would drop the single example the feature was
     * requested for.
     */
    @Test
    fun `a silent buff is still on the character`() {
        assertEquals(listOf("Shield"), buffsOf(buff("s", "Shield", silent = true)).map { it.name })
    }

    /**
     * No ancestor walk. A live buff parented under something other than the creature root is kept.
     *
     * Every applied buff on the probe sits at the root, so a `parent.collection == 'creatures'`
     * test would have passed there too — and would silently lose a homebrew sheet's buff nested
     * one folder deep. Decision 1: one rule, not two that can disagree.
     */
    @Test
    fun `a live buff is kept wherever it is parented`() {
        val buffs = buffsOf(buff("nested", "Bless", parentCollection = "creatureProperties"))
        assertEquals(listOf("nested"), buffs.map { it.propertyId })
    }

    /** R1: ordered by the server's `order`, name as the tie-break. */
    @Test
    fun `buffs come back in the sheet's own order`() {
        val buffs = buffsOf(
            buff("c", "Haste", order = 30),
            buff("a", "Shield", order = 10),
            buff("b2", "Zephyr", order = 20),
            buff("b1", "Aid", order = 20),
        )
        assertEquals(listOf("Shield", "Aid", "Zephyr", "Haste"), buffs.map { it.name })
    }

    // --- R2: what the model carries, and what it does not -------------------

    /**
     * `duration` is not read. It is a `_calculation` and on the live sheet it is a **parse
     * error** (`"1 Turn"`), so the only honest thing to print from it is nothing — and the way to
     * guarantee that is for the model to have nowhere to put it.
     */
    @Test
    fun `duration is not read`() {
        val buffs = buffsOf(
            buff(
                "d", "Shield",
                extra = ""","duration":{"type":"_calculation","calculation":"1 Turn","errors":[{"type":"error"}]}""",
            ),
        )
        assertEquals(
            "AppliedBuff carries exactly propertyId, name and description",
            3,
            com.hashtagchow.magehand.core.model.AppliedBuff::class.java.declaredFields
                .count { !it.isSynthetic },
        )
        assertEquals("Shield", buffs.single().name)
    }

    /**
     * BUG-25's reader, on the buff's own description: the server's rendered `value`, never the
     * tokenised source, and **never** with the emphasis pre-stripped — that happens at the screen.
     */
    @Test
    fun `the description is the rendered value, falling back to the source`() {
        val rendered = buffsOf(
            buff(
                "r", "Shield",
                description = ""","description":{"text":"+{5} to AC","value":"**+5** to AC"}""",
            ),
        ).single()
        assertEquals("**+5** to AC", rendered.description)

        val sourceOnly = buffsOf(
            buff("s", "Bless", description = ""","description":{"text":"+1d4 to saves"}"""),
        ).single()
        assertEquals("+1d4 to saves", sourceOnly.description)

        assertNull("no description is null, not an empty paragraph", buffsOf(buff("n", "Aid")).single().description)
    }

    // --- R2: a buff is not a row, and the board knows it --------------------

    /**
     * BUG-20's sum, and the structural half of decision 2: a buff can never resolve as a tapped
     * countable row, because it is not one and has no `TrackedResource` to be one with.
     */
    @Test
    fun `a buff is not a countable row`() {
        val board = TrackerEngine.build(sheetOf(buff("b", "Shield")))

        assertTrue(board.allCountableRows.isEmpty())
        assertNull(board.countableRow("b"))
        assertTrue("a toggle chip is not what this is either", board.activeToggles.isEmpty())
    }

    /** A character carrying nothing but a live buff still has a screen worth drawing. */
    @Test
    fun `a board with only a buff is not empty`() {
        assertTrue(TrackerEngine.build(sheetOf()).isEmpty)
        assertTrue(!TrackerEngine.build(sheetOf(buff("b", "Shield"))).isEmpty)
    }

    /**
     * The override layer does not reach a buff — `TrackerBoard.buffs`' own note, pinned.
     *
     * No customize-sheet control can set one on a buff, so an override naming a buff id can only
     * have come from a stale row; honouring it would hide a chip with nothing on screen able to
     * bring it back.
     */
    @Test
    fun `a hidden override does not remove a buff`() {
        val board = TrackerEngine.build(
            sheetOf(buff("b", "Shield")),
            listOf(TrackerOverride(propertyId = "b", hidden = true)),
        )
        assertEquals(listOf("Shield"), board.buffs.map { it.name })
    }

    // --- R6: the concentration source ---------------------------------------

    /**
     * A buff-sourced banner resolves to the buff and **not** to a toggle, which is what makes its
     * ✕ live through `turnOffBuff` rather than through `flipToggle` (the call the server refuses).
     */
    @Test
    fun `a concentration buff is resolved from the board`() {
        val board = TrackerEngine.build(sheetOf(buff("c1b", "Concentration: Web")))

        assertEquals("Concentration: Web", board.concentratingOn)
        assertEquals("c1b", board.concentrationBuff?.propertyId)
        assertNull("flipToggle refuses a buff — that half stays null", board.concentrationToggle)
    }

    /** A source that is neither a flippable toggle nor a buff still offers nothing to press. */
    @Test
    fun `a computed concentration toggle leaves both halves null`() {
        val board = TrackerEngine.build(
            sheetOf("""{"_id":"t","type":"toggle","name":"Concentration","order":1}"""),
        )

        assertEquals("Concentration", board.concentratingOn)
        assertNull(board.concentrationToggle)
        assertNull(board.concentrationBuff)
    }
}
