package com.pocketshell.uikit.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
 * #461 Slice 0 is a strict *zero-rendered-pixel* slice. It deliberately does
 * **not** fill the `surfaceContainer*` / `secondaryContainer` M3 slots, because
 * those are read by live components today and repointing them onto the dev-tool
 * palette would visibly shift rendered chrome:
 *
 * - `surfaceContainer` is `MenuTokens.ContainerColor`, so it paints all 5 of the
 *   app's `DropdownMenu` call sites (host kebab, add/edit host, two tmux pickers,
 *   session menu) — none override `containerColor`. M3 default `#211F26` ->
 *   `SurfaceElev #1C2129` would be a visible shift.
 * - `surfaceContainerHighest` is the unchecked `Switch` track key and the M3
 *   `Card` default container, both of which the app instantiates without a color
 *   override. Repointing it would shift those too.
 * - `secondaryContainer` / `onSecondaryContainer` are the M3 selected-state slots
 *   for chips, segmented buttons, and drawer items.
 *
 * Completing those slots onto the dev-tool palette is a real (intended) visual
 * change and is **deferred to Slice 1**, where the new menu / switch-track / chip
 * colors get an emulator visual audit + maintainer sign-off. See
 * `docs/design-system.md` §3.
 *
 * The only newly-filled slots kept in Slice 0 are the `inverse*` trio, which is
 * provably inert: the app instantiates no M3 `Snackbar` / `NavigationBar`
 * component, so nothing reads `inverseSurface` / `inverseOnSurface` /
 * `inversePrimary` today. They are filled now so Slice 1 doesn't have to.
 *
 * The non-M3 status/agent roles (Green/Amber/Purple/accentSoft…) live in
 * [PocketShellSemanticColors] and are carried via [LocalPocketShellSemantic],
 * since M3 has no slot for them. Those are colour values from the existing
 * palette and are not read by any component until a call site opts in, so they
 * also change nothing rendered today.
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
    // Inert in Slice 0: no Snackbar / NavigationBar is instantiated, so nothing
    // reads these today. Filled now so Slice 1 doesn't re-touch them.
    inverseSurface = PocketShellColors.SurfaceElev,
    inverseOnSurface = PocketShellColors.Text,
    inversePrimary = PocketShellColors.Accent,
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
