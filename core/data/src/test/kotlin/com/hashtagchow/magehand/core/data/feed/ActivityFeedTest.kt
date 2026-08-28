package com.hashtagchow.magehand.core.data.feed

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-25's feed engine (docs/design/16-actions-and-feed.md decisions 8–11).
 *
 * The two things worth pinning here are the ones a UI test could never see: **which** creatures'
 * entries end up in a merged feed, and **in what order**. Both are pure, both have silent failure
 * modes (a foreign creature's entry looks like an entry; a mis-sorted feed looks like a feed),
 * and one of them is the single deliberate exception to the mirror-partition rule.
 */
class ActivityFeedTest {

    private fun log(
        id: String,
        creatureId: String,
        creatureName: String? = "Someone",
        dateMillis: Long? = null,
        content: List<Pair<String?, String?>> = listOf("Short rest" to null),
    ): Pair<String, JsonObject> = id to buildJsonObject {
        put("creatureId", creatureId)
        if (creatureName != null) put("creatureName", creatureName)
        if (dateMillis != null) {
            // The EJSON wire shape: `{"$date": 1787226773294}`.
            put("date", buildJsonObject { put("\$date", dateMillis) })
        }
        put(
            "content",
            buildJsonArray {
                content.forEach { (name, value) ->
                    add(
                        buildJsonObject {
                            if (name != null) put("name", name)
                            if (value != null) put("value", value)
                        },
                    )
                }
            },
        )
    }

    // -----------------------------------------------------------------------
    // Decision 10 — the cross-creature merge, and its boundary
    // -----------------------------------------------------------------------

    /**
     * **The deliberate cross-creature read.** The mirror is per *connection*, so this map is a
     * union over every subscribed creature — and merging it is the feature, not the leak.
     *
     * What keeps it from being `CreatureSheet`'s partition bug is on display here: the merge is
     * filtered to an explicit member set, and every surviving entry still carries its own
     * `creatureId` and `creatureName`. See `ActivityFeedEngine`'s KDoc for the three-part rule.
     */
    @Test
    fun `entries from several creatures merge and each keeps its own attribution`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(
                log("a", "c1", creatureName = "First", dateMillis = 100),
                log("b", "c2", creatureName = "Second", dateMillis = 300),
                log("c", "c1", creatureName = "First", dateMillis = 200),
            ),
            creatureIds = setOf("c1", "c2"),
        )

        assertEquals("newest first, across creatures", listOf("b", "c", "a"), feed.map { it.logId })
        assertEquals(
            "every row is still labelled with the creature it belongs to",
            listOf("Second" to "c2", "First" to "c1", "First" to "c1"),
            feed.map { it.creatureName to it.creatureId },
        )
    }

    /**
     * A creature that is on the socket but not at this table does not appear.
     *
     * This is the filter that makes the union safe. Without it, opening a character screen
     * alongside the DM view would pour that character's log into the DM's panel.
     */
    @Test
    fun `an entry for a creature outside the member set is dropped`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(
                log("mine", "c1", dateMillis = 1),
                log("theirs", "stranger", dateMillis = 2),
            ),
            creatureIds = setOf("c1"),
        )
        assertEquals(listOf("mine"), feed.map { it.logId })
    }

    /**
     * An entry naming **no** creature is dropped — the opposite of `CreatureSheet.belongsTo`'s
     * leniency, deliberately.
     *
     * There, keeping an unattributable property protected a whole tracker from blanking on an
     * inference that might be wrong. Here an unattributable entry has no name to render beside
     * it, and would appear in the panel as an anonymous event from nobody.
     */
    @Test
    fun `an unattributable entry is dropped rather than shown anonymously`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf("orphan" to buildJsonObject { put("content", buildJsonArray { }) }),
            creatureIds = setOf("c1"),
        )
        assertTrue(feed.isEmpty())
    }

    @Test
    fun `an empty member set yields an empty feed rather than everything`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(log("a", "c1"), log("b", "c2")),
            creatureIds = emptySet(),
        )
        assertTrue(feed.isEmpty())
    }

    // -----------------------------------------------------------------------
    // Ordering
    // -----------------------------------------------------------------------

    /**
     * A dateless entry sorts **last**.
     *
     * This is the assertion guarding `compareByDescending(nullsFirst())` — the double negative
     * that reads like a bug and is not. Flipping it to `nullsLast` (the "obvious" fix) puts a
     * malformed row permanently at the top of a panel whose entire ordering claim is recency.
     */
    @Test
    fun `an entry with no timestamp sorts last and does not claim the top`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(
                log("undated", "c1"),
                log("old", "c1", dateMillis = 100),
                log("new", "c1", dateMillis = 900),
            ),
            creatureIds = setOf("c1"),
        )
        assertEquals(listOf("new", "old", "undated"), feed.map { it.logId })
        assertEquals(null, feed.last().dateMillis)
    }

    /**
     * Decision 10's rendered cap. The server caps at 20 per creature, so six creatures can offer
     * 120; the panel takes the newest [ActivityFeedEngine.RENDER_CAP].
     */
    @Test
    fun `the feed is capped at the newest fifty`() {
        val logs = (1..120).associate { log("e$it", "c1", dateMillis = it.toLong()) }
        val feed = ActivityFeedEngine.build(logs, setOf("c1"))

        assertEquals(ActivityFeedEngine.RENDER_CAP, feed.size)
        assertEquals("the cap keeps the NEWEST, not the first fifty found", "e120", feed.first().logId)
        assertEquals("e71", feed.last().logId)
    }

    // -----------------------------------------------------------------------
    // Decision 11 — entry rendering
    // -----------------------------------------------------------------------

    @Test
    fun `content blocks become name and value lines`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(
                log(
                    "a", "c1", dateMillis = 5,
                    content = listOf(
                        "Cure Wounds" to null,
                        "Healing" to "1d4 [3] + 2\n**5**  healing",
                        null to "trailing value only",
                    ),
                ),
            ),
            creatureIds = setOf("c1"),
        )

        val lines = feed.single().lines
        assertEquals(3, lines.size)
        assertEquals("Cure Wounds" to null, lines[0].name to lines[0].value)
        assertEquals("Healing", lines[1].name)
        assertEquals(
            "markdown is carried as text in v1 and stripped by the renderer, not here",
            "1d4 [3] + 2\n**5**  healing",
            lines[1].value,
        )
        assertEquals(null to "trailing value only", lines[2].name to lines[2].value)
    }

    /** A block with neither field would render as a blank line inside an entry. */
    @Test
    fun `an empty content block is dropped`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(log("a", "c1", dateMillis = 1, content = listOf(null to null, "Kept" to null))),
            creatureIds = setOf("c1"),
        )
        assertEquals(listOf("Kept"), feed.single().lines.map { it.name })
    }

    /**
     * L4: a log document with no `creatureName` field yields `null`, never the empty string.
     *
     * The engine used to `.orEmpty()` this field, so a nameless doc reached `DmFeedPanel` as
     * `""` and rendered a blank header — silent, not a crash, and easy to miss in review. The
     * fallback label belongs to the UI layer (`stringResource`, matching how a `null` timestamp
     * is resolved); this pins that the engine hands the UI an honest absence to resolve, rather
     * than inventing a blank string that looks like a value.
     */
    @Test
    fun `an entry with no creatureName is null, not the empty string`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(log("a", "c1", creatureName = null, dateMillis = 1)),
            creatureIds = setOf("c1"),
        )
        assertEquals(null, feed.single().creatureName)
    }

    /** A document with no `content` array at all is still an entry — a header with no body. */
    @Test
    fun `an entry with no content array survives with no lines`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(
                "a" to buildJsonObject {
                    put("creatureId", "c1")
                    put("creatureName", "Someone")
                },
            ),
            creatureIds = setOf("c1"),
        )
        assertEquals(1, feed.size)
        assertTrue(feed.single().lines.isEmpty())
    }

    /** The EJSON `{"$date": …}` wrapper is decoded to epoch millis. */
    @Test
    fun `the ejson date wrapper is decoded`() {
        val feed = ActivityFeedEngine.build(
            logs = mapOf(log("a", "c1", dateMillis = 1787226773294L)),
            creatureIds = setOf("c1"),
        )
        assertEquals(1787226773294L, feed.single().dateMillis)
    }

    /** Same input, same feed — the engine is pure, like the three beside it. */
    @Test
    fun `the engine is deterministic`() {
        val logs = mapOf(log("a", "c1", dateMillis = 2), log("b", "c1", dateMillis = 1))
        assertEquals(
            ActivityFeedEngine.build(logs, setOf("c1")),
            ActivityFeedEngine.build(logs, setOf("c1")),
        )
    }
}
