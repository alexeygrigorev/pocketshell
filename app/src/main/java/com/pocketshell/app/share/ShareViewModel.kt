package com.pocketshell.app.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.notifications.ShareUploadNotifications
import com.pocketshell.app.sessions.ActiveTmuxClients
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

    private val _item = MutableStateFlow<ShareableItem?>(null)
    val item: StateFlow<ShareableItem?> = _item.asStateFlow()

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

    private val uploader: ShareUploader = ShareUploader(applicationContext)

    /**
     * Stage the [item] the activity extracted from the share intent.
     * Idempotent; replaces any earlier staging (e.g. if the activity
     * is re-created across configuration change).
     */
    fun setItem(item: ShareableItem) {
        _item.value = item
        // Decide whether to surface the text-vs-file dispatch dialog.
        // `text/plain` payloads under 8 KB get the choice; everything
        // else goes straight to the host picker.
        if (item is ShareableItem.TextItem && item.text.toByteArray(Charsets.UTF_8).size <= TEXT_PASTE_BUDGET_BYTES) {
            _dispatchChoice.value = TextDispatchChoice.PromptUser
        } else {
            _dispatchChoice.value = TextDispatchChoice.SaveAsFile
        }
    }

    fun chooseSaveAsFile() {
        _dispatchChoice.value = TextDispatchChoice.SaveAsFile
    }

    fun chooseTextPasteIfAvailable() {
        if (hasAttachedSession.value) {
            _dispatchChoice.value = TextDispatchChoice.PasteIntoSession
        }
    }

    /** User tapped a host in the picker. Run the upload. */
    fun startUpload(host: HostEntity) {
        val payload = _item.value ?: return
        if (_uploadState.value is UploadState.Running) return
        _uploadState.value = UploadState.Running(host.name)
        viewModelScope.launch {
            val keyEntity = sshKeyDao.getById(host.keyId)
            if (keyEntity == null) {
                val message = "No SSH key for host ${host.name}"
                android.util.Log.w(LOG_TAG, "share upload aborted: $message")
                _uploadState.value = UploadState.Failed(host.name, message)
                ShareUploadNotifications.showFailure(applicationContext, host.name, message)
                return@launch
            }
            val result = uploader.upload(host, keyEntity, payload)
            result.fold(
                onSuccess = { remotePath ->
                    android.util.Log.i(LOG_TAG, "share upload succeeded: $remotePath")
                    _uploadState.value = UploadState.Success(host.name, remotePath)
                    ShareUploadNotifications.showSuccess(applicationContext, host.name, remotePath)
                },
                onFailure = { error ->
                    val message = error.message ?: "Upload failed"
                    android.util.Log.w(LOG_TAG, "share upload failed: $message", error)
                    _uploadState.value = UploadState.Failed(host.name, message)
                    ShareUploadNotifications.showFailure(applicationContext, host.name, message)
                },
            )
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
        val staged = _item.value
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
     * un-targeted `send-keys -l` if the pane id query fails (tmux
     * routes un-targeted `send-keys` to its last-used pane, which is
     * close enough for the fallback path).
     *
     * Throws [IllegalStateException] when tmux reports an error so the
     * caller surfaces a Failed UploadState instead of silently
     * succeeding.
     */
    private suspend fun sendTextToAttachedPane(client: TmuxClient, text: String) {
        val paneId = resolveActivePaneIdOrNull(client)
        val literal = escapeSingleQuoted(text)
        val cmd = if (paneId != null) {
            "send-keys -l -t $paneId -- '$literal'"
        } else {
            "send-keys -l -- '$literal'"
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
     * `$HOME/inbox/pocketshell/<filename>`).
     *
     * For send-keys paste (issue #193), [remotePath] holds a short
     * preview of the pasted text — the field name is preserved for
     * call-site compatibility but the value is a UI-facing string.
     */
    data class Success(val hostName: String, val remotePath: String) : UploadState

    /** Upload (or paste) failed with the human-readable [message]. */
    data class Failed(val hostName: String, val message: String) : UploadState
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
