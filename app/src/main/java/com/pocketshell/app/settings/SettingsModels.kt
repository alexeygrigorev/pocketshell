package com.pocketshell.app.settings

/**
 * Default presentation for the per-host workspace screen.
 *
 * [Tree] groups active projects under the configured workspace roots.
 * [Flat] keeps the older folder/session list available for users who
 * prefer a single recency-sorted surface. The host-detail screen reads
 * this preference directly instead of showing an inline Tree/Flat
 * segmented control.
 */
enum class HostDetailViewMode {
    Tree,
    Flat,
}

/**
 * Snapshot of all PocketShell user-tunable settings exposed by the
 * settings surface introduced in issue #112.
 *
 * The repository ([SettingsRepository]) emits an [AppSettings] each time
 * any field changes so observers can react with a single
 * `collectAsState()` call rather than wiring three separate flows.
 *
 * @property terminalFontSizeSp user-preferred terminal text size in
 *   scale-independent pixels. Stored as a `Float` even though the UI
 *   exposes integer steps so callers can pass it directly into Compose
 *   `.sp` (which takes a `Float`). Range: [MIN_TERMINAL_FONT_SP] to
 *   [MAX_TERMINAL_FONT_SP].
 * @property conversationFontSizeSp user-preferred conversation message-body
 *   text size in scale-independent pixels. Issue #496. Mirrors
 *   [terminalFontSizeSp] but scales the agent-conversation turns instead of
 *   the raw terminal. Defaults to [DEFAULT_CONVERSATION_FONT_SP] — the
 *   compact `bodyDense` (13sp, #493) rung — so a fresh install renders
 *   exactly as before; the Settings slider lets the user bump it up or
 *   down. Range: [MIN_CONVERSATION_FONT_SP] to [MAX_CONVERSATION_FONT_SP].
 * @property tmuxOnAttachByDefault when true, the host picker bootstrap
 *   prefers to attach via tmux when the remote has it installed. When
 *   false, the bootstrap defaults to plain SSH even on hosts where tmux
 *   is detected. The actual host-bootstrap flow continues to surface its
 *   own per-host choice — this preference only changes the default
 *   highlighted option.
 * @property defaultHostId saved host opened automatically on normal app
 *   launch. `null` keeps the launch destination at the host list.
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
 *   [DEFAULT_VOICE_SILENCE_SECONDS]. Issue #125 added the setting.
 *   Issue #185 raised the minimum bound to 2s; issue #397 makes the
 *   default much more conservative so natural pauses and quieter speech
 *   from a distant phone do not cut dictation off mid-thought.
 */
data class AppSettings(
    val terminalFontSizeSp: Float = DEFAULT_TERMINAL_FONT_SP,
    val conversationFontSizeSp: Float = DEFAULT_CONVERSATION_FONT_SP,
    val tmuxOnAttachByDefault: Boolean = true,
    val defaultHostId: Long? = null,
    val voiceLanguage: String = VOICE_LANGUAGE_AUTO,
    val voiceSilenceThresholdSeconds: Float = DEFAULT_VOICE_SILENCE_SECONDS,
    val showSystemNotes: Boolean = DEFAULT_SHOW_SYSTEM_NOTES,
    val hostDetailViewMode: HostDetailViewMode = HostDetailViewMode.Tree,
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
    /**
     * Issue #526: how long PocketShell waits after typing the composer's
     * message text into the agent pane before pressing the submit Enter, in
     * milliseconds. The composer's Send writes the text and the submit Enter
     * as two separate `send-keys` calls; on a busy TUI (Claude Code, Codex)
     * an Enter that lands before the agent has finished ingesting the pasted
     * text is not treated as submit, so the message sits unsent in the input
     * line. This configurable delay closes that race. Default
     * [DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS]; tunable between
     * [MIN_AGENT_SUBMIT_ENTER_DELAY_MS] and [MAX_AGENT_SUBMIT_ENTER_DELAY_MS]
     * via the Settings → Terminal slider.
     */
    val agentSubmitEnterDelayMs: Int = DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS,
) {
    companion object {
        const val MIN_TERMINAL_FONT_SP: Float = 10f
        const val MAX_TERMINAL_FONT_SP: Float = 22f
        const val DEFAULT_TERMINAL_FONT_SP: Float = 14f
        const val FONT_STEP_SP: Float = 1f

        /**
         * Issue #496: bounds for the Settings → conversation font-size
         * slider. The default matches the compact `bodyDense` rung
         * (13sp, #493) so a fresh install renders the agent conversation
         * exactly as it did before this setting existed. The lower bound
         * (11sp) keeps the text legible without breaking the dense-row
         * layout; the upper bound (22sp) matches the terminal slider's
         * ceiling so neither control lets the user push text so large the
         * turn layout falls apart. The same [FONT_STEP_SP] 1sp grain is
         * reused, so the slider snaps to whole sp like the terminal one.
         */
        const val MIN_CONVERSATION_FONT_SP: Float = 11f
        const val MAX_CONVERSATION_FONT_SP: Float = 22f
        const val DEFAULT_CONVERSATION_FONT_SP: Float = 13f

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
         * #185 raised this from 0.5s to 2.0s after a v0.2.8 feedback
         * report where dictation auto-stopped mid-sentence. Issue #397
         * keeps the low end available only as the explicit aggressive
         * choice; the default now lives far away from this floor.
         *
         * Persisted values predating #185 that fell below the new floor
         * are clamped on read by [SettingsRepository.readSnapshot], so a
         * fresh app launch always sees a >= 2s window even when the
         * SharedPreferences blob carries a legacy sub-2s value.
         */
        const val MIN_VOICE_SILENCE_SECONDS: Float = 2f
        const val MAX_VOICE_SILENCE_SECONDS: Float = 60f

        /**
         * Default silence window used on fresh installs. Issue #397 moves
         * the default to 30 seconds: long enough to behave like a
         * long-dictation mode through natural pauses and quieter distant
         * speech, while still eventually recovering if the user walks away
         * from an active mic. Users who want faster auto-stop can choose an
         * aggressive value in Settings.
         */
        const val DEFAULT_VOICE_SILENCE_SECONDS: Float = 30f
        const val VOICE_SILENCE_STEP_SECONDS: Float = 1f

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

        /**
         * Issue #526: bounds + default for the composer agent-submit Enter
         * delay (ms). After typing the message text into the agent pane the
         * composer waits this long, then sends the submit Enter as a separate
         * `send-keys` so a fast Enter doesn't race ahead of the agent TUI's
         * paste ingestion (which leaves the message sitting unsent).
         *
         * - Default 150ms sits in the maintainer-suggested 100–300ms band:
         *   long enough for Claude Code / Codex to finish ingesting a typical
         *   composer message before the submit Enter, short enough that Send
         *   still feels instant.
         * - The floor is 0ms (back-to-back, the pre-#526 behaviour) for users
         *   whose agent never races; the ceiling 1000ms covers a sluggish TUI
         *   without letting a hand-edited prefs value make Send feel broken.
         * - The slider grain is 50ms, giving a fine set of stops across the
         *   useful range.
         */
        const val MIN_AGENT_SUBMIT_ENTER_DELAY_MS: Int = 0
        const val MAX_AGENT_SUBMIT_ENTER_DELAY_MS: Int = 1000
        const val DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS: Int = 150
        const val AGENT_SUBMIT_ENTER_DELAY_STEP_MS: Int = 50
    }
}

/**
 * Display tuple for the language dropdown — a code/label pair so the
 * persistence layer stores the stable ISO code while the UI renders a
 * human-friendly string. Lives at file scope so test fakes can build
 * their own option lists without instantiating [AppSettings].
 */
data class VoiceLanguageOption(val code: String, val label: String)
