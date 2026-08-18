package com.hashtagchow.magehand.core.ddp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/** [MongoMirror] on its own — the document algebra, without the socket. */
class MongoMirrorTest {

    private val mirror = MongoMirror()

    private fun fields(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun add(id: String, json: String, collection: String = C) {
        mirror.applyAdded(collection, id, fields(json))
        mirror.flush()
    }

    @Test
    fun added_injects_the_id_and_publishes_an_immutable_snapshot() {
        add("p1", """{"name":"1st Level","value":3}""")

        val docs = mirror.documents(C)
        assertEquals(setOf("p1"), docs.keys)
        assertEquals("p1", (docs["p1"]!!["_id"] as JsonPrimitive).content)
        assertEquals(3, (docs["p1"]!!["value"] as JsonPrimitive).intOrNull)

        // the previously handed-out snapshot must not mutate under the caller
        add("p2", """{"name":"2nd Level"}""")
        assertEquals(setOf("p1"), docs.keys)
        assertEquals(setOf("p1", "p2"), mirror.documents(C).keys)
    }

    @Test
    fun changed_merges_fields_and_honours_cleared() {
        add("p1", """{"name":"Bless","damage":2,"total":3,"reset":"longRest"}""")

        mirror.applyChanged(C, "p1", fields("""{"damage":0}"""), listOf("reset"))
        mirror.flush()

        val doc = mirror.document(C, "p1")!!
        assertEquals(0, (doc["damage"] as JsonPrimitive).intOrNull)
        assertEquals(3, (doc["total"] as JsonPrimitive).intOrNull)
        assertFalse("cleared field must be gone", doc.containsKey("reset"))
        assertEquals("p1", (doc["_id"] as JsonPrimitive).content)
    }

    @Test
    fun changed_may_clear_only() {
        add("p1", """{"name":"Bless","concentration":true}""")
        mirror.applyChanged(C, "p1", null, listOf("concentration"))
        mirror.flush()
        assertFalse(mirror.document(C, "p1")!!.containsKey("concentration"))
    }

    @Test
    fun cleared_can_never_remove_the_injected_id() {
        add("p1", """{"name":"Bless"}""")
        mirror.applyChanged(C, "p1", null, listOf("_id", "name"))
        mirror.flush()
        assertEquals("p1", (mirror.document(C, "p1")!!["_id"] as JsonPrimitive).content)
    }

    @Test
    fun changed_for_an_unknown_document_is_ignored() {
        mirror.applyChanged(C, "ghost", fields("""{"value":1}"""), emptyList())
        mirror.flush()
        assertEquals(0, mirror.size(C))
    }

    @Test
    fun removed_drops_the_document() {
        add("p1", """{"name":"Bless"}""")
        mirror.applyRemoved(C, "p1")
        mirror.flush()
        assertNull(mirror.document(C, "p1"))
        assertEquals(0, mirror.size(C))
    }

    @Test
    fun per_collection_event_flow_reports_added_changed_removed() = runBlocking {
        val seen = Collections.synchronizedList(ArrayList<MirrorEvent>())
        val attached = CompletableDeferred<Unit>()
        val collector = launch(Dispatchers.IO) {
            mirror.events(C).onSubscription { attached.complete(Unit) }.collect { seen += it }
        }
        try {
            attached.await()

            add("p1", """{"name":"Bless","damage":2,"reset":"longRest"}""")
            mirror.applyChanged(C, "p1", fields("""{"damage":0}"""), listOf("reset"))
            mirror.flush()
            mirror.applyRemoved(C, "p1")
            mirror.flush()

            awaitUntil(what = "3 events") { seen.size == 3 }
            val added = seen[0] as MirrorEvent.Added
            assertEquals("p1", added.id)

            val changed = seen[1] as MirrorEvent.Changed
            assertEquals(listOf("reset"), changed.cleared)
            assertEquals(setOf("damage"), changed.fields.keys)
            assertEquals(0, (changed.document["damage"] as JsonPrimitive).intOrNull)

            val removed = seen[2] as MirrorEvent.Removed
            assertEquals("Bless", (removed.document["name"] as JsonPrimitive).content)
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun resync_keeps_replayed_documents_and_drops_the_rest() {
        add("p1", """{"name":"1st Level","value":3}""")
        add("p2", """{"name":"2nd Level","value":2}""")
        add("p3", """{"name":"Gone","value":1}""")
        add("c1", """{"name":"Sabriel"}""", collection = "creatures")

        mirror.beginResync()
        assertTrue(mirror.isResyncing)
        // mid-resync the old data is still readable — this is what stops the UI blanking
        assertEquals(3, mirror.size(C))

        mirror.applyAdded(C, "p1", fields("""{"name":"1st Level","value":1}"""))
        mirror.applyAdded(C, "p2", fields("""{"name":"2nd Level","value":2}"""))
        mirror.applyAdded(C, "p4", fields("""{"name":"3rd Level","value":1}"""))
        mirror.applyAdded("creatures", "c1", fields("""{"name":"Sabriel"}"""))
        mirror.flush()
        assertEquals(4, mirror.size(C))

        mirror.endResync()
        mirror.flush()

        assertEquals(setOf("p1", "p2", "p4"), mirror.documents(C).keys)
        assertEquals(1, (mirror.document(C, "p1")!!["value"] as JsonPrimitive).intOrNull)
        assertEquals(1, mirror.size("creatures"))
        assertFalse(mirror.isResyncing)
    }

    @Test
    fun resync_with_nothing_replayed_empties_the_mirror() {
        add("p1", """{"name":"1st Level"}""")
        mirror.beginResync()
        mirror.endResync()
        mirror.flush()
        assertEquals(0, mirror.size(C))
    }

    @Test
    fun re_added_identical_document_produces_no_event() {
        add("p1", """{"name":"1st Level","value":3}""")
        val before = mirror.documents(C)["p1"]
        mirror.beginResync()
        mirror.applyAdded(C, "p1", fields("""{"name":"1st Level","value":3}"""))
        mirror.endResync()
        mirror.flush()
        assertTrue("identical replay must be a no-op", before === mirror.documents(C)["p1"])
    }

    @Test
    fun snapshot_covers_every_collection() {
        add("p1", """{"name":"1st Level"}""")
        add("c1", """{"name":"Sabriel"}""", collection = "creatures")
        add("v1", """{"name":"strength"}""", collection = "creatureVariables")

        val snapshot = mirror.snapshot()
        assertEquals(setOf(C, "creatures", "creatureVariables"), snapshot.keys)
        assertEquals(1, snapshot.getValue(C).size)
        assertEquals(setOf(C, "creatures", "creatureVariables"), mirror.collectionNames())
    }

    @Test
    fun clear_empties_everything() {
        add("p1", """{"name":"1st Level"}""")
        mirror.clear()
        assertEquals(0, mirror.size(C))
    }

    private companion object {
        const val C = "creatureProperties"
    }
}
