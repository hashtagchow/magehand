package com.hashtagchow.magehand.core.model

/**
 * One row of the character list (docs/design/03-data-model.md, screen 2 of
 * docs/design/04-screens-ux.md).
 *
 * Assembled from a `creatures` document delivered by the `characterList`
 * publication. That publication carries **only** creature-level fields — verified
 * live on 2026-08-17, see docs/verification/WP5.md §2 — which is why there is no
 * class/level here: those live in `creatureProperties`, which only arrives with
 * the per-character `singleCharacter` subscription (WP6).
 *
 * @param creatureId the Meteor `_id` of the creature; the key for everything else.
 * @param name the creature's name as shown on the sheet.
 * @param alignment free text ("Chaotic Good", "Lawful Good ( his own LAW )") or
 *   `null` — DiceCloud does not constrain it.
 * @param gender free text or `null`.
 * @param picture portrait URL as stored on the sheet, or `null`. **Not guaranteed
 *   to be an image**: the table's sheets carry HeroForge *configurator* links, so
 *   the UI must degrade to a monogram rather than assume a decodable bitmap.
 * @param owner the Meteor **user id** of the creature's owner. The publication does
 *   not send the owning user's document, so there is no username to show — see
 *   [isOwnedByMe] for what the UI can actually say.
 * @param isOwnedByMe `owner == <the signed-in user id>`. False means "shared with
 *   you", which is the DM case: the DungeonMaster account is a reader/writer on
 *   the whole party but owns none of it, so every party row is badged.
 * @param writers the creature's `writers` array — Meteor user ids the sheet's owner
 *   has granted write access to. See [isEditableByMe]; the raw list is kept because
 *   it is the fact the server states, and a derived boolean alone would make a
 *   future "who else can edit this?" a re-fetch.
 * @param isEditableByMe FR-19's edit-capability gate (docs/design/14-large-screen-arc.md
 *   decision 18): `owner == me || writers.contains(me)`, resolved against the **live**
 *   user id for [isOwnedByMe]'s reason.
 */
data class CharacterSummary(
    val creatureId: String,
    val name: String,
    val alignment: String? = null,
    val gender: String? = null,
    val picture: String? = null,
    val owner: String = "",
    val isOwnedByMe: Boolean = false,
    val writers: List<String> = emptyList(),
    val isEditableByMe: Boolean = false,
) {
    /**
     * The one-or-two-word line under the name. Empty when the sheet carries
     * neither field, in which case the UI drops the line entirely.
     */
    val subtitle: String
        get() = listOfNotNull(alignment?.takeIf { it.isNotBlank() }, gender?.takeIf { it.isNotBlank() })
            .joinToString(" · ")

    /**
     * Up to two letters for the fallback portrait monogram.
     *
     * Bracketed and parenthesised suffixes are dropped first, because sheets are
     * routinely named things like `Elowen Brightmantle [2014 archive]` and
     * `Fenwick (Warden of the Vale)` — naively taking the first character of the
     * first two words yields `E[` and `F(`.
     */
    val monogram: String
        get() {
            val base = name.substringBefore('[').substringBefore('(').trim().ifEmpty { name.trim() }
            return base.split(Regex("\\s+"))
                .mapNotNull { word -> word.firstOrNull(Char::isLetter) }
                .take(2)
                .joinToString("") { it.uppercase() }
                .ifEmpty { "?" }
        }
}
