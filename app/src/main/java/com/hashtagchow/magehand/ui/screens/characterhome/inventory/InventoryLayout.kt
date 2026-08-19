package com.hashtagchow.magehand.ui.screens.characterhome.inventory

import com.hashtagchow.magehand.core.data.settings.InventoryLayoutEntry

/**
 * The stable names of the inventory tab's sections (docs/design/12-inventory-layout.md
 * decision 4).
 *
 * ### Why these are constants and not `enum.name.lowercase()`
 *
 * They were exactly that until FR-14, and it was free while the key's only job was to keep a
 * `LazyColumn` item identity stable across a re-sync. It stops being free the moment the key is
 * **written to disk**: `InventorySectionKind` is a UI enum, renaming one of its values is an
 * ordinary refactor, and a rename would silently invalidate every player's stored arrangement —
 * the sections would all read as unknown keys and quietly fall back to the default order. Nobody
 * would report that as a bug; they would report that customizing "doesn't stick sometimes".
 *
 * So the persisted vocabulary is spelled out here, once, and the enum's key is derived from it
 * rather than the other way round. [WALLET] has no enum value at all — see
 * [InventorySectionKind]'s KDoc for why the wallet is deliberately not one of those kinds — which
 * is the other half of the same argument: the key space is the *tab's*, not the enum's.
 */
object InventoryLayoutKeys {
    const val WALLET = "wallet"
    const val EQUIPPED = "equipped"
    const val WEAPONS = "weapons"
    const val ARMOR = "armor"
    const val GEAR = "gear"

    /** A container is keyed by its `creatureProperties._id` — decision 4's second half. */
    const val CONTAINER_PREFIX = "container:"

    fun container(propertyId: String): String = "$CONTAINER_PREFIX$propertyId"

    fun isContainer(key: String): Boolean = key.startsWith(CONTAINER_PREFIX)

    /**
     * Whether the customize sheet offers this section a hide control (12 decision 3's guardrails).
     *
     * Two sections are refused, for two different reasons, and both are worth stating because
     * they are the whole difference between this hide and the tracker's:
     *
     *  - **[EQUIPPED]** — the design's own words: *"hiding the section that shows what you're
     *    wielding is a footgun with no use case"*. Nothing is gained and a player who did it by
     *    accident would be looking at a sheet that no longer says what is in their hands.
     *
     *  - **[GEAR]** — because it is where everything else folds **to**. Decision 3's invariant is
     *    *"items fold, never vanish"*: hiding Weapons moves its rows into Gear, hiding a container
     *    moves its contents into Gear, and there is by construction nowhere for Gear's own rows to
     *    go. A hide control on Gear is therefore the one hide on this sheet that could not
     *    preserve the invariant, so it is not offered.
     *
     * The design's decision-3 prose does list Gear as hideable, one sentence before it states the
     * invariant that forbids it; the invariant is the load-bearing half — it is named *the*
     * invariant, and the wave brief restates the guardrails without Gear among the hideable ones.
     * Resolved in favour of the invariant, and recorded here rather than in a commit message
     * because this is the line a future reader will come to when they wonder where Gear's ✕ went.
     *
     * This is enforced **twice** on purpose: the sheet does not draw the control (so it cannot be
     * tapped) and [InventoryLayoutPlan] refuses the write and strips the flag on read (so a
     * hand-edited or future-version preferences file cannot make it happen either). A guardrail
     * that lives only in a composable is a guardrail the next screen forgets.
     */
    fun isHideable(key: String): Boolean = key != EQUIPPED && key != GEAR

    /**
     * Whether this section's **collapsed** state is remembered across restarts
     * (docs/design/13-collapsible-sections-local-gear.md decision 3).
     *
     * True for every section but one. Decision 1 makes every header an expander — Equipped and
     * Gear included, which is why this is not a second spelling of [isHideable]: those two
     * refuse to be *folded away* and collapse perfectly well.
     *
     * ### The Wallet exception, and why it is here rather than in the composable
     *
     * Decision 3's own words: the wallet's collapsed-default plus ephemeral expansion **is** the
     * designed reading (11 decision 4), and *"I opened my wallet to pay"* should not leave a
     * player's purse open for a week. So the wallet alone keeps `rememberSaveable` and writes
     * nothing here.
     *
     * This is the standing convention's first recorded "good reason" exception for a sub-rule
     * (00-DESIGN.md: sectioned surfaces default to collapsible), and an exception that lives
     * only as a `rememberSaveable` in one composable is an exception the next screen forgets. So
     * it is enforced the way [isHideable]'s guardrails are, **twice**: [InventoryLayoutPlan]
     * refuses to write a collapsed wallet and strips the flag on read, so a hand-edited or
     * future-version preferences file cannot reach the state either — and a reader wondering
     * why the wallet behaves differently finds the answer next to the rule instead of next to
     * the `var`.
     */
    fun persistsCollapse(key: String): Boolean = key != WALLET
}

/**
 * One thing the inventory tab draws, in order.
 *
 * ### Why the ordered list is blocks and not sections
 *
 * Because decision 1 puts the **Wallet** in the order and decision 3 lets the player move and
 * hide it, so the wallet has to be an element of whatever list expresses the order — and the
 * wallet is not an [InventorySectionState] and must not become one. [InventorySectionKind]'s
 * KDoc already argued that at length: the wallet is four fixed coin rows with steppers rather
 * than a list of item rows, it can never be empty, and its contents are not [InventoryRowState]s
 * at all, so modelling it as another section would put an ignorable field on every section so
 * that one of them could ignore it.
 *
 * A two-case union says the true thing instead — *the tab draws the wallet block, or an item
 * section* — and it makes the screen a `when` with no nullable branches. `InventorySectionState`
 * is untouched by FR-14 as a result.
 */
sealed interface InventoryBlock {

    /** The stable key this block is ordered and remembered by. See [InventoryLayoutKeys]. */
    val key: String

    /** The coin block (10 decision 5, FR-11's collapsing header). */
    data object Wallet : InventoryBlock {
        override val key: String get() = InventoryLayoutKeys.WALLET
    }

    /** Any section of item rows: Equipped, Weapons, Armor, a container, or Gear. */
    data class Items(val section: InventorySectionState) : InventoryBlock {
        override val key: String get() = section.key
    }
}

/**
 * Turns a stored arrangement and the sections a character actually has into the order to draw,
 * and turns a customize gesture back into the arrangement to store.
 *
 * Pure and separate from both the composable and the view model, for `TrackerOverridePlan`'s
 * reason exactly: the awkward part of a reorder feature is the *arithmetic on the list*, and it
 * is the part no screenshot and no manual pass would catch getting subtly wrong.
 *
 * ### The one idea in this file: [weave]
 *
 * Two different problems here are the same problem — *I have a list, and some keys that belong
 * in it are missing; where do they go?* — and both are answered by "wherever the other list says,
 * relative to what is already here":
 *
 *  1. **Reading.** The stored order predates a section that now exists (decision 4's
 *     "unknown/new keys append at the default position"): a container the player just added, or a
 *     section a future release introduces. Woven against the **default** order, a new container
 *     lands between Armor and Gear where decision 1 puts it, rather than at the bottom of a list
 *     the player arranged.
 *  2. **Writing.** The board is missing a section the stored order remembers — the socket is
 *     still connecting, or a snapshot is on screen. Woven against the **stored** order, a hide of
 *     the Wallet does not quietly forget where four containers used to sit.
 *
 * That second one is the reason writes are not simply "persist what is on screen". The window is
 * narrow — the customize sheet is opened from a tab the player is looking at — but the failure is
 * silent and total: one gesture during a cold open would drop every container's remembered place,
 * and the player would have no way to know it had happened.
 */
object InventoryLayoutPlan {

    /**
     * The order to draw: [defaultKeys] arranged the way [stored] asks, guardrails applied.
     *
     * @param defaultKeys every section this board has, in decision 1's default order. Sections
     *   the character does not have are simply not in it, which is what makes a vanished
     *   container "drop out of the stored order harmlessly" (decision 4) — it is filtered out
     *   here and left untouched on disk, so it comes back where it was if the container does.
     * @param stored what [com.hashtagchow.magehand.core.data.settings.InventoryLayoutStore] has
     *   for this character, or empty when they have never customized — in which case this returns
     *   [defaultKeys] unchanged, all visible, which is decision 1.
     */
    fun resolve(
        defaultKeys: List<String>,
        stored: List<InventoryLayoutEntry>,
    ): List<InventoryLayoutEntry> {
        val present = defaultKeys.toSet()
        val ordered = weave(
            base = stored.map { it.key }.filter { it in present },
            reference = defaultKeys,
        )
        val storedHidden = stored.filter { it.hidden }.map { it.key }.toSet()
        val storedCollapsed = stored.filter { it.collapsed }.map { it.key }.toSet()
        return ordered.map { key ->
            InventoryLayoutEntry(
                key = key,
                // The guardrail, applied on the way *in* as well as on the way out. A stored
                // `!equipped` — from a hand-edited file, or from a future version that allowed
                // it — reads as visible rather than as a section the player cannot get back.
                hidden = key in storedHidden && InventoryLayoutKeys.isHideable(key),
                // 13 decision 2's default is *expanded for everything but the wallet*, and it
                // is expressed by absence: a key the store has never heard of is not in
                // `storedCollapsed`, so it renders open. The wallet's own default lives in the
                // composable's `rememberSaveable`, which is the exception — see
                // [InventoryLayoutKeys.persistsCollapse], enforced here as well as there.
                collapsed = key in storedCollapsed && InventoryLayoutKeys.persistsCollapse(key),
            )
        }
    }

    /**
     * Moves one visible section by [delta] places and returns the whole arrangement to persist.
     *
     * Returns an **empty list** when the move is a no-op — the key is not on screen, the delta is
     * zero, or the section is already at the end it is being moved towards — so a bounce off the
     * top of the list is not a write. That is `TrackerOverridePlan.reorder`'s contract and the
     * same signal: a real arrangement always has at least one section in it, so empty is
     * unambiguous.
     *
     * ### Why hidden sections do not move and are not stepped over
     *
     * The move is expressed against the **visible** list, because that is the list the player can
     * see and the arrows are drawn on. Underneath, it is a *swap* of the two entries' absolute
     * positions, which leaves any hidden section lying between them exactly where it was. That is
     * what makes un-hiding predictable: a folded section reappears in the place it was folded
     * from, rather than at whichever end of the list the visible moves happened to push it.
     */
    fun move(
        resolved: List<InventoryLayoutEntry>,
        stored: List<InventoryLayoutEntry>,
        key: String,
        delta: Int,
    ): List<InventoryLayoutEntry> {
        if (delta == 0) return emptyList()
        val visible = resolved.filterNot { it.hidden }
        val from = visible.indexOfFirst { it.key == key }
        if (from < 0) return emptyList()
        val to = from + delta
        if (to !in visible.indices) return emptyList()

        val here = resolved.indexOfFirst { it.key == key }
        val there = resolved.indexOfFirst { it.key == visible[to].key }
        val moved = resolved.toMutableList().apply {
            this[here] = resolved[there]
            this[there] = resolved[here]
        }
        return persist(moved, stored)
    }

    /**
     * Folds a section away, or brings it back, and returns the whole arrangement to persist.
     *
     * Empty for a no-op, as [move] is, and **also** empty when the guardrail refuses: hiding
     * Equipped or Gear is not a thing this app does, and the refusal is here as well as in the
     * sheet so that the only way to reach the state is to write the preferences file by hand —
     * at which point [resolve] strips it on the way back in. See [InventoryLayoutKeys.isHideable].
     */
    fun setHidden(
        resolved: List<InventoryLayoutEntry>,
        stored: List<InventoryLayoutEntry>,
        key: String,
        hidden: Boolean,
    ): List<InventoryLayoutEntry> {
        if (hidden && !InventoryLayoutKeys.isHideable(key)) return emptyList()
        val index = resolved.indexOfFirst { it.key == key }
        if (index < 0 || resolved[index].hidden == hidden) return emptyList()
        val next = resolved.toMutableList().apply {
            this[index] = this[index].copy(hidden = hidden)
        }
        return persist(next, stored)
    }

    /**
     * Collapses a section, or opens it, and returns the whole arrangement to persist
     * (docs/design/13-collapsible-sections-local-gear.md decision 3).
     *
     * [setHidden]'s shape exactly, down to the empty-for-a-no-op contract and the guardrail
     * refusal — which is the point of writing it as a twin rather than generalising the two into
     * one `setFlag(…, KProperty)`. They are two different player intents ("stop grouping my
     * items this way" versus "stop showing me this list right now"), they have different
     * guardrails ([InventoryLayoutKeys.isHideable] versus
     * [InventoryLayoutKeys.persistsCollapse]), and a reader at either call site should see which
     * one they are looking at without resolving a property reference.
     *
     * The refusal here is the **Wallet**, and it is a refusal to *store* rather than a refusal
     * to collapse: the wallet's chevron works, its state simply lives in the composable. A write
     * that reached this with `wallet` would be a bug one layer up, and returning empty is what
     * makes it a no-op rather than a preference file that quietly disagrees with the screen.
     */
    fun setCollapsed(
        resolved: List<InventoryLayoutEntry>,
        stored: List<InventoryLayoutEntry>,
        key: String,
        collapsed: Boolean,
    ): List<InventoryLayoutEntry> {
        if (!InventoryLayoutKeys.persistsCollapse(key)) return emptyList()
        val index = resolved.indexOfFirst { it.key == key }
        if (index < 0 || resolved[index].collapsed == collapsed) return emptyList()
        val next = resolved.toMutableList().apply {
            this[index] = this[index].copy(collapsed = collapsed)
        }
        return persist(next, stored)
    }

    /**
     * What to write: [edited] — the sections on screen, in their new order — with everything the
     * store remembers about sections this board does not currently have put back where it had it.
     *
     * See the class KDoc's second [weave] case for why that matters.
     */
    private fun persist(
        edited: List<InventoryLayoutEntry>,
        stored: List<InventoryLayoutEntry>,
    ): List<InventoryLayoutEntry> {
        val ordered = weave(base = edited.map { it.key }, reference = stored.map { it.key })
        // `edited` last, so a key in both wins with its new hidden flag and its new place.
        val byKey = stored.associateBy { it.key } + edited.associateBy { it.key }
        return ordered.mapNotNull { byKey[it] }
    }

    /**
     * Every key of [reference] that is missing from [base], inserted where [reference] puts it —
     * immediately after the nearest earlier reference key that [base] does have, or at the front
     * when there is none.
     *
     * "Nearest earlier neighbour" rather than "the same index", because the two lists are
     * different lengths and an index means nothing across them: appending at the end would ignore
     * the reference entirely, and copying the index would put a new container wherever the
     * arithmetic landed. An anchor is the only formulation that survives both lists changing.
     *
     * Order matters in the loop: a run of consecutive missing keys anchors each one off the
     * previous, which has just been inserted, so `a,b,c` missing from `[c]` rebuilds as
     * `[a,b,c]` rather than as `[b,a,c]`.
     */
    private fun weave(base: List<String>, reference: List<String>): List<String> {
        val out = base.distinct().toMutableList()
        reference.forEachIndexed { index, key ->
            if (key in out) return@forEachIndexed
            val anchor = reference.take(index).lastOrNull { it in out }
            out.add(if (anchor == null) 0 else out.indexOf(anchor) + 1, key)
        }
        return out
    }
}
