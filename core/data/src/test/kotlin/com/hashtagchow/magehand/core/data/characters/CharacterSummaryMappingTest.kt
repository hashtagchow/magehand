package com.hashtagchow.magehand.core.data.characters

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `characterList` → [com.hashtagchow.magehand.core.model.CharacterSummary]
 * mapping against the document **shapes** captured from a live server on
 * 2026-08-17 (docs/verification/WP5.md §2).
 *
 * Names, ids and portrait URLs are invented; every quirk asserted here — the DM
 * owning none of the party, portrait-configurator links in `picture`, alignments
 * containing free text, sheets with no alignment at all — is the shape the server
 * actually sends.
 */
class CharacterSummaryMappingTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun doc(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    /** The DungeonMaster account id the dev token belongs to. */
    private val dmUserId = "FakeDmUser23456ab"

    private val elowenOwner = "Fakeowner2345678a"

    private val elowen = doc(
        """
        {"_id":"FakeCreature23456","owner":"Fakeowner2345678a","name":"Elowen Brightmantle",
         "gender":"Female","readers":["FakeDmUser23456ab"],"writers":["FakeDmUser23456ab"],
         "public":false,"alignment":"Chaotic Good",
         "avatarPicture":"https://example.com/portrait.png",
         "picture":"https://example.com/portrait.png"}
        """.trimIndent(),
    )

    private val bramwell = doc(
        """
        {"_id":"FakeCreature98765","owner":"Fakeowner876543zy","name":"Bramwell Ironfoot",
         "gender":"Male","readers":["FakeDmUser23456ab"],"writers":["FakeDmUser23456ab"],"public":false}
        """.trimIndent(),
    )

    @Test
    fun `maps the fields the publication actually sends`() {
        val summary = elowen.toCharacterSummary(dmUserId)

        assertEquals("FakeCreature23456", summary.creatureId)
        assertEquals("Elowen Brightmantle", summary.name)
        assertEquals("Chaotic Good", summary.alignment)
        assertEquals("Female", summary.gender)
        assertEquals(elowenOwner, summary.owner)
        assertEquals("Chaotic Good · Female", summary.subtitle)
    }

    @Test
    fun `the DM owns none of the party, so every row is badged as shared`() {
        // This is the WP5 DM feature, not a bug: the DungeonMaster account is a
        // reader and writer on every party sheet but the owner of none of them.
        assertFalse(elowen.toCharacterSummary(dmUserId).isOwnedByMe)
        assertFalse(bramwell.toCharacterSummary(dmUserId).isOwnedByMe)

        assertTrue(elowen.toCharacterSummary(elowenOwner).isOwnedByMe)
    }

    @Test
    fun `ownership is never claimed while the user id is unknown`() {
        // Before DDP login completes, userId is null. Defaulting to "mine" would
        // silently drop every badge for a second on every cold start.
        assertFalse(elowen.toCharacterSummary(null).isOwnedByMe)
    }

    @Test
    fun `absent optional fields become null rather than empty strings`() {
        val summary = bramwell.toCharacterSummary(dmUserId)

        assertNull(summary.alignment)
        assertNull(summary.picture)
        assertEquals("Male", summary.subtitle)
    }

    @Test
    fun `avatarPicture wins over picture`() {
        val both = doc(
            """{"_id":"a","owner":"o","name":"X","avatarPicture":"https://a/","picture":"https://p/"}""",
        )
        assertEquals("https://a/", both.toCharacterSummary(null).picture)

        val onlyFull = doc("""{"_id":"a","owner":"o","name":"X","picture":"https://p/"}""")
        assertEquals("https://p/", onlyFull.toCharacterSummary(null).picture)
    }

    @Test
    fun `blank strings are treated as absent`() {
        val blank = doc("""{"_id":"a","owner":"o","name":"X","alignment":"  ","picture":""}""")
        val summary = blank.toCharacterSummary(null)

        assertNull(summary.alignment)
        assertNull(summary.picture)
        assertEquals("", summary.subtitle)
    }

    @Test
    fun `a nameless creature still renders`() {
        val nameless = doc("""{"_id":"a","owner":"o"}""")
        assertEquals("Unnamed character", nameless.toCharacterSummary(null).name)
    }

    @Test
    fun `monograms ignore bracketed and parenthesised suffixes`() {
        // The regression this pins: naive "first char of the first two words" gave
        // "E[" for the archived sheets and "F(" for a parenthesised epithet.
        assertEquals("EB", elowen.toCharacterSummary(null).monogram)
        assertEquals(
            "EB",
            doc("""{"_id":"a","owner":"o","name":"Elowen Brightmantle [2014 archive]"}""")
                .toCharacterSummary(null).monogram,
        )
        assertEquals(
            "F",
            doc("""{"_id":"a","owner":"o","name":"Fenwick (Warden of the Vale)"}""")
                .toCharacterSummary(null).monogram,
        )
        assertEquals(
            "M",
            doc("""{"_id":"a","owner":"o","name":"Marwyn [2014 archive]"}""")
                .toCharacterSummary(null).monogram,
        )
        assertEquals("?", doc("""{"_id":"a","owner":"o","name":"12345"}""").toCharacterSummary(null).monogram)
    }

    // ---- FR-19: the edit capability (14 decision 18) -------------------------

    @Test
    fun `a DM named in writers may edit a character they do not own`() {
        // Decision 18: "a card is editable iff owner == me || writers.contains(me)". This is the
        // shape the live table actually has — the DungeonMaster account owns none of the party
        // and is a reader/writer on all of it — so it is also the *only* shape in which the DM
        // view's write half does anything at all.
        val summary = elowen.toCharacterSummary(dmUserId)

        assertFalse("the DM owns none of the party", summary.isOwnedByMe)
        assertEquals(listOf(dmUserId), summary.writers)
        assertTrue(summary.isEditableByMe)
    }

    @Test
    fun `a reader who is not a writer may not edit`() {
        // The capability that the DM view's toggle must not be able to override. A sheet shared
        // read-only is the common case for a player who wants the DM to *see* their character.
        val readOnly = doc(
            """
            {"_id":"c1","owner":"someone-else","name":"Sabriel",
             "readers":["$dmUserId"],"writers":[]}
            """.trimIndent(),
        ).toCharacterSummary(dmUserId)

        assertFalse(readOnly.isEditableByMe)
        assertEquals(emptyList<String>(), readOnly.writers)
    }

    @Test
    fun `the owner may always edit, writers array or not`() {
        val mine = doc("""{"_id":"c1","owner":"$dmUserId","name":"Sabriel"}""")
            .toCharacterSummary(dmUserId)

        assertTrue(mine.isOwnedByMe)
        assertTrue(mine.isEditableByMe)
    }

    @Test
    fun `an unknown user id grants nothing`() {
        // Before `login` lands there is no user id, and the fail-closed answer is the only safe
        // one: a summary that claimed an edit capability during that window would render write
        // controls on a sheet whose sharing this client has not yet been told about.
        val summary = elowen.toCharacterSummary(null)

        assertFalse(summary.isOwnedByMe)
        assertFalse(summary.isEditableByMe)
        assertEquals("the raw fact is still carried", listOf(dmUserId), summary.writers)
    }

    @Test
    fun `a malformed writers entry cannot become a matching id`() {
        // The field is absent on most creatures and this app has never written it, so what
        // arrives is whatever DiceCloud and its own clients have put on the sheet over the years.
        // Anything that is not a non-blank JSON string is dropped rather than coerced — the one
        // outcome that must not be possible is a malformed entry that some `contains` later
        // matches into an edit capability.
        val messy = doc(
            """
            {"_id":"c1","owner":"o","name":"Sabriel","writers":["",null,42,{"id":"$dmUserId"},"  "]}
            """.trimIndent(),
        ).toCharacterSummary(dmUserId)

        assertEquals(emptyList<String>(), messy.writers)
        assertFalse(messy.isEditableByMe)
    }

    @Test
    fun `a writers field that is not an array at all is not a crash`() {
        val wrongType = doc("""{"_id":"c1","owner":"o","name":"Sabriel","writers":"$dmUserId"}""")
            .toCharacterSummary(dmUserId)

        assertEquals(emptyList<String>(), wrongType.writers)
        assertFalse(wrongType.isEditableByMe)
    }
}
