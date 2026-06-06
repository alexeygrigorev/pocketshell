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
        assertEquals(AppSettings.DEFAULT_TERMINAL_FONT_SP, snap.terminalFontSizeSp, 0f)
        // Issue #496: the conversation font defaults to the compact
        // bodyDense rung (13sp) so a fresh install renders unchanged.
        assertEquals(AppSettings.DEFAULT_CONVERSATION_FONT_SP, snap.conversationFontSizeSp, 0f)
        assertEquals(13f, AppSettings.DEFAULT_CONVERSATION_FONT_SP, 0f)
        assertTrue(snap.tmuxOnAttachByDefault)
        assertEquals(AppSettings.VOICE_LANGUAGE_AUTO, snap.voiceLanguage)
        assertEquals(
            AppSettings.DEFAULT_VOICE_SILENCE_SECONDS,
            snap.voiceSilenceThresholdSeconds,
            0f,
        )
        assertEquals(
            AppSettings.DEFAULT_VOICE_TRANSCRIPTION_PROVIDER,
            snap.voiceTranscriptionProvider,
        )
        // Issue #176: system notes are visible-but-muted by default so
        // fresh installs see the same conversation events the pre-#176
        // build did, just visually de-emphasized.
        assertEquals(AppSettings.DEFAULT_SHOW_SYSTEM_NOTES, snap.showSystemNotes)
        assertTrue("expected default to be ON", snap.showSystemNotes)
        assertEquals(null, snap.defaultHostId)
        assertEquals(HostDetailViewMode.Tree, snap.hostDetailViewMode)
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

    // -- Issue #496: conversation font size -------------------------------

    @Test
    fun `setConversationFontSize persists and round-trips`() {
        val repo = SettingsRepository(context)
        repo.setConversationFontSizeSp(18f)
        assertEquals(18f, repo.settings.value.conversationFontSizeSp, 0f)
        // A fresh repository instance reads the persisted value back.
        assertEquals(18f, SettingsRepository(context).settings.value.conversationFontSizeSp, 0f)
    }

    @Test
    fun `setConversationFontSize clamps below minimum`() {
        val repo = SettingsRepository(context)
        repo.setConversationFontSizeSp(2f)
        assertEquals(
            AppSettings.MIN_CONVERSATION_FONT_SP,
            repo.settings.value.conversationFontSizeSp,
            0f,
        )
    }

    @Test
    fun `setConversationFontSize clamps above maximum`() {
        val repo = SettingsRepository(context)
        repo.setConversationFontSizeSp(99f)
        assertEquals(
            AppSettings.MAX_CONVERSATION_FONT_SP,
            repo.settings.value.conversationFontSizeSp,
            0f,
        )
    }

    @Test
    fun `conversation font wrong-typed prefs blob falls back to default`() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("conversation_font_sp", "huge")
            .commit()

        val snap = SettingsRepository(context).settings.value
        assertEquals(AppSettings.DEFAULT_CONVERSATION_FONT_SP, snap.conversationFontSizeSp, 0f)
    }

    @Test
    fun `legacy out-of-range conversation font blob is clamped on read`() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("conversation_font_sp", 99f)
            .commit()

        val snap = SettingsRepository(context).settings.value
        assertEquals(
            AppSettings.MAX_CONVERSATION_FONT_SP,
            snap.conversationFontSizeSp,
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
    fun `wrong typed restored prefs fall back to defaults`() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("terminal_font_sp", "huge")
            .putString("tmux_on_attach_default", "yes")
            .putString("voice_silence_seconds", "soon")
            .putString("show_system_notes", "maybe")
            .putBoolean("host_detail_view_mode", true)
            .putString("usage_warn_threshold_percent", "eighty")
            .putString("default_host_id", "host-a")
            .commit()

        val repo = SettingsRepository(context)
        val snap = repo.settings.value

        assertEquals(AppSettings.DEFAULT_TERMINAL_FONT_SP, snap.terminalFontSizeSp, 0f)
        assertTrue(snap.tmuxOnAttachByDefault)
        assertEquals(AppSettings.DEFAULT_VOICE_SILENCE_SECONDS, snap.voiceSilenceThresholdSeconds, 0f)
        assertEquals(AppSettings.DEFAULT_SHOW_SYSTEM_NOTES, snap.showSystemNotes)
        assertEquals(HostDetailViewMode.Tree, snap.hostDetailViewMode)
        assertEquals(AppSettings.DEFAULT_USAGE_WARN_PERCENT, snap.usageWarnThresholdPercent)
        assertEquals(null, snap.defaultHostId)
    }

    // -- Issue #305: default launch host preference -----------------------

    @Test
    fun `setDefaultHostId persists and round-trips`() {
        val repo = SettingsRepository(context)
        repo.setDefaultHostId(42L)
        assertEquals(42L, repo.settings.value.defaultHostId)
        assertEquals(42L, SettingsRepository(context).settings.value.defaultHostId)
    }

    @Test
    fun `setDefaultHostId clears selection when null or non-positive`() {
        val repo = SettingsRepository(context)
        repo.setDefaultHostId(42L)
        repo.setDefaultHostId(null)
        assertEquals(null, repo.settings.value.defaultHostId)
        assertEquals(null, SettingsRepository(context).settings.value.defaultHostId)

        repo.setDefaultHostId(-1L)
        assertEquals(null, repo.settings.value.defaultHostId)
    }

    @Test
    fun `setHostDetailViewMode toggles and persists`() {
        val repo = SettingsRepository(context)
        repo.setHostDetailViewMode(HostDetailViewMode.Flat)
        assertEquals(HostDetailViewMode.Flat, repo.settings.value.hostDetailViewMode)
        assertEquals(HostDetailViewMode.Flat, SettingsRepository(context).settings.value.hostDetailViewMode)

        repo.setHostDetailViewMode(HostDetailViewMode.Tree)
        assertEquals(HostDetailViewMode.Tree, repo.settings.value.hostDetailViewMode)
        assertEquals(HostDetailViewMode.Tree, SettingsRepository(context).settings.value.hostDetailViewMode)
    }

    @Test
    fun `unknown host detail view mode falls back to Tree`() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Timeline")
            .commit()

        val repo = SettingsRepository(context)

        assertEquals(HostDetailViewMode.Tree, repo.settings.value.hostDetailViewMode)
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
    fun `setVoiceTranscriptionProvider persists and round-trips`() {
        val repo = SettingsRepository(context)
        repo.setVoiceTranscriptionProvider(VoiceTranscriptionProvider.AndroidSpeech)
        assertEquals(
            VoiceTranscriptionProvider.AndroidSpeech,
            repo.settings.value.voiceTranscriptionProvider,
        )
        assertEquals(
            VoiceTranscriptionProvider.AndroidSpeech,
            SettingsRepository(context).settings.value.voiceTranscriptionProvider,
        )
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

    // -- Issue #185: stricter 2s minimum floor ----------------------------

    @Test
    fun `voice silence minimum bound is two seconds`() {
        // Issue #185 raised the floor from 0.5s to 2s after a v0.2.8
        // feedback report where natural mid-sentence pauses were
        // auto-stopping the recorder. Pin the constant explicitly so a
        // future tweak to the bound is caught here rather than letting
        // a sub-2s default slip back in.
        assertEquals(
            "minimum silence threshold must be 2s — anything below makes the watchdog hostile to natural speech",
            2f,
            AppSettings.MIN_VOICE_SILENCE_SECONDS,
            0f,
        )
    }

    @Test
    fun `default voice silence threshold is conservative for long dictation`() {
        // Issue #397: default auto-stop should be much less sensitive than
        // the earlier 5s window. Pin 30s as the long-dictation default so
        // natural pauses and quieter distant speech do not stop recording
        // mid-thought.
        assertTrue(
            "default silence threshold (${AppSettings.DEFAULT_VOICE_SILENCE_SECONDS}s) must be >= 2s",
            AppSettings.DEFAULT_VOICE_SILENCE_SECONDS >= 2f,
        )
        assertEquals(
            "issue #397 documents the default as 30s; raise or lower with care",
            30f,
            AppSettings.DEFAULT_VOICE_SILENCE_SECONDS,
            0f,
        )
    }

    @Test
    fun `legacy sub-two-second prefs blob is lifted to the floor on read`() {
        // A user on a pre-#185 build could have dragged the slider to
        // 0.5s. After they update, the SharedPreferences blob still
        // carries that value. Issue #185 fixes the regression by
        // clamping on read in [SettingsRepository.readSnapshot] so the
        // first launch under the new minimum surfaces the floor, not
        // the stored sub-floor value.
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .putFloat("voice_silence_seconds", 0.5f)
            .commit()

        val repo = SettingsRepository(context)

        assertEquals(
            "legacy sub-2s value must be lifted to the new minimum on first read",
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
        repo.setVoiceSilenceThresholdSeconds(12f)
        assertEquals(12f, repo.settings.value.voiceSilenceThresholdSeconds, 0.01f)
        assertEquals(
            12f,
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

    // -- Issue #214: usage warn threshold slider --------------------------

    @Test
    fun `usageWarnThresholdPercent default is 80`() {
        val repo = SettingsRepository(context)
        assertEquals(
            "issue #214: fresh install must default to 80% per spec",
            AppSettings.DEFAULT_USAGE_WARN_PERCENT,
            repo.settings.value.usageWarnThresholdPercent,
        )
        assertEquals(80, repo.settings.value.usageWarnThresholdPercent)
    }

    @Test
    fun `setUsageWarnThresholdPercent persists and round-trips`() {
        val repo = SettingsRepository(context)
        repo.setUsageWarnThresholdPercent(70)
        assertEquals(70, repo.settings.value.usageWarnThresholdPercent)
        assertEquals(
            70,
            SettingsRepository(context).settings.value.usageWarnThresholdPercent,
        )
    }

    @Test
    fun `setUsageWarnThresholdPercent clamps below minimum`() {
        val repo = SettingsRepository(context)
        repo.setUsageWarnThresholdPercent(10)
        assertEquals(
            AppSettings.MIN_USAGE_WARN_PERCENT,
            repo.settings.value.usageWarnThresholdPercent,
        )
    }

    @Test
    fun `setUsageWarnThresholdPercent clamps above maximum`() {
        val repo = SettingsRepository(context)
        repo.setUsageWarnThresholdPercent(150)
        assertEquals(
            AppSettings.MAX_USAGE_WARN_PERCENT,
            repo.settings.value.usageWarnThresholdPercent,
        )
    }

    @Test
    fun `setUsageWarnThresholdPercent snaps to slider step`() {
        // The slider grain is 5 % per [AppSettings.USAGE_WARN_PERCENT_STEP],
        // so an arbitrary 73 value should snap to 75.
        val repo = SettingsRepository(context)
        repo.setUsageWarnThresholdPercent(73)
        assertEquals(75, repo.settings.value.usageWarnThresholdPercent)
    }

    @Test
    fun `agentSubmitEnterDelay defaults to 150ms`() {
        // Issue #526: fresh install gets the maintainer-suggested ~150ms
        // delay so the composer's submit Enter never races ahead of the
        // agent TUI's paste ingestion.
        val repo = SettingsRepository(context)
        assertEquals(
            AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS,
            repo.settings.value.agentSubmitEnterDelayMs,
        )
        assertEquals(150, repo.settings.value.agentSubmitEnterDelayMs)
    }

    @Test
    fun `setAgentSubmitEnterDelayMs persists and round-trips`() {
        val repo = SettingsRepository(context)
        repo.setAgentSubmitEnterDelayMs(300)
        assertEquals(300, repo.settings.value.agentSubmitEnterDelayMs)
        // Reload from disk to prove persistence.
        assertEquals(
            300,
            SettingsRepository(context).settings.value.agentSubmitEnterDelayMs,
        )
    }

    @Test
    fun `setAgentSubmitEnterDelayMs clamps below minimum`() {
        val repo = SettingsRepository(context)
        repo.setAgentSubmitEnterDelayMs(-50)
        assertEquals(
            AppSettings.MIN_AGENT_SUBMIT_ENTER_DELAY_MS,
            repo.settings.value.agentSubmitEnterDelayMs,
        )
    }

    @Test
    fun `setAgentSubmitEnterDelayMs clamps above maximum`() {
        val repo = SettingsRepository(context)
        repo.setAgentSubmitEnterDelayMs(5000)
        assertEquals(
            AppSettings.MAX_AGENT_SUBMIT_ENTER_DELAY_MS,
            repo.settings.value.agentSubmitEnterDelayMs,
        )
    }

    @Test
    fun `setAgentSubmitEnterDelayMs snaps to slider step`() {
        // The slider grain is 50ms per
        // [AppSettings.AGENT_SUBMIT_ENTER_DELAY_STEP_MS], so an arbitrary 170
        // value should snap to the nearest stop (150).
        val repo = SettingsRepository(context)
        repo.setAgentSubmitEnterDelayMs(170)
        assertEquals(150, repo.settings.value.agentSubmitEnterDelayMs)
        repo.setAgentSubmitEnterDelayMs(180)
        assertEquals(200, repo.settings.value.agentSubmitEnterDelayMs)
    }
}
