package com.pocketshell.next.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.SftpChannel
import com.pocketshell.core.transport.SftpEntry
import com.pocketshell.core.transport.SftpFileTooLargeException
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.di.IoDispatcher
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A file transfer between the device and the host, as the banner renders it.
 *
 * Four distinct states rather than a nullable message, because "nothing is
 * happening", "bytes are moving", "it worked" and "it failed" need four
 * different treatments and conflating the last two into a string is how a
 * failure ends up painted as a success.
 */
sealed interface TransferState {
    data object Idle : TransferState

    /** [name] is the file, [uploading] which direction. */
    data class Running(
        val name: String,
        val uploading: Boolean,
        val source: String? = null,
        val destination: String? = null,
        val bytesTransferred: Long = 0L,
        val totalBytes: Long? = null,
        val id: Long? = null,
    ) : TransferState

    data class Done(
        val message: String,
        val source: String? = null,
        val destination: String? = null,
        val id: Long? = null,
    ) : TransferState

    data class Failed(
        val message: String,
        val source: String? = null,
        val destination: String? = null,
        val id: Long? = null,
    ) : TransferState
}

/** The lifecycle state shown by the full Transfers surface. */
enum class FileTransferStatus {
    Running,
    Completed,
    Failed,
}

/**
 * A durable-in-this-screen record of one upload or download.
 *
 * The transport API is deliberately whole-file shaped, so an unknown remote
 * size is represented by a null [totalBytes] and the UI renders an indeterminate
 * transfer. Local uploads can report measured stream bytes as they are read.
 */
data class FileTransferRecord(
    val id: Long,
    val name: String,
    val uploading: Boolean,
    val source: String,
    val destination: String,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long? = null,
    val status: FileTransferStatus = FileTransferStatus.Running,
    val message: String? = null,
)

/** State for the create-folder sheet. A failed request keeps the user's name. */
data class CreateFolderUiState(
    val visible: Boolean = false,
    val name: String = "",
    val submitting: Boolean = false,
    val failure: String? = null,
)

/** State for creating one empty text file in the current remote folder. */
data class NewTextFileUiState(
    val visible: Boolean = false,
    val name: String = "",
    val submitting: Boolean = false,
    val failure: String? = null,
)

/** State for the rename sheet. A failed request keeps the user's name. */
data class RenameFileUiState(
    val entry: SftpEntry? = null,
    val name: String = "",
    val submitting: Boolean = false,
    val failure: String? = null,
) {
    val visible: Boolean get() = entry != null
}

/** State for the explicit destructive delete confirmation. */
data class DeleteFileUiState(
    val entry: SftpEntry? = null,
    val submitting: Boolean = false,
    val failure: String? = null,
) {
    val visible: Boolean get() = entry != null
}

/** Everything the file explorer renders. */
data class FileExplorerUiState(
    val hostId: Long = 0,
    /** Display name for the host context kept in file and transfer headers. */
    val hostName: String = "",
    /** The directory on screen. Blank until the first listing resolves it. */
    val path: String = "",
    /** Directories first, then files, each group by name — see [sortEntries]. */
    val entries: List<SftpEntry> = emptyList(),
    /** First load, nothing to paint yet. */
    val loading: Boolean = false,
    /** At least one listing has succeeded, so [entries] is a real answer. */
    val loaded: Boolean = false,
    /**
     * The listing failed. Kept separate from an empty [entries] for the same
     * reason the session tree does it: "this directory is empty" and "we could
     * not read this directory" must not render identically.
     */
    val failure: String? = null,
    val transfer: TransferState = TransferState.Idle,
    /** Records remain visible after a transfer completes or fails. */
    val transferRecords: List<FileTransferRecord> = emptyList(),
    /** The screen-level overflow sheet. */
    val toolsVisible: Boolean = false,
    /** The selected row's action sheet. */
    val actionEntry: SftpEntry? = null,
    val createFolder: CreateFolderUiState = CreateFolderUiState(),
    val newTextFile: NewTextFileUiState = NewTextFileUiState(),
    val renameFile: RenameFileUiState = RenameFileUiState(),
    val deleteFile: DeleteFileUiState = DeleteFileUiState(),
    /** The full transfer history surface is kept in this route's state. */
    val transfersVisible: Boolean = false,
    /** One-shot path emitted after a new empty file has been created. */
    val newFilePathToOpen: String? = null,
    /** A successful mutation message; unlike a failure it does not hide rows. */
    val operationMessage: String? = null,
) {
    val crumbs: List<RemotePath.Crumb>
        get() = if (path.isBlank()) emptyList() else RemotePath.crumbs(path)

    /** True when the screen should say "this folder is empty" rather than stay blank. */
    val isEmptyAndHealthy: Boolean
        get() = loaded && entries.isEmpty() && failure == null

    /** True while a transfer is in flight — the upload/download actions are disabled then. */
    val transferring: Boolean
        get() = transfer is TransferState.Running
}

/**
 * The remote file explorer for one host (rewrite task P-3a, journey J10).
 *
 * One screen = one directory listing over [SftpChannel.list]. There is no
 * listing cache, no generation counter and no reconcile pass: the old client
 * carried all three (plus a `requestGeneration` field threaded through every
 * callback) because its connection could be swapped underneath it mid-browse.
 * [ConnectionsRegistry] removed that problem — `getOrConnect` either hands back
 * the live connection or dials a fresh one — so a navigation is just "list the
 * new path and replace what is on screen".
 *
 * ## Where the connection comes from
 *
 * [ConnectionsRegistry.getOrConnect], the same call the session tree makes and
 * for the same reasons: it reuses the connection the connect gate opened, it
 * survives a process restore straight onto this route, and it re-dials a
 * connection that died while the screen was backgrounded. Host-key questions
 * are NOT answered here — [ConnectResult.NeedsTrust] surfaces as a failure
 * pointing the user at the host list, because trust is decided on the screen
 * that owns it.
 *
 * ## Transfers
 *
 * Upload and download are byte-array shaped because [SftpChannel] is
 * ([SftpChannel.write] takes a `ByteArray`, [SftpChannel.read] returns one), so
 * both are capped: [MAX_UPLOAD_BYTES] refuses an over-size device file before a
 * single byte is read, and [MAX_DOWNLOAD_BYTES] rides on the channel's own
 * [SftpFileTooLargeException]. A phone must not try to hold a DVD image in the
 * JVM heap, and failing with "that file is too big" beats an OOM.
 *
 * Scoped storage: neither direction touches the filesystem directly. The screen
 * hands in a `() -> InputStream?` (from `ContentResolver.openInputStream` on a
 * SAF-picked document) for upload, and a `(ByteArray) -> Unit` sink (writing to
 * `ContentResolver.openOutputStream` on an `ACTION_CREATE_DOCUMENT` result) for
 * download. The ViewModel therefore needs no storage permission at all, on any
 * API level.
 */
@HiltViewModel
class FileExplorerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val hostDao: HostDao,
    private val registry: ConnectionsRegistry,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "FileExplorerViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    /**
     * The route's optional start path. Absent means "open the account's home
     * directory", which is resolved from the host on first load — the SFTP
     * subsystem has no notion of `~`, so somebody has to ask.
     */
    private val startPath: String? = savedStateHandle.get<String>(Destination.ARG_PATH)

    private val _state = MutableStateFlow(FileExplorerUiState(hostId = hostId))
    val state: StateFlow<FileExplorerUiState> = _state.asStateFlow()

    private var listJob: Job? = null
    private var transferJob: Job? = null
    private var mutationJob: Job? = null
    private var nextTransferId: Long = 1L
    private val retryActions = mutableMapOf<Long, () -> Unit>()

    init {
        viewModelScope.launch {
            val displayName = withContext(dispatcher) {
                hostDao.getById(hostId)?.name
            } ?: "Host $hostId"
            _state.update { it.copy(hostName = displayName) }
        }
    }

    /**
     * Lists the directory currently on screen (or resolves the start directory
     * on the very first call). Safe to call from `ON_START` and from a pull
     * gesture; a call made while a listing is in flight is ignored.
     */
    fun refresh() {
        if (listJob?.isActive == true) return
        listJob = viewModelScope.launch { load(_state.value.path.takeIf { it.isNotBlank() }) }
    }

    /** Opens [entry] — a directory row's tap target. Files are the screen's job. */
    fun openDirectory(entry: SftpEntry) {
        if (!entry.isDirectory) return
        navigateTo(entry.path)
    }

    /** The "up" action. A no-op at the root, which is its own parent. */
    fun goUp() {
        val current = _state.value.path
        if (current.isBlank()) return
        val parent = RemotePath.parent(current)
        if (parent == current) return
        navigateTo(parent)
    }

    /** Opens an absolute path — a breadcrumb tap or a typed path. */
    fun navigateTo(path: String) {
        val target = RemotePath.normalize(path)
        listJob?.cancel()
        listJob = viewModelScope.launch { load(target) }
    }

    /** Opens the screen-level file tools sheet. */
    fun openTools() {
        _state.update { it.copy(toolsVisible = true, operationMessage = null) }
    }

    fun dismissTools() {
        _state.update { it.copy(toolsVisible = false) }
    }

    /** Opens actions for a single row; destructive actions are reached from here. */
    fun openActions(entry: SftpEntry) {
        _state.update { it.copy(toolsVisible = false, actionEntry = entry, operationMessage = null) }
    }

    fun dismissActions() {
        _state.update { it.copy(actionEntry = null) }
    }

    fun openTransfers() {
        _state.update { it.copy(toolsVisible = false, transfersVisible = true) }
    }

    fun dismissTransfers() {
        _state.update { it.copy(transfersVisible = false) }
    }

    /** Opens the create-folder sheet for the directory currently on screen. */
    fun openCreateFolder() {
        if (_state.value.path.isBlank()) return
        _state.update {
            it.copy(
                toolsVisible = false,
                createFolder = CreateFolderUiState(visible = true),
                operationMessage = null,
            )
        }
    }

    fun setCreateFolderName(name: String) {
        if (_state.value.createFolder.submitting) return
        _state.update {
            it.copy(createFolder = it.createFolder.copy(name = name, failure = null))
        }
    }

    fun dismissCreateFolder() {
        if (_state.value.createFolder.submitting) return
        _state.update { it.copy(createFolder = CreateFolderUiState()) }
    }

    /** Creates exactly one directory below the current file-browser location. */
    fun createFolder() {
        val current = _state.value
        val directory = current.path
        if (!current.createFolder.visible || current.createFolder.submitting || directory.isBlank()) return
        val name = current.createFolder.name.trim()
        val validation = validateRemoteLeafName(name, "Folder name")
        if (validation != null) {
            _state.update { it.copy(createFolder = it.createFolder.copy(failure = validation)) }
            return
        }
        val target = RemotePath.join(directory, name)
        _state.update {
            it.copy(createFolder = it.createFolder.copy(submitting = true, failure = null))
        }
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            runCatching { sftp().mkdir(target) }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            createFolder = CreateFolderUiState(),
                            operationMessage = "Created $name in $directory",
                        )
                    }
                    load(directory)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            createFolder = it.createFolder.copy(
                                submitting = false,
                                failure = "Could not create $name: ${message(error)}",
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Opens the create-file form for the directory currently on screen. */
    fun openNewTextFile() {
        if (_state.value.path.isBlank()) return
        _state.update {
            it.copy(
                toolsVisible = false,
                newTextFile = NewTextFileUiState(visible = true),
                operationMessage = null,
            )
        }
    }

    fun setNewTextFileName(name: String) {
        if (_state.value.newTextFile.submitting) return
        _state.update {
            it.copy(newTextFile = it.newTextFile.copy(name = name, failure = null))
        }
    }

    fun dismissNewTextFile() {
        if (_state.value.newTextFile.submitting) return
        _state.update { it.copy(newTextFile = NewTextFileUiState()) }
    }

    /** Creates an empty remote file and emits its path for the editor route. */
    fun createNewTextFile() {
        val current = _state.value
        val directory = current.path
        val form = current.newTextFile
        if (!form.visible || form.submitting || directory.isBlank()) return
        val name = form.name.trim()
        val validation = validateRemoteLeafName(name, "File name")
        if (validation != null) {
            _state.update { it.copy(newTextFile = it.newTextFile.copy(failure = validation)) }
            return
        }
        val target = RemotePath.join(directory, name)
        _state.update { it.copy(newTextFile = it.newTextFile.copy(submitting = true, failure = null)) }
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            runCatching {
                val channel = sftp()
                if (channel.stat(target) != null) {
                    throw java.io.IOException("already exists: $target")
                }
                channel.write(target, ByteArray(0))
            }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            newTextFile = NewTextFileUiState(),
                            newFilePathToOpen = target,
                            operationMessage = "Created $name in $directory",
                        )
                    }
                    load(directory)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            newTextFile = it.newTextFile.copy(
                                submitting = false,
                                failure = "Could not create $name: ${message(error)}",
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Consumes the one-shot editor handoff emitted by [createNewTextFile]. */
    fun consumeNewFilePath() {
        _state.update { it.copy(newFilePathToOpen = null) }
    }

    fun openRename(entry: SftpEntry) {
        _state.update {
            it.copy(
                actionEntry = null,
                renameFile = RenameFileUiState(entry = entry, name = entry.name),
                operationMessage = null,
            )
        }
    }

    fun setRenameName(name: String) {
        if (_state.value.renameFile.submitting) return
        _state.update { it.copy(renameFile = it.renameFile.copy(name = name, failure = null)) }
    }

    fun dismissRename() {
        if (_state.value.renameFile.submitting) return
        _state.update { it.copy(renameFile = RenameFileUiState()) }
    }

    /** Renames within the selected entry's parent directory. */
    fun renameFile() {
        val current = _state.value
        val rename = current.renameFile
        val entry = rename.entry ?: return
        if (rename.submitting) return
        val name = rename.name.trim()
        val validation = validateRemoteLeafName(name, "File name")
        if (validation != null) {
            _state.update { it.copy(renameFile = it.renameFile.copy(failure = validation)) }
            return
        }
        val target = RemotePath.join(RemotePath.parent(entry.path), name)
        if (target == entry.path) {
            _state.update { it.copy(renameFile = RenameFileUiState()) }
            return
        }
        _state.update { it.copy(renameFile = it.renameFile.copy(submitting = true, failure = null)) }
        val directory = current.path
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            runCatching {
                val channel = sftp()
                if (channel.stat(target) != null) {
                    throw java.io.IOException("already exists: $target")
                }
                channel.rename(entry.path, target)
            }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            renameFile = RenameFileUiState(),
                            operationMessage = "Renamed ${entry.name} to $name",
                        )
                    }
                    load(directory)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            renameFile = it.renameFile.copy(
                                submitting = false,
                                failure = "Could not rename ${entry.name}: ${message(error)}",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun requestDelete(entry: SftpEntry) {
        _state.update {
            it.copy(
                actionEntry = null,
                deleteFile = DeleteFileUiState(entry = entry),
                operationMessage = null,
            )
        }
    }

    fun dismissDelete() {
        if (_state.value.deleteFile.submitting) return
        _state.update { it.copy(deleteFile = DeleteFileUiState()) }
    }

    /** Deletes only after the screen has explicitly opened the confirmation. */
    fun confirmDelete() {
        val current = _state.value
        val delete = current.deleteFile
        val entry = delete.entry ?: return
        if (delete.submitting) return
        _state.update { it.copy(deleteFile = delete.copy(submitting = true, failure = null)) }
        val directory = current.path
        mutationJob?.cancel()
        mutationJob = viewModelScope.launch {
            runCatching { sftp().delete(entry.path) }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            deleteFile = DeleteFileUiState(),
                            operationMessage = "Deleted ${entry.name}",
                        )
                    }
                    load(directory)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            deleteFile = it.deleteFile.copy(
                                submitting = false,
                                failure = "Could not delete ${entry.name}: ${message(error)}",
                            ),
                        )
                    }
                },
            )
        }
    }

    fun dismissOperationMessage() {
        _state.update { it.copy(operationMessage = null) }
    }

    /**
     * Uploads a device document into the directory on screen.
     *
     * [openStream] is invoked at most once, on [dispatcher]; the caller resolved
     * it from the SAF picker's content URI. [declaredSize] is the provider's
     * reported size (-1 when it did not say) and is only used to refuse an
     * obviously over-size file before reading anything — the authoritative cap
     * is applied to the bytes actually delivered.
     */
    fun upload(displayName: String, declaredSize: Long, openStream: () -> InputStream?) {
        val directory = _state.value.path
        if (directory.isBlank() || _state.value.transferring) return
        uploadAt(directory, displayName, declaredSize, openStream)
    }

    private fun uploadAt(
        directory: String,
        displayName: String,
        declaredSize: Long,
        openStream: () -> InputStream?,
    ) {
        if (directory.isBlank() || _state.value.transferring) return
        val name = sanitizeUploadName(displayName)
        val target = RemotePath.join(directory, name)
        val id = nextTransferId++
        if (declaredSize > MAX_UPLOAD_BYTES) {
            val failure = tooBigToUpload(name)
            _state.update {
                it.copy(
                    transfer = TransferState.Failed(
                        failure,
                        source = "This device",
                        destination = target,
                        id = id,
                    ),
                    transferRecords = it.transferRecords + FileTransferRecord(
                        id = id,
                        name = name,
                        uploading = true,
                        source = "This device",
                        destination = target,
                        totalBytes = declaredSize.takeIf { size -> size >= 0L },
                        status = FileTransferStatus.Failed,
                        message = failure,
                    ),
                )
            }
            return
        }
        val totalBytes = declaredSize.takeIf { it >= 0L }
        _state.update {
            it.copy(
                transfer = TransferState.Running(
                    name = name,
                    uploading = true,
                    source = "This device",
                    destination = target,
                    totalBytes = totalBytes,
                    id = id,
                ),
                transferRecords = it.transferRecords + FileTransferRecord(
                    id = id,
                    name = name,
                    uploading = true,
                    source = "This device",
                    destination = target,
                    totalBytes = totalBytes,
                ),
            )
        }
        retryActions[id] = { uploadAt(directory, displayName, declaredSize, openStream) }
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            val outcome = runCatching {
                val bytes = withContext(dispatcher) {
                    val stream = openStream() ?: throw java.io.IOException("could not read $name")
                    stream.use {
                        readCapped(it, MAX_UPLOAD_BYTES, name) { read ->
                            updateTransferProgress(id, read)
                        }
                    }
                }
                sftp().write(target, bytes)
                bytes.size.toLong()
            }
            outcome.fold(
                onSuccess = { written ->
                    _state.update {
                        it.copy(
                            transfer = TransferState.Done(
                                "Uploaded $name (${formatSize(written)}) to $directory",
                                source = "This device",
                                destination = target,
                                id = id,
                            ),
                        )
                    }
                    finishTransfer(
                        id = id,
                        bytes = written,
                        message = "Uploaded $name (${formatSize(written)}) to $directory",
                    )
                    // The directory listing on screen predates the new file.
                    load(directory)
                },
                onFailure = { error ->
                    val failure = "Upload failed: ${message(error)}"
                    _state.update {
                        it.copy(
                            transfer = TransferState.Failed(
                                failure,
                                source = "This device",
                                destination = target,
                                id = id,
                            ),
                        )
                    }
                    failTransfer(id, failure)
                },
            )
        }
    }

    /**
     * Downloads [entry] and hands the bytes to [sink], which the screen wires to
     * the document the user just named through `ACTION_CREATE_DOCUMENT`.
     */
    fun download(entry: SftpEntry, sink: (ByteArray) -> Unit) {
        if (entry.isDirectory || _state.value.transferring) return
        val id = nextTransferId++
        val source = entry.path
        val destination = "This device"
        _state.update {
            it.copy(
                transfer = TransferState.Running(
                    name = entry.name,
                    uploading = false,
                    source = source,
                    destination = destination,
                    id = id,
                ),
                transferRecords = it.transferRecords + FileTransferRecord(
                    id = id,
                    name = entry.name,
                    uploading = false,
                    source = source,
                    destination = destination,
                ),
            )
        }
        retryActions[id] = { download(entry, sink) }
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            val outcome = runCatching {
                val bytes = sftp().read(entry.path, MAX_DOWNLOAD_BYTES)
                withContext(dispatcher) { sink(bytes) }
                bytes.size.toLong()
            }
            outcome.fold(
                onSuccess = { size ->
                    _state.update {
                        it.copy(
                            transfer = TransferState.Done(
                                "Saved ${entry.name} (${formatSize(size)}) to your device",
                                source = source,
                                destination = destination,
                                id = id,
                            ),
                        )
                    }
                    finishTransfer(
                        id = id,
                        bytes = size,
                        message = "Saved ${entry.name} (${formatSize(size)}) to your device",
                    )
                },
                onFailure = { error ->
                    val failure = "Download failed: ${message(error)}"
                    _state.update {
                        it.copy(
                            transfer = TransferState.Failed(
                                failure,
                                source = source,
                                destination = destination,
                                id = id,
                            ),
                        )
                    }
                    failTransfer(id, failure)
                },
            )
        }
    }

    /** Re-runs a failed transfer with the original source/destination closures. */
    fun retryTransfer(id: Long) {
        if (_state.value.transferring) return
        retryActions[id]?.invoke()
    }

    /** Clears the transfer banner once the user has read it. */
    fun dismissTransfer() {
        if (_state.value.transferring) return
        _state.update { it.copy(transfer = TransferState.Idle) }
    }

    // ------------------------------------------------------------- internals

    /**
     * Lists [path], or the resolved home directory when [path] is null (first
     * load with no route argument).
     */
    private suspend fun load(path: String?) {
        _state.update { it.copy(loading = !it.loaded || it.path != (path ?: it.path), failure = null) }
        val connection = when (val result = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> result.connection
            is ConnectResult.NeedsTrust -> return fail(
                "This host's key still needs to be confirmed. Open it from the host " +
                    "list to review the key.",
            )

            is ConnectResult.Failed -> return fail(result.message)
        }
        val target = path ?: resolveStartDirectory(connection)
        runCatching { connection.sftp().list(target) }.fold(
            onSuccess = { entries ->
                _state.update {
                    it.copy(
                        path = target,
                        entries = sortEntries(entries),
                        loading = false,
                        loaded = true,
                        failure = null,
                    )
                }
            },
            onFailure = { error ->
                // The path is adopted even on failure so the breadcrumb and the
                // retry action point at what the user asked for, not at the
                // directory they were in two taps ago.
                _state.update { it.copy(path = target) }
                fail("Could not open $target: ${message(error)}")
            },
        )
    }

    /**
     * The directory to open when the route carried no path: the route argument
     * if there is one, else the login shell's working directory.
     *
     * A home-relative route (`~/git`, issue #2616) is expanded against that
     * same host-reported home before any SFTP use — SFTP has no shell and
     * cannot expand `~` itself.
     *
     * `pwd` over [HostConnection.exec] rather than an SFTP call, because the
     * channel deliberately has no "canonicalize"/"home" verb — the file screens
     * are its only consumer and this is the only place that needs one. A host
     * that cannot answer falls back to the root, which is always listable.
     */
    private suspend fun resolveStartDirectory(connection: HostConnection): String {
        val carried = startPath?.takeIf { it.isNotBlank() }
        if (carried != null && !RemotePath.isHomeRelative(carried)) return RemotePath.normalize(carried)
        val home = remoteHome(connection)
        if (carried != null) {
            return RemotePath.normalize(RemotePath.expandHome(carried, home) ?: carried)
        }
        return if (home.isNullOrBlank()) RemotePath.ROOT else RemotePath.normalize(home)
    }

    private suspend fun remoteHome(connection: HostConnection): String? =
        runCatching { connection.exec("pwd") }.getOrNull()
            ?.takeIf { it.exitCode == 0 && !it.timedOut }
            ?.stdout
            ?.lineSequence()
            ?.map { it.trim() }
            ?.lastOrNull { it.startsWith("/") }

    private suspend fun sftp(): SftpChannel = when (val result = registry.getOrConnect(hostId)) {
        is ConnectResult.Connected -> result.connection.sftp()
        is ConnectResult.NeedsTrust -> throw java.io.IOException("this host's key is not confirmed")
        is ConnectResult.Failed -> throw java.io.IOException(result.message)
    }

    private fun fail(message: String) {
        _state.update { it.copy(loading = false, failure = message) }
    }

    private fun updateTransferProgress(id: Long, bytes: Long) {
        _state.update { state ->
            val record = state.transferRecords.firstOrNull { it.id == id } ?: return@update state
            val updatedRecord = record.copy(bytesTransferred = bytes)
            state.copy(
                transfer = (state.transfer as? TransferState.Running)
                    ?.takeIf { it.id == id }
                    ?.copy(bytesTransferred = bytes)
                    ?: state.transfer,
                transferRecords = state.transferRecords.map {
                    if (it.id == id) updatedRecord else it
                },
            )
        }
    }

    private fun finishTransfer(id: Long, bytes: Long, message: String) {
        _state.update { state ->
            state.copy(
                transferRecords = state.transferRecords.map {
                    if (it.id == id) {
                        it.copy(
                            bytesTransferred = bytes,
                            totalBytes = it.totalBytes ?: bytes,
                            status = FileTransferStatus.Completed,
                            message = message,
                        )
                    } else {
                        it
                    }
                },
            )
        }
    }

    private fun failTransfer(id: Long, message: String) {
        _state.update { state ->
            state.copy(
                transferRecords = state.transferRecords.map {
                    if (it.id == id) it.copy(status = FileTransferStatus.Failed, message = message) else it
                },
            )
        }
    }

    private companion object {
        val MAX_UPLOAD_BYTES: Long = TransferLimits.MAX_UPLOAD_BYTES
        val MAX_DOWNLOAD_BYTES: Long = TransferLimits.MAX_DOWNLOAD_BYTES
    }
}

/**
 * Directories first, then files, each alphabetically and case-insensitively.
 *
 * Fixed, not user-configurable: the old client shipped a four-way sort menu
 * (name/size/modified/type × asc/desc) whose state had to be threaded through
 * the listing cache to stay consistent. Folders-then-name is what a developer
 * browsing a source tree wants every time; a sort menu can come back when
 * somebody asks for it.
 */
internal fun sortEntries(entries: List<SftpEntry>): List<SftpEntry> = entries.sortedWith(
    compareByDescending<SftpEntry> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.name },
)

/**
 * Reduces a device document's display name to a single safe remote file name.
 *
 * A content provider can report anything as a display name, including a value
 * with separators in it. Uploading `../../.ssh/authorized_keys` because a
 * provider said so is not a feature, so only the last segment survives and a
 * name that reduces to nothing gets a neutral fallback.
 */
internal fun sanitizeUploadName(displayName: String): String {
    val leaf = displayName.substringAfterLast('/').substringAfterLast('\\').trim()
    val cleaned = leaf.filterNot { it.isISOControl() }.trim()
    return when {
        cleaned.isEmpty() || cleaned == "." || cleaned == ".." -> "upload"
        else -> cleaned
    }
}

/** Validates one remote child name while leaving the form value untouched. */
internal fun validateRemoteLeafName(name: String, label: String): String? {
    if (name.isBlank()) return "$label is required"
    if (name == "." || name == "..") return "$label cannot be . or .."
    if (name.any { it == '/' || it == '\\' }) return "$label cannot contain a path separator"
    if (name.any(Char::isISOControl)) return "$label contains an unsupported character"
    return null
}

/**
 * Human file size, matching the explorer rows and the transfer banner.
 *
 * Pinned to [Locale.US] rather than the default: the decimal separator is not a
 * localisation the app does anywhere else (paths, ports and byte counts are all
 * rendered machine-style), and a default-locale format would make the same
 * string read `1,5 KB` on one device and `1.5 KB` on another.
 */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(Locale.US, bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
}

internal fun tooBigToUpload(name: String): String =
    "$name is larger than ${formatSize(TransferLimits.MAX_UPLOAD_BYTES)} — " +
        "too big to upload from the phone."

/**
 * The transfer caps.
 *
 * The whole document is held in memory once in each direction (SFTP writes a
 * `ByteArray` and reads one back), so these are heap budgets, not policy: 32 MiB
 * is a generous log/screenshot/patch and still a fraction of a phone's per-app
 * heap. Their own object rather than a private companion because the number is
 * part of what the screen tells the user ("larger than 32.0 MB").
 */
internal object TransferLimits {
    const val MAX_UPLOAD_BYTES: Long = 32L * 1024 * 1024
    const val MAX_DOWNLOAD_BYTES: Long = 32L * 1024 * 1024

    /** Read chunk for the local document stream. */
    const val COPY_CHUNK_BYTES: Int = 64 * 1024
}

/**
 * Reads [input] fully, refusing to exceed [maxBytes].
 *
 * Refuses rather than truncates, exactly like
 * [com.pocketshell.core.transport.SftpChannel.read]: half a file written to the
 * host under its real name is worse than a failed upload.
 */
internal fun readCapped(
    input: InputStream,
    maxBytes: Long,
    name: String,
    onProgress: (Long) -> Unit = {},
): ByteArray {
    val sink = ByteArrayOutputStream()
    val buffer = ByteArray(TransferLimits.COPY_CHUNK_BYTES)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw java.io.IOException(tooBigToUpload(name))
        sink.write(buffer, 0, read)
        onProgress(total)
    }
    return sink.toByteArray()
}

/**
 * User-facing text for a transfer/listing failure.
 *
 * [SftpFileTooLargeException] gets its own sentence because "12.4 MB is over the
 * limit" is actionable and "IOException" is not.
 */
internal fun message(error: Throwable): String = when (error) {
    is SftpFileTooLargeException ->
        "${RemotePath.nameOf(error.path)} is ${formatSize(error.sizeBytes)}, " +
            "over the ${formatSize(error.maxBytes)} limit"

    else -> error.message ?: error::class.simpleName ?: "unknown error"
}
