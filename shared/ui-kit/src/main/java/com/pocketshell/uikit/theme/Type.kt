package com.pocketshell.uikit.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Typography for PocketShell.
 *
 * Sizes are pinned to `docs/design-language.md`'s restrained scale:
 *
 * - 11sp captions (`labelSmall`)
 * - 14sp body (`bodyMedium`)
 * - 16sp titles (`titleMedium`)
 * - 20sp screen headings (`headlineSmall`)
 *
 * Font families:
 *
 * - UI chrome: Android system default (Roboto on most devices). The design
 *   spec calls for Inter or SF Pro, but bundling Inter is deferred per the
 *   issue's non-goals — "system mono fallback for now; bundling fonts is a
 *   follow-up". The system sans-serif is close enough for v1.
 * - Terminal and inline code: [JetBrainsMonoFamily], which today resolves to
 *   [FontFamily.Monospace] (system monospace). When we bundle the actual
 *   JetBrains Mono `.ttf` files (follow-up issue), swap the alias's value;
 *   call sites need no edits.
 */

/**
 * Alias for the monospace family used in terminals and inline code.
 *
 * Today: system monospace (Roboto Mono on most Android builds). Tomorrow:
 * bundled JetBrains Mono. Kept as a named alias so all downstream call sites
 * — terminal surface, `CommandChip`, inline `<code>` runs — flip in one
 * place when the bundled font lands.
 */
val JetBrainsMonoFamily: FontFamily = FontFamily.Monospace

/**
 * Material 3 typography for PocketShell. Only the slots we actually use today
 * are overridden; everything else inherits Material's defaults so unanticipated
 * components don't render with garbage sizes.
 */
val PocketShellTypography: Typography = Typography(
    // 20sp screen headings — `.appbar .title` in the mockups (`font-size: 22px`,
    // but the spec calls 20sp the canonical heading size; the mockup is 2px
    // larger because CSS px and Compose sp don't translate 1:1 once Material's
    // line-height padding is layered on. Trust the design-language.md spec).
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
    ),

    // 16sp titles — used for sheet headers (`.sheet-title`), card titles, etc.
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),

    // 14sp body — the default reading size for messages, settings rows,
    // `.session-row .sess-name`, etc.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),

    // 11sp captions — section labels, timestamps, `.statusbar .right`,
    // anything labeled `.text-muted` in the mockups.
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
    ),
)

/**
 * The new dense/mono type rungs (#461 §3.2 / Δ7 / Δ8).
 *
 * **Deliberately NOT M3 `Typography` slots.** Overriding a previously-default
 * Material slot (e.g. `titleSmall`, `bodyLarge`, `labelMedium`) would silently
 * restyle every component that already reads `MaterialTheme.typography.*` for
 * that slot — including the app-bar title and section labels, which would flip
 * to monospace. Slice 0 must be a no-op visually, so these rungs ship as
 * standalone [TextStyle] constants that call sites opt into explicitly:
 *
 * ```kotlin
 * Text(text = path, style = PocketShellType.bodyMono)
 * ```
 *
 * Font bundling stays deferred (#461 decision #5): the mono rungs use the system
 * monospace family via [JetBrainsMonoFamily].
 */
object PocketShellType {
    /**
     * 13sp dense body (Δ8) — the canonical dense-row size between `labelSmall`(11)
     * and `bodyMedium`(14). Promotes the de-facto 13sp literal (the 2nd most-used
     * size in the app) into a real rung: dense list/tree rows, conversation lines,
     * settings rows.
     */
    val bodyDense: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp, // ~1.35× of 13sp
    )

    /**
     * 13sp mono body (Δ7) — terminal-adjacent UI: host subtitles, paths, command
     * chips, tmux names, tool-call previews. System monospace via
     * [JetBrainsMonoFamily] (bundling deferred).
     */
    val bodyMono: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp, // ~1.4× of 13sp
    )

    /**
     * 11sp mono label (Δ7) — inline counts/IDs in a mono context. System
     * monospace via [JetBrainsMonoFamily] (bundling deferred).
     */
    val labelMono: TextStyle = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp, // ~1.3× of 11sp
    )
}
