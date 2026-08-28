package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonObject
import com.hashtagchow.magehand.core.model.QuestEntry

/**
 * Turns one creature's `note` properties into FR-32's quest log (docs/design/18-table-pack.md
 * decisions 13–16).
 *
 * Pure, like [TrackerEngine], [InventoryEngine] and [ActionEngine], and the fourth engine over the
 * same [CreatureSheet] seam: no I/O, no coroutines, no clock, and the same answer whether the
 * input came from the REST snapshot or the live DDP mirror.
 *
 * ### The discovery rule, and how much of it is *probed* rather than assumed
 *
 * Decision 13, whole: `type:'note' && tags contains 'quest'`. Both halves are probe-established
 * (2026-08-28) rather than inferred from a schema reading:
 *
 *  - notes **do** carry `tags` — inherited from the base `CreaturePropertySchema` as
 *    `Array<String>` defaulting to `[]`, so the field is always present and never null;
 *  - the table **already runs the convention**: all three party sheets carry quest notes tagged
 *    `quest`, named `⚔️ QUEST · <title>`, with a structured `summary.text`, at sheet root.
 *
 * That is the whole reason this is a discovery rule and not a feature request to the operator: the
 * data is already shaped this way, and the app is reading what is there.
 *
 * ### Nothing about the `tags` plumbing needed doing, and that is worth recording
 *
 * The wave checked, because the design asked it to. `MongoMirror` stores each document's `fields`
 * object verbatim — it merges and clears keys but never projects — and `CreatureSheet.fromMirror`
 * partitions documents by their `ancestors`, keeping each `JsonObject` whole. So `tags` arrives
 * untouched on both paths, and `CreatureSheet.strings("tags")` (which `TrackerEngine`'s
 * concentration rule has read since WP4) already knows how to read it. No field was dropped and
 * none had to be plumbed.
 *
 * ### Tolerance is the design, not defensiveness
 *
 * Decision 13 asks for the prefix strip to be *"tolerant: no prefix = name as-is"*, and this file
 * extends the same posture to every part of the convention a person types by hand. A note with the
 * tag and no prefix, no summary, or no description is still a quest and still renders. The one
 * thing that is *not* tolerated is the tag itself: without it there is no quest, because that is
 * the only part of the convention that distinguishes a quest note from the notes a sheet is full
 * of.
 */
object QuestEngine {

    /** The property type the whole rule keys on. */
    private const val TYPE_NOTE = "note"

    /** Decision 13's discovery tag. Matched case-insensitively — see [hasTag]. */
    private const val TAG_QUEST = "quest"

    /** The operator's finished marker (decision 13). Same case-insensitive matching. */
    private const val TAG_CLOSED = "closed"

    /**
     * The name prefix the table types, stripped for display.
     *
     * Carried as a constant with its trailing separator included, so the strip is one literal
     * comparison rather than a regex over an emoji — and so that a note named exactly the prefix
     * and nothing else is recognisable as such (see [titleOf]).
     */
    private const val NAME_PREFIX = "⚔️ QUEST · "

    /**
     * Builds the log, **already ordered** (decision 13: *"Open quests above, sheet order within
     * groups"*, closed ones *"sorted to the BOTTOM, de-emphasized, never hidden"*).
     *
     * Ordering lives here rather than in the UI for [ActionBoard]'s stated reason: one sort, in
     * one place, so no second opinion about the sequence can exist anywhere. `sortedWith` over
     * `(closed, sortOrder)` — `false` sorts before `true` for a Kotlin `Boolean`, so open quests
     * lead by the comparator rather than by a partition-and-concatenate that would need its own
     * comment to explain the order.
     *
     * L-batch [architect ruling]: NOT a third `title` key. `sortedWith` is a stable sort (backed
     * by `Collections.sort`, documented merge-sort semantics), so two quests tied on `(closed,
     * sortOrder)` — the common case, since most tables never set a custom `order` on a note —
     * fall back to `livePropertyList`'s own iteration order, i.e. sheet order, by construction.
     * An alphabetical third key was a second, silent ordering rule nobody asked for: it moved a
     * tied pair by *title* the moment one was renamed, which sheet order never does.
     *
     * `livePropertyList`, not `propertyList`: DiceCloud soft-deletes, and a deleted quest note is
     * still delivered to clients carrying `removed: true` (see [CreatureSheet.livePropertyList]).
     * A log that listed deleted quests would be the exact class of bug that accessor was added for.
     *
     * `inactive` is deliberately **not** filtered, matching [ActionEngine]'s reading of the same
     * question: a note is not a thing that can be switched off in any sense a player would act on,
     * and dropping one for a flag nothing sets here would silently lose a quest.
     */
    fun build(sheet: CreatureSheet): List<QuestEntry> = sheet.livePropertyList
        .filter { it.string("type") == TYPE_NOTE && it.hasTag(TAG_QUEST) }
        .mapNotNull { it.toQuest() }
        .sortedWith(compareBy({ it.closed }, { it.sortOrder }))

    /**
     * One `note` → one quest.
     *
     * `null` only for a note with no `_id`, which cannot happen on either source (`MongoMirror`
     * injects it, and the REST body carries it) and is refused anyway rather than given a
     * synthetic id: the id is what an expanded row keys on, and two rows sharing a minted blank
     * would collapse into one.
     *
     * A note with a **blank name** keeps a blank title rather than being dropped, and that is
     * deliberate: it still has a summary and a description, which is the content, and a quest the
     * table can see on the sheet vanishing from the app because somebody left the name empty is a
     * worse failure than a row with a thin heading.
     */
    private fun JsonObject.toQuest(): QuestEntry? = QuestEntry(
        propertyId = string("_id") ?: return null,
        title = titleOf(string("name").orEmpty()),
        summary = text("summary"),
        description = text("description"),
        closed = hasTag(TAG_CLOSED),
        sortOrder = number("order") ?: 0,
    )

    /**
     * Decision 13's prefix strip — *"strip the `⚔️ QUEST · ` name prefix for display (tolerant: no
     * prefix = name as-is)"*.
     *
     * Two tolerances, and the second one is the one worth having:
     *
     *  - **No prefix** → the name unchanged, which is the design's own wording.
     *  - **Nothing but the prefix** → the name unchanged as well, rather than an empty title. A
     *    note called `⚔️ QUEST · ` is somebody who has not finished typing, and turning that into
     *    a nameless row would hide the fact; leaving the raw name shows exactly what is on the
     *    sheet, which is the thing they need to go and fix.
     *
     * `removePrefix` and not a regex: the prefix is a fixed literal including its separator, and a
     * pattern over an emoji is a place for a surrogate-pair mistake to live.
     */
    private fun titleOf(name: String): String =
        name.removePrefix(NAME_PREFIX).trim().ifBlank { name }

    /**
     * Whether the property's `tags` array contains [tag], ignoring case.
     *
     * Case-insensitive because the array holds whatever a person typed into the sheet's tag field,
     * and `Quest` is the same intent as `quest`. That is [DamageDefense]'s reading of the same
     * kind of free-text array and `TrackerEngine.concentrationSource`'s of the same actual field —
     * a third site agreeing rather than a new rule.
     *
     * Exact-match per element, though, not a substring: a note tagged `questgiver` is about a
     * quest without being one, and a substring test would put it in the log with no way for the
     * author to say otherwise.
     */
    private fun JsonObject.hasTag(tag: String): Boolean =
        strings("tags").any { it.trim().equals(tag, ignoreCase = true) }

    /**
     * A field that is either a plain string or one of DiceCloud's wrapper objects — `summary` and
     * `description` are inline-calculation objects (`{text, value, …}`) on every sheet probed.
     *
     * The same reader [ActionEngine] uses for the same two fields, deliberately duplicated rather
     * than shared: it is nine lines, both copies are private to their engine, and the shared
     * version would have to live in `CreatureSheet`'s companion beside the primitive readers —
     * which are about *types* (`string`, `number`, `decimal`), while this is about two particular
     * DiceCloud wrapper shapes. `text` before `value` because where both exist `text` is the
     * rendered string and `value` is the un-substituted source. Blank normalises to `null` so
     * "absent reads as absent" holds without every caller repeating a `takeIf`.
     */
    private fun JsonObject.text(key: String): String? = when (val element = this[key]) {
        is JsonObject -> element.string("text")
            ?: element.string("value")
            ?: element.number("value")?.toString()
        else -> string(key)
    }?.takeIf { it.isNotBlank() }
}
