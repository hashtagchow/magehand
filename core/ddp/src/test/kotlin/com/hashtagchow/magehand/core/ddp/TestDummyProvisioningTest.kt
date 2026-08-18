package com.hashtagchow.magehand.core.ddp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Provisions — and re-verifies — the sacrificial **"MageHand Test Dummy"** creature that
 * WP7's live write probe runs against.
 *
 * ```
 * MAGEHAND_IT=1 MAGEHAND_SERVER=wss://<host>/websocket MAGEHAND_TOKEN=<resume token> \
 *   ./gradlew :core:ddp:test --tests '*TestDummyProvisioning*'
 * ```
 *
 * ### Why this lives in the repo rather than in a shell history
 *
 * docs/design/08-testing-and-release.md requires the first live mutation to land on a
 * dedicated creature, never on a player's sheet. That guarantee is only as good as the
 * ability to *rebuild* the creature — if the dummy is deleted, or the server is restored
 * from a backup, the next person needs the exact property shapes back, not a description
 * of them. This is that recipe, executable.
 *
 * ### Why it is idempotent
 *
 * It reads `MAGEHAND_DUMMY_ID` and, when set, **verifies** the existing creature instead of
 * inserting a second one. Running it twice must not litter the DM's character list with
 * near-identical dummies, and a second dummy would make "which one did the probe touch?" a
 * real question. With no id it inserts one and prints the id to paste into
 * docs/dicecloud-api.md.
 *
 * ### Safety
 *
 * The only creature this test can touch is the one it created (or the one whose id was
 * handed to it). Party ids are refused outright by [refuseKnownPartyIds] — a fat-fingered
 * `MAGEHAND_DUMMY_ID` pointing at a player's sheet fails the test rather than inserting
 * properties into it.
 *
 * ### Method signatures, learned live (not from the docs)
 *
 * - `creatures.insertCreature` **requires `startingLevel`** — `{name}` alone is a schema
 *   error the server reports as a bare 500. docs/design/02-ddp-and-api.md lists the method
 *   without it.
 * - `creatureProperties.insert` takes `{creatureProperty, parentRef}`, and every property
 *   **requires `order`**. `parentRef` is `{collection:'creatures', id:<creatureId>}` for a
 *   root-level property.
 * - `spellSlotLevel` and `baseValue` go in as `{calculation:"<n>"}` and come back as the
 *   `_calculation` wrapper WP4 §6.4 documents.
 */
class TestDummyProvisioningTest {

    @Before
    fun requireOptIn() {
        assumeTrue(
            "live provisioning test — set MAGEHAND_IT=1 to run it",
            System.getenv("MAGEHAND_IT") == "1",
        )
        assertNotNull(
            "MAGEHAND_SERVER (wss://…/websocket) is required; no server URL ships in this repo's code",
            System.getenv(ENV_SERVER),
        )
        assertNotNull("MAGEHAND_TOKEN is required (see docs/dicecloud-api.md)", System.getenv(ENV_TOKEN))
    }

    @Test
    fun the_test_dummy_exists_with_every_property_the_write_probe_needs() = runBlocking {
        val client = DdpClient.okHttp(
            url = System.getenv(ENV_SERVER),
            config = DdpClientConfig(handshakeTimeout = 30.seconds, methodTimeout = 30.seconds),
            resumeTokenProvider = { System.getenv(ENV_TOKEN) },
        )

        try {
            client.connect(45.seconds)
            val owner = client.userId.value
            println("== logged in as $owner")

            val creatureId = System.getenv(ENV_DUMMY_ID)?.takeIf { it.isNotBlank() }
                ?.also(::refuseKnownPartyIds)
                ?: insertDummy(client)

            val sub = client.subscribe("singleCharacter", ejsonParams(creatureId))
            sub.awaitReady(120.seconds)

            val creature = client.mirror.documents("creatures")[creatureId]
            assertNotNull("the dummy must exist and be readable", creature)
            assertEquals(DUMMY_NAME, creature!!.text("name"))
            assertEquals("the dummy must belong to the account the probe signs in as", owner, creature.text("owner"))

            val properties = client.mirror.documents("creatureProperties").values.toList()
            println("== ${properties.size} properties on $creatureId")

            // Insert whatever is missing, so a half-provisioned dummy heals rather than
            // failing the probe three steps in.
            REQUIRED.forEach { required ->
                if (properties.none(required.matches)) {
                    val id = insertProperty(client, creatureId, required.document(properties.size))
                    println("== inserted ${required.label} → $id")
                }
            }

            sub.stop()
            val recheck = client.subscribe("singleCharacter", ejsonParams(creatureId))
            recheck.awaitReady(120.seconds)
            val finalProperties = client.mirror.documents("creatureProperties").values.toList()

            REQUIRED.forEach { required ->
                val found = finalProperties.firstOrNull(required.matches)
                assertNotNull("the dummy is missing its ${required.label}", found)
                println("== ${required.label.padEnd(12)} ${found!!.text("_id")}  ${found.text("name")}")
            }

            println("== DUMMY creatureId=$creatureId")
            println("== record these in docs/dicecloud-api.md")
            recheck.stop()
        } finally {
            client.close()
        }
    }

    private suspend fun insertDummy(client: DdpClient): String {
        val result = client.call(
            "creatures.insertCreature",
            listOf(
                buildJsonObject {
                    put("name", DUMMY_NAME)
                    // Learned live: without this the method fails schema validation and the
                    // server reports a bare "Internal server error".
                    put("startingLevel", 1)
                },
            ),
        )
        val id = (result as? JsonPrimitive)?.contentOrNull
        assertNotNull("insertCreature must return the new creature id", id)
        println("== created $DUMMY_NAME → $id")
        return id!!
    }

    private suspend fun insertProperty(
        client: DdpClient,
        creatureId: String,
        property: JsonObject,
    ): String? = (
        client.call(
            "creatureProperties.insert",
            listOf(
                buildJsonObject {
                    put("creatureProperty", property)
                    putJsonObject("parentRef") {
                        put("collection", "creatures")
                        put("id", creatureId)
                    }
                },
            ),
        ) as? JsonPrimitive
        )?.contentOrNull

    /**
     * The five shapes WP7's probe exercises, one per write path in
     * docs/design/03-data-model.md §Write semantics.
     */
    private class Required(
        val label: String,
        val matches: (JsonObject) -> Boolean,
        val document: (order: Int) -> JsonObject,
    )

    private companion object {
        const val ENV_SERVER = "MAGEHAND_SERVER"
        const val ENV_TOKEN = "MAGEHAND_TOKEN"
        const val ENV_DUMMY_ID = "MAGEHAND_DUMMY_ID"
        const val DUMMY_NAME = "MageHand Test Dummy"

        /**
         * The party's sheets. Never writable by any test, ever
         * (docs/design/08-testing-and-release.md §Test data).
         *
         * The real ids are NOT in this public source: export them (comma-separated)
         * as MAGEHAND_PARTY_IDS when running live probes against a real table's
         * server, so the guard protects the actual party. The baked fallback only
         * keeps the assertion exercised in ungated runs.
         */
        val PARTY_IDS: Set<String> =
            System.getenv("MAGEHAND_PARTY_IDS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                ?: setOf("FakeCreature23456")

        fun refuseKnownPartyIds(id: String) = assertTrue(
            "$id is a player's character. The provisioning test may only touch the dummy.",
            id !in PARTY_IDS,
        )

        val REQUIRED = listOf(
            Required(
                label = "hitPoints",
                matches = { it.text("variableName") == "hitPoints" },
                document = { order ->
                    buildJsonObject {
                        put("order", order)
                        put("type", "attribute")
                        put("attributeType", "healthBar")
                        put("name", "Hit Points")
                        put("variableName", "hitPoints")
                        putJsonObject("baseValue") { put("calculation", "20") }
                    }
                },
            ),
            Required(
                label = "spell slot",
                matches = { it.text("attributeType") == "spellSlot" && it.text("reset") == "longRest" },
                document = { order ->
                    buildJsonObject {
                        put("order", order)
                        put("type", "attribute")
                        put("attributeType", "spellSlot")
                        put("name", "1st Level")
                        put("variableName", "dummySpellSlot1")
                        put("reset", "longRest")
                        putJsonObject("baseValue") { put("calculation", "3") }
                        putJsonObject("spellSlotLevel") { put("calculation", "1") }
                    }
                },
            ),
            Required(
                label = "resource",
                matches = { it.text("attributeType") == "resource" },
                document = { order ->
                    buildJsonObject {
                        put("order", order)
                        put("type", "attribute")
                        put("attributeType", "resource")
                        put("name", "Rage")
                        put("variableName", "dummyRage")
                        put("reset", "longRest")
                        putJsonObject("baseValue") { put("calculation", "2") }
                    }
                },
            ),
            Required(
                label = "item",
                matches = { it.text("type") == "item" },
                document = { order ->
                    buildJsonObject {
                        put("order", order)
                        put("type", "item")
                        put("name", "Potion of Healing")
                        put("quantity", 5)
                    }
                },
            ),
            Required(
                // Condition-free, so `TrackerEngine`'s flippable-toggle rule surfaces it as
                // a chip (docs/verification/WP4.md §6.2).
                label = "toggle",
                matches = { it.text("type") == "toggle" && !it.containsKey("condition") },
                document = { order ->
                    buildJsonObject {
                        put("order", order)
                        put("type", "toggle")
                        put("name", "Bless")
                    }
                },
            ),
        )

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
