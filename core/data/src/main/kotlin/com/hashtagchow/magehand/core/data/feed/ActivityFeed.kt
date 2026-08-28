package com.hashtagchow.magehand.core.data.feed

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.hashtagchow.magehand.core.data.connection.DdpConnectionManager
import com.hashtagchow.magehand.core.ddp.ejsonInstant
import com.hashtagchow.magehand.core.model.FeedEntry
import com.hashtagchow.magehand.core.model.FeedLine

/**
 * The **DiceCloud activity feed** (docs/design/16-actions-and-feed.md decisions 8–12, FR-25).
 *
 * Pure, like the three engines beside it: `Map<id, JsonObject>` in, [FeedEntry] list out, no
 * clock and no I/O. [ActivityFeedRepository] is the thin live wrapper.
 *
 * ### No new subscription, no new budget spend (decision 8)
 *
 * `creatureLogs` is **already arriving**. `singleCharacter` publishes more than the API doc
 * lists, and the log collection is one of the extras (WP2 §7's live enumeration, re-confirmed by
 * `DdpLiveIntegrationTest` and by the DM-view capture). So this feed costs one read of a mirror
 * map that is already being filled — no `subscribe`, no method call, and nothing added to the
 * 50-per-10s subscription bucket the whole table shares.
 *
 * ### THE ONE INTENTIONAL CROSS-CREATURE READ
 *
 * `CreatureSheet.fromMirror`'s KDoc is emphatic that the DDP mirror is per **connection**, not
 * per creature, and that reading it without partitioning by `creatureId` is what made six DM
 * cards render one creature's stats. That lesson stands and is not weakened here.
 *
 * This is the deliberate exception, and it differs from that bug in all three ways that made the
 * bug a bug:
 *
 *  1. **It is the feature.** Decision 10 asks for entries *"merged across the N subscribed
 *     creatures, newest-first"*. A per-creature partition would produce N separate feeds, which
 *     is precisely what the design says the panel is not. The union is the product, not a leak.
 *  2. **Every entry stays keyed and labelled.** [FeedEntry.creatureId] and
 *     [FeedEntry.creatureName] are carried on each row and rendered on each row (decision 11).
 *     The sheet bug was invisible *because* the union was silently attributed to whichever card
 *     was asking; here attribution travels with the data and is on screen. Nothing anywhere
 *     reads an entry without knowing whose it is.
 *  3. **It is filtered to the table.** [build] takes the member ids and drops everything else,
 *     so a `characterList` document or another open character's log cannot appear in a DM's
 *     panel. The union is over the creatures the DM chose, not over the socket.
 *
 * The rule that survives, stated so the partition lesson is not violated silently: **a
 * cross-creature read is legitimate only when the merge is the requirement, the attribution is
 * carried per row, and the set being merged is explicit.** This is the one place in the app that
 * meets all three; a fourth engine that "just needs a couple of other creatures' properties"
 * does not, and should re-read `CreatureSheet.fromMirror`.
 *
 * ### Nothing here logs our own writes (decision 9)
 *
 * MageHand's writes produce **no** `creatureLogs` documents — probe L3: `creatureProperties.damage`
 * and `adjustQuantity` never log server-side. That is a fact about the server, so it cannot be
 * fixed on this side, and the panel's empty state names what the feed carries rather than
 * implying it carries everything. Self-inserting via `creatureLogs.methods.insert` is deferred
 * and recorded — it would double-journal for anyone running both clients and spend the 5-per-5s
 * method lane on every tap.
 */
object ActivityFeedEngine {

    /** The mirrored collection. Not on [com.hashtagchow.magehand.core.data.tracker.CreatureSheet]
     *  because a log is not part of a sheet — it is an event about one. */
    const val CREATURE_LOGS = "creatureLogs"

    /**
     * Decision 10's *"rendered cap 50"*.
     *
     * **Latency, not memory** — the design says so in as many words, and the measurement behind
     * it is 18 KB at six creatures, which no phone would notice holding. What a phone does notice
     * is composing an unbounded list on every log arrival. The server caps its own side at 20
     * newest per creature, so six creatures can offer 120 and this takes the newest 50 of them.
     */
    const val RENDER_CAP = 50

    private const val FIELD_CREATURE_ID = "creatureId"
    private const val FIELD_CREATURE_NAME = "creatureName"
    private const val FIELD_CONTENT = "content"
    private const val FIELD_DATE = "date"
    private const val FIELD_NAME = "name"
    private const val FIELD_VALUE = "value"

    /**
     * Merges the mirrored logs into one newest-first feed.
     *
     * @param logs the raw `creatureLogs` mirror map — a per-connection **union**, see the class
     *   KDoc.
     * @param creatureIds the creatures whose entries belong in this feed. An entry naming any
     *   other creature is dropped; an entry naming **no** creature is also dropped, which is the
     *   opposite of `CreatureSheet.belongsTo`'s leniency and deliberately so. There the lenient
     *   direction protected a tracker from blanking on an inference that might be wrong; here an
     *   unattributable entry has no name to render beside it (decision 11 labels every row with
     *   its creature) and would appear in the panel as an anonymous event from nobody.
     * @param cap how many to keep. Defaults to [RENDER_CAP].
     */
    fun build(
        logs: Map<String, JsonObject>,
        creatureIds: Set<String>,
        cap: Int = RENDER_CAP,
    ): List<FeedEntry> = logs
        .asSequence()
        .mapNotNull { (id, doc) -> doc.toEntry(id, creatureIds) }
        // Newest first. A `null` date sorts LAST rather than first: a document that carried no
        // timestamp has not earned the top of a feed whose whole ordering claim is recency, and
        // substituting "now" for it would let one malformed row permanently head the panel.
        //
        // `nullsFirst()` under `compareByDescending` is what puts them last, and the double
        // negative is worth spelling out: descending negates the comparator, so the ordering
        // that ranks null lowest ascending ranks it last descending. `ActivityFeedTest` pins it,
        // because this is exactly the line a reader would "fix" into nullsLast and invert.
        .sortedWith(compareByDescending(nullsFirst()) { it.dateMillis })
        .take(cap)
        .toList()

    private fun JsonObject.toEntry(logId: String, creatureIds: Set<String>): FeedEntry? {
        val creatureId = string(FIELD_CREATURE_ID) ?: return null
        if (creatureId !in creatureIds) return null
        return FeedEntry(
            logId = logId,
            creatureId = creatureId,
            // The document carries the name itself, so the panel needs no join against the
            // character list — which also means an entry stays correctly labelled while the list
            // is still loading. `null`, not `.orEmpty()`: a doc with no name is a genuinely
            // absent fact, not an empty one, and the raw "" used to reach the panel unlabelled
            // (L4) — the UI resolves the absence to a fallback string, the engine does not.
            creatureName = string(FIELD_CREATURE_NAME),
            lines = contentLines(),
            dateMillis = ejsonInstant(FIELD_DATE)?.toEpochMilli(),
        )
    }

    /**
     * `content[]` → renderable lines (decision 11).
     *
     * A block with neither a `name` nor a `value` is dropped — it would render as a blank line
     * inside an entry, which reads as a rendering fault rather than as an empty block. `inline`
     * is present on some blocks and is deliberately ignored in v1: it is a layout hint for
     * DiceCloud's own renderer, and honouring it would be the start of re-implementing that
     * renderer rather than showing the text.
     */
    private fun JsonObject.contentLines(): List<FeedLine> =
        (this[FIELD_CONTENT] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.mapNotNull { block ->
                val name = block.string(FIELD_NAME)
                val value = block.string(FIELD_VALUE)
                if (name == null && value == null) null else FeedLine(name = name, value = value)
            }
            .orEmpty()

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
}

/**
 * The live [ActivityFeedEngine] — the mirrored `creatureLogs` for a chosen set of creatures.
 *
 * Its own repository rather than a flow on `OpenCharacter`, for two reasons that point the same
 * way. The feed is **not per character** — it is one merged list over several (decision 10), so
 * hanging it off a per-character handle would mean N flows to re-merge, each carrying the same
 * union. And `OpenCharacter` is deliberately a narrow, write-audited seam
 * (`WritePostureTest`); giving it a raw-document accessor to serve a read this object can serve
 * directly would widen it for no gain.
 *
 * This reads the same `DdpConnectionManager` → `client.mirror.documentsFlow(...)` path
 * `CharacterListRepository` already uses, and like it, subscribes to nothing.
 */
interface ActivityFeedRepository {

    /**
     * The merged, newest-first feed for [creatureIds], recomputed as logs arrive.
     *
     * Emits an empty list when no account is connected — an absent connection is an ordinary
     * state (signed out, still connecting), not an error the panel should render.
     */
    fun feed(creatureIds: Set<String>): Flow<List<FeedEntry>>
}

/** [ActivityFeedRepository] over the active account's DDP mirror. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultActivityFeedRepository(
    private val connectionManager: DdpConnectionManager,
) : ActivityFeedRepository {

    override fun feed(creatureIds: Set<String>): Flow<List<FeedEntry>> =
        connectionManager.connection.flatMapLatest { connection ->
            if (connection == null) {
                flowOf(emptyList())
            } else {
                // `documentsFlow` is `computeIfAbsent`, so naming a collection nothing has read
                // yet is legal and yields an empty-then-filling StateFlow. No subscription is
                // started here: `singleCharacter` — already open for each card — is what fills
                // it (decision 8), and decision 12's liveness follows for free because this is
                // the same reactive path the boards ride. No polling, no refresh control.
                connection.client.mirror.documentsFlow(ActivityFeedEngine.CREATURE_LOGS)
                    .map { ActivityFeedEngine.build(it, creatureIds) }
            }
        }
}
