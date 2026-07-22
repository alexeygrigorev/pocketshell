package com.pocketshell.app.tmux

import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.di.WhisperClientFactory

/**
 * Issue #1635-A (design D4): a minimal, plain-JVM [PromptComposerViewModel] for tests
 * that need a controller ([OutboundQueueAutoFlushController.boundTo]) but are NOT about
 * the retry budget.
 *
 * `boundTo` is the only way to build a controller — deliberately, so that no call site
 * can hand the controller a tracker the composer does not consume (the wrong-value
 * failure mode a required `budget` argument did NOT close). That means every controller
 * test needs a composer, so this keeps the cost to one line.
 *
 * All four required ViewModel dependencies are plain interfaces, and the rest default to
 * the `Disabled*` no-op stubs, so this constructs on the JVM with no Robolectric runner
 * and no Android framework — the controller tests in `TmuxSessionScreenTest` stay plain
 * JUnit. It launches nothing: the tests below only read `outboundAttemptBudget`.
 *
 * This lives in its own file (not in `TmuxSessionScreenTest.kt`) because that file is a
 * hygiene-ratcheted 122985 B, close to the 128 KiB guard threshold.
 */
internal fun outboundBudgetTestComposer(): PromptComposerViewModel =
    PromptComposerViewModel(
        audioRecorder = SilentMicCapture,
        whisperClientFactory = WhisperClientFactory { error("no transcription in a budget test") },
        apiKeyStorage = EmptyApiKeyVault,
        voiceSettings = DefaultVoiceSettings,
    )

private object SilentMicCapture : PromptComposerViewModel.MicCapture {
    override fun start() = Unit
    override fun stop(): ByteArray = ByteArray(0)
    override fun currentAmplitude(): Float = 0f
}

private object EmptyApiKeyVault : PromptComposerViewModel.ApiKeyVault {
    override fun save(key: CharArray) = Unit
    override fun load(): CharArray? = null
    override fun clear() = Unit
}

private object DefaultVoiceSettings : PromptComposerViewModel.VoiceSettingsSnapshot {
    override fun silenceWindowMs(): Long = 1_000L
    override fun whisperLanguageHint(): String? = null
}
