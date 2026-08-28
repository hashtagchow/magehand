package com.hashtagchow.magehand.core.data.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-32's quest log (docs/design/18-table-pack.md decisions 13–16).
 *
 * ### What is being pinned is a *convention*, not a schema
 *
 * DiceCloud has no quest type. What the table has is a habit — probed 2026-08-28 and live on all
 * three party sheets — of writing quests as `note` properties tagged `quest`, named
 * `⚔️ QUEST · <title>`, with a structured `summary.text`. A schema can be relied on; a habit is
 * something people type, so most of the tests below are about what happens when somebody types it
 * a little differently. Each of those tolerances is a decision (decision 13's *"tolerant: no
 * prefix = name as-is"*, generalised), not defensive padding, and each one has a failure it
 * prevents: a quest that is on the sheet and not in the app, which nobody would report as a bug
 * because it looks exactly like a quest that was never written down.
 *
 * The one thing deliberately **not** tolerated is a missing `quest` tag — that is the only part of
 * the convention distinguishing a quest note from the notes a sheet is full of.
 */
class QuestEngineTest {

    private val creatureId = "c1"

    /** The prefix the table types. Written out once, here, so a test can strip it deliberately. */
    private val prefix = "⚔️ QUEST · "

    /**
     * A note in the shape the probe recorded: a scalar `name`, and `summary`/`description` as
     * inline-calculation **objects** with the rendered string under `text`.
     *
     * The object shape is the trap this fixture exists to carry. MageHand learned it once already
     * — `description` on an action is an object, not a string — and a fixture that used plain
     * strings here would pass against a reader that only handles strings, which is precisely the
     * reader a second implementation writes first.
     */
    private fun note(
        id: String,
        name: String,
        tags: List<String> = listOf("quest"),
        summary: String? = "QUEST · Giver: Sildar · Reward: 50gp · Status: OPEN",
        description: String? = "Find Gundren Rockseeker, last seen on the Triboar Trail.",
        order: Int = 10,
    ): String {
        val tagJson = tags.joinToString(",") { "\"$it\"" }
        val summaryJson = summary?.let { ""","summary":{"text":"$it","value":"$it"}""" }.orEmpty()
        val descriptionJson = description?.let { ""","description":{"text":"$it","value":"$it"}""" }.orEmpty()
        return """{"_id":"$id","type":"note","name":"$name","tags":[$tagJson],
                   "order":$order$summaryJson$descriptionJson}"""
    }

    private fun sheetOf(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    // --- decision 13: discovery ---------------------------------------------

    @Test
    fun `a quest-tagged note is discovered, prefix stripped`() {
        val quests = QuestEngine.build(sheetOf(note("n1", "${prefix}Find Gundren")))

        val quest = quests.single()
        assertEquals("n1", quest.propertyId)
        assertEquals("Find Gundren", quest.title)
        assertEquals(
            "the structured summary renders as-is — the Giver/Reward parse is the design's call",
            "QUEST · Giver: Sildar · Reward: 50gp · Status: OPEN",
            quest.summary,
        )
        assertEquals(
            "Find Gundren Rockseeker, last seen on the Triboar Trail.",
            quest.description,
        )
        assertFalse(quest.closed)
        assertTrue(quest.hasDetail)
    }

    /**
     * An untagged note is not a quest, and neither is a note tagged something that merely
     * *contains* the word.
     *
     * The substring case is the one worth having: a note tagged `questgiver` is *about* a quest
     * without being one, and a `contains` test would put it in the log with no way for the author
     * to say otherwise. Exact match per element, case-insensitively.
     */
    @Test
    fun `only the quest tag counts, and it is matched exactly`() {
        val quests = QuestEngine.build(
            sheetOf(
                note("plain", "Shopping list", tags = emptyList()),
                note("near", "Sildar Hallwinter", tags = listOf("questgiver")),
                note("yes", "${prefix}Find Gundren", tags = listOf("Quest")),
                note("also", "${prefix}Wyvern Tor", tags = listOf("lore", "QUEST")),
            ),
        )

        assertEquals(listOf("yes", "also"), quests.map { it.propertyId })
    }

    /** A property of another type carrying the tag is not a note and is not a quest. */
    @Test
    fun `a non-note property with the quest tag is ignored`() {
        val quests = QuestEngine.build(
            sheetOf(
                """{"_id":"item","type":"item","name":"Quest Map","tags":["quest"],"quantity":1}""",
            ),
        )

        assertTrue(quests.isEmpty())
    }

    /**
     * DiceCloud **soft-deletes**, and delivers the deleted document to clients anyway.
     *
     * A log that listed deleted quests is exactly the class of bug `CreatureSheet.livePropertyList`
     * was added for, and it is invisible on a fresh sheet — you only meet it after somebody has
     * tidied up.
     */
    @Test
    fun `a soft-deleted quest note is not in the log`() {
        val quests = QuestEngine.build(
            sheetOf(
                """{"_id":"gone","type":"note","name":"${prefix}Cancelled","tags":["quest"],
                    "removed":true,"order":1}""",
                note("live", "${prefix}Still on"),
            ),
        )

        assertEquals(listOf("live"), quests.map { it.propertyId })
    }

    // --- decision 13: ordering ----------------------------------------------

    /**
     * *"`closed` tag = finished → sorted to the BOTTOM, de-emphasized, **never hidden**"*, with
     * sheet order within each group.
     *
     * Both halves matter and only one is obvious. Closed-at-the-bottom is the visible rule; *never
     * hidden* is the one a later "tidy the log" instinct would break, and decision 13's reason is
     * quoted where it belongs: a table wants its history.
     */
    @Test
    fun `open quests sort above closed ones, in sheet order within each group`() {
        val quests = QuestEngine.build(
            sheetOf(
                note("c-late", "${prefix}Redbrand hideout", tags = listOf("quest", "closed"), order = 40),
                note("o-late", "${prefix}Wyvern Tor", order = 30),
                note("c-early", "${prefix}Escort the wagon", tags = listOf("closed", "quest"), order = 10),
                note("o-early", "${prefix}Find Gundren", order = 20),
            ),
        )

        assertEquals(listOf("o-early", "o-late", "c-early", "c-late"), quests.map { it.propertyId })
        assertEquals(listOf(false, false, true, true), quests.map { it.closed })
        assertEquals("closed quests are present, not filtered", 4, quests.size)
    }

    /**
     * L-batch [architect ruling]: two quests tied on `(closed, order)` — the common case, since
     * most tables never set a custom `order` on a note — keep the SHEET's order, not an
     * alphabetical one. `"Zephyr"` sorts before `"Alabaster"` here precisely because the fixture
     * lists the properties in that sequence; a third `title` comparator key would have flipped
     * them, which is the bug this pins against regressing.
     */
    @Test
    fun `quests tied on order keep sheet order, not alphabetical`() {
        val quests = QuestEngine.build(
            sheetOf(
                note("q-zephyr", "${prefix}Zephyr's task", order = 5),
                note("q-alabaster", "${prefix}Alabaster's task", order = 5),
            ),
        )

        assertEquals(listOf("q-zephyr", "q-alabaster"), quests.map { it.propertyId })
        assertEquals(listOf("Zephyr's task", "Alabaster's task"), quests.map { it.title })
    }

    // --- decision 13: tolerance ---------------------------------------------

    /**
     * *"tolerant: no prefix = name as-is"* — and the second tolerance, which the design does not
     * spell out but which follows from the same reasoning.
     *
     * A note named **only** the prefix is somebody who has not finished typing. Stripping it to a
     * blank title would hide that; keeping the raw name shows exactly what is on the sheet, which
     * is the thing they need to go and fix.
     */
    @Test
    fun `the prefix strip is tolerant in both directions`() {
        fun titleOf(name: String) = QuestEngine.build(sheetOf(note("n", name))).single().title

        assertEquals("Find Gundren", titleOf("${prefix}Find Gundren"))
        assertEquals("a note with no prefix keeps its name", "Find Gundren", titleOf("Find Gundren"))
        assertEquals("nothing but the prefix is not a blank quest", prefix.trim(), titleOf(prefix).trim())
        assertEquals(
            "a prefix in the middle is not a prefix",
            "The ${prefix}thing",
            titleOf("The ${prefix}thing"),
        )
    }

    /**
     * A quest with no summary, no description, or neither is still a quest.
     *
     * The `hasDetail` assertion is the one with a consequence: it is what decides whether the row
     * takes a tap, and a tappable row that reveals nothing is worse than a row that is plainly
     * just text.
     */
    @Test
    fun `a quest with no summary or description still renders`() {
        val bare = QuestEngine.build(
            sheetOf(note("n", "${prefix}Sparse", summary = null, description = null)),
        ).single()

        assertEquals("Sparse", bare.title)
        assertNull(bare.summary)
        assertNull(bare.description)
        assertFalse("nothing to expand, so nothing to tap", bare.hasDetail)

        val summaryOnly = QuestEngine.build(
            sheetOf(note("n", "${prefix}Half", description = null)),
        ).single()
        assertFalse(summaryOnly.hasDetail)
        assertEquals("QUEST · Giver: Sildar · Reward: 50gp · Status: OPEN", summaryOnly.summary)
    }

    /**
     * `summary` and `description` are accepted as **plain strings** as well as as wrapper objects.
     *
     * The wrapper is what the live sheets carry, and the plain string is what a hand-written or
     * imported note may carry — DiceCloud is not uniform about this and neither reader in the app
     * pretends it is (`ActionEngine` has the same tolerance for the same two fields).
     */
    @Test
    fun `summary and description are read from a plain string too`() {
        val quest = QuestEngine.build(
            sheetOf(
                """{"_id":"n","type":"note","name":"${prefix}Plain","tags":["quest"],
                    "summary":"A short line","description":"A longer one","order":1}""",
            ),
        ).single()

        assertEquals("A short line", quest.summary)
        assertEquals("A longer one", quest.description)
    }

    /** A blank field is an absence, not an empty line in the sheet. */
    @Test
    fun `a blank summary reads as absent`() {
        val quest = QuestEngine.build(
            sheetOf(
                """{"_id":"n","type":"note","name":"${prefix}Blank","tags":["quest"],
                    "summary":{"text":"   "},"order":1}""",
            ),
        ).single()

        assertNull(quest.summary)
    }

    /**
     * A quest note with an empty **name** still renders, thinly.
     *
     * It still has a summary and a description, which is the content. A quest the table can see on
     * the sheet vanishing from the app because somebody left the name empty is a worse failure
     * than a row with a thin heading — and the row is still tappable, so the text is reachable.
     */
    @Test
    fun `a nameless quest note is kept`() {
        val quest = QuestEngine.build(sheetOf(note("n", ""))).single()

        assertEquals("", quest.title)
        assertTrue(quest.hasDetail)
    }

    // --- decision 14: the gate ----------------------------------------------

    /**
     * The empty list is the discovery gate: *"present only when ≥1 quest note exists"*.
     *
     * Asserted on a sheet that has notes but no quests, rather than on an empty sheet, because
     * that is the sheet every non-quest-running table has and the one where a too-loose predicate
     * would put the wrong button in the app bar.
     */
    @Test
    fun `a sheet with notes but no quests produces an empty log`() {
        val quests = QuestEngine.build(
            sheetOf(
                note("n1", "Shopping list", tags = emptyList()),
                note("n2", "Backstory", tags = listOf("lore")),
            ),
        )

        assertTrue(quests.isEmpty())
    }
}
