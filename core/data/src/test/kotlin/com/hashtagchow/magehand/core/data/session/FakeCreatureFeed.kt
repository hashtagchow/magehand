package com.hashtagchow.magehand.core.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller
import com.hashtagchow.magehand.core.model.ConnectionState

/**
 * A [CreatureFeed] whose mirror and connection state the test drives by hand.
 *
 * Stands in for `DdpCreatureFeed` + a real socket. **No live server is contacted and no
 * live document is mutated anywhere in WP4.**
 *
 * ### The mirror is a *parameter*, because in production it is shared
 *
 * `DdpCreatureFeed.documents` returns `client.mirror.documentsFlow(collection)` — the one
 * [com.hashtagchow.magehand.core.ddp.MongoMirror] owned by the account's single `DdpClient`.
 * Every feed built on that connection therefore reads the **same** collection maps. This fake
 * defaulted to a private map per instance, so no test could express the arrangement the DM
 * dashboard actually creates (N `singleCharacter` subscriptions filling one mirror), and the
 * whole suite was structurally blind to cross-creature contamination. Pass one [FakeMirror] to
 * several feeds to reproduce it — see `SharedMirrorPartitionTest`.
 */
class FakeCreatureFeed(
    private val caller: FakeDdpMethodCaller = FakeDdpMethodCaller(),
    /** The mirror this feed reads. Share one instance to model one DDP connection. */
    val mirror: FakeMirror = FakeMirror(),
) : CreatureFeed {

    override val connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    override val isReady = MutableStateFlow(false)

    var started = false
        private set
    var stopped = false
        private set

    override fun documents(collection: String): StateFlow<Map<String, JsonObject>> =
        mirror.flowFor(collection)

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        stopped = true
        isReady.value = false
    }

    override suspend fun call(method: String, params: List<JsonElement>): JsonElement =
        caller.call(method, params)

    /** Pushes a whole sheet into the mirror, the way `singleCharacter` fills it. */
    fun publish(sheet: CreatureSheet, creatureId: String) = mirror.replace(sheet, creatureId)

    /** Replaces one mirrored document — a `changed` frame. */
    fun changeProperty(id: String, document: JsonObject) = mirror.changeProperty(id, document)

    /** Empties the mirror, the way a resync that replayed nothing would. */
    fun clear() = mirror.clear()

    fun goLive() {
        connectionState.value = ConnectionState.LIVE
        isReady.value = true
    }
}

/**
 * The per-connection document store behind [FakeCreatureFeed] — one `MongoMirror`'s worth.
 *
 * Exists as its own object for exactly one reason: so a test can hand the same one to two
 * feeds. That is not an exotic arrangement, it is what the DM dashboard does on every open.
 */
class FakeMirror {

    private val collections = mutableMapOf<String, MutableStateFlow<Map<String, JsonObject>>>()

    fun flowFor(collection: String): MutableStateFlow<Map<String, JsonObject>> =
        collections.getOrPut(collection) { MutableStateFlow(emptyMap()) }

    /** One creature's sheet, as the *only* thing in the mirror. The single-screen case. */
    fun replace(sheet: CreatureSheet, creatureId: String) {
        flowFor(CreatureSheet.CREATURE_PROPERTIES).value = sheet.properties
        flowFor(CreatureSheet.CREATURES).value =
            sheet.creature?.let { mapOf(creatureId to it) } ?: emptyMap()
        flowFor(CreatureSheet.CREATURE_VARIABLES).value =
            sheet.variables?.let { mapOf(variablesKey(it, creatureId) to it) } ?: emptyMap()
    }

    /**
     * One creature's sheet **added to** whatever is already mirrored — a second
     * `singleCharacter` subscription landing on a connection that already carries a first.
     *
     * Merging rather than replacing is the whole point: Meteor's `added` frames accumulate into
     * one store per collection, and the union is what every session on that socket then reads.
     */
    fun add(sheet: CreatureSheet, creatureId: String) {
        val properties = flowFor(CreatureSheet.CREATURE_PROPERTIES)
        properties.value = properties.value + sheet.properties
        sheet.creature?.let {
            val creatures = flowFor(CreatureSheet.CREATURES)
            creatures.value = creatures.value + (creatureId to it)
        }
        sheet.variables?.let {
            val variables = flowFor(CreatureSheet.CREATURE_VARIABLES)
            variables.value = variables.value + (variablesKey(it, creatureId) to it)
        }
    }

    fun changeProperty(id: String, document: JsonObject) {
        val flow = flowFor(CreatureSheet.CREATURE_PROPERTIES)
        flow.value = flow.value + (id to document)
    }

    fun clear() {
        collections.values.forEach { it.value = emptyMap() }
    }

    /**
     * `creatureVariables` is keyed by its own `_id`, which is **not** the creature id — the
     * capture's variables document proves it. Keying two creatures' variables by a constant
     * (this fake used `"vars"`) collapses them onto one entry, which is a second way the fakes
     * could not represent a shared mirror.
     */
    private fun variablesKey(document: JsonObject, creatureId: String): String =
        (document["_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: creatureId
}
