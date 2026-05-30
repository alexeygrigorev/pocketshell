package com.pocketshell.app.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * the new value before the call returns, which makes the theme switch
 * feel instant from the UI thread.
 *
 * Singleton scope: the same instance is shared between the activity-level
 * theme observation and the [SettingsViewModel], so writes in the
 * settings screen are immediately visible at the composable root without
 * any cross-scope plumbing.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings: MutableStateFlow<AppSettings> = MutableStateFlow(readSnapshot())

    /** Hot, always-current snapshot of [AppSettings]. */
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setTheme(theme: ThemePreference) {
        if (_settings.value.theme == theme) return
        prefs.edit().putString(KEY_THEME, theme.name).apply()
        _settings.value = _settings.value.copy(theme = theme)
    }

    fun setTerminalFontSizeSp(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(
            AppSettings.MIN_TERMINAL_FONT_SP,
            AppSettings.MAX_TERMINAL_FONT_SP,
        )
        if (_settings.value.terminalFontSizeSp == clamped) return
        prefs.edit().putFloat(KEY_TERMINAL_FONT_SP, clamped).apply()
        _settings.value = _settings.value.copy(terminalFontSizeSp = clamped)
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

    private fun readSnapshot(): AppSettings {
        val themeName = prefs.safeString(KEY_THEME, ThemePreference.System.name)
            ?: ThemePreference.System.name
        val theme = runCatching { ThemePreference.valueOf(themeName) }
            .getOrDefault(ThemePreference.System)
        val font = prefs.safeFloat(KEY_TERMINAL_FONT_SP, AppSettings.DEFAULT_TERMINAL_FONT_SP)
            .coerceIn(AppSettings.MIN_TERMINAL_FONT_SP, AppSettings.MAX_TERMINAL_FONT_SP)
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
        return AppSettings(
            theme = theme,
            terminalFontSizeSp = font,
            tmuxOnAttachByDefault = tmux,
            defaultHostId = defaultHostId,
            voiceLanguage = language,
            voiceSilenceThresholdSeconds = silence,
            showSystemNotes = showSystemNotes,
            hostDetailViewMode = hostDetailViewMode,
            usageWarnThresholdPercent = usageWarnPercent,
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
        const val KEY_THEME = "theme"
        const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
        const val KEY_TMUX_ON_ATTACH = "tmux_on_attach_default"
        const val KEY_DEFAULT_HOST_ID = "default_host_id"
        const val KEY_VOICE_LANGUAGE = "voice_language"
        const val KEY_VOICE_SILENCE_SECONDS = "voice_silence_seconds"
        const val KEY_SHOW_SYSTEM_NOTES = "show_system_notes"
        const val KEY_HOST_DETAIL_VIEW_MODE = "host_detail_view_mode"
        const val KEY_USAGE_WARN_THRESHOLD = "usage_warn_threshold_percent"
    }
}
