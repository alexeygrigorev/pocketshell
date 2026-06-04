package com.pocketshell.uikit.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PocketShell design tokens — ported verbatim from `docs/mockups/styles.css`'s
 * `:root` block (the visual source of truth) and `docs/design-language.md`.
 *
 * Naming mirrors the CSS variables so a reviewer can `grep` between this file
 * and the mockup CSS and spot drift quickly:
 *
 * - CSS `--bg` -> [Background]
 * - CSS `--surface` -> [Surface]
 * - CSS `--surface-elev` -> [SurfaceElev]
 * - CSS `--border` -> [Border]
 * - CSS `--border-soft` -> [BorderSoft]
 * - CSS `--text` -> [Text]
 * - CSS `--text-secondary` -> [TextSecondary]
 * - CSS `--text-muted` -> [TextMuted]
 * - CSS `--accent` -> [Accent]
 * - CSS `--accent-soft` -> [AccentSoft]
 * - CSS `--accent-dim` -> [AccentDim]
 * - CSS `--green` / `--amber` / `--red` / `--purple` -> [Green] / [Amber] / [Red] / [Purple]
 * - CSS `--term-*` -> [TermBg], [TermText], [TermPrompt], [TermComment]
 *
 * These are raw tokens — they're mapped onto Material 3 [androidx.compose.material3.ColorScheme]
 * slots in [Theme]. Callers should prefer `MaterialTheme.colorScheme.*` for
 * semantic colour (background, surface, primary, etc). Reach for these raw
 * tokens only when Material's slot vocabulary doesn't cover the case — for
 * example, the terminal surface uses [TermBg] directly because xterm-style
 * "near-black" doesn't map onto any Material slot.
 */
object PocketShellColors {
    // Surface ramp.
    val Background = Color(0xFF0D1117)
    val Surface = Color(0xFF161B22)
    val SurfaceElev = Color(0xFF1C2129)
    val Border = Color(0xFF2D333B)
    val BorderSoft = Color(0xFF21262D)

    // Text ramp.
    val Text = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF8B949E)
    val TextMuted = Color(0xFF6E7681)

    // Accent + accent-derived.
    val Accent = Color(0xFF22D3EE)

    // rgba(34, 211, 238, 0.12) -> ARGB 0x1F22D3EE. 0.12 * 255 = 30.6 -> 0x1F.
    val AccentSoft = Color(0x1F22D3EE)
    val AccentDim = Color(0xFF0891B2)

    // Semantic / status colours. Used as dots, badges, progress states — never
    // for chrome (design-language.md: "UI chrome stays neutral").
    val Green = Color(0xFF22C55E)
    val Amber = Color(0xFFF59E0B)
    val Red = Color(0xFFEF4444)
    val Purple = Color(0xFFA78BFA)

    // Terminal-specific. The terminal background is *blacker* than the app
    // background on purpose (CSS `--term-bg: #010409`) — keeps the terminal
    // surface visually distinct from the surrounding chrome.
    val TermBg = Color(0xFF010409)
    val TermText = Color(0xFFE6EDF3)
    val TermPrompt = Color(0xFF22D3EE)
    val TermComment = Color(0xFF6E7681)

    // Colour used on top of [Accent] (e.g. FAB icon, primary button label).
    // Sourced from the mockup FAB / `.btn.primary` rule: `color: #04101A`.
    // Exposed publicly because it's the value passed to `onPrimary` in the
    // Material scheme, and downstream surfaces (FAB, mic button, primary
    // buttons) read it through `MaterialTheme.colorScheme.onPrimary`.
    val OnAccent = Color(0xFF04101A)
}

/**
 * Non-M3 semantic colour roles (#461 §3.1).
 *
 * Material 3's `ColorScheme` has no slot for "status" or "agent" roles, so these
 * are carried alongside it via [LocalPocketShellSemantic]. Every value here is
 * sourced from the existing [PocketShellColors] palette — this type does **not**
 * introduce new colours, it just centralises the ones screens already use as raw
 * tokens so they can be reached through one named role vocabulary.
 *
 * Status colours are for dots, left-edge ticks, and badges only — never chrome or
 * text (`docs/design-language.md`: "UI chrome stays neutral"). The
 * accent[Soft]/accent/accentDim trio is always used together (active chip bg =
 * `accentSoft`, text = `accent`, border = `accentDim`).
 */
@Immutable
data class PocketShellSemanticColors(
    /** Connected / attached / agent-live. Green dot. */
    val statusActive: Color,
    /** Detached / idle. Muted dot. */
    val statusIdle: Color,
    /** Connecting (pulse reserved for this state only). Amber dot. */
    val statusConnecting: Color,
    /** Failed. Red dot. */
    val statusError: Color,
    /** Needs-setup attention. Amber (folds with idle precedence per HostCard §8). */
    val statusAttention: Color,
    /** Agent / assistant role (conversation role glyph + agent bubble accent). Purple. */
    val agentAccent: Color,
    /** Active chip background / hint banner fill (paired with [accent] + [accentDim]). */
    val accentSoft: Color,
    /** Active chip text / accent content (paired with [accentSoft] + [accentDim]). */
    val accent: Color,
    /** Active chip / hint banner border (paired with [accentSoft] + [accent]). */
    val accentDim: Color,
)

/**
 * The PocketShell dark semantic roles, mapped 1:1 onto existing [PocketShellColors]
 * constants so nothing changes colour — they are just now reachable as named roles.
 */
val PocketShellDarkSemanticColors: PocketShellSemanticColors = PocketShellSemanticColors(
    statusActive = PocketShellColors.Green,
    statusIdle = PocketShellColors.TextMuted,
    statusConnecting = PocketShellColors.Amber,
    statusError = PocketShellColors.Red,
    statusAttention = PocketShellColors.Amber,
    agentAccent = PocketShellColors.Purple,
    accentSoft = PocketShellColors.AccentSoft,
    accent = PocketShellColors.Accent,
    accentDim = PocketShellColors.AccentDim,
)

/**
 * Carries [PocketShellSemanticColors] down the tree alongside `MaterialTheme`.
 * Provided by [PocketShellTheme]; read with
 * `LocalPocketShellSemantic.current.statusActive` etc. Defaults to the dark roles
 * so previews/tests that forget to wrap still resolve real values.
 */
val LocalPocketShellSemantic = staticCompositionLocalOf { PocketShellDarkSemanticColors }
