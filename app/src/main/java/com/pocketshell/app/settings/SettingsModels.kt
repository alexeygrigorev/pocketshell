package com.pocketshell.app.settings

import com.pocketshell.core.terminal.ui.TerminalKeyboardMode

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
 * Speech-to-text backend used by the prompt composer mic.
 *
 * [OpenAiWhisper] is the existing buffered WAV -> OpenAI Whisper path. It
 * requires a saved OpenAI API key and keeps the local no-speech/silence guard
 * before upload. [AndroidSpeech] delegates recognition to Android's system
 * [android.speech.SpeechRecognizer] service, usually backed by Google on GMS
 * devices. That path does not need an OpenAI key and can surface partial
 * hypotheses while the user is still speaking, but availability, language
 * support, network use, and privacy handling depend on the installed recognizer
 * service.
 */
enum class VoiceTranscriptionProvider {
    OpenAiWhisper,
    AndroidSpeech,
}

/**
 * Which tab an AGENT session lands on when it is first opened / first
 * detected. Issue #818.
 *
 * [Conversation] (the default) opens the parsed agent Conversation view —
 * the readable surface. This is the permanent cure for the recurring
 * "black screen" reports: a raw agent TUI uses the alternate-screen buffer
 * and renders mostly black when idle, whereas the parsed Conversation is
 * always legible. It is only a safe default now that the Conversation view
 * opens fast (#828: 264–280ms @ 80ms RTT) and binds to the correct source
 * (#825).
 *
 * [Terminal] opens the raw terminal view instead — the pre-#818 behaviour —
 * for users who prefer to watch the live TUI.
 *
 * IMPORTANT — open-time only. This preference selects the INITIAL tab when an
 * agent session opens. It must NEVER drive a mid-session switch: a user who is
 * already viewing a live session's Terminal must not be yanked to Conversation
 * on a later detection/refresh (that is the #815 regression line, reverted in
 * commit 207d33e5). A remembered/explicit per-session tab choice still wins
 * over this global default (the existing memory-seeding precedence). Shell
 * (non-agent) sessions have no Conversation tab and are unaffected.
 */
enum class DefaultAgentSessionView {
    Conversation,
    Terminal,
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
 * @property terminalKeyboardMode raw-command terminal keyboard by default,
 *   or explicit smart text mode. Smart text requests IME suggestions and
 *   swipe input but stages committed text until Enter confirms the buffer.
 * @property voiceTranscriptionProvider speech-to-text backend for prompt
 *   composer voice input. Defaults to [VoiceTranscriptionProvider.OpenAiWhisper]
 *   so existing users keep the current Whisper behaviour until they opt into
 *   the Android/system recognizer.
 * @property backgroundGraceMillis bounded process-background grace window
 *   before the app tears down live terminal SSH/tmux connections. Defaults to
 *   60 seconds, preserving the original issue #450 behaviour.
 */
data class AppSettings(
    val terminalFontSizeSp: Float = DEFAULT_TERMINAL_FONT_SP,
    val conversationFontSizeSp: Float = DEFAULT_CONVERSATION_FONT_SP,
    val terminalKeyboardMode: TerminalKeyboardMode = TerminalKeyboardMode.RawCommand,
    val tmuxOnAttachByDefault: Boolean = true,
    val defaultHostId: Long? = null,
    val voiceLanguage: String = VOICE_LANGUAGE_AUTO,
    val voiceSilenceThresholdSeconds: Float = DEFAULT_VOICE_SILENCE_SECONDS,
    val voiceTranscriptionProvider: VoiceTranscriptionProvider = DEFAULT_VOICE_TRANSCRIPTION_PROVIDER,
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
     * Issue #526 / #869: the MINIMUM time PocketShell waits after typing the
     * composer's message text into the agent pane before pressing the submit
     * Enter, in milliseconds. The composer's Send writes the text and the
     * submit Enter as two separate `send-keys` calls; on a busy TUI (Claude
     * Code, Codex) an Enter that lands before the agent has finished ingesting
     * the pasted text is not treated as submit, so the message sits unsent in
     * the input line ("most of the time when I click Send it's not really
     * sending").
     *
     * Issue #869 made the submit ACK-GATED: after this floor PocketShell polls
     * `capture-pane` and only presses Enter once the paste is visible in the
     * agent input (RTT-adaptive, bounded). This value is now the floor /
     * minimum wait rather than the whole delay — Send confirms the paste
     * landed regardless of how it is set, so a low value no longer reintroduces
     * the missed-submit race. Default [DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS];
     * tunable between [MIN_AGENT_SUBMIT_ENTER_DELAY_MS] and
     * [MAX_AGENT_SUBMIT_ENTER_DELAY_MS] via the Settings → Terminal slider.
     */
    val agentSubmitEnterDelayMs: Int = DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS,
    val backgroundGraceMillis: Long = DEFAULT_BACKGROUND_GRACE_MILLIS,
    /**
     * Issue #549: always-on diagnostics flight recorder. On by default;
     * records bounded, privacy-redacted connection/action metadata to an
     * in-app JSONL log that can be shared manually from Settings.
     */
    val diagnosticsRecordingEnabled: Boolean = DEFAULT_DIAGNOSTICS_RECORDING_ENABLED,
    /**
     * Issue #818: which tab an agent session opens on (Conversation /
     * Terminal). Defaults to [DefaultAgentSessionView.Conversation] — the
     * black-screen cure. Read only at open/initial-tab time; never drives a
     * mid-session switch (#815).
     */
    val defaultAgentSessionView: DefaultAgentSessionView = DEFAULT_AGENT_SESSION_VIEW,
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

        val DEFAULT_VOICE_TRANSCRIPTION_PROVIDER: VoiceTranscriptionProvider =
            VoiceTranscriptionProvider.OpenAiWhisper

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

        /**
         * Issue #869: the MINIMUM total wait before the submit Enter on the
         * **needle-miss fallback** path — when the ack poll never confirmed the
         * paste landed (an unrecognised TUI rendering, or `capture-pane` kept
         * failing). On that path the ack-gate cannot prove ingestion finished,
         * so it must NOT degrade to the pre-#869 ~150ms blind delay that caused
         * the maintainer's missed-submit ("most of the time when I click Send
         * it's not really sending"). Instead the WORST case is an ADEQUATE
         * working delay: at least this floor PLUS an RTT-proportional addend
         * (the measured `-CC` round-trip from the capture polls), so a
         * high-latency host waits proportionally longer. 350ms is the bottom of
         * the safe band — comfortably above the 150ms that raced under real RTT,
         * still short enough that a one-off needle miss does not make Send feel
         * laggy. This floor applies ONLY to the fallback; the happy path (a
         * capture confirms the paste) still submits the instant ingestion is
         * observed.
         */
        const val AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS: Long = 350L

        // Issue #969: recording is ON by default so a FRESH install captures the
        // FIRST reconnect — the one that matters most for diagnosing the
        // maintainer's stability complaints, previously lost because recording
        // defaulted off (#549's opt-in). The Settings → Diagnostics toggle still
        // turns it OFF; the single gate in DiagnosticRecorder.record() reads this
        // through SettingsRepository, so the default just flips the starting
        // value (hard-cut, D22 — no always-on fallback branch anywhere).
        const val DEFAULT_DIAGNOSTICS_RECORDING_ENABLED: Boolean = true
        const val AGENT_SUBMIT_ENTER_DELAY_STEP_MS: Int = 50

        /**
         * Issue #818: default tab for a freshly-opened agent session.
         * Conversation is the cure for the recurring black-screen reports
         * (raw agent TUIs render mostly black on the alt-screen buffer; the
         * parsed Conversation is always legible) and is safe now that the
         * Conversation view opens fast (#828) and binds the right source
         * (#825). Users who prefer the raw terminal can switch this to
         * Terminal in Settings.
         */
        val DEFAULT_AGENT_SESSION_VIEW: DefaultAgentSessionView =
            DefaultAgentSessionView.Conversation

        const val BACKGROUND_GRACE_30_SECONDS_MS: Long = 30_000L
        const val DEFAULT_BACKGROUND_GRACE_MILLIS: Long = 60_000L
        const val BACKGROUND_GRACE_5_MINUTES_MS: Long = 5 * 60_000L
        const val BACKGROUND_GRACE_10_MINUTES_MS: Long = 10 * 60_000L

        val BACKGROUND_GRACE_OPTIONS: List<BackgroundGraceOption> = listOf(
            BackgroundGraceOption(BACKGROUND_GRACE_30_SECONDS_MS, "30 sec"),
            BackgroundGraceOption(DEFAULT_BACKGROUND_GRACE_MILLIS, "1 min"),
            BackgroundGraceOption(BACKGROUND_GRACE_5_MINUTES_MS, "5 min"),
            BackgroundGraceOption(BACKGROUND_GRACE_10_MINUTES_MS, "10 min"),
        )
    }
}

/**
 * Display tuple for the language dropdown — a code/label pair so the
 * persistence layer stores the stable ISO code while the UI renders a
 * human-friendly string. Lives at file scope so test fakes can build
 * their own option lists without instantiating [AppSettings].
 */
data class VoiceLanguageOption(val code: String, val label: String)

/**
 * Display tuple for the bounded terminal background grace setting. The
 * repository persists only [millis]; the UI renders [label].
 */
data class BackgroundGraceOption(val millis: Long, val label: String)
