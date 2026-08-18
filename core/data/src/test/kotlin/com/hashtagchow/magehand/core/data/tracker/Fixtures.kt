package com.hashtagchow.magehand.core.data.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * The committed live capture, `docs/fixtures/sabriel-2026-08-17.json`
 * (docs/design/03-data-model.md names it as *the* tracker fixture).
 *
 * Read from the repo rather than copied into `src/test/resources`, so there is exactly
 * one 1.1 MB copy in the tree and the tests can never drift from the recorded capture.
 * The build script already exposes `magehand.repoRoot` for WP3's live probe.
 */
object Fixtures {

    const val SABRIEL_ID: String = "BnQXpq6swoFnpLyWX"

    /** The one soft-deleted property in the capture — see docs/verification/WP4.md §Delta. */
    const val REMOVED_PROPERTY_ID: String = "oQ956GvPzLM5dz4dK"

    /**
     * The captured creature's display name and owner id, capture-coupled like
     * [SABRIEL_ID]: assertions on them only mean anything with the private fixture
     * present, so they are read out of the capture rather than written down here —
     * the real values must not appear in the published source. Touching either
     * raises the same JUnit skip as [sabrielBody] when the fixture is absent.
     */
    val SABRIEL_NAME: String get() = capturedCreature("name")

    /** @see SABRIEL_NAME */
    val SABRIEL_OWNER: String get() = capturedCreature("owner")

    private fun capturedCreature(field: String): String =
        (sabrielRoot[CreatureSheet.CREATURES] as? JsonArray).orEmpty()
            .filterIsInstance<JsonObject>()
            .first { it.jsonId() == SABRIEL_ID }
            .let { (it[field] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val sabrielBody: String by lazy {
        val root = System.getProperty("magehand.repoRoot")
            ?: error("magehand.repoRoot system property not set by the build script")
        val file = File(root, "docs/fixtures/sabriel-2026-08-17.json")
        // The capture is a real character sheet and is not part of the public source
        // release. When it is absent (public clone), every test that feeds on it is
        // SKIPPED rather than failed - AssumptionViolatedException marks a JUnit skip.
        if (!file.isFile) {
            throw org.junit.AssumptionViolatedException(
                "fixture ${file.path} not present (private capture, excluded from the public repo)")
        }
        file.readText()
    }

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

    private fun JsonObject.jsonId(): String =
        (this["_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
        this ?: JsonArray(emptyList())
}
