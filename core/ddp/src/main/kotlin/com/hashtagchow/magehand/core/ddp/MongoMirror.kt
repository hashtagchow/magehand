package com.hashtagchow.magehand.core.ddp

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/** What happened to one document in [MongoMirror]. */
sealed interface MirrorEvent {
    val collection: String

    data class Added(
        override val collection: String,
        val id: String,
        val document: JsonObject,
    ) : MirrorEvent

    data class Changed(
        override val collection: String,
        val id: String,
        /** The document after the change (including `_id`). */
        val document: JsonObject,
        /** Only the fields whose value actually changed. */
        val fields: JsonObject,
        /** Fields the server told us to drop (DDP `cleared`). */
        val cleared: List<String>,
    ) : MirrorEvent

    data class Removed(
        override val collection: String,
        val id: String,
        /** The document as it was immediately before removal. */
        val document: JsonObject,
    ) : MirrorEvent

    /** The whole collection was dropped (client closed / explicit reset). */
    data class Reset(override val collection: String) : MirrorEvent
}

/**
 * Client-side minimongo: `Map<collection, Map<id, JsonObject>>` fed by DDP
 * `added` / `changed` / `removed` (docs/design/01-architecture.md).
 *
 * ### Guarantees
 * - **Immutable snapshots.** Readers only ever see immutable maps published through
 *   [documentsFlow]; the mutable working copy never escapes the client's dispatcher.
 * - **Ordering.** All mutations are applied from `DdpClient`'s single message
 *   dispatcher, so `added → changed → removed` for a document is applied in wire
 *   order (01-architecture.md, "DDP message handling runs on a single dispatcher per
 *   connection").
 * - **Snapshot-before-signal.** Snapshots are published (see [flush]) *before*
 *   `DdpClient` resolves `ready` / method results for the same batch, so
 *   `subscription.awaitReady()` returning implies the documents are already visible.
 * - **`_id` is injected** into every stored document, because DDP carries the id out
 *   of band while every consumer (and the REST snapshot `GET /api/creature/:id`)
 *   expects it inside the document.
 *
 * ### Reconnect (quiescence)
 * DDP session state is per-connection: after a reconnect the server replays every
 * document as `added`. Clearing the mirror on disconnect would blank the UI, so
 * instead [beginResync] marks the current contents provisional, re-`added`
 * documents are diffed against what we already hold (emitting [MirrorEvent.Changed]
 * only for real differences), and [endResync] — called once every active
 * subscription is `ready` again — removes whatever the server did *not* replay.
 * That is Meteor's own quiescence rule and it is what makes reconnection invisible
 * to consumers.
 */
class MongoMirror internal constructor(
    eventBufferCapacity: Int = 1024,
) {
    /** Mutable working copy — confined to the client's message dispatcher. */
    private val working = HashMap<String, LinkedHashMap<String, JsonObject>>()
    private val dirty = LinkedHashSet<String>()
    private val pendingEvents = ArrayList<MirrorEvent>()

    private val published = ConcurrentHashMap<String, MutableStateFlow<Map<String, JsonObject>>>()
    private val collectionEvents = ConcurrentHashMap<String, MutableSharedFlow<MirrorEvent>>()

    private val _events = MutableSharedFlow<MirrorEvent>(
        extraBufferCapacity = eventBufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var resyncing = false
    private val seenDuringResync = HashSet<String>()

    /**
     * Every document event, all collections. Buffered and `DROP_OLDEST`: a slow
     * collector must never stall the protocol pump (that would starve pong replies
     * and kill the connection). [documentsFlow] is the authoritative, lossless
     * channel; this one is for incremental consumers that can tolerate a gap.
     */
    val events: SharedFlow<MirrorEvent> get() = _events.asSharedFlow()

    /** Immutable snapshot flow for one collection. Starts empty, never completes. */
    fun documentsFlow(collection: String): StateFlow<Map<String, JsonObject>> =
        stateOf(collection).asStateFlow()

    /** Events for one collection only. Same buffering caveat as [events]. */
    fun events(collection: String): SharedFlow<MirrorEvent> =
        collectionEvents.computeIfAbsent(collection) {
            MutableSharedFlow(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }.asSharedFlow()

    /** Current immutable contents of [collection]. */
    fun documents(collection: String): Map<String, JsonObject> = stateOf(collection).value

    fun document(collection: String, id: String): JsonObject? = documents(collection)[id]

    fun size(collection: String): Int = documents(collection).size

    /** Names of every collection that has ever been published to. */
    fun collectionNames(): Set<String> = published.keys.toSet()

    /** Whole-mirror immutable snapshot. */
    fun snapshot(): Map<String, Map<String, JsonObject>> =
        published.entries.associate { (name, flow) -> name to flow.value }

    // ---------------------------------------------------------------- mutations
    // All of the below run on DdpClient's single message dispatcher.

    internal fun applyAdded(collection: String, id: String, fields: JsonObject) {
        val docs = working.getOrPut(collection) { LinkedHashMap() }
        val next = withId(fields, id)
        if (resyncing) seenDuringResync += key(collection, id)

        val previous = docs[id]
        if (previous == null) {
            docs[id] = next
            record(MirrorEvent.Added(collection, id, next))
            dirty += collection
            return
        }
        // `added` for a document we already hold: either the reconnect replay, or a
        // misbehaving server. Either way the server's copy wins; report the delta.
        if (previous == next) return
        docs[id] = next
        record(diffEvent(collection, id, previous, next))
        dirty += collection
    }

    internal fun applyChanged(collection: String, id: String, fields: JsonObject?, cleared: List<String>) {
        val docs = working[collection] ?: return
        val previous = docs[id] ?: return
        if (fields.isNullOrEmptyObject() && cleared.isEmpty()) return

        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(previous)
        // `cleared` first, then `fields` — a key in both means "set", not "drop".
        for (key in cleared) {
            if (key == ID_FIELD) continue // the id is ours, not the server's to clear
            merged.remove(key)
        }
        fields?.forEach { (k, v) -> merged[k] = v }
        merged[ID_FIELD] = JsonPrimitive(id)

        val next = JsonObject(merged)
        if (next == previous) return
        docs[id] = next

        val changedFields = fields?.filter { (k, v) -> previous[k] != v } ?: emptyMap()
        val actuallyCleared = cleared.filter { it != ID_FIELD && previous.containsKey(it) && !next.containsKey(it) }
        record(MirrorEvent.Changed(collection, id, next, JsonObject(changedFields), actuallyCleared))
        dirty += collection
        if (resyncing) seenDuringResync += key(collection, id)
    }

    internal fun applyRemoved(collection: String, id: String) {
        val docs = working[collection] ?: return
        val previous = docs.remove(id) ?: return
        record(MirrorEvent.Removed(collection, id, previous))
        dirty += collection
        if (resyncing) seenDuringResync -= key(collection, id)
    }

    internal val isResyncing: Boolean get() = resyncing

    /** Reconnect started: everything we hold is provisional until the server replays it. */
    internal fun beginResync() {
        resyncing = true
        seenDuringResync.clear()
    }

    /**
     * Every active subscription is `ready` again — drop whatever the server did not
     * replay. Those documents were deleted (or fell out of the publication) while we
     * were offline.
     */
    internal fun endResync() {
        if (!resyncing) return
        resyncing = false
        for ((collection, docs) in working) {
            val stale = docs.keys.filter { key(collection, it) !in seenDuringResync }
            if (stale.isEmpty()) continue
            for (id in stale) {
                val previous = docs.remove(id) ?: continue
                record(MirrorEvent.Removed(collection, id, previous))
            }
            dirty += collection
        }
        seenDuringResync.clear()
    }

    /** Drops everything (client shutdown / account switch). */
    internal fun clear() {
        resyncing = false
        seenDuringResync.clear()
        for (collection in working.keys.toList()) {
            working[collection]?.clear()
            dirty += collection
            record(MirrorEvent.Reset(collection))
        }
        flush()
    }

    /**
     * Publishes immutable snapshots for every collection touched since the last
     * flush, then emits the buffered events. Called once per drained batch of wire
     * messages, before any `ready`/`result` signal from that batch is delivered.
     */
    internal fun flush() {
        if (dirty.isNotEmpty()) {
            for (collection in dirty) {
                val docs = working[collection] ?: continue
                stateOf(collection).value = java.util.Collections.unmodifiableMap(LinkedHashMap(docs))
            }
            dirty.clear()
        }
        if (pendingEvents.isEmpty()) return
        val batch = ArrayList(pendingEvents)
        pendingEvents.clear()
        for (event in batch) {
            _events.tryEmit(event)
            collectionEvents[event.collection]?.tryEmit(event)
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun record(event: MirrorEvent) {
        pendingEvents += event
    }

    private fun stateOf(collection: String): MutableStateFlow<Map<String, JsonObject>> =
        published.computeIfAbsent(collection) { MutableStateFlow(emptyMap()) }

    private fun diffEvent(
        collection: String,
        id: String,
        previous: JsonObject,
        next: JsonObject,
    ): MirrorEvent.Changed {
        val changed = next.filter { (k, v) -> previous[k] != v }
        val cleared = previous.keys.filter { it !in next.keys }
        return MirrorEvent.Changed(collection, id, next, JsonObject(changed), cleared)
    }

    private fun withId(fields: JsonObject, id: String): JsonObject {
        if (fields[ID_FIELD] == JsonPrimitive(id)) return fields
        val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(fields)
        map[ID_FIELD] = JsonPrimitive(id)
        return JsonObject(map)
    }

    private fun key(collection: String, id: String) = "$collection $id"

    private fun JsonObject?.isNullOrEmptyObject() = this == null || this.isEmpty()

    companion object {
        const val ID_FIELD: String = "_id"
    }
}
