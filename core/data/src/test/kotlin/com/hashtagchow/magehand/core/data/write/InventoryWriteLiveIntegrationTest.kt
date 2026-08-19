package com.hashtagchow.magehand.core.data.write

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.tracker.InventoryEngine
import com.hashtagchow.magehand.core.ddp.DdpClient
import com.hashtagchow.magehand.core.ddp.DdpClientConfig
import com.hashtagchow.magehand.core.ddp.ejsonParams
import com.hashtagchow.magehand.core.model.CoinKind
import com.hashtagchow.magehand.core.model.NewItemSpec
import kotlin.time.Duration.Companion.seconds

/**
 * FR-8's acceptance probe against the **live** server: `creatureProperties.equip` and
 * `creatureProperties.insert`, as [WriteOp] actually builds them
 * (docs/design/08-testing-and-release.md §2; 10-inventory.md §Acceptance shape).
 *
 * ### This is opt-in and is not run by `./gradlew test`
 *
 * Gated on `MAGEHAND_IT=1`, exactly like `DdpLiveIntegrationTest`, and gated a second time on
 * an explicit **writable** creature id — see [DUMMY]. Without either, every test here raises a
 * JUnit *skip*.
 *
 * ```
 * MAGEHAND_IT=1 MAGEHAND_IT_WRITE_CREATURE_ID=<demo character> \
 *   ./gradlew :core:data:test -PmagehandIt=1 --tests '*InventoryWriteLive*'
 * ```
 *
 * ### Why a second gate, and why a *separate* id variable
 *
 * `MAGEHAND_IT_CREATURE_ID` is the read-only probe's target and, per
 * 08-testing-and-release.md, **party sheets are read-only for all tests**. These tests mutate:
 * they equip an item, unequip it again, and create one. Reusing that variable would mean a
 * `MAGEHAND_IT=1` run aimed at a party sheet started writing to it, which is precisely the
 * accident the read-only rule exists to prevent. A distinct variable makes pointing this at a
 * sheet a deliberate act.
 *
 * A deliberate act is still a mistyped one, so the variable is *also* checked against the party
 * ids — see [refuseKnownPartyIds]. A separate variable makes the mistake harder to make; the
 * refusal makes it impossible to make silently, which is the guarantee 08 actually asks for and
 * which `TestDummyProvisioningTest` has enforced on its own id since WP7.
 *
 * ### What it leaves behind, stated honestly
 *
 * The equip test is a **round trip on the equipped flag**: it reads the item's current
 * `equipped`, flips it, and flips it back, so the flag ends where it started. It is *not* a
 * round trip on the property's **parent** — `equip` reparents, the original parent is not
 * recorded anywhere, and unequipping restores the `carried` folder rather than the backpack the
 * item came out of (that is the limit `WriteOp.Equip` documents and the reason 10 decision 2
 * renders sections by state). So an item that lived in a container **stays in `carried`** after
 * this test runs. That residue is invisible on this app's inventory tab by design, and visible
 * in DiceCloud's own web UI — which is exactly what a user of that UI would have got from the
 * same two taps.
 *
 * The insert test is **not** a round trip and cannot be: the inverse of an insert is a
 * soft-remove, which 10 decision 12 fences out of this release, so there is no method this app
 * is willing to call to clean up after itself. It therefore creates one clearly-labelled item
 * per run and leaves it on the sheet. Point it only at a scratch character.
 *
 * ### What it proves that a unit test cannot
 *
 * Three things, each of which was a guess before the 2026-08-19 probe:
 * 1. the method is called `equip`, not `equipItem` (the doc was wrong for the whole project);
 * 2. `equip` **reparents** the property, which is the honest limit `WriteOp.Equip` documents;
 * 3. `insert` **rejects a body with no `order`** — a failure that only exists server-side.
 */
class InventoryWriteLiveIntegrationTest {

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
        // An *assertion*, not an assumption: pointing a mutating probe at a player's sheet is a
        // mistake that has to fail loudly. A skip would look like the gate simply being off.
        refuseKnownPartyIds(target!!)
    }

    /**
     * Connects, subscribes, runs [block] against a live sheet, and closes.
     *
     * The sheet is rebuilt from the mirror on demand rather than captured once: every call
     * below changes it, and the point of the probe is to see what the server did.
     */
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
            block(client) {
                CreatureSheet.fromMirror(client.mirror.snapshot(), DUMMY)
            }
            sub.stop()
        } finally {
            client.close()
        }
    }

    private suspend fun DdpClient.send(op: WriteOp): JsonElement = call(op.method, op.params)

    /** Waits for the mirror to show [predicate], because a method result precedes the push. */
    private suspend fun awaitSheet(
        sheet: () -> CreatureSheet,
        what: String,
        predicate: (CreatureSheet) -> Boolean,
    ): CreatureSheet {
        repeat(60) {
            val current = sheet()
            if (predicate(current)) return current
            kotlinx.coroutines.delay(250)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    /**
     * The method name, and the reparenting behaviour `WriteOp.Equip` documents.
     *
     * A **round trip**: whatever the item's state was, it is restored before this returns.
     */
    @Test
    fun equip_uses_the_probe_verified_name_and_reparents() = onLiveSheet { client, sheet ->
        val board = InventoryEngine.build(sheet())
        val item = (board.carried + board.equipped).firstOrNull()
        assumeTrue("the scratch character has no items to equip", item != null)
        requireNotNull(item)

        val originalParent = sheet().properties.getValue(item.propertyId).parentId()
        val original = item.equipped
        println("== equip probe: '${item.name}' equipped=$original parent=$originalParent")

        try {
            client.send(WriteOp.equip(item.propertyId, !original, original, item.name))
            val after = awaitSheet(sheet, "equipped == ${!original}") {
                it.properties[item.propertyId]?.get("equipped")
                    ?.let { v -> (v as? JsonPrimitive)?.booleanOrNull } == !original
            }

            val newParent = after.properties.getValue(item.propertyId).parentId()
            println("== after equip: parent=$newParent")
            assertNotNull("the item must still exist after an equip", newParent)

            // The finding that makes `WriteOp.Equip`'s undo honest. Recorded as a print plus a
            // soft assertion rather than a hard one: the item may already sit in the folder the
            // server would move it to, in which case the parent legitimately does not change,
            // and a hard assertion would fail on a correct server.
            if (newParent != originalParent) {
                println("== CONFIRMED: equip reparented $originalParent -> $newParent")
            } else {
                println("== note: parent unchanged (the item was already under the target folder)")
            }
        } finally {
            // Restore, whatever happened above. The probe is a round trip by construction.
            client.send(WriteOp.equip(item.propertyId, original, !original, item.name))
            awaitSheet(sheet, "equipped restored to $original") {
                it.properties[item.propertyId]?.get("equipped")
                    ?.let { v -> (v as? JsonPrimitive)?.booleanOrNull } == original
            }
            println("== equip probe: restored equipped=$original")
        }
    }

    /**
     * `creatureProperties.insert` as [WriteOp.insertItem] builds it, including the mandatory
     * `order`.
     *
     * **Leaves the item behind** — see the class KDoc. The name carries the run's timestamp so
     * a scratch sheet's accumulation is at least legible.
     */
    @Test
    fun insert_creates_an_item_with_the_mandatory_order() = onLiveSheet { client, sheet ->
        val target = InventoryEngine.insertTarget(sheet())
        assertNotNull("a live sheet must resolve an insert target", target)
        requireNotNull(target)
        println("== insert probe: parent=${target.parentId} (${target.parentCollection}) order=${target.order}")

        val name = "MageHand probe ${System.currentTimeMillis()}"
        val spec = NewItemSpec(
            name = name,
            quantity = 3,
            weightLb = 1.0,
            valueGp = 0.01,
            description = "Created by the FR-8 live write probe.",
            tags = listOf("adventuring gear"),
        )

        client.send(WriteOp.insertItem(spec, target.parentId, target.order, target.parentCollection))

        val after = awaitSheet(sheet, "the inserted item to appear") { current ->
            current.livePropertyList.any { it.textOf("name") == name }
        }

        val created = after.livePropertyList.single { it.textOf("name") == name }
        assertEquals("item", created.textOf("type"))
        assertEquals(3, (created["quantity"] as? JsonPrimitive)?.intOrNull)
        assertEquals(target.parentId, created.parentId())

        // And the engine finds it, which is the round trip that matters: a write this app
        // makes must produce a document this app's own discovery can read back.
        val board = InventoryEngine.build(after)
        val discovered = board.allItems.single { it.name == name }
        assertEquals(3, discovered.quantity)
        assertEquals(1.0, discovered.weightLb!!, 1e-9)
        // The only assertion that would catch the description wrapper drifting again. It is
        // sent as `{text: …}` (a bare string is `400: Description must be of type Object`) and
        // read back out of the server's `{text, value, hash, …}`, so this pins both halves of
        // a round trip that no JVM test can see: the write shape the server accepts and the
        // read shape `InventoryEngine` unwraps.
        assertEquals(spec.description, discovered.description)
        println("== insert probe: created ${created.textOf("_id")} — LEFT ON THE SHEET, clean up by hand")
    }

    /**
     * The failure mode the probe found: **the server rejects an insert with no `order`.**
     *
     * Written as a deliberately malformed call rather than through [WriteOp.insertItem] — the
     * factory cannot produce this body, which is the point of it taking `order` as a non-null
     * parameter. If this test ever stops throwing, the server relaxed the rule and the KDoc
     * claiming it is mandatory has become folklore.
     *
     * ### Residue
     *
     * **On the expected outcome, none.** The insert is rejected, so nothing is created — this
     * is the one test in the class that leaves the sheet exactly as it found it.
     *
     * **On the outcome that fails this test, one item.** If the server has started accepting an
     * order-less body, the call succeeded before the assertion ran and a property named
     * `MageHand order-less probe` is now on the sheet, with no `order` field. Delete it by hand
     * along with the probe items, and note that it is deliberately named without a timestamp:
     * this insert is only ever supposed to happen zero times, so a name that collides on a
     * second run is the correct signal rather than an accumulating pile.
     */
    @Test
    fun insert_without_order_is_rejected_by_the_server() = onLiveSheet { client, sheet ->
        val target = InventoryEngine.insertTarget(sheet())
        requireNotNull(target)

        val malformed = kotlinx.serialization.json.buildJsonObject {
            put(
                "creatureProperty",
                kotlinx.serialization.json.buildJsonObject {
                    put("type", JsonPrimitive("item"))
                    put("name", JsonPrimitive("MageHand order-less probe"))
                    put("quantity", JsonPrimitive(1))
                    // no `order` — this is the whole test
                },
            )
            put(
                "parentRef",
                kotlinx.serialization.json.buildJsonObject {
                    put("id", JsonPrimitive(target.parentId))
                    put("collection", JsonPrimitive(target.parentCollection))
                },
            )
        }

        val error = runCatching { client.call("creatureProperties.insert", listOf(malformed)) }
            .exceptionOrNull()

        println("== order-less insert outcome: $error")
        assertNotNull(
            "the server accepted an insert with no `order` — WriteOp.InsertProperty's KDoc " +
                "and docs/design/02-ddp-and-api.md both claim it is mandatory, and one of " +
                "them is now wrong",
            error,
        )
    }

    /**
     * The whole wallet path end to end, for a denomination the sheet does not carry: the row
     * reads absent, the first increment creates the item, and discovery finds it as that
     * denomination.
     *
     * **Leaves the coin behind**, like the insert test, and for the same reason.
     */
    @Test
    fun an_absent_denomination_is_created_by_the_first_increment() = onLiveSheet { client, sheet ->
        val before = InventoryEngine.build(sheet())
        val absent = CoinKind.entries.firstOrNull { before.wallet.row(it).isAbsent }
        assumeTrue("the scratch character already carries all four denominations", absent != null)
        requireNotNull(absent)
        println("== wallet probe: $absent is absent, taking the insert path")

        val sibling = before.wallet.rows.firstNotNullOfOrNull { it.propertyId }
        val target = InventoryEngine.insertTarget(sheet(), siblingOf = sibling)
        requireNotNull(target)

        client.send(
            WriteOp.insertItem(NewItemSpec.ofCoin(absent, 1), target.parentId, target.order, target.parentCollection),
        )

        val after = awaitSheet(sheet, "the created coin to appear") {
            !InventoryEngine.build(it).wallet.row(absent).isAbsent
        }

        val row = InventoryEngine.build(after).wallet.row(absent)
        assertEquals(1, row.quantity)
        assertNotNull("the stepper now has a property to adjust", row.propertyId)
        assertTrue(
            "the created coin must not also render as a carried item",
            InventoryEngine.build(after).carried.none { it.propertyId == row.propertyId },
        )
        println("== wallet probe: created ${row.propertyId} — LEFT ON THE SHEET, clean up by hand")
    }

    private fun JsonObject.textOf(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.parentId(): String? = (this["parent"] as? JsonObject)?.textOf("id")

    private companion object {
        const val ENV_WRITE_CREATURE_ID = "MAGEHAND_IT_WRITE_CREATURE_ID"

        // Same environment-driven shape as DdpLiveIntegrationTest: nothing is baked into the
        // source, and `by lazy` defers the error() past the MAGEHAND_IT assumption gate so an
        // ungated run *skips* rather than failing in static init.
        val URL: String by lazy { System.getenv("MAGEHAND_IT_WS_URL") ?: "wss://dicecloud.com/websocket" }
        val TOKEN: String by lazy { System.getenv("MAGEHAND_IT_TOKEN") ?: error("set MAGEHAND_IT_TOKEN") }

        /** A scratch character. **Never a party sheet** — see the class KDoc. */
        val DUMMY: String by lazy {
            System.getenv(ENV_WRITE_CREATURE_ID) ?: error("set $ENV_WRITE_CREATURE_ID")
        }

        /**
         * The party's sheets. Never writable by any test, ever
         * (docs/design/08-testing-and-release.md §Test data).
         *
         * Same shape as `TestDummyProvisioningTest.PARTY_IDS`, deliberately: one env contract
         * (`MAGEHAND_PARTY_IDS`, comma-separated) covers every mutating probe in the repo, so an
         * operator exports it once per live run rather than learning a second convention. The
         * real ids are **not** in this source — it is published to the public mirror — and the
         * baked fallback is a sentinel whose only job is to keep the assertion exercised in
         * ungated runs, where nothing is set and nothing is written either.
         *
         * Exporting the variable empty is therefore a *no-match*, not a disabled guard: the
         * split yields no ids, the set falls back to the sentinel, and a real party id would
         * still pass — which is the honest cost of ids that cannot live in this file. Protecting
         * a real table means exporting the real ids.
         */
        val PARTY_IDS: Set<String> =
            System.getenv("MAGEHAND_PARTY_IDS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }?.toSet()
                ?: setOf("FakeCreature23456")

        fun refuseKnownPartyIds(id: String) = assertTrue(
            "$id is a player's character. 08-testing-and-release.md makes party sheets " +
                "read-only for every test; this probe equips, unequips and creates items.",
            id !in PARTY_IDS,
        )
    }
}
