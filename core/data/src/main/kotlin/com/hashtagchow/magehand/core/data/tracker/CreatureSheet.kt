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

        /**
         * A property's denormalized ancestry. Its `creatures` entry is the partition key that
         * separates one card's documents from another's in the shared mirror — see [fromMirror].
         */
        const val ANCESTORS = "ancestors"

        /**
         * The creature a `creatureVariables` document belongs to.
         *
         * Needed because that document's `_id` is its **own**, not the creature's, so the
         * collection cannot be indexed by creature the way [CREATURES] can.
         */
        const val VARIABLES_CREATURE_ID = "_creatureId"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        val EMPTY: CreatureSheet = CreatureSheet(emptyMap())

        /**
         * Parses a `GET /api/creature/:id` body.
         *
         * Routed through the same [fromMirror] partition logic a shared mirror needs, so a
         * pre-fix Room snapshot that once cached a contaminated union heals on re-render instead
         * of reproducing it forever: when [creatureId] is given, `properties`/`variables` are
         * partitioned exactly as [fromMirror] partitions them and an absent creature yields
         * `null`, never a first-found fallback. Omitting [creatureId] keeps the REST body's own
         * contract — it is one creature by construction (never seen live, but the endpoint's
         * shape allows more) — and unnamed callers still get the whole body and its first
         * creature.
         */
        fun fromSnapshotJson(body: String, creatureId: String? = null): CreatureSheet =
            fromSnapshotJson(json.parseToJsonElement(body) as JsonObject, creatureId)

        fun fromSnapshotJson(root: JsonObject, creatureId: String? = null): CreatureSheet = fromMirror(
            properties = root.objects(CREATURE_PROPERTIES).associateBy { it.string("_id").orEmpty() }
                .filterKeys { it.isNotEmpty() },
            creatures = root.objects(CREATURES).associateBy { it.string("_id").orEmpty() }
                .filterKeys { it.isNotEmpty() },
            variables = root.objects(CREATURE_VARIABLES).associateBy { it.string("_id").orEmpty() }
                .filterKeys { it.isNotEmpty() },
            creatureId = creatureId,
        )

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

        /**
         * ### The mirror is per *connection*, so it is not one creature's
         *
         * `DdpCreatureFeed.documents` returns `client.mirror.documentsFlow(collection)` — the
         * one `MongoMirror` owned by the account's single `DdpClient` — and
         * `DefaultOpenCharacterFactory` builds every session on that same client. One
         * `singleCharacter` subscription therefore fills the same maps as the next, and
         * `characterList` pours every creature at the table into `creatures` besides. The maps
         * handed in here are a **union**, and only [creatureId] says which part of it is ours.
         *
         * This used to pass the union through whole, which was invisible while exactly one
         * character was ever open and became the DM dashboard's defining bug the moment six
         * were: every card's sheet held all six creatures' properties, so
         * `TrackerEngine.build`'s `firstNotNullOfOrNull { healthAttribute(…) }` found the same
         * `hitPoints` row for all of them and six cards rendered byte-identical stats —
         * belonging to whichever creature's `added` frames happened to land first, and drifting
         * between opens as that order changed. `captureSnapshot` then wrote the union into each
         * creature's Room snapshot, carrying it past the end of the screen.
         *
         * ### How a property is attributed, and which way it fails
         *
         * DiceCloud denormalizes each property's whole ancestry into `ancestors`; the entry
         * whose `collection` is `creatures` names the owner, and every one of the capture's 573
         * properties carries one.
         *
         * A property is dropped when its ancestry names a **different** creature — not when its
         * ancestry cannot be read at all. That asymmetry is deliberate. The repo holds no
         * field-level record of a `creatureProperties` `added` frame (WP2 §3.5's live probe
         * compares ids, not fields), so "the wire always carries `ancestors`" is an inference
         * from the REST capture and from the server rejecting writes to that path. If the
         * inference is ever wrong, a strict filter would blank *every* tracker in the app,
         * while this rule degrades to the old behaviour for the unattributable document alone.
         * `MirrorPartitionTest` pins the direction so it cannot be tightened by accident.
         *
         * A named creature that is simply absent yields **no** creature rather than the first
         * one to hand: during the window before a card's own subscription lands, `creatures` is
         * full of other people's characters, and `sheet.creatureId` is what `InventoryEngine`
         * uses to decide where a newly added item hangs.
         */
        fun fromMirror(
            properties: Map<String, JsonObject>,
            creatures: Map<String, JsonObject> = emptyMap(),
            variables: Map<String, JsonObject> = emptyMap(),
            creatureId: String? = null,
        ): CreatureSheet = if (creatureId == null) {
            // The caller did not say which creature it means, so there is nothing to partition
            // on — `fromSnapshotJson`'s REST body is one creature by construction anyway.
            CreatureSheet(
                properties = properties,
                creature = creatures.values.firstOrNull(),
                variables = variables.values.firstOrNull(),
            )
        } else {
            CreatureSheet(
                properties = properties.filterValues { it.belongsTo(creatureId) },
                creature = creatures[creatureId],
                variables = variables.values.variablesFor(creatureId),
            )
        }

        /** The `creatures` entry of a property's denormalized ancestry, or `null`. */
        private fun JsonObject.creatureAncestorId(): String? =
            (this[ANCESTORS] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it.string("collection") == CREATURES }
                ?.string("id")

        /** True unless this property's ancestry names some **other** creature. */
        private fun JsonObject.belongsTo(creatureId: String): Boolean =
            creatureAncestorId().let { it == null || it == creatureId }

        /** True only when this `creatureVariables` document names exactly [creatureId]. */
        private fun JsonObject.namesCreature(creatureId: String): Boolean =
            string(VARIABLES_CREATURE_ID) == creatureId

        /** True when this `creatureVariables` document carries no `_creatureId` at all. */
        private fun JsonObject.namesNoCreature(): Boolean =
            string(VARIABLES_CREATURE_ID) == null

        /**
         * The `creatureVariables` document for [creatureId]: an exact `_creatureId` match is
         * preferred, and an unattributed document is used only as a substitute when no exact
         * match exists — unlike a property's ancestry-less leniency ([belongsTo]), which is
         * *additive* (an unattributed property is kept alongside every exact match), this
         * leniency is *substitutive* (an unattributed document stands in only in a match's
         * absence, never instead of one).
         */
        private fun Collection<JsonObject>.variablesFor(creatureId: String): JsonObject? =
            firstOrNull { it.namesCreature(creatureId) } ?: firstOrNull { it.namesNoCreature() }

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
