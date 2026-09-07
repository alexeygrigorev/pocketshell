package com.pocketshell.next.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.next.connect.SshKeyUnlocker
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One rendered key row. */
data class SshKeyRow(
    val id: Long,
    val name: String,
    val fingerprint: String,
    val hasPassphrase: Boolean = false,
    /** Complete authorized-keys line derived on demand; never a private PEM. */
    val publicKey: String? = null,
    val publicKeyLoading: Boolean = false,
    val publicKeyError: String? = null,
)

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
 * Both paths go through [SshKeyStore], which owns the file layout,
 * fingerprint deduplication, encrypted-key retention, and public-key
 * derivation. This class keeps only display metadata and transient operation
 * state; private PEM text and passphrases never enter [SshKeysUiState].
 */
@HiltViewModel
class SshKeysViewModel @Inject constructor(
    sshKeyDao: SshKeyDao,
    private val keyStore: SshKeyStore,
    private val unlocker: SshKeyUnlocker,
) : ViewModel() {

    private val _state = MutableStateFlow(SshKeysUiState())
    val state: StateFlow<SshKeysUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sshKeyDao.getAll()
                .map { keys -> keys.map { SshKeyRow(it.id, it.name, it.fingerprint, it.hasPassphrase) } }
                .collect { rows ->
                    _state.value = _state.value.copy(keys = rows, loaded = true)
                }
        }
    }

    /** Generate a fresh key pair on-device under [name] (blank = a timestamped default). */
    fun generate(name: String): Job {
        if (_state.value.generating) return Job()
        _state.value = _state.value.copy(generating = true, message = null)
        return viewModelScope.launch {
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
    fun import(name: String, pem: String): Job = viewModelScope.launch {
        val result = runCatching { keyStore.importKey(name.trim().ifEmpty { "imported-key" }, pem) }
        _state.value = _state.value.copy(
            message = result.fold(
                onSuccess = {
                    if (it.hasPassphrase) {
                        "Added ${it.name}; its passphrase is requested only when needed"
                    } else {
                        "Added ${it.name}"
                    }
                },
                onFailure = { it.message ?: "Could not add that key" },
            ),
        )
    }

    /**
     * Derive and cache the complete public authorized-keys line for one detail
     * sheet. [passphrase] is copied only inside the store call and is scrubbed
     * in this finally block; it is never emitted through UI state.
     */
    fun loadPublicKey(keyId: Long, passphrase: CharArray? = null) {
        val row = _state.value.keys.firstOrNull { it.id == keyId } ?: run {
            passphrase?.fill('\u0000')
            return
        }
        if (row.publicKeyLoading) {
            passphrase?.fill('\u0000')
            return
        }
        _state.value = _state.value.copy(
            keys = _state.value.keys.map {
                if (it.id == keyId) it.copy(publicKeyLoading = true, publicKeyError = null) else it
            },
        )
        viewModelScope.launch {
            val result = runCatching {
                val key = keyStore.lookup(keyId)
                    ?: error("That SSH key is no longer on this device")
                keyStore.readPublicKey(key, passphrase)
                    ?: error("The private key file is missing")
            }
            passphrase?.fill('\u0000')
            _state.value = _state.value.copy(
                keys = _state.value.keys.map {
                    if (it.id != keyId) it else result.fold(
                        onSuccess = { publicKey ->
                            it.copy(
                                publicKey = publicKey,
                                publicKeyLoading = false,
                                publicKeyError = null,
                            )
                        },
                        onFailure = { failure ->
                            it.copy(
                                publicKeyLoading = false,
                                publicKeyError = failure.message ?: "Could not read the public key",
                            )
                        },
                    )
                },
            )
        }
    }

    /**
     * Validate a key-specific fallback passphrase by actually parsing the
     * encrypted private key, then hand a scrubbed process-local copy to the
     * connection resolver. A non-empty field is never treated as an unlock.
     */
    fun unlockWithPassphrase(
        keyId: Long,
        value: CharArray,
        onResult: (success: Boolean, error: String?) -> Unit,
    ): Job {
        if (value.isEmpty()) {
            value.fill('\u0000')
            onResult(false, "Enter the key passphrase")
            return Job()
        }
        val attempt = value.copyOf()
        value.fill('\u0000')
        return viewModelScope.launch {
            val result = runCatching {
                val key = keyStore.lookup(keyId)
                    ?: error("That SSH key is no longer on this device")
                if (!key.hasPassphrase) error("That key does not require a passphrase")
                keyStore.readPublicKey(key, attempt)
                    ?: error("The private key file is missing")
                unlocker.rememberPassphrase(keyId, attempt)
            }
            attempt.fill('\u0000')
            result.fold(
                onSuccess = { onResult(true, null) },
                onFailure = {
                    onResult(false, "Could not unlock that key. Check the passphrase and try again.")
                },
            )
        }
    }

    /**
     * Delete a key. Hosts referencing it cascade-delete via the FK on
     * `hosts.keyId`, which is why the screen confirms first and says so.
     */
    fun delete(keyId: Long): Job = viewModelScope.launch {
        val key = keyStore.lookup(keyId) ?: return@launch
        val result = runCatching { keyStore.deleteKey(key) }
        _state.value = _state.value.copy(
            message = result.fold(
                onSuccess = { "Deleted ${key.name}" },
                onFailure = { "Could not delete ${key.name}: ${it.message}" },
            ),
        )
    }

    /** Dismiss the message banner. */
    fun clearMessage() {
        _state.value = _state.value.copy(message = null)
    }
}
