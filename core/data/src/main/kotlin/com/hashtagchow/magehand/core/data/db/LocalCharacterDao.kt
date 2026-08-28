package com.hashtagchow.magehand.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * On-device characters and their tracker rows (docs/design/09-local-characters.md).
 *
 * ### The write posture this DAO assumes
 *
 * Every value that arrives here is **already clamped**. The clamps live in
 * `LocalOpenCharacter`, next to the ones `DefaultOpenCharacter` applies for the server path,
 * so the two implementations of one interface behave identically and there is one place to
 * read the rule (see `LocalOpenCharacter.spend`). A DAO that also clamped would be a second
 * copy of that rule, and second copies drift.
 *
 * The one exception is [rest], which is a *set-to-total* the SQL can express exactly, so it
 * is one statement rather than a read-modify-write per row.
 */
@Dao
interface LocalCharacterDao {

    // --- reads --------------------------------------------------------------

    /**
     * The "On this device" section (09 decision 3), newest first.
     *
     * By `createdAt` rather than `updatedAt`: the section is a stable shelf the player built,
     * and ordering by last-touched would make the list reshuffle itself every time a slot is
     * spent — which is exactly when the player is looking at it.
     */
    @Query("SELECT * FROM local_characters ORDER BY createdAt DESC, name ASC")
    fun observeAll(): Flow<List<LocalCharacterEntity>>

    @Query("SELECT * FROM local_characters ORDER BY createdAt DESC, name ASC")
    suspend fun getAll(): List<LocalCharacterEntity>

    @Query("SELECT * FROM local_characters WHERE id = :id")
    fun observe(id: String): Flow<LocalCharacterEntity?>

    @Query("SELECT * FROM local_characters WHERE id = :id")
    suspend fun find(id: String): LocalCharacterEntity?

    @Query("SELECT COUNT(*) FROM local_characters")
    suspend fun count(): Int

    @Query("SELECT * FROM local_tracker_rows WHERE characterId = :characterId ORDER BY sortIndex ASC, label ASC")
    fun observeRows(characterId: String): Flow<List<LocalTrackerRowEntity>>

    @Query("SELECT * FROM local_tracker_rows WHERE characterId = :characterId ORDER BY sortIndex ASC, label ASC")
    suspend fun getRows(characterId: String): List<LocalTrackerRowEntity>

    @Query("SELECT * FROM local_tracker_rows WHERE id = :rowId")
    suspend fun findRow(rowId: String): LocalTrackerRowEntity?

    // --- writes -------------------------------------------------------------

    @Upsert
    suspend fun upsert(character: LocalCharacterEntity)

    @Upsert
    suspend fun upsertRows(rows: List<LocalTrackerRowEntity>)

    @Query("DELETE FROM local_characters WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM local_tracker_rows WHERE characterId = :characterId AND id NOT IN (:keep)")
    suspend fun deleteRowsMissing(characterId: String, keep: List<String>)

    @Query("DELETE FROM local_tracker_rows WHERE characterId = :characterId")
    suspend fun deleteAllRows(characterId: String)

    /**
     * Deletes **one** row, by id — FR-9's local delete
     * (docs/design/12-inventory-layout.md decision 7).
     *
     * A real `DELETE` and not a flag. The server path soft-removes because that is the only
     * deletion its API offers, and the reversibility that falls out of it is a genuine gain;
     * copying the shape here would mean a `removed` column, a filter on every local query and
     * a tombstone the player can never see or empty — a schema migration to back an UNDO
     * button. `LocalOpenCharacter.removeItem` therefore files a non-undoable history entry and
     * the confirm dialog says so, which is the honest version of what this statement does.
     *
     * Keyed on the row id alone rather than on `(characterId, id)`: ids are UUIDs minted per
     * row (see `LocalOpenCharacter.newRowId`), and every other single-row statement in this
     * DAO — `setRowCurrent`, `setRowQuantity`, `setRowEquipped` — is keyed the same way.
     */
    @Query("DELETE FROM local_tracker_rows WHERE id = :rowId")
    suspend fun deleteRow(rowId: String)

    @Query("UPDATE local_characters SET currentHp = :currentHp, updatedAt = :at WHERE id = :id")
    suspend fun setCurrentHp(id: String, currentHp: Int, at: Long)

    @Query("UPDATE local_tracker_rows SET current = :current WHERE id = :rowId")
    suspend fun setRowCurrent(rowId: String, current: Int)

    /** [LocalRowKind.ITEM][com.hashtagchow.magehand.core.model.LocalRowKind.ITEM] only: an
     * item's quantity is both its value and its (absent) ceiling, so the two move together.
     */
    @Query("UPDATE local_tracker_rows SET current = :quantity, total = :quantity WHERE id = :rowId")
    suspend fun setRowQuantity(rowId: String, quantity: Int)

    @Query("UPDATE local_characters SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    /**
     * The wallet, all four columns at once (docs/design/10-inventory.md decision 10).
     *
     * One statement rather than four per-coin updates: the wallet is one value to the player,
     * and a stepper that could leave three of the four columns from one write and the fourth
     * from another is a partial state nothing needs. Values arrive already floored at zero —
     * see this DAO's write-posture note; the clamp lives in `LocalOpenCharacter`.
     */
    @Query("UPDATE local_characters SET pp = :pp, gp = :gp, sp = :sp, cp = :cp, updatedAt = :at WHERE id = :id")
    suspend fun setCoins(id: String, pp: Int, gp: Int, sp: Int, cp: Int, at: Long)

    /**
     * FR-23 decision 13: both death-save columns in one statement.
     *
     * One `UPDATE` and not two, unlike the server path's two `damage` calls — there the pair is
     * two documents and one method can only name one; here it is two columns of one row, so
     * writing them together is both cheaper and atomic. A caller that only means to change one
     * half passes the other's current value, which is what `LocalOpenCharacter.setDeathSaves`
     * does from inside its own critical section.
     */
    @Query(
        "UPDATE local_characters SET deathSuccesses = :successes, deathFailures = :failures, " +
            "updatedAt = :at WHERE id = :id",
    )
    suspend fun setDeathSaves(id: String, successes: Int, failures: Int, at: Long)

    /** 10 decision 10: local equip is a plain flag — there are no folders to move between. */
    @Query("UPDATE local_tracker_rows SET equipped = :equipped WHERE id = :rowId")
    suspend fun setRowEquipped(rowId: String, equipped: Boolean)

    /**
     * The highest `sortIndex` in use, or `null` for a character with no rows.
     *
     * The inventory's add path needs it for the same reason the server path needs an `order`:
     * a new item belongs at the end of the list, not wherever an index collision puts it.
     */
    @Query("SELECT MAX(sortIndex) FROM local_tracker_rows WHERE characterId = :characterId")
    suspend fun maxSortIndex(characterId: String): Int?

    @Query("UPDATE local_tracker_rows SET sortIndex = :sortIndex WHERE characterId = :characterId AND id = :rowId")
    suspend fun setRowSortIndex(characterId: String, rowId: String, sortIndex: Int)

    // --- transactions -------------------------------------------------------

    /**
     * The creation form's save path (09 decision 4), character and rows in one transaction.
     *
     * Rows the form no longer carries are deleted, so re-opening the form *is* the editor:
     * a removed row leaves no orphan, and the row ids the caller kept keep their `current`
     * value — [upsertRows] carries it, so editing a resource's label does not silently
     * refill it mid-session.
     *
     * One transaction and not three calls because a half-saved character — rows without
     * their sheet, or a sheet whose rows are the previous edit's — is a state the tracker
     * would happily render.
     */
    @Transaction
    suspend fun save(character: LocalCharacterEntity, rows: List<LocalTrackerRowEntity>) {
        upsert(character)
        if (rows.isEmpty()) {
            deleteAllRows(character.id)
        } else {
            // Delete-missing *before* the upsert so a row id reused across the two lists
            // cannot be removed after it was written.
            deleteRowsMissing(character.id, rows.map { it.id })
            upsertRows(rows)
        }
    }

    /**
     * 09 decision 7: a short rest refills rows whose reset rule is short; a long rest refills
     * short **and** long. Rows with `"none"` are never touched by either.
     *
     * Expressed as `current = total`, which is what "refill" means for a slot or a resource.
     * An item row cannot be caught by this in practice — the form offers no reset rule for
     * one — and if it were, `current = total` is a no-op there by construction, since the two
     * are kept equal for items.
     */
    @Query("UPDATE local_tracker_rows SET current = total WHERE characterId = :characterId AND resetRule IN (:rules)")
    suspend fun refillRows(characterId: String, rules: List<String>)

    /**
     * [rest]'s long-rest half — 09 decision 7's dated correction note: `currentHp = maxHp`,
     * one statement, called from inside the same [rest] transaction as [refillRows] so the two
     * can never be observed half-done. Never called for a short rest; 5e leaves HP alone there.
     */
    @Query("UPDATE local_characters SET currentHp = maxHp WHERE id = :id")
    suspend fun setCurrentHpToMax(id: String)

    /**
     * FR-29 decision 4's Use: **the uses decrement and the cost decrement, in ONE transaction.**
     *
     * > *"Use decrements uses and the cost row in ONE Room transaction"*
     *
     * ### Why atomicity is the requirement and not an optimisation
     *
     * A use that spent the Rage charge and then failed to decrement its own uses would leave the
     * player one charge poorer with nothing to show for it, and a use that did it the other way
     * round would leave them a free Rage. Neither is a state any tap can produce, and neither is
     * a state the player could diagnose — the tracker would simply read wrong. `@Transaction`
     * makes the pair all-or-nothing at the storage layer, which is the only layer that can
     * promise it: `LocalOpenCharacter`'s mutex serialises *writers*, it does not make two
     * statements one.
     *
     * ### It is also the undo
     *
     * [LocalOpenCharacter.undoLastWrite] calls this same method with the two **previous** values,
     * so restoring is one transaction for the same reason spending is: putting one column back and
     * not the other is the same broken pair, arrived at from the other direction. There is no
     * second "restore" statement to drift from this one.
     *
     * @param actionCurrent the uses the action row should read afterwards, or `null` for an
     *   unlimited action (nothing to decrement — decision 1 makes uses optional).
     * @param costRowId the row the use spends from, or `null` for a free action.
     * @param costCurrent what that row should read afterwards. Ignored when [costRowId] is null.
     * @param costIsItem whether the cost row is an item, in which case quantity and total move
     *   together ([setRowQuantity]) rather than only the remaining value ([setRowCurrent]). An
     *   item's two fields are one number — see [setRowQuantity] — and writing only `current` would
     *   leave a stored `total` the inventory tab renders from.
     */
    @Transaction
    suspend fun useAction(
        characterId: String,
        actionRowId: String,
        actionCurrent: Int?,
        costRowId: String?,
        costCurrent: Int?,
        costIsItem: Boolean,
        at: Long,
    ) {
        if (actionCurrent != null) setRowCurrent(actionRowId, actionCurrent)
        if (costRowId != null && costCurrent != null) {
            if (costIsItem) setRowQuantity(costRowId, costCurrent) else setRowCurrent(costRowId, costCurrent)
        }
        touch(characterId, at)
    }

    /**
     * @param healToMax 09 decision 7's dated correction note: `true` for a long rest, which
     *   heals `currentHp` to `maxHp` in this same transaction (5e long-rest semantics). `false`
     *   — the default, so every pre-existing caller keeps meaning exactly what it always meant
     *   — for a short rest, which 5e leaves HP untouched.
     */
    @Transaction
    suspend fun rest(characterId: String, rules: List<String>, at: Long, healToMax: Boolean = false) {
        refillRows(characterId, rules)
        if (healToMax) setCurrentHpToMax(characterId)
        touch(characterId, at)
    }

    /**
     * Applies a whole reordering in one transaction, so no intermediate order ever renders —
     * the same guarantee `TrackerPrefDao`'s bulk upsert gives the server path.
     *
     * The list *is* the order: position 0 gets `sortIndex` 0. Passing the indices separately
     * would let a caller hand over a set with gaps or duplicates, and there is no reading of
     * that which is not a guess.
     */
    @Transaction
    suspend fun reorderRows(characterId: String, orderedRowIds: List<String>) {
        orderedRowIds.forEachIndexed { index, rowId ->
            setRowSortIndex(characterId, rowId, index)
        }
    }
}
