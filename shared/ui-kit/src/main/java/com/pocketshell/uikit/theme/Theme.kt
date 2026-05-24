package com.pocketshell.uikit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
 * Light `ColorScheme` introduced for issue #112's Settings → Appearance
 * toggle. The PocketShell mockups are dark-only so there are no token
 * values to mirror — instead we hand-roll a pragmatic light palette that
 * keeps the same accent (cyan stays the brand colour) and inverts the
 * surface / text ramps. The accent stays on-brand, the surfaces flip to
 * a near-white ramp, and text inverts so the same component code reads
 * correctly under either scheme without per-screen branching.
 *
 * The raw [PocketShellColors] tokens used directly by individual screens
 * (e.g. `Background`, `Surface`, `Text`) are still hard-wired to the
 * dark palette — this means screens that reach for raw tokens stay dark
 * regardless of the Material scheme. That is intentional for this issue:
 * the goal is to expose the preference + propagate it via the
 * Material color scheme, not to re-skin every existing screen. Future
 * issues can migrate screens onto `MaterialTheme.colorScheme.*` to opt
 * into the light scheme.
 */
private val PocketShellLightColorScheme = lightColorScheme(
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F5),
    primary = PocketShellColors.Accent,
    onPrimary = PocketShellColors.OnAccent,
    onBackground = Color(0xFF0D1117),
    onSurface = Color(0xFF0D1117),
    outline = Color(0xFFCBD2DA),
    outlineVariant = Color(0xFFE2E6EC),
    error = PocketShellColors.Red,
    onError = Color(0xFFFFFFFF),
)

/**
 * Public theme variants exposed by [PocketShellTheme]. Internal to the
 * theme module so callers think in `useDarkColors = true/false` at the
 * call site (see the overload below); the enum is reachable for future
 * `@Preview` plumbing that wants to force one or the other.
 */
enum class PocketShellThemeMode {
    System,
    Light,
    Dark,
}

/**
 * Top-level theme wrapper. Wrap your app's root `setContent { ... }` block in
 * this composable to get the PocketShell colour scheme, typography, and
 * shapes:
 *
 * ```kotlin
 * setContent {
 *     PocketShellTheme(mode = PocketShellThemeMode.System) {
 *         Surface { ... }
 *     }
 * }
 * ```
 *
 * The default mode stays [PocketShellThemeMode.Dark] for source
 * backwards-compatibility — call sites that do not opt in to the new
 * preference (most existing tests and previews) keep the dark scheme
 * they were built against. Issue #112's settings surface explicitly
 * threads a [mode] derived from the persisted [com.pocketshell.app.settings.AppSettings]
 * snapshot.
 */
@Composable
fun PocketShellTheme(
    mode: PocketShellThemeMode = PocketShellThemeMode.Dark,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = when (mode) {
        PocketShellThemeMode.Dark -> PocketShellDarkColorScheme
        PocketShellThemeMode.Light -> PocketShellLightColorScheme
        PocketShellThemeMode.System ->
            if (isSystemInDarkTheme()) PocketShellDarkColorScheme else PocketShellLightColorScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = PocketShellTypography,
        shapes = PocketShellShapes,
        content = content,
    )
}
