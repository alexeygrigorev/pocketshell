package com.pocketshell.next.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One host the share can be routed to. */
data class ShareHostRow(
    val id: Long,
    val name: String,
    /** `username@hostname` — the muted mono subtitle, same shape as the host list. */
    val subtitle: String,
    /**
     * The app already holds a live connection to this host. Shown on the row,
     * and the tie-breaker that lets the picker be skipped when the user is
     * plainly "in" one host right now.
     */
    val connected: Boolean,
)

/** Where the share has got to. */
sealed interface ShareUploadState {

    /** Nothing in flight — the picker (or the empty state) is on screen. */
    data object Idle : ShareUploadState

    data class Running(val hostName: String, val detail: String) : ShareUploadState

    /** Every item landed. [paths] are the absolute remote paths, in order. */
    data class Success(
        val hostName: String,
        val paths: List<String>,
    ) : ShareUploadState {
        /**
         * Names the host, NOT the path: the paths are listed under this banner
         * verbatim, and saying the same 70-character path twice on one small
         * screen is how the one line that matters stops being read.
         */
        val message: String
            get() = if (paths.size == 1) {
                "Sent to $hostName"
            } else {
                "Sent ${paths.size} files to $hostName"
            }
    }

    /**
     * At least one item did not land.
     *
     * [uploaded] is not cosmetic: a partial failure that reported only the error
     * would leave the user unable to tell whether the other three screenshots
     * are on the host or not.
     */
    data class Failed(
        val hostName: String,
        val message: String,
        val uploaded: List<String> = emptyList(),
        val failedNames: List<String> = emptyList(),
    ) : ShareUploadState
}

/** Everything [SharePickerScreen] paints. */
data class ShareUiState(
    /** Display labels of the staged items, in share order. */
    val items: List<String> = emptyList(),
    val hosts: List<ShareHostRow> = emptyList(),
    /**
     * Room has answered. Separates "still loading" from "no hosts configured",
     * which need very different screens — the second one is a dead end the user
     * has to be told about.
     */
    val hostsLoaded: Boolean = false,
    val upload: ShareUploadState = ShareUploadState.Idle,
) {
    /** A transfer is in flight — no second one may start on top of it. */
    val busy: Boolean get() = upload is ShareUploadState.Running
}

/**
 * State and actions for the share target (rewrite task P-9, upload half).
 *
 * ## Scope
 *
 * Upload only. The shipping client's share ViewModel was ~1,500 lines because it
 * also owned a "paste into the attached tmux session" branch, a per-host project
 * target chooser, a passphrase prompt, and a lease it had to keep alive across
 * all of them. None of that is here: the session-injection half is a separate
 * (unbuilt) feature, the destination is one directory, and authentication
 * belongs to [ConnectionsRegistry] — the share reuses whatever connection the
 * app already has.
 *
 * ## Skipping the picker
 *
 * Asking "which host?" when the answer cannot be anything else is a tap the
 * gesture does not need, so [defaultShareHost] resolves the unambiguous cases
 * and the upload starts by itself. Ambiguous cases always ask; the share is a
 * write to somebody's machine, and guessing which one is worse than a tap.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    hostDao: HostDao,
    private val registry: ConnectionsRegistry,
    private val uploader: ShareUploader,
    private val notifier: ShareUploadNotifier,
    @IoDispatcher dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _state = MutableStateFlow(ShareUiState())
    val state: StateFlow<ShareUiState> = _state.asStateFlow()

    private var items: List<ShareableItem> = emptyList()
    private var uploadJob: Job? = null

    /**
     * True once this share has chosen a host, by tap or automatically. It stops
     * a later Room emission (a host added in another process, or simply the
     * flow re-emitting) from auto-starting a second upload behind a result the
     * user is reading.
     */
    private var hostChosen = false

    init {
        viewModelScope.launch {
            hostDao.getAll()
                .map { hosts -> hosts.map(::toRow) }
                .flowOn(dispatcher)
                .collect { rows ->
                    // `connected` is read HERE rather than inside the mapping,
                    // because the registry is an in-memory table this coroutine
                    // can query directly and a suspending map would make the
                    // flag older than the list it decorates.
                    val decorated = rows.map { it.copy(connected = registry.current(it.id) != null) }
                    _state.update { it.copy(hosts = decorated, hostsLoaded = true) }
                    maybeAutoStart()
                }
        }
    }

    /**
     * Stages what the share intent carried. Called once, before the first
     * composition; an empty list means the activity had nothing routable and is
     * about to finish.
     */
    fun stage(staged: List<ShareableItem>) {
        items = staged
        _state.update { it.copy(items = staged.map { item -> item.label() }) }
        maybeAutoStart()
    }

    /** A host row tap. */
    fun uploadTo(hostId: Long) {
        hostChosen = true
        start(hostId)
    }

    /** "Try again" after a failure — re-runs the whole share to the same host. */
    fun retry(hostId: Long) = start(hostId)

    /** Back to the picker after a failure, so another host can be tried. */
    fun backToPicker() {
        if (_state.value.busy) return
        notifier.clear()
        _state.update { it.copy(upload = ShareUploadState.Idle) }
    }

    /**
     * The share surface is gone for good — take the notification with it.
     *
     * One rule, no branches, and both halves matter. An in-flight upload dies
     * with [viewModelScope] here, so leaving its ONGOING "uploading…" row in the
     * status bar would advertise work that has stopped — and an ongoing
     * notification cannot be swiped away. A finished share does not need the
     * row either: the user closed the screen that had just told them the path.
     *
     * Backgrounding the share does NOT reach this: the activity is not
     * finishing, the ViewModel survives, the upload keeps running, and the
     * notification stays — which is the case it exists for.
     */
    override fun onCleared() {
        notifier.clear()
        super.onCleared()
    }

    // ------------------------------------------------------------- internals

    private fun maybeAutoStart() {
        if (hostChosen || items.isEmpty()) return
        val current = _state.value
        if (!current.hostsLoaded || current.upload !is ShareUploadState.Idle) return
        val target = defaultShareHost(current.hosts) ?: return
        hostChosen = true
        start(target.id)
    }

    private fun start(hostId: Long) {
        if (items.isEmpty() || _state.value.busy) return
        val hostName = _state.value.hosts.firstOrNull { it.id == hostId }?.name ?: "host"
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch { run(hostId, hostName) }
    }

    private suspend fun run(hostId: Long, hostName: String) {
        val payload = items
        val uploaded = mutableListOf<String>()
        val failedNames = mutableListOf<String>()
        var lastError: String? = null

        payload.forEachIndexed { index, item ->
            val label = item.label()
            val detail = if (payload.size == 1) {
                "Uploading $label"
            } else {
                "Uploading $label (${index + 1} of ${payload.size})"
            }
            _state.update { it.copy(upload = ShareUploadState.Running(hostName, detail)) }
            notifier.progress(hostName, detail)

            uploader.upload(hostId, item).fold(
                onSuccess = { path -> uploaded += path },
                onFailure = { error ->
                    failedNames += label
                    lastError = shareErrorMessage(error)
                },
            )
        }

        if (failedNames.isEmpty()) {
            _state.update { it.copy(upload = ShareUploadState.Success(hostName, uploaded)) }
            // The NOTIFICATION carries the path, where the screen's banner does
            // not: whoever reads the notification has left the screen that
            // listed the paths, so this is their only copy of it.
            notifier.success(
                hostName = hostName,
                detail = if (uploaded.size == 1) {
                    uploaded.single()
                } else {
                    "${uploaded.size} files in ${ShareUploader.INBOX_DISPLAY_PATH}"
                },
            )
            return
        }

        val message = buildString {
            append(lastError ?: "Upload failed")
            if (uploaded.isNotEmpty()) {
                append(" — ${uploaded.size} of ${payload.size} uploaded, ")
                append("failed: ${failedNames.joinToString(", ")}")
            }
        }
        _state.update {
            it.copy(
                upload = ShareUploadState.Failed(
                    hostName = hostName,
                    message = message,
                    uploaded = uploaded,
                    failedNames = failedNames,
                ),
            )
        }
        notifier.failure(hostName, message)
    }

    private companion object {
        fun toRow(host: HostEntity): ShareHostRow = ShareHostRow(
            id = host.id,
            name = host.name.ifBlank { host.hostname },
            subtitle = "${host.username}@${host.hostname}",
            connected = false,
        )
    }
}

/**
 * The host to upload to without asking, or null when the user must choose.
 *
 * Two unambiguous cases, and only those:
 *
 * 1. **One configured host.** There is no choice to offer; a picker here is a
 *    tap that can only have one outcome.
 * 2. **Exactly one live connection.** The user is demonstrably working on that
 *    host right now — it is what "the session I'm in" means to an app whose
 *    connections are one-per-host — so the share follows them there.
 *
 * Everything else asks. Two connected hosts is genuinely ambiguous, and no
 * connections with several hosts configured means the app has no evidence at
 * all about which machine the user meant. Guessing wrong writes a file to
 * somebody else's server, which is not a mistake a saved tap pays for.
 */
internal fun defaultShareHost(hosts: List<ShareHostRow>): ShareHostRow? = when {
    hosts.isEmpty() -> null
    hosts.size == 1 -> hosts.single()
    else -> hosts.filter { it.connected }.singleOrNull()
}
