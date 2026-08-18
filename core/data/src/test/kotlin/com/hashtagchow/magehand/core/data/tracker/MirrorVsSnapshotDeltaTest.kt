package com.hashtagchow.magehand.core.data.tracker

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import com.hashtagchow.magehand.core.data.api.OkHttpDiceCloudApi
import com.hashtagchow.magehand.core.ddp.DdpClient
import com.hashtagchow.magehand.core.ddp.ejsonParams
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * WP2 §3.5 flagged a stable one-document gap: `GET /api/creature/:id` returns **573**
 * `creatureProperties` for Sabriel while the `singleCharacter` DDP publication yields
 * **572**. This test is the answer.
 *
 * The offline half runs always, off the committed capture. The live half is gated on
 * `MAGEHAND_IT=1` and is **strictly read-only**: one REST GET and one DDP subscription,
 * no method call except `login`. Nothing on the live server is created or mutated —
 * mutation probes are WP7's, against the sacrificial test creature.
 */
class MirrorVsSnapshotDeltaTest {

    private val serverUrl = "https://dnd.example.com"

    @Test
    fun `the REST capture contains exactly one soft-deleted property`() {
        val properties = Fixtures.sabrielSheet().propertyList
        assertEquals("the capture should be the 573-document REST body", 573, properties.size)

        val removed = properties.filter { (it["removed"] as? JsonPrimitive)?.content == "true" }
        assertEquals("expected exactly one removed:true property, got ${removed.map { it["name"] }}", 1, removed.size)

        val doc = removed.single()
        assertEquals(Fixtures.REMOVED_PROPERTY_ID, (doc["_id"] as JsonPrimitive).content)
        assertEquals("item", (doc["type"] as JsonPrimitive).content)
        assertTrue("a soft-deleted property carries removedAt", doc.containsKey("removedAt"))

        // 573 REST − 1 soft-deleted = 572 DDP. That is the whole delta.
        assertEquals(572, properties.size - removed.size)
    }

    @Test
    fun `the engine ignores the delta, so both sources yield the same board`() {
        // The consequence that actually matters: whichever source the tracker reads, the
        // board is identical, because discovery drops `removed:true` anyway.
        val fromRest = TrackerEngine.build(Fixtures.sabrielSheet())
        val withoutRemoved = CreatureSheet(
            properties = Fixtures.sabrielSheet().properties - Fixtures.REMOVED_PROPERTY_ID,
            creature = Fixtures.sabrielSheet().creature,
            variables = Fixtures.sabrielSheet().variables,
        )
        assertEquals(572, withoutRemoved.properties.size)
        assertEquals(fromRest, TrackerEngine.build(withoutRemoved))
    }

    @Test
    fun `live - the DDP publication omits exactly the soft-deleted properties REST returns`() {
        assumeTrue("set MAGEHAND_IT=1 to run the live probe", System.getenv("MAGEHAND_IT") == "1")

        val token = devToken()
        val creatureId = Fixtures.SABRIEL_ID

        val restIds: Map<String, JsonObject>
        val ddpIds: Map<String, JsonObject>

        runBlocking {
            val body = OkHttpDiceCloudApi(
                OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .build(),
            ).fetchCreatureSnapshot(serverUrl, token, creatureId)
            restIds = CreatureSheet.fromSnapshotJson(body, creatureId).properties

            DdpClient.okHttp(
                url = "wss://dnd.example.com/websocket",
                resumeTokenProvider = { token },
            ).use { client ->
                client.connect()
                client.awaitLive()
                val subscription = client.subscribe("singleCharacter", ejsonParams(creatureId))
                subscription.awaitReady(60.seconds)
                ddpIds = client.mirror.documents(CreatureSheet.CREATURE_PROPERTIES)
                subscription.stop()
            }
        }

        val onlyInRest = restIds.keys - ddpIds.keys
        val onlyInDdp = ddpIds.keys - restIds.keys

        val softDeletedInRest = restIds.values.filter { (it["removed"] as? JsonPrimitive)?.content == "true" }

        println("LIVE DELTA: REST=${restIds.size} DDP=${ddpIds.size} onlyInRest=${onlyInRest.size} onlyInDdp=${onlyInDdp.size}")
        println("  soft-deleted (removed:true) still in the REST body: ${softDeletedInRest.size}")
        println(
            "  the fixture's soft-deleted item ${Fixtures.REMOVED_PROPERTY_ID}: " +
                "inREST=${Fixtures.REMOVED_PROPERTY_ID in restIds} inDDP=${Fixtures.REMOVED_PROPERTY_ID in ddpIds}",
        )
        onlyInRest.forEach { id ->
            val doc = restIds.getValue(id)
            println("  only in REST: $id type=${doc["type"]} name=${doc["name"]} removed=${doc["removed"]} removedAt=${doc["removedAt"]}")
        }
        onlyInDdp.forEach { println("  only in DDP: $it -> ${ddpIds[it]}") }

        // The invariant, not the count: the count is time-dependent because DiceCloud
        // eventually purges soft-deleted properties, at which point the delta is zero.
        assertTrue("the publication must not invent documents REST does not have", onlyInDdp.isEmpty())
        assertTrue(
            "every REST-only property must be soft-deleted; offenders: " +
                onlyInRest.filter { (restIds.getValue(it)["removed"] as? JsonPrimitive)?.content != "true" },
            onlyInRest.all { (restIds.getValue(it)["removed"] as? JsonPrimitive)?.content == "true" },
        )
        assertEquals("REST minus its soft-deleted rows must equal the publication", restIds.size - onlyInRest.size, ddpIds.size)
        assertTrue("no soft-deleted property may reach the mirror", ddpIds.keys.none { it in softDeletedInRest.map { d -> (d["_id"] as JsonPrimitive).content } })
    }

    /** Same single copy of the dev token WP3's live probe reads. */
    private fun devToken(): String {
        System.getenv("MAGEHAND_DEV_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        val repoRoot = System.getProperty("magehand.repoRoot")
            ?: error("magehand.repoRoot system property not set by the build script")
        val doc = File(repoRoot, "docs/dicecloud-api.md")
        assertTrue("missing ${doc.path}", doc.isFile)
        return requireNotNull(
            Regex("```\\s*\\n([A-Za-z0-9]{20,})\\s*\\n```").find(doc.readText())?.groupValues?.get(1),
        ) { "no dev token code block found in ${doc.path}" }
    }
}
