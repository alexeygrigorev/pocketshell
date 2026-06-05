package com.pocketshell.app.fileexplorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshFileNotFoundException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshNotADirectoryException
import com.pocketshell.core.ssh.SshPermissionDeniedException
import com.pocketshell.core.ssh.SshSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
 * Backs [FileExplorerScreen] — issue #528.
 *
 * Browses the remote filesystem over SSH: lists a directory via
 * [SshSession.listDirectory], lets the user descend into folders, go up to the
 * parent, jump to a typed path, and hand a resolved file path to the existing
 * file viewer.
 *
 * Connection model: one persistent [SshSession] is opened on first load and
 * reused across navigation (browsing is chatty — a fresh connect per directory
 * would be slow), then torn down in [onCleared]. This mirrors the host
 * credentials the live session already holds, passed in via [start].
 */
@HiltViewModel
class FileExplorerViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow<FileExplorerUiState>(
        FileExplorerUiState.Loading(currentPath = ""),
    )
    val state: StateFlow<FileExplorerUiState> = _state.asStateFlow()

    private var request: Request? = null
    private var session: SshSession? = null
    private var loadJob: Job? = null

    /** The absolute path of the directory currently shown (resolved). */
    private var currentDir: String = ""

    /**
     * Bind host credentials + the start directory and load it. Idempotent for
     * the same [Request] so a recomposition doesn't reset the user's navigation.
     */
    fun start(request: Request) {
        if (this.request == request) return
        this.request = request
        currentDir = ""
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

    private fun navigateTo(path: String, resolveSymbolic: Boolean) {
        val req = request ?: return
        loadJob?.cancel()
        _state.value = FileExplorerUiState.Loading(currentPath = path)
        loadJob = viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { load(req, path, resolveSymbolic) }
        }
    }

    private suspend fun load(
        req: Request,
        path: String,
        resolveSymbolic: Boolean,
    ): FileExplorerUiState {
        val live = ensureSession(req)
            ?: return FileExplorerUiState.Failed(
                currentPath = path,
                message = "Couldn't reach ${req.username}@${req.hostname}.",
            )
        return try {
            // Canonicalize so `~`, `..`, and relative input resolve to a stable
            // absolute path for the breadcrumb. `pwd -P` inside the dir is the
            // portable way; fall back to the raw path if it fails.
            val absolute = if (resolveSymbolic || path.contains("..") || !path.startsWith("/")) {
                canonicalize(live, path) ?: path
            } else {
                path
            }
            val listing = live.listDirectory(absolute)
            currentDir = absolute
            FileExplorerUiState.Ready(
                currentPath = absolute,
                entries = listing.entries.sortedWith(RemoteEntry.FOLDERS_FIRST),
                truncated = listing.truncated,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SshPermissionDeniedException) {
            FileExplorerUiState.Failed(
                currentPath = path,
                message = "Permission denied: you can't read $path.",
            )
        } catch (e: SshNotADirectoryException) {
            FileExplorerUiState.Failed(
                currentPath = path,
                message = "Not a directory: $path",
            )
        } catch (e: SshFileNotFoundException) {
            FileExplorerUiState.Failed(
                currentPath = path,
                message = "No such directory on the server: $path",
            )
        } catch (t: Throwable) {
            FileExplorerUiState.Failed(
                currentPath = path,
                message = "Couldn't list the directory: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    /**
     * Resolve [path] to an absolute, symlink-free directory path by `cd`-ing
     * into it and printing `pwd -P`. Returns null when the cd fails (the caller
     * then surfaces the listing error, which has the precise reason).
     */
    private suspend fun canonicalize(live: SshSession, path: String): String? {
        val quoted = path.replace("'", "'\\''")
        val result = runCatching { live.exec("cd '$quoted' 2>/dev/null && pwd -P") }.getOrNull()
        val out = result?.stdout?.trim()
        return out?.takeIf { result.exitCode == 0 && it.startsWith("/") }
    }

    private suspend fun ensureSession(req: Request): SshSession? {
        session?.let { if (it.isConnected) return it }
        val opened = SshConnection.connect(
            host = req.hostname,
            port = req.port,
            user = req.username,
            key = SshKey.Path(File(req.keyPath)),
            passphrase = req.passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrNull()
        session = opened
        return opened
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        runCatching { session?.close() }
        session = null
    }

    /**
     * Host credentials + the start directory for the explorer. Mirrors the
     * credential shape used by sibling per-host screens.
     */
    data class Request(
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
            var result = hostname.hashCode()
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
