package com.hashtagchow.magehand.core.data.local

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import com.hashtagchow.magehand.core.data.db.LocalCharacterDao
import com.hashtagchow.magehand.core.data.db.LocalCharacterEntity
import com.hashtagchow.magehand.core.data.db.LocalTrackerRowEntity
import com.hashtagchow.magehand.core.data.db.toDomain
import com.hashtagchow.magehand.core.data.settings.EquippableOverrideStore
import com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore
import com.hashtagchow.magehand.core.data.settings.SelectedRollStore
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import java.util.UUID

/**
 * On-device characters: the list, the form, and the one transactional save path
 * (docs/design/09-local-characters.md decisions 3 and 4).
 *
 * Plain constructor, like every other WP4 type, so it can be built by hand in a test with an
 * in-memory database and a fake clock. [now] and [newId] are injected for exactly that
 * reason: a save asserted against a wall clock or a random UUID is a test that asserts
 * nothing.
 *
 * [selectedRollStore], [equippableOverrideStore] and [inventoryLayoutStore] are here for
 * [delete] alone, and deliberately have **no default**: a character's FR-7 roll selection, its
 * FR-10 equippability overrides and its FR-14 inventory layout all live outside the database, so
 * a construction site that could quietly omit any of them would be a construction site whose
 * deletes leak keys. Same argument `DataModule` makes for handing the stores to
 * `DefaultAccountRepository.signOut`.
 */
class LocalCharacterRepository(
    private val dao: LocalCharacterDao,
    private val selectedRollStore: SelectedRollStore,
    private val equippableOverrideStore: EquippableOverrideStore,
    private val inventoryLayoutStore: InventoryLayoutStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    // --- reads --------------------------------------------------------------

    /** The character list's "On this device" section (09 decision 3). */
    fun observeAll(): Flow<List<LocalCharacter>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    fun observe(id: String): Flow<LocalCharacter?> = dao.observe(id).map { it?.toDomain() }

    fun observeRows(characterId: String): Flow<List<LocalTrackerRow>> =
        dao.observeRows(characterId).map { rows -> rows.mapNotNull { it.toDomain() } }

    suspend fun find(id: String): LocalCharacter? = dao.find(id)?.toDomain()

    suspend fun rows(characterId: String): List<LocalTrackerRow> =
        dao.getRows(characterId).mapNotNull { it.toDomain() }

    suspend fun count(): Int = dao.count()

    /**
     * The character as the form should re-open it, or `null` if it is gone.
     *
     * This is what makes "the form is the editor" true rather than aspirational: the same
     * type the create flow fills in is the type the edit flow starts from, so there is one
     * validator and one save path and no second, subtly different "update" code path to keep
     * in step.
     *
     * `currentHp` is deliberately not on [LocalCharacterForm]: it is play state the tracker
     * owns, and putting it on the form would let an edit silently heal the character.
     */
    suspend fun formFor(id: String): LocalCharacterForm? {
        val character = dao.find(id)?.toDomain() ?: return null
        return LocalCharacterForm(
            id = character.id,
            name = character.name,
            level = character.level,
            abilities = character.abilities,
            maxHp = character.maxHp,
            armorClass = character.armorClass,
            rows = rows(id).map { row ->
                LocalRowForm(
                    id = row.id,
                    kind = row.kind,
                    label = row.label,
                    total = row.total,
                    reset = row.reset,
                )
            },
        )
    }

    // --- writes -------------------------------------------------------------

    /**
     * Validates and saves, character and rows in one transaction (09 decision 4).
     *
     * Nothing is written when the form is invalid — [LocalSaveResult.Invalid] carries every
     * reason, and the database is untouched. Validation happens *here* and not only in wave
     * B's view model so that the rule has one home: a second caller (a future import, a test
     * helper) cannot skip it by not knowing about it.
     *
     * ### What survives an edit
     *
     * - **`createdAt`** — the character's identity in the list's ordering; an edit is not a
     *   re-creation.
     * - **A row's `current`** — editing a resource's label must not refill it mid-session.
     *   The row keeps what it had, clamped into the (possibly lowered) new ceiling.
     * - **`currentHp`** — likewise clamped into the new `maxHp`. Lowering max HP below the
     *   current value and leaving the character above its own maximum would put the HP row in
     *   a state the tracker's own clamps say is impossible.
     *
     * Rows the form no longer carries are deleted by [LocalCharacterDao.save]; the tracker
     * rows and the character can never be half-saved.
     *
     * ### An edit of a deleted character is [LocalSaveResult.Missing], not a create
     *
     * `form.id == null` means *create*; anything else means *edit that character*. When the
     * row behind a non-null id is gone, the honest answer is that there is nothing to edit —
     * the same answer [formFor] gives by returning `null`, arriving later. Upserting anyway
     * resurrected it: same id, but `createdAt` reset to now, `currentHp` back at full, and
     * only the rows the form happened to be holding — a character the player deleted
     * reappearing at the top of their list, subtly not the one they had. Deletion is the only
     * operation in 09 the player cannot undo, so it is the one this path must not undo for
     * them.
     */
    suspend fun save(form: LocalCharacterForm): LocalSaveResult {
        val errors = form.validate()
        if (errors.isNotEmpty()) return LocalSaveResult.Invalid(errors)

        val at = now()
        val existing = form.id?.let { dao.find(it) }
        if (form.id != null && existing == null) return LocalSaveResult.Missing
        // Past that guard, a non-null `form.id` always has its row, so this is exactly
        // "the character being edited, or a new id for a create".
        val characterId = existing?.id ?: newId()

        val entity = LocalCharacterEntity(
            id = characterId,
            name = form.name.trim(),
            level = form.level,
            strength = form.abilities.strength,
            dexterity = form.abilities.dexterity,
            constitution = form.abilities.constitution,
            intelligence = form.abilities.intelligence,
            wisdom = form.abilities.wisdom,
            charisma = form.abilities.charisma,
            maxHp = form.maxHp,
            // A new character arrives at full health; an edited one keeps what it had.
            currentHp = existing?.currentHp?.coerceIn(0, form.maxHp) ?: form.maxHp,
            armorClass = form.armorClass,
            createdAt = existing?.createdAt ?: at,
            updatedAt = at,
        )

        val previousRows = existing?.let { dao.getRows(characterId) }.orEmpty().associateBy { it.id }

        val rowEntities = form.rows.mapIndexed { index, row ->
            val rowId = row.id ?: newId()
            val previous = previousRows[rowId]
            val current = when {
                // An item's quantity *is* what the form typed — there is no separate
                // "remaining" for it, so an edit sets it outright.
                row.kind == LocalRowKind.ITEM -> row.total
                previous == null -> row.total
                else -> previous.current.coerceIn(0, row.total)
            }
            LocalTrackerRowEntity(
                id = rowId,
                characterId = characterId,
                kind = row.kind.storedValue,
                label = row.label.trim(),
                total = row.total,
                current = current,
                resetRule = row.reset?.wireValue ?: LocalTrackerRowEntity.RESET_NONE,
                // The form's order is the tracker's order — the one ordering mechanism
                // (09 decision 8). Assigned from the list position so it is always dense.
                sortIndex = index,
            )
        }

        dao.save(entity, rowEntities)
        return LocalSaveResult.Saved(characterId)
    }

    /**
     * Deletes a local character and, by the table's `ON DELETE CASCADE`, its rows.
     *
     * Note what this is *not*: sign-out. 09 decision 10 — signing out of a DiceCloud account
     * must not delete local characters, and it cannot, because nothing here is account-keyed.
     * This is the player deleting a character on purpose.
     *
     * ### The FR-7 selection goes too, and this is the only place that can take it
     *
     * A character's remembered roll is not a row — it is a DataStore key,
     * `SelectedRollStore.localKey(id)`, so `ON DELETE CASCADE` cannot reach it and neither can
     * `SelectedRollStore.deleteForAccount`: the local namespace is outside the sign-out reap on
     * purpose (09 decision 10). Local ids are UUIDs and never recur, so a key left behind is
     * unreachable **forever** rather than merely stale — the same argument sign-out makes about
     * `accounts.id`, arriving at the same conclusion for the one deletion path local characters
     * have.
     *
     * The selection is cleared *before* the row, matching sign-out's ordering: the satellite
     * state goes first, the record that names it last, so a failure between the two steps
     * leaves a character whose dropdown has forgotten its pick — a thing the player can redo —
     * rather than the orphaned key this path exists to prevent.
     */
    suspend fun delete(id: String) {
        selectedRollStore.setSelectedRollId(SelectedRollStore.localKey(id), null)
        // 11 decision 2's overrides, reaped here for word-for-word the same reason: another
        // DataStore key in the local namespace, outside the sign-out sweep on purpose, keyed
        // by a UUID that will never recur. Before the row, like everything else above it.
        equippableOverrideStore.clearForCharacter(EquippableOverrideStore.localKey(id))
        // 12 decision 5's inventory layout, and the third repetition of the same three facts:
        // a DataStore key in the local namespace, outside the sign-out sweep on purpose, keyed
        // by a UUID that will never recur. Before the row, like everything else above it.
        inventoryLayoutStore.clearForCharacter(InventoryLayoutStore.localKey(id))
        dao.delete(id)
    }
}
