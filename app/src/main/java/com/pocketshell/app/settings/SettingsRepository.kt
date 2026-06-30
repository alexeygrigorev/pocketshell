package com.pocketshell.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence + observable surface for [AppSettings].
 *
 * Backed by [SharedPreferences] to mirror the project's existing
 * preference stores (see
 * [com.pocketshell.app.systemsurfaces.SystemSurfaceStateStore]). DataStore
 * would be the modern choice but pulling in
 * `androidx.datastore:datastore-preferences` would add a new version
 * catalog entry without buying any feature we need here — the settings
 * payload is tiny, write traffic is one-edit-per-user-tap, and
 * SharedPreferences is already on the classpath transitively. Future
 * issues are free to migrate.
 *
 * Reads are synchronous: the constructor seeds the in-memory
 * [MutableStateFlow] from disk so the first `state.value` after
 * construction is the persisted snapshot. Writes route through the same
 * StateFlow + a synchronous `apply()` to the prefs file — observers see
 * the new value before the call returns, which makes setting changes
 * feel instant from the UI thread.
 *
 * Singleton scope: the same instance is shared between the activity-level
 * settings observation and the [SettingsViewModel], so writes in the
 * settings screen are immediately visible at the composable root without
 * any cross-scope plumbing.
 *
 * ## Off-main construction + splash preload (issue #1088)
 *
 * This `@Singleton` is built during `App.onCreate` Hilt field injection — on
 * the **Main** thread, before the first frame. The old constructor opened the
 * `app_settings` prefs file (`getSharedPreferences(...)` does a synchronous
 * first-touch disk read) AND read ~15 keys (`readSnapshot()`) eagerly, so it
 * blocked Main during cold launch — the same launch-path freeze class as the
 * #1087 batch off-main fixes (`UpdateCheckStore`, `LastSessionStore`, …).
 *
 * It was deferred from #1087 because [settings] is consumed at FIRST
 * COMPOSITION (the theme / per-pane config), so a naive async seed would
 * flash a default→persisted UI config (theme/settings pop). The fix:
 *
 * - The prefs open + [readSnapshot] run OFF Main, on [Dispatchers.IO], as an
 *   eager [async] kicked off from the constructor. Because construction itself
 *   happens during `App.onCreate`, that async IS the splash/startup-window
 *   PRELOAD — it warms while the activity inflates, before `setContent`. A
 *   [StartupTiming] mark fires when it completes for cold-launch observability.
 * - [settings] is created LAZILY (a getter over the lazy [_settings]); its
 *   initial value is the warm snapshot. The very first value the composition
 *   observes is therefore the persisted snapshot — never a default — so there
 *   is no flash. The first read blocks only on the await, which by first
 *   composition is already complete (warmed during the splash window), so it
 *   does not block launch.
 *
 * Hard-cut (D22): there is no synchronous on-Main read path. Reads/writes route
 * through [prefs], which warms-or-awaits the off-main build.
 */
@Singleton
class SettingsRepository @VisibleForTesting internal constructor(
    context: Context,
    /**
     * Test-only gate (#1088). When non-null, the off-main warm-up [async]
     * blocks on this latch BEFORE it builds the snapshot, so a test can hold
     * the snapshot build provably in-flight at the moment `settings.value` is
     * first read — the deterministic TIMING1 seam that proves the no-default-
     * flash property (a racy seed-default-then-update would expose the default
     * here, the blocking-await shipped path returns the persisted snapshot).
     * `null` in production (the [Inject] constructor), so this is a pure
     * no-op on the real launch path.
     */
    private val warmUpGate: CountDownLatch?,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(context, warmUpGate = null)

    private val appContext: Context = context.applicationContext

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Volatile
    private var snapshotBuildThreadName: String? = null

    private val warmUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Eager off-main preload of the prefs file + persisted snapshot. Kicked off
     * from the constructor (i.e. during `App.onCreate` injection) so it warms
     * during the splash/startup window and is ready by first composition.
     */
    private val snapshotDeferred: Deferred<AppSettings> = warmUpScope.async {
        // Test-only: hold the build in-flight until the gate opens. No-op in
        // production (gate == null). Placed BEFORE the snapshot is built so the
        // deferred cannot complete — and `_settings`' blocking-await cannot
        // return — while a test is reading the first value.
        warmUpGate?.await()
        snapshotBuildThreadName = currentPhysicalThreadName()
        val prefs = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { cachedPrefs = it }
        val snapshot = readSnapshot(prefs)
        StartupTiming.markOnce("settings-snapshot-preloaded")
        snapshot
    }

    private val prefs: SharedPreferences
        get() = cachedPrefs ?: runBlocking {
            snapshotDeferred.await()
            cachedPrefs ?: error("settings prefs not cached after off-main build")
        }

    private val _settings: MutableStateFlow<AppSettings> by lazy {
        MutableStateFlow(runBlocking { snapshotDeferred.await() })
    }

    /**
     * Hot, always-current snapshot of [AppSettings]. A getter (not an eager
     * `val`) so the backing [_settings] — and its blocking await on the warm
     * snapshot — is initialised at first read (first composition), NOT at
     * construction/injection. By first composition the preload is warm, so the
     * initial value is the persisted snapshot (no default flash) and the read
     * does not block launch.
     */
    val settings: StateFlow<AppSettings>
        get() = _settings.asStateFlow()

    /**
     * Test-only: block until the off-main preload completes and return the name
     * of the PHYSICAL thread it ran on (#1088). Proves the prefs open +
     * snapshot read did NOT run on the constructing (Main) thread.
     */
    @VisibleForTesting
    internal fun awaitSnapshotBuildThreadNameForTest(): String {
        runBlocking { snapshotDeferred.await() }
        return snapshotBuildThreadName
            ?: error("settings snapshot build thread was not recorded")
    }

    // The build runs inside a coroutine, whose framework decorates the thread
    // name with a " @coroutine#N" suffix. Strip it so the recorded value is the
    // PHYSICAL thread name — otherwise an on-Main build (e.g. the un-fixed base)
    // would still differ from the captured constructing name by the suffix
    // alone, giving a false off-main pass (#1088 G6: keep the assertion
    // load-bearing).
    private fun currentPhysicalThreadName(): String =
        Thread.currentThread().name.substringBefore(" @coroutine")

    fun setTerminalFontSizeSp(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(
            AppSettings.MIN_TERMINAL_FONT_SP,
            AppSettings.MAX_TERMINAL_FONT_SP,
        )
        if (_settings.value.terminalFontSizeSp == clamped) return
        prefs.edit().putFloat(KEY_TERMINAL_FONT_SP, clamped).apply()
        _settings.value = _settings.value.copy(terminalFontSizeSp = clamped)
    }

    fun setTerminalKeyboardMode(mode: TerminalKeyboardMode) {
        if (_settings.value.terminalKeyboardMode == mode) return
        prefs.edit().putString(KEY_TERMINAL_KEYBOARD_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(terminalKeyboardMode = mode)
    }

    /**
     * Persist the user's preferred conversation message-body font size.
     * Issue #496. Clamped to [AppSettings.MIN_CONVERSATION_FONT_SP] /
     * [AppSettings.MAX_CONVERSATION_FONT_SP] so a hand-edited prefs file
     * or a slider rounding error can't push the conversation text below a
     * legible floor or above a size that breaks the dense-turn layout.
     */
    fun setConversationFontSizeSp(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(
            AppSettings.MIN_CONVERSATION_FONT_SP,
            AppSettings.MAX_CONVERSATION_FONT_SP,
        )
        if (_settings.value.conversationFontSizeSp == clamped) return
        prefs.edit().putFloat(KEY_CONVERSATION_FONT_SP, clamped).apply()
        _settings.value = _settings.value.copy(conversationFontSizeSp = clamped)
    }

    fun setTmuxOnAttachByDefault(enabled: Boolean) {
        if (_settings.value.tmuxOnAttachByDefault == enabled) return
        prefs.edit().putBoolean(KEY_TMUX_ON_ATTACH, enabled).apply()
        _settings.value = _settings.value.copy(tmuxOnAttachByDefault = enabled)
    }

    fun setDefaultHostId(hostId: Long?) {
        val normalised = hostId?.takeIf { it > 0L }
        if (_settings.value.defaultHostId == normalised) return
        val edit = prefs.edit()
        if (normalised == null) {
            edit.remove(KEY_DEFAULT_HOST_ID)
        } else {
            edit.putLong(KEY_DEFAULT_HOST_ID, normalised)
        }
        edit.apply()
        _settings.value = _settings.value.copy(defaultHostId = normalised)
    }

    /**
     * Persist the user's preferred Whisper language. [code] is either
     * [AppSettings.VOICE_LANGUAGE_AUTO] (Whisper auto-detect) or an
     * ISO-639-1 string matching one of [AppSettings.VOICE_LANGUAGE_OPTIONS].
     * Values not in the option list are accepted but coerced to lowercase
     * — `SettingsViewModel` validates against the menu, so unknown codes
     * can only arrive from a hand-edited prefs file.
     */
    fun setVoiceLanguage(code: String) {
        val normalised = code.trim().lowercase().ifEmpty { AppSettings.VOICE_LANGUAGE_AUTO }
        if (_settings.value.voiceLanguage == normalised) return
        prefs.edit().putString(KEY_VOICE_LANGUAGE, normalised).apply()
        _settings.value = _settings.value.copy(voiceLanguage = normalised)
    }

    fun setVoiceTranscriptionProvider(provider: VoiceTranscriptionProvider) {
        if (_settings.value.voiceTranscriptionProvider == provider) return
        prefs.edit().putString(KEY_VOICE_TRANSCRIPTION_PROVIDER, provider.name).apply()
        _settings.value = _settings.value.copy(voiceTranscriptionProvider = provider)
    }

    /**
     * Persist the user's preferred auto-stop silence threshold (seconds).
     * Clamped to [AppSettings.MIN_VOICE_SILENCE_SECONDS] /
     * [AppSettings.MAX_VOICE_SILENCE_SECONDS] so a hand-edited prefs file
     * or a slider rounding error can't push the threshold below the
     * 50ms sample interval (which would auto-stop instantly) or above a
     * window so long the user assumes the mic is broken.
     */
    fun setVoiceSilenceThresholdSeconds(seconds: Float) {
        val clamped = seconds.coerceIn(
            AppSettings.MIN_VOICE_SILENCE_SECONDS,
            AppSettings.MAX_VOICE_SILENCE_SECONDS,
        )
        if (_settings.value.voiceSilenceThresholdSeconds == clamped) return
        prefs.edit().putFloat(KEY_VOICE_SILENCE_SECONDS, clamped).apply()
        _settings.value = _settings.value.copy(voiceSilenceThresholdSeconds = clamped)
    }

    /**
     * Persist the conversation-pane system-notes toggle. Issue #176. When
     * false, the renderer drops XML-tagged blocks (`<system-reminder>`,
     * `<command-name>`, `<local-command-stdout>`, …) from the visible
     * feed. When true, they remain visible as muted collapsible rows.
     */
    fun setShowSystemNotes(enabled: Boolean) {
        if (_settings.value.showSystemNotes == enabled) return
        prefs.edit().putBoolean(KEY_SHOW_SYSTEM_NOTES, enabled).apply()
        _settings.value = _settings.value.copy(showSystemNotes = enabled)
    }

    fun setHostDetailViewMode(mode: HostDetailViewMode) {
        if (_settings.value.hostDetailViewMode == mode) return
        prefs.edit().putString(KEY_HOST_DETAIL_VIEW_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(hostDetailViewMode = mode)
    }

    /**
     * Persist the user-configurable "approaching limit" threshold for
     * the in-app usage warning surfaces. Issue #214. Clamped to
     * [AppSettings.MIN_USAGE_WARN_PERCENT] /
     * [AppSettings.MAX_USAGE_WARN_PERCENT] so a hand-edited prefs file
     * or a slider rounding error can't push the threshold outside the
     * useful range. The slider's UI grain is
     * [AppSettings.USAGE_WARN_PERCENT_STEP], so the persisted value is
     * snapped to a multiple of that step on the way in.
     */
    fun setUsageWarnThresholdPercent(percent: Int) {
        val snapped = snapUsageWarnThreshold(percent)
        if (_settings.value.usageWarnThresholdPercent == snapped) return
        prefs.edit().putInt(KEY_USAGE_WARN_THRESHOLD, snapped).apply()
        _settings.value = _settings.value.copy(usageWarnThresholdPercent = snapped)
    }

    /**
     * Persist the composer's agent-submit Enter delay (ms). Issue #526.
     * Clamped to [AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS] /
     * [AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS] and snapped to the
     * slider grid so a hand-edited prefs file or slider rounding can't push
     * the delay outside the useful band. The send path reads this value to
     * decide how long to wait after typing the message text before pressing
     * the submit Enter (closing the agent-TUI paste-ingest race).
     */
    fun setAgentSubmitEnterDelayMs(delayMs: Int) {
        val snapped = snapAgentSubmitEnterDelay(delayMs)
        if (_settings.value.agentSubmitEnterDelayMs == snapped) return
        prefs.edit().putInt(KEY_AGENT_SUBMIT_ENTER_DELAY_MS, snapped).apply()
        _settings.value = _settings.value.copy(agentSubmitEnterDelayMs = snapped)
    }

    /**
     * Persist the bounded process-background grace window before terminal
     * SSH/tmux teardown. Only the conservative predefined options are
     * accepted; unsupported hand-edited values fall back to the 60s default.
     */
    fun setBackgroundGraceMillis(millis: Long) {
        val supported = normaliseBackgroundGraceMillis(millis)
        if (_settings.value.backgroundGraceMillis == supported) return
        prefs.edit().putLong(KEY_BACKGROUND_GRACE_MILLIS, supported).apply()
        _settings.value = _settings.value.copy(backgroundGraceMillis = supported)
    }

    /**
     * Persist which tab an agent session opens on. Issue #818. Read at
     * open/initial-tab time by the session-open path; never drives a
     * mid-session switch (#815).
     */
    fun setDefaultAgentSessionView(view: DefaultAgentSessionView) {
        if (_settings.value.defaultAgentSessionView == view) return
        prefs.edit().putString(KEY_DEFAULT_AGENT_SESSION_VIEW, view.name).apply()
        _settings.value = _settings.value.copy(defaultAgentSessionView = view)
    }

    fun setDiagnosticsRecordingEnabled(enabled: Boolean) {
        if (_settings.value.diagnosticsRecordingEnabled == enabled) return
        prefs.edit().putBoolean(KEY_DIAGNOSTICS_RECORDING_ENABLED, enabled).apply()
        _settings.value = _settings.value.copy(diagnosticsRecordingEnabled = enabled)
    }

    private fun readSnapshot(prefs: SharedPreferences): AppSettings {
        val font = prefs.safeFloat(KEY_TERMINAL_FONT_SP, AppSettings.DEFAULT_TERMINAL_FONT_SP)
            .coerceIn(AppSettings.MIN_TERMINAL_FONT_SP, AppSettings.MAX_TERMINAL_FONT_SP)
        val conversationFont = prefs.safeFloat(
            KEY_CONVERSATION_FONT_SP,
            AppSettings.DEFAULT_CONVERSATION_FONT_SP,
        ).coerceIn(
            AppSettings.MIN_CONVERSATION_FONT_SP,
            AppSettings.MAX_CONVERSATION_FONT_SP,
        )
        val terminalKeyboardModeName = prefs.safeString(
            KEY_TERMINAL_KEYBOARD_MODE,
            TerminalKeyboardMode.RawCommand.name,
        ) ?: TerminalKeyboardMode.RawCommand.name
        val terminalKeyboardMode = runCatching { TerminalKeyboardMode.valueOf(terminalKeyboardModeName) }
            .getOrDefault(TerminalKeyboardMode.RawCommand)
        val tmux = prefs.safeBoolean(KEY_TMUX_ON_ATTACH, true)
        val language = prefs.safeString(KEY_VOICE_LANGUAGE, AppSettings.VOICE_LANGUAGE_AUTO)
            ?.trim()
            ?.lowercase()
            ?.ifEmpty { AppSettings.VOICE_LANGUAGE_AUTO }
            ?: AppSettings.VOICE_LANGUAGE_AUTO
        val defaultHostId = prefs.safeLong(KEY_DEFAULT_HOST_ID, 0L)
            .takeIf { it > 0L }
        val silence = prefs.safeFloat(
            KEY_VOICE_SILENCE_SECONDS,
            AppSettings.DEFAULT_VOICE_SILENCE_SECONDS,
        ).coerceIn(
            AppSettings.MIN_VOICE_SILENCE_SECONDS,
            AppSettings.MAX_VOICE_SILENCE_SECONDS,
        )
        val voiceProviderName = prefs.safeString(
            KEY_VOICE_TRANSCRIPTION_PROVIDER,
            AppSettings.DEFAULT_VOICE_TRANSCRIPTION_PROVIDER.name,
        ) ?: AppSettings.DEFAULT_VOICE_TRANSCRIPTION_PROVIDER.name
        val voiceProvider = runCatching {
            VoiceTranscriptionProvider.valueOf(voiceProviderName)
        }.getOrDefault(AppSettings.DEFAULT_VOICE_TRANSCRIPTION_PROVIDER)
        val showSystemNotes = prefs.safeBoolean(
            KEY_SHOW_SYSTEM_NOTES,
            AppSettings.DEFAULT_SHOW_SYSTEM_NOTES,
        )
        val hostDetailViewModeName = prefs.safeString(
            KEY_HOST_DETAIL_VIEW_MODE,
            HostDetailViewMode.Tree.name,
        ) ?: HostDetailViewMode.Tree.name
        val hostDetailViewMode = runCatching { HostDetailViewMode.valueOf(hostDetailViewModeName) }
            .getOrDefault(HostDetailViewMode.Tree)
        val usageWarnPercent = snapUsageWarnThreshold(
            prefs.safeInt(
                KEY_USAGE_WARN_THRESHOLD,
                AppSettings.DEFAULT_USAGE_WARN_PERCENT,
            ),
        )
        val agentSubmitEnterDelayMs = snapAgentSubmitEnterDelay(
            prefs.safeInt(
                KEY_AGENT_SUBMIT_ENTER_DELAY_MS,
                AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS,
            ),
        )
        val backgroundGraceMillis = normaliseBackgroundGraceMillis(
            prefs.safeLong(
                KEY_BACKGROUND_GRACE_MILLIS,
                AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS,
            ),
        )
        val diagnosticsRecordingEnabled = prefs.safeBoolean(
            KEY_DIAGNOSTICS_RECORDING_ENABLED,
            AppSettings.DEFAULT_DIAGNOSTICS_RECORDING_ENABLED,
        )
        val defaultAgentSessionViewName = prefs.safeString(
            KEY_DEFAULT_AGENT_SESSION_VIEW,
            AppSettings.DEFAULT_AGENT_SESSION_VIEW.name,
        ) ?: AppSettings.DEFAULT_AGENT_SESSION_VIEW.name
        val defaultAgentSessionView = runCatching {
            DefaultAgentSessionView.valueOf(defaultAgentSessionViewName)
        }.getOrDefault(AppSettings.DEFAULT_AGENT_SESSION_VIEW)
        return AppSettings(
            terminalFontSizeSp = font,
            conversationFontSizeSp = conversationFont,
            terminalKeyboardMode = terminalKeyboardMode,
            tmuxOnAttachByDefault = tmux,
            defaultHostId = defaultHostId,
            voiceLanguage = language,
            voiceSilenceThresholdSeconds = silence,
            voiceTranscriptionProvider = voiceProvider,
            showSystemNotes = showSystemNotes,
            hostDetailViewMode = hostDetailViewMode,
            usageWarnThresholdPercent = usageWarnPercent,
            agentSubmitEnterDelayMs = agentSubmitEnterDelayMs,
            backgroundGraceMillis = backgroundGraceMillis,
            diagnosticsRecordingEnabled = diagnosticsRecordingEnabled,
            defaultAgentSessionView = defaultAgentSessionView,
        )
    }

    /**
     * Snap [delayMs] to the nearest
     * [AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS] grid point within
     * [AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS] /
     * [AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS]. Issue #526. Persistence
     * and read both route through this helper so a legacy or hand-edited
     * prefs value lands on a slider stop and stays inside the useful band.
     */
    private fun snapAgentSubmitEnterDelay(delayMs: Int): Int {
        val clamped = delayMs.coerceIn(
            AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS,
            AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS,
        )
        val step = AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS
        val snapped = ((clamped + step / 2) / step) * step
        return snapped.coerceIn(
            AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS,
            AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS,
        )
    }

    /**
     * Snap [percent] to the nearest [AppSettings.USAGE_WARN_PERCENT_STEP]
     * grid point within [AppSettings.MIN_USAGE_WARN_PERCENT] /
     * [AppSettings.MAX_USAGE_WARN_PERCENT]. Persistence and UI both
     * route through this helper so a legacy prefs file with a 73 value
     * shows up as 75 in the slider, and a slider drag that lands on
     * 82.7 % gets stored as 80 %.
     */
    private fun snapUsageWarnThreshold(percent: Int): Int {
        val clamped = percent.coerceIn(
            AppSettings.MIN_USAGE_WARN_PERCENT,
            AppSettings.MAX_USAGE_WARN_PERCENT,
        )
        val step = AppSettings.USAGE_WARN_PERCENT_STEP
        val snapped = ((clamped + step / 2) / step) * step
        return snapped.coerceIn(
            AppSettings.MIN_USAGE_WARN_PERCENT,
            AppSettings.MAX_USAGE_WARN_PERCENT,
        )
    }

    private fun normaliseBackgroundGraceMillis(millis: Long): Long =
        AppSettings.BACKGROUND_GRACE_OPTIONS
            .firstOrNull { it.millis == millis }
            ?.millis
            ?: AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS

    private fun SharedPreferences.safeString(key: String, default: String?): String? =
        runCatching { getString(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private fun SharedPreferences.safeFloat(key: String, default: Float): Float =
        runCatching { getFloat(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private fun SharedPreferences.safeBoolean(key: String, default: Boolean): Boolean =
        runCatching { getBoolean(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private fun SharedPreferences.safeInt(key: String, default: Int): Int =
        runCatching { getInt(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private fun SharedPreferences.safeLong(key: String, default: Long): Long =
        runCatching { getLong(key, default) }
            .getOrElse {
                edit().remove(key).apply()
                default
            }

    private companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
        const val KEY_CONVERSATION_FONT_SP = "conversation_font_sp"
        const val KEY_TERMINAL_KEYBOARD_MODE = "terminal_keyboard_mode"
        const val KEY_TMUX_ON_ATTACH = "tmux_on_attach_default"
        const val KEY_DEFAULT_HOST_ID = "default_host_id"
        const val KEY_VOICE_LANGUAGE = "voice_language"
        const val KEY_VOICE_SILENCE_SECONDS = "voice_silence_seconds"
        const val KEY_VOICE_TRANSCRIPTION_PROVIDER = "voice_transcription_provider"
        const val KEY_SHOW_SYSTEM_NOTES = "show_system_notes"
        const val KEY_HOST_DETAIL_VIEW_MODE = "host_detail_view_mode"
        const val KEY_USAGE_WARN_THRESHOLD = "usage_warn_threshold_percent"
        const val KEY_AGENT_SUBMIT_ENTER_DELAY_MS = "agent_submit_enter_delay_ms"
        const val KEY_BACKGROUND_GRACE_MILLIS = "background_grace_millis"
        const val KEY_DIAGNOSTICS_RECORDING_ENABLED = "diagnostics_recording_enabled"
        const val KEY_DEFAULT_AGENT_SESSION_VIEW = "default_agent_session_view"
    }
}
