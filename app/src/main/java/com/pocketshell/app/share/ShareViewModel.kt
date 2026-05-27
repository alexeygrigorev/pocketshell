package com.pocketshell.app.share

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.notifications.ShareUploadNotifications
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *
 * The ViewModel is Hilt-injected; the activity uses `viewModels()` to
 * obtain it.
 */
@HiltViewModel
internal class ShareViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
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
     * `true` once a session is attached anywhere in the app. Reserved
     * for a follow-up — the first cut never has an attached session
     * because the share intent launches PocketShell into [ShareActivity]
     * fresh, but the dialog already understands the flag so wiring
     * this up later is a one-line change.
     */
    val hasAttachedSession: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

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

    fun clearUploadState() {
        _uploadState.value = UploadState.Idle
    }

    private companion object {
        /** "Short text" threshold from the issue spec. */
        const val TEXT_PASTE_BUDGET_BYTES: Int = 8 * 1024
        const val LOG_TAG: String = "PocketShellShare"
    }
}

/**
 * The result of the upload pipeline. Mirrors a tiny state machine the
 * Compose surface branches on.
 */
internal sealed interface UploadState {
    /** Nothing in flight; show the host picker (or the empty state). */
    data object Idle : UploadState

    /** SCP upload is running. Surface a spinner + the host name. */
    data class Running(val hostName: String) : UploadState

    /** Upload landed at [remotePath] on host [hostName]. */
    data class Success(val hostName: String, val remotePath: String) : UploadState

    /** Upload failed with the human-readable [message]. */
    data class Failed(val hostName: String, val message: String) : UploadState
}

/**
 * For `text/plain` <= 8 KB shares, the share dialog gives the user
 * two options: paste into an active session, or save as a file in
 * the inbox. The ViewModel stages the choice; the activity routes the
 * "paste" option through a different code path (today disabled —
 * paste is reserved for follow-up wiring).
 */
internal enum class TextDispatchChoice {
    /** Surface the two-option dialog. */
    PromptUser,

    /** Skip the dialog and treat the text as a file save. */
    SaveAsFile,

    /**
     * Paste into the live attached session. The first cut never
     * reaches this branch because [ShareViewModel.hasAttachedSession]
     * is hard-coded to false (see ViewModel docs); the enum is here
     * so the follow-up wiring is mechanical.
     */
    PasteIntoSession,
}
