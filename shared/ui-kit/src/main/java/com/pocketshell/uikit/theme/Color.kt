package com.pocketshell.uikit.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * PocketShell Quiet colour tokens.
 *
 * The source values are `docs/design-kit/design-system/tokens.json`. The
 * production names remain stable so existing app2 screens receive the new
 * system through [PocketShellTheme] without per-screen translation code.
 *
 * The Quiet kit intentionally has no purple, accent-fill, or accent-border
 * roles. The old names remain as neutral aliases below until the components
 * that use those roles are redesigned in a later slice.
 */
object PocketShellColors {
    // Quiet surface ramp.
    val Background = Color(0xFF10171E)
    val Surface = Color(0xFF19222B)
    val SurfaceElev = Color(0xFF222D38)
    val Border = Color(0xFF64778A)
    val BorderSoft = Color(0xFF2B3946)

    // Quiet text ramp.
    val Text = Color(0xFFF0F3F7)
    val TextSecondary = Color(0xFFA6B2C1)
    val TextMuted = Color(0xFF92A0B0)

    // Quiet action colours.
    val Accent = Color(0xFF53D8EC)
    val OnAccent = Color(0xFF082027)

    // The kit uses neutral boundaries and surfaces in place of the old
    // accent-soft/accent-dim chip treatment. Keep these names source-compatible
    // for components that will move to the Quiet primitives in later slices.
    val AccentSoft = SurfaceElev
    val AccentDim = Border

    // Quiet semantic/status colours.
    val Green = Color(0xFF5CDF89)
    val Amber = Color(0xFFE6BC78)
    val Red = Color(0xFFF3A1A1)
    val Purple = TextMuted

    // The terminal remains a separate surface and uses the Quiet terminal
    // token. Its palette is installed as the emulator default by app2, so
    // reset and OSC colour operations keep the same production contract.
    val TermBg = Color(0xFF0B1117)
    val TermText = Text
    val TermPrompt = Accent
    val TermComment = TextMuted

    /** The Quiet scrim token (`#00000099`) for non-Material overlays. */
    val Scrim = Color(0x99000000)
}

/**
 * Non-M3 semantic colour roles (#461 §3.1).
 *
 * Material 3's `ColorScheme` has no slot for "status" or "agent" roles, so these
 * are carried alongside it via [LocalPocketShellSemantic]. Every value here is
 * sourced from the Quiet [PocketShellColors] palette — this type does **not**
 * introduce new colours, it centralises the roles screens already use so they
 * can be reached through one named vocabulary.
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
    /** Agent / assistant role; Quiet keeps session marks muted. */
    val agentAccent: Color,
    /** Active chip background / hint banner fill (paired with [accent] + [accentDim]). */
    val accentSoft: Color,
    /** Active chip text / accent content (paired with [accentSoft] + [accentDim]). */
    val accent: Color,
    /** Active chip / hint banner border (paired with [accentSoft] + [accent]). */
    val accentDim: Color,
)

/**
 * The PocketShell Quiet semantic roles, mapped onto [PocketShellColors]. Roles
 * that have no Quiet equivalent deliberately fall back to neutral surface/text
 * tokens instead of retaining the old purple and filled-chip treatments.
 */
val PocketShellDarkSemanticColors: PocketShellSemanticColors = PocketShellSemanticColors(
    statusActive = PocketShellColors.Green,
    statusIdle = PocketShellColors.TextMuted,
    statusConnecting = PocketShellColors.Amber,
    statusError = PocketShellColors.Red,
    statusAttention = PocketShellColors.Amber,
    agentAccent = PocketShellColors.TextMuted,
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
