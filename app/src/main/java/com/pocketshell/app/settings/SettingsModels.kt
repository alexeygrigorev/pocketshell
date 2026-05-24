package com.pocketshell.app.settings

/**
 * User-selectable theme preference applied at the composable root.
 *
 * `System` follows the platform `isSystemInDarkTheme()` flag and reacts to
 * changes without a process restart. `Light` and `Dark` pin the app to
 * the requested colour scheme regardless of the OS setting.
 *
 * The default is [System] — first launch matches whatever the device is
 * already set to, which is the least-surprising choice for a fresh
 * install. Existing PocketShell builds were dark-only (per
 * `Theme.kt`'s D8 comment); the new repository defaults align with that
 * historical look on dark-mode devices while letting light-mode devices
 * see the new light scheme by default.
 */
enum class ThemePreference {
    System,
    Light,
    Dark,
}

/**
 * Snapshot of all PocketShell user-tunable settings exposed by the
 * settings surface introduced in issue #112.
 *
 * The repository ([SettingsRepository]) emits an [AppSettings] each time
 * any field changes so observers can react with a single
 * `collectAsState()` call rather than wiring three separate flows.
 *
 * @property theme drives the colour scheme at the composable root — see
 *   [ThemePreference].
 * @property terminalFontSizeSp user-preferred terminal text size in
 *   scale-independent pixels. Stored as a `Float` even though the UI
 *   exposes integer steps so callers can pass it directly into Compose
 *   `.sp` (which takes a `Float`). Range: [MIN_TERMINAL_FONT_SP] to
 *   [MAX_TERMINAL_FONT_SP].
 * @property tmuxOnAttachByDefault when true, the host picker bootstrap
 *   prefers to attach via tmux when the remote has it installed. When
 *   false, the bootstrap defaults to plain SSH even on hosts where tmux
 *   is detected. The actual host-bootstrap flow continues to surface its
 *   own per-host choice — this preference only changes the default
 *   highlighted option.
 */
data class AppSettings(
    val theme: ThemePreference = ThemePreference.System,
    val terminalFontSizeSp: Float = DEFAULT_TERMINAL_FONT_SP,
    val tmuxOnAttachByDefault: Boolean = true,
) {
    companion object {
        const val MIN_TERMINAL_FONT_SP: Float = 10f
        const val MAX_TERMINAL_FONT_SP: Float = 22f
        const val DEFAULT_TERMINAL_FONT_SP: Float = 14f
        const val FONT_STEP_SP: Float = 1f
    }
}
