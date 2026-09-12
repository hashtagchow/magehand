package com.hashtagchow.magehand.core.data.catalog

import com.hashtagchow.magehand.core.model.CatalogSpell
import com.hashtagchow.magehand.core.model.CatalogWeapon
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The bundled **SRD spell and weapon catalogs** — FR-49,
 * docs/design/20-local-spells-and-attacks.md decision 7.
 *
 * ### Source, edition and licence — the attribution CC-BY-4.0 requires
 *
 * The spells are the **System Reference Document 5.1** and the weapons the **System Reference
 * Document 5.2.1**, both by Wizards of the Coast LLC and both published under the
 * **Creative Commons Attribution 4.0 International** licence — the architect's ruling of
 * 2026-09-12, recorded in `provenance.json`'s `_comment`: the intermediate repository's MIT/OGL
 * note describes its own code and is not the content licence. This KDoc is half of the attribution
 * CC-BY requires; the other half is user-visible, in Settings' About section, because a licence
 * term satisfied only in a comment is a licence term satisfied only for people who read the
 * source. That section prints [CatalogSource.attribution] **verbatim** (M2 [review, 2026-09-12]),
 * so the recorded sentence and the printed one are the same string.
 *
 * `resources/catalog/provenance.json` is the record of *which* retrieval these files are —
 * the intermediate repository, its commit, the date, the entry counts, and what was composed
 * rather than copied — and `CatalogTest` pins it against the data. Nothing enters
 * either catalog from a non-SRD source; [com.hashtagchow.magehand.core.model.ItemCatalog]'s rule
 * is the rule here.
 *
 * **The two editions are not mixed inside a file**, which is decision 7's fence. They differ
 * between files because SRD 5.2.1 publishes no spell list of its own in the data that was
 * obtainable verbatim (see `provenance.json`'s `editionNote`, which names the exact directory
 * listing that was checked), and because weapon **mastery** is a 2024 property — the whole reason
 * FR-47's badge has anything to render on a local attack.
 *
 * ### Why this lives in `:core:data` and not in `:core:model`
 *
 * Decision 7's file list puts these in `:core:model`. They are not there, and this is the wave's
 * one recorded deviation from it: `:core:model`'s own build file states the rule it would break —
 * *"deliberately dependency-free … no Android, no serialization … Adding a dependency here is a
 * design change, not a convenience"* (docs/design/01-architecture.md). Three hundred spells of
 * prose is a **JSON resource** rather than a Kotlin table for decision 7's stated reason, and
 * parsing a JSON resource needs a serializer, so one of the two rules had to give. The older and
 * broader one is kept: the *types* a caller holds ([CatalogSpell], [CatalogWeapon]) stay in
 * `:core:model` beside [com.hashtagchow.magehand.core.model.CatalogItem], and the parse lives
 * here, in the module that already owns every other parse in the app. Nothing downstream notices
 * — `:app` depends on both modules — and no decision 7 requirement is lost: the resource is still
 * bundled, still loaded once, and the JVM tests still load the same bytes the app does.
 *
 * ### Loaded once, lazily, from the module's own jar
 *
 * `by lazy`, so an app that never opens the add sheet never parses 365 KB — and so a unit test
 * that does opens exactly the file that ships. The stream comes from this class's own loader
 * rather than from a `Context`, which is what lets a plain JVM test read it with no Robolectric.
 *
 * A missing or malformed resource **throws**, and deliberately: it is a packaging failure, not a
 * runtime condition, and an empty catalog that renders as *"no matches"* would be the same defect
 * discovered a release later by a player who assumed they had mistyped.
 */
object SpellCatalog {

    /** All 319 SRD 5.1 spells, sorted by level then name — the order the picker scans in. */
    val entries: List<CatalogSpell> by lazy {
        readCatalog("spells.json", SpellJson.serializer()).map { it.toDomain() }
    }

    /** By [CatalogSpell.id]; `null` for one this build does not carry. Built once with [entries]. */
    private val index: Map<String, CatalogSpell> by lazy { entries.associateBy { it.id } }

    fun byId(id: String): CatalogSpell? = index[id]

    /**
     * The entries whose **name** contains [query], case-insensitively — `AddItemSheet`'s
     * `catalogMatches` rule, restated for a list two orders of magnitude longer.
     *
     * Name only, matching the inventory catalog and FR-24's filter: a description search over
     * three hundred spells surfaces *Fireball* for a query of "damage", which reads as a false
     * positive to a player who typed a spell name. A blank query returns everything, so the
     * sheet's first frame is the whole list rather than an empty state nobody asked for.
     */
    fun search(query: String): List<CatalogSpell> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return entries
        return entries.filter { it.name.contains(trimmed, ignoreCase = true) }
    }
}

/** The weapon half. See [SpellCatalog]'s KDoc for source, licence, edition and placement. */
object WeaponCatalog {

    /** All 38 SRD 5.2.1 weapons, sorted by category then name. */
    val entries: List<CatalogWeapon> by lazy {
        readCatalog("weapons.json", WeaponJson.serializer()).map { it.toDomain() }
    }

    private val index: Map<String, CatalogWeapon> by lazy { entries.associateBy { it.id } }

    fun byId(id: String): CatalogWeapon? = index[id]

    /** [SpellCatalog.search]'s rule over the weapon names. */
    fun search(query: String): List<CatalogWeapon> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return entries
        return entries.filter { it.name.contains(trimmed, ignoreCase = true) }
    }
}

/**
 * The retrieval record, as `provenance.json` states it — one entry per catalog.
 *
 * Read rather than restated in Kotlin so that `CatalogTest` can check the **file** the
 * app ships against the **data** the app ships: a count that drifted from its record is then a
 * failing test rather than a comment nobody re-read. The strings are also what Settings' About
 * section prints, so the attribution a user sees and the attribution the repo records cannot
 * diverge.
 */
object CatalogProvenance {

    val spells: CatalogSource by lazy { all.spells }

    val weapons: CatalogSource by lazy { all.weapons }

    private val all: ProvenanceJson by lazy {
        CATALOG_JSON.decodeFromString(
            ProvenanceJson.serializer(),
            readResource("provenance.json"),
        )
    }
}

/**
 * One catalog's provenance, as the user-visible and test-visible half of `provenance.json`.
 *
 * Deliberately **not** every field in that file: the licence notes and derivation notes there are
 * prose for a reader of the repository, and a type that named them would invite a screen to print
 * a paragraph about a directory listing. What is here is what is either shown or asserted.
 */
@Serializable
data class CatalogSource(
    /** *"SRD 5.1"*, *"SRD 5.2.1"*. */
    val edition: String,
    /** How many entries the file is recorded as holding. Pinned against the data by test. */
    val entryCount: Int,
    /** *"CC-BY-4.0"*. */
    val contentLicence: String,
    /** The licence's own URI — CC-BY-4.0 §3(a)(1)(A)(iv). Asserted to be *inside* [attribution]. */
    val contentLicenceUrl: String,
    /**
     * The whole attribution sentence CC-BY requires, and **the exact text Settings prints**.
     *
     * M2 [review, 2026-09-12]. Settings used to compose its own sentence out of `strings.xml` and
     * substitute only the edition names, which made this field a record nobody read and put the
     * user-visible wording two files away from the licence record it is supposed to be. It is
     * rendered verbatim now, so the sentence carries everything §3(a)(1) asks for by itself: the
     * creator, the title, the licence name, the licence **URI**, and the statement that the
     * material was **modified**. Changing what the app says about the licence means editing
     * `provenance.json` — which is the point of there being a `provenance.json`.
     */
    val attribution: String,
)

@Serializable
private data class ProvenanceJson(val spells: CatalogSource, val weapons: CatalogSource)

/**
 * `ignoreUnknownKeys`, so `provenance.json`'s `_comment`, its licence notes and its derivation
 * notes — written for a human reading the repository — do not have to appear on a Kotlin type
 * that exists to be printed and asserted. The data files carry no unknown keys today; the
 * tolerance is for the record, not for the rows.
 */
private val CATALOG_JSON = Json { ignoreUnknownKeys = true }

/**
 * One bundled catalog file, parsed with the serializer named **explicitly** by the caller.
 *
 * ### Why the serializer is a parameter and not a `reified T`
 *
 * M1 [review, 2026-09-12]. This used to be `inline fun <reified T> readCatalog(fileName)` calling
 * the no-argument `decodeFromString`, which resolves its serializer at runtime through
 * `serializer(typeOf<List<T>>())` — a **reflective** lookup that finds `SpellJson.$serializer` by
 * name. Release builds are minified, the wire shapes below are private and referenced from nowhere
 * else, and `app/proguard-rules.pro` kept serialization only under `ui.navigation.**`; so R8 was
 * free to strip or rename the generated serializer and the first parse would have thrown — in the
 * release variant only, on the Add sheet and in Settings, with every debug build and every unit
 * test green. Naming the serializer makes the reference **static**: `SpellJson.serializer()` is an
 * ordinary call R8 can see, and [ListSerializer] wraps it without a `KType` in sight.
 * [CatalogProvenance] already parsed this way; this is the rest of the file agreeing with it.
 *
 * The keep rule in `app/proguard-rules.pro` is belt to these braces, not the fix.
 */
private fun <T> readCatalog(fileName: String, serializer: KSerializer<T>): List<T> =
    CATALOG_JSON.decodeFromString(ListSerializer(serializer), readResource(fileName))

/**
 * One bundled catalog file's text.
 *
 * `SpellCatalog::class.java.classLoader` rather than a `Context`: these are Java resources in this
 * module's jar, so the same call works in the app, in a plain JVM unit test and under Robolectric,
 * and the test therefore reads the exact bytes that ship. A missing file throws — see
 * [SpellCatalog]'s KDoc for why that is the right failure.
 */
private fun readResource(fileName: String): String {
    val path = "catalog/$fileName"
    val stream = SpellCatalog::class.java.classLoader?.getResourceAsStream(path)
        ?: error("bundled catalog resource $path is missing from :core:data")
    return stream.use { it.readBytes().decodeToString() }
}

// --- wire shapes ------------------------------------------------------------
//
// One `@Serializable` mirror per catalog, kept private to this file, so that the domain types in
// `:core:model` stay annotation-free — which is what lets that module keep the dependency-free
// posture this file's KDoc argues about. The mirrors are also where a schema change would land:
// a renamed field in a future retrieval fails to parse here rather than silently reading null
// into a domain object that claims the field is optional.

@Serializable
private data class SpellJson(
    val id: String,
    val name: String,
    val level: Int,
    val school: String,
    val castingTime: String,
    val range: String,
    val components: String,
    val duration: String,
    val concentration: Boolean,
    val ritual: Boolean,
    val description: String,
    val higherLevels: String? = null,
    val classes: List<String> = emptyList(),
) {
    fun toDomain(): CatalogSpell = CatalogSpell(
        id = id,
        name = name,
        level = level,
        school = school,
        castingTime = castingTime,
        range = range,
        components = components,
        duration = duration,
        concentration = concentration,
        ritual = ritual,
        description = description,
        higherLevels = higherLevels,
        classes = classes,
    )
}

@Serializable
private data class WeaponJson(
    val id: String,
    val name: String,
    val category: String,
    val damage: String,
    val properties: List<String> = emptyList(),
    val mastery: String? = null,
    val weightLb: Double? = null,
    val costGp: Double? = null,
) {
    fun toDomain(): CatalogWeapon = CatalogWeapon(
        id = id,
        name = name,
        category = category,
        damage = damage,
        properties = properties,
        mastery = mastery,
        weightLb = weightLb,
        costGp = costGp,
    )
}
