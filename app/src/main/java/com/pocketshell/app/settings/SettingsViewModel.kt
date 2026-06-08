package com.pocketshell.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.diagnostics.DiagnosticRecorder
import com.pocketshell.app.release.ReleaseCheckResult
import com.pocketshell.app.release.ReleaseChecker
import com.pocketshell.app.release.ReleaseInfo
import com.pocketshell.app.usage.UsageScheduler
import com.pocketshell.app.usage.UsageSnapshot
import com.pocketshell.core.assistant.AssistantProvider
import com.pocketshell.core.assistant.AssistantSettings
import com.pocketshell.core.assistant.store.AssistantConfigStore
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.core.usage.UsageProviderRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Backs [SettingsScreen]. Thin pass-through over [SettingsRepository] —
 * the repository owns persistence and emits the snapshot; the view model
 * exists so the screen can call into the same surface other ViewModels
 * use (Hilt-injected `hiltViewModel()`) without exposing the repository
 * directly to Compose code.
 *
 * Also brokers the Whisper API-key lifecycle (issue #125): the keystore-
 * backed vault persists the key in `EncryptedSharedPreferences`; this
 * view model exposes a [keyStatus] flow so the Voice section can render
 * "Key set" vs "Set Whisper API key" without leaking the plaintext into
 * the Compose state. The vault dependency is the same
 * [PromptComposerViewModel.ApiKeyVault] interface the composer uses —
 * keeping a single seam means tests can substitute the in-memory
 * `FakeVault` already proven on the composer side, and production Hilt
 * graph wiring (`VoiceModule.provideApiKeyVault`) emits one singleton
 * for both consumers.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val apiKeyStorage: PromptComposerViewModel.ApiKeyVault,
    private val assistantConfigStore: AssistantConfigStore,
    private val diagnosticRecorder: DiagnosticRecorder,
    private val releaseChecker: ReleaseChecker,
    hostDao: HostDao,
    usageScheduler: UsageScheduler,
) : ViewModel() {

    val state: StateFlow<AppSettings> = repository.settings

    /**
     * Whether at least one persisted host reports `pocketshellInstalled == true`.
     *
     * Issue #157 polish item 5: when no pocketshell-installed host exists the
     * cross-host Usage dashboard strip on the host list never renders
     * (issue #116 AC: "no empty rail"), so a user has no way to
     * discover that the in-app quota panel even exists. The Settings →
     * Usage row drives the only other entry point, but tapping it
     * lands on a blank `UsageScreen`. Surfacing the boolean here lets
     * the Settings → Usage section render a "no `pocketshell` hosts detected"
     * hint with a link to the docs in the empty case, without changing
     * the routing behaviour for the populated case.
     *
     * Derivation mirrors [com.pocketshell.app.hosts.HostListViewModel.hasUsageInstalledHost]
     * verbatim so both surfaces agree on whether the panel is useful.
     */
    val hasUsageInstalledHost: StateFlow<Boolean> = hostDao.getAll()
        .map { rows -> rows.any { it.pocketshellInstalled == true && it.pocketshellVersionCompatible != false } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * Issue #206: surface every saved host so the new "Watched folders"
     * section in Settings can render a host picker. The picker lets the
     * user pick a host without first opening it for a session — useful
     * for pre-configuring a host's watched folders before the first
     * connection. The host-list kebab keeps the credential-rich route
     * for the discover-from-remote button.
     */
    val hosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * Issue #214: aggregated per-provider records the Settings → Usage
     * section's "per-provider state" list renders. Picks the worst-case
     * record per provider across every host the scheduler has snapped,
     * so a 96 % Claude on host A and a 50 % Claude on host B collapse
     * into a single "Claude — 96 %" row.
     *
     * Records are sorted by provider so the row order stays stable
     * across snapshot refreshes. Empty when the scheduler hasn't
     * reported any usage data yet.
     */
    val usageProviderRecords: StateFlow<List<UsageProviderRecord>> = usageScheduler.snapshots
        .map { snapshots ->
            val byProvider = mutableMapOf<String, UsageProviderRecord>()
            snapshots.values
                .filterIsInstance<UsageSnapshot.Records>()
                .flatMap { it.records }
                .forEach { record ->
                    val key = record.provider.lowercase()
                    val current = byProvider[key]
                    val currentPercent = current?.mostConstrainedWindow?.percent ?: -1.0
                    val candidatePercent = record.mostConstrainedWindow?.percent ?: -1.0
                    if (current == null || candidatePercent > currentPercent) {
                        byProvider[key] = record
                    }
                }
            byProvider.values.sortedBy { it.provider.lowercase() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _keyStatus: MutableStateFlow<WhisperKeyStatus> =
        MutableStateFlow(readKeyStatus())

    /**
     * Whether a Whisper API key is currently saved. Exposed as a separate
     * flow so the Voice section can rebuild on every save / clear without
     * coupling `AppSettings` to a secret-management state value (the key
     * itself is never put on the StateFlow — only a boolean derived from
     * its presence in the vault).
     */
    val keyStatus: StateFlow<WhisperKeyStatus> = _keyStatus.asStateFlow()

    private val _assistantState: MutableStateFlow<AssistantSettingsUiState> =
        MutableStateFlow(readAssistantState())

    /**
     * Issue #265: the in-app action assistant's provider config surface.
     * Carries the active provider, per-provider base URL / model, and a
     * masked indicator of whether each provider's key is set. The plaintext
     * key never enters this StateFlow — only the last-four tail, mirroring
     * [keyStatus] / the Whisper key UX. This config is wholly separate from
     * the Whisper key: a different KeyStore-backed store, so editing it
     * never disturbs voice transcription.
     */
    val assistantState: StateFlow<AssistantSettingsUiState> = _assistantState.asStateFlow()

    private val _diagnosticsShareState: MutableStateFlow<DiagnosticsShareState> =
        MutableStateFlow(DiagnosticsShareState.Idle)
    val diagnosticsShareState: StateFlow<DiagnosticsShareState> =
        _diagnosticsShareState.asStateFlow()

    /**
     * Manual update-check state for Settings → About. This gives the user a
     * deterministic in-app fallback even if the host-list courtesy banner
     * misses, is dismissed, or the app opens directly into another surface.
     */
    private val _updateCheckState: MutableStateFlow<SettingsUpdateCheckState> =
        MutableStateFlow(SettingsUpdateCheckState.Idle)
    val updateCheckState: StateFlow<SettingsUpdateCheckState> = _updateCheckState.asStateFlow()

    fun setTerminalFontSizeSp(sizeSp: Float) = repository.setTerminalFontSizeSp(sizeSp)

    fun setTerminalKeyboardMode(mode: TerminalKeyboardMode) =
        repository.setTerminalKeyboardMode(mode)

    /** Issue #496: persist the conversation message-body font size (sp). */
    fun setConversationFontSizeSp(sizeSp: Float) =
        repository.setConversationFontSizeSp(sizeSp)

    fun setTmuxOnAttachByDefault(enabled: Boolean) =
        repository.setTmuxOnAttachByDefault(enabled)

    fun setDefaultHostId(hostId: Long?) = repository.setDefaultHostId(hostId)

    /**
     * Persist [code] as the user's preferred Whisper language. The Voice
     * section enforces that [code] comes from
     * [AppSettings.VOICE_LANGUAGE_OPTIONS]; the repository normalises and
     * lowercases the value so manual prefs edits stay consistent.
     */
    fun setVoiceLanguage(code: String) = repository.setVoiceLanguage(code)

    fun setVoiceTranscriptionProvider(provider: VoiceTranscriptionProvider) =
        repository.setVoiceTranscriptionProvider(provider)

    /**
     * Persist [seconds] as the user's preferred auto-stop silence window.
     * The repository clamps to the supported range; passing a value
     * outside it (e.g. via the slider drag) silently snaps to bounds.
     */
    fun setVoiceSilenceThresholdSeconds(seconds: Float) =
        repository.setVoiceSilenceThresholdSeconds(seconds)

    /**
     * Toggle [AppSettings.showSystemNotes] — issue #176. When off, the
     * conversation pane filters XML-tagged system blocks entirely; when
     * on (default), they render as muted collapsible rows.
     */
    fun setShowSystemNotes(enabled: Boolean) = repository.setShowSystemNotes(enabled)

    fun setHostDetailViewMode(mode: HostDetailViewMode) =
        repository.setHostDetailViewMode(mode)

    /**
     * Persist [percent] as the user's preferred "approaching limit"
     * threshold for the in-app usage warning surfaces (issue #214).
     * Values are clamped + snapped to the slider grid by the
     * repository, so a slider drag that lands on a fractional value
     * snaps to a multiple of
     * [AppSettings.USAGE_WARN_PERCENT_STEP].
     */
    fun setUsageWarnThresholdPercent(percent: Int) =
        repository.setUsageWarnThresholdPercent(percent)

    /**
     * Persist [delayMs] as the composer's agent-submit Enter delay (issue
     * #526). The repository clamps + snaps to the slider grid, so a slider
     * drag that lands on a fractional or out-of-band value snaps to a
     * supported stop. The terminal send path reads this value to wait
     * between typing the message text and pressing the submit Enter.
     */
    fun setAgentSubmitEnterDelayMs(delayMs: Int) =
        repository.setAgentSubmitEnterDelayMs(delayMs)

    fun setBackgroundGraceMillis(millis: Long) =
        repository.setBackgroundGraceMillis(millis)

    fun setDiagnosticsRecordingEnabled(enabled: Boolean) =
        repository.setDiagnosticsRecordingEnabled(enabled)

    fun startFreshDiagnosticsCapture() {
        viewModelScope.launch {
            repository.setDiagnosticsRecordingEnabled(true)
            diagnosticRecorder.clear()
            diagnosticRecorder.record("diagnostics", "capture_started")
            _diagnosticsShareState.value = DiagnosticsShareState.Idle
        }
    }

    fun shareDiagnosticsLog() {
        if (_diagnosticsShareState.value is DiagnosticsShareState.Preparing) return
        _diagnosticsShareState.value = DiagnosticsShareState.Preparing
        viewModelScope.launch {
            val file = runCatching { diagnosticRecorder.exportSnapshot() }
                .getOrElse { error ->
                    _diagnosticsShareState.value = DiagnosticsShareState.Failed(
                        error.message ?: "Could not prepare diagnostics log.",
                    )
                    return@launch
                }
            if (file == null) {
                _diagnosticsShareState.value = DiagnosticsShareState.Failed("No diagnostics log recorded yet.")
            } else {
                _diagnosticsShareState.value = DiagnosticsShareState.Prepared(file)
            }
        }
    }

    fun markDiagnosticsShareLaunched() {
        if (_diagnosticsShareState.value is DiagnosticsShareState.Prepared) {
            _diagnosticsShareState.value = DiagnosticsShareState.Idle
        }
    }

    fun diagnosticsShareLaunchFailed(message: String) {
        _diagnosticsShareState.value = DiagnosticsShareState.Failed(message)
    }

    fun clearDiagnosticsShareState() {
        _diagnosticsShareState.value = DiagnosticsShareState.Idle
    }

    fun clearDiagnosticsLog() {
        viewModelScope.launch {
            diagnosticRecorder.clear()
            _diagnosticsShareState.value = DiagnosticsShareState.Idle
        }
    }

    fun checkForAppUpdate(currentVersion: String) {
        if (_updateCheckState.value == SettingsUpdateCheckState.Checking) return
        val normalizedVersion = currentVersion.trim()
        if (normalizedVersion.isEmpty() || normalizedVersion == "unknown") {
            _updateCheckState.value = SettingsUpdateCheckState.Failed("Installed version is unknown")
            return
        }
        _updateCheckState.value = SettingsUpdateCheckState.Checking
        viewModelScope.launch {
            _updateCheckState.value = when (val result = releaseChecker.checkForUpdate(normalizedVersion)) {
                is ReleaseCheckResult.UpdateAvailable -> SettingsUpdateCheckState.UpdateAvailable(result.info)
                ReleaseCheckResult.UpToDate -> SettingsUpdateCheckState.UpToDate
                is ReleaseCheckResult.Failed -> SettingsUpdateCheckState.Failed(result.reason)
            }
        }
    }

    fun onUpdateDownloadStarted(info: ReleaseInfo) {
        _updateCheckState.value = SettingsUpdateCheckState.DownloadStarted(info)
    }

    fun onUpdateDownloadFailed(info: ReleaseInfo, reason: String) {
        _updateCheckState.value = SettingsUpdateCheckState.DownloadFailed(info, reason)
    }

    /**
     * Persist [key] through the keystore-backed vault. The caller still
     * owns the [CharArray] and is responsible for zeroing it after this
     * call returns (matches the contract in
     * [com.pocketshell.app.composer.PromptComposerViewModel.saveApiKey]).
     *
     * Re-reads the key status after the save so the screen flips to
     * "Key set" without waiting for a recomposition cycle.
     */
    fun saveApiKey(key: CharArray) {
        apiKeyStorage.save(key)
        // Refresh on a coroutine to avoid hopping onto the main thread
        // for the prefs read; load() is fast but it's still I/O and the
        // viewmodel scope is the natural lifecycle for the work.
        viewModelScope.launch {
            _keyStatus.value = readKeyStatus()
        }
    }

    /** Clear the stored Whisper key. The vault drops the encrypted blob. */
    fun clearApiKey() {
        apiKeyStorage.clear()
        _keyStatus.value = WhisperKeyStatus.Unset
    }

    /**
     * Issue #265: switch the active assistant provider. Default is OpenAI;
     * switching changes which client [com.pocketshell.core.assistant.AssistantLlmClientFactory]
     * returns on the next `create()`.
     */
    fun setAssistantProvider(provider: AssistantProvider) {
        assistantConfigStore.setProvider(provider)
        _assistantState.value = readAssistantState()
    }

    /** Persist the base URL + model for [provider]. */
    fun setAssistantEndpoint(provider: AssistantProvider, baseUrl: String, model: String) {
        assistantConfigStore.setEndpoint(provider, baseUrl, model)
        _assistantState.value = readAssistantState()
    }

    /**
     * Persist [key] for [provider] through the KeyStore-backed store. The
     * caller still owns the [CharArray] and zeroes it after the call (same
     * contract as [saveApiKey]).
     */
    fun saveAssistantKey(provider: AssistantProvider, key: CharArray) {
        assistantConfigStore.saveKey(provider, key)
        viewModelScope.launch {
            _assistantState.value = readAssistantState()
        }
    }

    /** Clear the stored assistant key for [provider]. */
    fun clearAssistantKey(provider: AssistantProvider) {
        assistantConfigStore.clearKey(provider)
        _assistantState.value = readAssistantState()
    }

    private fun readAssistantState(): AssistantSettingsUiState {
        val settings = assistantConfigStore.loadSettings()
        return AssistantSettingsUiState(
            provider = settings.provider,
            openAiBaseUrl = settings.openAiBaseUrl,
            openAiModel = settings.openAiModel,
            anthropicBaseUrl = settings.anthropicBaseUrl,
            anthropicModel = settings.anthropicModel,
            zaiBaseUrl = settings.zaiBaseUrl,
            zaiModel = settings.zaiModel,
            openAiKey = readAssistantKeyStatus(AssistantProvider.OpenAi),
            anthropicKey = readAssistantKeyStatus(AssistantProvider.Anthropic),
            zaiKey = readAssistantKeyStatus(AssistantProvider.Zai),
        )
    }

    private fun readAssistantKeyStatus(provider: AssistantProvider): WhisperKeyStatus {
        val loaded = assistantConfigStore.loadKey(provider) ?: return WhisperKeyStatus.Unset
        val tail = loaded.takeLast(MASKED_TAIL_LENGTH).joinToString("")
        java.util.Arrays.fill(loaded, ' ')
        return WhisperKeyStatus.Set(maskedTail = tail)
    }

    private fun readKeyStatus(): WhisperKeyStatus {
        val loaded = apiKeyStorage.load() ?: return WhisperKeyStatus.Unset
        // Zero the peek copy — we never surface the plaintext, only the
        // masked tail. The vault's load() returns a fresh CharArray each
        // call, so wiping ours doesn't affect future reads.
        val tail = loaded.takeLast(MASKED_TAIL_LENGTH).joinToString("")
        java.util.Arrays.fill(loaded, ' ')
        return WhisperKeyStatus.Set(maskedTail = tail)
    }

    companion object {
        /**
         * Number of trailing key characters surfaced in the masked
         * display ("sk-…1234"). Four matches the convention OpenAI itself
         * uses when listing keys in their dashboard.
         */
        const val MASKED_TAIL_LENGTH: Int = 4
    }
}

/**
 * Whether a Whisper API key is currently saved in the keystore-backed
 * vault. The [Set] variant carries the last four characters so the
 * Voice section can render `sk-…1234` without re-reading the prefs file
 * on every recomposition.
 */
sealed interface WhisperKeyStatus {
    object Unset : WhisperKeyStatus
    data class Set(val maskedTail: String) : WhisperKeyStatus
}

/**
 * Issue #265: display state for the Settings → Assistant section. Carries
 * the active provider, the per-provider base URL / model, and a masked
 * key indicator per provider ([WhisperKeyStatus] is reused — the shape is
 * "Unset" vs "Set(maskedTail)", which is exactly what the Assistant key
 * rows need too). The plaintext key never lands here.
 */
data class AssistantSettingsUiState(
    val provider: AssistantProvider,
    val openAiBaseUrl: String,
    val openAiModel: String,
    val anthropicBaseUrl: String,
    val anthropicModel: String,
    val zaiBaseUrl: String,
    val zaiModel: String,
    val openAiKey: WhisperKeyStatus,
    val anthropicKey: WhisperKeyStatus,
    val zaiKey: WhisperKeyStatus,
) {
    /** Masked key status for whichever provider is currently active. */
    fun keyStatusFor(provider: AssistantProvider): WhisperKeyStatus = when (provider) {
        AssistantProvider.OpenAi -> openAiKey
        AssistantProvider.Anthropic -> anthropicKey
        AssistantProvider.Zai -> zaiKey
    }

    /** Base URL for [provider]. */
    fun baseUrlFor(provider: AssistantProvider): String = when (provider) {
        AssistantProvider.OpenAi -> openAiBaseUrl
        AssistantProvider.Anthropic -> anthropicBaseUrl
        AssistantProvider.Zai -> zaiBaseUrl
    }

    /** Model for [provider]. */
    fun modelFor(provider: AssistantProvider): String = when (provider) {
        AssistantProvider.OpenAi -> openAiModel
        AssistantProvider.Anthropic -> anthropicModel
        AssistantProvider.Zai -> zaiModel
    }
}

sealed interface DiagnosticsShareState {
    data object Idle : DiagnosticsShareState
    data object Preparing : DiagnosticsShareState
    data class Prepared(val file: File) : DiagnosticsShareState
    data class Failed(val message: String) : DiagnosticsShareState
}

sealed interface SettingsUpdateCheckState {
    data object Idle : SettingsUpdateCheckState
    data object Checking : SettingsUpdateCheckState
    data object UpToDate : SettingsUpdateCheckState
    data class UpdateAvailable(val info: ReleaseInfo) : SettingsUpdateCheckState
    data class Failed(val reason: String) : SettingsUpdateCheckState
    data class DownloadStarted(val info: ReleaseInfo) : SettingsUpdateCheckState
    data class DownloadFailed(val info: ReleaseInfo, val reason: String) : SettingsUpdateCheckState
}
