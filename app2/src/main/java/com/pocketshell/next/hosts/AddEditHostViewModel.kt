package com.pocketshell.next.hosts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which form field a failed submit should point at. */
enum class HostFormField { Name, Hostname, Port, Username, Key }

/**
 * Per-field validation messages. `null` means the field is clean; the whole
 * struct is clean until the first submit attempt, so an untouched form does not
 * open covered in red.
 */
data class HostFormErrors(
    val name: String? = null,
    val hostname: String? = null,
    val port: String? = null,
    val username: String? = null,
    val key: String? = null,
) {
    val isClean: Boolean
        get() = name == null && hostname == null && port == null && username == null && key == null

    /** The field a rejected submit should move focus to, in reading order. */
    val firstInvalid: HostFormField?
        get() = when {
            name != null -> HostFormField.Name
            hostname != null -> HostFormField.Hostname
            port != null -> HostFormField.Port
            username != null -> HostFormField.Username
            key != null -> HostFormField.Key
            else -> null
        }
}

/**
 * The add/edit form's state.
 *
 * [port] is a `String`, not an `Int`: a half-typed port ("2", "22") is a legal
 * intermediate state of a text field, and modelling it as an `Int` forces
 * either a crash or a silent value on every keystroke. Parsing happens once, in
 * [AddEditHostViewModel.validate].
 */
data class HostFormState(
    val name: String = "",
    val hostname: String = "",
    val port: String = "22",
    val username: String = "",
    val selectedKeyId: Long? = null,
    val errors: HostFormErrors = HostFormErrors(),
    /** True while an existing host is being read; false for Add, which has nothing to read. */
    val loading: Boolean = false,
    /** True when this form is editing a stored host rather than creating one. */
    val editing: Boolean = false,
    /** One-shot: the row was written and the screen should navigate away. */
    val saved: Boolean = false,
)

/**
 * Backs [AddEditHostScreen] — the only way to get a host into app2 by hand
 * (rewrite task P-6).
 *
 * ## Identity, and the #2456 / audit-F1 bug this is shaped to prevent
 *
 * The old client kept the host being edited in a plain `editingHostId` field on
 * an Activity-scoped ViewModel, and bound it only when the route carried an id:
 *
 * ```kotlin
 * fun bind(hostId: Long?) { if (hostId == null) return; editingHostId = hostId; … }
 * ```
 *
 * Entering Add after an Edit therefore left A's id in place, and `save()` — which
 * updates whenever the id is non-null — overwrote host A with the details the
 * user typed for host B. The screen said "Add host" the whole time, because the
 * title came from the route while the write came from the retained field.
 *
 * Two changes make that unrepresentable here:
 *
 * 1. **Identity is not a field.** It lives in [SavedStateHandle] under the
 *    route's own `hostId` argument, so "which host is this form editing" and
 *    "which host did navigation ask for" are one value, not two that can drift.
 *    It also survives process death for free.
 * 2. **[bind] is unconditional.** `bind(null)` is a real instruction — clear the
 *    identity and reset the form — not a no-op. A stale in-flight load from a
 *    previous binding is fenced by [bindGeneration] so it cannot land on top of
 *    the form the user is now filling in.
 *
 * `AddEditHostViewModelTest` drives both directions (edit→add and add→edit) on a
 * single instance, which is the only way to observe the retained-identity bug at
 * all.
 */
@HiltViewModel
class AddEditHostViewModel @Inject constructor(
    private val hostDao: HostDao,
    sshKeyDao: SshKeyDao,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** Live key list for the picker; empty means the form cannot be submitted yet. */
    val sshKeys: StateFlow<List<SshKeyEntity>> = sshKeyDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val _state = MutableStateFlow(HostFormState())
    val state: StateFlow<HostFormState> = _state.asStateFlow()

    /**
     * The host being edited, or `null` in Add mode. Backed by the route
     * argument in [SavedStateHandle] — see the class doc; there is deliberately
     * no field holding this.
     */
    private var editingHostId: Long?
        get() = savedStateHandle.get<Long>(Destination.ARG_HOST_ID)?.takeIf { it > 0L }
        set(value) {
            savedStateHandle[Destination.ARG_HOST_ID] = value ?: Destination.NO_HOST_ID
        }

    /** Fences a load started by an earlier [bind] against a later one. */
    private var bindGeneration: Long = 0

    private var hasBound: Boolean = false

    /**
     * Point the form at [hostId], or at nothing (Add) when it is `null` or not
     * a real row id.
     *
     * Idempotent for the same target so a recomposition does not reload the
     * form out from under the user's typing — but it is NOT idempotent across
     * targets, and `null` is a target like any other.
     */
    fun bind(hostId: Long?) {
        val target = hostId?.takeIf { it > 0L }
        if (hasBound && editingHostId == target) return
        hasBound = true
        editingHostId = target
        val generation = ++bindGeneration

        if (target == null) {
            _state.value = HostFormState()
            return
        }

        _state.value = HostFormState(loading = true, editing = true)
        viewModelScope.launch {
            val host = hostDao.getById(target)
            // The user re-bound (typically: navigated to Add) while this read
            // was in flight. Dropping the result is the whole point of the
            // generation counter.
            if (generation != bindGeneration) return@launch
            _state.value = if (host == null) {
                // The row was deleted between navigating and loading. Fall back
                // to a blank Add form rather than an Edit form for a ghost.
                editingHostId = null
                HostFormState()
            } else {
                HostFormState(
                    name = host.name,
                    hostname = host.hostname,
                    port = host.port.toString(),
                    username = host.username,
                    selectedKeyId = host.keyId,
                    editing = true,
                )
            }
        }
    }

    /**
     * Apply an edit to the form. Any error on a field the user just changed is
     * cleared, so a corrected value stops looking wrong immediately instead of
     * waiting for the next submit.
     */
    fun update(transform: (HostFormState) -> HostFormState) {
        val previous = _state.value
        val next = transform(previous)
        _state.value = next.copy(errors = clearTouchedErrors(previous, next))
    }

    /**
     * Validate and persist.
     *
     * On success [HostFormState.saved] flips once; the screen consumes it via
     * [consumeSaved] so the navigation does not re-fire on the next
     * recomposition.
     */
    fun save() {
        val current = _state.value
        val errors = validate(current)
        if (!errors.isClean) {
            _state.value = current.copy(errors = errors)
            return
        }

        viewModelScope.launch {
            val port = current.port.trim().toInt()
            val keyId = requireNotNull(current.selectedKeyId)
            val editingId = editingHostId

            if (editingId == null) {
                hostDao.insert(
                    HostEntity(
                        name = current.name.trim(),
                        hostname = current.hostname.trim(),
                        port = port,
                        username = current.username.trim(),
                        keyId = keyId,
                    ),
                )
            } else {
                // Merge onto the stored row instead of building a new entity, so
                // the columns this form does not own — bootstrap/CLI detection
                // cache, forwarding defaults, treeIdentity, lastConnectedAt —
                // survive an edit. A fresh HostEntity would reset all of them.
                val existing = hostDao.getById(editingId)
                if (existing == null) {
                    // Deleted underneath us mid-edit. Writing an Update for a
                    // row that no longer exists is a silent no-op, so insert.
                    hostDao.insert(
                        HostEntity(
                            name = current.name.trim(),
                            hostname = current.hostname.trim(),
                            port = port,
                            username = current.username.trim(),
                            keyId = keyId,
                        ),
                    )
                } else {
                    val endpointUnchanged =
                        existing.hostname.equals(current.hostname.trim(), ignoreCase = true) &&
                            existing.port == port
                    hostDao.update(
                        existing.copy(
                            name = current.name.trim(),
                            hostname = current.hostname.trim(),
                            port = port,
                            username = current.username.trim(),
                            keyId = keyId,
                            // Trust is pinned to an exact endpoint. Repointing
                            // the row at a different host:port must not carry
                            // the old server's accepted key forward, or the
                            // next dial silently trusts the wrong machine.
                            trustedHostKeyAlgorithm =
                                existing.trustedHostKeyAlgorithm.takeIf { endpointUnchanged },
                            trustedHostKeySha256 =
                                existing.trustedHostKeySha256.takeIf { endpointUnchanged },
                        ),
                    )
                }
            }
            _state.value = _state.value.copy(saved = true, errors = HostFormErrors())
        }
    }

    /** Acknowledge [HostFormState.saved] after navigating away. */
    fun consumeSaved() {
        val current = _state.value
        if (current.saved) _state.value = current.copy(saved = false)
    }

    private fun clearTouchedErrors(previous: HostFormState, next: HostFormState): HostFormErrors {
        val errors = previous.errors
        return errors.copy(
            name = errors.name.takeIf { previous.name == next.name },
            hostname = errors.hostname.takeIf { previous.hostname == next.hostname },
            port = errors.port.takeIf { previous.port == next.port },
            username = errors.username.takeIf { previous.username == next.username },
            key = errors.key.takeIf { previous.selectedKeyId == next.selectedKeyId },
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65535

        /**
         * Pure validation, so the screen can derive its submit-enabled state
         * without speculatively calling [save].
         *
         * The port rule is the reason this is not just a set of `isBlank`
         * checks: a text field can hold "22x" or "99999", and both have to be a
         * validation message rather than a `NumberFormatException` on submit.
         */
        fun validate(state: HostFormState): HostFormErrors {
            val port = state.port.trim().toIntOrNull()
            return HostFormErrors(
                name = "Required".takeIf { state.name.isBlank() },
                hostname = "Required".takeIf { state.hostname.isBlank() },
                port = when {
                    state.port.isBlank() -> "Required"
                    port == null || port !in MIN_PORT..MAX_PORT -> "Enter a port between 1 and 65535"
                    else -> null
                },
                username = "Required".takeIf { state.username.isBlank() },
                key = "Choose an SSH key".takeIf { state.selectedKeyId == null },
            )
        }
    }
}
