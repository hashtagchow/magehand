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

    @Transaction
    suspend fun rest(characterId: String, rules: List<String>, at: Long) {
        refillRows(characterId, rules)
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
