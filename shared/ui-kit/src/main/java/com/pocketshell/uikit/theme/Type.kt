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
