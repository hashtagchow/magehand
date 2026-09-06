package com.hashtagchow.magehand.core.data.write

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.tracker.TrackerEngine
import com.hashtagchow.magehand.core.ddp.DdpClient
import com.hashtagchow.magehand.core.ddp.DdpClientConfig
import com.hashtagchow.magehand.core.ddp.ejsonParams
import kotlin.time.Duration.Companion.seconds

/**
 * FR-44's ruling-R2 probe: **can this app write `usesUsed`, and does a rest reset it?**
 *
 * The ledger row is explicit that the write is *"decided by a probe on the Test Dummy, not by
 * guess"*, and that the app's existing pip vocabulary is the wrong instrument — the tracker
 * spends charges with `creatureProperties.damage`, which is an `attribute` method, while a
 * limited-use ability's counter lives on the `action`/`spell` property itself. The only
 * candidate the method catalog offers is `creatureProperties.update {_id, path, value}`, whose
 * documented refusals (`type/order/parent/ancestors/damage`) say nothing about `usesUsed`.
 * Silence in a doc is not permission, so this runs it.
 *
 * ### The two probes
 *
 * - **A — `creatureProperties.update {_id, path:['usesUsed'], value: 1}`.** Accepted or
 *   rejected, and if accepted whether the server *recomputes* (`usesLeft` must follow, or the
 *   write moved a field nothing reads).
 * - **B — `creature.methods.rest {creatureId, restType:'longRest'}`.** Whether the server's own
 *   rest clears `usesUsed` on an `action` row carrying `reset: 'longRest'`, and whether it logs
 *   it. This decides whether the rows may join `rowsRestoredBy`'s restore list — decision 19's
 *   honesty rule: the dialog's list is a *promise* about what the button does, so a row goes in
 *   it only when the server is known to restore it.
 *
 * ### This is opt-in and is not run by `./gradlew test`
 *
 * Gated on `MAGEHAND_IT=1` and on an explicitly-named writable creature, exactly like
 * [InventoryWriteLiveIntegrationTest], and refused outright against a party id.
 *
 * ```
 * MAGEHAND_IT=1 MAGEHAND_IT_WS_URL=wss://<host>/websocket MAGEHAND_IT_TOKEN=<resume token> \
 *   MAGEHAND_IT_WRITE_CREATURE_ID=<the dummy> \
 *   ./gradlew :core:data:test -PmagehandIt=1 --tests '*LimitedUseWriteLive*'
 * ```
 *
 * ### Residue
 *
 * The probe inserts one `action` and **soft-removes it at the end** — `softRemove` is the only
 * deletion this server has (see [WriteOp.RemoveProperty]), so the document survives with
 * `removed: true` and every one of this app's readers drops it. That residue is itself a
 * fixture worth having: it is the negative case R1's discovery rule has to keep filtering.
 *
 * The rest in probe B is a **real long rest on the dummy**, which also restores its spell slot,
 * its resource and its HP. That is the point of a sacrificial creature.
 */
class LimitedUseWriteLiveIntegrationTest {

    @Before
    fun requireOptIn() {
        assumeTrue(
            "live write probe — set MAGEHAND_IT=1 to run it",
            System.getenv("MAGEHAND_IT") == "1",
        )
        val target = System.getenv(ENV_WRITE_CREATURE_ID)
        assumeTrue(
            "live write probe — set MAGEHAND_IT_WRITE_CREATURE_ID to a scratch character you " +
                "are willing to have written to. Never a party sheet.",
            !target.isNullOrBlank(),
        )
        refuseKnownPartyIds(target!!)
    }

    /**
     * The whole of R2, in one test, because the three steps are one story: a row has to exist
     * before it can be written to, and it has to have been written to before a rest can be seen
     * clearing it. Splitting them into three JUnit tests would make the order an accident of the
     * runner rather than the probe's own sequence.
     */
    @Test
    fun usesUsed_write_and_rest_reset_on_a_limited_use_action() = onLiveSheet { client, sheet ->
        val before = sheet().properties.size
        println("== FR-44 probe: dummy $DUMMY carries $before properties before the insert")

        val probeName = "MageHand FR-44 probe ${System.currentTimeMillis()}"
        val insertedId = insertProbeAction(client, probeName)
        println("== inserted action $insertedId '$probeName'")

        val seeded = awaitSheet(sheet, "the probe action to appear") { it.properties.containsKey(insertedId) }
        printRow("after insert", seeded.properties.getValue(insertedId))
        awaitComputedUses(sheet, insertedId, "after insert")

        try {
            // ---- Probe A -------------------------------------------------------------
            val attempt = runCatching {
                client.call(
                    METHOD_UPDATE,
                    listOf(
                        buildJsonObject {
                            put("_id", insertedId)
                            put("path", buildJsonArray { add(JsonPrimitive(PATH_USES_USED)) })
                            put("value", 1)
                        },
                    ),
                )
            }
            println("== PROBE A result: ${attempt.getOrNull()}  error: ${attempt.exceptionOrNull()}")

            if (attempt.isSuccess) {
                val after = runCatching {
                    awaitSheet(sheet, "usesUsed == 1") { intAt(it, insertedId, PATH_USES_USED) == 1 }
                }
                println("== PROBE A recompute: ${if (after.isSuccess) "usesUsed landed" else "TIMED OUT"}")
                printRow("after update", sheet().properties.getValue(insertedId))
                awaitComputedUses(sheet, insertedId, "after update")
            }

            // ---- Probe B -------------------------------------------------------------
            val logsBefore = client.mirror.documents(CREATURE_LOGS).keys.toSet()
            val rest = runCatching {
                client.call(
                    METHOD_REST,
                    listOf(
                        buildJsonObject {
                            put("creatureId", DUMMY)
                            put("restType", "longRest")
                        },
                    ),
                )
            }
            println("== PROBE B result: ${rest.getOrNull()}  error: ${rest.exceptionOrNull()}")

            val cleared = runCatching {
                awaitSheet(sheet, "usesUsed cleared by the rest") {
                    val v = intAt(it, insertedId, PATH_USES_USED)
                    v == null || v == 0
                }
            }
            println("== PROBE B usesUsed after rest: ${intAt(sheet(), insertedId, PATH_USES_USED)} " +
                "(${if (cleared.isSuccess) "RESET" else "NOT RESET"})")
            printRow("after rest", sheet().properties.getValue(insertedId))
            awaitComputedUses(sheet, insertedId, "after rest")

            val newLogs = client.mirror.documents(CREATURE_LOGS).filterKeys { it !in logsBefore }
            println("== PROBE B new creatureLogs: ${newLogs.size}")
            newLogs.values.forEach { println("==   log: $it") }
        } finally {
            client.call(METHOD_SOFT_REMOVE, listOf(buildJsonObject { put("_id", insertedId) }))
            runCatching {
                awaitSheet(sheet, "the probe action to be soft-removed") {
                    it.properties[insertedId]?.get("removed").let { v ->
                        (v as? JsonPrimitive)?.content == "true"
                    }
                }
            }
            println("== cleanup: softRemove $insertedId; dummy now carries ${sheet().properties.size} properties")
        }

        // The probe's whole value is its transcript, which is read by a person and written into
        // docs/verification/probe-fr44.md. The only hard assertion is that the row existed —
        // everything else is a *finding*, and a finding that fails a test is a finding nobody
        // can record.
        assertNotNull("the probe action must have been created", seeded.properties[insertedId])
    }

    // -----------------------------------------------------------------------

    /**
     * The shape R1 discovers: an `action` with a numeric `uses` and a `reset` rule.
     *
     * `uses` goes in as `{calculation:"2"}` — DiceCloud stores every numeric property field as a
     * calculation and publishes the `_calculation` wrapper back, which is why the live party
     * sheets read `uses: {calculation:"proficiencyBonus", …, value:2}` rather than `2`.
     * `order` is mandatory and `description` must be an object (both from FR-8's probe).
     */
    private suspend fun insertProbeAction(client: DdpClient, name: String): String {
        val result = client.call(
            "creatureProperties.insert",
            listOf(
                buildJsonObject {
                    putJsonObject("creatureProperty") {
                        put("order", 99)
                        put("type", "action")
                        put("actionType", "action")
                        put("name", name)
                        put("reset", "longRest")
                        putJsonObject("uses") { put("calculation", "2") }
                        putJsonObject("description") { put("text", "Inserted by the FR-44 R2 probe.") }
                    }
                    putJsonObject("parentRef") {
                        put("collection", "creatures")
                        put("id", DUMMY)
                    }
                },
            ),
        )
        return requireNotNull((result as? JsonPrimitive)?.contentOrNull) {
            "creatureProperties.insert must return the new property id, got $result"
        }
    }

    /**
     * **Probe C (2026-09-06 re-run): when does `uses.value` land over DDP?**
     *
     * The first run of this probe recorded `uses={"calculation":"2"}` with **no `value`** at every
     * step, and nobody noticed what that meant: `TrackerEngine.limitedUse` reads `uses` through
     * `number()`, which resolves a `_calculation` wrapper by its `value` key, so the probe row was
     * never discoverable at any point in that run. The transcript proved the *write* and silently
     * disproved the *read*.
     *
     * That is a real property of the server, not a defect in the reader: `uses` is a calculation
     * and the server computes it on a debounced pass. This waits for it — up to
     * [COMPUTE_TIMEOUT_MILLIS] — and prints how long it took, so the window is a measured number
     * in `probe-fr44.md` rather than an assumption. It also runs the production discovery rule at
     * the end, because "the field arrived" and "the engine finds the row" are two claims and only
     * the second one is the feature.
     */
    private suspend fun awaitComputedUses(sheet: () -> CreatureSheet, id: String, label: String) {
        val started = System.currentTimeMillis()
        var elapsed = -1L
        repeat((COMPUTE_TIMEOUT_MILLIS / POLL_MILLIS).toInt()) {
            if (computedUses(sheet(), id) != null) {
                elapsed = System.currentTimeMillis() - started
                return@repeat
            }
            kotlinx.coroutines.delay(POLL_MILLIS)
        }
        val computed = computedUses(sheet(), id)
        val discovered = TrackerEngine.build(sheet()).limitedUses.firstOrNull { it.propertyId == id }
        println(
            "== COMPUTE $label: uses.value=${computed ?: "ABSENT"} " +
                "after ${if (elapsed >= 0) "${elapsed}ms" else ">${COMPUTE_TIMEOUT_MILLIS}ms (TIMED OUT)"}; " +
                "TrackerEngine discovers it: ${discovered != null}" +
                (discovered?.let { " (${it.value} / ${it.total})" } ?: ""),
        )
    }

    /** The computed answer inside the `_calculation` wrapper, or `null` while it is still absent. */
    private fun computedUses(sheet: CreatureSheet, id: String): Int? =
        ((sheet.properties[id]?.get("uses") as? JsonObject)?.get("value") as? JsonPrimitive)
            ?.content?.toDoubleOrNull()?.toInt()

    private fun printRow(label: String, row: JsonObject) {
        println(
            "== $label: uses=${row["uses"]} usesUsed=${row["usesUsed"]} " +
                "usesLeft=${row["usesLeft"]} reset=${row["reset"]} removed=${row["removed"]}",
        )
    }

    private fun intAt(sheet: CreatureSheet, id: String, key: String): Int? =
        (sheet.properties[id]?.get(key) as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()

    private fun onLiveSheet(block: suspend (DdpClient, () -> CreatureSheet) -> Unit) = runBlocking {
        val client = DdpClient.okHttp(
            url = URL,
            config = DdpClientConfig(handshakeTimeout = 30.seconds, methodTimeout = 30.seconds),
            resumeTokenProvider = { TOKEN },
        )
        try {
            client.connect(45.seconds)
            val sub = client.subscribe("singleCharacter", ejsonParams(DUMMY))
            sub.awaitReady(120.seconds)
            block(client) { CreatureSheet.fromMirror(client.mirror.snapshot(), DUMMY) }
            sub.stop()
        } finally {
            client.close()
        }
    }

    private suspend fun awaitSheet(
        sheet: () -> CreatureSheet,
        what: String,
        predicate: (CreatureSheet) -> Boolean,
    ): CreatureSheet {
        repeat(80) {
            val current = sheet()
            if (predicate(current)) return current
            kotlinx.coroutines.delay(250)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private companion object {
        const val ENV_WRITE_CREATURE_ID = "MAGEHAND_IT_WRITE_CREATURE_ID"
        const val METHOD_UPDATE = "creatureProperties.update"
        const val METHOD_REST = "creature.methods.rest"
        const val METHOD_SOFT_REMOVE = "creatureProperties.softRemove"
        const val PATH_USES_USED = "usesUsed"
        const val CREATURE_LOGS = "creatureLogs"

        /** How long Probe C waits for the server's debounced recompute to publish `uses.value`. */
        const val COMPUTE_TIMEOUT_MILLIS: Long = 60_000
        const val POLL_MILLIS: Long = 500

        val URL: String by lazy { System.getenv("MAGEHAND_IT_WS_URL") ?: "wss://dicecloud.com/websocket" }
        val TOKEN: String by lazy { System.getenv("MAGEHAND_IT_TOKEN") ?: error("set MAGEHAND_IT_TOKEN") }
        val DUMMY: String by lazy {
            System.getenv(ENV_WRITE_CREATURE_ID) ?: error("set $ENV_WRITE_CREATURE_ID")
        }

        /** See `InventoryWriteLiveIntegrationTest.PARTY_IDS` — one env contract for every probe. */
        val PARTY_IDS: Set<String> =
            System.getenv("MAGEHAND_PARTY_IDS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }?.toSet()
                ?: setOf("FakeCreature23456")

        fun refuseKnownPartyIds(id: String) = assertTrue(
            "$id is a player's character. 08-testing-and-release.md makes party sheets " +
                "read-only for every test; this probe inserts, writes and rests.",
            id !in PARTY_IDS,
        )
    }
}
