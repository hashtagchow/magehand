package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One section's place on the inventory tab: which section, whether the player folded it, and
 * whether they collapsed it.
 *
 * A triple rather than three parallel lists, because the facts are only ever read together and
 * a list of keys beside a set of hidden keys is two things that can disagree — the shape where
 * a section is ordered but not known, or hidden but not ordered, would be representable and
 * meaningless.
 *
 * [key] is opaque here on purpose. `:core:data` has no idea that `wallet` is four coin rows or
 * that `container:` prefixes a `creatureProperties._id`; it stores strings the UI layer minted
 * and hands them back in order. That is what lets docs/design/12-inventory-layout.md decision 4
 * add a future section without touching this file.
 */
data class InventoryLayoutEntry(
    val key: String,
    /** Folded, in decision 3's sense: the section's *heading* goes, never its items. */
    val hidden: Boolean = false,
    /**
     * Collapsed, in docs/design/13-collapsible-sections-local-gear.md decision 3's sense: the
     * section's **header stays and its rows go**.
     *
     * Orthogonal to [hidden] and stored alongside it rather than folded into one tri-state,
     * because the two are genuinely independent (13 decision 4): a hidden section does not
     * render at all and its items appear under Gear, while a collapsed one renders its header
     * with the items still filed under it. All four combinations are meaningful, and a tri-state
     * would have had to invent a precedence between them.
     *
     * **Not written for every section.** The wallet's collapse is deliberately ephemeral — see
     * decision 3's exception and `InventoryLayoutKeys.persistsCollapse` — so a `wallet` entry
     * always reads `false` here regardless of what is on screen.
     */
    val collapsed: Boolean = false,
)

/**
 * How the player has arranged the inventory tab's sections, **per character, across restarts**
 * (docs/design/12-inventory-layout.md decision 5).
 *
 * ### What it is for
 *
 * Decision 1 gives every character the same default order — Wallet, Equipped, Weapons, Armor,
 * containers, Gear — and decision 3 lets the player rearrange it: move a section, or fold one
 * away so its items join Gear. FR-16 (13 decision 3) adds a third fact to the same entries:
 * whether the section is **collapsed**, which is remembered here rather than in a
 * `rememberSaveable` because *"I never look at Armor"* is a durable fact about a character while
 * a quick wallet peek is not. This remembers that arrangement. It stores a *preference over
 * whatever exists*, never a list of what exists: the board is the authority on which sections
 * a character has, and it changes with every sync.
 *
 * ### Why this is the third copy of the same store and not a generalisation of the first two
 *
 * [SelectedRollStore] and [EquippableOverrideStore] have this shape already — a DataStore key
 * per character, two key namespaces, a sign-out prefix reap and a local-delete clear — and the
 * obvious move is to hoist the three into one generic `PerCharacterStore<T>`. It is deliberately
 * not done, and the reason is that the three share a *lifecycle*, not a *contract*: a remembered
 * roll is one nullable string, an override set is an unordered set with add/remove semantics,
 * and this is an ordered list with a codec. A generic store would have to be parameterised over
 * the value type, the empty-is-absent rule and the codec, at which point it is three interfaces
 * wearing one name — and every reader of a call site would have to go and find out which of the
 * three behaviours they were looking at.
 *
 * What the three genuinely share is the two reaping paths, and those are shared the way that
 * actually helps: same file (see `DataModule`), same key shape, same two call sites in
 * `DefaultAccountRepository.signOut` and `LocalCharacterRepository.delete`. That is what has to
 * stay in step, and a reader comparing the three files sees three copies of one argument rather
 * than one abstraction hiding three.
 *
 * ### Why a DataStore string and not a schema change
 *
 * Decision 5 says "NO schema change" and the reasons are [SelectedRollStore]'s, whole: the key
 * has to be *the character* — a local one has no account, and 09 decision 1 forbids the sentinel
 * account a per-account table would need — and this is a preference about how the app draws a
 * sheet, not a fact about the sheet.
 *
 * One string rather than a row per section, because the whole arrangement is read as a unit on
 * every rebuild of the tab and written as a unit by every gesture, and because a single key is
 * what makes both reaping paths a one-liner. See [InventoryLayoutCodec] for the format.
 *
 * ### Sign-out, and deleting an on-device character
 *
 * [deleteForAccount] and [clearForCharacter] exist for the reasons the other two stores state at
 * length: `accounts.id` is a fresh UUID per sign-in, so anything left keyed to a dead account id
 * is unreachable **forever** rather than merely stale; and a local character's key is outside
 * that reap on purpose (09 decision 10), so its one deletion path has to clear it by hand.
 */
interface InventoryLayoutStore {

    /**
     * This character's stored arrangement, or an empty list when they have never customized.
     *
     * Empty rather than `null` for "no preference", matching [EquippableOverrideStore.overrides]:
     * both readings mean *use the default*, and a nullable list would put `?: emptyList()` at
     * every call site to reach the same place.
     *
     * Keys in here that no longer name a section — a container the sheet no longer has — are
     * simply never matched, and are **not** swept (decision 4: "drops out of the stored order
     * harmlessly"). This store does not know what a character has; a container that comes back
     * after a dropped socket should find its place still remembered rather than tidied away
     * while nobody was looking.
     */
    fun layout(characterKey: String): Flow<List<InventoryLayoutEntry>>

    /**
     * Replaces this character's whole arrangement.
     *
     * Whole rather than incremental because the value *is* an order, and an order cannot be
     * edited one element at a time without the caller and the store agreeing on what the other
     * elements are — which is the bug `TrackerOverridePlan.reorder` avoids by re-indexing a
     * whole section rather than nudging one `sortIndex` past its neighbour. The plan that builds
     * the list lives in the UI layer, where the section keys mean something.
     *
     * An empty list **removes the key** rather than storing an empty string, matching
     * [EquippableOverrideStore.setOverridden]'s rule: a key holding nothing reads identically to
     * no key, and leaving one behind would make a character who customized and reset permanently
     * occupy a slot in a file nothing else prunes.
     */
    suspend fun setLayout(characterKey: String, layout: List<InventoryLayoutEntry>)

    /**
     * How this character's rows are ordered inside each section (FR-35 decision 4), or
     * [InventorySort.DEFAULT] when they have never chosen.
     *
     * ### Why this is on the layout store and not a fourth per-character store
     *
     * The class KDoc above refuses to generalise three stores into one because they share a
     * *lifecycle* and not a *contract*. This is the other case: it shares the contract too. It is
     * a preference about how the inventory tab draws **this** character, keyed by the same
     * character key, reaped by the same two paths, written by gestures in the same sheet, and
     * cleared by the same Reset. A fourth store would have been a fourth copy of the two reaping
     * paths — two more call sites in `DefaultAccountRepository.signOut` and
     * `LocalCharacterRepository.delete` that have to stay in step with the other three — bought
     * for nothing.
     *
     * ### Two keys, not one, and both beside the order rather than inside it
     *
     * The criterion and the direction are stored as **two separate preference keys**, suffixed
     * onto [characterKey] (see `DataStoreInventoryLayoutStore`). Two rather than one packed
     * string because each then degrades on its own: a criterion this build has never heard of
     * falls back to Default *without* taking a perfectly readable direction with it. And beside
     * the order rather than inside [InventoryLayoutCodec]'s string because the order is a list of
     * opaque section keys — the codec's delimiters are safe precisely because every token starts
     * with a letter or digit, and a `sort=weight` token in the same string would either break that
     * property or need escaping.
     */
    fun sort(characterKey: String): Flow<InventorySort>

    /**
     * Records how this character's rows are ordered (FR-35 decision 4).
     *
     * [InventorySort.DEFAULT] **removes both keys** rather than writing them, matching
     * [setLayout]'s empty-list rule and for the same reason: a stored default reads identically
     * to no preference at all, so writing one would leave a slot in a file nothing prunes,
     * occupied forever by a character who sorted once and put it back.
     *
     * Writing this **never touches the section order**, and vice versa. That is a property of
     * them being separate preference keys in one DataStore rather than care taken here — but it
     * is the property `InventoryLayoutStoreTest` pins in both directions, because the failure
     * mode (a sort gesture silently resetting a player's arrangement) is total and silent.
     */
    suspend fun setSort(characterKey: String, sort: InventorySort)

    /**
     * Drops this character's arrangement — the sheet's **Reset**, and the local-delete reap.
     *
     * Deliberately one method for both, because they are the same write and the same intent:
     * *forget this character's arrangement*, after which decision 1's default is what renders.
     * `setLayout(key, emptyList())` reaches the same state; this is named so the two call sites
     * read as what they are.
     *
     * 13 decision 3's *"Reset clears collapse too"* falls out of that rather than needing a
     * branch: collapse is a flag on the same entries, in the same key, so removing the key
     * removes it. A separate collapse store would have needed a second call here, and a Reset
     * that left every section shut is exactly the failure a second store would have shipped.
     *
     * FR-35's *"Reset restores Default + ascending"* does **not** fall out for free, because the
     * sort lives in two keys of its own — so this removes all three. That is deliberate and is
     * what makes Reset mean one thing: after it, the tab draws 12 decision 1's default order with
     * the source's own row order inside it, which is what every character starts as.
     */
    suspend fun clearForCharacter(characterKey: String)

    /** Drops every arrangement belonging to [accountId]. See the class KDoc. */
    suspend fun deleteForAccount(accountId: String)

    companion object {
        /**
         * The key for a DiceCloud character.
         *
         * Account-scoped for [SelectedRollStore.serverKey]'s reasons exactly: the same creature
         * is reachable from two accounts and is two rows everywhere else in this app, and the
         * scoping is what makes [deleteForAccount] a prefix match rather than a scan.
         */
        fun serverKey(accountId: String, creatureId: String): String =
            "$SERVER_PREFIX$accountId:$creatureId"

        /**
         * The key for an on-device character.
         *
         * A separate prefix rather than the bare id, and for the guarantee rather than for
         * disambiguation: it is what makes [deleteForAccount]'s prefix match *provably* unable
         * to reach a local character's layout. Sign-out must not touch local data (09 decision
         * 10), and a namespace states that more strongly than a comment.
         */
        fun localKey(characterId: String): String = "$LOCAL_PREFIX$characterId"

        internal const val KEY_PREFIX = "inventory_layout:"
        internal const val SERVER_PREFIX = "${KEY_PREFIX}server:"
        internal const val LOCAL_PREFIX = "${KEY_PREFIX}local:"

        /**
         * FR-35's two keys, as **suffixes on the character key** rather than a namespace of
         * their own.
         *
         * That shape is doing one specific job: [deleteForAccount] finds an account's characters
         * by the shape of the key (`inventory_layout:server:<acct>:`), so anything suffixed onto
         * a character key is inside that prefix and is reaped by the *existing* scan, with no
         * second pass and no second prefix that could be forgotten. A separate
         * `inventory_sort:server:…` namespace would have needed the reap to know about it, and
         * the failure mode of forgetting is the one this store's KDoc calls unreachable
         * **forever** — a fresh account id per sign-in means an orphaned key is never revisited.
         *
         * Nothing can collide with a character key: a server key ends in a `creatureProperties`
         * creature id and a local key in a UUID, and Meteor's id alphabet is alphanumeric — so no
         * character key ever ends in `:sort` or `:sort-direction`, and no suffixed key is ever
         * read as somebody's arrangement.
         */
        internal const val SORT_SUFFIX = ":sort"
        internal const val SORT_DIRECTION_SUFFIX = ":sort-direction"
    }
}

/**
 * The stored format (12 decision 5, extended by 13 decision 3): the ordered keys, joined by
 * commas, each optionally prefixed by `!` for folded and `^` for collapsed —
 * `wallet,equipped,!weapons,^armor,^!container:abc,gear`.
 *
 * ### Why a hand-rolled string and not JSON
 *
 * Because the value is a list of short opaque tokens with two booleans each, and JSON would cost
 * a serializer dependency in `:core:data` to express `["wallet",{"key":"weapons",...}]` — more
 * apparatus than the thing being stored. The format is also *readable in a preferences dump*,
 * which matters for a preference the only debugging tool for is `adb shell run-as … strings`.
 *
 * ### Why 13 decision 3 could extend this and still say "NO schema change"
 *
 * **Not** because 1.5.0 could read a 1.6.0 file. It could not, and the claim that it could was
 * wrong: 1.5.0's decoder was `startsWith(HIDDEN_MARK)` / `removePrefix(HIDDEN_MARK)`, so it
 * recognised exactly one mark. A 1.5.0 install meeting `^armor` reads the key **`^armor`**,
 * matches no section, and drops that section out of the stored order — the arrangement survives,
 * but Armor loses its position. The negative prefix scan below is a *1.6.0* property; it buys
 * forward tolerance of some later release's mark, not backward tolerance of this one's.
 *
 * The reason "no schema change" holds is that **no install ever reads a file written by a newer
 * build**, and there are two independent mechanisms for that:
 *
 *  - `android:allowBackup="false"` (see the manifest), so Android's backup/restore transport
 *    never carries this preferences file onto a device running a different version of the app.
 *  - Android has no in-place downgrade: installing an older `versionCode` over a newer one is
 *    refused, so getting back to 1.5.0 means uninstalling first — which deletes the app's data
 *    directory and the DataStore file with it. 1.5.0 comes back to an empty store and the
 *    default arrangement, which is a first-run, not a corruption.
 *
 * So the extension is safe because the old reader is never handed the new string, not because the
 * old reader coped. Downgrade tolerance is **not** claimed here; what *is* claimed is the forward
 * direction, and that is what the sections below are about.
 *
 * ### Marks are order-independent and unknown ones are ignored
 *
 * `^!armor` and `!^armor` both decode to *armor, hidden and collapsed*, because [decode] reads
 * the whole run of prefix characters as a set rather than as a sequence. There is no reading in
 * which the order of two independent booleans matters, so making one canonical would only create
 * a second string for one state and a bug for whichever writer produced the other. [encode]
 * still emits one canonical order (`^` then `!`), so a round trip is byte-stable.
 *
 * An unrecognised mark — `~armor` from some future release — is **dropped, and the key kept**.
 * That is the same forward-compatibility posture `InventoryLayoutPlan.resolve` takes towards an
 * unknown *key*: the honest degradation of "a newer build knew one more thing about this
 * section" is to lose that one thing, not to lose the section or the whole arrangement with it.
 *
 * ### What makes the delimiters safe
 *
 * Section keys are minted by the UI layer and are one of five constants or `container:` followed
 * by a `creatureProperties._id`, which Meteor generates from an alphanumeric alphabet — so every
 * key **starts with a letter or a digit**, and `,`, `!` and `^` occur in none of them. That is
 * what lets the prefix run be defined negatively, as "everything before the first alphanumeric",
 * which is the only definition that can absorb a mark this build has never heard of. [decode]
 * does not *rely* on any of it being true forever: it drops blank segments and de-duplicates
 * keys, so a malformed string that somehow reached the file degrades to a partial order rather
 * than to a crash or a duplicated section.
 */
internal object InventoryLayoutCodec {
    const val SEPARATOR = ","
    const val HIDDEN_MARK = '!'
    const val COLLAPSED_MARK = '^'

    fun encode(layout: List<InventoryLayoutEntry>): String =
        layout.joinToString(SEPARATOR) { entry ->
            buildString {
                // Canonical order, so encode(decode(s)) is stable. Nothing reads it back in
                // this order — see the class KDoc — it exists so two writers agree.
                if (entry.collapsed) append(COLLAPSED_MARK)
                if (entry.hidden) append(HIDDEN_MARK)
                append(entry.key)
            }
        }

    fun decode(stored: String?): List<InventoryLayoutEntry> =
        stored.orEmpty()
            .split(SEPARATOR)
            .mapNotNull { token ->
                val marks = token.takeWhile { !it.isLetterOrDigit() }
                val key = token.drop(marks.length)
                key.takeIf { it.isNotBlank() }?.let {
                    InventoryLayoutEntry(
                        key = it,
                        hidden = HIDDEN_MARK in marks,
                        collapsed = COLLAPSED_MARK in marks,
                    )
                }
            }
            // A key twice over is not a representable arrangement — a section cannot be in two
            // places — so the first mention wins rather than the last section silently moving.
            .distinctBy { it.key }
}

/** Preferences-DataStore backed [InventoryLayoutStore]. */
class DataStoreInventoryLayoutStore(
    private val dataStore: DataStore<Preferences>,
) : InventoryLayoutStore {

    override fun layout(characterKey: String): Flow<List<InventoryLayoutEntry>> =
        dataStore.data.map { InventoryLayoutCodec.decode(it[key(characterKey)]) }

    override suspend fun setLayout(characterKey: String, layout: List<InventoryLayoutEntry>) {
        val preferenceKey = key(characterKey)
        dataStore.edit { prefs ->
            // Absent, not empty — see the interface. An empty string would decode to an empty
            // list and read identically, which is exactly why it must not be written.
            if (layout.isEmpty()) {
                prefs.remove(preferenceKey)
            } else {
                prefs[preferenceKey] = InventoryLayoutCodec.encode(layout)
            }
        }
    }

    /**
     * The two sort keys, read together into one value.
     *
     * `map` over one `dataStore.data` emission rather than two flows combined, so the criterion
     * and the direction are always read from the **same** snapshot of the file. Two flows would
     * have made a single `setSort` emit twice — once with the new criterion beside the old
     * direction — and the tab would have re-sorted through a state nobody chose.
     */
    override fun sort(characterKey: String): Flow<InventorySort> = dataStore.data.map { prefs ->
        InventorySort(
            criterion = InventorySortCriterion.fromKey(prefs[sortKey(characterKey)]),
            direction = InventorySortDirection.fromKey(prefs[sortDirectionKey(characterKey)]),
        )
    }

    override suspend fun setSort(characterKey: String, sort: InventorySort) {
        dataStore.edit { prefs ->
            // Absent, not "default" — see the interface. One `edit` for both keys so the pair is
            // never observed half-written, which is the same guarantee `sort`'s single `map`
            // makes on the way out.
            if (sort.isDefault) {
                prefs.remove(sortKey(characterKey))
                prefs.remove(sortDirectionKey(characterKey))
            } else {
                prefs[sortKey(characterKey)] = sort.criterion.key
                prefs[sortDirectionKey(characterKey)] = sort.direction.key
            }
        }
    }

    /**
     * Reset, and the local-delete reap: all **three** of this character's keys.
     *
     * Not two edits, and not a call to [setSort] — one atomic `edit` removing everything keyed to
     * this character, so a Reset interrupted by a process death cannot leave a sort behind on an
     * order that is gone.
     */
    override suspend fun clearForCharacter(characterKey: String) {
        dataStore.edit {
            it.remove(key(characterKey))
            it.remove(sortKey(characterKey))
            it.remove(sortDirectionKey(characterKey))
        }
    }

    /**
     * Removes by **prefix**, in one edit — `DataStoreSelectedRollStore.deleteForAccount`'s
     * mechanism, for its reasons.
     *
     * There is no index to consult, DataStore being a flat map, so the account's characters are
     * found by the shape of the key rather than by asking a table which creatures the account
     * had. That is the more robust direction: a character the cache had already forgotten is
     * still reaped, and reaping is the whole point. The keys are collected before the removals
     * so nothing mutates the map being walked, and the whole thing is one atomic `edit`.
     */
    override suspend fun deleteForAccount(accountId: String) {
        val prefix = InventoryLayoutStore.serverKey(accountId, "")
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun key(characterKey: String) = stringPreferencesKey(characterKey)

    private fun sortKey(characterKey: String) =
        stringPreferencesKey("$characterKey${InventoryLayoutStore.SORT_SUFFIX}")

    private fun sortDirectionKey(characterKey: String) =
        stringPreferencesKey("$characterKey${InventoryLayoutStore.SORT_DIRECTION_SUFFIX}")
}
