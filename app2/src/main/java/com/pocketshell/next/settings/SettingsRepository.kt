package com.pocketshell.next.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persistence + the observable snapshot for [AppSettings] (rewrite task P-6).
 *
 * ## SharedPreferences, like the rest of app2's small stores
 *
 * Four scalars with one write per user tap. Room would need a schema bump for
 * state that is never queried relationally; DataStore would add a version
 * catalog entry for nothing. `ShowAllPortsStore` made the same call for the
 * same reason.
 *
 * ## Reads are synchronous, and that is deliberate
 *
 * The old client spent two issues (#1088, #1249) undoing an eager off-Main
 * preload it had added to hide a cold-launch stall, and landed on exactly what
 * is written here: open the file and read the keys synchronously, once, on
 * first use. The seed has to be the PERSISTED snapshot rather than a default
 * that is corrected a frame later, because the terminal's font size is read at
 * first composition and a default-then-update flashes a resize. One bounded
 * small-file read is a smaller cost than either the flash or the machinery that
 * avoided it.
 *
 * `by lazy` on both members is what keeps that read off `App.onCreate`: this is
 * a `@Singleton`, so an eager `val` would do disk I/O during Hilt field
 * injection on the main thread before the first frame.
 *
 * ## A corrupt preferences file degrades, it does not crash
 *
 * A truncated `next_settings.xml` (power loss, disk full) makes
 * `getSharedPreferences` itself throw, and that exception would surface on Main
 * at the first `collectAsState()` — a launch crash loop with no recovery short
 * of clearing app data. So the open is guarded, and a failure deletes the file
 * and retries on the fresh one, which makes the recovery durable AND leaves
 * writes working (D22: one path, no "degraded mode" flag).
 *
 * ## Fields that are stored but not yet read
 *
 * [AppSettings.voiceLanguage] is written here and read by nothing yet: app2's
 * dictation calls the recognizer with no language hint until task P-2 lands the
 * voice stack, which owns that call site. [AppSettings.usageWarnThresholdPercent]
 * is likewise the usage panel's (task P-5) to read.
 * [AppSettings.backgroundGraceMillis] is task U-8's. Each is a settings-surface
 * value its owning task consumes; the alternative — landing the screen without
 * them and editing it three more times — is worse. They are called out here so
 * "nothing reads this" is a known state, not a discovery.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private val _settings: MutableStateFlow<AppSettings> by lazy {
        MutableStateFlow(runCatching { readSnapshot(prefs) }.getOrElse { AppSettings() })
    }

    /** The current settings, hot. Never a default that is corrected later. */
    val settings: StateFlow<AppSettings>
        get() = _settings.asStateFlow()

    /** Terminal glyph size in raw device pixels, snapped to the slider grid. */
    fun setTerminalTextSizePx(sizePx: Int) {
        val snapped = snapTerminalTextSize(sizePx)
        if (_settings.value.terminalTextSizePx == snapped) return
        write { putInt(KEY_TERMINAL_TEXT_SIZE_PX, snapped) }
        _settings.value = _settings.value.copy(terminalTextSizePx = snapped)
    }

    /** Dictation language hint; blank or unknown normalises to auto-detect. */
    fun setVoiceLanguage(code: String) {
        val normalised = normaliseVoiceLanguage(code)
        if (_settings.value.voiceLanguage == normalised) return
        write { putString(KEY_VOICE_LANGUAGE, normalised) }
        _settings.value = _settings.value.copy(voiceLanguage = normalised)
    }

    /** "Approaching limit" percent for the usage panel, snapped to the grid. */
    fun setUsageWarnThresholdPercent(percent: Int) {
        val snapped = snapUsageWarnThreshold(percent)
        if (_settings.value.usageWarnThresholdPercent == snapped) return
        write { putInt(KEY_USAGE_WARN_THRESHOLD, snapped) }
        _settings.value = _settings.value.copy(usageWarnThresholdPercent = snapped)
    }

    /**
     * Background grace window. Only the offered options are accepted; anything
     * else (a hand-edited file, a value from an older option list) falls back to
     * the default rather than becoming an unreachable state the picker cannot
     * show as selected.
     */
    fun setBackgroundGraceMillis(millis: Long) {
        val supported = normaliseBackgroundGrace(millis)
        if (_settings.value.backgroundGraceMillis == supported) return
        write { putLong(KEY_BACKGROUND_GRACE_MILLIS, supported) }
        _settings.value = _settings.value.copy(backgroundGraceMillis = supported)
    }

    private fun write(edit: SharedPreferences.Editor.() -> Unit) {
        runCatching { prefs.edit().apply(edit).apply() }
    }

    private fun openPrefs(): SharedPreferences =
        runCatching { appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            .getOrElse {
                runCatching { appContext.deleteSharedPreferences(PREFS_NAME) }
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }

    /**
     * Every read is clamped/normalised on the way out, not only on the way in.
     * A value written by an older build with different bounds is otherwise a
     * setting the UI cannot represent — a slider pinned off its own track.
     */
    private fun readSnapshot(prefs: SharedPreferences): AppSettings = AppSettings(
        terminalTextSizePx = snapTerminalTextSize(
            prefs.safeInt(KEY_TERMINAL_TEXT_SIZE_PX, AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_PX),
        ),
        voiceLanguage = normaliseVoiceLanguage(
            prefs.safeString(KEY_VOICE_LANGUAGE, AppSettings.VOICE_LANGUAGE_AUTO),
        ),
        usageWarnThresholdPercent = snapUsageWarnThreshold(
            prefs.safeInt(KEY_USAGE_WARN_THRESHOLD, AppSettings.DEFAULT_USAGE_WARN_PERCENT),
        ),
        backgroundGraceMillis = normaliseBackgroundGrace(
            prefs.safeLong(KEY_BACKGROUND_GRACE_MILLIS, AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS),
        ),
    )

    private fun snapTerminalTextSize(sizePx: Int): Int = snap(
        value = sizePx,
        min = AppSettings.MIN_TERMINAL_TEXT_SIZE_PX,
        max = AppSettings.MAX_TERMINAL_TEXT_SIZE_PX,
        step = AppSettings.TERMINAL_TEXT_SIZE_STEP_PX,
    )

    private fun snapUsageWarnThreshold(percent: Int): Int = snap(
        value = percent,
        min = AppSettings.MIN_USAGE_WARN_PERCENT,
        max = AppSettings.MAX_USAGE_WARN_PERCENT,
        step = AppSettings.USAGE_WARN_PERCENT_STEP,
    )

    private fun normaliseVoiceLanguage(code: String?): String {
        val trimmed = code?.trim()?.lowercase().orEmpty()
        return AppSettings.VOICE_LANGUAGE_OPTIONS
            .firstOrNull { it.code == trimmed }
            ?.code
            ?: AppSettings.VOICE_LANGUAGE_AUTO
    }

    private fun normaliseBackgroundGrace(millis: Long): Long =
        AppSettings.BACKGROUND_GRACE_OPTIONS
            .firstOrNull { it.millis == millis }
            ?.millis
            ?: AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS

    /**
     * Clamps into range, then rounds to the nearest step measured FROM [min] —
     * not from zero. A grid anchored at zero would make 50/95 with a step of 5
     * work by luck and 16/48 with a step of 2 land off its own minimum.
     */
    private fun snap(value: Int, min: Int, max: Int, step: Int): Int {
        val clamped = value.coerceIn(min, max)
        val steps = ((clamped - min) + step / 2) / step
        return (min + steps * step).coerceIn(min, max)
    }

    /**
     * A key stored with the wrong type throws `ClassCastException` rather than
     * returning the default, which would otherwise turn one bad key into a
     * permanent launch failure. Removing it makes the recovery durable.
     */
    private fun SharedPreferences.safeInt(key: String, default: Int): Int =
        runCatching { getInt(key, default) }.getOrElse { drop(key); default }

    private fun SharedPreferences.safeLong(key: String, default: Long): Long =
        runCatching { getLong(key, default) }.getOrElse { drop(key); default }

    private fun SharedPreferences.safeString(key: String, default: String): String =
        runCatching { getString(key, default) ?: default }.getOrElse { drop(key); default }

    private fun SharedPreferences.drop(key: String) {
        runCatching { edit().remove(key).apply() }
    }

    private companion object {
        /**
         * app2's own file, NOT the old client's `app_settings`. The two schemas
         * share no keys worth migrating (nine of the old sixteen fields are
         * gone), and reusing the name would mean the X-4 `applicationId` rename
         * inherits a file full of settings for deleted machinery.
         */
        const val PREFS_NAME = "next_settings"

        const val KEY_TERMINAL_TEXT_SIZE_PX = "terminal_text_size_px"
        const val KEY_VOICE_LANGUAGE = "voice_language"
        const val KEY_USAGE_WARN_THRESHOLD = "usage_warn_threshold_percent"
        const val KEY_BACKGROUND_GRACE_MILLIS = "background_grace_millis"
    }
}
