package com.hashtagchow.magehand.ui.golden

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.local.LocalTrackerBoard
import com.hashtagchow.magehand.core.model.AbilityScores
import com.hashtagchow.magehand.core.model.ConnectionState
import com.hashtagchow.magehand.core.model.LocalCharacter
import com.hashtagchow.magehand.core.model.LocalRowKind
import com.hashtagchow.magehand.core.model.LocalTrackerRow
import com.hashtagchow.magehand.core.model.ResetRule
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.TrackerTab
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.toTrackerUiState
import com.hashtagchow.magehand.ui.screens.local.LocalReferenceState
import com.hashtagchow.magehand.ui.screens.local.ReferenceStrip
import com.hashtagchow.magehand.ui.testing.captureGolden
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **A local character's tracker, with its armour class on both surfaces** — the 1.18.0 review's
 * LOW-6.
 *
 * ### What this picture is for
 *
 * FR-50 R4 rules two things at once: `LocalCharacter.armorClass` maps onto the same board field a
 * DiceCloud `armor` attribute lands on, *and* the reference strip above the tabs keeps its own AC
 * cell (09 decision 6 — the strip is reference for both tabs, not a tracker row). The consequence
 * is that a local character prints its AC **twice** on this one screen, which the build accepted
 * and recorded as the cost of the block being the surface.
 *
 * Accepted is not the same as looked at. `LocalTrackerBoardTest` covers the mapping and
 * `TrackerTabRenderTest` covers the badge, and neither can see the thing a reader would actually
 * judge: whether "AC 17" in the strip and "AC 17" on the block read as one fact stated twice or as
 * two facts that might disagree. That is a question about a layout, so it gets a picture — and it
 * is the one screen in the app where the duplication exists, so before this file nobody had seen
 * it.
 *
 * ### The composition
 *
 * The strip and the tab, in the order and with the surfaces the real screen stacks them in, built
 * through the **production** pipeline — `LocalTrackerBoard.build` → `toTrackerUiState`, and
 * `LocalReferenceState.from` — rather than by filling two UI states in by hand. Going through the
 * real functions costs nothing and buys the guarantee that matters for a golden about a
 * duplicated number: both AC strings are the ones the app computes, from one `LocalCharacter`, so
 * a picture where they disagreed would be a real defect rather than a fixture typo.
 *
 * `LocalCharacterHomeScreen` itself cannot be photographed — it defaults its view model to
 * `hiltViewModel()` wired to a database — which is why `ReferenceStrip` is `internal`, exactly as
 * `DmCardGrid` is for `DmViewGoldenTest`. What is left out is the app bar and the tab row, both of
 * which have goldens of their own (`HomeAppBar_*`, `HomeTabRow_*`).
 *
 * ### Why 800 dp and not the corpus's usual 411 dp phone
 *
 * Because at 411 dp **the two numbers are never on screen together**, which this file found by
 * being recorded there first: the strip is a `horizontalScroll` row and its AC cell is last, so on
 * a phone it sits past the right edge behind "CHA" and the player has to drag the strip to reach
 * it. That is worth knowing and is recorded in the wave's handover — it is quiet evidence for
 * decision 23's "the block is the surface", since the block's badge is the only AC a phone user
 * actually sees — but it makes a phone capture a picture of the duplication *not* happening,
 * which is not the thing R4 needs looked at. 800 dp is the narrowest common width where the whole
 * strip fits, so this is the duplication as ruled, with nothing hidden.
 *
 * ### Store safety (design 19 decision 9)
 *
 * An invented character name, invented scores, no host, no token, no party identifier.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalTrackerGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a local character's reference strip and HP block both show the armour class`() {
        val reference = requireNotNull(LocalReferenceState.from(character))
        val tracker = toTrackerUiState(
            creatureId = character.id,
            board = LocalTrackerBoard.build(character, rows),
            connection = ConnectionState.LIVE,
            lastSyncedAt = null,
            isShowingSnapshot = false,
            zone = ZoneId.of("UTC"),
            canWrite = true,
        )

        compose.captureGolden("LocalTrackerScreen_ac") {
            Column {
                ReferenceStrip(state = reference)
                TrackerTab(state = tracker)
            }
        }
    }

    /**
     * AC 17 rather than the form's default, so the number in the picture is one somebody chose —
     * a default would be the one value a mapping bug could produce by accident.
     *
     * A score of 7 is in the set on purpose: `abilityModifier`'s `floorDiv` reads it as −2 rather
     * than −1, and the strip is where a reader would see that go wrong.
     */
    private val character = LocalCharacter(
        id = "local-golden",
        name = "Brambles",
        level = 5,
        abilities = AbilityScores(
            strength = 7,
            dexterity = 16,
            constitution = 14,
            intelligence = 12,
            wisdom = 15,
            charisma = 10,
        ),
        maxHp = 34,
        currentHp = 21,
        armorClass = 17,
        createdAt = 1,
        updatedAt = 1,
    )

    /** One slot row and one resource row, so the block is not the only thing on the tab. */
    private val rows = listOf(
        LocalTrackerRow(
            id = "row-slot",
            characterId = character.id,
            kind = LocalRowKind.SLOT,
            label = "1st Level",
            total = 4,
            current = 3,
            reset = ResetRule.LONG_REST,
            sortIndex = 0,
        ),
        LocalTrackerRow(
            id = "row-resource",
            characterId = character.id,
            kind = LocalRowKind.RESOURCE,
            label = "Bardic Inspiration",
            total = 3,
            current = 1,
            reset = ResetRule.SHORT_REST,
            sortIndex = 1,
        ),
    )
}
