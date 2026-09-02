package com.hashtagchow.magehand.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.data.settings.UiScale
import com.hashtagchow.magehand.ui.components.RadioRow
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.MINUS
import com.hashtagchow.magehand.ui.screens.characterhome.tracker.PLUS
import com.hashtagchow.magehand.ui.components.screenContentWindowInsets

/**
 * The inset every row of this screen sits in.
 *
 * `internal` and named rather than repeated as a literal because
 * `SettingsUiScaleGoldenTest` captures the UI-size stepper **alone** and has to give it exactly
 * the room the screen does. That control's failure mode is running out of width — a bare capture
 * gets the whole device and fits where the real screen clips, which is precisely what happened on
 * this feature's first recording. Sharing the value means a change to the screen's padding moves
 * the goldens with it instead of silently invalidating them.
 */
internal val SETTINGS_HORIZONTAL_PADDING = 24.dp

/**
 * Screen 6 — settings and accounts (docs/design/04-screens-ux.md §6).
 *
 * WP5 delivers the multi-server account switcher and sign-out. Per-character
 * accent colour, portrait override and "about" are WP8.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingSignOutOf by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = screenContentWindowInsets,
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SETTINGS_HORIZONTAL_PADDING, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_accounts),
                style = MaterialTheme.typography.titleMedium,
            )

            uiState.accounts.forEach { account ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioRow(
                        selected = account.id == uiState.activeAccountId,
                        title = account.username,
                        subtitle = account.serverUrl.removePrefix("https://"),
                        onClick = { viewModel.switchTo(account.id) },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { confirmingSignOutOf = account.id }) {
                        Text(stringResource(R.string.action_sign_out))
                    }
                }
            }

            if (uiState.accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_no_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            OutlinedButton(
                onClick = onSignedOut,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_add_account))
            }

            Text(
                text = stringResource(R.string.settings_signout_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_display),
                style = MaterialTheme.typography.titleMedium,
            )

            UiScaleSetting(
                selected = uiState.uiScale,
                onSelect = viewModel::setUiScale,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(
                text = stringResource(R.string.settings_tracker),
                style = MaterialTheme.typography.titleMedium,
            )

            // FR-6 (docs/design/09-local-characters.md decision 9). The whole row is the
            // target, not just the thumb: a 24 dp switch on a settings list is the smallest
            // thing on the screen and the label is the part people aim at.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setShowToggles(!uiState.showToggles) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_show_toggles),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.settings_show_toggles_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = uiState.showToggles,
                    onCheckedChange = viewModel::setShowToggles,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .testTag("settings:show-toggles"),
                )
            }
        }
    }

    confirmingSignOutOf?.let { accountId ->
        // "no destructive action without undo or confirm" (04, UX principles).
        AlertDialog(
            onDismissRequest = { confirmingSignOutOf = null },
            title = { Text(stringResource(R.string.signout_dialog_title)) },
            text = { Text(stringResource(R.string.signout_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingSignOutOf = null
                    viewModel.signOut(accountId, onSignedOut)
                }) {
                    Text(stringResource(R.string.action_sign_out))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingSignOutOf = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * FR-18's control (docs/design/14-large-screen-arc.md decision 4, addendum 3 as amended).
 *
 * ### A stepper, and what it replaced
 *
 * This has now been three controls, and the reasons are worth keeping because each one was
 * right about the problem it was solving and wrong about the next one.
 *
 * A `SingleChoiceSegmentedButtonRow` showed all four of 14 decision 2's steps at once, which is
 * the property decision 2 wanted: choosing is one tap and comparing is free. FR-38 made it seven
 * and that broke — a segmented row divides its width evenly and refuses to wrap, so seven labels
 * squeeze until "Default" clips at 360-411 dp. A `FlowRow` of `FilterChip`s fixed the *fitting*
 * and was built, and on being looked at it was withdrawn (operator, same day): seven chips
 * wrapping to two lines is a large, loud block for a setting most users touch once, and Default
 * — the value almost everybody is on and wants to get back to — sat wherever the wrap happened
 * to leave it.
 *
 * A stepper gives up the thing decision 2 asked for: you can no longer see 150% while standing
 * on 70%, and reaching it is six taps rather than one. What it buys is that the control is one
 * row tall at every width and every scale, and that **Default is centred by construction** — the
 * current value is always in the middle, so the setting reads as "where am I" rather than "which
 * of seven". The steps are adjacent by design (0.7 → 0.8 → 0.9 → 1.0 → 1.1 → 1.25 → 1.5), so
 * stepping through them is also how a user finds the size they want: each tap is a visible
 * change, and the live rescale (decision 2's no-restart rule) is what makes walking the range
 * cheaper than picking from a list of numbers nobody can evaluate in the abstract.
 *
 * ### `−` and `+` as glyphs, not icons
 *
 * `StepperButton`'s reason, reused rather than diverged from: `material-icons-core` — the ~200 KB
 * subset this app depends on instead of the ~50 MB extended set — ships `Add` but **not**
 * `Remove`. [MINUS] is U+2212, which matches the `+` optically at this size. The tracker's pips
 * and the DM card's steppers are drawn from the same two constants, so a user who meets a `−` on
 * three screens meets the same `−`.
 *
 * This is not [StepperButton] itself. That one carries press-and-hold acceleration, which the
 * tracker's HP needs and this does not: every tap here re-lays-out the entire app, and a held
 * button would run six of those before the finger lifted.
 *
 * ### TalkBack (BUG-6's lesson, which is the whole of the semantics below)
 *
 * The sentence lives on the node focus actually stops at, and focus stops at three nodes:
 *
 *  - **each button**, which names its **destination** and not itself — "Smaller, to 90%", not
 *    "Smaller". A stepper is the control where "what does this do" and "where does this go" come
 *    apart: the button's label never changes, but what it does changes with every tap, and only
 *    the destination tells a user who cannot see the value line whether the tap was worth making.
 *  - **the value**, which carries the group name *and* the step — "UI size, 110%". The heading
 *    above names the section for a user walking it top to bottom, but a user who lands on the
 *    value by swiping gets no heading, and "110%" alone is a number with no subject.
 *  - **a disabled button**, which stays in the tree and says why it will not move ("Smaller,
 *    already at the smallest size"). Removing it at the ends would be worse: the row would
 *    change shape at 70% and 150%, and a screen-reader user would find the control's node count
 *    depending on its value.
 *
 * The `Row` deliberately carries **no** `contentDescription`. It is a plain layout, so setting
 * one would merge all three nodes into a single announcement naming none of them — the exact
 * defect BUG-6 was.
 */
@Composable
internal fun UiScaleSetting(
    selected: UiScale,
    onSelect: (UiScale) -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = UiScale.entries
    val index = steps.indexOf(selected)
    // `getOrNull(-1)` is null, so the floor needs no special case beyond this line.
    val smaller = steps.getOrNull(index - 1)
    val larger = steps.getOrNull(index + 1)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_ui_scale),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.settings_ui_scale_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            UiScaleStepButton(
                glyph = MINUS,
                destination = smaller,
                atLimitDescription = R.string.settings_ui_scale_smaller_limit,
                towardsDescription = R.string.settings_ui_scale_smaller,
                testTag = "settings:ui-scale:smaller",
                onStep = onSelect,
            )
            // Resolved outside the `semantics` lambda, which is not a composable scope.
            val valueDescription = uiScaleValueDescription(selected)
            Text(
                text = stringResource(uiScaleValueLabel(selected)),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                // `weight(1f)` bounds the value to whatever the two 48 dp buttons leave, so it
                // can never push them off the row. What it does *inside* that box took two
                // recordings to get right, and the picture is the only reason either was caught.
                //
                // The provider multiplies density **and** font scale by the same factor, so sp
                // text grows with the square of a step while dp space grows linearly, and the
                // two 48 dp targets do not shrink at all. The Default value is the widest string
                // this control ever shows. At 150 % on a 360 dp phone, inside the screen's own
                // 24 dp padding, the slot is about 112 dp and that string needs more at any font
                // size a user would call legible — so `maxLines = 1` and `autoSize` together
                // still clipped it, twice, with no ellipsis to admit it.
                //
                // So the line is allowed to become two. `autoSize` keeps it on one wherever one
                // fits — which is every step at 100 %, and every width the setting is normally
                // read at — and the floor is `bodyMedium` so the value never shrinks below the
                // note above it just to stay on a single line.
                //
                // The string is `100% (Default)` and not the amendment's literal `100% · Default`
                // for the same reason: it has to survive being broken in half. A wrapped middle
                // dot leaves a separator dangling at the end of the first line; a parenthesised
                // word reads correctly whether it is on the same line or the next one.
                maxLines = 2,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    maxFontSize = MaterialTheme.typography.titleMedium.fontSize,
                ),
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = valueDescription },
            )
            UiScaleStepButton(
                glyph = PLUS,
                destination = larger,
                atLimitDescription = R.string.settings_ui_scale_larger_limit,
                towardsDescription = R.string.settings_ui_scale_larger,
                testTag = "settings:ui-scale:larger",
                onStep = onSelect,
            )
        }
    }
}

/**
 * One end of the stepper: enabled exactly when there is a [destination] to step to.
 *
 * The two facts a caller could otherwise get out of step — "is this enabled" and "does its
 * description name a real step" — are derived from the same nullable here, so a button cannot be
 * tappable while claiming to be at a limit, or disabled while promising a destination.
 */
@Composable
private fun UiScaleStepButton(
    glyph: String,
    destination: UiScale?,
    @StringRes atLimitDescription: Int,
    @StringRes towardsDescription: Int,
    testTag: String,
    onStep: (UiScale) -> Unit,
) {
    val description = if (destination == null) {
        stringResource(atLimitDescription)
    } else {
        stringResource(towardsDescription, stringResource(uiScaleLabel(destination)))
    }

    IconButton(
        onClick = { destination?.let(onStep) },
        enabled = destination != null,
        modifier = Modifier
            .semantics { contentDescription = description }
            .testTag(testTag),
    ) {
        // Not an `Icon`: see the control's KDoc — `material-icons-core` has no `Remove`, and a
        // drawn `+` beside a typed `−` would not match. The colour comes from the button's own
        // `LocalContentColor`, which is what greys the glyph when it is disabled.
        Text(text = glyph, style = MaterialTheme.typography.headlineSmall)
    }
}

/**
 * The percentage label for each step.
 *
 * Exhaustive `when` rather than a lookup on [UiScale.key]: adding another step then fails to
 * compile until somebody writes its label, which is the only mechanism that keeps a new step
 * from shipping as a blank button. `internal` so the mapping can be pinned without the
 * Compose harness `:app` does not have.
 */
@StringRes
internal fun uiScaleLabel(scale: UiScale): Int = when (scale) {
    UiScale.SMALL_70 -> R.string.settings_ui_scale_70
    UiScale.SMALL_80 -> R.string.settings_ui_scale_80
    UiScale.SMALL_90 -> R.string.settings_ui_scale_90
    UiScale.DEFAULT -> R.string.settings_ui_scale_default
    UiScale.LARGE_110 -> R.string.settings_ui_scale_110
    UiScale.LARGE_125 -> R.string.settings_ui_scale_125
    UiScale.LARGE_150 -> R.string.settings_ui_scale_150
}

/**
 * The stepper's centred value line.
 *
 * Delegates to [uiScaleLabel] for every step but one, so there is no second exhaustive `when` to
 * drift out of agreement with the first. `Default` is the exception because it is the only step
 * whose label does not say what size it is — a user reading "Default" cannot tell whether they
 * are above or below it, which is the one question a stepper's value line has to answer.
 */
@StringRes
internal fun uiScaleValueLabel(scale: UiScale): Int =
    if (scale == UiScale.DEFAULT) R.string.settings_ui_scale_default_value else uiScaleLabel(scale)

/**
 * What TalkBack reads when focus lands on the value: the group's name and the current step.
 *
 * Not the displayed text. `100% (Default)` is a *typographic* line — the brackets are punctuation
 * a sighted reader skips and a screen reader either announces or swallows depending on its
 * verbosity settings, neither of which is what should be said. The spoken form spells the same
 * fact as a sentence, and stays one line even where the displayed value has wrapped to two.
 */
@Composable
internal fun uiScaleValueDescription(scale: UiScale): String =
    if (scale == UiScale.DEFAULT) {
        stringResource(R.string.settings_ui_scale_value_description_default)
    } else {
        stringResource(R.string.settings_ui_scale_value_description, stringResource(uiScaleLabel(scale)))
    }
