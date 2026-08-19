package com.hashtagchow.magehand.core.model

/**
 * What kind of thing an item **is**, as this app is willing to state it
 * (docs/design/13-collapsible-sections-local-gear.md decision 7).
 *
 * ### Why a constant and not a tag list
 *
 * A DiceCloud sheet answers "is this a weapon?" with a free-text tag taxonomy, and
 * `InventoryEngine` reads it (11 decision 1). A **local** character has no taxonomy at all —
 * 09's form captures no tags and `LocalTrackerRow` stores none — so FR-10b collects the answer
 * directly instead of collecting the evidence for it. Three values rather than a `List<String>`
 * because that is exactly what the local rule needs and nothing more: reproducing the tag
 * indirection over a table this app owns outright would be modelling DiceCloud's limitation
 * rather than the item, which is the argument [CoinPurse] already makes about currency.
 *
 * The three values are chosen to line up with [EquipGroup] one-for-one (see [equipGroup]), so a
 * catalog weapon and a server-discovered weapon land in the same section and mean the same
 * thing — decision 7's requirement, expressed as a total function rather than as a convention.
 *
 * ### Why [GEAR] is the default everywhere
 *
 * Because it is the reading of *"nobody said"* that costs the player nothing. Decision 10's
 * local rule is `category != GEAR || equipped || override`, so a row that defaults to gear keeps
 * its equip control whenever it is equipped and can always get one back through 11 decision 2's
 * override. Defaulting to [WEAPON] or [ARMOR] would be this app inventing a claim about an item
 * it was never told anything about.
 */
enum class CatalogCategory {
    WEAPON,
    ARMOR,
    GEAR,
    ;

    /** Which Carried subsection an item of this category lands in (11 decision 3). */
    val equipGroup: EquipGroup
        get() = when (this) {
            WEAPON -> EquipGroup.WEAPON
            ARMOR -> EquipGroup.ARMOR
            GEAR -> EquipGroup.GEAR
        }

    /**
     * What `local_tracker_rows.category` holds.
     *
     * Spelled out rather than `name.lowercase()`, for [LocalRowKind.storedValue]'s reason and
     * `InventoryLayoutKeys`' at greater length: the moment a value is **written to disk**,
     * renaming the enum constant stops being an ordinary refactor and becomes a silent
     * re-classification of every row already stored. The persisted vocabulary is written here,
     * once, and the constant's name is free to change without it.
     */
    val storedValue: String
        get() = when (this) {
            WEAPON -> "weapon"
            ARMOR -> "armor"
            GEAR -> "gear"
        }

    companion object {
        /** Decision 8's column default, and the reading of "never collected". See the KDoc. */
        val DEFAULT: CatalogCategory = GEAR

        /**
         * The stored value's category, or [DEFAULT] for anything this build does not know.
         *
         * **Not nullable**, which is the deliberate difference from [LocalRowKind.fromStored]:
         * an unrecognised `kind` means a row this app cannot render at all, so dropping it is
         * the honest answer. An unrecognised `category` — from a downgrade after some future
         * release added a fourth — describes a row that renders perfectly well; refusing it
         * would delete a player's item to protect a classification. Gear is the same "nobody
         * usable said anything" reading the column default carries.
         */
        fun fromStored(value: String?): CatalogCategory =
            entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

/**
 * One entry in the built-in add-item catalog (docs/design/10-inventory.md decision 6).
 *
 * A *template*, not an item: it has no `_id`, no quantity of its own beyond a sensible
 * default, and nothing that ties it to a character. [NewItemSpec.of] turns one into
 * something creatable.
 */
data class CatalogItem(
    /**
     * This app's own stable key — lowercase, hyphenated, never shown to the user.
     *
     * Stable is the whole requirement: it goes into [NewItemSpec.catalogId] so wave B can
     * recognise "the player added a torch" across sessions, and renaming an entry's display
     * name must not break that. It is never sent to DiceCloud (see [NewItemSpec.catalogId]).
     */
    val id: String,
    /** As the SRD spells it — the name the created item carries onto the sheet. */
    val name: String,
    /** Pounds, per unit. */
    val weightLb: Double,
    /** Gold pieces, per unit. SRD costs in sp/cp are converted (1 sp = 0.1, 1 cp = 0.01). */
    val valueGp: Double,
    /** Written onto the created item so the sheet can group and filter it as usual. */
    val tags: List<String>,
    /**
     * What this entry **is** (13 decision 7) — carried onto a local row, which has no [tags]
     * to be classified by.
     *
     * No default, deliberately. Every entry states its own category at the point it is written,
     * so adding a longsword to this list is a line that cannot compile without answering the
     * question — which is the one moment a reader has the SRD's own statistics in front of them.
     * A defaulted `GEAR` would let a weapon join the catalog silently mis-filed, and the defect
     * would surface as "the equip chip is missing on my new sword", months later.
     *
     * It must agree with [tags] under 11 decision 1's taxonomy — `ItemCatalogCategoryTest` pins
     * that against `InventoryEngine`'s own tag sets, so a catalog entry and a server-discovered
     * item cannot disagree about what the same object is.
     */
    val category: CatalogCategory,
    /** One line. The picker shows it under the name; the created item carries it. */
    val description: String,
    /**
     * How many the picker offers first.
     *
     * Not always 1, and that is the point of having the field: ammunition and pitons are
     * bought and carried in bundles, and a catalog that made the player tap "+1" nineteen
     * times for a quiver would be slower than the custom form it exists to replace.
     */
    val defaultQuantity: Int = 1,
)

/**
 * The built-in add-item catalog — the whole of 10 decision 6's "curated catalog" half.
 *
 * ### Why a hard-coded list and not a library search
 *
 * Because the search does not work. The probe established that `searchLibraryNodes` matches
 * on `name` or an exact tag, and SRD items are stored as `reference` nodes carrying **no
 * top-level name** — so a name search for "torch" returns nothing, for every item in the
 * library. 10 decision 6 records the SRD library path as deliberately out of scope rather
 * than shipping a search box that is empty by construction; revisiting it needs a
 * fetch-and-filter design, not a bug fix.
 *
 * Thirty-odd entries is not an attempt at completeness and should not become one. It is the
 * set a player reaches for mid-session, where the alternative is typing a name, a weight and
 * a price into a form while four other people wait. Anything rarer is exactly what the custom
 * form is for.
 *
 * ### English in a data file, recorded as an ACCEPTED decision (LOW-10, 11 decision 8)
 *
 * Every `name` and `description` below is an English string compiled into the app, and no
 * amount of `strings.xml` discipline elsewhere will translate them. That was raised as a
 * localization defect and is **accepted rather than open**, on the grounds that these are not
 * UI copy: "Rope, Hempen (50 feet)" is the name of a thing that will be written onto a
 * DiceCloud sheet, read back by DiceCloud's own web UI, and shown to a table of players who
 * may not all be running this app. Moving it to `strings.xml` would mean a French device
 * creating an item a sheet's other readers cannot recognise — a localization that makes the
 * shared artifact worse.
 *
 * The rule this settles on, so a future reader does not re-open it: **UI chrome is copy and
 * lives in `strings.xml`; catalog entries are data and live here.** Revisit only as part of a
 * real localization pass, which would have to answer the harder question first — what language
 * a *sheet* is in — and not by moving these thirty-five strings.
 *
 * ### Source and licence
 *
 * Every name, weight and price below is from the **System Reference Document 5.1** (Wizards
 * of the Coast), which is published under the Creative Commons Attribution 4.0 International
 * licence and, previously, the Open Gaming Licence 1.0a. SRD 5.1 gear statistics are
 * therefore reproducible here with attribution, which this KDoc is. Nothing in this file is
 * from any non-SRD source, and nothing may be added to it from one.
 *
 * Costs are converted to gold pieces because that is the unit [InventoryItem.valueGp] and
 * DiceCloud's own `value` field both use: 1 sp = 0.1 gp, 1 cp = 0.01 gp. Weights the SRD
 * leaves as "—" are `0.0` — the SRD's own claim that the item is too light to track, not a
 * missing measurement (contrast [InventoryItem.weightLb], where `null` means "unknown").
 *
 * ### Every entry is [CatalogCategory.GEAR] today, and that is the honest answer
 *
 * 13 decision 7 asks for every entry to carry a category, classified *by the same tag-set 11
 * decision 1 uses*. Applying that rule to this list returns **gear for all thirty-five entries**,
 * because this list is the SRD's *Adventuring Gear* table and nothing else: it has never carried
 * a weapon or a suit of armor, and the two entries that come closest do not survive contact with
 * the rule either — ammunition (`arrows`, `crossbow-bolts`) is what a weapon consumes rather
 * than a weapon, and the flask of oil is an improvised thrown object, which is a *use* of an
 * item and not a class of one.
 *
 * That is worth writing down rather than leaving as a coincidence a reader has to re-derive,
 * because it makes the category look like dead weight when it is not:
 *
 *  - It is the **capture point** decision 9 names. `NewItemSpec.of` carries it onto the created
 *    row, so the moment this list gains a longsword the local board classifies it correctly with
 *    no further change anywhere.
 *  - [CatalogItem.category] has **no default** precisely so that moment cannot pass unnoticed.
 *  - `ItemCatalogCategoryTest` asserts the agreement in both directions — including that the
 *    catalog carries no weapon or armor *tag* today, so a future entry that adds one and leaves
 *    the category at gear fails a test rather than shipping mis-filed.
 *
 * Nothing here should be re-categorised to make the field look busier. A tinderbox is gear.
 */
object ItemCatalog {

    /** The tag every entry carries, so a sheet can tell app-created items apart. */
    const val TAG_ADVENTURING_GEAR: String = "adventuring gear"

    /** SRD gear is mundane by definition; the one potion below overrides it. */
    private const val TAG_MUNDANE = "mundane"

    /**
     * The entries, in the SRD's own alphabetical order.
     *
     * Alphabetical and not by category or popularity: the player is *looking for a known
     * name*, which is a scan, and a list ordered by anything else makes them read all thirty
     * entries to be sure the one they want is absent. Wave B is free to put a search field on
     * top; the underlying order still has to be the one a human can binary-search by eye.
     */
    val entries: List<CatalogItem> = listOf(
        gear("arrows", "Arrows (20)", 1.0, 1.0, "Ammunition for a shortbow or longbow.", 20, "ammunition"),
        gear("ball-bearings", "Ball Bearings (bag of 1,000)", 2.0, 1.0, "Spill to cover a 10-foot square; a creature moving through must save or fall prone."),
        gear("bedroll", "Bedroll", 7.0, 1.0, "A sleeping roll for camping rough."),
        gear("blanket", "Blanket", 3.0, 0.5, "Wool, for warmth on a cold night."),
        gear("caltrops", "Caltrops (bag of 20)", 2.0, 1.0, "Scatter to cover a 5-foot square; a creature entering must save or take 1 piercing damage."),
        gear("candle", "Candle", 0.0, 0.01, "Burns for 1 hour: 5 feet of bright light, 5 more of dim."),
        gear("chalk", "Chalk (1 piece)", 0.0, 0.01, "For marking a route through a dungeon."),
        gear("crossbow-bolts", "Crossbow Bolts (20)", 1.5, 1.0, "Ammunition for any crossbow.", 20, "ammunition"),
        gear("crowbar", "Crowbar", 5.0, 2.0, "Grants advantage on Strength checks where leverage can be applied."),
        gear("grappling-hook", "Grappling Hook", 4.0, 2.0, "Throw and set to climb where there is something to catch on."),
        gear("hammer", "Hammer", 3.0, 1.0, "For driving pitons and other honest work."),
        gear("healers-kit", "Healer's Kit", 3.0, 5.0, "Ten uses; one stabilises a creature at 0 hit points with no check."),
        gear("hunting-trap", "Hunting Trap", 25.0, 5.0, "A saw-toothed steel ring that snaps shut on a creature stepping in it."),
        gear("ink", "Ink (1-ounce bottle)", 0.0, 10.0, "Black ink, for a pen."),
        gear("ink-pen", "Ink Pen", 0.0, 0.02, "A wooden stylus with a metal nib."),
        gear("lantern-hooded", "Lantern, Hooded", 2.0, 5.0, "Burns oil for 6 hours: 30 feet of bright light, 30 more of dim. The hood dims it to a 5-foot glow."),
        gear("mess-kit", "Mess Kit", 1.0, 0.2, "A tin box holding a cup and simple cutlery, for preparing and eating food."),
        gear("mirror-steel", "Mirror, Steel", 0.5, 5.0, "A polished steel hand mirror, for looking around corners and signalling."),
        gear("oil-flask", "Oil (flask)", 1.0, 0.1, "Fuels a lantern for 6 hours, or splashes and burns as a thrown improvised weapon."),
        gear("parchment", "Parchment (one sheet)", 0.0, 0.1, "For writing on."),
        gear("piton", "Piton", 0.25, 0.05, "An iron spike hammered into rock or wood to anchor a rope.", 10),
        gear("pouch", "Pouch", 1.0, 0.5, "Cloth or leather; holds a fifth of a cubic foot, or 6 pounds."),
        gear("rations", "Rations (1 day)", 2.0, 0.5, "Dry food for one day of travel: jerky, dried fruit, hardtack and nuts."),
        gear("rope-hempen", "Rope, Hempen (50 feet)", 10.0, 1.0, "Has 2 hit points and can be burst with a DC 17 Strength check."),
        gear("rope-silk", "Rope, Silk (50 feet)", 5.0, 10.0, "Lighter than hemp, and just as strong."),
        gear("sack", "Sack", 0.5, 0.01, "Holds a cubic foot, or 30 pounds."),
        gear("shovel", "Shovel", 5.0, 2.0, "For digging, and for the things digging leads to."),
        gear("soap", "Soap", 0.0, 0.02, "A bar of it."),
        gear("spellbook", "Spellbook", 3.0, 50.0, "A leather-bound tome of 100 blank vellum pages, suitable for recording spells."),
        gear("tent", "Tent, Two-Person", 20.0, 2.0, "A simple canvas shelter sleeping two."),
        gear("tinderbox", "Tinderbox", 1.0, 0.5, "Flint, steel and tinder. Lights a torch as an action, anything else in about a minute."),
        gear("torch", "Torch", 1.0, 0.01, "Burns for 1 hour: 20 feet of bright light, 20 more of dim. Deals 1 fire damage as an improvised weapon."),
        gear("waterskin", "Waterskin", 5.0, 0.2, "Holds 4 pints. The weight given is full."),
        gear("whistle-signal", "Whistle, Signal", 0.0, 0.05, "Carries further than a shout, and says less."),
        CatalogItem(
            id = "potion-of-healing",
            name = "Potion of Healing",
            weightLb = 0.5,
            valueGp = 50.0,
            // Not `mundane`: this is the one magic item in the list, and a sheet that filters
            // its inventory by tag should see it filed where it belongs.
            tags = listOf("potion", "magic", "common"),
            // Gear like the rest, and worth stating because it is the one entry that had to be
            // decided rather than inherited from [gear]: a potion is drunk, not worn or wielded,
            // and 11 decision 1's taxonomy does not name it either. See the object KDoc's
            // "Every entry is GEAR today" section.
            category = CatalogCategory.GEAR,
            description = "Drink as an action to regain 2d4 + 2 hit points.",
        ),
    ).sortedBy { it.name.lowercase() }

    /** Looks an entry up by [CatalogItem.id]; `null` for one this build does not carry. */
    fun byId(id: String): CatalogItem? = entries.firstOrNull { it.id == id }

    private fun gear(
        id: String,
        name: String,
        weightLb: Double,
        valueGp: Double,
        description: String,
        defaultQuantity: Int = 1,
        extraTag: String? = null,
    ): CatalogItem = CatalogItem(
        id = id,
        name = name,
        weightLb = weightLb,
        valueGp = valueGp,
        tags = listOfNotNull(TAG_ADVENTURING_GEAR, TAG_MUNDANE, extraTag),
        // The helper's own name is the claim: everything it builds carries `adventuring gear`,
        // so a weapon or a suit of armor cannot be created through it and must use the full
        // constructor — which has no category default. See the object KDoc.
        category = CatalogCategory.GEAR,
        description = description,
        defaultQuantity = defaultQuantity,
    )
}
