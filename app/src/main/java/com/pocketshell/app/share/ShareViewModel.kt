package com.pocketshell.app.share

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.composer.PromptAttachmentStager
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.notifications.ShareUploadNotifications
import com.pocketshell.app.projects.SshFolderListGateway
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.terminal.input.BracketedPaste
import com.pocketshell.core.tmux.TmuxClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *   so the activity can swap the picker for progress and failure
 *   surfaces.
 * - Drive failure feedback while keeping successful file-share upload
 *   completion quiet.
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
internal class ShareViewModel internal constructor(
    @ApplicationContext private val applicationContext: Context,
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val activeTmuxClients: ActiveTmuxClients,
    private val projectRootDao: ProjectRootDao,
    private val sshLeaseManager: SshLeaseManager,
    private val stageIntoSessionTimeoutMs: Long = STAGE_INTO_SESSION_TIMEOUT_MS,
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext applicationContext: Context,
        hostDao: HostDao,
        sshKeyDao: SshKeyDao,
        activeTmuxClients: ActiveTmuxClients,
        projectRootDao: ProjectRootDao,
        sshLeaseManager: SshLeaseManager,
    ) : this(
        applicationContext = applicationContext,
        hostDao = hostDao,
        sshKeyDao = sshKeyDao,
        activeTmuxClients = activeTmuxClients,
        projectRootDao = projectRootDao,
        sshLeaseManager = sshLeaseManager,
        stageIntoSessionTimeoutMs = STAGE_INTO_SESSION_TIMEOUT_MS,
    )

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

    private val _shareFlowForeground = MutableStateFlow(true)

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
     * Issue #473: target selection for the picked host. When non-null,
     * the picker shows the per-host target chooser ("Host inbox" + the
     * active-session quick target + the host's known projects) instead
     * of immediately uploading. Cleared when the user backs out or the
     * upload starts.
     */
    private val _targetSelection = MutableStateFlow<TargetSelection?>(null)
    val targetSelection: StateFlow<TargetSelection?> = _targetSelection.asStateFlow()

    /**
     * The per-file uploader. A `var` so unit tests can substitute a
     * fake that records calls and drives success/failure per item,
     * exercising the multi-file loop + partial-failure aggregation
     * (issue #258) without a live SSH session. Production code never
     * reassigns it.
     */
    @androidx.annotation.VisibleForTesting
    internal var uploader: ShareItemUploader = ShareUploader(
        context = applicationContext,
        connect = { host, key, keyPath -> connectForUpload(host, key, keyPath) },
    )

    private var lastShareAction: ShareRetryAction? = null

    /**
     * Issue #654: the passphrase entered for the current share's
     * passphrase-protected key, if any. A fresh share connect (when no
     * warm app lease is reusable) needs the key's passphrase exactly the
     * way the main app does. The share activity is launched cold from the
     * system share sheet, so it cannot inherit the live session's already
     * unlocked credentials when that session's warm lease has expired
     * (e.g. the user spent longer than the background grace window in the
     * other app). We hold the freshly entered passphrase here for the
     * lifetime of this one share so the upload + any retry reuse it, and
     * clear it the moment the share surface is dismissed so it never
     * outlives the transaction.
     */
    private val pendingPassphrase = AtomicReference<CharArray?>(null)

    /**
     * Issue #560: one-shot navigation event. After the shared file is
     * staged into a chosen active session's `.pocketshell/attachments`
     * scope (the #544 mechanic), the ViewModel emits a [SessionLaunch] here.
     * [ShareActivity] collects it, launches [com.pocketshell.app.MainActivity]
     * into that tmux session with the staged remote path pre-loaded as a
     * composer attachment chip, and finishes itself.
     *
     * Backed by a [Channel] so the event is delivered to the activity's
     * collector even if it subscribes a tick after the emit (the staging
     * round-trip can outrun the first composition).
     */
    private val _sessionLaunch = Channel<SessionLaunch>(capacity = Channel.BUFFERED)
    val sessionLaunch: Flow<SessionLaunch> = _sessionLaunch.receiveAsFlow()

    /**
     * Issue #560/#621: SSH connect seam for the share-into-session staging
     * round-trip. Defaults to the app-scoped SSH lease manager so shares
     * use the same host/key identity as active sessions instead of starting
     * an unrelated auth attempt. A `var` lets unit tests substitute a fake
     * session without a live host. Production never reassigns it.
     */
    @androidx.annotation.VisibleForTesting
    internal var connectForStaging: suspend (HostEntity, SshKeyEntity) -> Result<SshSession> =
        { host, keyEntity ->
            acquireLeaseBackedSession(host = host, keyPath = keyEntity.privateKeyPath)
        }

    private suspend fun connectForUpload(
        host: HostEntity,
        key: SshKey,
        keyPath: String,
    ): Result<SshSession> {
        if (key !is SshKey.Path) {
            return Result.failure(
                com.pocketshell.core.ssh.SshException("Share upload requires a persisted SSH key"),
            )
        }
        return acquireLeaseBackedSession(host = host, keyPath = keyPath)
    }

    private suspend fun acquireLeaseBackedSession(
        host: HostEntity,
        keyPath: String,
    ): Result<SshSession> {
        val target = host.toShareLeaseTarget(keyPath, pendingPassphrase.get())
        return sshLeaseManager.acquire(target).map { lease ->
            LeaseBackedShareSession(lease)
        }
    }

    /**
     * Build the lease target for the share connect.
     *
     * The [SshLeaseKey] is byte-for-byte identical to the one the live
     * tmux session uses (`TmuxSessionViewModel.toSshLeaseTarget`) — same
     * `credentialId` (`"$id:$keyPath"`) and `knownHostsId` — so when the
     * app already holds a warm lease for this host/key the share REUSES it
     * (the passphrase is intentionally NOT part of the lease key, so a
     * warm, already-unlocked lease is shared regardless of whether we have
     * a passphrase here). The [passphrase] only matters on the
     * fresh-connect fallback, when no reusable lease exists and the key is
     * passphrase-protected (issue #654).
     */
    private fun HostEntity.toShareLeaseTarget(
        keyPath: String,
        passphrase: CharArray?,
    ): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = "$id:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

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

    fun setShareFlowForeground(foreground: Boolean) {
        _shareFlowForeground.value = foreground
    }

    fun chooseSaveAsFile() {
        _dispatchChoice.value = TextDispatchChoice.SaveAsFile
    }

    fun chooseTextPasteIfAvailable() {
        if (hasAttachedSession.value) {
            _dispatchChoice.value = TextDispatchChoice.PasteIntoSession
        }
    }

    /**
     * Issue #473 / #507: user tapped a host in the picker. Instead of
     * uploading straight to the host inbox, surface the per-host target
     * chooser so the file can land in either `~/inbox/pocketshell/`
     * (default), one of the user's current/open session projects, or a
     * top-level watched root's `.inbox/`.
     *
     * Resolves the target options off the main thread:
     *
     *  - Issue #507: the current/open sessions' PROJECTS — each live
     *    `tmux -CC` session's active-pane cwd — so the user can drop a
     *    file into the project they are actually working in (e.g.
     *    `~/git/pocketshell/.inbox/`), not just a top-level watched
     *    root. The focused session leads. Resolved by enumerating the
     *    live client's sessions via `list-panes -a` (one round-trip).
     *  - the host's known projects / watched roots from [ProjectRootDao]
     *    (kept as options — a user may still want a root's `.inbox/`).
     *
     * The two lists are de-duplicated on path: a session project that is
     * also a watched root is listed only once, in the prominent
     * session-project slot.
     */
    fun selectTargetHost(host: HostEntity) {
        if (_uploadState.value is UploadState.Running) return
        _targetSelection.value = TargetSelection(host = host, loading = true)
        viewModelScope.launch {
            // Issue #560: enumerate the host's active tmux sessions ONCE so
            // they can be offered as destinations above the inbox folders.
            // The same `list-panes -a` round-trip yields each session's
            // active-pane cwd, which #507 already turns into the
            // session-project targets below; we additionally surface the
            // session itself (name + cwd) as a "share into this session"
            // destination (#560).
            val activeSessions = resolveActiveSessions(host)
            // Issue #507: the session-PROJECT targets — each open session's
            // active-pane cwd, focused first, de-duplicated by path and with
            // a path-derived label. Sessions whose cwd tmux did not report as
            // an absolute path are excluded from the project list (but still
            // appear as #560 active-session destinations, which key on the
            // session name rather than the cwd).
            val sessionProjects = buildList {
                val seenPaths = linkedSetOf<String>()
                for (session in activeSessions) {
                    val path = session.cwd.takeIf { it.startsWith("/") } ?: continue
                    if (!seenPaths.add(path)) continue
                    add(ProjectTarget(path = path, label = defaultLabelForPath(path)))
                }
            }
            val sessionPaths = sessionProjects.map { it.path }.toSet()
            val knownProjects = runCatching {
                projectRootDao.getByHostId(host.id).first()
            }.getOrDefault(emptyList())
                .map { root ->
                    ProjectTarget(
                        path = root.path,
                        label = com.pocketshell.app.projects.WatchedFoldersViewModel
                            .stripOrderPrefix(root.label)
                            .ifBlank { defaultLabelForPath(root.path) },
                    )
                }
                // Issue #507: a watched root that is also a live session
                // project is already surfaced (prominently) above, so
                // drop it here to avoid a double-listing.
                .filter { it.path !in sessionPaths }
            _targetSelection.value = TargetSelection(
                host = host,
                loading = false,
                activeSessions = activeSessions,
                sessionProjects = sessionProjects,
                knownProjects = knownProjects,
            )
        }
    }

    /** Dismiss the target chooser and return to the host list. */
    fun clearTargetSelection() {
        _targetSelection.value = null
    }

    /**
     * Issue #560: stage the staged share item(s) INTO an active session,
     * then open that session with the file pre-loaded as a composer
     * attachment chip.
     *
     * The upload reuses the exact #544 [PromptAttachmentStager] mechanic:
     * it opens a short-lived [SshSession] to the host (the live `tmux -CC`
     * control channel is not a file-transfer channel), uploads each staged
     * file to `~/.pocketshell/attachments/host-<id>-<session>/…` — the same
     * scope key the in-session Attach button uses
     * ([com.pocketshell.app.tmux.TmuxSessionViewModel.stagePromptAttachments])
     * — and emits a [SessionLaunch] carrying the session destination + the
     * resulting remote path(s). The activity then launches into that tmux
     * session with the composer focused and the chip(s) already present.
     *
     * Works for ANY active session (agent or plain shell); there is no
     * agent-detection gate. Surfaces failure through the existing
     * [UploadState] machine so the picker UI does not need a parallel
     * result surface.
     */
    fun stageIntoSession(host: HostEntity, session: ActiveSessionTarget) {
        val payload = _items.value
        if (payload.isEmpty()) return
        if (_uploadState.value is UploadState.Running) return
        lastShareAction = ShareRetryAction.StageIntoSession(host, session)
        DiagnosticEvents.record(
            "action",
            "share_stage_into_session_start",
            "hostId" to host.id,
            "itemCount" to payload.size,
            "uriCount" to payload.count { it is ShareableItem.UriItem },
        )
        val entry = activeTmuxClients.clients.value[host.id]
        if (entry == null) {
            DiagnosticEvents.record(
                "action",
                "share_stage_into_session_result",
                "status" to "failure",
                "hostId" to host.id,
                "cause" to "NoActiveSession",
            )
            val message = "No active session on ${host.name} — save to inbox instead"
            android.util.Log.w(LOG_TAG, "share into session aborted: $message")
            _uploadState.value = UploadState.Failed(host.name, message)
            notifyShareFailure(host.name, message)
            return
        }
        _targetSelection.value = null
        _uploadState.value = UploadState.Running(host.name)
        viewModelScope.launch {
            val keyEntity = sshKeyDao.getById(host.keyId)
            if (keyEntity == null) {
                DiagnosticEvents.record(
                    "action",
                    "share_stage_into_session_result",
                    "status" to "failure",
                    "hostId" to host.id,
                    "cause" to "MissingSshKey",
                )
                val message = "No SSH key for host ${host.name}"
                android.util.Log.w(LOG_TAG, "share into session aborted: $message")
                _uploadState.value = UploadState.Failed(host.name, message)
                notifyShareFailure(host.name, message)
                return@launch
            }

            // Issue #560: scope key MUST match the in-session Attach path
            // (TmuxSessionViewModel) so the chip lands in the exact same
            // remote directory the user would see attaching from inside the
            // session — the #544 mechanic, no parallel upload location.
            val scopeKey = "host-${host.id}-${session.sessionName}"
            val result = stageIntoSessionBounded(
                host = host,
                keyEntity = keyEntity,
                scopeKey = scopeKey,
                payload = payload,
                sessionName = session.sessionName,
            )

            result.fold(
                onSuccess = { remotePaths ->
                    if (remotePaths.isEmpty()) {
                        DiagnosticEvents.record(
                            "action",
                            "share_stage_into_session_result",
                            "status" to "failure",
                            "hostId" to host.id,
                            "cause" to "NoFilesStaged",
                        )
                        val message = "No files were staged into ${session.sessionName}"
                        _uploadState.value = UploadState.Failed(host.name, message)
                        notifyShareFailure(host.name, message)
                        return@fold
                    }
                    android.util.Log.i(
                        LOG_TAG,
                        "staged ${remotePaths.size} file(s) into session ${session.sessionName}",
                    )
                    DiagnosticEvents.record(
                        "action",
                        "share_stage_into_session_result",
                        "status" to "success",
                        "hostId" to host.id,
                        "stagedCount" to remotePaths.size,
                    )
                    _sessionLaunch.trySend(
                        SessionLaunch(
                            hostId = host.id,
                            hostName = host.name,
                            hostname = host.hostname,
                            port = host.port,
                            username = host.username,
                            keyPath = keyEntity.privateKeyPath,
                            sessionName = session.sessionName,
                            attachmentPaths = remotePaths,
                        ),
                    )
                    // Leave the FSM Running so the picker shows the spinner
                    // until the activity finishes; the launch event is what
                    // ends this surface.
                },
                onFailure = { error ->
                    DiagnosticEvents.record(
                        "action",
                        "share_stage_into_session_result",
                        "status" to "failure",
                        "hostId" to host.id,
                        "cause" to error.javaClass.simpleName,
                    )
                    val message = error.message ?: "Could not stage into ${session.sessionName}"
                    android.util.Log.w(LOG_TAG, "share into session staging failed: $message", error)
                    _uploadState.value = UploadState.Failed(host.name, message)
                    notifyShareFailure(host.name, message)
                },
            )
        }
    }

    private suspend fun stageIntoSessionBounded(
        host: HostEntity,
        keyEntity: SshKeyEntity,
        scopeKey: String,
        payload: List<ShareableItem>,
        sessionName: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val openedSession = AtomicReference<SshSession?>()
        val deferred = async {
            val sshSession = connectForStaging(host, keyEntity).getOrThrow()
            openedSession.set(sshSession)
            sshSession.use { live ->
                val stagedInput = materializeSessionStagingUris(payload)
                try {
                    PromptAttachmentStager(
                        resolver = applicationContext.contentResolver,
                        cacheDir = applicationContext.cacheDir,
                    ).stage(live, scopeKey, stagedInput.uris).getOrThrow()
                } finally {
                    stagedInput.deleteTempFiles()
                }
            }
        }

        try {
            val remotePaths = withTimeoutOrNull(stageIntoSessionTimeoutMs) {
                deferred.await()
            }
            if (remotePaths != null) {
                Result.success(remotePaths)
            } else {
                android.util.Log.w(
                    LOG_TAG,
                    "share into session staging timed out after ${stageIntoSessionTimeoutMs}ms; " +
                        "closing staging SSH session if it opened",
                )
                deferred.cancel()
                withContext(NonCancellable) {
                    runCatching { openedSession.getAndSet(null)?.close() }
                }
                Result.failure(ShareStageIntoSessionTimeoutException(sessionName, stageIntoSessionTimeoutMs))
            }
        } catch (cancelled: CancellationException) {
            deferred.cancel()
            withContext(NonCancellable) {
                runCatching { openedSession.getAndSet(null)?.close() }
            }
            throw cancelled
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Issue #507: enumerate the current/open sessions' projects for
     * [host] (the "share to what I'm working on" targets). Returns an
     * empty list when the host has no registered `tmux -CC` client or the
     * enumeration fails / yields no usable path.
     *
     * Each open tmux session contributes its active pane's
     * `pane_current_path` (the project the user is working in for that
     * session). The currently-focused session's project leads so the
     * most likely destination is at the top; remaining session projects
     * follow in session order. Paths are de-duplicated so two sessions
     * sharing a cwd surface a single destination.
     *
     * Implementation: a single `list-panes -a` control-mode round-trip
     * (reusing [SshFolderListGateway.CONTROL_LIST_PANES_COMMAND] /
     * [FolderListGateway.parseSessionWindowRows]) yields every window's
     * active-pane cwd plus `#{window_active}` / `#{pane_active}`, from
     * which the per-session active pane and the globally focused pane are
     * derived without a second query.
     */
    private suspend fun resolveActiveSessions(host: HostEntity): List<ActiveSessionTarget> {
        val entry = activeTmuxClients.clients.value[host.id] ?: return emptyList()
        val windows = runCatching {
            val response =
                entry.client.sendCommand(SshFolderListGateway.CONTROL_LIST_PANES_COMMAND)
            if (response.isError) return@runCatching emptyList()
            SshFolderListGateway.parseSessionWindowRows(response.output.joinToString("\n"))
        }.getOrDefault(emptyList())
        if (windows.isEmpty()) return emptyList()

        // The focused session is the one whose active window is also the
        // active pane row tmux reports; `parseSessionWindowRows` already
        // filtered to active panes, so `active == true` marks the
        // focused window. Sort that session first.
        val activePanes = SshFolderListGateway.activePaneRowsBySession(windows)
        val focusedSession = windows.firstOrNull { it.active }?.sessionName

        val ordered = buildList {
            focusedSession?.let { add(it) }
            // Preserve session enumeration order for the rest.
            windows.map { it.sessionName }.distinct().forEach { name ->
                if (name != focusedSession) add(name)
            }
        }

        val sessions = mutableListOf<ActiveSessionTarget>()
        for (sessionName in ordered) {
            if (sessionName.isBlank()) continue
            val cwd = activePanes[sessionName]?.cwd?.trim().orEmpty()
            val normalised = cwd.takeIf { it.startsWith("/") }?.trimEnd('/')?.ifBlank { "/" }.orEmpty()
            sessions += ActiveSessionTarget(
                sessionName = sessionName,
                cwd = normalised,
                label = sessionName,
                focused = sessionName == focusedSession,
            )
        }
        return sessions
    }

    /**
     * Issue #473: upload every staged item to [host], routing to either
     * the host inbox or a specific project's `.inbox/` per [target].
     *
     * Issue #258: a multi-file share stages more than one item; the loop
     * uploads each in turn and aggregates the outcome so partial
     * failures can report "3 of 4 uploaded" / list the names that
     * failed rather than silently dropping files. Fully successful file
     * shares finish quietly.
     */
    fun startUpload(host: HostEntity, target: ShareTarget = ShareTarget.HostInbox) {
        val payload = _items.value
        if (payload.isEmpty()) return
        if (_uploadState.value is UploadState.Running) return
        lastShareAction = ShareRetryAction.Upload(host, target)
        val targetName = target.diagnosticName()
        DiagnosticEvents.record(
            "action",
            "share_upload_start",
            "hostId" to host.id,
            "itemCount" to payload.size,
            "target" to targetName,
        )
        _targetSelection.value = null
        _uploadState.value = UploadState.Running(host.name)
        viewModelScope.launch {
            val keyEntity = sshKeyDao.getById(host.keyId)
            if (keyEntity == null) {
                DiagnosticEvents.record(
                    "action",
                    "share_upload_result",
                    "status" to "failure",
                    "hostId" to host.id,
                    "target" to targetName,
                    "totalCount" to payload.size,
                    "successCount" to 0,
                    "cause" to "MissingSshKey",
                )
                val message = "No SSH key for host ${host.name}"
                android.util.Log.w(LOG_TAG, "share upload aborted: $message")
                _uploadState.value =
                    UploadState.Failed(host.name, message, totalCount = payload.size)
                notifyShareFailure(host.name, message)
                return@launch
            }

            val succeededPaths = mutableListOf<String>()
            val failedNames = mutableListOf<String>()
            var lastError: String? = null

            for ((index, item) in payload.withIndex()) {
                val itemLabel = item.displayName?.takeIf { it.isNotBlank() } ?: "file"
                val result = uploader.upload(host, keyEntity, item, target)
                val failure = result.exceptionOrNull()
                // Issue #654: a fresh share connect (when no warm app lease
                // is reusable — e.g. the live session's lease expired while
                // the user was in the other app past the background grace
                // window) can only authenticate a passphrase-protected key
                // if we have its passphrase. Rather than surfacing a bare
                // "Authentication failed", prompt for the same unlock the
                // main app uses and re-run the upload. We only divert on the
                // FIRST item before anything uploaded — once a file has
                // landed the credentials are clearly fine and any later
                // failure is a genuine per-file error.
                if (failure != null &&
                    index == 0 &&
                    succeededPaths.isEmpty() &&
                    keyEntity.hasPassphrase &&
                    pendingPassphrase.get() == null &&
                    isAuthFailure(failure)
                ) {
                    DiagnosticEvents.record(
                        "action",
                        "share_upload_result",
                        "status" to "needs_passphrase",
                        "hostId" to host.id,
                        "target" to targetName,
                    )
                    android.util.Log.i(
                        LOG_TAG,
                        "share upload needs passphrase for host ${host.name}; prompting",
                    )
                    _uploadState.value = UploadState.NeedsPassphrase(
                        hostName = host.name,
                        keyName = keyEntity.name,
                    )
                    return@launch
                }
                result.fold(
                    onSuccess = { remotePath ->
                        if (remotePath.isBlank()) {
                            val message = "Upload completed but returned no remote path"
                            android.util.Log.w(
                                LOG_TAG,
                                "share upload produced no returned path for $itemLabel",
                            )
                            failedNames += itemLabel
                            lastError = message
                        } else {
                            android.util.Log.i(LOG_TAG, "share upload succeeded: $remotePath")
                            succeededPaths += remotePath
                        }
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
                targetName = targetName,
            )
        }
    }

    /**
     * Map the accumulated per-file results into a single terminal
     * [UploadState]. Failed outcomes also drive the matching failure
     * notification; successful uploads stay quiet.
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
        targetName: String,
    ) {
        val successCount = succeededPaths.size
        when {
            failedNames.isEmpty() -> {
                if (succeededPaths.isEmpty()) {
                    DiagnosticEvents.record(
                        "action",
                        "share_upload_result",
                        "status" to "failure",
                        "hostId" to host.id,
                        "target" to targetName,
                        "totalCount" to total,
                        "successCount" to 0,
                        "cause" to "MissingReturnedPaths",
                    )
                    val message = "Upload completed but returned no remote paths"
                    _uploadState.value = UploadState.Failed(
                        hostName = host.name,
                        message = message,
                        totalCount = total,
                    )
                    notifyShareFailure(host.name, message)
                    return
                }
                DiagnosticEvents.record(
                    "action",
                    "share_upload_result",
                    "status" to "success",
                    "hostId" to host.id,
                    "target" to targetName,
                    "totalCount" to total,
                    "successCount" to successCount,
                )
                val copyText = succeededPaths.joinToString("\n")
                val detail = if (total > 1) {
                    "$successCount files uploaded:\n$copyText"
                } else {
                    succeededPaths.firstOrNull().orEmpty()
                }
                _uploadState.value = UploadState.Success(
                    hostName = host.name,
                    remotePath = detail,
                    copyText = copyText,
                    successCount = successCount,
                    totalCount = total,
                )
            }
            successCount == 0 -> {
                DiagnosticEvents.record(
                    "action",
                    "share_upload_result",
                    "status" to "failure",
                    "hostId" to host.id,
                    "target" to targetName,
                    "totalCount" to total,
                    "successCount" to 0,
                )
                val message = lastError ?: "Upload failed"
                _uploadState.value = UploadState.Failed(
                    hostName = host.name,
                    message = message,
                    totalCount = total,
                    successCount = 0,
                    failedNames = failedNames,
                )
                notifyShareFailure(host.name, message)
            }
            else -> {
                DiagnosticEvents.record(
                    "action",
                    "share_upload_result",
                    "status" to "partial_failure",
                    "hostId" to host.id,
                    "target" to targetName,
                    "totalCount" to total,
                    "successCount" to successCount,
                    "failureCount" to failedNames.size,
                )
                val message = buildString {
                    append("$successCount of $total uploaded. ")
                    append("Failed: ")
                    append(failedNames.joinToString(", "))
                    lastError?.let { append(" ($it)") }
                    append("\nUploaded:\n")
                    append(succeededPaths.joinToString("\n"))
                }
                android.util.Log.w(LOG_TAG, "partial share upload: $message")
                _uploadState.value = UploadState.Failed(
                    hostName = host.name,
                    message = message,
                    totalCount = total,
                    successCount = successCount,
                    failedNames = failedNames,
                    successfulPaths = succeededPaths,
                )
                notifyShareFailure(host.name, message)
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
     * pasted text.
     */
    fun pasteIntoSession(host: HostEntity) {
        val staged = _items.value.firstOrNull()
        if (staged !is ShareableItem.TextItem) {
            DiagnosticEvents.record(
                "action",
                "share_paste_into_session_result",
                "status" to "failure",
                "hostId" to host.id,
                "cause" to "NoTextItem",
            )
            val message = "Nothing to paste"
            _uploadState.value = UploadState.Failed(host.name, message)
            return
        }
        if (_uploadState.value is UploadState.Running) return
        lastShareAction = ShareRetryAction.PasteIntoSession(host)
        DiagnosticEvents.record(
            "action",
            "share_paste_into_session_start",
            "hostId" to host.id,
            "textBytes" to staged.text.toByteArray(Charsets.UTF_8).size,
        )
        val entry = activeTmuxClients.clients.value[host.id]
        if (entry == null) {
            // Defensive — the picker should not surface this host as
            // tappable for paste, but if a race tore the client down
            // between rendering and tapping we degrade to a clear
            // message that points the user at the file-save fallback.
            DiagnosticEvents.record(
                "action",
                "share_paste_into_session_result",
                "status" to "failure",
                "hostId" to host.id,
                "cause" to "NoActiveSession",
            )
            val message = "No active session on ${host.name} — save to inbox instead"
            android.util.Log.w(LOG_TAG, "share paste aborted: $message")
            _uploadState.value = UploadState.Failed(host.name, message)
            notifyShareFailure(host.name, message)
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
                    DiagnosticEvents.record(
                        "action",
                        "share_paste_into_session_result",
                        "status" to "success",
                        "hostId" to host.id,
                        "textBytes" to staged.text.toByteArray(Charsets.UTF_8).size,
                    )
                    android.util.Log.i(
                        LOG_TAG,
                        "share paste succeeded on ${host.name}: ${preview.length} chars",
                    )
                    _uploadState.value = UploadState.Success(host.name, preview)
                },
                onFailure = { error ->
                    DiagnosticEvents.record(
                        "action",
                        "share_paste_into_session_result",
                        "status" to "failure",
                        "hostId" to host.id,
                        "cause" to error.javaClass.simpleName,
                    )
                    val message = error.message ?: "Paste failed"
                    android.util.Log.w(LOG_TAG, "share paste failed: $message", error)
                    _uploadState.value = UploadState.Failed(host.name, message)
                    notifyShareFailure(host.name, message)
                },
            )
        }
    }

    private fun notifyShareFailure(hostName: String, message: String) {
        if (_shareFlowForeground.value) return
        ShareUploadNotifications.showFailure(applicationContext, hostName, message)
    }

    private data class SessionStagingInput(
        val uris: List<Uri>,
        val tempFiles: List<File>,
    ) {
        fun deleteTempFiles() {
            tempFiles.forEach { it.delete() }
        }
    }

    private fun materializeSessionStagingUris(payload: List<ShareableItem>): SessionStagingInput {
        val uris = mutableListOf<Uri>()
        val tempFiles = mutableListOf<File>()
        for (item in payload) {
            when (item) {
                is ShareableItem.UriItem -> uris += item.uri
                is ShareableItem.FileItem -> uris += Uri.fromFile(item.file)
                is ShareableItem.TextItem -> {
                    val file = materializeTextShare(item)
                    tempFiles += file
                    uris += Uri.fromFile(file)
                }
            }
        }
        return SessionStagingInput(uris = uris, tempFiles = tempFiles)
    }

    private fun materializeTextShare(item: ShareableItem.TextItem): File {
        val dir = File(applicationContext.cacheDir, "share-session-text").also { it.mkdirs() }
        val sanitised = FilenameSanitiser.sanitise(
            item.displayName,
            defaultExtension = item.fallbackExtension ?: "txt",
        ).render()
        val file = File(dir, "${System.nanoTime()}-$sanitised")
        file.writeText(item.text, Charsets.UTF_8)
        return file
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
        if (BracketedPaste.containsLineBreak(bytes) || bytes.size > BracketedPaste.BODY_CHUNK_BYTES) {
            for (hex in BracketedPaste.hexChunks(bytes)) {
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
        clearPendingPassphrase()
        _uploadState.value = UploadState.Idle
    }

    /**
     * Issue #664: deterministic test seam. Pushes [_uploadState] to an
     * arbitrary [UploadState] so an instrumented Compose UI test can render
     * the production surface for that state (e.g. drive
     * [UploadState.NeedsPassphrase] to verify the [HostPickerScreen]
     * `PassphraseDialog` renders) without needing a passphrase-protected key
     * and a flaky live SSH auth round-trip. The JVM unit tests already cover
     * the real state transition (`ShareViewModelTest`); this only exercises
     * the on-device rendering of the resulting surface.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setUploadStateForTest(state: UploadState) {
        _uploadState.value = state
    }

    fun chooseDifferentShareTarget() {
        clearPendingPassphrase()
        _targetSelection.value = null
        _uploadState.value = UploadState.Idle
    }

    fun retryLastShareAction() {
        val action = lastShareAction ?: return
        _uploadState.value = UploadState.Idle
        when (action) {
            is ShareRetryAction.Upload -> startUpload(action.host, action.target)
            is ShareRetryAction.StageIntoSession -> stageIntoSession(action.host, action.session)
            is ShareRetryAction.PasteIntoSession -> pasteIntoSession(action.host)
        }
    }

    /**
     * Issue #654: the user typed the passphrase in the
     * [UploadState.NeedsPassphrase] prompt. Stash it for this share, then
     * re-run the last share action so the fresh connect can unlock the
     * key. The same retry path covers a wrong passphrase: the upload fails
     * auth again, but [pendingPassphrase] is now non-null so we surface a
     * normal failure ("Authentication failed") rather than re-prompting in
     * a loop — the user can re-enter via the retry/different-host buttons.
     */
    fun submitPassphrase(passphrase: CharArray) {
        if (passphrase.isEmpty()) return
        pendingPassphrase.getAndSet(passphrase.copyOf())?.fill(' ')
        retryLastShareAction()
    }

    /** Issue #654: the user dismissed the passphrase prompt without typing one. */
    fun cancelPassphrasePrompt() {
        clearUploadState()
    }

    private fun clearPendingPassphrase() {
        pendingPassphrase.getAndSet(null)?.fill(' ')
    }

    /**
     * Issue #654: does this throwable indicate the SSH handshake failed
     * because the credentials were rejected (vs. a transport/IO error)?
     * Mirrors the keyword match [ShareUploader.errorMessage] uses to map
     * the failure to "Authentication failed", so the passphrase prompt
     * triggers on exactly the cases the user would otherwise see as a bare
     * auth failure.
     */
    override fun onCleared() {
        // Issue #654: never let an entered passphrase outlive the share.
        clearPendingPassphrase()
        super.onCleared()
    }

    private fun isAuthFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            val text = (cause.message ?: cause.javaClass.simpleName).lowercase(java.util.Locale.ROOT)
            if (text.contains("auth")) return true
            cause = cause.cause
        }
        return false
    }

    private companion object {
        /** "Short text" threshold from the issue spec. */
        const val TEXT_PASTE_BUDGET_BYTES: Int = 8 * 1024
        const val LOG_TAG: String = "PocketShellShare"
        const val STAGE_INTO_SESSION_TIMEOUT_MS: Long = 90_000L

        /** Truncation cap for the paste preview surfaced in UploadState.Success. */
        const val PASTE_PREVIEW_LENGTH: Int = 80

        /**
         * Issue #473: derive a short, non-blank label from a project
         * path — the trailing path segment
         * (`/home/alexey/git/pocketshell` → `pocketshell`), falling back
         * to the trimmed path itself when there is no usable tail.
         */
        fun defaultLabelForPath(path: String): String {
            val stripped = path.trim().trimEnd('/')
            if (stripped.isEmpty()) return path.trim().ifBlank { "project" }
            return stripped.substringAfterLast('/').ifBlank { stripped }
        }

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
 * renderer treats both paths uniformly.
 */
internal sealed interface UploadState {
    /** Nothing in flight; show the host picker (or the empty state). */
    data object Idle : UploadState

    /** SCP upload (or send-keys paste) is running. Surface a spinner + the host name. */
    data class Running(val hostName: String) : UploadState

    /**
     * Issue #654: the fresh share connect could not authenticate because
     * the host's SSH key is passphrase-protected and there is no warm,
     * already-unlocked app lease to reuse (the share activity is launched
     * cold from the system share sheet). Show the same passphrase unlock
     * the main app uses; submitting it re-runs the upload.
     */
    data class NeedsPassphrase(
        val hostName: String,
        val keyName: String,
    ) : UploadState

    /**
     * Operation succeeded.
     *
     * For file uploads, [remotePath] is the display detail and [copyText]
     * is the exact clipboard payload. Single-file uploads use the same
     * absolute remote path (e.g.
     * `$HOME/inbox/pocketshell/<filename>`). For a multi-file share
     * (issue #258), [remotePath] includes a count header for readability
     * while [copyText] is just the newline-joined uploaded paths;
     * [successCount]/[totalCount] carry the counts for tests and any
     * non-visual follow-up handling.
     *
     * For send-keys paste (issue #193), [remotePath] holds a short
     * preview of the pasted text — the field name is preserved for
     * call-site compatibility but the value is a UI-facing string.
     */
    data class Success(
        val hostName: String,
        val remotePath: String,
        val copyText: String = remotePath,
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
        val successfulPaths: List<String> = emptyList(),
        val copyText: String = successfulPaths.joinToString("\n"),
    ) : UploadState
}

private sealed interface ShareRetryAction {
    data class Upload(val host: HostEntity, val target: ShareTarget) : ShareRetryAction
    data class StageIntoSession(
        val host: HostEntity,
        val session: ActiveSessionTarget,
    ) : ShareRetryAction
    data class PasteIntoSession(val host: HostEntity) : ShareRetryAction
}

private class LeaseBackedShareSession(
    private val lease: SshLease,
) : SshSession {
    private val session: SshSession get() = lease.session

    override val isConnected: Boolean
        get() = session.isConnected

    override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult =
        session.exec(command)

    override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job =
        session.tail(path, onLine)

    override fun tail(
        path: String,
        fromLineExclusive: Long,
        onLine: (String) -> Unit,
    ): kotlinx.coroutines.Job =
        session.tail(path, fromLineExclusive, onLine)

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): com.pocketshell.core.ssh.SshPortForward =
        session.openLocalPortForward(remoteHost, remotePort, localPort)

    override fun startShell(): com.pocketshell.core.ssh.SshShell =
        session.startShell()

    override suspend fun uploadFile(file: File, remotePath: String): String =
        session.uploadFile(file, remotePath)

    override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray =
        session.downloadFile(remotePath, maxBytes)

    override suspend fun uploadStream(
        input: java.io.InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String =
        session.uploadStream(input, length, name, remotePath)

    override suspend fun listDirectory(
        remotePath: String,
        maxEntries: Int,
    ): com.pocketshell.core.ssh.RemoteListing =
        session.listDirectory(remotePath, maxEntries)

    override fun close() {
        kotlinx.coroutines.runBlocking {
            lease.release()
        }
    }
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

/**
 * Issue #473: a project on the chosen host that a shared file can land
 * in (under `<path>/.inbox/`).
 *
 * @property path the project's remote path as known to the app — a
 *   watched-root path, a recent folder, or a live session's
 *   `pane_current_path`. May be absolute or `~`-relative; the uploader
 *   resolves it to an absolute path before creating `.inbox/`.
 * @property label the user-visible folder name (trailing path segment or
 *   the watched-root label).
 */
internal data class ProjectTarget(
    val path: String,
    val label: String,
)

private fun ShareTarget.diagnosticName(): String = when (this) {
    ShareTarget.HostInbox -> "host_inbox"
    is ShareTarget.Project -> "project"
}

/**
 * Issue #473: the per-host target chooser state. Surfaced after the user
 * taps a host in the share picker so they can route the file to either
 * the host inbox or a specific project's `.inbox/`.
 *
 * @property host the host the file will land on.
 * @property loading true while the session-project list + known-project
 *   list are still resolving (a `list-panes -a` round-trip to the live
 *   client + a DAO read).
 * @property sessionProjects the current/open sessions' projects (issue
 *   #507) — each live tmux session's active-pane cwd, focused session
 *   first — offered as prominent one-tap quick targets at the top of the
 *   chooser. Empty when the host has no attached session.
 * @property knownProjects the host's known projects (top-level watched
 *   roots / recent folders) for the inactive case. De-duplicated against
 *   [sessionProjects] so a root that is also an open-session project is
 *   not listed twice.
 */
internal data class TargetSelection(
    val host: HostEntity,
    val loading: Boolean = false,
    val activeSessions: List<ActiveSessionTarget> = emptyList(),
    val sessionProjects: List<ProjectTarget> = emptyList(),
    val knownProjects: List<ProjectTarget> = emptyList(),
)

/**
 * Issue #560: an active tmux session on the chosen host that a shared
 * file can be staged into. Picking one uploads the file to the session's
 * `.pocketshell/attachments` scope (the #544 mechanic) and opens the
 * session with the file pre-loaded as a composer attachment chip.
 *
 * @property sessionName the tmux session name — the navigation key and
 *   the attachment scope segment (`host-<id>-<sessionName>`).
 * @property cwd the session's active-pane `pane_current_path`, or empty
 *   when tmux did not report a usable absolute path. Display-only here;
 *   the attachment scope keys on the session name, not the cwd.
 * @property label the user-visible session label (the session name).
 * @property focused true for the host's currently-focused session, which
 *   the picker lists first.
 */
internal data class ActiveSessionTarget(
    val sessionName: String,
    val cwd: String,
    val label: String,
    val focused: Boolean = false,
)

private class ShareStageIntoSessionTimeoutException(
    sessionName: String,
    timeoutMs: Long,
) : Exception(
    "Timed out after ${timeoutMs / 1_000}s staging attachments into $sessionName. " +
        "Check the connection and try again, or save to inbox.",
)

/**
 * Issue #560: one-shot navigation payload emitted after a shared file is
 * staged into an active session. [ShareActivity] consumes it to launch
 * [com.pocketshell.app.MainActivity] into the tmux session with the staged
 * remote path pre-loaded as a composer attachment chip.
 *
 * Carries the resolved SSH connection parameters (sourced from the
 * [HostEntity] + its key) so the session destination can be rebuilt on the
 * MainActivity side without a second DB read.
 */
internal data class SessionLaunch(
    val hostId: Long,
    val hostName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val sessionName: String,
    val attachmentPaths: List<String>,
)
