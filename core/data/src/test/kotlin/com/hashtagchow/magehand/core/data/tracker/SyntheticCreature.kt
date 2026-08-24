package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Two or more creatures, invented rather than captured.
 *
 * [Fixtures] holds exactly one creature and *checks* that it does — which is right for every
 * assertion about a real sheet, and useless for the one question the DM dashboard raises:
 * **what happens when several creatures' documents live in the same mirror at once?** No
 * capture can answer that (a `singleCharacter` body is one creature by definition), so the
 * inputs are built here.
 *
 * Synthetic also means these tests run in a public clone, where the capture is absent and
 * every fixture-coupled assertion is skipped. A partitioning defect is not the kind of thing
 * that should only be caught on a machine that has the private capture on it.
 *
 * The shape is the capture's shape, verified against it rather than guessed:
 * - every `creatureProperties` document carries `ancestors`, and exactly one entry has
 *   `collection: "creatures"` — that entry's `id` is the owning creature (573/573 in the
 *   capture);
 * - the `creatureVariables` document is keyed by its own `_id`, which is **not** the creature
 *   id, and names its creature in `_creatureId`.
 */
object SyntheticCreature {

    /** The `creatures` document. */
    fun creature(creatureId: String, name: String, owner: String = "owner-1"): JsonObject =
        buildJsonObject {
            put("_id", creatureId)
            put("name", name)
            put("owner", owner)
        }

    /**
     * An `attribute` property with `variableName: "hitPoints"` — what `TrackerEngine` searches
     * for when it fills [com.hashtagchow.magehand.core.model.TrackerBoard.hp].
     */
    fun hitPoints(creatureId: String, total: Int, damage: Int = 0): JsonObject =
        property(creatureId, "hp") {
            put("type", "attribute")
            put("attributeType", "healthBar")
            put("variableName", "hitPoints")
            put("name", "Hit Points")
            put("total", total)
            put("damage", damage)
            put("value", total - damage)
        }

    /** An `attribute` of `attributeType: "resource"` — a tracker row below the health bar. */
    fun resource(creatureId: String, name: String, total: Int): JsonObject =
        property(creatureId, "res-$name") {
            put("type", "attribute")
            put("attributeType", "resource")
            put("variableName", name)
            put("name", name)
            put("total", total)
            put("value", total)
            put("reset", "longRest")
        }

    /** The one fat `creatureVariables` document. Its `_id` is its own, not the creature's. */
    fun variables(creatureId: String): JsonObject = buildJsonObject {
        put("_id", "vars-$creatureId")
        put("_creatureId", creatureId)
    }

    /**
     * One whole creature: the creature document, an HP attribute, one named resource and the
     * variables document.
     */
    fun sheet(
        creatureId: String,
        name: String,
        hitPoints: Int,
        damage: Int = 0,
        resourceName: String = "$name Dice",
        resourceTotal: Int = 1,
    ): CreatureSheet = CreatureSheet(
        properties = listOf(
            hitPoints(creatureId, hitPoints, damage),
            resource(creatureId, resourceName, resourceTotal),
        ).associateBy { it.id() },
        creature = creature(creatureId, name),
        variables = variables(creatureId),
    )

    /** The mirror shape — `Map<collection, Map<_id, doc>>` — holding every given sheet at once. */
    fun mirrorOf(vararg sheets: Pair<String, CreatureSheet>): Map<String, Map<String, JsonObject>> =
        buildMap {
            put(
                CreatureSheet.CREATURE_PROPERTIES,
                sheets.fold(emptyMap()) { acc, (_, sheet) -> acc + sheet.properties },
            )
            put(
                CreatureSheet.CREATURES,
                sheets.mapNotNull { (id, sheet) -> sheet.creature?.let { id to it } }.toMap(),
            )
            put(
                CreatureSheet.CREATURE_VARIABLES,
                sheets.mapNotNull { (_, sheet) -> sheet.variables?.let { it.id() to it } }.toMap(),
            )
        }

    /**
     * A property document with the tree fields the server puts on every one of them.
     *
     * `ancestors` is the partition key: DiceCloud denormalizes each property's whole ancestry
     * into it, and the `creatures` entry is the owner.
     */
    private fun property(
        creatureId: String,
        suffix: String,
        build: JsonObjectBuilder.() -> Unit,
    ): JsonObject = buildJsonObject {
        put("_id", "$creatureId-$suffix")
        putJsonArray("ancestors") {
            add(
                buildJsonObject {
                    put("collection", CreatureSheet.CREATURES)
                    put("id", creatureId)
                },
            )
        }
        putJsonObject("parent") {
            put("collection", CreatureSheet.CREATURES)
            put("id", creatureId)
        }
        put("order", 0)
        build()
    }

    /** A property document with **no** `ancestors` at all — the shape the wire has never shown. */
    fun propertyWithoutAncestry(id: String, total: Int): JsonObject = buildJsonObject {
        put("_id", id)
        put("type", "attribute")
        put("attributeType", "healthBar")
        put("variableName", "hitPoints")
        put("name", "Hit Points")
        put("total", total)
        put("damage", 0)
        put("value", total)
        put("order", 0)
    }

    private fun JsonObject.id(): String = string("_id").orEmpty()
}
