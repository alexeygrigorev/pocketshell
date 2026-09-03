package com.pocketshell.next.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.SshKeyDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One rendered key row. */
data class SshKeyRow(val id: Long, val name: String, val fingerprint: String)

/** What [SshKeysScreen] renders. */
data class SshKeysUiState(
    val keys: List<SshKeyRow> = emptyList(),
    val loaded: Boolean = false,
    /** In-flight generate; the button shows progress and cannot be double-tapped. */
    val generating: Boolean = false,
    /** Last user-facing message (an error, or a confirmation of what was added). */
    val message: String? = null,
)

/**
 * Backs [SshKeysScreen] — generate a key, or import an existing one (rewrite
 * task P-6).
 *
 * Both paths go through [SshKeyStore], which is where the file layout, the
 * fingerprint deduplication, and the encrypted-key refusal live; this class is
 * the list projection plus the one-line message the screen shows afterwards.
 *
 * The passphrase half of the original P-6 scope (biometric-gated unlock) was
 * cut, so there is no passphrase field anywhere here: an encrypted key is
 * rejected by [SshKeyStore.importKey] with an explanation, and everything this
 * screen creates is unencrypted.
 */
@HiltViewModel
class SshKeysViewModel @Inject constructor(
    sshKeyDao: SshKeyDao,
    private val keyStore: SshKeyStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SshKeysUiState())
    val state: StateFlow<SshKeysUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sshKeyDao.getAll()
                .map { keys -> keys.map { SshKeyRow(it.id, it.name, it.fingerprint) } }
                .collect { rows ->
                    _state.value = _state.value.copy(keys = rows, loaded = true)
                }
        }
    }

    /** Generate a fresh key pair on-device under [name] (blank = a timestamped default). */
    fun generate(name: String) {
        if (_state.value.generating) return
        _state.value = _state.value.copy(generating = true, message = null)
        viewModelScope.launch {
            val result = runCatching {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) keyStore.generateKey() else keyStore.generateKey(trimmed)
            }
            _state.value = _state.value.copy(
                generating = false,
                message = result.fold(
                    onSuccess = { "Generated ${it.name}" },
                    onFailure = { "Could not generate a key: ${it.message}" },
                ),
            )
        }
    }

    /** Register an existing private key pasted or read from a file. */
    fun import(name: String, pem: String) {
        viewModelScope.launch {
            val result = runCatching { keyStore.importKey(name.trim().ifEmpty { "imported-key" }, pem) }
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = { "Added ${it.name}" },
                    // NotAPrivateKey / EncryptedKeyUnsupported already carry a
                    // full user-facing sentence, so it is shown verbatim.
                    onFailure = { it.message ?: "Could not add that key" },
                ),
            )
        }
    }

    /**
     * Delete a key. Hosts referencing it cascade-delete via the FK on
     * `hosts.keyId`, which is why the screen confirms first and says so.
     */
    fun delete(keyId: Long) {
        viewModelScope.launch {
            val key = keyStore.lookup(keyId) ?: return@launch
            val result = runCatching { keyStore.deleteKey(key) }
            _state.value = _state.value.copy(
                message = result.fold(
                    onSuccess = { "Deleted ${key.name}" },
                    onFailure = { "Could not delete ${key.name}: ${it.message}" },
                ),
            )
        }
    }

    /** Dismiss the message banner. */
    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
