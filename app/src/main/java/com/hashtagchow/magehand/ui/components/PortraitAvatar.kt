package com.hashtagchow.magehand.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * FR-21's portrait (docs/design/15-polish-batch.md decisions 1–4): the sheet's picture, with the
 * initials monogram underneath.
 *
 * ### The monogram is *underneath*, not an error painter
 *
 * This is the WP5 character-list `Portrait`'s rule, generalized rather than reinvented — and it
 * is the whole of decision 1's *"the current look IS the fallback; zero regression"*. The table's
 * sheets store HeroForge **configurator** links in `picture`: those are HTML pages, not images,
 * so Coil fetches them, fails to decode, and draws nothing. Because the monogram was already
 * painted in the same box, what remains is exactly what the row looked like before — no error
 * state to design, no placeholder flash, no layout jump when a load succeeds or fails, and the
 * same behaviour for "no URL at all" (every local character) as for "a URL that is not an image".
 *
 * Loading is the same non-event: nothing is drawn over the monogram until there are pixels.
 *
 * ### Cleartext, unchanged (decision 3)
 *
 * `usesCleartextTraffic=false` stands and there is no manifest change and no special case here.
 * An `http://` URL simply fails the network layer, which lands in the same branch as a HeroForge
 * link — the fallback. A sheet that stores an insecure portrait URL degrades; it does not get an
 * exemption.
 *
 * ### Decorative, deliberately (decision 4)
 *
 * `contentDescription = null`. The portrait carries no information the row does not already
 * speak: the name is right beside it (and on the DM card it is inside a merged node that names
 * the character in its first clause). A description here would either repeat the name or
 * announce "portrait", and the row's spoken sentence is unchanged by this composable existing —
 * which is decision 4's requirement stated as a property rather than as a promise.
 *
 * @param url `CharacterSummary.picture`, which is already `avatarPicture ?: picture` — the
 *   preference decision 1 asks for is applied once, at the mapping (`toCharacterSummary`), so no
 *   caller has to remember the order. `null` renders the monogram alone.
 * @param monogram up to two letters. `CharacterSummary.monogram` for a DiceCloud character;
 *   local characters build their own the same way.
 */
@Composable
fun PortraitAvatar(
    url: String?,
    monogram: String,
    size: Dp,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.titleMedium,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram,
            style = textStyle,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** The character list's portrait size, and the reference the DM card's smaller one is derived from. */
val PORTRAIT_SIZE_LIST: Dp = 56.dp

/**
 * The DM card's portrait size.
 *
 * Smaller than [PORTRAIT_SIZE_LIST] because a dashboard card is a dense read — decision 12's
 * five facts plus an inventory line — and a 56 dp disc beside the name would push the
 * concentration banner and the HP bar down on a six-card grid. 40 dp is large enough to
 * recognise a face at a glance and small enough to sit on the name's own line.
 */
val PORTRAIT_SIZE_CARD: Dp = 40.dp
