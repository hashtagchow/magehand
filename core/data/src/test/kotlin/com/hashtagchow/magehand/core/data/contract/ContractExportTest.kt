package com.hashtagchow.magehand.core.data.contract

import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.tracker.InventoryEngine
import com.hashtagchow.magehand.core.data.tracker.TrackerEngine
import com.hashtagchow.magehand.core.data.write.WriteOp
import com.hashtagchow.magehand.core.data.write.WriteQueueConfig
import com.hashtagchow.magehand.core.ddp.DdpClientConfig
import com.hashtagchow.magehand.core.ddp.MeteorId
import com.hashtagchow.magehand.core.model.DeathSaves
import com.hashtagchow.magehand.core.model.InventoryBoard
import com.hashtagchow.magehand.core.model.ItemCatalog
import com.hashtagchow.magehand.core.model.TrackerBoard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The upstream guarantee behind WebHand's vendored contract.
 *
 * ### Two jobs, and neither one is enough alone
 *
 * 1. **The golden pin.** Regenerating the export must reproduce the committed
 *    `contract-export/` byte for byte. That is what lets WebHand vendor these files and trust
 *    that a MageHand change which alters the wire — a new method, a different frame shape, a
 *    discovery rule edit — surfaces *here*, in MageHand's own suite, rather than as a bug in
 *    a downstream app three weeks later.
 *
 * 2. **The semantic assertions.** A golden test on its own pins whatever the code does today,
 *    including a regression: break `damage {set}` and the export changes, the pin fails, the
 *    next person regenerates and commits, and the golden file now certifies the bug. So every
 *    probe-established quirk is *also* asserted against the export's contents by name. The
 *    pin catches drift; these catch drift in the wrong direction.
 *
 * ### Writing the export
 *
 * `./gradlew exportContract` runs this class with `magehand.contract.write=true`, which makes
 * [writeOrPin] write the files instead of comparing them. Nothing else in the suite ever sets
 * it, so a plain `./gradlew test` can only ever *check*.
 */
class ContractExportTest {

    // =======================================================================
    // 1 — the pin
    // =======================================================================

    /**
     * Also a determinism check, and deliberately so.
     *
     * Under `./gradlew exportContract` the files on disk were written by [writeIfRequested] a
     * moment ago, from a *different* invocation of the emitter — a second DDP session, a
     * second set of client-minted subscription ids, a second run of both engines. Comparing
     * this run against that one is what proves the export has no hidden nondeterminism, which
     * is the property that makes the pin meaningful in the first place. A golden file that
     * only matched itself when nothing had changed would be no guarantee at all.
     */
    @Test
    fun `regenerating the export reproduces the committed files exactly`() {
        val committed = committedManifest()
        val generated = ContractExport.generate(
            // Provenance is inherited rather than recomputed — see the class KDoc's plumbing note.
            sourceCommit = committed.str("sourceCommit").orEmpty(),
            generatedOn = committed.str("generatedOn").orEmpty(),
        )
        for ((path, content) in generated) {
            val file = File(exportDir(), path)
            assertTrue(
                "$path is missing from the committed export. Run `./gradlew exportContract` and commit.",
                file.isFile,
            )
            assertEquals(
                "$path drifted from what the production code generates. If the change is " +
                    "intended, run `./gradlew exportContract`, commit, and tell webhand to " +
                    "re-sync its vendored contract.",
                content,
                file.readText(),
            )
        }
    }

    @Test
    fun `the committed manifest hashes match the committed files`() {
        val manifest = committedManifest()
        val listed = manifest["files"]!!.jsonArray.map { it.jsonObject }
        assertTrue("the manifest must list files", listed.isNotEmpty())

        for (entry in listed) {
            val path = entry.str("path")!!
            val file = File(exportDir(), path)
            assertTrue("manifest lists $path, which is not committed", file.isFile)
            val bytes = file.readBytes()
            assertEquals("sha256 mismatch for $path", entry.str("sha256"), ContractExport.sha256(bytes))
            assertEquals("byte count mismatch for $path", entry["bytes"]?.jsonPrimitive()?.intOrNull, bytes.size)
        }

        // The other direction: nothing may sit in the export unlisted, or a consumer verifying
        // against the manifest would import a file nobody vouched for.
        val listedPaths = listed.mapNotNull { it.str("path") }.toSet() + ContractExport.MANIFEST
        val onDisk = exportDir().walkTopDown().filter { it.isFile }
            .map { it.relativeTo(exportDir()).path.replace(File.separatorChar, '/') }
            .toSet()
        assertEquals("the export directory and the manifest disagree", listedPaths, onDisk)
    }

    @Test
    fun `the manifest states its provenance`() {
        val manifest = committedManifest()
        assertEquals(ContractExport.SCHEMA_VERSION, manifest["schemaVersion"]?.jsonPrimitive()?.intOrNull)
        val sha = manifest.str("sourceCommit").orEmpty()
        assertTrue("sourceCommit must be a 40-character git sha, was '$sha'", sha.matches(Regex("[0-9a-f]{40}")))
        val date = manifest.str("generatedOn").orEmpty()
        assertTrue("generatedOn must be an ISO date, was '$date'", date.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    // =======================================================================
    // 2 — public safety
    // =======================================================================

    /**
     * The export is vendored into a repo that may be mirrored publicly, so it is held to the
     * release gate's own forbidden list — read out of `tools/public-gate.sh` rather than
     * copied here, for the reason `magehand-release.sh` already gives: one list, several
     * consumers, and a copy is a thing that goes stale silently.
     */
    @Test
    fun `the export carries none of the release gate's forbidden strings`() {
        val patterns = forbiddenPatterns()
        assertTrue("could not read the pattern list out of tools/public-gate.sh", patterns.size >= 20)

        val offenders = mutableListOf<String>()
        for (file in exportDir().walkTopDown().filter { it.isFile }) {
            val text = file.readText()
            for (pattern in patterns) {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                regex.find(text)?.let { match ->
                    offenders += "${file.name}: /$pattern/ matched '${match.value}'"
                }
            }
        }
        assertEquals("forbidden strings in the contract export", emptyList<String>(), offenders)
    }

    /**
     * The gate's own positive control: a scan that can find nothing proves nothing. If
     * `dicecloud.com` ever stops appearing in the export, `tools/public-gate.sh <dir>` would
     * exit 2 ("gate broken") on it rather than passing — better to learn that here.
     */
    @Test
    fun `the export contains the public gate's positive control`() {
        val hit = exportDir().walkTopDown().filter { it.isFile }.any { it.readText().contains("dicecloud.com") }
        assertTrue(
            "the export must name dicecloud.com somewhere, or public-gate.sh's positive control fails",
            hit,
        )
    }

    @Test
    fun `every identifier in the export is a synthetic Meteor id`() {
        for (id in listOf(
            ContractFixtures.creatureId,
            ContractFixtures.userId,
            ContractFixtures.longswordId,
            ContractFixtures.potionId,
            ContractFixtures.backpackId,
        )) {
            assertTrue("'$id' is not a well-formed Meteor id", MeteorId.isValid(id))
        }
        // The alphabet excludes the characters a human misreads. Asserted rather than assumed,
        // because the fixture's claim to be "in the Meteor alphabet" rests on it.
        for (banned in listOf('0', '1', 'I', 'O', 'l', 'U', 'V')) {
            assertFalse("UNMISTAKABLE_CHARS must not contain '$banned'", banned in MeteorId.UNMISTAKABLE_CHARS)
        }
    }

    // =======================================================================
    // 3 — the quirks, asserted against the export's own bytes
    // =======================================================================

    @Test
    fun `insert sends description as an object and always supplies order`() {
        for (name in listOf("insert.catalogItem", "insert.customItem")) {
            val body = vector(name).frame().params().first().jsonObject["creatureProperty"]!!.jsonObject
            assertNotNull("$name must supply `order` — the server rejects the insert without it", body["order"])
            val description = body["description"]
            assertTrue(
                "$name must send `description` as an object {text: …}; a bare string is a 400",
                description is JsonObject && description["text"] is JsonPrimitive,
            )
        }
        // And the coin path, which states no description at all — the shape that let the
        // string-description bug ship green.
        val coinBody = vector("insert.coin").frame().params().first().jsonObject["creatureProperty"]!!.jsonObject
        assertNotNull("insert.coin must still supply `order`", coinBody["order"])
        assertNull("insert.coin states no description", coinBody["description"])
    }

    @Test
    fun `equip is recorded under its real name and documents the reparent`() {
        val equip = vector("equip.equip")
        assertEquals("creatureProperties.equip", equip.str("method"))
        assertTrue(
            "the equip vector must record that the server REPARENTS the property",
            equip.str("quirk").orEmpty().contains("REPARENT", ignoreCase = true),
        )
        val params = equip.frame().params().first().jsonObject
        assertNotNull(params["_id"])
        assertEquals(true, params["equipped"]?.jsonPrimitive()?.booleanOrNull)
        // Its inverse is the opposite equip and nothing else.
        val inverse = equip["inverseParams"]!!.jsonObject
        assertEquals("creatureProperties.equip", inverse.str("method"))
        assertEquals(false, inverse["params"]!!.jsonArray.first().jsonObject["equipped"]?.jsonPrimitive()?.booleanOrNull)
    }

    @Test
    fun `damage set carries the remaining value not the complement`() {
        val params = vector("damage.setRemaining").frame().params().first().jsonObject
        assertEquals("set", params.str("operation"))
        // The fixture's HP row is 42 total / 30 remaining and the vector asks for 20. The
        // corrected reading sends 20; design 03's original `total − desired` would send 22.
        assertEquals(
            "`set` takes the value the row should SHOW, not total − desired",
            20,
            params["value"]?.jsonPrimitive()?.intOrNull,
        )
    }

    @Test
    fun `adjustQuantity increment is a consumption amount`() {
        val consume = vector("adjustQuantity.consumeItem").frame().params().first().jsonObject
        assertEquals("increment", consume.str("operation"))
        assertEquals(
            "consuming one item is increment +1 — the server counts UP as the stack counts DOWN",
            1,
            consume["value"]?.jsonPrimitive()?.intOrNull,
        )
        val acquire = vector("adjustQuantity.addItem").frame().params().first().jsonObject
        assertEquals(
            "acquiring two is a NEGATIVE increment",
            -2,
            acquire["value"]?.jsonPrimitive()?.intOrNull,
        )
    }

    @Test
    fun `damage spend and restore are opposite increments`() {
        val spend = vector("damage.spendSpellSlot").frame().params().first().jsonObject
        assertEquals("increment", spend.str("operation"))
        assertEquals(1, spend["value"]?.jsonPrimitive()?.intOrNull)
        val restore = vector("damage.restoreSpellSlot").frame().params().first().jsonObject
        assertEquals(-1, restore["value"]?.jsonPrimitive()?.intOrNull)
    }

    @Test
    fun `softRemove pairs with restore and both are exported`() {
        val remove = vector("softRemove.item")
        assertEquals("creatureProperties.softRemove", remove.str("method"))
        assertEquals(
            "softRemove must invert into restore — the one op whose inverse is a different method",
            "creatureProperties.restore",
            remove["inverseParams"]!!.jsonObject.str("method"),
        )
        val restore = vector("restore.item")
        assertEquals("creatureProperties.restore", restore.str("method"))
        assertEquals(
            "the pair must name the same property",
            remove.frame().params().first().jsonObject.str("_id"),
            restore.frame().params().first().jsonObject.str("_id"),
        )
    }

    @Test
    fun `organizeDoc carries docRef parentRef and order`() {
        val params = vector("organizeDoc.moveIntoContainer").frame().params().first().jsonObject
        val docRef = params["docRef"]!!.jsonObject
        assertNotNull("docRef.id", docRef["id"])
        assertEquals("creatureProperties", docRef.str("collection"))
        val parentRef = params["parentRef"]!!.jsonObject
        assertNotNull("parentRef.id", parentRef["id"])
        assertNotNull("parentRef.collection", parentRef["collection"])
        assertNotNull("organizeDoc requires an order", params["order"])
    }

    @Test
    fun `every v1 catalog method is either exported as a vector or recorded as uncalled`() {
        val document = documentAt("ddp/method-vectors.json")
        val exported = document["vectors"]!!.jsonArray.map { it.jsonObject.str("method") }.toSet()
        val uncalled = document["documentedNotCalled"]!!.jsonArray.map { it.jsonObject.str("method") }.toSet()
        val handshake = setOf("login")

        // Design 02's v1 method catalog, in full. Adding a method to the app without adding it
        // here is the drift this assertion exists to stop.
        val catalog = setOf(
            "login",
            "creatureProperties.damage",
            "creatureProperties.adjustQuantity",
            "creature.methods.rest",
            "creatureProperties.flipToggle",
            "creatureProperties.update",
            "creatureProperties.equip",
            "creatureProperties.insert",
            "creatureProperties.softRemove",
            "creatureProperties.restore",
            "organize.organizeDoc",
            // FR-28 (docs/design/17-use-action.md decision 7). Not in design 02's original table
            // — this wave adds them — and catalogued here for the same reason everything else is:
            // so that a method reaching the app without reaching the export fails HERE.
            "creatureProperties.doAction",
            "creatureProperties.doCastSpell",
            "creatures.insertCreature",
            "creatures.update",
        )
        assertEquals(
            "design 02's method catalog and the export disagree",
            emptySet<String>(),
            catalog - exported - uncalled - handshake,
        )

        // …and the other direction, which this assertion did not cover and needed to.
        //
        // The set difference above answers "is every catalogued method accounted for", and a
        // NEW method added to the app and exported as a vector satisfies it without ever being
        // catalogued — the catalog is a hand-written list, and nothing was checking that the
        // list had heard of what the export contains. FR-28 is exactly that case: two new
        // methods, two new vectors, and the assertion above would have stayed green with the
        // catalog untouched. A drift defence that only fires in the direction somebody
        // remembered is the one that was already there.
        assertEquals(
            "a method is exported as a vector but is not in design 02's catalog above. Add it " +
                "there (and to WritePostureTest's mutationMethods) rather than deleting this.",
            emptySet<String>(),
            exported + uncalled - catalog,
        )
    }

    /**
     * Every vector's `rateClass` label names a class that actually enforces its spacing.
     *
     * The companion to `the rate table's stated limits agree with the production spacings`, which
     * checks each class internally (`spacing × calls == window`) and cannot see a **vector**
     * filed under the wrong class. That was a latent bug (never shipped) until schema 6: `vectorDocument` derived
     * the label from a two-way test — damage, else default — so FR-28's 500 ms ops were labelled
     * `default`, a class the same document states as 1000 ms. Both halves regenerated together
     * and agreed with the code, so the golden pin was perfectly happy.
     *
     * `ContractExport.rateClassNameFor` is the fix; this is what stops the next person
     * reintroducing it as an `else` branch.
     */
    @Test
    fun `every vector is filed under a class that enforces its own spacing`() {
        val classes = documentAt("ddp/rate-limits.json")["classes"]!!.jsonArray
            .map { it.jsonObject }
            .associate { it.str("name") to it["minSpacingMillis"]!!.jsonPrimitive().longOrNull!! }

        for (vector in documentAt("ddp/method-vectors.json")["vectors"]!!.jsonArray.map { it.jsonObject }) {
            val name = vector.str("rateClass")
            val spacing = vector["minSpacingMillis"]!!.jsonPrimitive().longOrNull!!
            assertEquals(
                "vector ${vector.str("name")} is filed under rate class `$name`, which does not " +
                    "enforce its ${spacing}ms spacing",
                classes[name],
                spacing,
            )
        }
    }

    /**
     * FR-28 decision 5, held down from both sides: the export SAYS a use is never replayed, and
     * the production op it describes actually declines the retry.
     *
     * A `quirk` string is prose and a golden pin certifies prose exactly as happily as it
     * certifies a bug. This is the semantic half — the export's claim tied to the flag the queue
     * reads — so that deleting `isReplayable` fails this suite rather than leaving a contract
     * document telling WebHand something MageHand stopped doing.
     */
    @Test
    fun `the use vectors are the only ops that refuse the rate-limit retry`() {
        val neverReplayed = ContractExport.VECTORS.filterNot { it.op.isReplayable }.map { it.op.method }.toSet()
        assertEquals(
            "exactly the two Use methods may decline the queue's single retry",
            setOf(WriteOp.METHOD_DO_ACTION, WriteOp.METHOD_DO_CAST_SPELL),
            neverReplayed,
        )

        val use = documentAt("ddp/method-vectors.json")["use"]!!.jsonObject
        assertTrue(
            "ddp/method-vectors.json#use must state that a use is never replayed",
            use.str("neverReplay").orEmpty().contains("NEVER REPLAYED"),
        )
        // The four traps decision 9 names, by key. A quirk that quietly stopped being exported
        // is a fact WebHand re-learns live, which is the whole thing this file exists to prevent.
        for (key in listOf(
            "trap.nullReturnRefusals",
            "trap.preparedUnchecked",
            "trap.honorSystemResources",
            "trap.usesLeftLag",
        )) {
            assertNotNull("ddp/method-vectors.json#use is missing `$key`", use[key])
        }
    }

    // =======================================================================
    // 3b — the timings, cross-checked against the production configuration
    // =======================================================================
    //
    // These assert against `DdpClientConfig()` / `WriteQueueConfig()` themselves rather than
    // against the committed bytes. The golden pin already catches "the file changed"; what it
    // cannot catch is the file and the code changing together in the wrong direction, which
    // is precisely the failure mode for a number a sibling client copies out and runs on.

    @Test
    fun `the exported connection timings are the production client's own`() {
        val connection = documentAt("ddp/timings.json")["connection"]!!.jsonObject
        val config = DdpClientConfig()

        assertEquals(
            "handshakeTimeoutMillis must be DdpClientConfig's own value",
            config.handshakeTimeout.inWholeMilliseconds,
            connection["handshakeTimeoutMillis"]!!.jsonPrimitive().longOrNull,
        )
        assertEquals(
            "methodTimeoutMillis must be DdpClientConfig's own value",
            config.methodTimeout.inWholeMilliseconds,
            connection["methodTimeoutMillis"]!!.jsonPrimitive().longOrNull,
        )
        assertEquals(
            "heartbeatIntervalMillis must be DdpClientConfig's own value",
            config.heartbeatInterval.inWholeMilliseconds,
            connection["heartbeatIntervalMillis"]!!.jsonPrimitive().longOrNull,
        )
        assertEquals(
            "pongDeadlineMillis is DdpClientConfig.heartbeatTimeout — the wait for the matching pong",
            config.heartbeatTimeout.inWholeMilliseconds,
            connection["pongDeadlineMillis"]!!.jsonPrimitive().longOrNull,
        )
        assertEquals(true, connection["generated"]?.jsonPrimitive()?.booleanOrNull)

        // The invariant the pongDeadline note claims, asserted rather than merely written down:
        // a deadline at or past the interval lets two probes be outstanding at once, and the
        // client would then be diagnosing a ping it has already replaced.
        assertTrue(
            "the pong deadline must be strictly shorter than the heartbeat interval",
            config.heartbeatTimeout < config.heartbeatInterval,
        )
    }

    /**
     * The exported bounds are probed off `ExponentialBackoff` with a pinned [kotlin.random.Random];
     * this re-samples the policy the client is *actually configured with*, on a real one. If the
     * two ever disagree the export is describing a schedule nobody runs.
     */
    @Test
    fun `the exported backoff bounds contain what the configured policy actually returns`() {
        val backoff = documentAt("ddp/timings.json")["reconnectBackoff"]!!.jsonObject
        val schedule = backoff["schedule"]!!.jsonArray.map { it.jsonObject }
        assertEquals(ContractExport.BACKOFF_ATTEMPTS, schedule.size)

        val policy = DdpClientConfig().backoff
        val observed = mutableSetOf<Long>()
        for ((attempt, step) in schedule.withIndex()) {
            assertEquals("the schedule must be in attempt order", attempt, step["attempt"]?.jsonPrimitive()?.intOrNull)
            val min = step["minDelayMillis"]!!.jsonPrimitive().longOrNull!!
            val max = step["maxDelayMillis"]!!.jsonPrimitive().longOrNull!!
            assertTrue("attempt $attempt has an inverted range", min <= max)
            repeat(SAMPLES_PER_ATTEMPT) {
                val delay = policy.delayMillis(attempt)
                assertTrue(
                    "the configured policy returned ${delay}ms for attempt $attempt, outside the " +
                        "exported [$min, $max]",
                    delay in min..max,
                )
                if (attempt == 0) observed += delay
            }
        }
        // And the spread is real. An exported range a policy never varies inside would be a
        // schedule that quietly lost its jitter, which is the whole point of the range.
        assertTrue("attempt 0 must actually jitter, not return one fixed delay", observed.size > 1)
    }

    @Test
    fun `the backoff parameters agree with the schedule they describe`() {
        val backoff = documentAt("ddp/timings.json")["reconnectBackoff"]!!.jsonObject
        val schedule = backoff["schedule"]!!.jsonArray.map { it.jsonObject }
        fun min(n: Int) = schedule[n]["minDelayMillis"]!!.jsonPrimitive().longOrNull!!
        fun max(n: Int) = schedule[n]["maxDelayMillis"]!!.jsonPrimitive().longOrNull!!

        val initial = backoff["initialMillis"]!!.jsonPrimitive().longOrNull!!
        val factor = backoff["factor"]!!.jsonPrimitive().content.toDouble()
        val cap = backoff["maxMillis"]!!.jsonPrimitive().longOrNull!!
        val jitter = backoff["jitterRatio"]!!.jsonPrimitive().content.toDouble()
        val saturates = backoff["saturatesAtAttempt"]!!.jsonPrimitive().intOrNull!!

        // The stated parameters must reproduce the stated schedule — the two halves of this
        // document are otherwise free to drift apart, and a consumer may port either one.
        assertEquals("initialMillis is attempt 0's midpoint", initial, (min(0) + max(0)) / 2)
        assertEquals("factor steps the base delay", factor, ((min(1) + max(1)) / 2.0) / initial, 1e-9)
        assertEquals("jitterRatio is attempt 0's half-spread", jitter, (max(0) - min(0)) / (2.0 * initial), 1e-9)
        assertEquals("the schedule must flatten against the cap", cap, max(saturates))
        assertEquals(
            "saturation means the bounds stop moving",
            min(saturates) to max(saturates),
            min(saturates + 1) to max(saturates + 1),
        )
        assertTrue("no exported delay may exceed the cap", schedule.all { it["maxDelayMillis"]!!.jsonPrimitive().longOrNull!! <= cap })
        assertTrue("the ramp must climb before it flattens", min(0) < min(saturates))
    }

    @Test
    fun `the exported resubscribe knobs are the production client's own`() {
        val resubscribe = documentAt("ddp/timings.json")["resubscribe"]!!.jsonObject
        val config = DdpClientConfig()

        assertEquals(
            "staggerMillis must be DdpClientConfig's own value",
            config.resubscribeStagger.inWholeMilliseconds,
            resubscribe["staggerMillis"]!!.jsonPrimitive().longOrNull,
        )
        assertEquals(
            "retryDelayMillis must be DdpClientConfig's own value",
            config.resubscribeRetryDelay.inWholeMilliseconds,
            resubscribe["retryDelayMillis"]!!.jsonPrimitive().longOrNull,
        )
        assertEquals(true, resubscribe["generated"]?.jsonPrimitive()?.booleanOrNull)
        // "Single retry — no storms" is the half of decision 17 a consumer is most likely to
        // read as advisory. A number, so a port cannot quietly make it a loop.
        assertEquals(1, resubscribe["maxRetries"]?.jsonPrimitive()?.intOrNull)

        // The arithmetic the retry note claims: the bucket refills at five per second, so a
        // retry delay shorter than the stagger would be re-entering the congestion it is
        // waiting out, and one shorter than the window's per-second refill buys nothing.
        assertTrue(
            "the retry delay must be at least the stagger — it is the longer wait by design",
            config.resubscribeRetryDelay >= config.resubscribeStagger,
        )
    }

    /**
     * The drift defence, widened from a *value* pin to a *coverage* pin.
     *
     * Every assertion above catches an exported number that stopped matching the code. None of
     * them catches the failure that actually happened: `DdpClientConfig` grew
     * `resubscribeStagger` and `resubscribeRetryDelay`, `timings()` hand-enumerated the fields
     * it knew about, and the two new ones were simply *absent*. Nothing failed, because nothing
     * was asserting about a key that does not exist. WebHand would have vendored a timings
     * document with no stagger in it and become the client that storms the shared bucket.
     *
     * So the enumeration is inverted: this test reflects over the config's declared fields and
     * requires each one to be accounted for, either as an exported key or as a named, argued
     * exclusion. Adding a field to `DdpClientConfig` now fails this suite until somebody
     * decides which it is.
     *
     * Java reflection rather than `kotlin.reflect`: `:core:data` does not carry `kotlin-reflect`
     * and adding a runtime dependency to serve one test would be a product change made for a
     * test's convenience — `CHARACTER_LIST`'s reasoning, applied to a dependency instead of a
     * visibility.
     */
    @Test
    fun `every DdpClientConfig field is exported or explicitly excluded`() {
        val timings = documentAt("ddp/timings.json")

        // Where each field surfaces in the document, as `object.key` (or just `object` where
        // the field is a whole policy rather than a scalar).
        val exported = mapOf(
            "handshakeTimeout" to "connection.handshakeTimeoutMillis",
            "methodTimeout" to "connection.methodTimeoutMillis",
            "heartbeatInterval" to "connection.heartbeatIntervalMillis",
            "heartbeatTimeout" to "connection.pongDeadlineMillis",
            "backoff" to "reconnectBackoff",
            "resubscribeStagger" to "resubscribe.staggerMillis",
            "resubscribeRetryDelay" to "resubscribe.retryDelayMillis",
        )

        // Not exported, with the reason. A consumer cannot port a log sink and would learn
        // nothing from being told one exists.
        val excluded = mapOf(
            "logger" to "a diagnostic sink with no wire effect; off by default",
        )

        val declared = DdpClientConfig::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .filterNot { it == "Companion" || it.startsWith("$") }
            .toSet()

        assertEquals(
            "a field was added to DdpClientConfig. Decide whether it is part of the contract: " +
                "export it in ContractExport.timings() and name it here, or record it in the " +
                "excluded map with the reason. Do NOT delete this assertion.",
            declared,
            exported.keys + excluded.keys,
        )

        // …and the exported names are not merely listed here, they are present in the document.
        exported.values.forEach { path ->
            val parts = path.split('.')
            val obj = timings[parts.first()]?.jsonObject
            assertNotNull("ddp/timings.json has no `${parts.first()}` object", obj)
            if (parts.size > 1) {
                assertNotNull(
                    "ddp/timings.json is missing `$path`",
                    obj!![parts[1]],
                )
            }
        }
    }

    @Test
    fun `the rate-limit retry delay is the production one and matches the window`() {
        val rateLimit = documentAt("ddp/timings.json")["rateLimit"]!!.jsonObject
        assertEquals(
            "retryAfterRateLimitMillis must be WriteQueueConfig's own value",
            WriteQueueConfig().rateLimitBackoffMillis,
            rateLimit["retryAfterRateLimitMillis"]!!.jsonPrimitive().longOrNull,
        )
        // The claim the note makes: the retry waits a full window, so it cannot re-enter the
        // window that just refused the call. This also ties timings.json to rate-limits.json.
        assertEquals(
            "the retry delay and the rate-limit window must agree across the two documents",
            documentAt("ddp/rate-limits.json")["windowMillis"]!!.jsonPrimitive().longOrNull,
            rateLimit["retryAfterRateLimitMillis"]!!.jsonPrimitive().longOrNull,
        )
        assertEquals(false, rateLimit["windowMillisGenerated"]?.jsonPrimitive()?.booleanOrNull)
        assertEquals(true, rateLimit["retryAfterRateLimitMillisGenerated"]?.jsonPrimitive()?.booleanOrNull)
    }

    // =======================================================================
    // 3c — login completes on two frames, and the export says so
    // =======================================================================

    /**
     * The under-recording webhand's WP-B found.
     *
     * The export used to hand-build login's reply as a lone `result`, while the production
     * client has always held the call open for `result` AND `updated`. A second client reading
     * the old export would ship a login that completes one frame early — and would then
     * occasionally act on a mirror the server's own writes had not landed in yet, which is a
     * bug that reproduces roughly never on a fast link.
     *
     * The behavioural proofs live in `:core:ddp`'s `DdpClientTest`
     * (`method_completes_only_after_both_result_and_updated`, `updated_may_arrive_before_result`).
     * What is asserted here is that the *export* states the same thing.
     */
    @Test
    fun `the login step records both completion frames in the live order`() {
        val login = handshakeStep("login")
        val sentId = login["sent"]!!.jsonObject.str("id")
        assertNotNull("the recorded login frame must carry a method id", sentId)

        val replies = login["expectedReplies"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            "login completes on BOTH `updated` and `result` — and the live server sends them " +
                "in that order (WP2 §3.1)",
            listOf("updated", "result"),
            replies.mapNotNull { it.str("msg") },
        )
        assertEquals(true, login["repliesGenerated"]?.jsonPrimitive()?.booleanOrNull)

        // Both frames must actually address the call that was sent, or the export would be
        // pairing a login with somebody else's completion.
        val updated = replies.first { it.str("msg") == "updated" }
        assertEquals(
            "the `updated` frame must name the login's method id",
            listOf(sentId),
            updated["methods"]!!.jsonArray.mapNotNull { it.jsonPrimitive().contentOrNull },
        )
        assertEquals("the `result` frame must be addressed to the login", sentId, replies.first { it.str("msg") == "result" }.str("id"))

        assertTrue(
            "the step must record that `updated` arrives FIRST for login — the reverse of every " +
                "other method, and the reverse of design 02's transcript",
            login.str("quirk").orEmpty().contains("BEFORE `result`"),
        )
        assertTrue(
            "the step must state that it completes on both frames",
            login.str("completesOn").orEmpty().contains("BOTH"),
        )
    }

    @Test
    fun `every handshake step lists its replies and says what completes it`() {
        val steps = documentAt("ddp/handshake.json")["sequence"]!!.jsonArray.map { it.jsonObject }
        assertTrue("the handshake must have steps", steps.isNotEmpty())
        for (step in steps) {
            val name = step.str("name")
            assertNotNull("$name must list expectedReplies, even when empty", step["expectedReplies"]?.jsonArray)
            assertNotNull("$name must state what completes it", step.str("completesOn"))
            assertNotNull("$name must state its replies' provenance", step["repliesGenerated"]?.jsonPrimitive()?.booleanOrNull)
            // Schema 2 replaced the singular key. A consumer still reading it should get a
            // missing key rather than a silently-truncated one-frame login.
            assertNull("$name must not carry the schema-1 `expectedReply`", step["expectedReply"])
        }
    }

    @Test
    fun `the export states the method-completion rule and points login at it`() {
        val handshake = documentAt("ddp/handshake.json")
        val rule = handshake.str("methodCompletion").orEmpty()
        assertTrue("the completion rule must require both frames", rule.contains("BOTH"))
        assertTrue(
            "the rule must say either order is legal — this server uses both",
            rule.contains("EITHER ORDER"),
        )
        assertTrue(
            "the rule must state that it covers login too, which is where the export used to be silent",
            rule.contains("login"),
        )
        assertTrue(
            "the rule must name the timeout that bounds it",
            rule.contains("methodTimeoutMillis"),
        )
    }

    @Test
    fun `the rate table's stated limits agree with the production spacings`() {
        val document = documentAt("ddp/rate-limits.json")
        val window = document["windowMillis"]!!.jsonPrimitive().longOrNull!!
        for (rateClass in document["classes"]!!.jsonArray.map { it.jsonObject }) {
            val calls = rateClass["callsPerWindow"]!!.jsonPrimitive().intOrNull!!
            val spacing = rateClass["minSpacingMillis"]!!.jsonPrimitive().longOrNull!!
            assertEquals(
                "the ${rateClass.str("name")} class's stated limit and its enforced spacing disagree",
                window,
                spacing * calls,
            )
        }
        assertEquals(250L, WriteOp.DAMAGE_SPACING_MILLIS)
        assertEquals(1_000L, WriteOp.SLOW_SPACING_MILLIS)
    }

    // =======================================================================
    // 4 — the discovery vectors say what they claim to
    // =======================================================================

    /**
     * The coincidence, held down from both sides on one fixture.
     *
     * These two properties are excluded from `slots` **and** discovered into `deathSaves`, and
     * the two facts have nothing to do with each other: the exclusion is `reset == null` and the
     * discovery is `variableName`. Asserting both here is what stops the retired reading from
     * creeping back in as "well, the exclusion does find them, so…". It does not find them; it
     * drops them, and something else finds them.
     */
    @Test
    fun `death saves are in the input, excluded from slots, and discovered by variableName`() {
        val vector = discoveryVector("tracker-discovery")
        val input = vector["input"]!!.jsonObject["creatureProperties"]!!.jsonArray.map { it.jsonObject }
        val deathSaves = input.filter { it.str("_id") in setOf(ContractFixtures.deathSaveSuccessId, ContractFixtures.deathSaveFailureId) }
        assertEquals("both death saves must be present in the INPUT", 2, deathSaves.size)
        assertTrue(
            "this fixture's death saves are spellSlots carrying no reset rule — the shape that " +
                "makes them exercise the slot exclusion, and NOT what identifies them",
            deathSaves.all { it.str("attributeType") == "spellSlot" && it.str("reset") == null },
        )
        assertEquals(
            "the input must carry the discriminator the engine actually keys on, or the vector " +
                "quietly models the retired reading",
            setOf(TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, TrackerEngine.VAR_DEATH_SAVE_FAILURES),
            deathSaves.mapNotNull { it.str("variableName") }.toSet(),
        )

        val board = vector.expected()["trackerBoard"]!!.jsonObject
        val slots = board["slots"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            "no death save may reach the slot list",
            slots.none { it.str("propertyId") in deathSaves.mapNotNull { d -> d.str("_id") } },
        )
        assertEquals("exactly the two reachable slot levels survive", 2, slots.size)

        // …and the same two properties DID get found, by the other rule.
        val found = board["deathSaves"]!!.jsonObject
        assertEquals(ContractFixtures.deathSaveSuccessId, found.str("successesPropertyId"))
        assertEquals(ContractFixtures.deathSaveFailureId, found.str("failuresPropertyId"))
    }

    // =======================================================================
    // 4b — FR-23 death saves (schema 5)
    // =======================================================================

    /**
     * The correction itself, asserted as a *retirement* rather than as an addition.
     *
     * Adding `#discovery.deathSaves` while leaving the old sentence in place would have been the
     * worst of both: a consumer reading top to bottom meets the wrong rule first, and the two
     * documents disagree with no way to tell which is current. So this asserts the old claim is
     * GONE from the exclusion text, and that the correction is stated where somebody who ported
     * the old wording will actually trip over it.
     */
    @Test
    fun `the spell-slot exclusion no longer claims to be the death-save filter`() {
        val slots = documentAt("domain/rules.json")["discovery"]!!.jsonObject["spellSlots"]!!.jsonObject
        val exclusions = slots["exclusions"]!!.jsonArray.mapNotNull { it.jsonPrimitive().contentOrNull }

        val resetExclusion = exclusions.first { it.startsWith("reset == null") }
        assertFalse(
            "the reset == null exclusion must no longer be described as THE death-save filter",
            resetExclusion.contains("THE death-save filter"),
        )
        assertTrue(
            "…and it must point at the correction rather than leaving a consumer to notice",
            resetExclusion.contains("exclusionCorrection"),
        )
        assertTrue(
            "the exclusion must still be stated — it is unchanged and still required",
            exclusions.any { it.startsWith("reset == null") } && exclusions.any { it.startsWith("total == 0") },
        )

        val correction = slots.str("exclusionCorrection").orEmpty()
        assertTrue("the correction must name the schema it corrects", correction.contains("schema 4"))
        assertTrue(
            "the correction must say the null was a coincidence — design 15 D11's own word, and " +
                "the whole reason the old rule identified nothing",
            correction.contains("coincidence", ignoreCase = true),
        )
        assertTrue(
            "the correction must send the reader to the rule that replaces it",
            correction.contains("variableName") && correction.contains("#discovery.deathSaves"),
        )
    }

    /**
     * The new rule is rendered from the production constants, not typed alongside them.
     *
     * This is the assertion the `tempHitPoints` episode bought: an export that *restates* a
     * discriminator can be wrong on its own, silently, because nothing compares it to the engine.
     */
    @Test
    fun `the death-save rule states the discriminator from the production constants`() {
        val rule = deathSaveRule()
        val names = rule["variableNames"]!!.jsonObject
        assertEquals(TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, names.str("successes"))
        assertEquals(TrackerEngine.VAR_DEATH_SAVE_FAILURES, names.str("failures"))
        assertEquals(
            "DiceCloud's own spelling — `Fails`, not `Failures`. A tidied name finds nothing.",
            "deathSaveFails",
            TrackerEngine.VAR_DEATH_SAVE_FAILURES,
        )
        assertTrue(
            "the match must key on `variableName`, which is the whole correction",
            rule.str("match").orEmpty().contains("variableName"),
        )
        assertTrue(
            "the rule must record that `attributeType` is deliberately NOT checked",
            rule.str("attributeTypeIsNotChecked").orEmpty().contains("NOT"),
        )
        assertEquals(
            "the inverted storage must state the production maximum",
            DeathSaves.MAX,
            rule["storage"]!!.jsonObject["max"]?.jsonPrimitive()?.intOrNull,
        )
        assertTrue(
            "the export must say `value` is the mark count — the inversion is the fact a client " +
                "gets backwards",
            rule["storage"]!!.jsonObject.str("inverted").orEmpty().contains("MARK COUNT"),
        )
        assertEquals("dead is a derivation off the failure pips", "failures == ${DeathSaves.MAX}", rule["derivations"]!!.jsonObject.str("dead"))
        assertEquals("stable is a derivation off the success pips", "successes == ${DeathSaves.MAX}", rule["derivations"]!!.jsonObject.str("stable"))
    }

    /**
     * The `type` discriminator, and the sub-type that is deliberately ignored — pinned against
     * the engine rather than against the export's own sentence.
     *
     * `TrackerEngine.TYPE_ATTRIBUTE` is private and stays private, so `ContractExport`'s copy of
     * the string is a restatement. This is what makes the restatement safe: the same two variable
     * names are fed to the production engine under two different `attributeType`s (both must be
     * found) and under a different `type` (must not be), so the exported rule's claims are
     * checked by running the code they describe.
     */
    @Test
    fun `death-save discovery keys on type and variableName, never on attributeType`() {
        for (attributeType in listOf("spellSlot", "resource", "healthBar")) {
            val board = boardOf(
                deathSaveProperty(ContractFixtures.deathSaveSuccessId, TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, attributeType, marks = 1),
                deathSaveProperty(ContractFixtures.deathSaveFailureId, TrackerEngine.VAR_DEATH_SAVE_FAILURES, attributeType, marks = 2),
            )
            assertNotNull(
                "a pair typed `$attributeType` must still be discovered — the rule ignores the sub-type",
                board.deathSaves,
            )
        }

        // The `type` half, which IS checked. A `skill` carrying both names is not the pair.
        val notAttributes = boardOf(
            ContractFixtures.skill(
                id = ContractFixtures.deathSaveSuccessId, name = "Succeeded Saves", skillType = "save",
                variableName = TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, value = 1, abilityMod = 0,
                proficiency = 0, ability = null, order = 2,
            ),
            ContractFixtures.skill(
                id = ContractFixtures.deathSaveFailureId, name = "Failed Saves", skillType = "save",
                variableName = TrackerEngine.VAR_DEATH_SAVE_FAILURES, value = 2, abilityMod = 0,
                proficiency = 0, ability = null, order = 3,
            ),
        )
        assertNull(
            "the exported `type == 'attribute'` must be what the engine enforces; a `skill` " +
                "carrying the same variable names is not the death-save pair",
            notAttributes.deathSaves,
        )

        // Half a pair is no pair (decision 18): three failure pips would have nowhere to write.
        val halfPair = boardOf(
            deathSaveProperty(ContractFixtures.deathSaveSuccessId, TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, "spellSlot", marks = 1),
        )
        assertNull("one half is not a pair", halfPair.deathSaves)
    }

    /**
     * **The pin that makes a separately-stated trigger necessary.**
     *
     * `#discovery.deathSaves.trigger` is restated from `TrackerUiState.deathSaves`, which lives
     * in `:app` and is not visible here — see `ContractExport.deathSaveBlockVisible`. What is
     * provable from this module is the half that matters: production discovery does **not** read
     * the HP row, so the pair comes back identically at 0 HP and at 9. If someone ever moved the
     * gate into the engine, this fails — and it should, because the export would then be telling
     * consumers to apply a condition that had already been applied.
     */
    @Test
    fun `discovery returns the pair whatever the hit-point row says`() {
        val downed = TrackerEngine.build(
            CreatureSheet.fromSnapshotJson(
                ContractFixtures.deathSaveSheetBody(hitPointsValue = 0, withPair = true),
                ContractFixtures.creatureId,
            ),
        )
        val up = TrackerEngine.build(
            CreatureSheet.fromSnapshotJson(
                ContractFixtures.deathSaveSheetBody(hitPointsValue = 9, withPair = true),
                ContractFixtures.creatureId,
            ),
        )
        assertEquals("the ONLY difference between the two sheets is the HP row", 0, downed.hp?.value)
        assertEquals(9, up.hp?.value)
        assertEquals(
            "discovery is discovery: the pair must not depend on hit points",
            downed.deathSaves,
            up.deathSaves,
        )
        assertNotNull(downed.deathSaves)

        // And the trigger — the thing the engine does NOT do — separates them.
        assertTrue(ContractExport.deathSaveBlockVisible(downed))
        assertFalse(ContractExport.deathSaveBlockVisible(up))
    }

    /**
     * The three exported cases, read as the set they are.
     *
     * Each falsifies a different wrong implementation: gate on discovery alone and the
     * above-zero case fails; gate on the HP row alone and the no-pair case fails.
     */
    @Test
    fun `the death-save vectors separate the trigger's two halves`() {
        val downed = discoveryVector("death-save-downed").expected()
        assertEquals(0, downed["hitPointsValue"]?.jsonPrimitive()?.intOrNull)
        assertNotNull("the downed sheet must express the pair", downed["deathSaves"] as? JsonObject)
        assertEquals(true, downed["blockVisible"]?.jsonPrimitive()?.booleanOrNull)

        val up = discoveryVector("death-save-above-zero").expected()
        assertEquals(9, up["hitPointsValue"]?.jsonPrimitive()?.intOrNull)
        assertNotNull(
            "discovery still returns the pair above zero — that is the point of this vector",
            up["deathSaves"] as? JsonObject,
        )
        assertEquals(
            "a healthy character gets no death-save block, pair or no pair",
            false,
            up["blockVisible"]?.jsonPrimitive()?.booleanOrNull,
        )

        val none = discoveryVector("death-save-no-pair").expected()
        assertEquals(0, none["hitPointsValue"]?.jsonPrimitive()?.intOrNull)
        assertTrue(
            "no pair means null, not an error — the Dummy's shape",
            none["deathSaves"] is kotlinx.serialization.json.JsonNull,
        )
        assertEquals(false, none["blockVisible"]?.jsonPrimitive()?.booleanOrNull)

        // The two-with-a-pair cases must genuinely be the same sheet but for the HP row, or the
        // comparison above proves nothing about the trigger.
        fun ids(name: String) = discoveryVector(name)["input"]!!.jsonObject["creatureProperties"]!!
            .jsonArray.map { it.jsonObject.str("_id") }
        assertEquals(ids("death-save-downed"), ids("death-save-above-zero"))

        // The trigger is honest about its own provenance.
        val trigger = deathSaveRule()["trigger"]!!.jsonObject
        assertEquals(
            "the HP half is restated from the UI layer and must not claim to be generated",
            false,
            trigger["generated"]?.jsonPrimitive()?.booleanOrNull,
        )
        assertTrue(
            "the rule must warn off the \"0 HP?\" toggle, which is the obvious wrong source",
            trigger.str("notTheToggle").orEmpty().contains("NEVER"),
        )
        assertTrue(
            "…and off `creature.deathSave`, which looks like it would answer `stable`",
            trigger.str("notCreatureDeathSave").orEmpty().contains("VESTIGIAL"),
        )
    }

    /**
     * The inversion, proved on the wire shape rather than asserted in prose.
     *
     * The fixture states BOTH `value` and `damage` precisely so a client reading either field is
     * testable — and `value + damage == MAX` is the identity that makes the two readings agree.
     */
    @Test
    fun `death-save storage is inverted and the engine reads marks out of it`() {
        val input = discoveryVector("death-save-downed")["input"]!!.jsonObject["creatureProperties"]!!
            .jsonArray.map { it.jsonObject }
        val successes = input.first { it.str("variableName") == TrackerEngine.VAR_DEATH_SAVE_SUCCESSES }
        val failures = input.first { it.str("variableName") == TrackerEngine.VAR_DEATH_SAVE_FAILURES }

        for (property in listOf(successes, failures)) {
            val value = property["value"]!!.jsonPrimitive().int
            val damage = property["damage"]!!.jsonPrimitive().int
            assertEquals("total must be the production maximum", DeathSaves.MAX, property["total"]!!.jsonPrimitive().int)
            assertEquals(
                "the inversion's identity: damage == MAX − value, so both readings agree",
                DeathSaves.MAX,
                value + damage,
            )
        }

        val saves = discoveryVector("death-save-downed").expected()["deathSaves"]!!.jsonObject
        assertEquals(
            "the discovered mark count is the property's `value`, NOT what is left",
            successes["value"]!!.jsonPrimitive().int,
            saves["successes"]?.jsonPrimitive()?.intOrNull,
        )
        assertEquals(
            failures["value"]!!.jsonPrimitive().int,
            saves["failures"]?.jsonPrimitive()?.intOrNull,
        )

        // The read-side clamp: a row another client drove past three must not paint a fourth pip.
        val overshoot = boardOf(
            deathSaveProperty(ContractFixtures.deathSaveSuccessId, TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, "spellSlot", marks = 1),
            deathSaveProperty(ContractFixtures.deathSaveFailureId, TrackerEngine.VAR_DEATH_SAVE_FAILURES, "spellSlot", marks = DeathSaves.MAX + 1),
        )
        assertEquals(DeathSaves.MAX, overshoot.deathSaves?.failures)
        assertTrue(
            "clamped at the maximum still reads as dead, which is the honest answer",
            overshoot.deathSaves!!.isDead,
        )
    }

    /** Derivations, not stored flags: the export must carry them next to the counts. */
    @Test
    fun `dead and stable are derived from the pip counts`() {
        val saves = discoveryVector("death-save-downed").expected()["deathSaves"]!!.jsonObject
        val successes = saves["successes"]!!.jsonPrimitive().int
        val failures = saves["failures"]!!.jsonPrimitive().int
        assertEquals(DeathSaves.MAX, saves["max"]?.jsonPrimitive()?.intOrNull)
        assertEquals(successes >= DeathSaves.MAX, saves["isStable"]?.jsonPrimitive()?.booleanOrNull)
        assertEquals(failures >= DeathSaves.MAX, saves["isDead"]?.jsonPrimitive()?.booleanOrNull)

        // The answer a reasonable implementation gets wrong: three-and-out is still tappable,
        // because a block that locked itself is a block a stale-marks sheet cannot be fixed from.
        val dead = boardOf(
            deathSaveProperty(ContractFixtures.deathSaveSuccessId, TrackerEngine.VAR_DEATH_SAVE_SUCCESSES, "spellSlot", marks = 0),
            deathSaveProperty(ContractFixtures.deathSaveFailureId, TrackerEngine.VAR_DEATH_SAVE_FAILURES, "spellSlot", marks = DeathSaves.MAX),
        ).deathSaves!!
        assertTrue(dead.isDead)
        assertTrue("pips stay tappable at three failures", dead.isEditable)
        assertEquals(true, saves["isEditable"]?.jsonPrimitive()?.booleanOrNull)
    }

    /**
     * The write, and the two things about it a consumer can only get from a recorded frame: the
     * method (`damage`, not `adjustQuantity`) and the rate class it therefore lands in.
     */
    @Test
    fun `a death-save mark is an absolute damage set on the fast lane`() {
        val mark = vector("damage.markDeathSaveFailure")
        assertEquals(
            "death saves are written with `damage`; `adjustQuantity` would be a different " +
                "method, in the slow lane, against a property with no quantity",
            "creatureProperties.damage",
            mark.str("method"),
        )
        assertEquals("damage", mark.str("rateClass"))
        assertEquals(
            WriteOp.DAMAGE_SPACING_MILLIS,
            mark["minSpacingMillis"]?.jsonPrimitive()?.longOrNull,
        )

        val params = mark.frame().params().first().jsonObject
        assertEquals("an ABSOLUTE set, never an increment", "set", params.str("operation"))
        assertEquals(
            "the fixture's failure row carries 2 marks and the vector marks the third",
            3,
            params["value"]?.jsonPrimitive()?.intOrNull,
        )
        assertEquals(
            "the write must address the failures property discovery returned",
            discoveryVector("death-save-downed").expected()["deathSaves"]!!.jsonObject.str("failuresPropertyId"),
            params.str("_id"),
        )
        assertTrue(
            "the quirk must state idempotence — the reason an absolute set is the right shape " +
                "for a call whose reply may be lost",
            mark.str("quirk").orEmpty().contains("IDEMPOTENT"),
        )
        assertTrue(
            "…and that the server clamps natively, so no pre-flight read is needed",
            mark.str("quirk").orEmpty().contains("CLAMPS"),
        )

        // The inverse restores the pip rather than clearing the row — `setValue` builds it from
        // the resource's CURRENT value, which is what makes an undo an undo.
        val inverse = mark["inverseParams"]!!.jsonObject
        assertEquals("creatureProperties.damage", inverse.str("method"))
        assertEquals(
            2,
            inverse["params"]!!.jsonArray.first().jsonObject["value"]?.jsonPrimitive()?.intOrNull,
        )
    }

    /**
     * The clear pair, and D12's semantics — the half of FR-23 that costs the whole table if a
     * consumer ports the obvious implementation.
     */
    @Test
    fun `the clear pair sets both properties to zero and states when it may not be sent`() {
        val saves = discoveryVector("death-save-downed").expected()["deathSaves"]!!.jsonObject
        val clears = mapOf(
            "damage.clearDeathSaveSuccesses" to saves.str("successesPropertyId"),
            "damage.clearDeathSaveFailures" to saves.str("failuresPropertyId"),
        )
        for ((name, propertyId) in clears) {
            val params = vector(name).frame().params().first().jsonObject
            assertEquals("creatureProperties.damage", vector(name).str("method"))
            assertEquals("set", params.str("operation"))
            assertEquals("a clear is `set 0`", 0, params["value"]?.jsonPrimitive()?.intOrNull)
            assertEquals("$name must address its own half of the pair", propertyId, params.str("_id"))
            // The trap: the op HAS an inverse (a player's own pip tap uses the same call), but
            // the heal-attached clear takes no receipt. A consumer reading `undoable: true` off
            // the vector and wiring an undo entry recreates H1 — the snackbar's UNDO reversing a
            // clear instead of the heal that produced it.
            assertEquals(
                "$name's inverse is a property of the CALL, and the vector says so",
                true,
                vector(name)["undoable"]?.jsonPrimitive()?.booleanOrNull,
            )
            assertTrue(
                "$name must warn that the heal-attached clear files no receipt despite that",
                vector(name).str("quirk").orEmpty().contains("noReceipt"),
            )
        }
        assertEquals(
            "the two clears must address DIFFERENT properties — it is a pair, not one call twice",
            2,
            clears.keys.map { vector(it).frame().params().first().jsonObject.str("_id") }.toSet().size,
        )

        val rule = documentAt("ddp/method-vectors.json")["deathSaveClear"]!!.jsonObject
        assertEquals(
            clears.keys.toList().sorted(),
            rule["clears"]!!.jsonArray.mapNotNull { it.jsonPrimitive().contentOrNull }.sorted(),
        )
        assertTrue(
            "the server never clears these — if the export does not say so, a consumer waits " +
                "for a reset that never comes",
            rule.str("serverNeverClears").orEmpty().contains("does NOT clear"),
        )
        assertTrue(
            "the clear rides THIS client's own write",
            rule.str("attachedToOurOwnWrite").orEmpty().contains("0 to positive"),
        )
        assertTrue(
            "the observer storm must be stated in the imperative — it is the failure that costs " +
                "the table rather than the client that ships it",
            rule.str("neverReactive").orEmpty().contains("NEVER fire these from OBSERVED state"),
        )
        assertTrue(
            "…including WHY: N clients watching one sheet each send the same pair",
            rule.str("neverReactive").orEmpty().contains("dashboard"),
        )
        assertTrue(
            "the accepted cost — stale marks after someone else's heal — must be recorded, or a " +
                "consumer reads it as a bug and 'fixes' it reactively",
            rule.str("acceptedCost").orEmpty().contains("ANOTHER client"),
        )
        assertEquals(false, rule["generated"]?.jsonPrimitive()?.booleanOrNull)
    }

    @Test
    fun `soft-removed properties are delivered and filtered out of every sum`() {
        val vector = discoveryVector("soft-removed-still-streams")
        val expected = vector.expected()
        assertEquals(
            "the removed property must be counted in what the server delivers",
            1,
            expected["softRemovedDelivered"]?.jsonPrimitive()?.intOrNull,
        )
        assertTrue(
            "the removed item must not reach the board",
            expected["removedItemAbsentFromBoard"]?.jsonPrimitive()?.booleanOrNull == true,
        )

        // The number that would actually go wrong. The soft-removed Anvil weighs 100 lb; a
        // client that skipped the filter reports a carried weight 100 higher, and it is the
        // carried weight the capacity bar is drawn against.
        val sheet = CreatureSheet.fromSnapshotJson(ContractFixtures.inventorySheetBody(), ContractFixtures.creatureId)
        val carried = InventoryEngine.build(sheet).carriedWeightLb
        assertEquals(carried, expected["carriedWeightLb"]!!.jsonPrimitive().content.toDouble(), 1e-9)
        assertTrue("the fixture must carry less than the removed anvil's own weight", carried < 100.0)
    }

    @Test
    fun `the rolls vector covers a check a save a skill and both advantage directions`() {
        val expected = discoveryVector("rolls-discovery").expected()
        val rolls = expected["rolls"]!!.jsonArray.map { it.jsonObject }
        fun roll(id: String) = rolls.firstOrNull { it.str("id") == id }

        // The FR-7 shapes the section is made of, one each.
        val check = roll(ContractFixtures.abilityIntelligenceId)
        assertNotNull("an ability check must be discovered", check)
        val save = roll(ContractFixtures.saveDexterityId)
        assertNotNull("a saving throw must be discovered", save)
        val skill = roll(ContractFixtures.skillArcanaId)
        assertNotNull("a skill must be discovered", skill)

        // Advantage is the SIGN of a rollup, and the fixture's magnitudes are 2 and -3
        // precisely so a `== 1` / `== -1` implementation answers NONE and fails here.
        assertEquals(
            "advantage: 2 is ADVANTAGE — the field is a rollup, not a flag",
            "ADVANTAGE",
            roll(ContractFixtures.skillStealthAdvantageId)?.str("advantage"),
        )
        assertEquals(
            "advantage: -3 is DISADVANTAGE",
            "DISADVANTAGE",
            roll(ContractFixtures.saveWisdomDisadvantageId)?.str("advantage"),
        )
        assertEquals(
            "advantage present and zero is NONE",
            "NONE",
            check!!.str("advantage"),
        )
        assertEquals(
            "advantage absent is the same answer as advantage zero",
            "NONE",
            skill!!.str("advantage"),
        )

        // The modifier's provenance, stated as the two numbers a wrong client produces.
        assertEquals(
            "an ability check reads `modifier`, not the score — 14 would be the score",
            3,
            check["modifier"]?.jsonPrimitive()?.intOrNull,
        )
        assertEquals(
            "a skill reads `value`, the sheet's own total — abilityMod + proficiency is 6",
            7,
            skill["modifier"]?.jsonPrimitive()?.intOrNull,
        )

        // The sign convention, on both roll kinds.
        assertEquals(
            "a check modifier may be negative",
            -1,
            roll(ContractFixtures.abilityStrengthCheckId)?.get("modifier")?.jsonPrimitive()?.intOrNull,
        )
        assertEquals(
            "a save modifier may be negative",
            -1,
            roll(ContractFixtures.saveWisdomDisadvantageId)?.get("modifier")?.jsonPrimitive()?.intOrNull,
        )

        // Ordering is the sheet's, never alphabetical: Intelligence (10) before Arcana (20).
        assertEquals(
            rolls.map { it.str("name") },
            rolls.sortedBy { it["sortOrder"]!!.jsonPrimitive().int }.map { it.str("name") },
        )
    }

    @Test
    fun `the rolls vector carries its exclusions in the input`() {
        val vector = discoveryVector("rolls-discovery")
        val input = vector["input"]!!.jsonObject["creatureProperties"]!!.jsonArray.map { it.jsonObject }
        val expected = vector.expected()
        val discovered = expected["rolls"]!!.jsonArray.map { it.jsonObject.str("id") }.toSet()
        val excluded = expected["excludedPropertyIds"]!!.jsonArray.map { it.jsonObject }

        assertEquals(
            "every input property is either discovered or listed as excluded",
            input.size,
            discovered.size + excluded.size,
        )
        assertEquals(input.size, expected["propertiesDelivered"]?.jsonPrimitive()?.intOrNull)
        assertEquals(discovered.size, expected["rollsDiscovered"]?.jsonPrimitive()?.intOrNull)

        fun excludedIds() = excluded.mapNotNull { it.str("_id") }.toSet()

        // The three proficiency skillTypes: same property type, same `value` field, not rolls.
        TrackerEngine.NON_ROLL_SKILL_TYPES.forEach { skillType ->
            assertTrue(
                "a `$skillType` proficiency must be in the input so the exclusion is provable",
                input.any { it.str("skillType") == skillType },
            )
            assertTrue(
                "a `$skillType` proficiency must not be offered as a roll",
                excluded.any { it.str("skillType") == skillType },
            )
        }
        // `check` is NOT excluded — the filter is an exclusion list so unfamiliar kinds surface.
        assertTrue(
            "a `check` (Initiative) is a roll and must survive the filter",
            ContractFixtures.checkInitiativeId in discovered,
        )

        assertTrue(
            "an ability with no `modifier` field is skipped, never back-derived from the score",
            ContractFixtures.abilityNoModifierId in excludedIds(),
        )
        assertTrue("`inactive` skips", ContractFixtures.skillInactiveDeathSaveId in excludedIds())
        assertTrue("`removed` skips", ContractFixtures.skillRemovedId in excludedIds())
        assertTrue("a nameless roll is dropped", ContractFixtures.skillNamelessId in excludedIds())
    }

    /**
     * M6: FR-30's own vector, run against the production engine like every other case here — see
     * [ContractFixtures.hitDiceSheetBody] for why it stands apart from `tracker-discovery`.
     */
    @Test
    fun `the hit-dice vector discovers its own kind with the die size and no reset`() {
        val vector = discoveryVector("hit-dice-discovery")
        val input = vector["input"]!!.jsonObject["creatureProperties"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            "the fixture's one property must actually carry the discriminator, or the vector " +
                "proves nothing about the predicate",
            TrackerEngine.ATTR_HIT_DICE,
            input.single().str("attributeType"),
        )

        val hitDice = vector.expected()["trackerBoard"]!!.jsonObject["hitDice"]!!.jsonArray
            .map { it.jsonObject }
        val row = hitDice.single()
        assertEquals(ContractFixtures.hitDiceD8Id, row.str("propertyId"))
        assertEquals("HIT_DICE", row.str("kind"))
        assertEquals("d8", row.str("dieSize"))
        // total 4, damage 1 → value 3, the same `total − damage` arithmetic every other row uses.
        assertEquals(3, row["value"]?.jsonPrimitive()?.intOrNull)
        assertEquals(4, row["total"]?.jsonPrimitive()?.intOrNull)
        assertTrue(
            "decision 17: no reset field on the discovered row, even though this rule's own " +
                "`domain/rules.json#discovery.hitDice.noResetField` states the source carries none",
            row["reset"] is JsonNull,
        )
    }

    @Test
    fun `the rolls rule names the collection the engine actually reads`() {
        val rule = documentAt("domain/rules.json")["discovery"]!!.jsonObject["rolls"]!!.jsonObject
        assertTrue(
            "rolls come off creatureProperties; the export must say so, because " +
                "`creatureVariables` is the obvious wrong guess",
            rule.str("sourceCollection").orEmpty().contains("creatureProperties"),
        )
        assertEquals(
            TrackerEngine.NON_ROLL_SKILL_TYPES.sorted(),
            rule["skillSaveOrCheck"]!!.jsonObject["excludedSkillTypes"]!!.jsonArray
                .mapNotNull { it.jsonPrimitive().contentOrNull },
        )
        assertEquals(
            "the ability rule must name the field the engine reads, not the one a client guesses",
            TrackerEngine.FIELD_MODIFIER,
            rule["abilityCheck"]!!.jsonObject.str("modifierField"),
        )
        assertEquals(
            TrackerEngine.FIELD_ADVANTAGE,
            rule["advantage"]!!.jsonObject.str("field"),
        )
        assertTrue(
            "the modifier may be negative and the export must say so",
            rule.str("modifierSign").orEmpty().contains("NEGATIVE", ignoreCase = true),
        )
    }

    @Test
    fun `the temp HP rule exports every variable name production accepts`() {
        val hp = documentAt("domain/rules.json")["discovery"]!!.jsonObject["hitPoints"]!!.jsonObject
        val names = hp["tempVariableNames"]!!.jsonArray.mapNotNull { it.jsonPrimitive().contentOrNull }
        assertEquals(TrackerEngine.TEMP_HP_VARIABLE_NAMES.sorted(), names)
        assertTrue(
            "`tempHP` is what live sheets write; an export naming only 03's `tempHitPoints` " +
                "sends a consumer looking for a row that is never there",
            "tempHP" in names,
        )
        assertTrue("03's spelling stays accepted too", "tempHitPoints" in names)
        assertEquals(TrackerEngine.VAR_HIT_POINTS, "hitPoints")

        // The engine, not the prose: both spellings must actually produce a temp-HP row.
        TrackerEngine.TEMP_HP_VARIABLE_NAMES.forEach { variableName ->
            val body = ContractFixtures.snapshotBody(
                creature = ContractFixtures.creature(),
                properties = listOf(
                    ContractFixtures.attribute(
                        id = ContractFixtures.tempHitPointsId, name = "Temp HP",
                        attributeType = "healthBar", variableName = variableName,
                        total = 5, damage = 0, order = 1,
                    ),
                ),
            )
            val board = TrackerEngine.build(
                CreatureSheet.fromSnapshotJson(body, ContractFixtures.creatureId),
            )
            assertNotNull("`$variableName` must be found as temp HP", board.tempHp)
            assertEquals(5, board.tempHp!!.total)
        }
    }

    @Test
    fun `the capacity rule states the arithmetic the vector only demonstrates`() {
        val rule = documentAt("domain/rules.json")["capacity"]!!.jsonObject
        assertEquals(
            InventoryBoard.CAPACITY_PER_STRENGTH,
            rule["perStrength"]?.jsonPrimitive()?.intOrNull,
        )
        assertTrue(
            "the rule must be keyed on the strength variableName, not the display name",
            rule.str("match").orEmpty().contains("'${InventoryEngine.VAR_STRENGTH}'"),
        )
        assertTrue(
            "`total` before `value`, so an effect that raises Strength raises the ceiling",
            rule.str("score").orEmpty().contains("total"),
        )

        // The number the vector already pins must be exactly what this rule computes.
        val board = discoveryVector("inventory-discovery").expected()["inventoryBoard"]!!.jsonObject
        val sheet = CreatureSheet.fromSnapshotJson(
            ContractFixtures.inventorySheetBody(),
            ContractFixtures.creatureId,
        )
        val strength = sheet.propertyList
            .first { it.str("variableName") == InventoryEngine.VAR_STRENGTH }["total"]!!
            .jsonPrimitive().int
        assertEquals(
            "the exported capacity must equal STR × ${InventoryBoard.CAPACITY_PER_STRENGTH}",
            strength * InventoryBoard.CAPACITY_PER_STRENGTH,
            board["capacityLb"]?.jsonPrimitive()?.intOrNull,
        )
    }

    @Test
    fun `the equippable rule is exported with the tag sets the engine uses`() {
        val rule = documentAt("domain/rules.json")["equippable"]!!.jsonObject
        assertEquals(
            InventoryEngine.EQUIPPABLE_TAGS.sorted(),
            rule["equippableTags"]!!.jsonArray.mapNotNull { it.jsonPrimitive().contentOrNull },
        )
        assertTrue(
            "the export must record the Half Plate defect that forces the enumerated set",
            rule.str("dataDefect1").orEmpty().contains("Half Plate"),
        )

        val items = discoveryVector("inventory-discovery").expected()["inventoryBoard"]!!.jsonObject
        val all = (items["equipped"]!!.jsonArray + items["carried"]!!.jsonArray).map { it.jsonObject }
        fun equippable(id: String) = all.first { it.str("propertyId") == id }["isEquippable"]!!.jsonPrimitive().booleanOrNull

        assertEquals("a tagged weapon is equippable", true, equippable(ContractFixtures.longswordId))
        assertEquals(
            "Half Plate carries `medium armor` and NOT `armor` — it must still be equippable",
            true,
            equippable(ContractFixtures.halfPlateId),
        )
        assertEquals(
            "an equipped hand-made item stays equippable, or taking it off is impossible",
            true,
            equippable(ContractFixtures.handmadeEquippedId),
        )
        assertEquals("a tinderbox is not equippable", false, equippable(ContractFixtures.tinderboxId))
        assertEquals(
            "the rule's known residual: unequipped and untagged loses the control",
            false,
            equippable(ContractFixtures.handmadeStowedId),
        )
    }

    @Test
    fun `the wallet renders a denomination the sheet does not carry`() {
        val wallet = discoveryVector("inventory-discovery").expected()["inventoryBoard"]!!
            .jsonObject["wallet"]!!.jsonObject
        val rows = wallet["rows"]!!.jsonArray.map { it.jsonObject }
        assertEquals("all four denominations always render", 4, rows.size)

        val copper = rows.first { it.str("coin") == "COPPER" }
        assertEquals("the fixture carries no copper", 0, copper["quantity"]?.jsonPrimitive()?.intOrNull)
        assertTrue(
            "a denomination the sheet lacks has no propertyId — the first increment CREATES it",
            copper["propertyId"] is kotlinx.serialization.json.JsonNull,
        )
        assertEquals(true, copper["isAbsent"]?.jsonPrimitive()?.booleanOrNull)

        val gold = rows.first { it.str("coin") == "GOLD" }
        assertEquals(15, gold["quantity"]?.jsonPrimitive()?.intOrNull)
        assertNotNull("a denomination the sheet carries names its property", gold.str("propertyId"))
    }

    @Test
    fun `a missing quantity reads one in both engines`() {
        val cross = discoveryVector("inventory-discovery").expected()["crossEngineQuantity"]!!.jsonObject
        assertEquals(1, cross["inventoryQuantity"]?.jsonPrimitive()?.intOrNull)
        assertEquals(1, cross["trackerQuantity"]?.jsonPrimitive()?.intOrNull)

        // And the input really does omit the field — otherwise the vector proves nothing.
        val input = discoveryVector("inventory-discovery")["input"]!!.jsonObject["creatureProperties"]!!
            .jsonArray.map { it.jsonObject }
        val singleton = input.first { it.str("_id") == ContractFixtures.singletonId }
        assertNull("the fixture property must carry NO quantity field", singleton["quantity"])

        // Cross-checked against the live engines rather than only against the committed bytes.
        val sheet = CreatureSheet.fromSnapshotJson(ContractFixtures.inventorySheetBody(), ContractFixtures.creatureId)
        assertEquals(
            1,
            InventoryEngine.build(sheet).allItems.first { it.propertyId == ContractFixtures.singletonId }.quantity,
        )
        assertEquals(
            1,
            TrackerEngine.build(sheet).allItems.first { it.propertyId == ContractFixtures.singletonId }.value,
        )
    }

    // =======================================================================
    // 5 — the catalog
    // =======================================================================

    @Test
    fun `the exported catalog is the production catalog`() {
        val document = documentAt("item-catalog.json")
        val entries = document["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals(ItemCatalog.entries.size, entries.size)
        assertEquals(
            "the export must not reorder or rename the catalog",
            ItemCatalog.entries.map { it.id },
            entries.mapNotNull { it.str("id") },
        )
        for ((source, exported) in ItemCatalog.entries.zip(entries)) {
            assertEquals(source.name, exported.str("name"))
            assertEquals(source.category.name, exported.str("category"))
            assertEquals(source.tags, exported["tags"]!!.jsonArray.mapNotNull { it.jsonPrimitive().contentOrNull })
            assertEquals(source.defaultQuantity, exported["defaultQuantity"]?.jsonPrimitive()?.intOrNull)
        }
        assertEquals(ItemCatalog.entries.size, document["entryCount"]?.jsonPrimitive()?.intOrNull)
    }

    // =======================================================================
    // Plumbing
    // =======================================================================

    private fun repoRoot(): File =
        File(requireNotNull(System.getProperty("magehand.repoRoot")) { "magehand.repoRoot is set by the build script" })

    private fun exportDir(): File = File(repoRoot(), ContractExport.DIRECTORY)

    private fun committedManifest(): JsonObject {
        val file = File(exportDir(), ContractExport.MANIFEST)
        assertTrue(
            "${ContractExport.MANIFEST} is missing. Run `./gradlew exportContract` and commit the result.",
            file.isFile,
        )
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun documentAt(path: String): JsonObject =
        Json.parseToJsonElement(File(exportDir(), path).readText()).jsonObject

    private fun vector(name: String): JsonObject =
        documentAt("ddp/method-vectors.json")["vectors"]!!.jsonArray
            .map { it.jsonObject }
            .first { it.str("name") == name }

    private fun handshakeStep(name: String): JsonObject =
        documentAt("ddp/handshake.json")["sequence"]!!.jsonArray
            .map { it.jsonObject }
            .first { it.str("name") == name }

    private fun discoveryVector(name: String): JsonObject =
        documentAt("domain/discovery-vectors.json")["vectors"]!!.jsonArray
            .map { it.jsonObject }
            .first { it.str("name") == name }

    private fun deathSaveRule(): JsonObject =
        documentAt("domain/rules.json")["discovery"]!!.jsonObject["deathSaves"]!!.jsonObject

    /** One half of the pair, in the inverted storage, under whatever sub-type the case needs. */
    private fun deathSaveProperty(
        id: String,
        variableName: String,
        attributeType: String,
        marks: Int,
    ): JsonObject = ContractFixtures.attribute(
        id = id, name = variableName, attributeType = attributeType, variableName = variableName,
        total = DeathSaves.MAX, damage = DeathSaves.MAX - marks, order = 1,
    )

    /** The production engine over an ad-hoc property list — the pin for a restated rule. */
    private fun boardOf(vararg properties: JsonObject): TrackerBoard = TrackerEngine.build(
        CreatureSheet.fromSnapshotJson(
            ContractFixtures.snapshotBody(ContractFixtures.creature(), properties.toList()),
            ContractFixtures.creatureId,
        ),
    )

    private fun JsonObject.expected(): JsonObject = this["expected"]!!.jsonObject

    private fun JsonObject.frame(): JsonObject = this["frame"]!!.jsonObject

    private fun JsonObject.params(): JsonArray = this["params"]!!.jsonArray

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitive(): JsonPrimitive = this as JsonPrimitive

    /**
     * The forbidden list, parsed out of `tools/public-gate.sh`'s `FORBIDDEN=( … )` array.
     *
     * Read rather than duplicated, so adding a pattern to the release gate automatically
     * tightens this test too — and so a divergence between the two is impossible rather than
     * merely unlikely.
     */
    private fun forbiddenPatterns(): List<String> {
        val script = File(repoRoot(), "tools/public-gate.sh").readText()
        val block = script.substringAfter("FORBIDDEN=(").substringBefore("\n)")
        return block.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("'") && it.endsWith("'") && it.length > 2 }
            .map { it.trim('\'') }
            .toList()
    }

    companion object {

        private const val WRITE_PROPERTY = "magehand.contract.write"

        /**
         * Draws per backoff attempt when re-sampling the configured policy.
         *
         * Enough that a genuinely wrong bound is caught rather than merely likely to be — with
         * a uniform spread, 200 draws miss a range half the exported width with probability
         * around 2^-200 — and cheap enough that eight attempts stay well under a millisecond.
         */
        private const val SAMPLES_PER_ATTEMPT = 200

        /**
         * `./gradlew exportContract`'s only difference from `./gradlew test`.
         *
         * Writing happens **once, before every assertion in this class**, so the export task
         * both regenerates the directory and then verifies it — the same suite, against the
         * bytes it just produced. Only the `exportContract` task ever sets the property, so a
         * plain test run can only check.
         *
         * The directory is deleted first rather than overwritten: a file that stops being
         * generated has to stop being committed, and an overwrite would leave it behind for a
         * consumer to keep verifying against a manifest that no longer lists it.
         */
        @BeforeClass
        @JvmStatic
        fun writeIfRequested() {
            if (System.getProperty(WRITE_PROPERTY) != "true") return
            val root = File(requireNotNull(System.getProperty("magehand.repoRoot")))
            val dir = File(root, ContractExport.DIRECTORY)
            val generated = ContractExport.generate(sourceCommit = gitHead(root), generatedOn = today())
            dir.deleteRecursively()
            for ((path, content) in generated) {
                val file = File(dir, path)
                file.parentFile.mkdirs()
                file.writeText(content)
            }
            println("contract export written: ${generated.size} files under ${dir.path}")
        }

        private fun gitHead(root: File): String {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(root)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            check(process.waitFor() == 0) { "git rev-parse HEAD failed: $output" }
            return output
        }

        private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()
    }
}
