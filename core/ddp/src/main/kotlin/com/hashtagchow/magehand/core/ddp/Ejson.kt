package com.hashtagchow.magehand.core.ddp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/**
 * EJSON — the flavour of JSON Meteor puts on the DDP wire
 * (docs/design/01-architecture.md "EJSON note", 02-ddp-and-api.md).
 *
 * DiceCloud only ever uses one EJSON extension: dates, encoded as
 * `{"$date": <epoch-millis>}`. Everything else in its collections is plain JSON.
 *
 * Design decision: [MongoMirror] stores documents **exactly as they arrive**, i.e.
 * dates stay as `{"$date": ms}` objects. That keeps the mirror lossless and byte-
 * comparable with the REST snapshot (`GET /api/creature/:id` returns the same
 * shape), and means round-tripping a document back onto the wire is the identity.
 * Consumers read dates through [toInstantOrNull] / [ejsonInstant] and write them
 * through [date] / [encode].
 */
object Ejson {

    /** The EJSON date key. Literal string is `$date`. */
    const val DATE_KEY: String = "\$date"

    /** `Instant` → `{"$date": <epoch-millis>}`. */
    fun date(instant: Instant): JsonObject =
        JsonObject(mapOf(DATE_KEY to JsonPrimitive(instant.toEpochMilli())))

    /** Epoch millis → `{"$date": ms}`. */
    fun date(epochMillis: Long): JsonObject =
        JsonObject(mapOf(DATE_KEY to JsonPrimitive(epochMillis)))

    /** True when [element] is an EJSON date object. */
    fun isDate(element: JsonElement?): Boolean = toInstantOrNull(element) != null

    /**
     * `{"$date": ms}` → [Instant], or `null` when [element] is anything else.
     *
     * Tolerates the millis arriving as a JSON string (some Meteor versions stringify
     * large numbers); anything else — extra keys, non-numeric payload — is not a date.
     */
    fun toInstantOrNull(element: JsonElement?): Instant? {
        val obj = element as? JsonObject ?: return null
        if (obj.size != 1) return null
        val raw = obj[DATE_KEY] as? JsonPrimitive ?: return null
        val millis = if (raw.isString) raw.content.toLongOrNull() else raw.longOrNull
        return millis?.let(Instant::ofEpochMilli)
    }

    /** Strict [toInstantOrNull]. */
    fun toInstant(element: JsonElement?): Instant =
        toInstantOrNull(element) ?: throw IllegalArgumentException("not an EJSON date: $element")

    /**
     * Kotlin value → EJSON [JsonElement]. Used to build method/subscription params.
     *
     * [Instant] becomes `{"$date": ms}`; maps/lists recurse; [JsonElement] passes
     * through untouched.
     */
    fun encode(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Instant -> date(value)
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Enum<*> -> JsonPrimitive(value.name)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to encode(v) })
        is Iterable<*> -> JsonArray(value.map { encode(it) })
        is Array<*> -> JsonArray(value.map { encode(it) })
        else -> throw IllegalArgumentException("cannot EJSON-encode ${value::class.qualifiedName}")
    }

    /**
     * EJSON [JsonElement] → plain Kotlin values, with `{"$date": ms}` decoded to
     * [Instant] at any depth. Numbers come back as `Long` when integral, `Double`
     * otherwise.
     */
    fun decode(element: JsonElement?): Any? = when (element) {
        null, JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull ?: element.content
        }
        is JsonArray -> element.map { decode(it) }
        is JsonObject -> toInstantOrNull(element) ?: element.mapValues { decode(it.value) }
    }
}

/** Reads [key] off this document as an EJSON date, or `null` if absent/not a date. */
fun JsonObject.ejsonInstant(key: String): Instant? = Ejson.toInstantOrNull(this[key])

/** Builds a DDP `params` array from plain Kotlin values (see [Ejson.encode]). */
fun ejsonParams(vararg values: Any?): List<JsonElement> = values.map { Ejson.encode(it) }
