package com.pocketshell.app.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.notifications.ShareUploadNotifications
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.tmux.TMUX_PASTE_BODY_CHUNK_BYTES
import com.pocketshell.app.tmux.buildBracketedPasteHexChunks
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state + actions for [ShareActivity] (issue #138).
 *
 * Responsibilities:
 *
 * - Stream the list of configured hosts via [HostDao.getAll] so the
 *   host picker re-renders when the user adds a host from another
 *   surface.
 * - Hold the [ShareableItem] handed in from the share intent (set by
 *   the activity in `onCreate`).
 * - Run the upload via [ShareUploader] and expose the [UploadState]
 *   so the activity can swap the picker for a progress / result
 *   surface.
 * - Drive notification + clipboard surface on success/failure.
 * - Issue #193: surface a real "paste into active session" option
 *   driven by [ActiveTmuxClients]. When the user shares text and at
 *   least one host has a registered live `tmux -CC` client, the
 *   two-option dialog enables the paste branch. Tapping a host on the
 *   paste branch sends the text via `send-keys -l` to that host's
 *   active pane (reusing the byte-injection path from #160).
 *
 * The ViewModel is Hilt-injected; the activity uses `viewModels()` to
 * obtain it.
 */
@HiltViewModel
internal class ShareViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val activeTmuxClients: ActiveTmuxClients,
) : ViewModel() {

    val hosts: StateFlow<List<HostEntity>> = hostDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    /**
     * The staged share payload. Issue #258: a share can carry more than
     * one file (`ACTION_SEND_MULTIPLE`); the list holds every selected
     * item so the upload loop can route them all to the chosen host.
     * Single-file (`ACTION_SEND`) shares stage a one-element list.
     */
    private val _items = MutableStateFlow<List<ShareableItem>>(emptyList())
    val items: StateFlow<List<ShareableItem>> = _items.asStateFlow()

    /**
     * Convenience view of the first staged item. The text-dispatch and
     * paste-into-session branches (issue #193 / #209) are inherently
     * single-payload (text only), so they operate on this head item.
     */
    val item: StateFlow<ShareableItem?> = _items
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uploadState = MutableStateFlow<UploadState>(UploadState.Idle)
    val uploadState: StateFlow<UploadState> = _uploadState.asStateFlow()

    /** Two-option text dispatch: the activity sets this once before [setItem]. */
    private val _dispatchChoice = MutableStateFlow<TextDispatchChoice?>(null)
    val dispatchChoice: StateFlow<TextDispatchChoice?> = _dispatchChoice.asStateFlow()

    /**
     * Issue #193: derived from [ActiveTmuxClients]. `true` whenever at
     * least one host currently has a registered live `tmux -CC` client
     * (i.e. the user is attached to a tmux session on that host
     * elsewhere in the app). The upfront dispatch dialog gates the
     * "Paste into session" option on this; per-host attached status is
     * additionally exposed via [hostsWithAttachedSession] so the picker
     * can filter to only the hosts where paste is actually possible.
     *
     * Previously hardcoded `false`. Now reflects the real registry so
     * users can route a short text share straight into the pane they
     * are looking at without taking the "save as file" detour.
     */
    val hasAttachedSession: StateFlow<Boolean> = activeTmuxClients.clients
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /**
     * Issue #193: per-host attached snapshot. Keyed by host id; an entry
     * is present iff that host has a live `tmux -CC` client registered
     * in [ActiveTmuxClients] right now. The picker reads this to gate
     * the paste tap target on each row.
     */
    val hostsWithAttachedSession: StateFlow<Set<Long>> = activeTmuxClients.clients
        .map { it.keys.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    /**
     * The per-file uploader. A `var` so unit tests can substitute a
     * fake that records calls and drives success/failure per item,
     * exercising the multi-file loop + partial-failure aggregation
     * (issue #258) without a live SSH session. Production code never
     * reassigns it.
     */
    @androidx.annotation.VisibleForTesting
    internal var uploader: ShareItemUploader = ShareUploader(applicationContext)

    /**
     * Stage the [items] the activity extracted from the share intent.
     * Idempotent; replaces any earlier staging (e.g. if the activity
     * is re-created across configuration change).
     *
     * Issue #258: a multi-file share (`ACTION_SEND_MULTIPLE`) stages
     * more than one item. The text-vs-file dispatch dialog only makes
     * sense for a single short text payload, so a multi-item share
     * always goes straight to the host picker as a file save.
     */
    fun setItems(items: List<ShareableItem>) {
        _items.value = items
        // Decide whether to surface the text-vs-file dispatch dialog.
        // A single `text/plain` payload under 8 KB gets the choice;
        // everything else (URIs, oversized text, or multi-file shares)
        // goes straight to the host picker.
        val single = items.singleOrNull()
        if (single is ShareableItem.TextItem &&
            single.text.toByteArray(Charsets.UTF_8).size <= TEXT_PASTE_BUDGET_BYTES
        ) {
            _dispatchChoice.value = TextDispatchChoice.PromptUser
        } else {
            _dispatchChoice.value = TextDispatchChoice.SaveAsFile
        }
    }

    /** Single-item staging convenience used by tests and callers. */
    fun setItem(item: ShareableItem) = setItems(listOf(item))

    fun chooseSaveAsFile() {
        _dispatchChoice.value = TextDispatchChoice.SaveAsFile
    }

    fun chooseTextPasteIfAvailable() {
        if (hasAttachedSession.value) {
            _dispatchChoice.value = TextDispatchChoice.PasteIntoSession
        }
    }

    /**
     * User tapped a host in the picker. Upload every staged item to that
     * host's inbox.
     *
     * Issue #258: a multi-file share stages more than one item; the loop
     * uploads each in turn and aggregates the outcome so the result
     * surface can report partial failure ("3 of 4 uploaded" / lists the
     * names that failed) rather than silently dropping files. A single-
     * file share still produces the familiar one-path success/failure
     * surface because [UploadState.totalCount] is 1.
     */
    fun startUpload(host: HostEntity) {
        val payload = _items.value
        if (payload.isEmpty()) return
        if (_uploadState.value is UploadState.Running) return
        _uploadState.value = UploadState.Running(host.name)
        viewModelScope.launch {
            val keyEntity = sshKeyDao.getById(host.keyId)
            if (keyEntity == null) {
                val message = "No SSH key for host ${host.name}"
                android.util.Log.w(LOG_TAG, "share upload aborted: $message")
                _uploadState.value =
                    UploadState.Failed(host.name, message, totalCount = payload.size)
                ShareUploadNotifications.showFailure(applicationContext, host.name, message)
                return@launch
            }

            val succeededPaths = mutableListOf<String>()
            val failedNames = mutableListOf<String>()
            var lastError: String? = null

            for (item in payload) {
                val itemLabel = item.displayName?.takeIf { it.isNotBlank() } ?: "file"
                uploader.upload(host, keyEntity, item).fold(
                    onSuccess = { remotePath ->
                        android.util.Log.i(LOG_TAG, "share upload succeeded: $remotePath")
                        succeededPaths += remotePath
                    },
                    onFailure = { error ->
                        val message = error.message ?: "Upload failed"
                        android.util.Log.w(
                            LOG_TAG,
                            "share upload failed for $itemLabel: $message",
                            error,
                        )
                        failedNames += itemLabel
                        lastError = message
                    },
                )
            }

            publishUploadOutcome(
                host = host,
                total = payload.size,
                succeededPaths = succeededPaths,
                failedNames = failedNames,
                lastError = lastError,
            )
        }
    }

    /**
     * Map the accumulated per-file results into a single terminal
     * [UploadState] and drive the matching notification.
     *
     * - All succeeded -> [UploadState.Success] (detail is the single
     *   remote path for a one-file share, or an "N files" summary).
     * - Some succeeded, some failed -> [UploadState.Failed] surfaced as
     *   a partial failure that names what did not upload, while still
     *   reporting the success count so the user knows the rest landed.
     * - None succeeded -> [UploadState.Failed] with the last error.
     */
    private fun publishUploadOutcome(
        host: HostEntity,
        total: Int,
        succeededPaths: List<String>,
        failedNames: List<String>,
        lastError: String?,
    ) {
        val successCount = succeededPaths.size
        when {
            failedNames.isEmpty() -> {
                val detail = if (total > 1) {
                    "$successCount files uploaded:\n" + succeededPaths.joinToString("\n")
                } else {
                    succeededPaths.firstOrNull().orEmpty()
                }
                _uploadState.value = UploadState.Success(
                    hostName = host.name,
                    remotePath = detail,
                    successCount = successCount,
                    totalCount = total,
                )
                ShareUploadNotifications.showSuccess(applicationContext, host.name, detail)
            }
            successCount == 0 -> {
                val message = lastError ?: "Upload failed"
                _uploadState.value = UploadState.Failed(
                    hostName = host.name,
                    message = message,
                    totalCount = total,
                    successCount = 0,
                    failedNames = failedNames,
                )
                ShareUploadNotifications.showFailure(applicationContext, host.name, message)
            }
            else -> {
                val message = buildString {
                    append("$successCount of $total uploaded. ")
                    append("Failed: ")
                    append(failedNames.joinToString(", "))
                    lastError?.let { append(" ($it)") }
                }
                android.util.Log.w(LOG_TAG, "partial share upload: $message")
                _uploadState.value = UploadState.Failed(
                    hostName = host.name,
                    message = message,
                    totalCount = total,
                    successCount = successCount,
                    failedNames = failedNames,
                )
                ShareUploadNotifications.showFailure(applicationContext, host.name, message)
            }
        }
    }

    /**
     * Issue #193: paste the staged text into the active pane of the
     * tmux session attached on [host]. Looks up the live [TmuxClient]
     * from [ActiveTmuxClients]; resolves the focused pane via
     * `display-message -p '#{pane_id}'`; writes the text with
     * `send-keys -l -- '<escaped>'` so it is treated as a literal
     * paste (no key-bind interpretation). Mirrors the byte-injection
     * pattern used by [com.pocketshell.app.tmux.TmuxSessionViewModel]'s
     * `send-keys -l` path (issue #160).
     *
     * No carriage return is appended — share-paste is meant to populate
     * the line so the user can review and press Enter themselves
     * (matches "Paste" semantics, not "Submit"). Callers that need an
     * implicit submit can append "\n" in the staged payload.
     *
     * Surfaces success/failure through the existing [UploadState]
     * machine so the picker UI does not need a parallel result surface.
     * The "remotePath" slot is repurposed for a one-line preview of the
     * pasted text (truncated to keep the notification short).
     */
    fun pasteIntoSession(host: HostEntity) {
        val staged = _items.value.firstOrNull()
        if (staged !is ShareableItem.TextItem) {
            val message = "Nothing to paste"
            _uploadState.value = UploadState.Failed(host.name, message)
            return
        }
        if (_uploadState.value is UploadState.Running) return
        val entry = activeTmuxClients.clients.value[host.id]
        if (entry == null) {
            // Defensive — the picker should not surface this host as
            // tappable for paste, but if a race tore the client down
            // between rendering and tapping we degrade to a clear
            // message that points the user at the file-save fallback.
            val message = "No active session on ${host.name} — save to inbox instead"
            android.util.Log.w(LOG_TAG, "share paste aborted: $message")
            _uploadState.value = UploadState.Failed(host.name, message)
            ShareUploadNotifications.showFailure(applicationContext, host.name, message)
            return
        }
        _uploadState.value = UploadState.Running(host.name)
        viewModelScope.launch {
            val sendResult = runCatching {
                sendTextToAttachedPane(entry.client, staged.text)
            }
            sendResult.fold(
                onSuccess = {
                    val preview = previewFor(staged.text)
                    android.util.Log.i(
                        LOG_TAG,
                        "share paste succeeded on ${host.name}: ${preview.length} chars",
                    )
                    _uploadState.value = UploadState.Success(host.name, preview)
                    ShareUploadNotifications.showSuccess(applicationContext, host.name, preview)
                },
                onFailure = { error ->
                    val message = error.message ?: "Paste failed"
                    android.util.Log.w(LOG_TAG, "share paste failed: $message", error)
                    _uploadState.value = UploadState.Failed(host.name, message)
                    ShareUploadNotifications.showFailure(applicationContext, host.name, message)
                },
            )
        }
    }

    /**
     * Issue #193: send [text] as a literal paste to the currently
     * active pane on [client]'s tmux server. Resolves the active pane
     * via `display-message -p '#{pane_id}'` first so the bytes land
     * on the pane the user can actually see; falls back to an
     * un-targeted `send-keys` if the pane id query fails (tmux
     * routes un-targeted `send-keys` to its last-used pane, which is
     * close enough for the fallback path).
     *
     * Issue #209: when [text] contains a `\n`, the bytes are routed
     * through tmux's `send-keys -H` with bracketed-paste markers
     * (`\e[200~` ... `\e[201~`) so the receiving program (Claude Code
     * CLI, modern bash/zsh readline, vim, …) treats the entire block
     * as ONE pasted prompt instead of submitting line-by-line. Large
     * single-line text uses the same bounded chunk route so a share
     * cannot create one unbounded tmux control command. Normal-sized
     * single-line text keeps the existing `send-keys -l` shape so the
     * regression suite around the share paste UI is preserved.
     *
     * Throws [IllegalStateException] when tmux reports an error so the
     * caller surfaces a Failed UploadState instead of silently
     * succeeding.
     */
    private suspend fun sendTextToAttachedPane(client: TmuxClient, text: String) {
        val paneId = resolveActivePaneIdOrNull(client)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (text.contains('\n') || bytes.size > TMUX_PASTE_BODY_CHUNK_BYTES) {
            for (hex in buildBracketedPasteHexChunks(bytes)) {
                val response = if (paneId != null) {
                    client.sendCommand("send-keys -H -t $paneId $hex")
                } else {
                    client.sendCommand("send-keys -H $hex")
                }
                if (response.isError) {
                    val detail = response.output.joinToString(separator = " ").trim()
                    throw IllegalStateException(
                        "tmux rejected paste${if (detail.isNotEmpty()) ": $detail" else ""}",
                    )
                }
            }
            return
        }
        val cmd = run {
            val literal = escapeSingleQuoted(text)
            if (paneId != null) {
                "send-keys -l -t $paneId -- '$literal'"
            } else {
                "send-keys -l -- '$literal'"
            }
        }
        val response = client.sendCommand(cmd)
        if (response.isError) {
            val detail = response.output.joinToString(separator = " ").trim()
            throw IllegalStateException(
                "tmux rejected paste${if (detail.isNotEmpty()) ": $detail" else ""}",
            )
        }
    }

    private suspend fun resolveActivePaneIdOrNull(client: TmuxClient): String? {
        val response = runCatching {
            client.sendCommand("display-message -p '#{pane_id}'")
        }.getOrNull() ?: return null
        if (response.isError) return null
        val first = response.output.firstOrNull()?.trim().orEmpty()
        return first.takeIf { it.startsWith("%") }
    }

    fun clearUploadState() {
        _uploadState.value = UploadState.Idle
    }

    private companion object {
        /** "Short text" threshold from the issue spec. */
        const val TEXT_PASTE_BUDGET_BYTES: Int = 8 * 1024
        const val LOG_TAG: String = "PocketShellShare"

        /** Truncation cap for the paste preview surfaced in UploadState.Success. */
        const val PASTE_PREVIEW_LENGTH: Int = 80

        fun previewFor(text: String): String {
            val firstLine = text.lineSequence().firstOrNull().orEmpty()
            return if (firstLine.length > PASTE_PREVIEW_LENGTH) {
                firstLine.take(PASTE_PREVIEW_LENGTH) + "…"
            } else {
                firstLine
            }
        }

        /**
         * Single-quote escape for tmux command arguments. Mirrors the
         * private helper in [com.pocketshell.app.tmux.TmuxSessionViewModel] —
         * inlined here to keep the share package decoupled from
         * `:tmux` internals.
         */
        fun escapeSingleQuoted(input: String): String =
            input.replace("'", "'\\''")
    }
}

/**
 * The result of the upload pipeline. Mirrors a tiny state machine the
 * Compose surface branches on.
 *
 * Issue #193 repurposes the success/failure variants for the
 * paste-into-session path too — the `remotePath`-named field carries a
 * short text preview when the operation was a paste rather than an
 * upload. The field name is retained to avoid churning the existing
 * notification call sites; the renderer treats both paths uniformly.
 */
internal sealed interface UploadState {
    /** Nothing in flight; show the host picker (or the empty state). */
    data object Idle : UploadState

    /** SCP upload (or send-keys paste) is running. Surface a spinner + the host name. */
    data class Running(val hostName: String) : UploadState

    /**
     * Operation succeeded.
     *
     * For file uploads, [remotePath] is the absolute remote path (e.g.
     * `$HOME/inbox/pocketshell/<filename>`). For a multi-file share
     * (issue #258) it is a newline-joined summary of every uploaded
     * path; [successCount]/[totalCount] carry the counts so the result
     * surface and toast can say "uploaded N/N".
     *
     * For send-keys paste (issue #193), [remotePath] holds a short
     * preview of the pasted text — the field name is preserved for
     * call-site compatibility but the value is a UI-facing string.
     */
    data class Success(
        val hostName: String,
        val remotePath: String,
        val successCount: Int = 1,
        val totalCount: Int = 1,
    ) : UploadState

    /**
     * Upload (or paste) failed with the human-readable [message].
     *
     * Issue #258: a multi-file share can fail partially. [successCount]
     * reports how many of [totalCount] files did upload (0 for a total
     * failure), and [failedNames] lists the items that did not so the
     * UI can show the user exactly what to retry.
     */
    data class Failed(
        val hostName: String,
        val message: String,
        val totalCount: Int = 1,
        val successCount: Int = 0,
        val failedNames: List<String> = emptyList(),
    ) : UploadState
}

/**
 * For `text/plain` <= 8 KB shares, the share dialog gives the user
 * two options: paste into an active session, or save as a file in
 * the inbox. The ViewModel stages the choice; the activity routes the
 * "paste" option through a different code path.
 *
 * Issue #193 wires the paste branch end-to-end. When at least one host
 * has a registered live `tmux -CC` client in [ActiveTmuxClients], the
 * upfront dialog enables [PasteIntoSession]; the picker then offers a
 * tap-to-paste row for each host with an attached session.
 */
internal enum class TextDispatchChoice {
    /** Surface the two-option dialog. */
    PromptUser,

    /** Skip the dialog and treat the text as a file save. */
    SaveAsFile,

    /**
     * Paste into the live attached session. Issue #193: the picker
     * routes taps on hosts in [ShareViewModel.hostsWithAttachedSession]
     * through [ShareViewModel.pasteIntoSession] instead of the SCP
     * uploader.
     */
    PasteIntoSession,
}
