package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import com.hashtagchow.magehand.core.ddp.MongoMirror
import com.hashtagchow.magehand.core.model.CharacterSummary

/**
 * One creature's raw state, in whichever form we happen to have it.
 *
 * This is the seam that makes [TrackerEngine] source-agnostic (docs/design/06-offline-and-sync.md
 * §Snapshot lifecycle step 3). Both inputs are shape-compatible because WP2's
 * [MongoMirror] injects `_id` into every mirrored document, exactly like the REST
 * body carries it (docs/verification/WP2.md deviation #6):
 *
 * - [fromSnapshotJson] — the `GET /api/creature/:id` body, `{creatures, creatureProperties,
 *   creatureVariables}` with array values.
 * - [fromMirror] — the live DDP mirror, `Map<id, JsonObject>` per collection.
 *
 * Nothing here interprets DiceCloud semantics; that is [TrackerEngine]'s job.
 */
class CreatureSheet(
    /** Every `creatureProperties` document, keyed by `_id`. */
    val properties: Map<String, JsonObject>,
    /** The `creatures` document, or `null` when the source carried none. */
    val creature: JsonObject? = null,
    /** The single fat `creatureVariables` document, or `null`. */
    val variables: JsonObject? = null,
) {
    val creatureId: String? get() = creature?.string("_id")

    /**
     * **Every** property document, soft-deleted ones included.
     *
     * Deliberately unfiltered: this is the raw source, and [toSnapshotBody] has to round-trip
     * the REST body verbatim or the cached snapshot would silently diverge from what the
     * server sent. Discovery is where the filtering belongs — but that means *every* caller
     * that lists, sums or counts properties has to remember, and the one that forgot is
     * exactly the bug 10 decision 3 sent this wave hunting. Prefer [livePropertyList].
     */
    val propertyList: List<JsonObject> get() = properties.values.toList()

    /**
     * The property documents that still exist — `removed: true` dropped.
     *
     * **DiceCloud soft-deletes.** A deleted property keeps its document, gains `removed: true`
     * and a `removedAt`, and is **still delivered to clients**: the REST body carries it
     * (the live capture holds one), and the 2026-08-19 probe confirmed the subscription can
     * too. Nothing about the transport removes it; only a filter does.
     *
     * This accessor exists so that filter has a *name*. Before it, the rule lived nine times
     * over inside `TrackerEngine`'s private discovery functions — correct in all nine, but
     * invisible to any new consumer reading `propertyList` and reasonably assuming a sheet's
     * properties are the sheet's properties. The inventory tab is the first such consumer and
     * it sums weights, where a stale item is not a cosmetic error but a wrong number.
     *
     * The engine's own rules keep their inline checks rather than being rewritten onto this:
     * they filter `inactive` in the same breath and one of them ([TrackerEngine] toggles)
     * deliberately does not, so folding them together here would change behaviour to save a
     * line. This is the accessor for everything *new*.
     */
    val livePropertyList: List<JsonObject> get() = properties.values.filterNot { it.isTrue(REMOVED) }

    /**
     * True when the sheet holds at least one property that has not been soft-deleted.
     *
     * The distinction from `properties.isNotEmpty()` is load-bearing wherever "does this
     * source have anything in it?" decides between two sources — see `CreatureSession`'s
     * mirror-wins rule. A mirror holding nothing but soft-deleted documents is a mirror with
     * nothing to render, and answering "yes" for it beats a perfectly good cached snapshot
     * and blanks the screen.
     */
    val hasLiveProperties: Boolean get() = properties.values.any { !it.isTrue(REMOVED) }

    /** A named entry from the one fat `creatureVariables` document, or `null`. */
    fun variable(name: String): JsonElement? = variables?.get(name)

    /**
     * The creature as a selector row. [myUserId] decides [CharacterSummary.isOwnedByMe];
     * pass `null` when the caller does not know who is logged in.
     */
    fun summary(myUserId: String? = null): CharacterSummary? {
        val doc = creature ?: return null
        val id = doc.string("_id") ?: return null
        val owner = doc.string("owner").orEmpty()
        return CharacterSummary(
            creatureId = id,
            name = doc.string("name").orEmpty(),
            alignment = doc.string("alignment"),
            gender = doc.string("gender"),
            picture = doc.string("picture") ?: doc.string("avatarPicture"),
            owner = owner,
            isOwnedByMe = myUserId != null && myUserId == owner,
        )
    }

    /**
     * Serializes back to the `GET /api/creature/:id` shape.
     *
     * This is what makes 06-offline-and-sync.md step 2 ("mirror → snapshot refresh on
     * every app-background") cheap: the mirror is already shape-compatible with REST, so
     * the refresh is a re-serialize rather than another 1 MB round trip. Round-tripping
     * through [fromSnapshotJson] is lossless for everything the tracker reads.
     */
    fun toSnapshotBody(): String {
        val root = buildJsonObject {
            put(CREATURES, JsonArray(listOfNotNull(creature)))
            put(CREATURE_PROPERTIES, JsonArray(properties.values.toList()))
            put(CREATURE_VARIABLES, JsonArray(listOfNotNull(variables)))
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    companion object {
        /** Collection names, shared with `singleCharacter`'s DDP publication. */
        const val CREATURES = "creatures"
        const val CREATURE_PROPERTIES = "creatureProperties"
        const val CREATURE_VARIABLES = "creatureVariables"

        /** DiceCloud's soft-delete flag. See [livePropertyList]. */
        const val REMOVED = "removed"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val EMPTY: CreatureSheet = CreatureSheet(emptyMap())

        /**
         * Parses a `GET /api/creature/:id` body.
         *
         * @param creatureId when several creatures are present (never seen live, but the
         *   endpoint's shape allows it), pick this one; otherwise the first is used.
         */
        fun fromSnapshotJson(body: String, creatureId: String? = null): CreatureSheet =
            fromSnapshotJson(json.parseToJsonElement(body) as JsonObject, creatureId)

        fun fromSnapshotJson(root: JsonObject, creatureId: String? = null): CreatureSheet {
            val creatures = root.objects(CREATURES)
            val creature = creatureId?.let { wanted -> creatures.firstOrNull { it.string("_id") == wanted } }
                ?: creatures.firstOrNull()
            return CreatureSheet(
                properties = root.objects(CREATURE_PROPERTIES).associateBy { it.string("_id").orEmpty() }
                    .filterKeys { it.isNotEmpty() },
                creature = creature,
                variables = root.objects(CREATURE_VARIABLES).firstOrNull(),
            )
        }

        /** Builds a sheet from [MongoMirror.snapshot] (or any equivalent per-collection map). */
        fun fromMirror(
            snapshot: Map<String, Map<String, JsonObject>>,
            creatureId: String? = null,
        ): CreatureSheet = fromMirror(
            properties = snapshot[CREATURE_PROPERTIES].orEmpty(),
            creatures = snapshot[CREATURES].orEmpty(),
            variables = snapshot[CREATURE_VARIABLES].orEmpty(),
            creatureId = creatureId,
        )

        fun fromMirror(
            properties: Map<String, JsonObject>,
            creatures: Map<String, JsonObject> = emptyMap(),
            variables: Map<String, JsonObject> = emptyMap(),
            creatureId: String? = null,
        ): CreatureSheet = CreatureSheet(
            properties = properties,
            creature = creatureId?.let { creatures[it] } ?: creatures.values.firstOrNull(),
            variables = variables.values.firstOrNull(),
        )

        private fun JsonObject.objects(key: String): List<JsonObject> =
            (this[key] as? JsonArray)?.filterIsInstance<JsonObject>() ?: emptyList()
    }
}

// ---------------------------------------------------------------------------
// Small JSON readers shared by the sheet and the engine.
//
// DiceCloud fields are not uniformly typed: `spellSlotLevel` and `baseValue` arrive
// as `_calculation` objects with the answer under `value`, while `total`/`value` are
// plain numbers. Everything below tolerates both, plus stringified numbers, and
// returns `null` rather than throwing on a shape we have not seen.
// ---------------------------------------------------------------------------

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? = when (val v = this[key]) {
    is JsonPrimitive -> v.booleanOrNull ?: v.content.toBooleanStrictOrNull()
    else -> null
}

/** `true` only when the field is literally `true`; missing and `null` both mean "no". */
internal fun JsonObject.isTrue(key: String): Boolean = bool(key) == true

/**
 * Reads a numeric field, unwrapping DiceCloud's `_calculation` wrapper
 * (`{"calculation":"3", …, "value":3}`) when it finds one.
 */
internal fun JsonObject.number(key: String): Int? = numberOf(this[key])

private fun numberOf(element: JsonElement?): Int? = when (element) {
    is JsonPrimitive -> element.intOrNull
        ?: element.content.toDoubleOrNull()?.toInt()
    is JsonObject -> numberOf(element["value"])
    else -> null
}

/**
 * The fractional twin of [number], for the one field where truncating to `Int` would
 * invert the meaning: `damageMultiplier.value` is `0.5` for a resistance, and
 * [number] would read that back as `0` — *immune*. Unwraps the same `_calculation`
 * wrapper and tolerates the same stringified numbers.
 */
internal fun JsonObject.decimal(key: String): Double? = decimalOf(this[key])

private fun decimalOf(element: JsonElement?): Double? = when (element) {
    is JsonPrimitive -> element.content.toDoubleOrNull()
    is JsonObject -> decimalOf(element["value"])
    else -> null
}

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?: emptyList()
