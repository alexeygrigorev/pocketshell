package com.pocketshell.app.snippets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.SnippetDao
import com.pocketshell.core.storage.entity.SnippetEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Snippet "kind" discriminator stored verbatim on [SnippetEntity.kind].
 *
 * Persisted as a free-form String to keep schema migrations open-ended
 * (per the entity's own KDoc). The UI maps to / from this enum for
 * type-safe rendering — see [SnippetsScreen] and [SnippetPickerSheet].
 */
public enum class SnippetKind(public val storageValue: String, public val label: String) {
    /** Shell command to run (the body is appended with `\n` when sent). */
    Command(storageValue = "command", label = "Command"),

    /**
     * Prompt template for an AI agent. The body is appended without a
     * trailing newline — the composer or session caller decides whether
     * to add Enter.
     */
    Prompt(storageValue = "prompt", label = "Prompt"),
    ;

    public companion object {
        /**
         * Parse the persisted [SnippetEntity.kind] string into an enum.
         * Unknown values default to [Command] — the user-visible cost of
         * a mis-typed migration is a snippet that runs as a command
         * (which can be edited via the screen), versus a hard crash.
         */
        public fun fromStorage(value: String): SnippetKind =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: Command
    }
}

/**
 * Backs [SnippetsScreen] and [SnippetPickerSheet]. Owns the per-host
 * snippet list and the CRUD operations.
 *
 * The ViewModel is Hilt-scoped — `hiltViewModel()` in the screen lifts
 * one off the activity's `ViewModelStoreOwner`, so the list survives
 * sheet open/close cycles within the same activity instance.
 *
 * ## Per-host filtering
 *
 * Snippets are scoped to a single [com.pocketshell.core.storage.entity.HostEntity]
 * row (the picker is only meaningful in the context of a host's session
 * or its host-edit form). The host id is set externally via [bindHost];
 * the [snippets] flow re-collects whenever the id changes.
 *
 * Before [bindHost] is called the [snippets] flow emits an empty list —
 * this is the cold-start state when the screen has not yet been told
 * which host to render.
 *
 * ## Lifecycle of CRUD
 *
 * All mutations go through suspending DAO calls launched in
 * [viewModelScope]. Errors (FK violations on insert, etc) are recorded
 * in [error] for the screen to surface. The screen renders an inline
 * banner; tapping it (or the next successful mutation) clears it.
 */
@HiltViewModel
public open class SnippetsViewModel @Inject constructor(
    internal val snippetDao: SnippetDao,
) : ViewModel() {

    private val _hostId: MutableStateFlow<Long?> = MutableStateFlow(null)

    /**
     * Per-host snippet list. Sorted by `label` (same ordering as
     * [SnippetDao.getByHostId]'s SQL). Re-emits whenever a snippet is
     * added / updated / deleted because Room's `Flow<List<...>>` is
     * change-notified.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public val snippets: StateFlow<List<SnippetEntity>> = _hostId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else snippetDao.getByHostId(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private val _error: MutableStateFlow<String?> = MutableStateFlow(null)

    /** Last-error message surfaced by the screen. `null` while clean. */
    public val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Tell the ViewModel which host's library to surface. Idempotent —
     * calling twice with the same id is a no-op (the StateFlow only
     * emits on distinct values).
     */
    public fun bindHost(hostId: Long) {
        _hostId.value = hostId
    }

    /** Currently-bound host id, or null if [bindHost] has not been called. */
    public fun currentHostId(): Long? = _hostId.value

    /**
     * Insert a new snippet for the currently-bound host. No-op if no
     * host is bound or [label] / [body] are blank — the screen's form
     * validation should catch these but we double-check to keep bad
     * rows out of the DB.
     */
    public fun addSnippet(label: String, body: String, kind: SnippetKind) {
        val hostId = _hostId.value ?: run {
            _error.value = "No host selected"
            return
        }
        if (label.isBlank() || body.isBlank()) {
            _error.value = "Label and body are required"
            return
        }
        viewModelScope.launch {
            try {
                snippetDao.insert(
                    SnippetEntity(
                        hostId = hostId,
                        label = label.trim(),
                        body = body,
                        kind = kind.storageValue,
                    ),
                )
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to save snippet: ${t.message}"
            }
        }
    }

    /**
     * Update an existing snippet. The [snippet] argument must carry the
     * persisted row's `id` — callers obtain it by clicking through to
     * the edit dialog from the list. The host id is preserved from the
     * supplied entity (changing it would amount to a delete + insert).
     */
    public fun updateSnippet(snippet: SnippetEntity) {
        if (snippet.label.isBlank() || snippet.body.isBlank()) {
            _error.value = "Label and body are required"
            return
        }
        viewModelScope.launch {
            try {
                snippetDao.update(snippet.copy(label = snippet.label.trim()))
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to update snippet: ${t.message}"
            }
        }
    }

    /** Delete a snippet by entity. Used by the screen's row "Delete" action. */
    public fun deleteSnippet(snippet: SnippetEntity) {
        viewModelScope.launch {
            try {
                snippetDao.delete(snippet)
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to delete snippet: ${t.message}"
            }
        }
    }

    /** Clear the inline error banner. */
    public fun clearError() {
        _error.value = null
    }

    /**
     * Test seam: the [snippets] StateFlow uses
     * [SharingStarted.WhileSubscribed], so tests that want to assert
     * against the latest emission without collecting can use this to
     * reach the raw upstream flow.
     */
    internal fun snippetsFlowFor(hostId: Long): Flow<List<SnippetEntity>> =
        snippetDao.getByHostId(hostId)
}
