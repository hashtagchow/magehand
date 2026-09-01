package com.hashtagchow.magehand.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.Hyphens

/**
 * Asks the platform to hyphenate a word too long for its line, rather than breaking it between
 * two arbitrary letters.
 *
 * **Verified working on device; the golden corpus cannot witness it. Read the last section
 * before changing any of the five call sites.**
 *
 * ### The defect it was reached for
 *
 * `docs/verification/FR-34.md` §4.1: at 320 dp × 150 % the inventory row's item name draws as
 * `Qua / rte…` — "Quarterstaff" broken between two arbitrary letters. That is BUG-4's defect class
 * (`PaneChrome`'s KDoc) on a multi-line row, and the first golden the FR-34 corpus recorded caught
 * it on its first run.
 *
 * ### Why BUG-4's own instrument does not apply here
 *
 * BUG-4's ruling is *"`softWrap = false` is what makes wrapping unrepresentable"*, and it is right
 * about a **single-line** label: a tab that fits one line or ellipsises has no legitimate reason to
 * wrap. It cannot be carried to a `maxLines = 2` row, because on such a row wrapping is the *point*
 * — "Ration Pack (1 day)" is meant to take two lines — and `softWrap = false` would throw away the
 * second line on every row to prevent a break that only happens on the rare word too long for one.
 *
 * So the **operator ruled on 2026-08-30** (a scoped exception to BUG-4's ruling, for multi-line
 * body text only): keep the two lines and hyphenate — `Quar-terstaff`, not `Qua/rte…`. An
 * unbreakable word wider than its line cannot be *prevented* from breaking; what can be decided is
 * whether the break is typographically correct. [Hyphens.Auto] is Compose's expression of that.
 *
 * ### What was measured, 2026-08-31 — it works, and no test can prove it
 *
 * **In the test environment it does nothing.** Applying this changed not one pixel of the golden
 * corpus: `recordRoborazziDebug` rewrote all 18 images and 17 came back byte-identical,
 * `InventoryRow_narrow.png` included — it still reads `Qua / rte…`. A direct probe (a `Text` laid
 * out twice, once [Hyphens.None] and once [Hyphens.Auto], read back through
 * `GetTextLayoutResult`) explains why: the two produce **identical line contents**, and no hyphen
 * character appears in either. Android's hyphenator needs its dictionaries
 * (`/system/usr/hyphen-data`), and the Robolectric graphics stack the whole FR-34 corpus renders
 * in does not supply them.
 *
 * **On hardware it works.** Architect device probe, 2026-08-31, `magehand-a30` (API 30, the sweep
 * floor): a "Quarterstaff" row at a ~160 dp-equivalent name column renders `Quar-` with a **true
 * hyphen** at the break. Evidence: `docs/verification/sweep-fr34/golden1-device-hyphenation.png`.
 * GOLDEN-1 and BUG-5 are closed on that basis.
 *
 * ### The residue, which is the part that matters to the next editor
 *
 * **No golden can regression-pin any of this.** The corpus renders identically whether hyphenation
 * works or has been silently removed, so deleting this function would keep `verifyRoborazziDebug`
 * green and quietly reintroduce a shipped defect. `InventoryRow_narrow.png` is kept as it is for
 * the same reason — it does not record an open defect any more, it records what Robolectric draws.
 *
 * The guard is therefore a device-checklist item, **L11**, and not a picture. If you change these
 * five call sites, or the theme's typography under them, that item is the only thing standing
 * between the change and `Qua / rte…` coming back.
 *
 * ### Where it belongs
 *
 * On every `Text` that draws a **name the user or their sheet typed** into a constrained
 * multi-line slot, which is where an arbitrarily long unbreakable token can arrive. Not on copy
 * this app wrote — `strings.xml` has no 14-letter words in a 90 dp column. One helper rather than
 * five `.copy(hyphens = …)` calls with five comments, for `mageHandIconButtonColors`' reason: this
 * is one ruling, and five copies of it is how two of them end up not being it.
 */
fun TextStyle.hyphenated(): TextStyle = copy(hyphens = Hyphens.Auto)
