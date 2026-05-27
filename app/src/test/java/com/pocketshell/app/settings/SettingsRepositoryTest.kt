package com.pocketshell.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SettingsRepository] running against Robolectric's
 * in-memory `SharedPreferences` implementation.
 *
 * Mirrors the host-list test pattern (Robolectric + manifest-less
 * config) — see
 * [com.pocketshell.app.hosts.HostListViewModelTest] for the established
 * conventions in this module.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe the prefs file between tests so each starts from a clean
        // default state regardless of which test ran before.
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `defaults match documented values`() = runTest {
        val repo = SettingsRepository(context)
        val snap = repo.settings.first()
        assertEquals(ThemePreference.System, snap.theme)
        assertEquals(AppSettings.DEFAULT_TERMINAL_FONT_SP, snap.terminalFontSizeSp, 0f)
        assertTrue(snap.tmuxOnAttachByDefault)
        assertEquals(AppSettings.VOICE_LANGUAGE_AUTO, snap.voiceLanguage)
        assertEquals(
            AppSettings.DEFAULT_VOICE_SILENCE_SECONDS,
            snap.voiceSilenceThresholdSeconds,
            0f,
        )
        // Issue #176: system notes are visible-but-muted by default so
        // fresh installs see the same conversation events the pre-#176
        // build did, just visually de-emphasized.
        assertEquals(AppSettings.DEFAULT_SHOW_SYSTEM_NOTES, snap.showSystemNotes)
        assertTrue("expected default to be ON", snap.showSystemNotes)
    }

    @Test
    fun `setTheme persists and re-emits`() = runTest {
        val repo = SettingsRepository(context)
        repo.setTheme(ThemePreference.Light)
        assertEquals(ThemePreference.Light, repo.settings.value.theme)

        // New instance reading from the same prefs file should observe
        // the persisted value, proving the write went to disk.
        val reread = SettingsRepository(context)
        assertEquals(ThemePreference.Light, reread.settings.value.theme)
    }

    @Test
    fun `setTerminalFontSize clamps below minimum`() {
        val repo = SettingsRepository(context)
        repo.setTerminalFontSizeSp(2f)
        assertEquals(
            AppSettings.MIN_TERMINAL_FONT_SP,
            repo.settings.value.terminalFontSizeSp,
            0f,
        )
    }

    @Test
    fun `setTerminalFontSize clamps above maximum`() {
        val repo = SettingsRepository(context)
        repo.setTerminalFontSizeSp(99f)
        assertEquals(
            AppSettings.MAX_TERMINAL_FONT_SP,
            repo.settings.value.terminalFontSizeSp,
            0f,
        )
    }

    @Test
    fun `setTmuxOnAttachByDefault toggles and persists`() {
        val repo = SettingsRepository(context)
        repo.setTmuxOnAttachByDefault(false)
        assertEquals(false, repo.settings.value.tmuxOnAttachByDefault)
        assertEquals(false, SettingsRepository(context).settings.value.tmuxOnAttachByDefault)
    }

    @Test
    fun `unknown theme name falls back to System`() {
        // Seed a junk value directly so the migration path is exercised.
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().putString("theme", "Polkadot").commit()
        val repo = SettingsRepository(context)
        assertEquals(ThemePreference.System, repo.settings.value.theme)
    }

    // -- Issue #125: voice preferences -------------------------------------

    @Test
    fun `setVoiceLanguage persists and round-trips`() {
        val repo = SettingsRepository(context)
        repo.setVoiceLanguage("es")
        assertEquals("es", repo.settings.value.voiceLanguage)
        // Re-read from a new instance against the same prefs file to
        // confirm the write reached disk.
        assertEquals("es", SettingsRepository(context).settings.value.voiceLanguage)
    }

    @Test
    fun `setVoiceLanguage normalises case and trims whitespace`() {
        val repo = SettingsRepository(context)
        repo.setVoiceLanguage("  EN  ")
        assertEquals("en", repo.settings.value.voiceLanguage)
    }

    @Test
    fun `setVoiceLanguage empty falls back to auto sentinel`() {
        val repo = SettingsRepository(context)
        repo.setVoiceLanguage("ru")
        assertEquals("ru", repo.settings.value.voiceLanguage)
        repo.setVoiceLanguage("")
        assertEquals(AppSettings.VOICE_LANGUAGE_AUTO, repo.settings.value.voiceLanguage)
    }

    @Test
    fun `setVoiceSilenceThresholdSeconds clamps below minimum`() {
        val repo = SettingsRepository(context)
        repo.setVoiceSilenceThresholdSeconds(0.1f)
        assertEquals(
            AppSettings.MIN_VOICE_SILENCE_SECONDS,
            repo.settings.value.voiceSilenceThresholdSeconds,
            0f,
        )
    }

    @Test
    fun `setVoiceSilenceThresholdSeconds clamps above maximum`() {
        val repo = SettingsRepository(context)
        repo.setVoiceSilenceThresholdSeconds(99f)
        assertEquals(
            AppSettings.MAX_VOICE_SILENCE_SECONDS,
            repo.settings.value.voiceSilenceThresholdSeconds,
            0f,
        )
    }

    @Test
    fun `setVoiceSilenceThresholdSeconds persists and round-trips`() {
        val repo = SettingsRepository(context)
        repo.setVoiceSilenceThresholdSeconds(2.5f)
        assertEquals(2.5f, repo.settings.value.voiceSilenceThresholdSeconds, 0.01f)
        assertEquals(
            2.5f,
            SettingsRepository(context).settings.value.voiceSilenceThresholdSeconds,
            0.01f,
        )
    }

    // -- Issue #176: conversation system-notes toggle ----------------------

    @Test
    fun `setShowSystemNotes toggles off and on, persisting across instances`() {
        val repo = SettingsRepository(context)
        repo.setShowSystemNotes(false)
        assertEquals(false, repo.settings.value.showSystemNotes)
        // Re-read from a fresh instance to confirm the write went to
        // disk and survives a process death.
        assertEquals(false, SettingsRepository(context).settings.value.showSystemNotes)

        repo.setShowSystemNotes(true)
        assertEquals(true, repo.settings.value.showSystemNotes)
        assertEquals(true, SettingsRepository(context).settings.value.showSystemNotes)
    }
}
