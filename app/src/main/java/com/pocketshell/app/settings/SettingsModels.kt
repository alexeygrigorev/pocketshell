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
 * @property voiceLanguage ISO-639-1 language code passed to Whisper's
 *   `language` parameter. The sentinel value [VOICE_LANGUAGE_AUTO]
 *   (default) means "let Whisper auto-detect"; in that case no language
 *   parameter is sent. Other supported values map to the entries in
 *   [VOICE_LANGUAGE_OPTIONS]. Issue #125.
 * @property voiceSilenceThresholdSeconds auto-stop silence window in
 *   seconds for both the prompt composer and inline dictation. The
 *   recording auto-stops after this many seconds of below-threshold
 *   amplitude. Range: [MIN_VOICE_SILENCE_SECONDS] to
 *   [MAX_VOICE_SILENCE_SECONDS]; default
 *   [DEFAULT_VOICE_SILENCE_SECONDS] matches the historic 5-second
 *   constant in `PromptComposerViewModel.SILENCE_WINDOW_MS`. Issue #125.
 *   Issue #185 raised the minimum bound to 2s so a hand-edited or stray
 *   slider drag cannot leave the user with a sub-2s threshold that
 *   auto-stops on natural mid-sentence pauses.
 */
data class AppSettings(
    val theme: ThemePreference = ThemePreference.System,
    val terminalFontSizeSp: Float = DEFAULT_TERMINAL_FONT_SP,
    val tmuxOnAttachByDefault: Boolean = true,
    val voiceLanguage: String = VOICE_LANGUAGE_AUTO,
    val voiceSilenceThresholdSeconds: Float = DEFAULT_VOICE_SILENCE_SECONDS,
    val showSystemNotes: Boolean = DEFAULT_SHOW_SYSTEM_NOTES,
    /**
     * Whether failed Whisper transcriptions are persisted to disk so the
     * audio can be retried. Issue #180. Default `true` so users get the
     * data-loss protection by default; the privacy-conscious can opt out
     * via Settings → Voice. When `false`, a Whisper failure clears the
     * audio buffer the same way pre-#180 builds did.
     */
    val persistFailedTranscriptions: Boolean = DEFAULT_PERSIST_FAILED_TRANSCRIPTIONS,
    /**
     * Issue #214: the percent at which the in-app usage warning surfaces
     * start firing ("approaching limit"). Default 80%; configurable
     * between [MIN_USAGE_WARN_PERCENT] and [MAX_USAGE_WARN_PERCENT] via
     * the Settings → Usage slider. Only the lower "approaching" band is
     * user-configurable — the "critical" (95%) and "exceeded" (100%)
     * thresholds are fixed by design so a 99% Claude quota still shows
     * as red regardless of where the slider sits.
     */
    val usageWarnThresholdPercent: Int = DEFAULT_USAGE_WARN_PERCENT,
) {
    companion object {
        const val MIN_TERMINAL_FONT_SP: Float = 10f
        const val MAX_TERMINAL_FONT_SP: Float = 22f
        const val DEFAULT_TERMINAL_FONT_SP: Float = 14f
        const val FONT_STEP_SP: Float = 1f

        /**
         * Sentinel value for "no language hint" — Whisper auto-detects
         * the spoken language. Stored as a sentinel string rather than
         * `null` so the SharedPreferences round-trip stays trivial.
         */
        const val VOICE_LANGUAGE_AUTO: String = "auto"

        /**
         * Supported language options surfaced in the Settings → Voice
         * dropdown. Each entry is (ISO-639-1 code, human label).
         *
         * The list is intentionally short — Whisper supports far more
         * languages than this, but the picker in v1 covers the common
         * cases. Users who want a language outside this list can edit
         * `SharedPreferences` directly; a free-form text field is
         * tracked as a follow-up.
         */
        val VOICE_LANGUAGE_OPTIONS: List<VoiceLanguageOption> = listOf(
            VoiceLanguageOption(code = VOICE_LANGUAGE_AUTO, label = "Auto-detect"),
            VoiceLanguageOption(code = "en", label = "English"),
            VoiceLanguageOption(code = "es", label = "Spanish"),
            VoiceLanguageOption(code = "fr", label = "French"),
            VoiceLanguageOption(code = "de", label = "German"),
            VoiceLanguageOption(code = "ja", label = "Japanese"),
            VoiceLanguageOption(code = "ru", label = "Russian"),
        )

        /**
         * Lowest silence window the user is allowed to configure. Issue
         * #185 raised this from 0.5s to 2.0s after a v0.2.8 dogfood
         * report where dictation auto-stopped mid-sentence. A natural
         * mid-utterance pause routinely exceeds 0.5s — anything below
         * ~2s makes the watchdog feel hostile rather than helpful — so
         * the floor is now 2s. Users who want a tighter window are out
         * of luck on purpose; the failure mode (auto-stop mid-speech)
         * was uniformly worse than waiting an extra second.
         *
         * Persisted values predating #185 that fell below the new floor
         * are clamped on read by [SettingsRepository.readSnapshot], so a
         * fresh app launch always sees a >= 2s window even when the
         * SharedPreferences blob carries a legacy sub-2s value.
         */
        const val MIN_VOICE_SILENCE_SECONDS: Float = 2f
        const val MAX_VOICE_SILENCE_SECONDS: Float = 10f

        /**
         * Default silence window used on fresh installs. Issue #185
         * documents the chosen value as 5 seconds — well above the 2s
         * floor — so a user who never visits Settings still gets a
         * forgiving window. The historic constant
         * `PromptComposerViewModel.SILENCE_WINDOW_MS` (5000 ms) is the
         * same value expressed in milliseconds.
         */
        const val DEFAULT_VOICE_SILENCE_SECONDS: Float = 5f
        const val VOICE_SILENCE_STEP_SECONDS: Float = 0.5f

        /**
         * Default for [AppSettings.showSystemNotes] — issue #176. When
         * true, XML-tagged system blocks emitted by Claude Code (and any
         * future engine that adopts the same convention) render as muted
         * collapsible rows in the conversation pane. When false they are
         * filtered out entirely. Default is true so a fresh install sees
         * the same notes the previous (pre-#176) builds did, just visually
         * de-emphasized.
         */
        const val DEFAULT_SHOW_SYSTEM_NOTES: Boolean = true

        /**
         * Default for [AppSettings.persistFailedTranscriptions] — issue
         * #180. Default is `true` so the maintainer's data-loss complaint
         * from v0.2.7 dogfood is solved out of the box; users who prefer
         * the older "fail fast, no on-disk audio buffer" behaviour can
         * opt out via the Voice section toggle.
         */
        const val DEFAULT_PERSIST_FAILED_TRANSCRIPTIONS: Boolean = true

        /**
         * Lowest "approaching limit" threshold the user is allowed to
         * configure via Settings → Usage. 50 % matches issue #214's
         * slider spec — anything lower would surface the warning
         * banner for routine quota use and lose its signal value.
         */
        const val MIN_USAGE_WARN_PERCENT: Int = 50

        /**
         * Highest "approaching limit" threshold the user can configure.
         * 95 % matches the fixed "critical" cutoff: pulling the slider
         * all the way to 95 % collapses the approaching band entirely,
         * which the issue spec explicitly allows. Values >= 95 % would
         * just duplicate the critical state.
         */
        const val MAX_USAGE_WARN_PERCENT: Int = 95

        /**
         * Default "approaching limit" threshold on fresh installs.
         * Matches the issue #214 spec — "percent >= 80% →
         * approaching" — and mirrors the
         * [com.pocketshell.core.usage.UsageProviderRecord.DEFAULT_WARN_PERCENT]
         * constant from `core-usage` so the two stays in sync.
         */
        const val DEFAULT_USAGE_WARN_PERCENT: Int = 80

        /**
         * Increment for the Settings → Usage slider. 5 % gives the
         * user nine stops between 50 % and 95 %, which is finer than
         * any meaningful interpretation difference.
         */
        const val USAGE_WARN_PERCENT_STEP: Int = 5
    }
}

/**
 * Display tuple for the language dropdown — a code/label pair so the
 * persistence layer stores the stable ISO code while the UI renders a
 * human-friendly string. Lives at file scope so test fakes can build
 * their own option lists without instantiating [AppSettings].
 */
data class VoiceLanguageOption(val code: String, val label: String)
