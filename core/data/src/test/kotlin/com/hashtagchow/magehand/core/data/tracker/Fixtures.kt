package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * The committed live capture — a real character sheet, recorded once and asserted against
 * ever since (docs/design/03-data-model.md names it as *the* tracker fixture).
 *
 * Read from the repo rather than copied into `src/test/resources`, so there is exactly
 * one 1.1 MB copy in the tree and the tests can never drift from the recorded capture.
 * The build script already exposes `magehand.repoRoot` for WP3's live probe.
 *
 * ### This file contains no real identifiers, and that is the point
 *
 * It is the one file in the source tree whose job is to *name* private data, which used
 * to make it the one file that had to be exempted from `tools/public-gate.sh`. An
 * exemption is a hole, and a hole in a leak gate is where the next leak goes. So every
 * value that identifies anything — the creature id, its name, its owner, the soft-deleted
 * property — is now **read out of the capture at runtime** instead of written down here.
 * The gate now passes this file with no exemption at all.
 *
 * The cost is that these are `get()`-style properties rather than `const`, so touching
 * any of them parses the capture and therefore raises the same JUnit **skip** as
 * [sabrielBody] when the capture is absent (a public clone). That is the correct
 * behaviour — an assertion about a sheet nobody has is not a failure — and it is what
 * every consumer already wanted: all of them are fixture-coupled tests.
 *
 * Where the capture's *shape* is an assumption rather than a lookup, it is checked rather
 * than trusted: a future capture holding two creatures, or two deleted properties, fails
 * loudly here instead of silently picking one and changing what a dozen tests mean.
 */
object Fixtures {

    /**
     * The captured creature's id, display name and owner id.
     *
     * Derived, not declared — see the file KDoc. The capture holds exactly one creature,
     * which is what makes "the creature" a well-defined phrase; [capturedCreature]
     * enforces that rather than assuming it.
     */
    val SABRIEL_ID: String get() = capturedCreature.stringField("_id")

    /** @see SABRIEL_ID */
    val SABRIEL_NAME: String get() = capturedCreature.stringField("name")

    /** @see SABRIEL_ID */
    val SABRIEL_OWNER: String get() = capturedCreature.stringField("owner")

    /**
     * The one soft-deleted property in the capture — see docs/verification/WP4.md §Delta.
     *
     * Found by the flag rather than by its id, so this stays correct if the capture is
     * ever re-recorded, and so the id itself never appears in source. The REST snapshot
     * still carries it while the DDP mirror does not, which is the whole delta that
     * `MirrorVsSnapshotDeltaTest` exists to pin.
     */
    val REMOVED_PROPERTY_ID: String get() = removedProperty

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val sabrielBody: String by lazy {
        val root = System.getProperty("magehand.repoRoot")
            ?: error("magehand.repoRoot system property not set by the build script")
        // Located by directory, not by filename: the capture is named for the character
        // and the date it was recorded, so hard-coding it would put a real name back in
        // the source (and would need editing every time it is re-recorded).
        val dir = File(root, FIXTURE_DIR)
        val captures = dir.listFiles { f: File -> f.isFile && f.extension == "json" }.orEmpty()
        // The capture is a real character sheet and is not part of the public source
        // release. When it is absent (public clone), every test that feeds on it is
        // SKIPPED rather than failed - AssumptionViolatedException marks a JUnit skip.
        if (captures.isEmpty()) {
            throw org.junit.AssumptionViolatedException(
                "no capture in ${dir.path} (private fixture, excluded from the public repo)")
        }
        check(captures.size == 1) {
            "expected exactly one capture in ${dir.path}; found " +
                captures.joinToString { it.name } +
                ". Every fixture-coupled assertion in the suite means 'the capture'."
        }
        captures.single().readText()
    }

    private const val FIXTURE_DIR = "docs/fixtures"

    val sabrielRoot: JsonObject by lazy { json.parseToJsonElement(sabrielBody) as JsonObject }

    /**
     * The REST capture, parsed the way the offline path parses it. Built from the shared
     * lazily-parsed root so a test class does not re-parse 1.1 MB per assertion.
     */
    fun sabrielSheet(): CreatureSheet = CreatureSheet.fromSnapshotJson(sabrielRoot, SABRIEL_ID)

    /**
     * The same capture reshaped as a DDP mirror snapshot — `Map<collection, Map<_id, doc>>`.
     *
     * This is the shape [com.hashtagchow.magehand.core.ddp.MongoMirror.snapshot] produces,
     * and it is only possible because the mirror injects `_id` into every document
     * (docs/verification/WP2.md deviation #6).
     */
    fun sabrielMirror(): Map<String, Map<String, JsonObject>> = buildMap {
        for (collection in listOf(
            CreatureSheet.CREATURES,
            CreatureSheet.CREATURE_PROPERTIES,
            CreatureSheet.CREATURE_VARIABLES,
        )) {
            val docs = (sabrielRoot[collection] as? JsonArray).orEmpty()
                .filterIsInstance<JsonObject>()
                .associateBy { (it.jsonId()) }
            put(collection, docs)
        }
    }

    /**
     * The single creature document.
     *
     * `lazy` rather than a function so the size check runs once, and `check` rather than
     * `first()` because "there is one creature" is an assumption this whole object rests
     * on: a two-creature capture would make [SABRIEL_ID] an arbitrary pick and quietly
     * re-point every fixture-coupled assertion at a different sheet. Loud is correct here.
     * (This is a *shape* failure, not a missing capture, so it is an error rather than a
     * skip — the file is present and does not say what the tests think it says.)
     */
    private val capturedCreature: JsonObject by lazy {
        val creatures = documents(CreatureSheet.CREATURES)
        check(creatures.size == 1) {
            "the capture must hold exactly one creature; found ${creatures.size}. " +
                "Fixtures.SABRIEL_* would be an arbitrary pick — re-record or teach " +
                "this object which creature it means."
        }
        creatures.single()
    }

    /** @see REMOVED_PROPERTY_ID */
    private val removedProperty: String by lazy {
        val removed = documents(CreatureSheet.CREATURE_PROPERTIES).filter { it.isTrue("removed") }
        check(removed.size == 1) {
            "the capture must hold exactly one soft-deleted property; found ${removed.size}. " +
                "The REST-vs-DDP delta test asserts on 'the' removed property and would " +
                "otherwise be pinning whichever one happened to sort first."
        }
        removed.single().stringField("_id")
    }

    private fun documents(collection: String): List<JsonObject> =
        (sabrielRoot[collection] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()

    private fun JsonObject.stringField(name: String): String =
        (this[name] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.isTrue(name: String): Boolean =
        (this[name] as? JsonPrimitive)?.content == "true"

    private fun JsonObject.jsonId(): String = stringField("_id")

    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: JsonArray(emptyList())
}
