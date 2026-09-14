package com.hashtagchow.magehand.core.model

/**
 * The `**`/`*` emphasis runs this app strips instead of rendering.
 *
 * 16 decision 4: *"plain text (no markdown rendering v1)"*. Stripping is the honest half of that
 * ruling — leaving the asterisks in would print `**Nick.**` at a player, and rendering them would
 * be the markdown renderer the decision declines.
 *
 * ### Only asterisks that hug their content
 *
 * FR-47 review LOW-5: a bare `\*+` also ate a literal asterisk in prose, so *"2 \* your level"*
 * rendered as *"2  your level"* — the app deleting a character the server sent. A markdown
 * delimiter always touches the text it emphasises on at least one side, and a multiplication sign
 * is spaced on both, so the two alternatives here ("preceded by non-space" or "followed by
 * non-space") strip every `**bold**` and `*italic*` run and leave a standalone `*` alone. A list
 * dash is untouched because it is not an asterisk at all.
 *
 * ### Why it lives in `:core:model` (BUG-25 R2)
 *
 * It was `ActionEngine`'s private constant from FR-47 until 1.19.0, which was the right home
 * while the only two strippers were that engine's own mastery readers. BUG-25 R2 makes the strip
 * a **render-time** rule — the description a detail sheet displays runs through it — and the
 * screens that display one are in `:app`, which cannot see a private engine constant. The
 * alternatives were both worse than moving it: a second copy of the regex in `:app` is the exact
 * drift FR-47's review already paid for once, and exporting it off `ActionEngine` would make a
 * discovery engine the place a composable imports its text helper from.
 *
 * There is deliberately **no** second regex for anything else. Emphasis is the only markdown the
 * library's own strings carry that would misread as prose; headings, links and code fences have
 * never appeared on a probed sheet, and inventing patterns for them would be a renderer written
 * one exception at a time.
 */
val MARKDOWN_EMPHASIS: Regex = Regex("""(?<=\S)\*+|\*+(?=\S)""")

/**
 * This string with [MARKDOWN_EMPHASIS] removed — the whole of BUG-25 R2's "strip at render".
 *
 * Nothing else happens to the text: it is not re-flowed, re-wrapped, trimmed or re-punctuated,
 * because every character that survives the strip is the server's. A string carrying no emphasis
 * comes back identical, which is what makes it safe to run on every displayed description rather
 * than only on the ones somebody noticed were bold.
 */
fun String.withoutMarkdownEmphasis(): String = replace(MARKDOWN_EMPHASIS, "")
