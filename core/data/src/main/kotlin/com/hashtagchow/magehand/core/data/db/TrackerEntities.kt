package com.hashtagchow.magehand.core.data.db

import androidx.room.Entity
import androidx.room.Index
import com.hashtagchow.magehand.core.model.CharacterSummary
import com.hashtagchow.magehand.core.model.TrackerOverride

/**
 * The four tables schema **version 2** adds, verbatim from docs/design/03-data-model.md
 * §Room schema:
 *
 * ```
 * characters(accountId, creatureId, name, picture, owner, isOwned, lastOpenedAt,
 *            PK(accountId, creatureId))
 * snapshots(accountId, creatureId, json BLOB, fetchedAt, PK(accountId, creatureId))
 * tracker_prefs(accountId, creatureId, propertyId, pinned, hidden, sortIndex,
 *               PK(accountId, creatureId, propertyId))
 * theme_prefs(accountId, creatureId, accentColor, PK(accountId, creatureId))
 * ```
 *
 * No foreign key to `accounts`: 03 specifies primary keys only, and a cascading FK would
 * make sign-out order-dependent. Deleting an account's rows is explicit
 * (`deleteForAccount` on every DAO), which is also what makes it testable.
 */

/** Cached `characterList`, refreshed on every successful subscription (06 §Snapshot lifecycle 4). */
@Entity(tableName = "characters", primaryKeys = ["accountId", "creatureId"])
data class CharacterEntity(
    val accountId: String,
    val creatureId: String,
    val name: String,
    val picture: String?,
    val owner: String,
    val isOwned: Boolean,
    val lastOpenedAt: Long,
)

/**
 * The cached row as a selector entry.
 *
 * `alignment` and `gender` are `null`: 03's `characters` schema has no columns for them,
 * and they are creature-level detail the live `characterList` publication supplies
 * (docs/verification/WP5.md §2). A cached row is the offline fallback — name, portrait and
 * ownership — not a substitute for the live list.
 *
 * `lastOpenedAt` stays on the entity: it is the selector's *ordering* key, which
 * [CharacterDao.observeForAccount] applies in SQL, so the domain type does not need it.
 */
fun CharacterEntity.toDomain(): CharacterSummary = CharacterSummary(
    creatureId = creatureId,
    name = name,
    alignment = null,
    gender = null,
    picture = picture,
    owner = owner,
    isOwnedByMe = isOwned,
)

fun CharacterSummary.toEntity(accountId: String, lastOpenedAt: Long = 0L): CharacterEntity =
    CharacterEntity(
        accountId = accountId,
        creatureId = creatureId,
        name = name,
        picture = picture,
        owner = owner,
        isOwned = isOwnedByMe,
        lastOpenedAt = lastOpenedAt,
    )

/**
 * One **gzipped** `GET /api/creature/:id` body per character, newest wins
 * (03 §Room schema; 06 §Data budget: ~1 MB raw → ~100 KB stored).
 *
 * The `fetchedAt` index is what makes the ≤10-per-account LRU eviction a single
 * indexed statement rather than a table scan.
 */
@Entity(
    tableName = "snapshots",
    primaryKeys = ["accountId", "creatureId"],
    indices = [Index(value = ["accountId", "fetchedAt"])],
)
class SnapshotEntity(
    val accountId: String,
    val creatureId: String,
    /** gzip of the raw JSON body, UTF-8. */
    val json: ByteArray,
    val fetchedAt: Long,
) {
    // Not a data class: a generated `equals` on a ByteArray compares identity, which is
    // a well-known trap. Content equality is what tests and caches actually mean.
    override fun equals(other: Any?): Boolean = other is SnapshotEntity &&
        accountId == other.accountId && creatureId == other.creatureId &&
        fetchedAt == other.fetchedAt && json.contentEquals(other.json)

    override fun hashCode(): Int {
        var result = accountId.hashCode()
        result = 31 * result + creatureId.hashCode()
        result = 31 * result + json.contentHashCode()
        result = 31 * result + fetchedAt.hashCode()
        return result
    }

    override fun toString(): String =
        "SnapshotEntity(accountId=$accountId, creatureId=$creatureId, " +
            "gzippedBytes=${json.size}, fetchedAt=$fetchedAt)"
}

/** The user-override layer behind [TrackerOverride] (03 §6). Survives re-syncs by design. */
@Entity(tableName = "tracker_prefs", primaryKeys = ["accountId", "creatureId", "propertyId"])
data class TrackerPrefEntity(
    val accountId: String,
    val creatureId: String,
    val propertyId: String,
    val pinned: Boolean,
    val hidden: Boolean,
    /** `null` keeps the natural (server) order. */
    val sortIndex: Int?,
)

fun TrackerPrefEntity.toDomain(): TrackerOverride = TrackerOverride(
    propertyId = propertyId,
    pinned = pinned,
    hidden = hidden,
    sortIndex = sortIndex,
)

fun TrackerOverride.toEntity(accountId: String, creatureId: String): TrackerPrefEntity =
    TrackerPrefEntity(
        accountId = accountId,
        creatureId = creatureId,
        propertyId = propertyId,
        pinned = pinned,
        hidden = hidden,
        sortIndex = sortIndex,
    )

/**
 * Per-character accent colour (04-screens-ux.md).
 *
 * Stored as text (`"#RRGGBB"`) rather than a packed int: it is self-describing in a
 * database dump, and `:core:data` has no business holding a Compose `Color`
 * representation. The UI parses it.
 */
@Entity(tableName = "theme_prefs", primaryKeys = ["accountId", "creatureId"])
data class ThemePrefEntity(
    val accountId: String,
    val creatureId: String,
    val accentColor: String?,
)
