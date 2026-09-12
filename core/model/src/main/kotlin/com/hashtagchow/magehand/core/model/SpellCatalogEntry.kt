package com.hashtagchow.magehand.core.model

/**
 * One entry in the bundled **spell** catalog (FR-49,
 * docs/design/20-local-spells-and-attacks.md decision 7).
 *
 * ### A template, exactly as [CatalogItem] is — and 20 decision 6 says so in as many words
 *
 * *"The catalog is a template, not the truth."* Tapping one of these does **not** add a row: it
 * pre-fills the add form with these fields, the player edits whatever they like, and the saved
 * [LocalTrackerRow] **copies** the values it keeps. [id] rides along as
 * [LocalTrackerRow.catalogId] for provenance and for a future *"update from catalog"* that can
 * diff on it; nothing re-reads this table to render a row, so a row cannot dangle when an entry
 * changes and a spell the player rewrote stays rewritten.
 *
 * ### Where the strings come from, and why they are not in `strings.xml`
 *
 * [ItemCatalog]'s LOW-10 ruling, unchanged and now carrying three hundred times the text:
 * **UI chrome is copy and lives in `strings.xml`; catalog entries are data.** These are the
 * System Reference Document's own sentences, reproduced verbatim under CC-BY-4.0, and
 * translating them would not make them a different work — it would make them a work this app
 * cannot attribute. The loader (`:core:data`'s `SpellCatalog`) carries the attribution and the
 * provenance record; `core/data/src/main/resources/catalog/provenance.json` carries the retrieval.
 *
 * ### Why the type is here and the table is not
 *
 * `:core:model` is dependency-free by design (docs/design/01-architecture.md — *"no Android, no
 * serialization"*), and three hundred spells of prose is a **JSON resource** rather than a Kotlin
 * table precisely because it is data. Parsing it needs a serializer, so the loader lives one
 * module out in `:core:data` while the type a caller holds lives here beside [CatalogItem]. See
 * `SpellCatalog`'s KDoc for the whole argument, which is this wave's one recorded deviation from
 * decision 7's file list.
 *
 * @property id the source's own slug — `"fireball"`. Stable, lowercase, never shown to a user.
 * @property level `0` for a cantrip, `1..9` otherwise.
 * @property school *"Evocation"*. Shown in the picker's one-line summary; not otherwise used.
 * @property components the component letters plus any material, as one string —
 *   *"V, S, M (A tiny ball of bat guano and sulfur.)"*. The source's own capitalisation and
 *   punctuation inside the parentheses, un-normalised: a verbatim reproduction is verbatim or it
 *   is an edit nobody recorded.
 * @property higherLevels the *"Using a Higher-Level Spell Slot"* / *"At Higher Levels"* paragraph,
 *   or `null` when the spell has none — which is most of them (90 of 319). **Nothing computes
 *   from it**; see [LocalTrackerRow.higherLevels] for the rule and for why it is a rule.
 * @property classes informational only. There is deliberately no class filter in this wave
 *   (20 decision 10) — a local character records no class to filter *by*.
 */
data class CatalogSpell(
    val id: String,
    val name: String,
    val level: Int,
    val school: String,
    val castingTime: String,
    val range: String,
    val components: String,
    val duration: String,
    val concentration: Boolean,
    val ritual: Boolean,
    val description: String,
    val higherLevels: String? = null,
    val classes: List<String> = emptyList(),
)

/**
 * One entry in the bundled **weapon** catalog (20 decision 7). [CatalogSpell]'s sibling, and a
 * template on exactly the same terms — see there.
 *
 * @property category the SRD's own two-axis classification as one phrase — *"Martial Melee"*,
 *   *"Simple Ranged"*. One string rather than two enums because it is one line of display text
 *   and nothing branches on it.
 * @property damage the damage as text — *"1d8 slashing"*, or *"1d8 / 1d10 slashing"* for a
 *   versatile weapon with the two-handed die second. **Never parsed**: 20 decision 8 keeps every
 *   number on this path as the SRD's own characters, because a local character has no sheet to
 *   resolve a die against and a wrong bonus is worse than no bonus (the FR-36 lesson).
 * @property properties the SRD's property names, with the numeric parentheticals the 2024 data
 *   carries as structured fields composed back onto them — *"Versatile (1d10)"*,
 *   *"Thrown (Range 20/60)"*. A list here and one joined string on the row it creates, because a
 *   row's properties are a sentence the player may edit.
 * @property mastery the 2024 weapon-mastery word — *"Sap"*, *"Nick"* — or `null` on an edition
 *   that has no such concept. It is written into the created row's
 *   [LocalTrackerRow.properties] as a `Mastery: X` entry rather than into a column of its own, so
 *   that FR-47's badge has exactly one source and the player can edit it like any other property.
 */
data class CatalogWeapon(
    val id: String,
    val name: String,
    val category: String,
    val damage: String,
    val properties: List<String> = emptyList(),
    val mastery: String? = null,
    val weightLb: Double? = null,
    val costGp: Double? = null,
)
