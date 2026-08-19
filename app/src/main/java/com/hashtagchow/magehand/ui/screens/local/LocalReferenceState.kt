package com.hashtagchow.magehand.ui.screens.local

import com.hashtagchow.magehand.core.model.Ability
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.formatSignedModifier

/**
 * The read-only reference strip on a local character's tracker
 * (docs/design/09-local-characters.md decision 6).
 *
 * *"Sheet basics render as a read-only reference strip (level, the six scores with modifiers,
 * AC — HP is already the tracker's first row). Not tappable; editing goes through the form."*
 *
 * ### Why the numbers arrive here already formatted
 *
 * Because the formatting is the part that can be wrong. `+3` versus `3`, and — the one that
 * actually bites — `−2` versus `−1` for a score of 7, which is what `abilityModifier`'s
 * `floorDiv` exists to get right. Turning the character into strings in a pure function means
 * `LocalReferenceStateTest` asserts the sentence the player reads, without a Compose runtime;
 * a composable that did its own `if (mod >= 0) "+"` would be untestable and would be the
 * second place that rule lives.
 *
 * HP is deliberately absent: it is the tracker's first row, and a strip that also printed
 * "24 / 24" would be a second, stale copy of a number the player is about to change.
 */
data class LocalReferenceState(
    /** `"Level 5"`, or `null` when the player left the level blank — the label is then absent. */
    val level: String?,
    /** The six scores in sheet order (STR → CHA). Always six entries. */
    val abilities: List<AbilityReference>,
    /** `"AC 15"`. Always present: the form gives armour class a default and a range. */
    val armorClass: String,
) {
    companion object {
        /** `null` in, `null` out — the strip is absent until the character has loaded. */
        fun from(character: LocalCharacter?): LocalReferenceState? {
            if (character == null) return null
            return LocalReferenceState(
                level = character.level?.let { "Level $it" },
                abilities = character.abilities.inSheetOrder.map { (ability, score) ->
                    AbilityReference(
                        ability = ability,
                        score = score.toString(),
                        // The rule itself, not a re-derivation of it: `AbilityScores.modifier`
                        // delegates to `abilityModifier`, whose `floorDiv` is the reason a
                        // score of 7 reads −2 here and not −1.
                        modifier = formatModifier(character.abilities.modifier(ability)),
                    )
                },
                armorClass = "AC ${character.armorClass}",
            )
        }

        /**
         * `3` → `"+3"`, `-2` → `"−2"`, `0` → `"+0"`.
         *
         * Delegates to [formatSignedModifier] rather than restating the rule. FR-7's Rolls
         * section prints the same six numbers for a local character, eight lines below this
         * strip and from a different code path — so the two disagreeing about `+0` or about
         * which character a minus sign is would be visible in a single glance. Kept as a name
         * on this class because that is what its own tests and its composable call.
         */
        fun formatModifier(value: Int): String = formatSignedModifier(value)
    }
}

/** One ability's cell in the strip: `STR 16 (+3)`, split so the strip can style the parts. */
data class AbilityReference(
    val ability: Ability,
    val score: String,
    /** Already signed — see [LocalReferenceState.Companion.formatModifier]. */
    val modifier: String,
) {
    /** `"STR"`. The enum's own name is the abbreviation every sheet prints. */
    val label: String get() = ability.name
}
