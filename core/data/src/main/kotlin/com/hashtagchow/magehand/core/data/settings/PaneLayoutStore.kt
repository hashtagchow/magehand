package com.hashtagchow.magehand.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One of the character screen's surfaces, as a thing that can be *chosen* rather than navigated
 * to (docs/design/14-large-screen-arc.md decision 6).
 *
 * ### Why this closed vocabulary lives in `:core:data` and not in the UI
 *
 * [UiScale]'s reason, whole: the store below persists these, so the store's module has to name
 * them. The UI layer owns the other half — which surface renders what, and which of them a given
 * character even has — and maps its own tab enums onto these constants.
 *
 * ### Declaration order is the DEFAULT display order (FR-27)
 *
 * Decision 6 used to read *"Display order is fixed (Tracker, Inventory, Sheet) regardless of
 * selection order — panes are places, not history"*, and it was enforced by storing a `Set` so
 * that no order could exist to be got wrong. FR-27 decision 1 keeps the *"places, not history"*
 * half and drops the *"fixed"* half: the order is now a per-character preference, so the stored
 * value is an ordered [PaneLayoutEntry] list and this enum's ordinal is the **default** that a
 * character who has never arranged anything gets.
 *
 * That is a smaller change than it sounds, and the reason is the distinction decision 6 was
 * really protecting: display order still must not be *selection* order. A player who ticks the
 * Sheet before the Tracker has not thereby asked for the Sheet on the left. What FR-27 adds is a
 * deliberate gesture — the arrows on the order sheet — and nothing else writes a position. See
 * `PaneLayoutCodec` for how the two facts share one string, and `resolvePaneLayout` for how a
 * stored order and this default one are reconciled.
 *
 * The order matches `CharacterHomeTab`'s for the same reason that enum gives for its own order
 * — Tracker and Inventory are the native surfaces, the Sheet is the WebView fallback behind
 * them — and `PaneSelectionTest` pins that the two agree, because two enums that must stay in
 * step and only agree by coincidence do not stay in step.
 *
 * @property key the stored token. Stable: it is on disk on user devices.
 */
enum class PaneSurface(val key: String) {
    TRACKER("tracker"),
    INVENTORY("inventory"),

    /**
     * FR-26's Actions surface (docs/design/16-actions-and-feed.md decision 1).
     *
     * **Inserted here, not appended**, and the position is the decision: decision 1 fixes the
     * display order at *"Tracker → Inventory → Actions → Sheet everywhere the order exists"*, and
     * this enum's ordinal IS that order (see the class KDoc). Appending would have put the
     * Actions column to the right of the Sheet's WebView with no other file changing and no test
     * failing on the order itself.
     *
     * Inserting is safe for data already on disk because the codec is keyed on [key], not on the
     * ordinal — `tracker,sheet` written by 1.8.0 decodes to the same two surfaces here. The only
     * thing the ordinal decides is the order they are drawn in, which is exactly what changed on
     * purpose.
     */
    ACTIONS("actions"),
    SHEET("sheet"),
    ;

    companion object {
        /** The token back to a surface, or `null` for one this build has never heard of. */
        fun fromKey(key: String): PaneSurface? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One surface's place in a character's arrangement: where it sits, and whether it is open as a
 * pane (FR-27 decisions 1 and 2).
 *
 * ### Why one entry carrying two facts, and not an ordered list beside a set
 *
 * [InventoryLayoutEntry]'s argument, whole: the two facts are only ever read together, and a
 * list of keys beside a set of selected keys is two things that can disagree — a surface that is
 * ordered but not known, or selected but not ordered, would be representable and meaningless.
 *
 * ### Why [selected] is a flag and not membership of the list
 *
 * Before FR-27 the stored value *was* the selected set, and membership was the whole of it.
 * Making the list ordered breaks that, because the two facts have different domains: the pane
 * picker chooses among the surfaces (usually one or two of them), while the order sheet arranges
 * **all** of them — a phone player reordering their tab row has no idea panes exist and must not
 * be silently opting every surface into a tablet layout by touching an arrow. An order that only
 * covered the selected surfaces could not express the gesture at all: with Tracker alone
 * selected, "put Sheet before Actions" has nowhere to be written down.
 *
 * So the list carries every surface the player has a position for, and [selected] says which of
 * them the picker has ticked. `PaneLayoutCodec` writes the flag as a one-character mark, which
 * is what keeps every string 1.9.0 ever wrote reading correctly — see its KDoc.
 *
 * @property selected whether this surface is open as a **pane** (14 decision 6). Irrelevant on a
 *   phone, where the tab row draws every available surface whatever this says; the flag and the
 *   position are independent for exactly that reason.
 */
data class PaneLayoutEntry(
    val surface: PaneSurface,
    val selected: Boolean = true,
)

/**
 * Which panes the player has open on a character's home screen, **per character, across
 * restarts** (docs/design/14-large-screen-arc.md decision 8).
 *
 * ### What it is for
 *
 * On an EXPANDED-width window the tab row becomes a multi-select picker and the chosen surfaces
 * render side by side (decisions 5–7). *Which* surfaces those are is a durable fact about how
 * somebody plays a particular character — "I keep the tracker and the sheet up for my wizard"
 * — so it outlives the screen, the process and the install session, exactly as
 * [InventoryLayoutStore]'s arrangement does.
 *
 * ### Why this is the fourth copy of the same store and not a generalisation of the first three
 *
 * [InventoryLayoutStore]'s KDoc makes the argument at length and it has not changed: the four
 * stores share a *lifecycle*, not a *contract*. A remembered roll is one nullable string, an
 * override set is an unordered set of opaque ids, an inventory layout is an ordered list of
 * **opaque** keys with two flags each, and this is an ordered list over a **closed enum** —
 * which is the difference that matters here, because it is what lets [PaneLayoutCodec] drop a
 * token it does not recognise instead of keeping it. FR-27 made this one's shape closer to the
 * inventory store's without making it the same: the element type still decides the unknown-token
 * policy, and that policy is the opposite one. Generalising would mean parameterising over the
 * value type, the empty-is-absent rule, the codec *and* the unknown-token policy, at which point
 * it is four interfaces wearing one name.
 *
 * What the four genuinely share is the two reaping paths, and those are shared the way that
 * helps: same DataStore file (see `DataModule`), same key shape, same two call sites in
 * `DefaultAccountRepository.signOut` and `LocalCharacterRepository.delete`.
 *
 * ### Default, and reset
 *
 * Decision 8: *"Default: Tracker only (today's behavior)"* and *"reset-to-default = delete"*,
 * which FR-27 decision 3 extends to *"restores default order AND default set"*. All of it falls
 * out of [InventoryLayoutStore]'s empty-is-absent rule rather than needing a branch: an absent
 * key decodes to the empty list, and the UI layer resolves an empty list to the default order
 * with the first available surface open. So the default is never written down anywhere — which
 * is the point, because a stored copy of today's default would freeze it into every character
 * that had ever been touched, and a later release that changed the default would change it for
 * nobody.
 *
 * Note that a *user* cannot reach the empty list by arranging: the picker enforces a minimum of
 * one pane (decision 6) and a reorder never removes an entry. [clearForCharacter] is therefore
 * the reset/reap path, reached by the order sheet's **Reset**, and the empty case is exercised
 * by `PaneLayoutStoreTest` rather than by a picker gesture.
 */
interface PaneLayoutStore {

    /**
     * This character's arrangement, or the empty list when they have never arranged one — which
     * reads as *use the default*, matching [InventoryLayoutStore.layout] and
     * [EquippableOverrideStore.overrides].
     *
     * Ordered, and the order is the player's (FR-27 decision 1). It is **not** resolved against
     * what this character actually has: a surface stored for a character who no longer has one
     * stays in this list, exactly as an inventory container the sheet has dropped stays in
     * [InventoryLayoutStore.layout], and for the same reason — it must find its place still
     * remembered if it comes back. `resolvePaneLayout` does the filtering, in the UI layer, which
     * is the only layer that knows what a character has.
     */
    fun panes(characterKey: String): Flow<List<PaneLayoutEntry>>

    /**
     * Replaces this character's whole arrangement.
     *
     * Whole rather than incremental, for [InventoryLayoutStore.setLayout]'s reason: the value
     * *is* an order, and an order cannot be edited one element at a time without the caller and
     * the store agreeing on what the other elements are. The plan that builds the list lives in
     * the UI layer — see `PaneSelection.kt` — where the surfaces a character has mean something.
     *
     * An empty list **removes the key** rather than storing an empty string, again for
     * [InventoryLayoutStore.setLayout]'s reason: a key holding nothing reads identically to no
     * key, and leaving one behind would make a character who customized and reset permanently
     * occupy a slot in a file nothing else prunes.
     *
     * This store does **not** enforce decision 6's minimum of one pane. That rule belongs to the
     * picker, which is where the user gesture that could violate it happens; enforcing it here
     * as well would mean the store silently rewriting a caller's value, and a store that returns
     * something other than what it was given is the kind of thing that gets debugged twice.
     */
    suspend fun setPanes(characterKey: String, panes: List<PaneLayoutEntry>)

    /**
     * Drops this character's arrangement, so the default order with Tracker open draws again.
     *
     * One method for the order sheet's Reset, the pane reset and the local-delete reap, exactly
     * as [InventoryLayoutStore.clearForCharacter] is, and for the same reason: they are the same
     * write and the same intent. FR-27 decision 3's *"reset restores default order AND default
     * set"* falls out of that rather than needing a second call — both facts live in this one
     * key, so removing it removes both.
     */
    suspend fun clearForCharacter(characterKey: String)

    /** Drops every pane choice belonging to [accountId]. See the class KDoc. */
    suspend fun deleteForAccount(accountId: String)

    companion object {
        /**
         * The key for a DiceCloud character — decision 8's `pane_layout:server:<acct>:<creature>`.
         *
         * Account-scoped for [InventoryLayoutStore.serverKey]'s reasons exactly: the same creature
         * is reachable from two accounts and is two rows everywhere else in this app, and the
         * scoping is what makes [deleteForAccount] a prefix match rather than a scan.
         */
        fun serverKey(accountId: String, creatureId: String): String =
            "$SERVER_PREFIX$accountId:$creatureId"

        /**
         * The key for an on-device character — decision 8's `pane_layout:local:<id>`.
         *
         * A separate prefix for the guarantee rather than for disambiguation: it is what makes
         * [deleteForAccount]'s prefix match *provably* unable to reach a local character's panes.
         * Sign-out must not touch local data (09 decision 10).
         */
        fun localKey(characterId: String): String = "$LOCAL_PREFIX$characterId"

        internal const val KEY_PREFIX = "pane_layout:"
        internal const val SERVER_PREFIX = "${KEY_PREFIX}server:"
        internal const val LOCAL_PREFIX = "${KEY_PREFIX}local:"
    }
}

/**
 * The stored format (decision 8: *"comma-joined surface keys"*, extended by FR-27 decision 1) —
 * the surfaces in the player's order, each optionally prefixed by `!` for *not open as a pane*:
 * `tracker,!inventory,sheet,!actions`.
 *
 * ### What FR-27 changed, and what it deliberately did not
 *
 * The separator, the tokens and the drop-unknown policy are untouched. Two things are new: the
 * order is now read out of the string instead of being discarded, and a surface can be *in* the
 * string without being open. FR-27 decision 1 describes the first as removing set semantics that
 * *"were imposed at the codec"*, which is exactly right — the file always was a list.
 *
 * The mark is the second half, and it is not decoration. The list has to hold a position for
 * every surface the player has arranged, including the ones the pane picker has **not** ticked,
 * or a phone player nudging their tab row would be silently opening every surface as a tablet
 * pane — and an order over only the selected surfaces cannot express the gesture at all (see
 * [PaneLayoutEntry]). `!` is [InventoryLayoutCodec]'s own mark, reused for the same shape of
 * fact — *in the order, not drawn* — so a reader who has met one format has met both.
 *
 * ### Every string 1.9.0 wrote still reads correctly
 *
 * An unmarked token decodes to `selected = true`, so `tracker,sheet` — the only shape any
 * released build ever wrote — decodes to Tracker and Sheet open, in that order, which is what
 * that string has always meant. There is no migration and no version field, because the old
 * format is a strict subset of this one.
 *
 * The other direction is not claimed, and [InventoryLayoutCodec]'s KDoc states why it does not
 * need to be: `android:allowBackup="false"` keeps a newer file off an older install, and Android
 * refuses an in-place downgrade, so no build is ever handed a string a later build wrote. Were
 * it to happen, a 1.9.0 reader meets the token `!inventory`, matches no [PaneSurface], and drops
 * that one surface — the arrangement survives, one entry poorer. That is the same honest
 * degradation this codec already applies to an unknown key.
 *
 * ### Why a hand-rolled string and not JSON
 *
 * [InventoryLayoutCodec]'s reason, unchanged and if anything stronger here: the value is a
 * handful of short constant tokens, JSON would cost a serializer dependency in `:core:data` to
 * express `["tracker","sheet"]`, and the format stays readable in a preferences dump — which is
 * the only debugging tool a DataStore preference has.
 *
 * ### Unknown tokens are DROPPED, which is the opposite of what the inventory codec does
 *
 * [InventoryLayoutCodec] keeps a key it does not recognise, because there the keys are *opaque*
 * — `container:abc` names a thing on the character's sheet that `:core:data` has never heard of
 * and has no business discarding. Here the vocabulary is closed: every legal token is a
 * [PaneSurface] constant this build compiled in. A token outside it can only be a surface some
 * *later* release added, and the honest degradation is to render the panes this build does have
 * rather than to carry an id nothing can draw.
 *
 * That difference is why this is a second codec rather than a reuse of the first, and it is
 * pinned in `PaneLayoutStoreTest`: a future `pane_layout` of `tracker,dmview` must open on
 * Tracker, not crash, not show an empty column, and not lose Tracker.
 *
 * ### A repeated surface is first-wins, not last-wins
 *
 * FR-27 decision 3. A surface cannot be in two columns, so a duplicate has to collapse — and
 * with an order in play, *which* copy survives is now a visible decision rather than a set
 * absorbing it. First-wins because the first occurrence is the one whose position the rest of
 * the string was written around: dropping it would move a surface the player never touched.
 * [InventoryLayoutPlan]'s `weave` reaches the same answer with `distinct()`, and this is the
 * same rule stated where the duplicate actually arrives.
 *
 * ### What makes the delimiters safe
 *
 * Every token is a compiled-in constant from [PaneSurface], every one of them starts with a
 * letter, and none contains a comma or a `!`. That is what lets the prefix run be defined
 * negatively — *everything before the first alphanumeric* — which is [InventoryLayoutCodec]'s
 * formulation and the only one that can absorb a mark this build has never heard of. [decode]
 * does not rely on any of it being true forever: blank segments are dropped, so a malformed
 * string that somehow reached the file degrades to a partial order — and, via the empty case, to
 * the default — rather than to a crash.
 *
 * [encode] emits the marks in one canonical spelling, so `encode(decode(s))` is byte-stable and
 * two writers of the same arrangement agree.
 */
internal object PaneLayoutCodec {
    const val SEPARATOR = ","

    /** *In the order, not open as a pane.* [InventoryLayoutCodec.HIDDEN_MARK]'s character. */
    const val DESELECTED_MARK = '!'

    fun encode(panes: List<PaneLayoutEntry>): String =
        panes.joinToString(SEPARATOR) { entry ->
            if (entry.selected) entry.surface.key else "$DESELECTED_MARK${entry.surface.key}"
        }

    fun decode(stored: String?): List<PaneLayoutEntry> =
        stored.orEmpty()
            .split(SEPARATOR)
            .mapNotNull { segment ->
                val token = segment.trim()
                val marks = token.takeWhile { !it.isLetterOrDigit() }
                // An unrecognised *key* drops the whole entry (the vocabulary is closed — see
                // above); an unrecognised *mark* is simply not `!`, so it reads as selected and
                // the surface is kept. Losing one flag beats losing the surface.
                PaneSurface.fromKey(token.drop(marks.length))?.let { surface ->
                    PaneLayoutEntry(surface, selected = DESELECTED_MARK !in marks)
                }
            }
            // First-wins. See the class KDoc.
            .distinctBy { it.surface }
}

/** Preferences-DataStore backed [PaneLayoutStore]. */
class DataStorePaneLayoutStore(
    private val dataStore: DataStore<Preferences>,
) : PaneLayoutStore {

    override fun panes(characterKey: String): Flow<List<PaneLayoutEntry>> =
        dataStore.data.map { PaneLayoutCodec.decode(it[key(characterKey)]) }

    override suspend fun setPanes(characterKey: String, panes: List<PaneLayoutEntry>) {
        val preferenceKey = key(characterKey)
        dataStore.edit { prefs ->
            // Absent, not empty — see the interface. An empty string would decode to an empty
            // list and read identically, which is exactly why it must not be written.
            if (panes.isEmpty()) {
                prefs.remove(preferenceKey)
            } else {
                prefs[preferenceKey] = PaneLayoutCodec.encode(panes)
            }
        }
    }

    override suspend fun clearForCharacter(characterKey: String) {
        dataStore.edit { it.remove(key(characterKey)) }
    }

    /**
     * Removes by **prefix**, in one edit — `DataStoreInventoryLayoutStore.deleteForAccount`'s
     * mechanism, for its reasons: DataStore is a flat map with no index, so the account's
     * characters are found by the shape of the key rather than by asking a table which creatures
     * the account had. That is the more robust direction — a character the cache had already
     * forgotten is still reaped — and the whole thing is one atomic `edit`.
     */
    override suspend fun deleteForAccount(accountId: String) {
        val prefix = PaneLayoutStore.serverKey(accountId, "")
        dataStore.edit { prefs ->
            prefs.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { prefs.remove(stringPreferencesKey(it.name)) }
        }
    }

    private fun key(characterKey: String) = stringPreferencesKey(characterKey)
}
