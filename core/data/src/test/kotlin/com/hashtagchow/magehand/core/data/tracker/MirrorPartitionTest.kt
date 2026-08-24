package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CreatureSheet.fromMirror] against a mirror holding **more than one creature**.
 *
 * ### Why this file exists
 *
 * The DDP mirror is per *connection*, not per creature: `DdpCreatureFeed.documents` hands back
 * `client.mirror.documentsFlow(collection)`, and `DefaultOpenCharacterFactory` builds every
 * session on the account's one `DdpClient`. So the moment a second `singleCharacter`
 * subscription lands — which is the DM dashboard's entire premise, six of them at once — the
 * `creatureProperties` map is the **union** of every subscribed creature's documents.
 *
 * `fromMirror` took that union and passed it through whole. Every card's `CreatureSheet`
 * therefore held all six creatures' properties, and `TrackerEngine.build`'s
 * `properties.firstNotNullOfOrNull { healthAttribute(...) }` picked whichever `hitPoints`
 * attribute happened to sit first in the merged map — **the same one for all six cards**. That
 * is the shipping defect: six cards rendering byte-identical stats belonging to whichever
 * creature's `added` frames arrived first, drifting between opens as that order changes.
 *
 * Everything downstream inherited it. The resource rows, items, toggles, defenses and rolls
 * were all the six-way union, and `captureSnapshot` serialized that union into *each*
 * creature's Room snapshot — so the contamination outlived the screen.
 *
 * The whole suite missed it because nothing could express a shared mirror: `FakeCreatureFeed`
 * gave every session a private collection map, and [Fixtures] holds exactly one creature and
 * asserts that it does. Both are fixed — see [SyntheticCreature] and `FakeMirror`.
 */
class MirrorPartitionTest {

    private val alpha = "creature-alpha"
    private val bravo = "creature-bravo"

    private val alphaSheet = SyntheticCreature.sheet(alpha, "Alpha", hitPoints = 20)
    private val bravoSheet = SyntheticCreature.sheet(bravo, "Bravo", hitPoints = 7)

    private val mirror = SyntheticCreature.mirrorOf(alpha to alphaSheet, bravo to bravoSheet)

    /**
     * The device signature, at its source: two cards, one mirror, identical numbers.
     *
     * Asserted as *inequality* first because that is what the tablet showed — uniform stats
     * across every card — and an assertion that only checked Alpha's total would have passed
     * on the merged soup whenever Alpha's documents happened to arrive first.
     */
    @Test
    fun `two creatures in one mirror produce two different boards`() {
        val alphaBoard = TrackerEngine.build(CreatureSheet.fromMirror(mirror, alpha))
        val bravoBoard = TrackerEngine.build(CreatureSheet.fromMirror(mirror, bravo))

        assertEquals("Alpha's card must show Alpha's hit points", 20, alphaBoard.hp?.total)
        assertEquals("Bravo's card must show Bravo's hit points", 7, bravoBoard.hp?.total)
        assertNotEquals(
            "both cards reported the same HP row — the shared-mirror contamination signature",
            alphaBoard.hp,
            bravoBoard.hp,
        )
    }

    /** The same, in the other direction: neither sheet may carry the other's documents. */
    @Test
    fun `a sheet built from a shared mirror holds only its own creature's properties`() {
        val sheet = CreatureSheet.fromMirror(mirror, alpha)

        assertEquals(alphaSheet.properties.keys, sheet.properties.keys)
        assertTrue(
            "Bravo's properties leaked into Alpha's sheet: " +
                (sheet.properties.keys intersect bravoSheet.properties.keys),
            (sheet.properties.keys intersect bravoSheet.properties.keys).isEmpty(),
        )
    }

    /**
     * The union reached the tracker rows too, not only the health bar.
     *
     * Pinned separately from the HP assertion because HP is found with `firstNotNullOfOrNull`
     * (one wrong row) while the rows are found with `mapNotNull` (every wrong row), so the two
     * fail in visibly different ways and a fix could plausibly address one and not the other.
     */
    @Test
    fun `resource rows are not the union of every subscribed creature`() {
        val board = TrackerEngine.build(CreatureSheet.fromMirror(mirror, alpha))

        assertEquals(listOf("Alpha Dice"), board.resources.map { it.name })
    }

    /**
     * `creatureVariables` is keyed by its own `_id`, so the old `values.firstOrNull()` handed
     * every session whichever creature's variables document was first in the map — and
     * `captureSnapshot` then wrote it into that creature's cached snapshot.
     */
    @Test
    fun `the variables document belongs to the named creature`() {
        assertEquals(
            SyntheticCreature.variables(alpha),
            CreatureSheet.fromMirror(mirror, alpha).variables,
        )
        assertEquals(
            SyntheticCreature.variables(bravo),
            CreatureSheet.fromMirror(mirror, bravo).variables,
        )
    }

    /**
     * A named creature the mirror does not hold answers **nothing**, never somebody else.
     *
     * This is a live shape, not a hypothetical: `characterList` publishes every creature at the
     * table into the same `creatures` collection, so during the window before a card's own
     * `singleCharacter` sub lands, `creatures` is full of other people's characters and the old
     * `?: creatures.values.firstOrNull()` fallback returned one of them. `sheet.creatureId` is
     * what `InventoryEngine` uses to decide where a newly added item hangs, so the fallback put
     * a write target on the wrong character's sheet.
     */
    @Test
    fun `naming a creature the mirror lacks yields no creature at all`() {
        val sheet = CreatureSheet.fromMirror(mirror, "creature-charlie")

        assertNull("a card must never adopt another creature's document", sheet.creature)
        assertNull(sheet.creatureId)
        assertTrue(sheet.properties.isEmpty())
    }

    /**
     * The unnamed case is untouched: a caller that does not say which creature it means still
     * gets the whole mirror and the first creature, exactly as before.
     *
     * `DdpCreatureFeed.sheet()` and `fromSnapshotJson` both rely on it, and a REST body is one
     * creature by construction, so there is nothing to partition there.
     */
    @Test
    fun `an unnamed creature still gets the whole mirror`() {
        val sheet = CreatureSheet.fromMirror(mirror, creatureId = null)

        assertEquals(
            alphaSheet.properties.keys + bravoSheet.properties.keys,
            sheet.properties.keys,
        )
    }

    /**
     * The direction the filter fails in, chosen deliberately and pinned so it cannot drift.
     *
     * A property is dropped when its ancestry names a **different** creature — not when its
     * ancestry is merely unreadable. Every one of the capture's 573 properties carries an
     * `ancestors` entry naming its creature, and the field is part of DiceCloud's document
     * rather than of the transport, so this branch has no live example. It exists because the
     * repo holds no field-level record of a `creatureProperties` `added` frame (WP2 §3.5's
     * live probe compares ids, not fields), and the cost of being wrong is asymmetric: a
     * strict filter meeting a document without ancestry would blank *every* tracker in the
     * app, while this one degrades to exactly the pre-fix behaviour for that document alone.
     */
    @Test
    fun `a property naming no creature is kept rather than dropped`() {
        val orphan = SyntheticCreature.propertyWithoutAncestry("orphan-hp", total = 99)
        val withOrphan = mirror + mapOf(
            CreatureSheet.CREATURE_PROPERTIES to
                mirror.getValue(CreatureSheet.CREATURE_PROPERTIES) + mapOf("orphan-hp" to orphan),
        )

        val sheet = CreatureSheet.fromMirror(withOrphan, alpha)

        assertTrue(
            "a document whose provenance cannot be read must not be silently discarded",
            "orphan-hp" in sheet.properties,
        )
        assertEquals(alphaSheet.properties.keys + "orphan-hp", sheet.properties.keys)
    }

    /**
     * FIX (reviewer finding d): an exact `_creatureId` match must win over a null-keyed
     * `creatureVariables` document regardless of which one iteration reaches first — the null
     * -keyed leniency is a substitute for a missing match, never a competitor to one.
     */
    @Test
    fun `an exact variables match wins over a null-keyed one even when the null-keyed doc arrives first`() {
        val nullKeyed = buildJsonObject { put("_id", "vars-none") }
        val exactMatch = SyntheticCreature.variables(alpha)

        // LinkedHashMap iteration order is insertion order, so the null-keyed doc is seen first.
        val bothOrders = linkedMapOf(
            "vars-none" to nullKeyed,
            "vars-$alpha" to exactMatch,
        )

        val sheet = CreatureSheet.fromMirror(
            properties = emptyMap(),
            variables = bothOrders,
            creatureId = alpha,
        )

        assertEquals(
            "the exact match must win even though the null-keyed doc iterates first",
            exactMatch,
            sheet.variables,
        )
    }

    /** The other half of the same pin: absent an exact match, the null-keyed doc is used. */
    @Test
    fun `a null-keyed variables doc is used when no exact match exists`() {
        val nullKeyed = buildJsonObject { put("_id", "vars-none") }

        val sheet = CreatureSheet.fromMirror(
            properties = emptyMap(),
            variables = linkedMapOf("vars-none" to nullKeyed),
            creatureId = alpha,
        )

        assertEquals(nullKeyed, sheet.variables)
    }

    /**
     * FIX (reviewer finding c): [CreatureSheet.fromSnapshotJson] must route through the same
     * partition logic as [CreatureSheet.fromMirror] — a pre-fix contaminated Room snapshot must
     * heal on re-render, not keep reproducing the union it once cached.
     */
    @Test
    fun `fromSnapshotJson partitions a two-creature snapshot body the same way fromMirror does`() {
        val body = twoCreatureSnapshotBody()

        val sheet = CreatureSheet.fromSnapshotJson(body, alpha)

        assertEquals(alphaSheet.properties.keys, sheet.properties.keys)
        assertTrue(
            "Bravo's properties leaked into Alpha's snapshot-derived sheet",
            (sheet.properties.keys intersect bravoSheet.properties.keys).isEmpty(),
        )
        assertEquals(SyntheticCreature.variables(alpha), sheet.variables)
        assertEquals(alpha, sheet.creatureId)
    }

    /** Regression pin: a one-creature snapshot renders byte-identically to before the fix. */
    @Test
    fun `a one-creature snapshot renders byte-identically whether or not the creature id is given`() {
        val body = buildJsonObject {
            put(CreatureSheet.CREATURES, JsonArray(listOfNotNull(alphaSheet.creature)))
            put(CreatureSheet.CREATURE_PROPERTIES, JsonArray(alphaSheet.properties.values.toList()))
            put(CreatureSheet.CREATURE_VARIABLES, JsonArray(listOfNotNull(alphaSheet.variables)))
        }

        val named = CreatureSheet.fromSnapshotJson(body, alpha)
        val unnamed = CreatureSheet.fromSnapshotJson(body)

        assertEquals(alphaSheet.properties, named.properties)
        assertEquals(alphaSheet.creature, named.creature)
        assertEquals(alphaSheet.variables, named.variables)
        assertEquals(unnamed.properties, named.properties)
        assertEquals(unnamed.creature, named.creature)
        assertEquals(unnamed.variables, named.variables)
    }

    private fun twoCreatureSnapshotBody(): JsonObject = buildJsonObject {
        put(CreatureSheet.CREATURES, JsonArray(listOfNotNull(alphaSheet.creature, bravoSheet.creature)))
        put(
            CreatureSheet.CREATURE_PROPERTIES,
            JsonArray(alphaSheet.properties.values.toList() + bravoSheet.properties.values.toList()),
        )
        put(
            CreatureSheet.CREATURE_VARIABLES,
            JsonArray(listOfNotNull(alphaSheet.variables, bravoSheet.variables)),
        )
    }
}
