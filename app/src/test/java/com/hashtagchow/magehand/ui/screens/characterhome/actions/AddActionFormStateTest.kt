package com.hashtagchow.magehand.ui.screens.characterhome.actions

import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.catalog.SpellCatalog
import com.hashtagchow.magehand.core.data.catalog.WeaponCatalog
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.NewLocalRowSpec
import com.hashtagchow.magehand.core.model.ResetRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add sheet's form (FR-49, docs/design/20-local-spells-and-attacks.md decision 6).
 *
 * Pure, so none of this needs a Compose harness — `AddActionFormState` is a data class with no
 * Compose in it precisely so that decision 6's central claim can be checked at the seam rather
 * than through a rendered sheet. The claim: **the catalog is a template, not the truth**, which
 * means a picked entry and a hand-typed one are the same value by the time anything acts on them.
 */
class AddActionFormStateTest {

    // --- the pre-fill (decision 6) ------------------------------------------

    /**
     * A catalog pick fills every field the row will keep, and the result is **immediately
     * saveable** — which is what makes the pre-filled form a template rather than a questionnaire.
     */
    @Test
    fun `picking a catalog spell fills the form and the form is already valid`() {
        val form = AddActionFormState.of(SpellCatalog.byId("fireball")!!)

        assertEquals(LocalRowKind.SPELL, form.kind)
        assertEquals("Fireball", form.name)
        assertEquals(3, form.spellLevel)
        assertEquals("1 action", form.castingTime)
        assertEquals("150 feet", form.range)
        assertEquals("V, S, M (A tiny ball of bat guano and sulfur.)", form.components)
        assertEquals("Instantaneous", form.duration)
        assertFalse(form.concentration)
        assertFalse(form.ritual)
        assertTrue(form.description.startsWith("A bright streak flashes"))
        assertTrue(form.higherLevels.startsWith("When you cast this spell"))
        assertEquals("fireball", form.catalogId)
        assertEquals("uses are the player's to add, not the SRD's", "", form.uses)

        val spec = form.toSpec()
        assertEquals("Fireball", spec?.label)
        assertEquals(3, spec?.spellLevel)
        assertEquals("fireball", spec?.catalogId)
    }

    /** A spell with no upcast paragraph pre-fills a blank field, which saves as absent. */
    @Test
    fun `a spell that does not upcast pre-fills an empty higher-levels field`() {
        val form = AddActionFormState.of(SpellCatalog.byId("light")!!)

        assertEquals(0, form.spellLevel)
        assertEquals("", form.higherLevels)
        assertNull("blank saves as absent, so no heading is drawn", form.toSpec()?.higherLevels)
    }

    /**
     * A weapon pick folds the mastery into the **editable** property string, which is the single
     * source FR-47's badge is read back out of (`LocalActionBoard.masteryFrom`).
     */
    @Test
    fun `picking a catalog weapon fills damage and the mastery-bearing property string`() {
        val form = AddActionFormState.of(WeaponCatalog.byId("longsword")!!)

        assertEquals(LocalRowKind.ATTACK, form.kind)
        assertEquals("Longsword", form.name)
        assertEquals("1d8 / 1d10 slashing", form.damage)
        assertEquals("Versatile (1d10), Mastery: Sap", form.properties)
        assertEquals("longsword", form.catalogId)
        assertNull("an attack states no level", form.spellLevel)
        assertTrue(form.toSpec()!!.isValid)
    }

    /**
     * **Every field stays editable**, and the edit is what gets saved — decision 6's *"the player
     * edits anything they like before saving"*, and the reason a row is self-contained.
     *
     * The `catalogId` survives the edit deliberately: it records where the text *came from*, which
     * stays true of a spell the player rewrote. What it does not do is reach back — nothing
     * re-reads the catalog, so a rewritten Fireball stays rewritten.
     */
    @Test
    fun `editing a pre-filled field changes what is saved and keeps the provenance`() {
        val spec = AddActionFormState.of(SpellCatalog.byId("fireball")!!)
            .copy(name = "Brambles' Bigger Ball", spellLevel = 5, range = "300 feet")
            .toSpec()

        assertEquals("Brambles' Bigger Ball", spec?.label)
        assertEquals(5, spec?.spellLevel)
        assertEquals("300 feet", spec?.range)
        assertEquals("fireball", spec?.catalogId)
    }

    // --- validation ---------------------------------------------------------

    /**
     * A hand-typed spell starts with no level and is not saveable until one is chosen — decision
     * 6's *"level required on a spell"*, which is `LocalCharacterForm`'s own rule restated at the
     * one place the sheet can enforce it.
     */
    @Test
    fun `a custom spell needs a name and a level`() {
        val blank = AddActionFormState(kind = LocalRowKind.SPELL)
        assertNull(blank.toSpec())

        assertNull(blank.copy(name = "Homebrew").toSpec())
        assertNull(blank.copy(spellLevel = 2).toSpec())
        assertNull("blank, not merely empty", blank.copy(name = "   ", spellLevel = 2).toSpec())

        val ok = blank.copy(name = " Homebrew ", spellLevel = 2).toSpec()
        assertEquals("trimmed on the way out", "Homebrew", ok?.label)
    }

    /** A custom attack needs only a name — 20 decision 8 gives it no required number at all. */
    @Test
    fun `a custom attack needs only a name`() {
        assertNull(AddActionFormState(kind = LocalRowKind.ATTACK).toSpec())
        assertTrue(AddActionFormState(kind = LocalRowKind.ATTACK, name = "Club").toSpec()!!.isValid)
    }

    /**
     * Messages stay off until the first save attempt, then name the field — the posture
     * `LocalCharacterFormState` and `AddItemFormState` both take.
     */
    @Test
    fun `errors are silent until the first save attempt`() {
        val blank = AddActionFormState(kind = LocalRowKind.SPELL)

        assertNull(blank.nameError)
        assertNull(blank.spellLevelError)

        with(blank.copy(showErrors = true)) {
            assertEquals(R.string.local_error_row_label, nameError)
            assertEquals(R.string.local_error_row_spell_level, spellLevelError)
        }

        with(blank.copy(showErrors = true, name = "Homebrew", spellLevel = 1)) {
            assertNull(nameError)
            assertNull(spellLevelError)
        }
    }

    /** An attack is never asked for a level, even once the messages are on. */
    @Test
    fun `an attack form never shows a level error`() {
        val form = AddActionFormState(kind = LocalRowKind.ATTACK, name = "Club", showErrors = true)
        assertNull(form.spellLevelError)
    }

    // --- the empty uses box, and why it is not an error ---------------------

    /**
     * A blank **uses** box is `0` — the stored "unlimited" — and not the sentinel the character
     * form uses for a cleared number field.
     *
     * The difference is what the empty box *means*: on the character form an empty AC box is a
     * field the player cleared and the save must refuse; here it is the ordinary state of the
     * ordinary spell, which casts from a slot and has no charges of its own. A sentinel would have
     * made the common case an error.
     */
    @Test
    fun `a blank uses box means unlimited and not an error`() {
        val form = AddActionFormState(kind = LocalRowKind.SPELL, name = "Fireball", spellLevel = 3)

        assertEquals(0, form.toSpec()?.uses)
        assertNull(form.copy(showErrors = true).usesError)
        assertFalse("nothing to reset, so no chips", form.offersReset)
    }

    /**
     * A uses box too long for an `Int` is an **error**, not "unlimited" — NIT 6
     * [review, 2026-09-12].
     *
     * The field filters to digits, so `"99999999999999"` used to pass the filter, fail
     * `toIntOrNull`, and fall through the `?: 0` into the *blank* case — which means unlimited.
     * A player asking for a hundred billion uses got a row with no limit at all, saved silently,
     * with no red field and nothing in the way. `AddActionSheet` now caps the box at
     * [AddActionFormState.USES_MAX_DIGITS] so typing cannot reach it; this is the other half, for
     * the paste that does.
     */
    @Test
    fun `a uses box too long for an Int is an error, not unlimited`() {
        val form = AddActionFormState(
            kind = LocalRowKind.SPELL,
            name = "Fireball",
            spellLevel = 3,
            uses = "99999999999999",
            showErrors = true,
        )

        assertNotNull("overflow must be refused, not read as unlimited", form.usesError)
        assertNull(form.toSpec())
        assertFalse("an unsaveable limit offers no reset rule", form.offersReset)
    }

    /** The cap is derived from the range the spec validates against, not written out. */
    @Test
    fun `the uses field is capped at the width of the range it is validated against`() {
        assertEquals(
            NewLocalRowSpec.USES_RANGE.last.toString().length,
            AddActionFormState.USES_MAX_DIGITS,
        )
        assertNull(
            "the largest in-range value still saves",
            AddActionFormState(
                kind = LocalRowKind.SPELL,
                name = "Fireball",
                spellLevel = 3,
                uses = NewLocalRowSpec.USES_RANGE.last.toString(),
                showErrors = true,
            ).usesError,
        )
    }

    /** With uses, the reset chips appear and the rule is saved beside them. */
    @Test
    fun `uses bring the reset chips with them, and lose them again`() {
        val form = AddActionFormState(
            kind = LocalRowKind.SPELL,
            name = "Misty Step",
            spellLevel = 2,
            uses = "2",
            reset = ResetRule.LONG_REST,
        )

        assertTrue(form.offersReset)
        assertEquals(2, form.toSpec()?.uses)
        assertEquals(ResetRule.LONG_REST, form.toSpec()?.reset)

        // Clearing the uses drops the rule from the save and keeps it on screen, so raising them
        // again does not lose what the player picked — `LocalRowFormState.toRowForm`'s shape.
        val unlimited = form.copy(uses = "")
        assertFalse(unlimited.offersReset)
        assertNull(unlimited.toSpec()?.reset)
        assertEquals(ResetRule.LONG_REST, unlimited.reset)
    }

    // --- the per-kind fence -------------------------------------------------

    /**
     * A field the kind cannot mean is **dropped on the way out** and kept on screen — the
     * arrangement `reset` and `category` already have on the editor's row form, and for their
     * stated reason: switching kinds mid-edit and switching back must not lose what was typed.
     */
    @Test
    fun `a spell's scalars are dropped when the form is an attack, and the reverse`() {
        val muddled = AddActionFormState(
            kind = LocalRowKind.ATTACK,
            name = "Longsword",
            spellLevel = 3,
            castingTime = "1 action",
            range = "150 feet",
            components = "V, S",
            duration = "Instantaneous",
            higherLevels = "…",
            concentration = true,
            ritual = true,
            damage = "1d8 slashing",
            properties = "Versatile (1d10)",
        )

        with(muddled.toSpec()!!) {
            assertNull(spellLevel)
            assertNull(castingTime)
            assertNull(range)
            assertNull(components)
            assertNull(duration)
            assertNull(higherLevels)
            assertFalse(concentration)
            assertFalse(ritual)
            assertEquals("1d8 slashing", damage)
            assertEquals("Versatile (1d10)", properties)
        }

        with(muddled.copy(kind = LocalRowKind.SPELL).toSpec()!!) {
            assertEquals(3, spellLevel)
            assertEquals("1 action", castingTime)
            assertTrue(concentration)
            assertNull("a spell has no damage text", damage)
            assertNull(properties)
        }

        // …and the state itself still holds everything, so the switch is reversible on screen.
        assertEquals("1 action", muddled.castingTime)
    }

    /** Blank optional fields save as absent, never as empty strings. */
    @Test
    fun `blank optional fields become absent rather than empty`() {
        val spec = AddActionFormState(
            kind = LocalRowKind.SPELL,
            name = "Homebrew",
            spellLevel = 1,
            castingTime = "  ",
            range = "",
            description = " ",
        ).toSpec()!!

        assertNull(spec.castingTime)
        assertNull(spec.range)
        assertNull(spec.description)
    }

    /** Whatever the form produces, the write path's own gate agrees with it. */
    @Test
    fun `a form that produces a spec produces a valid one`() {
        val forms = listOf(
            AddActionFormState.of(SpellCatalog.byId("cure-wounds")!!),
            AddActionFormState.of(WeaponCatalog.byId("dagger")!!),
            AddActionFormState(kind = LocalRowKind.SPELL, name = "Homebrew", spellLevel = 9),
            AddActionFormState(kind = LocalRowKind.ATTACK, name = "Improvised"),
        )

        forms.forEach { form ->
            val spec: NewLocalRowSpec = checkNotNull(form.toSpec()) { "expected ${form.name} to save" }
            assertTrue(spec.isValid)
        }
    }
}
