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
}
