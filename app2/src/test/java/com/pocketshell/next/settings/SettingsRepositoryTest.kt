package com.pocketshell.next.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SettingsRepository] against the real `SharedPreferences` file (Robolectric
 * backs `getSharedPreferences` with a real on-disk file under the test's
 * sandboxed `filesDir`, not a fake).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SettingsRepositoryTest {

    private fun repository(): SettingsRepository =
        SettingsRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `a fresh install reads the AppSettings defaults`() {
        assertEquals(AppSettings(), repository().settings.value)
    }

    @Test
    fun `a written terminal text size survives a new repository instance`() {
        repository().setTerminalTextSizePx(40)

        assertEquals(40, repository().settings.value.terminalTextSizePx)
    }

    @Test
    fun `the terminal text size snaps to the 2px grid, not just the bounds`() {
        val repo = repository()

        repo.setTerminalTextSizePx(15) // below the 16px floor
        assertEquals(16, repo.settings.value.terminalTextSizePx)

        repo.setTerminalTextSizePx(21) // odd -> rounds to the nearest even stop
        assertEquals(22, repo.settings.value.terminalTextSizePx)

        repo.setTerminalTextSizePx(1000)
        assertEquals(AppSettings.MAX_TERMINAL_TEXT_SIZE_PX, repo.settings.value.terminalTextSizePx)

        repo.setTerminalTextSizePx(-1000)
        assertEquals(AppSettings.MIN_TERMINAL_TEXT_SIZE_PX, repo.settings.value.terminalTextSizePx)
    }

    @Test
    fun `the usage warn threshold snaps to its own 5-point grid`() {
        val repo = repository()

        repo.setUsageWarnThresholdPercent(83)

        assertEquals(85, repo.settings.value.usageWarnThresholdPercent)
    }

    @Test
    fun `an unsupported voice language falls back to auto`() {
        val repo = repository()

        repo.setVoiceLanguage("klingon")

        assertEquals(AppSettings.VOICE_LANGUAGE_AUTO, repo.settings.value.voiceLanguage)
    }

    @Test
    fun `voice language is trimmed and lowercased`() {
        val repo = repository()

        repo.setVoiceLanguage("  RU  ")

        assertEquals("ru", repo.settings.value.voiceLanguage)
    }

    @Test
    fun `only an offered background grace option is accepted, others fall back to default`() {
        val repo = repository()

        repo.setBackgroundGraceMillis(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS)
        assertEquals(AppSettings.BACKGROUND_GRACE_5_MINUTES_MS, repo.settings.value.backgroundGraceMillis)

        repo.setBackgroundGraceMillis(123_456L)
        assertEquals(AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS, repo.settings.value.backgroundGraceMillis)
    }

    /**
     * A truncated/corrupt preferences file must degrade to defaults rather than
     * crash the whole Settings surface on open — see [SettingsRepository]'s
     * class doc.
     */
    @Test
    fun `a corrupt preferences file recovers instead of crashing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefsFile = context.getSharedPreferences("next_settings", Context.MODE_PRIVATE)
        // Force a type mismatch a plain safeInt/getInt cannot survive: the key
        // this repository expects to be an Int is instead a String.
        prefsFile.edit().putString("terminal_text_size_px", "not-an-int").apply()

        val snapshot = repository().settings.value

        assertEquals(AppSettings.DEFAULT_TERMINAL_TEXT_SIZE_PX, snapshot.terminalTextSizePx)
    }

    @Test
    fun `changing one field does not touch the others`() {
        val repo = repository()

        repo.setTerminalTextSizePx(36)
        repo.setUsageWarnThresholdPercent(60)

        val snapshot = repo.settings.value
        assertEquals(36, snapshot.terminalTextSizePx)
        assertEquals(60, snapshot.usageWarnThresholdPercent)
        assertEquals(AppSettings.VOICE_LANGUAGE_AUTO, snapshot.voiceLanguage)
        assertEquals(AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS, snapshot.backgroundGraceMillis)
        assertEquals(AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS, snapshot.agentSubmitEnterDelayMs)
    }

    @Test
    fun `agentSubmitEnterDelay defaults to 150ms`() {
        val repo = repository()
        assertEquals(
            AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS,
            repo.settings.value.agentSubmitEnterDelayMs,
        )
        assertEquals(150, repo.settings.value.agentSubmitEnterDelayMs)
    }

    @Test
    fun `setAgentSubmitEnterDelayMs persists and round-trips`() {
        repository().setAgentSubmitEnterDelayMs(300)
        assertEquals(300, repository().settings.value.agentSubmitEnterDelayMs)
    }

    @Test
    fun `setAgentSubmitEnterDelayMs clamps below minimum and above maximum`() {
        val repo = repository()

        repo.setAgentSubmitEnterDelayMs(-50)
        assertEquals(
            AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS,
            repo.settings.value.agentSubmitEnterDelayMs,
        )

        repo.setAgentSubmitEnterDelayMs(5000)
        assertEquals(
            AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS,
            repo.settings.value.agentSubmitEnterDelayMs,
        )
    }

    @Test
    fun `setAgentSubmitEnterDelayMs snaps to the 50ms slider grid`() {
        val repo = repository()

        repo.setAgentSubmitEnterDelayMs(170)
        assertEquals(150, repo.settings.value.agentSubmitEnterDelayMs)

        repo.setAgentSubmitEnterDelayMs(180)
        assertEquals(200, repo.settings.value.agentSubmitEnterDelayMs)
    }
}
