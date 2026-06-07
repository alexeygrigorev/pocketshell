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
import kotlinx.coroutines.flow.map
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
 * Hard cap on derived labels. Picked at the top of the issue #190 range
 * (≤ 40 chars) so the label stays a single line in the picker on Pixel-7
 * widths without truncation that would force an ellipsis on the common
 * case (short shell commands like `tail -f /var/log/syslog`). Anything
 * longer falls back to a truncation with an ellipsis sentinel so the row
 * still reads as a label, not as truncated body text.
 */
internal const val SNIPPET_DERIVED_LABEL_MAX_CHARS: Int = 40

/**
 * Threshold below which the derived label uses the full first line
 * unchanged. The issue spec ("if first line is very short, use the full
 * first line") wants to avoid forcing an ellipsis on something like
 * `ls -la` just because the literal length is below the truncation cap.
 * Twenty chars is a comfortable phone-portrait read.
 */
internal const val SNIPPET_DERIVED_LABEL_SHORT_LINE: Int = 20

/**
 * Compute the user-facing label for a [SnippetEntity], honouring the
 * issue #190 derivation rule:
 *
 *  - If the entity carries a non-null, non-blank override label, return
 *    that (the user explicitly named the snippet).
 *  - Otherwise derive a label from the first non-empty line of [body]:
 *    very short lines (≤ [SNIPPET_DERIVED_LABEL_SHORT_LINE] chars) round-
 *    trip in full; longer lines are truncated at
 *    [SNIPPET_DERIVED_LABEL_MAX_CHARS] with a Unicode ellipsis appended.
 *  - If the body is entirely blank (shouldn't happen — the editor
 *    blocks blank bodies — but defensive against legacy rows), fall back
 *    to a placeholder so the row still has something to render.
 *
 * The function is pure so previews, tests, and renderers can call it
 * without dragging the ViewModel in.
 */
public fun deriveSnippetLabel(label: String?, body: String): String {
    val explicit = label?.trim()
    if (!explicit.isNullOrEmpty()) return explicit
    val firstLine = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: return "(empty snippet)"
    return if (firstLine.length <= SNIPPET_DERIVED_LABEL_SHORT_LINE) {
        firstLine
    } else if (firstLine.length <= SNIPPET_DERIVED_LABEL_MAX_CHARS) {
        firstLine
    } else {
        firstLine.take(SNIPPET_DERIVED_LABEL_MAX_CHARS).trimEnd() + "…"
    }
}

/**
 * Convenience for callers that already have a [SnippetEntity] handy —
 * forwards to [deriveSnippetLabel] with the entity's label/body fields.
 */
public fun SnippetEntity.displayLabel(): String = deriveSnippetLabel(label, body)

/**
 * `true` when the snippet's label was explicitly chosen by the user
 * (i.e. it is non-null and non-blank after trim). The picker uses this
 * to decide whether to render a secondary body preview underneath the
 * label — when the label IS the derived first line of the body, that
 * preview would just duplicate the primary text.
 */
public fun SnippetEntity.hasExplicitLabel(): Boolean =
    !label?.trim().isNullOrEmpty()

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
        // Sort by the *displayed* label so derived-label rows (issue #190)
        // interleave with overridden rows alphabetically instead of all
        // floating to the top via SQLite's default `NULL`-first ordering.
        .map { rows ->
            rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayLabel() })
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
     * host is bound or [body] is blank — the screen's form validation
     * should catch these but we double-check to keep bad rows out of the
     * DB.
     *
     * [label] is optional (issue #190): a non-null, non-blank value is
     * treated as a user-overridden label; a `null` or blank value tells
     * the read-side renderer to derive the label from the first line of
     * [body] (see [deriveSnippetLabel]). The default creation flow passes
     * `null` so the user is asked to type only one thing — the body.
     */
    public fun addSnippet(label: String?, body: String, kind: SnippetKind) {
        val hostId = _hostId.value ?: run {
            _error.value = "No host selected"
            return
        }
        if (body.isBlank()) {
            _error.value = "Body is required"
            return
        }
        val storedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            try {
                snippetDao.insert(
                    SnippetEntity(
                        hostId = hostId,
                        label = storedLabel,
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
     *
     * The [SnippetEntity.label] field is treated the same way as on
     * [addSnippet]: blank or `null` collapses to `null` (derive at read
     * time), non-blank stores the user's explicit override.
     */
    public fun updateSnippet(snippet: SnippetEntity) {
        if (snippet.body.isBlank()) {
            _error.value = "Body is required"
            return
        }
        val normalisedLabel = snippet.label?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            try {
                snippetDao.update(snippet.copy(label = normalisedLabel))
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to update snippet: ${t.message}"
            }
        }
    }

    /**
     * Rename a snippet from the picker / manage list row menu. Empty or
     * whitespace-only input clears the override and reverts the row to the
     * derived-label path. Other fields (body, kind, hostId) are preserved
     * verbatim from [snippet].
     */
    public fun renameSnippet(snippet: SnippetEntity, newLabel: String?) {
        val normalised = newLabel?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            try {
                snippetDao.update(snippet.copy(label = normalised))
                _error.value = null
            } catch (t: Throwable) {
                _error.value = "Failed to rename snippet: ${t.message}"
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
