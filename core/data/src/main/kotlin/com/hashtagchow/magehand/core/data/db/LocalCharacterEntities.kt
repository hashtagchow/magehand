package com.hashtagchow.magehand.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.hashtagchow.magehand.core.model.AbilityScores
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
    /** [LocalRowKind.storedValue] — `"slot"` / `"resource"` / `"item"`. */
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
     * explicit `"none"` rather than a nullable column: on a discovered spell slot a null
     * reset means "this is a death-save counter" (02 §Known server quirks), and reusing null
     * for a user's deliberate "no reset" would put two unrelated meanings on one absence.
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
) {
    companion object {
        const val RESET_NONE: String = "none"
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
)
