package com.pocketshell.app.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * View-model state for the add / edit host form.
 *
 * Fields mirror the persisted [HostEntity] but as `String` for the
 * numeric ones (port, advanced port range) — the UI surfaces them as
 * `OutlinedTextField`s, and a partially-typed value is a valid
 * intermediate state.
 *
 * [error] is `null` while the form is clean; it becomes a user-facing
 * message after a failed [AddEditHostViewModel.save] (missing required
 * field, missing key selection).
 *
 * [saved] flips to `true` once the row is persisted — the screen
 * `LaunchedEffect`s on this flag to navigate back.
 */
data class HostFormState(
    val name: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val selectedKeyId: Long? = null,
    val error: String? = null,
    val saved: Boolean = false,
)

/**
 * Backs [AddEditHostScreen]. Reads the host being edited (if any), holds
 * the form state, and persists changes via [HostDao].
 *
 * The form takes a `selectedKeyId` that must reference a row in
 * `ssh_keys`. The brief defers key creation to [SshKeysViewModel] — the
 * key dropdown in [AddEditHostScreen] only picks from what already exists.
 * Hosts with no available key cannot be saved (the form surfaces an
 * inline error).
 */
@HiltViewModel
class AddEditHostViewModel @Inject constructor(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
) : ViewModel() {

    private val _state = MutableStateFlow(HostFormState())
    val state: StateFlow<HostFormState> = _state.asStateFlow()

    /** Live list of registered SSH keys for the dropdown. */
    val sshKeys: StateFlow<List<SshKeyEntity>> = sshKeyDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private var editingHostId: Long? = null

    /**
     * Load an existing host into the form. Idempotent — calling twice
     * with the same id is a no-op the second time.
     */
    fun loadHost(hostId: Long) {
        if (editingHostId == hostId) return
        editingHostId = hostId
        viewModelScope.launch {
            val host = hostDao.getById(hostId) ?: return@launch
            _state.value = _state.value.copy(
                name = host.name,
                hostname = host.hostname,
                port = host.port.toString(),
                username = host.username,
                selectedKeyId = host.keyId,
                error = null,
            )
        }
    }

    /** Lambda-form state updater so the screen can compose changes inline. */
    fun updateState(update: (HostFormState) -> HostFormState) {
        _state.value = update(_state.value)
    }

    /**
     * Persist the form. On success [HostFormState.saved] flips to `true`
     * so the screen can pop back; on failure [HostFormState.error] carries
     * a human-readable message.
     */
    fun save() {
        val s = _state.value
        if (s.name.isBlank() || s.hostname.isBlank() || s.username.isBlank()) {
            _state.value = s.copy(error = "Name, hostname and username are required")
            return
        }
        val keyId = s.selectedKeyId
        if (keyId == null) {
            _state.value = s.copy(error = "Select an SSH key first (Keys → +)")
            return
        }

        viewModelScope.launch {
            val host = HostEntity(
                id = editingHostId ?: 0,
                name = s.name.trim(),
                hostname = s.hostname.trim(),
                port = s.port.toIntOrNull() ?: 22,
                username = s.username.trim(),
                keyId = keyId,
            )
            if (editingHostId != null) {
                hostDao.update(host)
            } else {
                hostDao.insert(host)
            }
            _state.value = _state.value.copy(saved = true, error = null)
        }
    }
}
