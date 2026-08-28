package com.hashtagchow.magehand.core.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.hashtagchow.magehand.core.data.db.MageHandDatabase
import com.hashtagchow.magehand.core.data.fake.FakeDiceCloudApi
import com.hashtagchow.magehand.core.data.snapshot.SnapshotStore
import com.hashtagchow.magehand.core.data.tracker.CreatureSheet
import com.hashtagchow.magehand.core.data.write.FakeDdpMethodCaller
import com.hashtagchow.magehand.core.model.ConcentrationPrompt

/**
 * FR-31's concentration prompt (docs/design/18-table-pack.md decisions 9–12).
 *
 * ### The one assertion this file exists for
 *
 * Decision 9: *"Never reactive to observed damage (the observer-storm rule)."* Everything else
 * here is arithmetic and plumbing; `another client's damage prompts nothing` is the test that
 * would still be worth writing if all the others were deleted, because the implementation it
 * forbids — a collector on `board.hp` — is simpler, is what somebody will eventually reach for,
 * and produces a visibly working feature on a single-client desk test. It fails only at a table,
 * on six screens at once, which is precisely where nobody is running the suite.
 *
 * It is FR-23 decision 20's pin with a different subject, and the two now guard the same rule from
 * both ends: a heal must clear death saves only on the client that healed, and damage must prompt
 * only on the client that damaged.
 *
 * ### Synthetic sheets, not the capture
 *
 * [DefaultOpenCharacterWriteTest]'s reason, unchanged: the committed capture is absent from a
 * public clone, so every assertion against it *skips* there. A rule this load-bearing has to run
 * everywhere. The harness is that file's, narrowed to what a prompt needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConcentrationPromptTest {

    private val accountId = "acc-1"
    private val creatureId = "c1"

    private lateinit var database: MageHandDatabase
    private lateinit var snapshots: SnapshotStore
    private val scopes = mutableListOf<CoroutineScope>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, MageHandDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        snapshots = SnapshotStore(
            snapshotDao = database.snapshotDao(),
            api = FakeDiceCloudApi(),
            ioDispatcher = Dispatchers.Unconfined,
            now = { 1_700_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        database.close()
    }

    private class Harness(
        val character: DefaultOpenCharacter,
        val session: CreatureSession,
        val feed: FakeCreatureFeed,
        val caller: FakeDdpMethodCaller,
        /** Every prompt this session emitted, in order. Collected from the moment of creation. */
        val prompts: List<ConcentrationPrompt>,
    )

    /**
     * A live session over a synthetic sheet, with a collector already attached.
     *
     * The collector is started **before** any write, because the flow is a `SharedFlow` with no
     * replay — which is the interface's own contract (*"what just happened"*, not *"what is
     * true"*). A test that subscribed afterwards would see nothing and would pass for the wrong
     * reason.
     */
    private fun TestScope.harness(vararg properties: String): Harness {
        val caller = FakeDdpMethodCaller(nowMillis = testScheduler::currentTime)
        val feed = FakeCreatureFeed(caller)
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()).also { scopes += it }
        val session = CreatureSession(
            accountId = accountId,
            creatureId = creatureId,
            feed = feed,
            snapshotStore = snapshots,
            trackerPrefDao = database.trackerPrefDao(),
            scope = scope,
        )
        feed.publish(sheetOf(*properties), creatureId)
        feed.goLive()
        val character = DefaultOpenCharacter(
            session = session,
            scope = scope,
            serverOrigin = "https://example.invalid",
            themePrefDao = database.themePrefDao(),
            trackerPrefDao = database.trackerPrefDao(),
        )
        val prompts = mutableListOf<ConcentrationPrompt>()
        scope.launch { character.concentrationPrompts.collect { prompts += it } }
        advanceUntilIdle()
        return Harness(character, session, feed, caller, prompts)
    }

    private fun sheetOf(vararg properties: String): CreatureSheet =
        CreatureSheet.fromSnapshotJson(
            """{"creatures":[{"_id":"$creatureId","name":"Scratch"}],
               "creatureProperties":[${properties.joinToString(",")}],
               "creatureVariables":[{"_id":"v1"}]}""",
        )

    private fun hp(current: Int, total: Int = 40) =
        """{"_id":"hp1","type":"attribute","attributeType":"healthBar",
            "variableName":"hitPoints","name":"Hit Points","total":$total,"value":$current}"""

    /**
     * A **flippable** concentration source: a `toggle` carrying `enabled`, which is the server's
     * own precondition for `flipToggle` (WP7 read it out of the running bundle).
     */
    private fun concentrationToggle() =
        """{"_id":"tog1","type":"toggle","name":"Concentration: Bless","enabled":true,
            "order":1}"""

    /**
     * The same toggle, switched **off** — `inactive: true`, which is what "off" is on the wire.
     *
     * Deliberately keeps `enabled: true` beside it, because those two fields are not opposites:
     * the presence of the `enabled` key is what makes the toggle *manual*, and `inactive` is what
     * makes it *off*. See the test that uses this.
     */
    private fun inactiveConcentrationToggle() =
        """{"_id":"tog1","type":"toggle","name":"Concentration: Bless","enabled":true,
            "inactive":true,"order":1}"""

    /**
     * A **buff**-sourced concentration source, which 03 §5 permits and `flipToggle` refuses.
     *
     * The case decision 10 calls out: the prompt is informational because there is no method that
     * ends a buff. Carries no `enabled`/`disabled`, which is what makes it non-flippable.
     */
    private fun concentrationBuff() =
        """{"_id":"buf1","type":"buff","name":"Concentration: Web","order":1}"""

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    /** Advances past the settle window so a pending prompt is emitted. */
    private fun TestScope.settle() {
        advanceTimeBy(1_000)
        advanceUntilIdle()
    }

    // --- decision 9: OUR write, and only ours -------------------------------

    @Test
    fun `our own damage write on a concentrating character prompts once`() = runTest {
        val h = harness(hp(current = 40), concentrationToggle())

        h.character.changeHitPoints(-24)
        settle()

        val prompt = h.prompts.single()
        assertEquals("Concentration: Bless", prompt.sourceName)
        assertEquals(24, prompt.damage)
        // 5e: half the damage, minimum 10. 24 → 12.
        assertEquals(12, prompt.dc)
        // Decision 10's one action, resolved through `TrackerBoard.concentrationToggle` — the same
        // rule the tracker banner's ✕ reads, which is why they cannot disagree.
        assertEquals("tog1", prompt.toggleId)
        assertTrue(prompt.canDrop)
    }

    /**
     * **The pin (decision 9), and the twin of FR-23 decision 20's.**
     *
     * Another client damages a character this session is merely *watching*: the mirror re-publishes
     * the HP property with a lower value, the board moves, and **nothing** is emitted. Not a
     * quieter prompt, not a deduplicated one — none.
     *
     * The failure mode this prevents is invisible on one screen and unmissable at a table: a party
     * of six with the DM dashboard open is six sessions observing the same sheets, so a
     * board-derived prompt would put the same check on every screen for one hit, on five of which
     * nobody pressed anything.
     *
     * The board really does move — asserted, so a future change that stops the fixture from
     * exercising the transition at all fails here rather than passing vacuously.
     */
    @Test
    fun `another client's damage on a concentrating character prompts nothing`() = runTest {
        val h = harness(hp(current = 40), concentrationToggle())
        assertEquals(40, h.session.board.value.hp?.value)

        h.feed.changeProperty("hp1", parse(hp(current = 8)))
        settle()

        assertEquals(8, h.session.board.value.hp?.value)
        assertTrue(
            "observed damage must never prompt — see decision 9's observer-storm rule",
            h.prompts.isEmpty(),
        )
    }

    @Test
    fun `a heal prompts nothing`() = runTest {
        val h = harness(hp(current = 10), concentrationToggle())

        h.character.changeHitPoints(+15)
        settle()

        assertTrue(h.prompts.isEmpty())
    }

    @Test
    fun `a character who is not concentrating is never prompted`() = runTest {
        val h = harness(hp(current = 40))

        h.character.changeHitPoints(-30)
        settle()

        assertTrue(h.prompts.isEmpty())
    }

    /**
     * A **switched-off** concentration toggle is not a concentration source, so there is no check.
     *
     * The gate reads `TrackerBoard.concentratingOn` — the board's own answer — rather than "does
     * the sheet mention concentration anywhere". `TrackerEngine.concentrationSource` drops a
     * property that is `inactive`, so a character whose Bless has been switched off is simply not
     * concentrating and takes damage like anybody else.
     *
     * Note which field says "off". `inactive` does; the `enabled` **key** does not — WP7 read the
     * server's own precondition and found that carrying `enabled`/`disabled` at all is what makes
     * a toggle *manual*, while `inactive` is what makes it *off* (see `TrackerEngine.toggle`,
     * where `enabled` is derived from `inactive` and never from the key of the same name). A
     * fixture using `"enabled": false` to mean "switched off" would be testing a reading of the
     * wire that this app deliberately does not have.
     */
    @Test
    fun `a switched-off concentration toggle is not a source and prompts nothing`() = runTest {
        val h = harness(hp(current = 40), inactiveConcentrationToggle())

        assertNull(h.session.board.value.concentratingOn)
        h.character.changeHitPoints(-26)
        settle()

        assertTrue(h.prompts.isEmpty())
    }

    // --- decision 10: the drop action, and when it is absent ----------------

    /**
     * A buff-sourced banner gives an **informational** prompt (decision 10's *"if not cheaply
     * writable … recorded honestly"*).
     *
     * `flipToggle` refuses anything that is not a toggle, and DiceCloud publishes no method that
     * ends a buff — so a Drop button here would promise a write this app cannot make. The prompt
     * still fires, because the *check* is owed regardless of whether the app can end the spell.
     */
    @Test
    fun `a buff-sourced concentration prompts with no drop action`() = runTest {
        val h = harness(hp(current = 40), concentrationBuff())

        h.character.changeHitPoints(-13)
        settle()

        val prompt = h.prompts.single()
        assertEquals("Concentration: Web", prompt.sourceName)
        assertEquals(13, prompt.damage)
        assertEquals("half of 13, floored, is 6 — under the floor, so 10", 10, prompt.dc)
        assertNull("no method ends a buff, so there is nothing to offer", prompt.toggleId)
        assertEquals(false, prompt.canDrop)
    }

    // --- decision 11: one op = one prompt, largest in a window ---------------

    /**
     * Decision 11, both clauses.
     *
     * A burst of stepper taps inside one settle window produces **one** prompt, and its DC is the
     * **largest single op**'s — not the sum, and not one prompt per tap. The design states the
     * residual honestly and this test inherits it: per-hit granularity under stepper spam is
     * unknowable client-side, and what the app can be exact about is the direct-entry path below.
     */
    @Test
    fun `a burst inside one settle window produces one prompt at the largest op`() = runTest {
        val h = harness(hp(current = 60, total = 60), concentrationToggle())

        h.character.changeHitPoints(-4)
        advanceTimeBy(100)
        h.character.changeHitPoints(-30)
        advanceTimeBy(100)
        h.character.changeHitPoints(-9)
        settle()

        val prompt = h.prompts.single()
        assertEquals("the largest single op, not the sum (43) and not the last (9)", 30, prompt.damage)
        assertEquals(15, prompt.dc)
    }

    /** Two bursts far enough apart are two separate checks, and both are owed. */
    @Test
    fun `two ops in separate windows produce two prompts`() = runTest {
        val h = harness(hp(current = 60, total = 60), concentrationToggle())

        h.character.changeHitPoints(-4)
        settle()
        h.character.changeHitPoints(-22)
        settle()

        assertEquals(listOf(4, 22), h.prompts.map { it.damage })
        assertEquals(listOf(10, 11), h.prompts.map { it.dc })
        // Two events, not one state: the ids differ, which is what stops a UI collapsing them.
        assertEquals(2, h.prompts.map { it.id }.toSet().size)
    }

    /**
     * FR-22's direct entry is decision 11's *exact* path: one op, one known drop.
     *
     * The damage is the difference the write actually applies, not the number typed — setting 40
     * to 12 is 28 damage, and the DC has to be 14 rather than 10-because-12-is-small or
     * 20-because-40-was-the-old-value.
     */
    @Test
    fun `setHitPoints downward prompts on the drop, and upward prompts nothing`() = runTest {
        val h = harness(hp(current = 40), concentrationToggle())

        h.character.setHitPoints(12)
        settle()

        assertEquals(28, h.prompts.single().damage)
        assertEquals(14, h.prompts.single().dc)

        // The server's echo. `FakeDdpMethodCaller` acknowledges the call but does not mutate the
        // mirror, so without this the optimistic overlay drops on resolution and the board springs
        // back to 40 — at which point the *next* set would read as a drop rather than as the heal
        // it is. Publishing the new value is what a real `changed` frame does, and the assertion
        // below is meaningless without it.
        h.feed.changeProperty("hp1", parse(hp(current = 12)))
        advanceUntilIdle()
        assertEquals(12, h.session.board.value.hp?.value)

        h.character.setHitPoints(30)
        settle()

        assertEquals("a set that raises the number is a heal", 1, h.prompts.size)
    }

    /**
     * Concentration ending inside the settle window cancels the prompt.
     *
     * Not an edge case dressed up: the window exists precisely so the app can wait a beat, and a
     * beat is long enough for the player to tap the banner's ✕, or for another client to end the
     * spell. Prompting for a check on a spell that is over would be the app telling a table to
     * roll for nothing.
     */
    @Test
    fun `concentration ending during the settle window cancels the prompt`() = runTest {
        val h = harness(hp(current = 40), concentrationToggle())

        h.character.changeHitPoints(-20)
        advanceTimeBy(100)
        // The source leaves the sheet — a soft-remove, which `concentrationSource` filters on.
        h.feed.changeProperty(
            "tog1",
            parse("""{"_id":"tog1","type":"toggle","name":"Concentration: Bless","enabled":true,"removed":true}"""),
        )
        settle()

        assertTrue(h.prompts.isEmpty())
    }

    // --- decision 12 / the DC rule, as arithmetic ---------------------------

    /**
     * The DC rule on its own, at the boundaries — *"half of D, min 10"*.
     *
     * A pure test beside the plumbing ones because the rule is the thing a table will argue about,
     * and because the floor is where an off-by-one lives: 20 damage is DC 10 by *both* halves of
     * the rule, 21 is DC 10 by the floor alone, and 22 is the first value where halving wins.
     */
    @Test
    fun `the DC is half the damage, floored, with a minimum of ten`() {
        fun dc(damage: Int) = ConcentrationPrompt(id = 1, sourceName = "x", damage = damage).dc

        assertEquals(10, dc(1))
        assertEquals(10, dc(19))
        assertEquals(10, dc(20))
        assertEquals("21 halves to 10 with the remainder dropped, which the floor also gives", 10, dc(21))
        assertEquals("the first damage where halving beats the floor", 11, dc(22))
        assertEquals(25, dc(50))
        assertEquals("odd values floor, they do not round", 25, dc(51))
    }

    /**
     * The write itself is untouched by any of this.
     *
     * FR-31 adds **no** intent and **no** method — decision 9's trigger hangs off writes that
     * already existed, and its one action is the existing `toggle`. So a prompted damage write
     * still sends exactly the call it sent before, which is what keeps `WritePostureTest`'s
     * catalog unedited for this feature.
     */
    /**
     * The write itself is untouched by any of this.
     *
     * FR-31 adds **no** intent and **no** method — decision 9's trigger hangs off writes that
     * already existed, and its one action is the existing `toggle`. So a prompted damage write
     * sends exactly the one call it sent before, and the prompt produces no traffic of its own.
     * That is what keeps `WritePostureTest`'s catalog unedited for this feature, checked here from
     * the wire rather than inferred from the catalog not having changed.
     */
    @Test
    fun `prompting sends no call of its own`() = runTest {
        val h = harness(hp(current = 40), concentrationToggle())

        h.character.changeHitPoints(-24)
        settle()

        assertEquals(1, h.prompts.size)
        assertEquals(
            listOf("creatureProperties.damage"),
            h.caller.calls.map { it.method },
        )
    }

    /**
     * L-batch [architect ruling]: the clamp does NOT matter here, and that is the point — it used
     * to. `setHitPoints` floors the WRITE at 0 (the row can never read negative), but the PROMPT
     * reports the raw entry: a set of −5 off 30 is a 35-point hit for DC purposes, RAW's "total
     * damage dealt to you" rather than the lesser amount that actually moved the stat. See
     * `DefaultOpenCharacter.setHitPoints`'s KDoc for the ruling this pins.
     */
    @Test
    fun `an unlimited direct entry to zero prompts on the raw drop, not the clamped one`() = runTest {
        val h = harness(hp(current = 30), concentrationToggle())

        h.character.setHitPoints(-5)
        settle()

        assertEquals(35, h.prompts.single().damage)
        assertEquals(17, h.prompts.single().dc)
    }

    /**
     * L-batch: the two HP paths are pinned to agree. A pad damage tap for the same raw amount —
     * hp 30, take 35 — reports the identical [ConcentrationPrompt.damage] a `setHitPoints(-5)`
     * does above, because both now read the same input: the amount the player entered, not the
     * amount the row had room to absorb.
     */
    @Test
    fun `changeHitPoints and setHitPoints agree on the same raw damage`() = runTest {
        val viaSet = harness(hp(current = 30), concentrationToggle())
        viaSet.character.setHitPoints(-5)
        settle()

        val viaDelta = harness(hp(current = 30), concentrationToggle())
        viaDelta.character.changeHitPoints(-35)
        settle()

        assertEquals(viaDelta.prompts.single().damage, viaSet.prompts.single().damage)
    }
}
