package com.hashtagchow.magehand.core.model

/**
 * One quest, as the log renders it (FR-32, docs/design/18-table-pack.md decisions 13–16).
 *
 * ### This is a *convention*, not a schema
 *
 * DiceCloud has no quest type. What it has is a `note` property with a free-text name, a
 * `summary`, a `description` and an array of `tags` inherited from the base property schema — and
 * what the table has is a habit of using them a particular way, live on all three party sheets
 * (probed 2026-08-28): `tags: ['quest']`, a name prefixed `⚔️ QUEST · `, and a structured
 * `summary.text` reading *"QUEST · Giver: X · Reward: Y · Status: STATE"*.
 *
 * That difference is the reason this type is deliberately thin and every field of it is optional
 * except the identity and the title. A schema can be relied on; a convention is something people
 * type, and the reading has to survive somebody typing it slightly differently — a note with the
 * tag and no prefix, or the prefix and no summary, is still a quest and still renders. `QuestEngine`
 * is where each tolerance is written and argued.
 *
 * ### Read-only, and not for lack of a write
 *
 * Decision 15: *"Read-only v1; marking closed from the app = v2 (the tags write path needs its own
 * probe)"*. So there is no `closed` setter here and no property id path to one — the same
 * structural read-only posture `DamageDefense` and `RollModifier` have, for the same reason:
 * nothing in this file can be turned into a write, so nothing has to remember not to.
 *
 * @property propertyId `creatureProperties._id`. The stable identity the expanded row keys on; it
 *   is never written to.
 * @property title the note's `name` with the `⚔️ QUEST · ` prefix stripped (decision 13). Never
 *   blank — a note whose name is only the prefix keeps the raw name rather than becoming a blank
 *   row, see `QuestEngine`.
 * @property summary `summary.text`, **rendered as-is** (decision 13). The structured
 *   *"Giver / Reward / Status"* line is deliberately not parsed into fields: the parse would be
 *   this app inventing a schema for a habit, and it would fail silently the first time somebody
 *   wrote the line differently. `null` when the note carries none.
 * @property description `description.text`, shown on tap. Plain text in v1 — no markdown
 *   rendering, the design-16 precedent (16 decision 4's own deferral, unchanged here).
 * @property closed the operator's `closed` tag (decision 13). Finished quests sort to the
 *   **bottom**, de-emphasized, and are **never hidden**: a table wants its history.
 * @property sortOrder the sheet's own `order`, which is the only ordering the notes carry and
 *   therefore the only tie-break within each group.
 */
data class QuestEntry(
    val propertyId: String,
    val title: String,
    val summary: String? = null,
    val description: String? = null,
    val closed: Boolean = false,
    val sortOrder: Int = 0,
) {
    /** Whether tapping the row has anything to reveal. See [description]. */
    val hasDetail: Boolean get() = !description.isNullOrBlank()
}
