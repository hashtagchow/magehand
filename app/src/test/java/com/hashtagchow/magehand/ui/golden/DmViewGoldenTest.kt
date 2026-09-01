package com.hashtagchow.magehand.ui.golden

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.HpState
import com.hashtagchow.magehand.ui.screens.dmview.DmCardAvailability
import com.hashtagchow.magehand.ui.screens.dmview.DmCardGrid
import com.hashtagchow.magehand.ui.screens.dmview.DmCardUiState
import com.hashtagchow.magehand.ui.testing.Sabriel
import com.hashtagchow.magehand.ui.testing.captureGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **Q13**: the DM view's six-card grid, photographed at 100 % and 150 % on a landscape tablet
 * (docs/verification/FR-34-checklist-map.md, area Q).
 *
 * ### What the two pictures claim
 *
 * `DmViewSelectionTest` pins `dmGridColumns` as arithmetic; what no arithmetic test can see is
 * the *board*: whether four columns of cards actually share the width without a card clipping its
 * HP bar, and whether FR-18's density factor moving the column count (1280 dp reads as ~853
 * scaled dp at 150 %, so four columns become two) produces a sane layout rather than a correct
 * number rendered badly. The checklist item is "column count sane at 100 % and 150 %", and sane
 * is a judgement a human makes about a picture — which is what a golden is for.
 *
 * The grid is photographed bare — `DmCardGrid` was made `internal` for exactly this test —
 * because `DmViewScreen` defaults its view model to `hiltViewModel()` wired to live
 * subscriptions, and the scaffold chrome above the grid is the same `TopAppBar` every other
 * golden already carries. The grid *is* the geometry Q13 names.
 *
 * ### The fixture board
 *
 * Six cards, because six is `DM_VIEW_MAX_MEMBERS` and the fullest board is the one whose
 * geometry can break. The mix is deliberate: write controls on and off (the two card heights),
 * one concentration banner (the tallest card), and one NOT_AVAILABLE card — which also carries
 * the pixel half of `DmCardRenderTest`'s Q15 claim: the semantics there prove the unavailable
 * card *speaks* no tracker, and this picture is where a human sees it *draws* none. Names are
 * invented (store safety, design 19 decision 9); Sabriel's numbers seed the one card that
 * mirrors a real capture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DmViewGoldenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `six cards on a landscape tablet at 100 percent`() = capture("DmViewGrid_100")

    @Test
    fun `six cards on a landscape tablet at 150 percent`() =
        capture("DmViewGrid_150", scale = UiScale.LARGE_150)

    private fun capture(name: String, scale: UiScale = UiScale.DEFAULT) =
        compose.captureGolden(name, scale = scale) {
            DmCardGrid(
                cards = sixCards,
                onCardClick = {},
                onSpend = { _, _, _ -> },
                onRestore = { _, _, _ -> },
                onChangeHitPoints = { _, _ -> },
                onToggleCondition = { _, _ -> },
            )
        }

    private val sixCards = listOf(
        card("c1", "Sabriel", hp = 11 to 17, writable = true, concentratingOn = "Bless"),
        card("c2", "Brindlewick", hp = 24 to 31, writable = true),
        card("c3", "Okkra", hp = 8 to 44, writable = false),
        card("c4", "Fennimore", hp = 30 to 30, writable = false),
        card("c5", "Vantis", hp = 17 to 26, writable = true),
        card("c6", "Quill", hp = 0 to 0, writable = true, availability = DmCardAvailability.NOT_AVAILABLE),
    )

    private fun card(
        id: String,
        name: String,
        hp: Pair<Int, Int>,
        writable: Boolean,
        concentratingOn: String? = null,
        availability: DmCardAvailability = DmCardAvailability.AVAILABLE,
    ) = DmCardUiState(
        creatureId = id,
        name = name,
        monogram = name.take(1),
        availability = availability,
        hp = HpState(propertyId = "hp-$id", current = hp.first, max = hp.second, tempHp = 0),
        slots = listOf(Sabriel.firstLevel, Sabriel.secondLevel),
        concentratingOn = concentratingOn,
        showsWriteControls = writable,
        writeControlsEnabled = writable,
        grantedEditing = writable,
    )
}
