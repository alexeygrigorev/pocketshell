package com.pocketshell.app.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.projects.ClaudeProfile
import com.pocketshell.app.projects.CodexProfile
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
 * Per-field validation errors for the Add / Edit host form (issue #111).
 *
 * Each property carries a human-readable message that the screen renders
 * as `OutlinedTextField` supporting text in error colour. `null` means the
 * field is clean. The whole struct is `null` on the form state until the
 * user attempts a submit — until then we don't want to red-flag empty
 * fields the user simply hasn't reached yet.
 */
data class HostFormErrors(
    val name: String? = null,
    val hostname: String? = null,
    val port: String? = null,
    val username: String? = null,
    val selectedKey: String? = null,
) {
    /** Convenience for the CTA disabled-state check. */
    fun isClean(): Boolean =
        name == null && hostname == null && port == null &&
            username == null && selectedKey == null
}

/**
 * Which field a failed [AddEditHostViewModel.save] should move focus to.
 *
 * The screen exposes a `FocusRequester` per field and routes focus to the
 * first invalid one, matching Material 3 form guidance.
 */
enum class HostFormField {
    Name,
    Hostname,
    Port,
    Username,
    SelectedKey,
}

/**
 * View-model state for the add / edit host form.
 *
 * Fields mirror the persisted [HostEntity] but as `String` for the
 * numeric ones (port, advanced port range) — the UI surfaces them as
 * `OutlinedTextField`s, and a partially-typed value is a valid
 * intermediate state.
 *
 * [fieldErrors] holds the per-field error messages set after a failed
 * `save()` attempt (issue #111). It stays empty while the user has not
 * yet attempted to submit — empty required fields don't flash red the
 * moment the screen opens.
 *
 * [firstInvalidField] is a one-shot signal so the screen knows where to
 * move focus after a rejected submit. The screen consumes it via
 * [AddEditHostViewModel.consumeFirstInvalidField] so we don't repeatedly
 * yank focus on every recomposition.
 *
 * [error] survives only as a "no SSH keys available" banner above the
 * CTA — that's a global precondition, not a per-field problem. Per-field
 * errors (missing field, invalid port, no key selected) are surfaced via
 * [fieldErrors].
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
    /**
     * Issue #117 (usage Fix C): optional override for the per-host usage
     * command. `null` and the empty string both mean "use the default
     * `pocketshell usage --json`". Persisted to [HostEntity.usageCommandOverride]
     * and forwarded to [UsageRemoteSource.fetchUsage] as
     * `commandOverride`. Not validated — any non-empty string is
     * accepted because the value is shell-executed server-side and the
     * app deliberately doesn't second-guess the user's wrapper script.
     */
    val usageCommand: String = "",
    /**
     * Issue #627: Claude Code profiles for this host. Each profile has a
     * display name and an optional config directory path on the remote host.
     * Persisted as JSON in [HostEntity.claudeProfilesJson]. Empty means
     * only the default profile (no config dir override).
     */
    val claudeProfiles: List<ClaudeProfile> = emptyList(),
    /**
     * Issue #631: Codex profiles for this host. Each profile has a display
     * name and an optional config directory path on the remote host that
     * maps to `CODEX_HOME`. Persisted as JSON in
     * [HostEntity.codexProfilesJson]. Empty means only the default profile.
     */
    val codexProfiles: List<CodexProfile> = emptyList(),
    val fieldErrors: HostFormErrors = HostFormErrors(),
    val firstInvalidField: HostFormField? = null,
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
     * Baseline form snapshot used by [isDirty] to detect unsaved changes.
     * For Add this is the empty default; for Edit it's the loaded
     * host's values. Only the user-editable fields are compared — the
     * transient `error` / `saved` flags are excluded so they don't
     * register as user edits.
     */
    private var baseline: HostFormState = HostFormState()

    /**
     * `true` when the user has typed something the baseline doesn't
     * contain. Drives the BackHandler confirmation dialog (issue #38
     * item 3) so unsaved edits aren't dropped on a system-back.
     */
    fun isDirty(): Boolean {
        val s = _state.value
        return s.name != baseline.name ||
            s.hostname != baseline.hostname ||
            s.port != baseline.port ||
            s.username != baseline.username ||
            s.selectedKeyId != baseline.selectedKeyId ||
            s.usageCommand != baseline.usageCommand ||
            s.claudeProfiles != baseline.claudeProfiles ||
            s.codexProfiles != baseline.codexProfiles
    }

    /**
     * Load an existing host into the form. Idempotent — calling twice
     * with the same id is a no-op the second time.
     */
    fun loadHost(hostId: Long) {
        if (editingHostId == hostId) return
        editingHostId = hostId
        viewModelScope.launch {
            val host = hostDao.getById(hostId) ?: return@launch
            val loaded = _state.value.copy(
                name = host.name,
                hostname = host.hostname,
                port = host.port.toString(),
                username = host.username,
                selectedKeyId = host.keyId,
                usageCommand = host.usageCommandOverride.orEmpty(),
                claudeProfiles = ClaudeProfile.fromJson(host.claudeProfilesJson),
                codexProfiles = CodexProfile.fromJson(host.codexProfilesJson),
                fieldErrors = HostFormErrors(),
                firstInvalidField = null,
                error = null,
            )
            _state.value = loaded
            // Capture the loaded form as the dirty-state baseline so a
            // user that opens-then-immediately-backs out is not prompted.
            baseline = loaded.copy(error = null, saved = false)
        }
    }

    /**
     * Lambda-form state updater so the screen can compose changes inline.
     *
     * As soon as the user types into a field that previously showed an
     * error, clear that field's error so the red outline doesn't linger
     * after they fix the value. The user has to re-press Save to
     * re-validate.
     */
    fun updateState(update: (HostFormState) -> HostFormState) {
        val previous = _state.value
        val next = update(previous)
        val cleared = clearErrorsForChangedFields(previous, next)
        _state.value = cleared
    }

    /**
     * After [save] queues focus on the first invalid field, the screen
     * pulls the value out and clears the signal. Without consuming it,
     * `LaunchedEffect(firstInvalidField)` would re-trigger on every
     * recomposition that happens before the user submits again.
     */
    fun consumeFirstInvalidField() {
        val s = _state.value
        if (s.firstInvalidField != null) {
            _state.value = s.copy(firstInvalidField = null)
        }
    }

    /**
     * Persist the form. On success [HostFormState.saved] flips to `true`
     * so the screen can pop back; on failure the per-field
     * [HostFormState.fieldErrors] are populated and
     * [HostFormState.firstInvalidField] is set so the screen can request
     * focus.
     */
    fun save() {
        val s = _state.value
        val errors = validate(s)
        if (!errors.isClean()) {
            _state.value = s.copy(
                fieldErrors = errors,
                firstInvalidField = firstInvalid(errors),
                error = null,
            )
            return
        }

        viewModelScope.launch {
            val usageOverride = s.usageCommand.trim().takeIf { it.isNotEmpty() }
            val profilesJson = ClaudeProfile.toJson(s.claudeProfiles)
            val codexProfilesJson = CodexProfile.toJson(s.codexProfiles)
            val editingId = editingHostId
            val host = if (editingId != null) {
                // Merge form fields into the persisted row so bootstrap
                // cache columns (tmuxInstalled, pocketshellInstalled,
                // lastBootstrapAt, pocketshellLastDetectedAt, etc.) and the
                // auto-forward defaults survive a form save. The form
                // only owns the user-editable fields plus the optional
                // override (usageCommandOverride from issue #117).
                val existing = hostDao.getById(editingId) ?: HostEntity(
                    id = editingId,
                    name = s.name.trim(),
                    hostname = s.hostname.trim(),
                    port = s.port.toInt(),
                    username = s.username.trim(),
                    keyId = checkNotNull(s.selectedKeyId),
                )
                existing.copy(
                    name = s.name.trim(),
                    hostname = s.hostname.trim(),
                    port = s.port.toInt(),
                    username = s.username.trim(),
                    keyId = checkNotNull(s.selectedKeyId),
                    usageCommandOverride = usageOverride,
                    claudeProfilesJson = profilesJson,
                    codexProfilesJson = codexProfilesJson,
                )
            } else {
                HostEntity(
                    id = 0,
                    name = s.name.trim(),
                    hostname = s.hostname.trim(),
                    port = s.port.toInt(),
                    username = s.username.trim(),
                    keyId = checkNotNull(s.selectedKeyId),
                    usageCommandOverride = usageOverride,
                    claudeProfilesJson = profilesJson,
                    codexProfilesJson = codexProfilesJson,
                )
            }
            if (editingId != null) {
                hostDao.update(host)
            } else {
                hostDao.insert(host)
            }
            _state.value = _state.value.copy(
                saved = true,
                fieldErrors = HostFormErrors(),
                firstInvalidField = null,
                error = null,
            )
        }
    }

    /**
     * Pure-function validation used both by [save] and by the screen for
     * the CTA enabled-state check. Kept outside the `save()` call so the
     * screen can derive `canSubmit` reactively without trying to call
     * `save()` speculatively.
     */
    internal fun validate(state: HostFormState): HostFormErrors = Companion.validate(state)

    companion object {
        /**
         * Pure-function validation. Exposed at companion scope so unit
         * tests and screen-level helpers can call it without
         * constructing a ViewModel.
         */
        fun validate(state: HostFormState): HostFormErrors {
            val name = if (state.name.isBlank()) "Required" else null
            val hostname = if (state.hostname.isBlank()) "Required" else null
            val username = if (state.username.isBlank()) "Required" else null
            val portInt = state.port.trim().toIntOrNull()
            val port = when {
                state.port.isBlank() -> "Required"
                portInt == null || portInt !in 1..65535 ->
                    "Invalid port (1-65535)"
                else -> null
            }
            val selectedKey =
                if (state.selectedKeyId == null) "Select an SSH key" else null
            return HostFormErrors(
                name = name,
                hostname = hostname,
                port = port,
                username = username,
                selectedKey = selectedKey,
            )
        }
    }

    /**
     * Clears any existing per-field error for a field whose value the
     * user just changed. Without this the error outline would persist
     * after the fix until the next Save press, which feels stale.
     */
    private fun clearErrorsForChangedFields(
        previous: HostFormState,
        next: HostFormState,
    ): HostFormState {
        val errs = previous.fieldErrors
        val updated = errs.copy(
            name = if (errs.name != null && previous.name != next.name) null else errs.name,
            hostname = if (errs.hostname != null && previous.hostname != next.hostname) null else errs.hostname,
            port = if (errs.port != null && previous.port != next.port) null else errs.port,
            username = if (errs.username != null && previous.username != next.username) null else errs.username,
            selectedKey = if (errs.selectedKey != null && previous.selectedKeyId != next.selectedKeyId) null else errs.selectedKey,
        )
        return if (updated == errs) next else next.copy(fieldErrors = updated)
    }

    private fun firstInvalid(errors: HostFormErrors): HostFormField? = when {
        errors.name != null -> HostFormField.Name
        errors.hostname != null -> HostFormField.Hostname
        errors.port != null -> HostFormField.Port
        errors.username != null -> HostFormField.Username
        errors.selectedKey != null -> HostFormField.SelectedKey
        else -> null
    }
}
