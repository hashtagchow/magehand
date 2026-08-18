package com.hashtagchow.magehand.core.ddp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** EJSON dates — the only EJSON extension DiceCloud puts on the wire. */
class EjsonTest {

    @Test
    fun decodes_the_wire_form() {
        val element = Json.parseToJsonElement("""{"${'$'}date":1755448320000}""")
        assertTrue(Ejson.isDate(element))
        assertEquals(Instant.ofEpochMilli(1_755_448_320_000), Ejson.toInstant(element))
    }

    @Test
    fun encodes_the_wire_form() {
        assertEquals(
            """{"${'$'}date":1755448320000}""",
            Ejson.date(Instant.ofEpochMilli(1_755_448_320_000)).toString(),
        )
        assertEquals(Ejson.date(7L), Ejson.date(Instant.ofEpochMilli(7)))
    }

    @Test
    fun tolerates_stringified_millis() {
        val element = Json.parseToJsonElement("""{"${'$'}date":"1755448320000"}""")
        assertEquals(Instant.ofEpochMilli(1_755_448_320_000), Ejson.toInstant(element))
    }

    @Test
    fun rejects_things_that_are_not_dates() {
        assertNull(Ejson.toInstantOrNull(null))
        assertNull(Ejson.toInstantOrNull(JsonPrimitive(1)))
        assertNull(Ejson.toInstantOrNull(Json.parseToJsonElement("""{"${'$'}date":1,"extra":2}""")))
        assertNull(Ejson.toInstantOrNull(Json.parseToJsonElement("""{"${'$'}date":"not-a-number"}""")))
        assertNull(Ejson.toInstantOrNull(Json.parseToJsonElement("""{"date":1}""")))
        assertFalse(Ejson.isDate(Json.parseToJsonElement("""{}""")))
    }

    @Test
    fun encode_walks_maps_lists_and_instants() {
        val encoded = Ejson.encode(
            mapOf(
                "_id" to "FakeCreature23456",
                "at" to Instant.ofEpochMilli(42),
                "tags" to listOf("a", 1, true, null),
                "nested" to mapOf("deep" to Instant.ofEpochMilli(7)),
            )
        )
        assertEquals(
            """{"_id":"FakeCreature23456","at":{"${'$'}date":42},"tags":["a",1,true,null],""" +
                """"nested":{"deep":{"${'$'}date":7}}}""",
            encoded.toString(),
        )
    }

    @Test
    fun decode_round_trips_at_any_depth() {
        val json = Json.parseToJsonElement(
            """{"name":"Bless","value":3,"ratio":0.5,"on":true,"missing":null,""" +
                """"at":{"${'$'}date":42},"list":[{"${'$'}date":7}]}"""
        )

        @Suppress("UNCHECKED_CAST")
        val decoded = Ejson.decode(json) as Map<String, Any?>
        assertEquals("Bless", decoded["name"])
        assertEquals(3L, decoded["value"])
        assertEquals(0.5, decoded["ratio"])
        assertEquals(true, decoded["on"])
        assertNull(decoded["missing"])
        assertEquals(Instant.ofEpochMilli(42), decoded["at"])
        assertEquals(listOf(Instant.ofEpochMilli(7)), decoded["list"])
    }

    @Test
    fun ejsonParams_builds_a_ddp_params_array() {
        val params = ejsonParams("FakeCreature23456", mapOf("restType" to "longRest"))
        assertEquals(2, params.size)
        assertEquals("\"FakeCreature23456\"", params[0].toString())
        assertEquals("""{"restType":"longRest"}""", params[1].toString())
    }

    @Test
    fun ejsonInstant_reads_a_document_field() {
        val doc = Json.parseToJsonElement(
            """{"_id":"p1","dateCreated":{"${'$'}date":1755448320000},"name":"Bless"}"""
        ) as JsonObject
        assertEquals(Instant.ofEpochMilli(1_755_448_320_000), doc.ejsonInstant("dateCreated"))
        assertNull(doc.ejsonInstant("name"))
        assertNull(doc.ejsonInstant("absent"))
    }

    @Test
    fun meteor_ids_use_the_unmistakable_alphabet() {
        repeat(200) {
            val id = MeteorId.random()
            assertEquals(17, id.length)
            assertTrue(MeteorId.isValid(id))
            assertTrue(id.none { it in "01lIO" })
        }
        assertFalse(MeteorId.isValid("too-short"))
        assertFalse(MeteorId.isValid("0000000000000000O"))
        assertTrue(MeteorId.isValid("FakeCreature23456"))
    }
}
