package com.pocketshell.uikit.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * Material 3 mapping for the PocketShell Quiet tokens.
 *
 * This follows `docs/design-kit/android/PocketShellTheme.kt`: Quiet uses the
 * raised surface for selected/primary containers, a strong neutral outline for
 * fields, and the divider token for hairlines. The explicit mappings keep
 * existing app2 screens on one shared theme as the palette changes.
 */
private val PocketShellDarkColorScheme = darkColorScheme(
    background = PocketShellColors.Background,
    surface = PocketShellColors.Surface,
    surfaceVariant = PocketShellColors.SurfaceElev,
    primary = PocketShellColors.Accent,
    onPrimary = PocketShellColors.OnAccent,
    primaryContainer = PocketShellColors.SurfaceElev,
    onPrimaryContainer = PocketShellColors.Text,
    secondary = PocketShellColors.TextSecondary,
    onSecondary = PocketShellColors.Background,
    secondaryContainer = PocketShellColors.SurfaceElev,
    onSecondaryContainer = PocketShellColors.Text,
    tertiary = PocketShellColors.TextSecondary,
    onTertiary = PocketShellColors.Background,
    onBackground = PocketShellColors.Text,
    onSurface = PocketShellColors.Text,
    onSurfaceVariant = PocketShellColors.TextSecondary,
    surfaceTint = Color.Transparent,
    outline = PocketShellColors.Border,
    outlineVariant = PocketShellColors.BorderSoft,
    error = PocketShellColors.Red,
    onError = PocketShellColors.Background,
    errorContainer = PocketShellColors.Surface,
    onErrorContainer = PocketShellColors.Red,
    scrim = Color.Black,
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
 * PocketShell is a single dark dev-tool design system (#477, D22 hard-cut):
 * the app always renders the dark scheme regardless of the device's system
 * light/dark setting. There is no System-following mode and no light scheme —
 * those were removed together with the #112 Settings → Appearance toggle. With
 * one scheme app-wide, `MaterialTheme.colorScheme` is unconditionally the dark
 * dev-tool palette, so components can safely source chrome from it without
 * white-flipping on a light-mode device.
 */
@Composable
fun PocketShellTheme(
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = PocketShellDarkColorScheme
    CompositionLocalProvider(
        LocalPocketShellSemantic provides PocketShellDarkSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PocketShellTypography,
            shapes = PocketShellShapes,
            content = content,
        )
    }
}
