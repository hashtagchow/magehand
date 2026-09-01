package com.hashtagchow.magehand.ui.scale

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.theme.MageHandTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * FR-18's provider, **measured in a real composition** — the behavioural half of what
 * `UiScaleProviderTest` could only read out of the source
 * (docs/design/19-ui-test-infrastructure.md decision 5: *"UiScaleProviderTest's 'applied exactly
 * once' source-scan half → behavioural: nested providers rendered, effective density measured
 * once"*).
 *
 * ### The defect, restated because it is the whole reason this file exists
 *
 * `ProvideUiScale` reads its base density on the line *above* `CompositionLocalProvider`. Move
 * that read inside — or nest a second provider under the first — and every recomposition
 * re-scales an already-scaled density: 1.25, then 1.5625, then 1.953. Nothing crashes. The app
 * simply grows every time something recomposes, which on a screen with a live DDP feed is
 * continuously, and the bug report reads *"the app slowly zooms in"*, which is nobody's first
 * guess about a settings toggle.
 *
 * `UiScaleProviderTest` pins the arithmetic (`scaledDensity(scaledDensity(d, s), s)` compounds)
 * and the whole-app structure (exactly one file provides `LocalDensity`, mounted at the activity
 * root). Neither can show the thing that actually goes wrong, which is *time*: a composition that
 * is correct on its first frame and wrong on its tenth. That needs a composition and a clock, and
 * this is what having one buys.
 *
 * The two files are deliberately not merged. This one needs Robolectric and a Compose runtime; the
 * arithmetic and the source scans do not, and making them pay for a harness they have no use for
 * would slow the fastest tests in the module for nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiScaleProviderRenderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the effective density is the system's, times the chosen factor, exactly once`() {
        var base = 0f
        var scaled = 0f

        compose.setContent {
            // Outside the provider: whatever the platform handed the Activity. Inside: what every
            // screen in the app measures at.
            base = LocalDensity.current.density
            ProvideUiScale(UiScale.LARGE_125) {
                scaled = LocalDensity.current.density
            }
        }

        compose.runOnIdle {
            assertEquals(base * 1.25f, scaled, 1e-4f)
        }
    }

    /**
     * **The regression**: recomposing the provider must not re-scale what it already scaled.
     *
     * The state is read *above* `ProvideUiScale`, so a change recomposes the provider itself and
     * not merely its content — which is the shape a real recomposition takes on this app's root,
     * where the scale flows out of a `collectAsStateWithLifecycle` above the same call.
     *
     * Ten frames, one density. A provider that read its base inside itself would produce ten
     * different densities here, each 1.25× the last, and the assertion would name the first
     * divergence.
     */
    @Test
    fun `ten recompositions of the provider leave the density where it started`() {
        var tick by mutableStateOf(0)
        val seen = mutableListOf<Float>()

        compose.setContent {
            val frame = tick
            ProvideUiScale(UiScale.LARGE_125) {
                val density = LocalDensity.current.density
                SideEffect { seen += density }
                // Something for the frame to be about, so the content genuinely recomposes.
                Text("frame $frame")
            }
        }

        repeat(10) { compose.runOnIdle { tick++ } }

        compose.runOnIdle {
            // Both assertions matter (FR-34 review finding 5): distinct==1 alone cannot tell ten
            // identical frames from one frame — if the content lambda ever becomes skippable, the
            // SideEffect fires once and the compounding this test exists for goes unobserved.
            assertEquals(
                "expected one density observation per frame — initial + 10 ticks (seen: $seen)",
                11,
                seen.size,
            )
            assertEquals(
                "the density must be identical on every frame — a provider that re-read its own " +
                    "scaled base compounds the factor once per recomposition, and the app grows " +
                    "without bound (seen: $seen)",
                1,
                seen.distinct().size,
            )
        }
    }

    /**
     * **Q1a**, converted: every step the Settings pane offers applies, and `Default` restores the
     * system's density *exactly* (docs/verification/FR-34-checklist-map.md, area Q).
     *
     * The map's seam for this row is "extend `UiScaleProviderRenderTest`", and the reason it is
     * worth extending rather than leaving to `UiScaleProviderTest`'s arithmetic is the same reason
     * the first test here exists: the arithmetic is a claim about a function, and this is a claim
     * about what a composition measures at. They agree today; a provider that quietly stopped
     * passing its factor through would only break one of them.
     *
     * `Default` is asserted with a **zero delta**, not an epsilon. Decision 1's claim is that the
     * step is neutral by construction (its factor is exactly `1.0f`), so `base * 1.0f` is
     * bit-identical to `base` in IEEE 754 — and a `Default` that merely came *close* to the
     * system density would be an app that could never quite be turned back off.
     */
    @Test
    fun `every step measures at the system density times its own factor, and Default is identity`() {
        val measured = mutableMapOf<UiScale, Float>()
        var base = 0f

        compose.setContent {
            base = LocalDensity.current.density
            UiScale.entries.forEach { scale ->
                ProvideUiScale(scale) {
                    measured[scale] = LocalDensity.current.density
                }
            }
        }

        compose.runOnIdle {
            assertEquals(
                "every step the user can choose must be measured",
                UiScale.entries.size,
                measured.size,
            )
            assertEquals(
                "Default must return the system's own density unchanged, exactly",
                base,
                measured.getValue(UiScale.DEFAULT),
                0f,
            )
            UiScale.entries.forEach { scale ->
                assertEquals(
                    "${scale.key} must measure at base × ${scale.factor}",
                    base * scale.factor,
                    measured.getValue(scale),
                    1e-4f,
                )
            }
            // Distinct steps must actually differ on screen; a factor table that collapsed two
            // steps onto one density would satisfy every assertion above.
            assertEquals(
                "no two steps may measure the same (measured: $measured)",
                UiScale.entries.size,
                measured.values.distinct().size,
            )
        }
    }

    /**
     * **Q3**, converted — and the row's stated conversion is **wrong**, which is why this test
     * asserts something else and says so here.
     *
     * ### The correction
     *
     * `FR-34-checklist-map.md` proposes asserting that "the **first** composition pass already
     * reflects the scaled density", calling it "stronger than the original eyeball check — proves
     * there is no unscaled frame at all". That test would fail against shipped code, and it should:
     * `MainViewModel.uiScale` is `stateIn(..., SharingStarted.Eagerly, UiScale.DEFAULT)`, whose
     * KDoc states the trade outright — *"A user at 150% therefore sees one un-scaled frame on a
     * cold start — the honest cost of not blocking the first frame on a disk read."*
     *
     * `docs/DEVICE-CHECKLIST.md`'s Q3 agrees with the code and not with the map: *"the **known
     * single** un-scaled first frame is momentary and non-jarring … regression bar is 'no flash of
     * tiny UI longer than a beat'"*. So the map restated a deliberate design decision as a defect.
     * Writing its version would have produced a red test whose obvious "fix" is to block the first
     * frame on a disk read — undoing decision 2's live-not-restart rule to satisfy a checklist row
     * that never asked for it.
     *
     * ### What is pinned instead
     *
     * The contract as designed: the cold-start sequence is **exactly one** default frame, and the
     * stored scale lands on the very next one. That is a real regression guard — it fails if the
     * seed stops being `DEFAULT`, and it fails if the provider ever stops re-measuring when the
     * store emits (a `SharingStarted.Lazily`, a scale read once outside the composition), which is
     * the failure mode that would turn "one brief frame" into "the setting does nothing until
     * relaunch".
     *
     * What no JVM test can answer is the eyeball half — whether one frame at 60 Hz reads as a
     * flash to a human. That stays a device item, and the checklist row keeps it.
     */
    @Test
    fun `cold start is exactly one default frame, then the stored scale`() {
        // The root's shape: the scale is state read *above* the provider, which is what
        // `collectAsStateWithLifecycle` is in `MainActivity`.
        var scale by mutableStateOf(UiScale.DEFAULT)
        val frames = mutableListOf<Float>()
        var base = 0f

        compose.setContent {
            base = LocalDensity.current.density
            ProvideUiScale(scale) {
                val density = LocalDensity.current.density
                SideEffect { frames += density }
                Text("scale ${scale.key}")
            }
        }

        compose.runOnIdle {
            assertEquals("the seeded frame is the only one so far", 1, frames.size)
            assertEquals(
                "the seed is DEFAULT, so the first frame is the system's own density",
                base,
                frames.single(),
                0f,
            )
        }

        // The store's first real emission, a frame later.
        compose.runOnIdle { scale = UiScale.LARGE_150 }

        compose.runOnIdle {
            assertEquals(
                "the store's emission must produce a second frame (frames: $frames)",
                2,
                frames.size,
            )
            assertEquals(
                "and that frame must be scaled — if it is not, the setting does nothing until " +
                    "the app is relaunched, which is the failure decision 2 forbids",
                base * UiScale.LARGE_150.factor,
                frames.last(),
                1e-4f,
            )
        }
    }

    /**
     * Re-entering the theme does not re-enter the scale.
     *
     * `CharacterHomeScreen` calls `MageHandTheme` a second time to apply a per-character accent
     * colour, which is the shipped composition this rule exists for — and the reason
     * `ProvideUiScale` sits *above* the theme rather than inside it. A provider placed in the
     * theme would be entered twice on that one screen, so the character screen alone would render
     * 1.5625× while every other screen rendered 1.25×.
     */
    @Test
    fun `a nested theme, as the character screen does it, does not compound the scale`() {
        var outer = 0f
        var inner = 0f

        compose.setContent {
            ProvideUiScale(UiScale.LARGE_125) {
                MageHandTheme(darkTheme = false) {
                    outer = LocalDensity.current.density
                    MageHandTheme(darkTheme = false, accentColor = "#8E24AA") {
                        inner = LocalDensity.current.density
                    }
                }
            }
        }

        compose.runOnIdle { assertEquals(outer, inner, 0f) }
    }
}
