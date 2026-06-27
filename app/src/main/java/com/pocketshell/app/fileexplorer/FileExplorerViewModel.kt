package com.pocketshell.app.fileexplorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.core.ssh.SshFileNotFoundException
import com.pocketshell.core.ssh.SshFileTooLargeException
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshNotADirectoryException
import com.pocketshell.core.ssh.SshPermissionDeniedException
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SortField
import com.pocketshell.core.ssh.shellSingleQuote
import com.pocketshell.app.share.FilenameSanitiser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject

/**
 * UI state for the file explorer (issue #528).
 *
 * Every state carries the [currentPath] being browsed so the breadcrumb /
 * header renders even mid-load and on error, and so the parent ("..")
 * affordance is always derivable.
 */
sealed interface FileExplorerUiState {

    val currentPath: String

    /** Fetching the listing for [currentPath]. */
    data class Loading(override val currentPath: String) : FileExplorerUiState

    /** A directory was listed. [entries] are already folders-first sorted. */
    data class Ready(
        override val currentPath: String,
        val entries: List<RemoteEntry>,
        /** True when the listing was capped at the entry limit. */
        val truncated: Boolean,
    ) : FileExplorerUiState

    /**
     * The directory couldn't be listed. [message] is user-facing; [canRetry]
     * gates the Retry affordance (always true here — a transient transport drop
     * or a since-fixed permission can be retried).
     */
    data class Failed(
        override val currentPath: String,
        val message: String,
        val canRetry: Boolean = true,
    ) : FileExplorerUiState
}

/**
 * Transfer banner state for in-explorer upload / download (issue #643).
 *
 * Kept separate from [FileExplorerUiState] so a transfer never clobbers the
 * directory listing the user is looking at — the banner overlays the list and
 * the entries stay visible underneath.
 */
sealed interface FileTransferState {

    /** No transfer in flight; nothing to show. */
    data object Idle : FileTransferState

    /**
     * A transfer is running. [name] is the file's display name and
     * [isUpload] picks the verb shown in the banner ("Uploading…" vs
     * "Downloading…").
     */
    data class InProgress(
        val name: String,
        val isUpload: Boolean,
        val bytesTotal: Long? = null,
    ) : FileTransferState

    /** A transfer finished successfully. [message] is the user-facing summary. */
    data class Success(val message: String) : FileTransferState

    /** A transfer failed. [message] is the user-facing reason. */
    data class Failure(val message: String) : FileTransferState
}

/**
 * Backs [FileExplorerScreen] — issue #528.
 *
 * Browses the remote filesystem over SSH: lists a directory via
 * [SshSession.listDirectory], lets the user descend into folders, go up to the
 * parent, jump to a typed path, and hand a resolved file path to the existing
 * file viewer.
 *
 * Connection model (issue #697): the explorer is a **transport consumer** — it
 * acquires the app-wide warm [SshLeaseManager] lease the session / folder /
 * tmux screens already hold for this host (keyed IDENTICALLY by
 * host/port/user/`"$hostId:$keyPath"`), runs its SFTP/exec channel over that
 * existing connection, and releases the lease (it never `close()`s it). So
 * opening the explorer on a host whose session screen is already up rides the
 * warm transport — no fresh ~3-4s SSH handshake per open. The per-open
 * `SshConnection.connect()` / `close()` is hard-cut (D22): there is no longer a
 * file-explorer-only connection.
 *
 * Each browse operation follows the
 * [com.pocketshell.app.projects.FolderListGateway] acquire → block → release
 * template, including the #680 stale-channel heal-retry: a lease whose pooled
 * transport silently died between acquire and the exec is evicted and the
 * operation retried once on a fresh transport, so a transient drop heals
 * instead of surfacing a scary error.
 */
@HiltViewModel
class FileExplorerViewModel @Inject constructor(
    private val sshLeaseManager: SshLeaseManager,
) : ViewModel() {

    private val _state = MutableStateFlow<FileExplorerUiState>(
        FileExplorerUiState.Loading(currentPath = ""),
    )
    val state: StateFlow<FileExplorerUiState> = _state.asStateFlow()

    private val _transfer = MutableStateFlow<FileTransferState>(FileTransferState.Idle)
    val transfer: StateFlow<FileTransferState> = _transfer.asStateFlow()

    /**
     * The current listing sort (issue #762). Re-sorting is a pure in-memory
     * reorder of the already-fetched entries — no re-list, no transport cost —
     * with folders kept first within any sort. Defaults to Name-ascending so the
     * listing opens exactly as it did before the Sort menu existed.
     */
    private val _sort = MutableStateFlow(SortMode(SortField.NAME, ascending = true))
    val sort: StateFlow<SortMode> = _sort.asStateFlow()

    private var request: Request? = null
    private var loadJob: Job? = null
    private var transferJob: Job? = null

    /** The absolute path of the directory currently shown (resolved). */
    private var currentDir: String = ""

    /**
     * Small directory-listing cache (issue #697): keyed by the canonical
     * absolute path, capped LRU. On re-entering a directory we already listed
     * (a `goUp()` to a parent, a back-and-forth) the cached entries render
     * instantly while a fresh listing reconciles in the background — the #620
     * cached-then-reconcile pattern applied to the explorer browse path. The
     * cache only ever supplies a transient first paint; the live listing always
     * wins, so a since-changed directory never silently shows stale contents.
     */
    private val listingCache = object : LinkedHashMap<String, CachedListing>(
        /* initialCapacity = */ LISTING_CACHE_CAPACITY + 1,
        /* loadFactor = */ 1f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedListing>): Boolean =
            size > LISTING_CACHE_CAPACITY
    }

    /**
     * Bind host credentials + the start directory and load it. Idempotent for
     * the same [Request] so a recomposition doesn't reset the user's navigation.
     */
    fun start(request: Request) {
        if (this.request == request) return
        this.request = request
        currentDir = ""
        listingCache.clear()
        navigateTo(request.startDir.ifBlank { "~" }, resolveSymbolic = true)
    }

    /** Re-list the current directory (wired to Retry on the failed panel). */
    fun retry() {
        val dir = currentDir.ifBlank { request?.startDir.orEmpty().ifBlank { "~" } }
        navigateTo(dir, resolveSymbolic = currentDir.isBlank())
    }

    /** Descend into a child directory of the current listing. */
    fun openDirectory(entry: RemoteEntry) {
        // A symlink may point at a directory; let the server resolve it on
        // re-list rather than guessing here.
        val child = joinPath(currentDir, entry.name)
        navigateTo(child, resolveSymbolic = entry.type == RemoteEntry.Type.SYMLINK)
    }

    /** Go up to the parent of the current directory. No-op at the root. */
    fun goUp() {
        val parent = parentOf(currentDir)
        if (parent == currentDir) return
        navigateTo(parent, resolveSymbolic = false)
    }

    /** Jump to an ancestor crumb (absolute path already known). */
    fun navigateToAbsolute(absolutePath: String) {
        if (absolutePath == currentDir) return
        navigateTo(absolutePath, resolveSymbolic = false)
    }

    /**
     * Go to a user-typed path. Relative paths resolve against the current
     * directory server-side; `~` and absolute paths resolve as the shell sees
     * them. The result is canonicalized so the breadcrumb stays accurate.
     */
    fun goToPath(typed: String) {
        val input = typed.trim()
        if (input.isEmpty()) return
        val target = when {
            input.startsWith("/") || input.startsWith("~") -> input
            currentDir.isNotBlank() -> joinPath(currentDir, input)
            else -> input
        }
        navigateTo(target, resolveSymbolic = true)
    }

    /**
     * Change the listing sort (issue #762). Pure in-memory reorder of the
     * currently-shown entries (and the cached listing for this directory so a
     * re-enter keeps the chosen order) — no re-list. Folders stay grouped first
     * regardless of field/direction.
     */
    fun setSort(field: SortField, ascending: Boolean) {
        val next = SortMode(field, ascending)
        if (_sort.value == next) return
        _sort.value = next
        val current = _state.value
        if (current is FileExplorerUiState.Ready) {
            val resorted = current.entries.sortedWith(next.comparator())
            _state.value = current.copy(entries = resorted)
            // Keep the cache for this dir consistent with the chosen order so a
            // back-and-forth paints in the same order it was last shown.
            listingCache[current.currentPath]?.let { cached ->
                listingCache[current.currentPath] = cached.copy(entries = resorted)
            }
        }
    }

    /** Dismiss the transfer banner once the user has seen the result. */
    fun dismissTransfer() {
        if (_transfer.value is FileTransferState.InProgress) return
        _transfer.value = FileTransferState.Idle
    }

    /**
     * Upload a device file into the directory currently shown (issue #643).
     *
     * The caller resolves a content URI into [openStream] (a fresh
     * [InputStream] each invocation), the byte [length], and the device-side
     * [displayName]; we sanitise the name, SCP-stream it into [currentDir],
     * surface a progress/success/failure banner, then re-list so the new file
     * appears. [openStream] may be invoked twice — once for the size-bearing
     * stream upload, and only if needed — so it must return a fresh stream.
     */
    fun uploadFile(displayName: String, length: Long, openStream: () -> InputStream?) {
        val req = request ?: return
        val dir = currentDir
        if (dir.isBlank()) return
        if (_transfer.value is FileTransferState.InProgress) return
        val safeName = sanitizeUploadName(displayName)
        _transfer.value = FileTransferState.InProgress(
            name = safeName,
            isUpload = true,
            bytesTotal = length.takeIf { it > 0L },
        )
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                withLeaseSession(req, leasePurpose = LEASE_PURPOSE_TRANSFER, blockTimeoutMs = null) { live ->
                    val remotePath = joinPath(dir, safeName)
                    val input = openStream()
                        ?: throw IllegalStateException("Couldn't read the selected file.")
                    input.use { stream ->
                        live.uploadStream(
                            input = stream,
                            length = length,
                            name = safeName,
                            remotePath = remotePath,
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { written ->
                    _transfer.value = FileTransferState.Success(
                        "Uploaded $safeName to ${displayPath(written)}",
                    )
                    // Re-list the current directory so the new file shows up.
                    // The cache for this dir is stale now — drop it so the
                    // re-list shows the fresh contents, not the pre-upload cache.
                    listingCache.remove(dir)
                    navigateTo(dir, resolveSymbolic = false)
                },
                onFailure = { t ->
                    if (t is CancellationException) throw t
                    _transfer.value = FileTransferState.Failure(
                        "Upload failed: ${transferErrorText(t)}",
                    )
                },
            )
        }
    }

    /**
     * Download a remote file from the current listing to the device
     * (issue #643).
     *
     * Fetches [entry] from [currentDir] over the existing session (capped at
     * [MAX_DOWNLOAD_BYTES] so a runaway file never blows the JVM heap) and hands
     * the bytes to [writeBytes], which the caller wires to the device URI it
     * picked (`ContentResolver.openOutputStream`). Surfaces a
     * progress/success/failure banner.
     */
    fun downloadFile(entry: RemoteEntry, writeBytes: (ByteArray) -> Unit) {
        val req = request ?: return
        val dir = currentDir
        if (dir.isBlank()) return
        if (_transfer.value is FileTransferState.InProgress) return
        _transfer.value = FileTransferState.InProgress(
            name = entry.name,
            isUpload = false,
            bytesTotal = entry.sizeBytes.takeIf { it > 0L },
        )
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val downloaded = withLeaseSession(
                    req,
                    leasePurpose = LEASE_PURPOSE_TRANSFER,
                    blockTimeoutMs = null,
                ) { live ->
                    val remotePath = joinPath(dir, entry.name)
                    live.downloadFile(remotePath, MAX_DOWNLOAD_BYTES)
                }
                downloaded.mapCatching { bytes ->
                    writeBytes(bytes)
                    bytes.size
                }
            }
            result.fold(
                onSuccess = { written ->
                    _transfer.value = FileTransferState.Success(
                        "Downloaded ${entry.name} (${formatSize(written.toLong())})",
                    )
                },
                onFailure = { t ->
                    if (t is CancellationException) throw t
                    _transfer.value = FileTransferState.Failure(
                        "Download failed: ${transferErrorText(t)}",
                    )
                },
            )
        }
    }

    private fun navigateTo(path: String, resolveSymbolic: Boolean) {
        val req = request ?: return
        loadJob?.cancel()
        // Issue #697: if we already have a cached listing for this exact
        // absolute path (a re-enter / goUp to somewhere we just were), paint it
        // instantly so the browse feels local, then reconcile with the live
        // listing below. We only do this for an already-absolute path (the
        // cache is keyed by the canonical path the live list produced), since
        // `~` / `..` / relative input still need a server round-trip to resolve.
        val cached = if (path.startsWith("/")) listingCache[path] else null
        if (cached != null) {
            currentDir = path
            _state.value = FileExplorerUiState.Ready(
                currentPath = path,
                entries = cached.entries,
                truncated = cached.truncated,
            )
        } else {
            _state.value = FileExplorerUiState.Loading(currentPath = path)
        }
        loadJob = viewModelScope.launch {
            val next = withContext(Dispatchers.IO) { load(req, path, resolveSymbolic) }
            // A reconcile must never replace a freshly-shown cached listing with
            // a Loading flicker; load() only returns Ready/Failed, so this is a
            // straight swap to the authoritative result.
            _state.value = next
        }
    }

    private suspend fun load(
        req: Request,
        path: String,
        resolveSymbolic: Boolean,
    ): FileExplorerUiState {
        val result = withLeaseSession(req) { live ->
            // Canonicalize so `~`, `..`, and relative input resolve to a stable
            // absolute path for the breadcrumb. `pwd -P` inside the dir is the
            // portable way; fall back to the raw path if it fails.
            val absolute = if (resolveSymbolic || path.contains("..") || !path.startsWith("/")) {
                canonicalize(live, path) ?: path
            } else {
                path
            }
            val listing = live.listDirectory(absolute)
            // Sort by the user's active choice (issue #762); folders stay first.
            val sorted = listing.entries.sortedWith(_sort.value.comparator())
            absolute to (sorted to listing.truncated)
        }
        return result.fold(
            onSuccess = { (absolute, listing) ->
                val (entries, truncated) = listing
                currentDir = absolute
                listingCache[absolute] = CachedListing(entries = entries, truncated = truncated)
                FileExplorerUiState.Ready(
                    currentPath = absolute,
                    entries = entries,
                    truncated = truncated,
                )
            },
            onFailure = { t ->
                when (t) {
                    is CancellationException -> throw t
                    is SshPermissionDeniedException -> FileExplorerUiState.Failed(
                        currentPath = path,
                        message = "Permission denied: you can't read $path.",
                    )
                    is SshNotADirectoryException -> FileExplorerUiState.Failed(
                        currentPath = path,
                        message = "Not a directory: $path",
                    )
                    is SshFileNotFoundException -> FileExplorerUiState.Failed(
                        currentPath = path,
                        message = "No such directory on the server: $path",
                    )
                    else -> FileExplorerUiState.Failed(
                        currentPath = path,
                        message = "Couldn't list the directory: ${t.message ?: t.javaClass.simpleName}",
                    )
                }
            },
        )
    }

    /**
     * Resolve [path] to an absolute, symlink-free directory path by `cd`-ing
     * into it and printing `pwd -P`. Returns null when the cd fails (the caller
     * then surfaces the listing error, which has the precise reason).
     */
    private suspend fun canonicalize(live: SshSession, path: String): String? {
        val quoted = shellSingleQuote(path)
        val result = runCatching { live.exec("cd $quoted 2>/dev/null && pwd -P") }.getOrNull()
        val out = result?.stdout?.trim()
        return out?.takeIf { result.exitCode == 0 && it.startsWith("/") }
    }

    /**
     * One lease acquire → block → release cycle over the app-wide warm
     * transport (issue #697). The block runs its SFTP/exec channel on the
     * shared connection (no new handshake). On a stale-channel symptom — the
     * pooled transport silently died between acquire and the exec — the lease is
     * EVICTED and the block retried ONCE on a fresh transport, mirroring the
     * [com.pocketshell.app.projects.FolderListGateway] heal-retry (#680). Any
     * other failure is returned as-is for the caller to map to UI.
     */
    private suspend fun <T> withLeaseSession(
        req: Request,
        leasePurpose: String? = null,
        blockTimeoutMs: Long? = LeaseSessionExec.BLOCK_TIMEOUT_MS,
        block: suspend (SshSession) -> T,
    ): Result<T> =
        LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = req.toLeaseSessionTarget(leasePurpose),
            blockTimeoutMs = blockTimeoutMs,
            block = block,
        )

    /**
     * Build the lease target keyed IDENTICALLY to the session / folder / tmux
     * screens (issue #697): `host/port/user/"$hostId:$keyPath"`. Sharing the
     * exact [SshLeaseKey] is what makes the pool hand back the LITERALLY SAME
     * warm transport those screens already opened, so the explorer's SFTP/exec
     * channel rides the existing connection instead of dialing its own.
     */
    private fun Request.toLeaseSessionTarget(leasePurpose: String? = null): LeaseSessionTarget =
        LeaseSessionTarget(
            hostId = hostId,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase?.copyOf(),
            leasePurpose = leasePurpose,
        )

    /**
     * Shorten an absolute remote path for the success banner: collapse the
     * file's own name off the end so the banner reads "…to /home/me/logs"
     * rather than repeating the filename.
     */
    private fun displayPath(remotePath: String): String {
        val parent = parentOf(remotePath)
        return parent.ifBlank { remotePath }
    }

    /** One-line, stack-trace-free reason text for a transfer banner. */
    private fun transferErrorText(t: Throwable): String = when (t) {
        is SshPermissionDeniedException -> "permission denied"
        is SshFileNotFoundException -> "no such file on the server"
        is SshNotADirectoryException -> "not a directory"
        is SshFileTooLargeException ->
            "file exceeds the ${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB transfer limit"
        else -> (t.message ?: t.javaClass.simpleName).lineSequence().firstOrNull()?.take(160)
            ?: "transfer error"
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        transferJob?.cancel()
        listingCache.clear()
        // Issue #697: the explorer no longer owns a connection — it borrowed the
        // app-wide warm lease and released it after every operation. There is
        // nothing to close here; the lease pool keeps the transport warm for its
        // idle TTL so a sibling screen (or re-opening the explorer) reuses it.
    }

    /**
     * A cached directory listing (issue #697). Holds the folders-first sorted
     * entries + the truncated flag for a previously-listed absolute path so a
     * re-enter paints instantly while the live listing reconciles.
     */
    private data class CachedListing(
        val entries: List<RemoteEntry>,
        val truncated: Boolean,
    )

    /**
     * The explorer's listing sort (issue #762): a [field] + a direction. Pairs
     * with [RemoteEntry.comparator] so folders-first stays invariant within any
     * field/direction. Default is Name-ascending.
     */
    data class SortMode(
        val field: SortField,
        val ascending: Boolean,
    ) {
        fun comparator(): Comparator<RemoteEntry> = RemoteEntry.comparator(field, ascending)
    }

    /**
     * Host credentials + the start directory for the explorer. Mirrors the
     * credential shape used by sibling per-host screens. [hostId] is carried so
     * the lease key matches the session / folder / tmux screens exactly
     * (issue #697), letting the explorer reuse their warm transport.
     */
    data class Request(
        val hostId: Long,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val startDir: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Request) return false
            if (hostId != other.hostId) return false
            if (hostname != other.hostname) return false
            if (port != other.port) return false
            if (username != other.username) return false
            if (keyPath != other.keyPath) return false
            if (startDir != other.startDir) return false
            if (passphrase != null) {
                if (other.passphrase == null) return false
                if (!passphrase.contentEquals(other.passphrase)) return false
            } else if (other.passphrase != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = hostId.hashCode()
            result = 31 * result + hostname.hashCode()
            result = 31 * result + port
            result = 31 * result + username.hashCode()
            result = 31 * result + keyPath.hashCode()
            result = 31 * result + startDir.hashCode()
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {

        /**
         * Hard cap on a single download-to-device transfer (issue #643). A few
         * hundred MB is far past anything the user will sensibly pull onto a
         * phone; capping here keeps a runaway file (a multi-GB log, an image
         * dump) from blowing the JVM heap, mirroring the viewer's preview cap.
         */
        const val MAX_DOWNLOAD_BYTES: Long = 256L * 1024 * 1024

        /**
         * LRU cap on the directory-listing cache (issue #697). A handful of
         * recent directories is enough to make back-and-forth / `goUp()`
         * navigation paint instantly; bounding it keeps a deep browse from
         * pinning every listing in memory.
         */
        private const val LISTING_CACHE_CAPACITY: Int = 24

        /**
         * Sanitise a device-picked filename for a remote upload (issue #643).
         * Reuses the share-target [FilenameSanitiser] so traversal segments,
         * control bytes, and absurd lengths can't reach the remote path; unlike
         * the share path we do NOT prepend a timestamp, since the user is
         * choosing the destination directory explicitly and expects the file to
         * land under its own name.
         */
        internal fun sanitizeUploadName(displayName: String): String =
            FilenameSanitiser.sanitise(displayName).render()

        internal const val LEASE_PURPOSE_TRANSFER: String = "file-transfer"

        /**
         * Join [name] onto a directory [base], collapsing the trailing slash.
         * Pure — unit-tested. Used for descend (base is always absolute by the
         * time a row is tapped, since the listing canonicalized it).
         */
        internal fun joinPath(base: String, name: String): String {
            if (base.isEmpty()) return name
            val cleaned = base.trimEnd('/')
            return if (cleaned == "") "/$name" else "$cleaned/$name"
        }

        /**
         * Parent of an absolute directory path. The root (`/`) is its own
         * parent (used to gate the up affordance). Pure — unit-tested.
         */
        internal fun parentOf(path: String): String {
            val cleaned = path.trimEnd('/')
            if (cleaned.isEmpty() || cleaned == "") return "/"
            val idx = cleaned.lastIndexOf('/')
            return when {
                idx < 0 -> path // not absolute; can't go up portably
                idx == 0 -> "/" // parent of /foo is /
                else -> cleaned.substring(0, idx)
            }
        }

        /**
         * Split an absolute path into clickable breadcrumb segments:
         * pairs of (label, absolutePath). The first crumb is the root `/`.
         * Pure — unit-tested.
         */
        internal fun breadcrumbSegments(path: String): List<Pair<String, String>> {
            if (!path.startsWith("/")) {
                // Non-absolute (pre-canonicalization) — render as a single crumb.
                return listOf(path to path)
            }
            val crumbs = mutableListOf("/" to "/")
            var acc = ""
            for (segment in path.split('/')) {
                if (segment.isEmpty()) continue
                acc = "$acc/$segment"
                crumbs += segment to acc
            }
            return crumbs
        }
    }
}
