package com.hashtagchow.magehand.ui.screens.characterhome.tracker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hashtagchow.magehand.R
import com.hashtagchow.magehand.core.model.ConcentrationPrompt

/**
 * FR-31's prompt — *"Concentration check — DC 12 (half of 24 damage, minimum 10)"*
 * (docs/design/18-table-pack.md decisions 10 and 12).
 *
 * ### A banner and not a `Snackbar`, and that is the wave's one judgment call here
 *
 * Decision 10 asks for *"a snackbar-priority banner/dialog-lite … with ONE action: 'Drop
 * concentration' … Dismissible; informational otherwise"*, and decision 12 asks for an
 * **assertive** live region because the check is time-sensitive.
 *
 * Material 3's `Snackbar` satisfies the first sentence and cannot satisfy the second: it announces
 * itself with a *polite* live region, which a screen reader queues behind whatever it is currently
 * saying — and "whatever it is currently saying" during a damage write is the write's own
 * confirmation snackbar. The one thing decision 12 is for is the case where those two collide.
 * `SnackbarHost` exposes no hook to change the politeness of what it composes, so the choice was
 * between the widget and the requirement.
 *
 * So this is a banner that behaves like a snackbar: it sits at the top of the surface where the
 * concentration banner already lives, it carries one action and a dismiss, and it is replaced
 * rather than stacked when a second prompt lands. It does **not** time out — decision 10 says
 * *dismissible*, and a check the player has not answered is not a thing to take away from them
 * mid-decision. The two ways it leaves are the ✕ and the character no longer concentrating; both
 * are the caller's, because both are state this composable does not own.
 *
 * ### The DC is labelled, and the label IS the feature
 *
 * Decision 10: *"The DC math is labeled transparently — the transparency IS the variant-rules
 * answer."* So the rule (`half of N damage, minimum 10`) is printed beside the number rather than
 * the number alone. A table running a variant can see exactly which part to disregard, and the app
 * never has to know which variant that is. It does not roll, does not decide, and does not drop
 * concentration on the player's behalf — all three are on 18's out-of-scope list.
 *
 * ### The action is absent, not disabled, when it cannot work
 *
 * [ConcentrationPrompt.toggleId] is `null` whenever the banner's source is not a flippable toggle
 * — a `buff`-sourced concentration, which `flipToggle` refuses (see
 * `TrackerBoard.concentrationToggle`). A greyed "Drop concentration" would promise a control the
 * server has no method behind; the prompt is then purely informational, which decision 10 permits
 * in as many words. That is the same limitation the tracker's own ✕ has carried since WP7, from
 * the same cause, and it is stated in the wave report rather than hidden here.
 *
 * @param subjectName whose check this is, for the DM dashboard — decision 9's *"the DM's own write
 *   prompts on the DM's screen"*, where six characters share one banner slot and the sentence is
 *   meaningless without a name. `null` on a character's own screen, where the answer is "you".
 * @param canWrite the tracker's own dimming rule (`TrackerScreen`'s `ConcentrationBanner` ✕,
 *   mirrored here): the queue refuses a write while disconnected, so the Drop action is absent
 *   under the same condition rather than composed and left to fail silently.
 * @param onDrop handed the toggle id, which is never null when this is called: the button that
 *   calls it is only composed when [ConcentrationPrompt.canDrop] and [canWrite].
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConcentrationPromptBanner(
    prompt: ConcentrationPrompt,
    canWrite: Boolean,
    onDrop: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subjectName: String? = null,
) {
    val headline = stringResource(R.string.tracker_concentration_prompt, prompt.dc)
    val rule = stringResource(R.string.tracker_concentration_prompt_rule, prompt.damage)
    val spoken = stringResource(
        R.string.tracker_concentration_prompt_spoken,
        prompt.dc,
        prompt.damage,
        prompt.sourceName,
    )

    Surface(
        // `errorContainer`, where the concentration banner two rows up uses `primaryContainer`:
        // that one is a standing statement of fact and this is a thing that just happened and
        // wants answering. Two different-coloured bands, so a player glancing down can tell which
        // is which without reading either.
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            // Decision 12: **assertive**, because a concentration check is time-sensitive and a
            // polite region would queue behind the write confirmation that caused it. The whole
            // sentence is one node — the DC, the rule that produced it, and what is being
            // concentrated on — so TalkBack reads it as a statement rather than as four fragments.
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Assertive
                contentDescription = listOfNotNull(subjectName, spoken).joinToString(". ")
            }
            .testTag("tracker:concentration:prompt"),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                subjectName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rule,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (canWrite) prompt.toggleId?.let { toggleId ->
                TextButton(
                    onClick = { onDrop(toggleId) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("tracker:concentration:prompt:drop"),
                ) {
                    Text(stringResource(R.string.tracker_drop_concentration))
                }
            }

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("tracker:concentration:prompt:dismiss"),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(
                        R.string.tracker_concentration_prompt_dismiss,
                    ),
                )
            }
        }
    }
}
