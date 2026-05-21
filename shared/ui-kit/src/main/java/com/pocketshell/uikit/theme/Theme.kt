package com.pocketshell.uikit.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark `ColorScheme` mapping the PocketShell design tokens onto Material 3
 * slots.
 *
 * Slot mapping (per issue #11's Scope section):
 *
 * - `background` -> [PocketShellColors.Background]
 * - `surface` -> [PocketShellColors.Surface]
 * - `surfaceVariant` -> [PocketShellColors.SurfaceElev]
 * - `primary` -> [PocketShellColors.Accent]
 * - `onPrimary` -> [PocketShellColors.OnAccent] (`#04101A`)
 * - `onBackground` / `onSurface` -> [PocketShellColors.Text]
 * - `outline` -> [PocketShellColors.Border]
 * - `outlineVariant` -> [PocketShellColors.BorderSoft]
 * - `error` -> [PocketShellColors.Red]
 * - `onError` -> [PocketShellColors.Text]
 *
 * Slots not listed above keep Material's dark defaults — we don't pretend to
 * have a full design vocabulary for `tertiary` / `inverseSurface` / etc.
 * Anything calling those slots today is unintentional, and the bare-default
 * colour makes that easy to spot during review.
 */
private val PocketShellDarkColorScheme = darkColorScheme(
    background = PocketShellColors.Background,
    surface = PocketShellColors.Surface,
    surfaceVariant = PocketShellColors.SurfaceElev,
    primary = PocketShellColors.Accent,
    onPrimary = PocketShellColors.OnAccent,
    onBackground = PocketShellColors.Text,
    onSurface = PocketShellColors.Text,
    outline = PocketShellColors.Border,
    outlineVariant = PocketShellColors.BorderSoft,
    error = PocketShellColors.Red,
    onError = PocketShellColors.Text,
)

/**
 * Top-level theme wrapper. Wrap your app's root `setContent { ... }` block in
 * this composable to get the PocketShell colour scheme, typography, and
 * shapes:
 *
 * ```kotlin
 * setContent {
 *     PocketShellTheme {
 *         Surface { ... }
 *     }
 * }
 * ```
 *
 * Per D8 + the issue's non-goals: dark only for v1. No light theme, no
 * dynamic colour. We may revisit once the visual identity is established.
 */
@Composable
fun PocketShellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PocketShellDarkColorScheme,
        typography = PocketShellTypography,
        shapes = PocketShellShapes,
        content = content,
    )
}
