package com.hashtagchow.magehand.core.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The WP3 acceptance probe against the live server, gated on `MAGEHAND_IT=1`.
 *
 * **Strictly read-only.** docs/design/07-build-plan.md rules out a live login
 * probe (there is no throwaway account and no test password in the repo), so this
 * proves the authenticated REST path a different way: it replays the committed
 * dev resume token against `GET /api/creature/:id` and asserts a real, parseable
 * sheet comes back. No password is ever sent, and nothing is mutated.
 *
 * Run with:
 * ```
 * MAGEHAND_IT=1 ./gradlew :core:data:test --tests '*LiveSnapshotIntegrationTest*'
 * ```
 */
class LiveSnapshotIntegrationTest {

    private val serverUrl = System.getenv("MAGEHAND_IT_SERVER") ?: "https://dicecloud.com"

    /** A creature the probe token may read — no id is baked into the source. */
    private val creatureId = System.getenv("MAGEHAND_IT_CREATURE_ID")
        ?: "unset-see-MAGEHAND_IT_CREATURE_ID"

    private fun api(): DiceCloudApi = OkHttpDiceCloudApi(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // The server force-recomputes a stale sheet before answering.
            .readTimeout(90, TimeUnit.SECONDS)
            .build(),
    )

    /**
     * The dev token lives in exactly one place — `docs/dicecloud-api.md`, which is
     * the operator-approved record of it. Reading it from there (rather than
     * pasting it into test source) keeps the repo down to a single copy to rotate.
     * `MAGEHAND_DEV_TOKEN` overrides it for a freshly minted token.
     */
    private fun devToken(): String {
        System.getenv("MAGEHAND_DEV_TOKEN")?.takeIf { it.isNotBlank() }?.let { return it }
        val repoRoot = System.getProperty("magehand.repoRoot")
            ?: error("magehand.repoRoot system property not set by the build script")
        val doc = File(repoRoot, "docs/dicecloud-api.md")
        assertTrue("missing ${doc.path}", doc.isFile)
        val token = Regex("```\\s*\\n([A-Za-z0-9]{20,})\\s*\\n```")
            .find(doc.readText())
            ?.groupValues
            ?.get(1)
        return requireNotNull(token) { "no dev token code block found in ${doc.path}" }
    }

    @Test
    fun `fetchCreatureSnapshot returns a parseable sheet with at least 500 creatureProperties`() {
        assumeTrue("set MAGEHAND_IT=1 to run the live probe", System.getenv("MAGEHAND_IT") == "1")

        val body = runBlocking { api().fetchCreatureSnapshot(serverUrl, devToken(), creatureId) }

        // A returned body already means HTTP 200: every other status maps to a
        // typed ApiException, so reaching this line is the status assertion.
        val root = Json.parseToJsonElement(body) as JsonObject

        val creatures = root["creatures"] as? JsonArray
        assertNotNull("no creatures array in the response", creatures)
        assertEquals("expected exactly the requested creature", 1, creatures!!.size)

        val properties = root["creatureProperties"] as? JsonArray
        assertNotNull("no creatureProperties array in the response", properties)
        assertTrue(
            "expected >= 500 creatureProperties, got ${properties!!.size}",
            properties.size >= 500,
        )

        assertNotNull("no creatureVariables in the response", root["creatureVariables"])

        println(
            "LIVE PROBE OK: $serverUrl/api/creature/$creatureId -> " +
                "${body.length} bytes, ${properties.size} creatureProperties, " +
                "${creatures.size} creature(s)",
        )
    }

    @Test
    fun `a bad token is rejected by the live server rather than silently succeeding`() {
        assumeTrue("set MAGEHAND_IT=1 to run the live probe", System.getenv("MAGEHAND_IT") == "1")

        // Proves the 200 above is actually gated on the bearer token — a server
        // that answered regardless would make the probe above meaningless.
        val thrown = try {
            runBlocking {
                api().fetchCreatureSnapshot(serverUrl, "definitely-not-a-valid-token", creatureId)
            }
            null
        } catch (e: ApiException) {
            e
        }

        assertTrue(
            "expected TokenRejected or NotFound, got ${thrown?.let { it::class.simpleName }}",
            thrown is ApiException.TokenRejected || thrown is ApiException.NotFound,
        )
        println("LIVE PROBE OK: invalid token rejected with ${thrown!!::class.simpleName}")
    }
}
