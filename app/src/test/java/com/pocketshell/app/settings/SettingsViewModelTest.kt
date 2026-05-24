package com.pocketshell.app.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Light-weight unit test confirming [SettingsViewModel] is a thin
 * pass-through over [SettingsRepository] — the repository owns the
 * write logic, the view model just relays.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsViewModelTest {

    private lateinit var context: Context
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repo = SettingsRepository(context)
    }

    @Test
    fun `state exposes repository snapshot`() {
        val vm = SettingsViewModel(repo)
        assertEquals(repo.settings.value, vm.state.value)
    }

    @Test
    fun `setTheme flows through to repository`() {
        val vm = SettingsViewModel(repo)
        vm.setTheme(ThemePreference.Dark)
        assertEquals(ThemePreference.Dark, repo.settings.value.theme)
        assertEquals(ThemePreference.Dark, vm.state.value.theme)
    }

    @Test
    fun `setTerminalFontSizeSp flows through to repository`() {
        val vm = SettingsViewModel(repo)
        vm.setTerminalFontSizeSp(18f)
        assertEquals(18f, repo.settings.value.terminalFontSizeSp, 0f)
    }

    @Test
    fun `setTmuxOnAttachByDefault flows through to repository`() {
        val vm = SettingsViewModel(repo)
        vm.setTmuxOnAttachByDefault(false)
        assertEquals(false, repo.settings.value.tmuxOnAttachByDefault)
    }
}
