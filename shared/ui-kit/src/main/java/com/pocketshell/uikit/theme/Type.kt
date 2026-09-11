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
 * Quiet app chrome uses the shared scale from `docs/design-kit/spec/DesignSystem.md`:
 * 28sp screen headings, 20sp titles, 18sp body, and 16sp metadata/labels.
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
    // 28sp screen headings.
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),

    // 20sp titles and workspace names.
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),

    // 18sp body — the default reading size for settings and standard rows.
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),

    // 16sp metadata and labels.
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
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
    /** 28sp Quiet screen title. */
    val quietScreen: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )

    /** 20sp Quiet workspace and row title. */
    val quietTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    )

    /** 18sp Quiet body copy. */
    val quietBody: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    )

    /** 16sp Quiet supporting metadata. */
    val quietMetadata: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** 16sp Quiet labels and action text. */
    val quietLabel: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** 28sp screen heading. */
    val screen: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    )

    /** 20sp workspace or detail title. */
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    )

    /** 20sp workspace name. Kept distinct for call-site readability. */
    val workspace: TextStyle = title

    /** 18sp standard row and explanatory body text. */
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    )

    /** 16sp supporting text and metadata. */
    val metadata: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** 16sp section and field label. */
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )

    /** 18sp action label. */
    val button: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    )

    /** 16sp terminal-adjacent app text; terminal output has its own grid. */
    val terminal: TextStyle = metadata

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
     * chips, aplexer names, tool-call previews. System monospace via
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
