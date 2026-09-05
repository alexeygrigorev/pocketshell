package com.pocketshell.next.settings

/**
 * Every user-tunable preference app2 has (rewrite task P-6).
 *
 * ## Six fields, not sixteen
 *
 * The old client's `AppSettings` carried sixteen. Most of them configured
 * machinery the rewrite deleted, so porting them would have shipped a settings
 * screen whose controls change nothing — the "dead field" shape the plan's P-6
 * entry and the audit both call out. What was dropped, and why, recorded here
 * because the next person will otherwise wonder where it went:
 *
 * | Dropped | Why |
 * | --- | --- |
 * | `tmuxOnAttachByDefault` | Plan P-6 drop list. app2 always attaches through the host CLI; there is no plain-SSH branch to prefer. |
 * | `outboundDeliveryAuthority` (+ enum) | Plan P-6 drop list. It selected between two outbound-queue implementations, both deleted. |
 * | `diagnosticsRecordingEnabled` | The connection-journal recorder subsystem is not ported (plan P-10, audit finding #5). |
 * | `terminalKeyboardMode` | app2's terminal pins char-based input (see `TerminalHostView`'s client) — a smart-text mode no longer exists to select. |
 * | `conversationFontSizeSp`, `showSystemNotes`, `defaultAgentSessionView` | The conversation view (U-10) is cut by the scope amendment. |
 * | `hostDetailViewMode` | The tree/flat toggle: app2 has one session-tree presentation (U-3). |
 * | `defaultHostId` | The open-on-launch destination. app2 always starts on the host list; "startup" is not in P-6's KEEP list. |
 * | `voiceTranscriptionProvider` | Whisper-vs-Android picker. Composer mic is Android `SpeechRecognizer` only (#2529). |
 *
 * `agentSubmitEnterDelayMs` was on that drop list (P-6: "agent surfaces are
 * cut") and is back: the composer is still the send path into those agents,
 * and concatenating body+Enter into one PTY write is the race issue #2526
 * restores the delay to close. No capture-pane ACK gating (#869) — delay only.
 *
 * ## Values, not Compose state
 *
 * A plain immutable data class with defaults that ARE the fresh-install
 * behaviour, so a test (or a Compose preview, or [LocalAppSettings]) can build
 * one with no repository, no `Context` and no disk.
 */
data class AppSettings(
    /**
     * Terminal glyph size in RAW DEVICE PIXELS.
     *
     * Pixels rather than the old client's `sp` because that is the unit the
     * vendored `TerminalView.setTextSize` actually consumes — its javadoc says
     * density-independent pixels and is wrong about its own code (see
     * `TERMINAL_TEXT_SIZE_RAW_PX`). Storing sp here and converting at the view
     * would silently change the shipped default from 28 px to ~42 px on an
     * xxhdpi phone, i.e. a visual regression dressed up as a unit fix.
     */
    val terminalTextSizePx: Int = DEFAULT_TERMINAL_TEXT_SIZE_PX,
    /**
     * ISO-639-1 hint handed to the speech recognizer, or [VOICE_LANGUAGE_AUTO]
     * for "let it detect".
     *
     * Stored as a sentinel string rather than `null` so the preferences
     * round-trip stays trivial. See the class doc on [SettingsRepository] for
     * why this is currently written but not yet read.
     */
    val voiceLanguage: String = VOICE_LANGUAGE_AUTO,
    /**
     * Endpointer silence window in seconds for Android `SpeechRecognizer`
     * (#590/#884/#2529). Persisted as `voice_silence_seconds` so a v0.4.x
     * value survives upgrade. The provider clamps to a 2s floor.
     */
    val voiceSilenceThresholdSeconds: Float = DEFAULT_VOICE_SILENCE_SECONDS,
    /**
     * The percent at which the usage panel starts calling a provider quota
     * "approaching limit". Only the lower band is user-tunable; "critical"
     * (95 %) and "exceeded" (100 %) are fixed by design, so a 99 % quota reads
     * red wherever this slider sits.
     */
    val usageWarnThresholdPercent: Int = DEFAULT_USAGE_WARN_PERCENT,
    /**
     * How long a backgrounded app holds its live connections before tearing
     * them down (D21). The one field this task ADDS rather than ports: task
     * U-8's `GraceCoordinator` hard-codes the same 90 s default, and this row
     * is what lets it be changed without a rebuild.
     */
    val backgroundGraceMillis: Long = DEFAULT_BACKGROUND_GRACE_MILLIS,
    /**
     * Milliseconds to wait after writing the composer body before sending
     * the submit Enter, as a second PTY write (issue #2526 / old #526).
     *
     * Agents (Claude/Codex/Grok) treat a body+CR concatenated into one write
     * as a newline and swallow the submit. Default
     * [DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS]; tunable between
     * [MIN_AGENT_SUBMIT_ENTER_DELAY_MS] and [MAX_AGENT_SUBMIT_ENTER_DELAY_MS]
     * via Settings → Terminal. Read per send, not once at graph construction
     * (same lesson as #2488). Delay only — no capture-pane ACK gating (#869).
     */
    val agentSubmitEnterDelayMs: Int = DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS,
) {
    companion object {

        /**
         * Must equal `com.pocketshell.next.terminal.TERMINAL_TEXT_SIZE_RAW_PX`
         * — the size the terminal renders at when nothing is stored. Pinned by
         * `AppSettingsTest` rather than by referencing that `internal` constant,
         * so this file stays independent of the terminal package.
         */
        const val DEFAULT_TERMINAL_TEXT_SIZE_PX: Int = 28

        /**
         * Bounds for the terminal text-size slider.
         *
         * 16 px is where a 1080 px-wide phone still renders glyphs a reader can
         * resolve; 48 px leaves roughly 22 columns, which is the point below
         * which a shell prompt stops fitting on one line. The 2 px grain keeps
         * the slider on whole even pixels — the renderer measures integer
         * advances, so odd steps produce two indistinguishable sizes.
         */
        const val MIN_TERMINAL_TEXT_SIZE_PX: Int = 16
        const val MAX_TERMINAL_TEXT_SIZE_PX: Int = 48
        const val TERMINAL_TEXT_SIZE_STEP_PX: Int = 2

        /** "No language hint" — the recognizer detects it. */
        const val VOICE_LANGUAGE_AUTO: String = "auto"

        /**
         * Silence window for the Android recognizer extras. Floor 2s (#185);
         * default 4s matches [com.pocketshell.next.voice.AndroidSpeechRecognitionProvider]
         * `DEFAULT_COMPLETE_SILENCE_MS`. Max 60s. A stored v0.4.x 30s value
         * is still in range.
         */
        const val MIN_VOICE_SILENCE_SECONDS: Float = 2f
        const val MAX_VOICE_SILENCE_SECONDS: Float = 60f
        const val DEFAULT_VOICE_SILENCE_SECONDS: Float = 4f
        const val VOICE_SILENCE_STEP_SECONDS: Float = 1f

        /**
         * The languages offered in Settings. Deliberately short: a recognizer
         * supports far more, and a full list would be a scrolling picker for a
         * setting almost nobody changes twice.
         */
        val VOICE_LANGUAGE_OPTIONS: List<VoiceLanguageOption> = listOf(
            VoiceLanguageOption(VOICE_LANGUAGE_AUTO, "Auto-detect"),
            VoiceLanguageOption("en", "English"),
            VoiceLanguageOption("ru", "Russian"),
            VoiceLanguageOption("de", "German"),
            VoiceLanguageOption("fr", "French"),
            VoiceLanguageOption("es", "Spanish"),
        )

        const val MIN_USAGE_WARN_PERCENT: Int = 50
        const val MAX_USAGE_WARN_PERCENT: Int = 95
        const val USAGE_WARN_PERCENT_STEP: Int = 5
        const val DEFAULT_USAGE_WARN_PERCENT: Int = 80

        const val BACKGROUND_GRACE_30_SECONDS_MS: Long = 30_000L
        const val BACKGROUND_GRACE_1_MINUTE_MS: Long = 60_000L
        const val BACKGROUND_GRACE_90_SECONDS_MS: Long = 90_000L
        const val BACKGROUND_GRACE_5_MINUTES_MS: Long = 5 * 60_000L
        const val BACKGROUND_GRACE_10_MINUTES_MS: Long = 10 * 60_000L

        /**
         * 90 seconds, the maintainer's directive on the old client (#1159:
         * "make the default like 90 seconds… 5 minutes is longer than needed")
         * and the same number task U-8's `GraceCoordinator` defaults to.
         */
        const val DEFAULT_BACKGROUND_GRACE_MILLIS: Long = BACKGROUND_GRACE_90_SECONDS_MS

        /**
         * Issue #526 / #2526: bounds + default for the composer agent-submit
         * Enter delay (ms). After typing the message text into the pane the
         * composer waits this long, then sends Enter as a separate PTY write
         * so a fast Enter does not race ahead of the agent TUI's paste
         * ingestion (which leaves the message sitting unsent).
         *
         * - Default 150ms sits in the maintainer-suggested 100–300ms band:
         *   long enough for Claude Code / Codex to finish ingesting a typical
         *   composer message before the submit Enter, short enough that Send
         *   still feels instant.
         * - The floor is 0ms (back-to-back) for users whose agent never
         *   races; the ceiling 1000ms covers a sluggish TUI without letting
         *   a hand-edited prefs value make Send feel broken.
         * - The slider grain is 50ms, matching v0.4.47.
         */
        const val MIN_AGENT_SUBMIT_ENTER_DELAY_MS: Int = 0
        const val MAX_AGENT_SUBMIT_ENTER_DELAY_MS: Int = 1000
        const val DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS: Int = 150
        const val AGENT_SUBMIT_ENTER_DELAY_STEP_MS: Int = 50

        /**
         * The offered grace windows, ascending.
         *
         * A fixed option list rather than a slider because the value is a
         * battery/convenience trade-off with no meaningful resolution between
         * stops, and because a stored value outside the list is normalised back
         * to the default — which is only a safe rule when the list is the whole
         * domain.
         */
        val BACKGROUND_GRACE_OPTIONS: List<BackgroundGraceOption> = listOf(
            BackgroundGraceOption(BACKGROUND_GRACE_30_SECONDS_MS, "30 sec"),
            BackgroundGraceOption(BACKGROUND_GRACE_1_MINUTE_MS, "1 min"),
            BackgroundGraceOption(BACKGROUND_GRACE_90_SECONDS_MS, "90 sec"),
            BackgroundGraceOption(BACKGROUND_GRACE_5_MINUTES_MS, "5 min"),
            BackgroundGraceOption(BACKGROUND_GRACE_10_MINUTES_MS, "10 min"),
        )
    }
}

/** A code/label pair for the voice-language picker. */
data class VoiceLanguageOption(val code: String, val label: String)

/** A millis/label pair for the background-grace picker. */
data class BackgroundGraceOption(val millis: Long, val label: String)
