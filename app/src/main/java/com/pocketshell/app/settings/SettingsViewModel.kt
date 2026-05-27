package com.pocketshell.app.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.core.storage.dao.HostDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    hostDao: HostDao,
) : ViewModel() {

    val state: StateFlow<AppSettings> = repository.settings

    /**
     * Whether at least one persisted host reports `quseInstalled == true`.
     *
     * Issue #157 polish item 5: when no quse-installed host exists the
     * cross-host Usage dashboard strip on the host list never renders
     * (issue #116 AC: "no empty rail"), so a user has no way to
     * discover that the in-app quota panel even exists. The Settings →
     * Usage row drives the only other entry point, but tapping it
     * lands on a blank `UsageScreen`. Surfacing the boolean here lets
     * the Settings → Usage section render a "no `quse` hosts detected"
     * hint with a link to the docs in the empty case, without changing
     * the routing behaviour for the populated case.
     *
     * Derivation mirrors [com.pocketshell.app.hosts.HostListViewModel.hasUsageInstalledHost]
     * verbatim so both surfaces agree on whether the panel is useful.
     */
    val hasUsageInstalledHost: StateFlow<Boolean> = hostDao.getAll()
        .map { rows -> rows.any { it.quseInstalled == true } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

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

    fun setTheme(theme: ThemePreference) = repository.setTheme(theme)

    fun setTerminalFontSizeSp(sizeSp: Float) = repository.setTerminalFontSizeSp(sizeSp)

    fun setTmuxOnAttachByDefault(enabled: Boolean) =
        repository.setTmuxOnAttachByDefault(enabled)

    /**
     * Persist [code] as the user's preferred Whisper language. The Voice
     * section enforces that [code] comes from
     * [AppSettings.VOICE_LANGUAGE_OPTIONS]; the repository normalises and
     * lowercases the value so manual prefs edits stay consistent.
     */
    fun setVoiceLanguage(code: String) = repository.setVoiceLanguage(code)

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

    /**
     * Toggle [AppSettings.persistFailedTranscriptions] — issue #180.
     * When off, a Whisper failure drops the audio buffer (the
     * pre-#180 behaviour). When on (default), the audio is persisted
     * so the user can retry.
     */
    fun setPersistFailedTranscriptions(enabled: Boolean) =
        repository.setPersistFailedTranscriptions(enabled)

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
