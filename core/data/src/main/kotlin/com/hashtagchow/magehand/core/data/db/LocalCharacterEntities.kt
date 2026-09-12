package com.hashtagchow.magehand.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity.Companion.CATEGORY_GEAR
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.CatalogCategory
import com.hashtagchow.magehand.core.model.CoinPurse
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.ResetRule

/**
 * The two tables schema **version 3** adds (docs/design/09-local-characters.md decisions
 * 1 and 2):
 *
 * ```
 * local_characters(id PK, name, level, str..cha, maxHp, currentHp, ac, createdAt, updatedAt)
 * local_tracker_rows(id PK, characterId FK→local_characters(id) CASCADE, kind, label,
 *                    total, current, resetRule, sortIndex)
 * ```
 *
 * ### No `accountId` anywhere
 *
 * 09 decision 1, and it is the whole point: a local character is not a fake account. There
 * is no column here that a sentinel account id could occupy, so no account-keyed query, no
 * WebView session-store key and no sign-out path can reach these rows by accident. Sign-out
 * deletes account-scoped rows (`deleteForAccount` on the four WP4 DAOs); it cannot delete
 * these, which is exactly 09 decision 10's requirement.
 *
 * ### Why a foreign key here when WP4's tables have none
 *
 * The WP4 tables deliberately have no FK to `accounts` because deletion there is explicit
 * and order-dependent cascading would make sign-out fragile. The relationship here is the
 * opposite kind: a tracker row **cannot exist** without its character — it is a part, not an
 * association — and a row orphaned from a deleted character would be unreachable garbage
 * that no DAO method could ever name again. `ON DELETE CASCADE` is the correct semantic and
 * it takes the "delete the rows first" ordering bug off the table for good.
 */

@Entity(tableName = "local_characters")
data class LocalCharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** `null` when the player left it blank; 1–20 otherwise (09 decision 4). */
    val level: Int?,
    val strength: Int,
    val dexterity: Int,
    val constitution: Int,
    val intelligence: Int,
    val wisdom: Int,
    val charisma: Int,
    val maxHp: Int,
    val currentHp: Int,
    val armorClass: Int,
    /**
     * The four coin columns schema **version 4** adds (docs/design/10-inventory.md decision
     * 10). Platinum, gold, silver, copper.
     *
     * `defaultValue` is declared rather than left to the Kotlin default, and that is what
     * makes [MIGRATION_3_4] honest: SQLite requires a `DEFAULT` when adding a `NOT NULL`
     * column to a table that already has rows, so the migrated column *will* carry one. If
     * the entity did not, a fresh install's `CREATE TABLE` would carry none and the two
     * schemas would differ in a way Room's validator happens to tolerate — a difference that
     * is invisible until it is not. Declaring it makes the exported v4 schema and the
     * migration say the same thing, which is the claim the migration test checks.
     */
    @ColumnInfo(defaultValue = "0") val pp: Int = 0,
    @ColumnInfo(defaultValue = "0") val gp: Int = 0,
    @ColumnInfo(defaultValue = "0") val sp: Int = 0,
    @ColumnInfo(defaultValue = "0") val cp: Int = 0,
    /**
     * The two death-save columns schema **version 6** adds (FR-23,
     * docs/design/15-polish-batch.md decision 13). Marks, not remaining — `0..DeathSaves.MAX`.
     *
     * ### Two `Int`s, not a `DeathSaves`
     *
     * A local character has no `creatureProperties` and therefore no pair of property ids to
     * carry, which is most of what `DeathSaves` is. What survives the trip to a Room row is the
     * two counts, and `LocalTrackerBoard` rebuilds the domain type from them with synthetic ids
     * — exactly the shape the four coin columns already have against `CoinPurse`.
     *
     * `defaultValue` is declared for the coin columns' reason, unchanged: SQLite requires a
     * `DEFAULT` when adding a `NOT NULL` column to a populated table, so the migrated column
     * carries one, and an entity that did not declare it would make a fresh install's
     * `CREATE TABLE` disagree with the migrated schema. `0` is also the only honest value —
     * every row predating this column belongs to a character nobody has ever rolled a death
     * save for, because the app had nowhere to record one.
     */
    @ColumnInfo(defaultValue = "0") val deathSuccesses: Int = 0,
    @ColumnInfo(defaultValue = "0") val deathFailures: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "local_tracker_rows",
    foreignKeys = [
        ForeignKey(
            entity = LocalCharacterEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["characterId"])],
)
data class LocalTrackerRowEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    /** [LocalRowKind.storedValue] — `"slot"` / `"resource"` / `"item"` / `"action"`. */
    val kind: String,
    val label: String,
    val total: Int,
    val current: Int,
    /**
     * [ResetRule.wireValue], or [RESET_NONE].
     *
     * The *same* wire strings DiceCloud uses (`"shortRest"` / `"longRest"`), not a second
     * private vocabulary, so [ResetRule.fromWire] is the only parser in the app and a
     * database dump reads the same either side of the local/server line. `NOT NULL` with an
     * explicit `"none"` rather than a nullable column: on a *discovered* sheet a null reset is
     * already load-bearing — `TrackerEngine.spellSlot` drops those rows, because a slot no rest
     * restores is a slot the tracker's controls would lie about — so reusing null for a user's
     * deliberate "no reset" would put two unrelated meanings on one absence.
     *
     * The clause this used to carry — *"a null reset means this is a death-save counter"* — is
     * **retired** by FR-23 decision 19: that reading was a coincidence, and death saves are
     * discovered by `variableName`. The column's argument survives it intact, because the
     * exclusion it depends on is unchanged; only the story about what the null *means* was
     * wrong. See `TrackerEngine.spellSlot`, where the correction lives in full.
     */
    val resetRule: String,
    val sortIndex: Int,
    /**
     * The four inventory columns schema **version 4** adds (10 decision 10).
     *
     * `weight` and `value` are `REAL?` and `description` is `TEXT?`, because a form field the
     * player left blank is an absence rather than a zero — see [LocalTrackerRow.weightLb].
     * `equipped` is `NOT NULL DEFAULT 0`, with the same `defaultValue` discipline the coin
     * columns get and for the same reason.
     */
    val weight: Double? = null,
    val value: Double? = null,
    val description: String? = null,
    @ColumnInfo(defaultValue = "0") val equipped: Boolean = false,
    /**
     * The one column schema **version 5** adds
     * (docs/design/13-collapsible-sections-local-gear.md decision 8).
     *
     * [CatalogCategory.storedValue] — `"weapon"` / `"armor"` / `"gear"`.
     *
     * `NOT NULL DEFAULT 'gear'`, with the same `defaultValue` discipline the v4 columns get and
     * for the same reason (see the coin columns' KDoc): SQLite refuses `ADD COLUMN … NOT NULL`
     * without a default, so the migrated column *will* carry one, and the entity has to declare
     * it or a fresh install's `CREATE TABLE` would not — two schemas differing in a way that is
     * invisible until it is not.
     *
     * **The default is a claim about data, not a convenience.** Every row that predates this
     * column was collected by a build that asked no such question, and 13 decision 8 reads that
     * as gear rather than as the all-equippable interim `LocalInventoryBoard` used to render.
     * Nothing a player did becomes undone by it: an equipped row keeps its control through the
     * rule's `equipped` disjunct, and any other row can be rescued by 11 decision 2's override —
     * which is exactly what that override has existed for since 1.4.0.
     *
     * `NOT NULL` with an explicit `'gear'` rather than a nullable column, matching [resetRule]'s
     * argument: there is no second meaning available for a null here, so allowing one would put
     * "never collected" and "collected as nothing" on one absence.
     */
    @ColumnInfo(defaultValue = "'${CATEGORY_GEAR}'")
    val category: String = CATEGORY_GEAR,
    /**
     * The two columns schema **version 7** adds (FR-29, docs/design/18-table-pack.md decision 1).
     *
     * ```
     * local_tracker_rows += costRowId  TEXT
     *                    += costAmount INTEGER
     * ```
     *
     * ### Two columns, not three
     *
     * Decision 1 lists *"`costRowId TEXT NULL`, `costAmount INTEGER NULL`, `description TEXT NULL`
     * **if absent** for ACTION rows — exact columns the wave's call"*. `description` is **not**
     * absent: FR-8 added it in v4 for item notes and it is nullable `TEXT` already, which is
     * exactly the column an action's description wants. Adding a second one would have given the
     * table two text columns meaning the same thing for two kinds of row, and the repository's
     * save path would have had to know which. So v7 is the two genuinely new columns and nothing
     * else — the wave's call, recorded here rather than in a commit message.
     *
     * ### Both nullable, so neither takes a `DEFAULT`
     *
     * The opposite of every column added since v4, and for the reason those needed one: SQLite
     * refuses `ADD COLUMN … NOT NULL` without a default because there would be no value for the
     * existing rows. These are nullable, so `NULL` *is* the value, and `NULL` is also the honest
     * reading — no row that predates v7 is an action, so none of them has a cost. Declaring a
     * default would have been inventing one.
     *
     * ### Why the pair is two columns rather than one encoded string
     *
     * `costRowId` is a foreign-key-shaped reference the picker resolves and `costAmount` is a
     * number the confirm dialog prints; storing `"row-1:2"` would put a parser between the
     * database and both of them. See [LocalTrackerRow.costRowId] for why there is deliberately no
     * actual `FOREIGN KEY` clause on it.
     */
    val costRowId: String? = null,
    val costAmount: Int? = null,
    /**
     * The eleven columns schema **version 8** adds (FR-49,
     * docs/design/20-local-spells-and-attacks.md decision 2).
     *
     * ```
     * local_tracker_rows += catalogId     TEXT
     *                    += spellLevel    INTEGER
     *                    += higherLevels  TEXT
     *                    += castingTime   TEXT
     *                    += range         TEXT
     *                    += components    TEXT
     *                    += duration      TEXT
     *                    += concentration INTEGER NOT NULL DEFAULT 0
     *                    += ritual        INTEGER NOT NULL DEFAULT 0
     *                    += damage        TEXT
     *                    += properties    TEXT
     * ```
     *
     * ### Eleven columns on one shared table, and why that is still the right shape
     *
     * This is the biggest single widening `local_tracker_rows` has taken, and the case for a
     * second table gets stronger every time — so it is worth writing down why it is still
     * declined. The table is the **unit of a player's row list**: `sortIndex` orders across kinds,
     * `costRowId` references across kinds, `save` upserts the whole list in one transaction, and
     * `deleteRowsMissing` reaps by the set the form still holds. Splitting spells out would mean
     * two tables joined on a shared order, a cost reference with two possible targets, and a save
     * that is atomic across both — which is a great deal of machinery bought to avoid nullable
     * columns the other kinds simply do not read. [weightLb]'s own argument, at scale: *one unused
     * field on a shared row type is cheaper than a second row type, and every consumer already
     * switches on [kind]*.
     *
     * ### Nine nullable, two `NOT NULL DEFAULT 0`
     *
     * The same discipline every column since v4 has followed, read from both ends.
     * [concentration] and [ritual] are booleans with no third state — a spell either needs
     * concentration or it does not — so they are `NOT NULL`, which SQLite then requires a
     * `DEFAULT` for on a populated table, which the `@ColumnInfo` here has to declare or a fresh
     * install's `CREATE TABLE` would disagree with the migrated schema. `0` is also the fact
     * rather than a chosen reading: no row predating v8 is a spell, so none of them concentrates.
     *
     * The other nine are nullable and take **no** default, matching v7's pair: `NULL` is what the
     * existing rows get and `NULL` is what they mean — a row that is not a spell has no casting
     * time, and a row that is not an attack has no damage text. There is no reading to choose here
     * of the kind v5's `'gear'` had to make.
     *
     * ### `spellLevel` is the one column with two meanings, and the migration back-fills it
     *
     * On a SPELL row it is the spell's level; on a SLOT row it is the slot's (decision 3), which
     * is what lets `toTrackedResource` publish a real `spellSlotLevel` and the existing
     * `spellSlotOptions` offer a local slot unchanged. [MIGRATION_7_8] back-fills the SLOT half
     * from the label's leading integer. See [LocalTrackerRow.spellLevel].
     *
     * ### `damage` is TEXT, and that is decision 8 in the schema
     *
     * Not a dice expression to be evaluated and not a foreign key to a damage row: a local
     * character has no sheet to resolve either against, so the column holds the SRD's own words
     * and the app renders them. See [LocalTrackerRow.damage].
     */
    val catalogId: String? = null,
    val spellLevel: Int? = null,
    val higherLevels: String? = null,
    val castingTime: String? = null,
    /**
     * The spell's range.
     *
     * Named `range` on the entity and therefore in SQL too. `range` is not a SQLite keyword (it is
     * not reserved in any SQLite version), and Room quotes every identifier in the DDL it
     * generates and the statements it compiles, so the column needs no alias. Worth stating
     * because the name looks like one.
     */
    val range: String? = null,
    val components: String? = null,
    val duration: String? = null,
    @ColumnInfo(defaultValue = "0") val concentration: Boolean = false,
    @ColumnInfo(defaultValue = "0") val ritual: Boolean = false,
    val damage: String? = null,
    val properties: String? = null,
) {
    companion object {
        const val RESET_NONE: String = "none"

        /**
         * The v5 column's default, as a compile-time constant so the `@ColumnInfo` annotation
         * and the Kotlin default cannot drift.
         *
         * Not `CatalogCategory.GEAR.storedValue`, much as that would be the tidier spelling: an
         * annotation argument must be a compile-time constant, and a `when` on an enum is not
         * one. `CatalogCategoryTest` asserts the two agree, which is the check that would
         * otherwise be a comment nobody runs.
         */
        const val CATEGORY_GEAR: String = "gear"
    }
}

// --- mapping ----------------------------------------------------------------

fun LocalCharacterEntity.toDomain(): LocalCharacter = LocalCharacter(
    id = id,
    name = name,
    level = level,
    abilities = AbilityScores(
        strength = strength,
        dexterity = dexterity,
        constitution = constitution,
        intelligence = intelligence,
        wisdom = wisdom,
        charisma = charisma,
    ),
    maxHp = maxHp,
    currentHp = currentHp,
    armorClass = armorClass,
    coins = CoinPurse(platinum = pp, gold = gp, silver = sp, copper = cp),
    deathSuccesses = deathSuccesses,
    deathFailures = deathFailures,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun LocalCharacter.toEntity(): LocalCharacterEntity = LocalCharacterEntity(
    id = id,
    name = name,
    level = level,
    strength = abilities.strength,
    dexterity = abilities.dexterity,
    constitution = abilities.constitution,
    intelligence = abilities.intelligence,
    wisdom = abilities.wisdom,
    charisma = abilities.charisma,
    maxHp = maxHp,
    currentHp = currentHp,
    armorClass = armorClass,
    pp = coins.platinum,
    gp = coins.gold,
    sp = coins.silver,
    cp = coins.copper,
    deathSuccesses = deathSuccesses,
    deathFailures = deathFailures,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * `null` when the stored `kind` is not one this build knows — a downgrade-then-upgrade
 * could in principle leave one, and a row we cannot render is a row we drop rather than a
 * crash on the tracker.
 */
fun LocalTrackerRowEntity.toDomain(): LocalTrackerRow? {
    val rowKind = LocalRowKind.fromStored(kind) ?: return null
    return LocalTrackerRow(
        id = id,
        characterId = characterId,
        kind = rowKind,
        label = label,
        total = total,
        current = current,
        reset = ResetRule.fromWire(resetRule),
        sortIndex = sortIndex,
        weightLb = weight,
        valueGp = value,
        description = description,
        equipped = equipped,
        // Never `null`, and never a dropped row: an unrecognised category from a future build
        // reads as gear. See [CatalogCategory.fromStored] for why that differs from `kind`'s
        // null-and-drop.
        category = CatalogCategory.fromStored(category),
        // The pair moves together or not at all: a cost row with no amount, or an amount naming
        // no row, is half a cost and there is no reading of it. Either half missing reads as "no
        // cost", which is the state every pre-v7 row is in.
        costRowId = costRowId?.takeIf { it.isNotBlank() && costAmount != null },
        costAmount = costAmount?.takeIf { !costRowId.isNullOrBlank() },
        // FR-49's eleven, straight across. Nothing is normalised on the way out — unlike the cost
        // pair above, which has a half-filled state the form refuses to create and this refuses to
        // return. These have no such pairing: a spell with a range and no duration is an ordinary
        // spell, and a blank string is the player's own empty field rather than a broken record.
        // Blank-to-absent happens where it is *rendered* (`LocalActionBoard`), so the editor still
        // shows the player exactly what they typed.
        catalogId = catalogId,
        spellLevel = spellLevel,
        higherLevels = higherLevels,
        castingTime = castingTime,
        range = range,
        components = components,
        duration = duration,
        concentration = concentration,
        ritual = ritual,
        damage = damage,
        properties = properties,
    )
}

fun LocalTrackerRow.toEntity(): LocalTrackerRowEntity = LocalTrackerRowEntity(
    id = id,
    characterId = characterId,
    kind = kind.storedValue,
    label = label,
    total = total,
    current = current,
    resetRule = reset?.wireValue ?: LocalTrackerRowEntity.RESET_NONE,
    sortIndex = sortIndex,
    weight = weightLb,
    value = valueGp,
    description = description,
    equipped = equipped,
    category = category.storedValue,
    costRowId = costRowId,
    costAmount = costAmount,
    catalogId = catalogId,
    spellLevel = spellLevel,
    higherLevels = higherLevels,
    castingTime = castingTime,
    range = range,
    components = components,
    duration = duration,
    concentration = concentration,
    ritual = ritual,
    damage = damage,
    properties = properties,
)
