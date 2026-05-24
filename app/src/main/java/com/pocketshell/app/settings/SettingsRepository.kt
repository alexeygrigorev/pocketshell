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

    private fun readSnapshot(): AppSettings {
        val themeName = prefs.getString(KEY_THEME, ThemePreference.System.name)
            ?: ThemePreference.System.name
        val theme = runCatching { ThemePreference.valueOf(themeName) }
            .getOrDefault(ThemePreference.System)
        val font = prefs.getFloat(KEY_TERMINAL_FONT_SP, AppSettings.DEFAULT_TERMINAL_FONT_SP)
            .coerceIn(AppSettings.MIN_TERMINAL_FONT_SP, AppSettings.MAX_TERMINAL_FONT_SP)
        val tmux = prefs.getBoolean(KEY_TMUX_ON_ATTACH, true)
        val language = prefs.getString(KEY_VOICE_LANGUAGE, AppSettings.VOICE_LANGUAGE_AUTO)
            ?.trim()
            ?.lowercase()
            ?.ifEmpty { AppSettings.VOICE_LANGUAGE_AUTO }
            ?: AppSettings.VOICE_LANGUAGE_AUTO
        val silence = prefs.getFloat(
            KEY_VOICE_SILENCE_SECONDS,
            AppSettings.DEFAULT_VOICE_SILENCE_SECONDS,
        ).coerceIn(
            AppSettings.MIN_VOICE_SILENCE_SECONDS,
            AppSettings.MAX_VOICE_SILENCE_SECONDS,
        )
        return AppSettings(
            theme = theme,
            terminalFontSizeSp = font,
            tmuxOnAttachByDefault = tmux,
            voiceLanguage = language,
            voiceSilenceThresholdSeconds = silence,
        )
    }

    private companion object {
        const val PREFS_NAME = "app_settings"
        const val KEY_THEME = "theme"
        const val KEY_TERMINAL_FONT_SP = "terminal_font_sp"
        const val KEY_TMUX_ON_ATTACH = "tmux_on_attach_default"
        const val KEY_VOICE_LANGUAGE = "voice_language"
        const val KEY_VOICE_SILENCE_SECONDS = "voice_silence_seconds"
    }
}
