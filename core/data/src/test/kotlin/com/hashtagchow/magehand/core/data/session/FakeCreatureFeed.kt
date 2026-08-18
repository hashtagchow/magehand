package com.hashtagchow.magehand.core.data.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller
import com.hashtagchow.magehand.core.model.ConnectionState

/**
 * A [CreatureFeed] whose mirror and connection state the test drives by hand.
 *
 * Stands in for `DdpCreatureFeed` + a real socket. **No live server is contacted and no
 * live document is mutated anywhere in WP4.**
 */
class FakeCreatureFeed(
    private val caller: FakeDdpMethodCaller = FakeDdpMethodCaller(),
) : CreatureFeed {

    override val connectionState = MutableStateFlow(ConnectionState.CONNECTING)
    override val isReady = MutableStateFlow(false)

    private val collections = mutableMapOf<String, MutableStateFlow<Map<String, JsonObject>>>()

    var started = false
        private set
    var stopped = false
        private set

    override fun documents(collection: String): StateFlow<Map<String, JsonObject>> = flowFor(collection)

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() {
        stopped = true
        isReady.value = false
    }

    override suspend fun call(method: String, params: List<JsonElement>): JsonElement =
        caller.call(method, params)

    private fun flowFor(collection: String) =
        collections.getOrPut(collection) { MutableStateFlow(emptyMap()) }

    /** Pushes a whole sheet into the mirror, the way `singleCharacter` fills it. */
    fun publish(sheet: CreatureSheet, creatureId: String) {
        flowFor(CreatureSheet.CREATURE_PROPERTIES).value = sheet.properties
        flowFor(CreatureSheet.CREATURES).value =
            sheet.creature?.let { mapOf(creatureId to it) } ?: emptyMap()
        flowFor(CreatureSheet.CREATURE_VARIABLES).value =
            sheet.variables?.let { mapOf("vars" to it) } ?: emptyMap()
    }

    /** Replaces one mirrored document — a `changed` frame. */
    fun changeProperty(id: String, document: JsonObject) {
        val flow = flowFor(CreatureSheet.CREATURE_PROPERTIES)
        flow.value = flow.value + (id to document)
    }

    /** Empties the mirror, the way a resync that replayed nothing would. */
    fun clear() {
        collections.values.forEach { it.value = emptyMap() }
    }

    fun goLive() {
        connectionState.value = ConnectionState.LIVE
        isReady.value = true
    }
}
