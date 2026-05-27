package com.pocketshell.app.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Backs [WatchedFoldersScreen] — the per-host "Watched folders"
 * config surface introduced in issue #206.
 *
 * The data layer is the existing [ProjectRootDao] (v3 of the Room
 * schema, landed by issues #58/#59). This view model is purely a
 * thin orchestration layer over that DAO plus the ssh-exec used by
 * the optional "Discover from remote" button.
 *
 * ## Persistence
 *
 * `getByHostId` is a Flow — every CRUD operation through
 * [addFolder] / [updateFolder] / [deleteFolder] / [reorderFolder]
 * lands in the DB and the Flow emits the new snapshot. No local
 * cache is maintained on the view model side.
 *
 * ## Ordering
 *
 * The DAO sorts by `(label, path)` for stable display. The view
 * model exposes a [reorderFolder] entry point that prefixes the
 * label with a lexicographic `[NN]` key built from the new index
 * so Room's ORDER BY honors the user's chosen order without
 * needing a new schema column. This is intentionally minimal —
 * users typically have a handful of watched folders per host
 * (3-10), so a finer-grained drag-handle reordering with a
 * dedicated `position` column is left to a future issue if
 * dogfood demands it.
 *
 * ## Discover from remote
 *
 * When the destination supplies SSH connection parameters
 * (`hostname` + `keyPath`) the screen surfaces a "Discover" button
 * that opens a one-shot SSH session, runs `ls -d` against the
 * conventional `~/git`, `~/code`, and `~/projects` roots (see
 * [DISCOVER_COMMAND]), parses the output into candidate paths, and
 * adds the ones the user accepts. Without credentials the button is
 * hidden — the Settings entry path can't unlock the user's
 * passphrase, so the discover path is only available from the
 * host-list kebab.
 */
@HiltViewModel
class WatchedFoldersViewModel @Inject constructor(
    internal val projectRootDao: ProjectRootDao,
    @Suppress("UNUSED_PARAMETER") private val hostDao: HostDao,
) : ViewModel() {

    private val _state: MutableStateFlow<WatchedFoldersUiState> =
        MutableStateFlow(WatchedFoldersUiState())

    val state: StateFlow<WatchedFoldersUiState> = _state.asStateFlow()

    private var observeJob: Job? = null
    private var hostId: Long? = null
    private var sshCredentials: SshCredentials? = null

    /**
     * Bind this view model to [hostId] and start observing its watched
     * folders. Re-calling with the same id is a no-op so a recomposition
     * (e.g. orientation change) does not re-subscribe and re-emit a
     * loading state.
     *
     * [sshCredentials] are optional — when supplied, the "Discover from
     * remote" button is enabled and the view model runs the ssh-exec on
     * demand. When null (e.g. opened from Settings without an active
     * session), the screen renders the hint instead.
     */
    fun bind(hostId: Long, hostName: String, sshCredentials: SshCredentials? = null) {
        if (this.hostId == hostId && this.sshCredentials == sshCredentials) {
            // Same target — only refresh the display name in case it changed.
            if (_state.value.hostName != hostName) {
                _state.value = _state.value.copy(hostName = hostName)
            }
            return
        }
        this.hostId = hostId
        this.sshCredentials = sshCredentials
        observeJob?.cancel()
        _state.value = WatchedFoldersUiState(
            hostId = hostId,
            hostName = hostName,
            sshCapable = sshCredentials != null,
        )
        observeJob = viewModelScope.launch {
            projectRootDao.getByHostId(hostId).collectLatest { rows ->
                _state.value = _state.value.copy(roots = rows)
            }
        }
    }

    fun addFolder(rawLabel: String, rawPath: String) {
        val hostId = this.hostId ?: return
        val validation = validate(rawLabel = rawLabel, rawPath = rawPath)
        if (validation is Validation.Invalid) {
            _state.value = _state.value.copy(feedback = validation.message)
            return
        }
        val (label, path) = (validation as Validation.Valid)
        viewModelScope.launch {
            val existing = projectRootDao.getByHostId(hostId).first()
            if (existing.any { it.path == path }) {
                _state.value = _state.value.copy(
                    feedback = "Path already in this host's watched folders.",
                )
                return@launch
            }
            projectRootDao.insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = label,
                    path = path,
                ),
            )
            _state.value = _state.value.copy(feedback = "Added $label.")
        }
    }

    fun updateFolder(id: Long, rawLabel: String, rawPath: String) {
        val hostId = this.hostId ?: return
        val current = _state.value.roots.firstOrNull { it.id == id } ?: return
        val validation = validate(rawLabel = rawLabel, rawPath = rawPath)
        if (validation is Validation.Invalid) {
            _state.value = _state.value.copy(feedback = validation.message)
            return
        }
        val (label, path) = (validation as Validation.Valid)
        viewModelScope.launch {
            if (path != current.path) {
                val collision = projectRootDao.getByHostId(hostId).first()
                    .any { it.id != id && it.path == path }
                if (collision) {
                    _state.value = _state.value.copy(
                        feedback = "Another entry already uses that path.",
                    )
                    return@launch
                }
            }
            projectRootDao.update(current.copy(label = label, path = path))
            _state.value = _state.value.copy(feedback = "Updated $label.")
        }
    }

    fun deleteFolder(id: Long) {
        val current = _state.value.roots.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            projectRootDao.delete(current)
            val visibleLabel = stripOrderPrefix(current.label)
            _state.value = _state.value.copy(feedback = "Removed $visibleLabel.")
        }
    }

    /**
     * Move the row at [fromIndex] up or down by [delta] (typically +/-1).
     *
     * The DAO sorts rows by `(label, path)`, so we synthesise the new
     * order by re-prefixing each row's label with an `[NN]` key. This
     * keeps reorder a pure DAO update without a schema migration — a
     * deeper solution (dedicated `position` column) is left to a future
     * issue if reorder turns into a common operation in dogfood.
     *
     * Out-of-bounds moves silently no-op so the screen can pass the
     * delta directly from the up/down chevron without bounds-checking.
     */
    fun reorderFolder(fromIndex: Int, delta: Int) {
        val rows = _state.value.roots
        if (rows.isEmpty() || fromIndex !in rows.indices) return
        val toIndex = (fromIndex + delta).coerceIn(0, rows.size - 1)
        if (toIndex == fromIndex) return
        val reordered = rows.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        viewModelScope.launch {
            reordered.forEachIndexed { index, row ->
                val newLabel = applyOrderPrefix(row.label, index, reordered.size)
                if (newLabel != row.label) {
                    projectRootDao.update(row.copy(label = newLabel))
                }
            }
        }
    }

    /**
     * Trigger the optional "Discover from remote" flow. Requires the
     * destination to have supplied [SshCredentials]; otherwise the
     * call is a no-op and the screen shows the static hint instead.
     */
    fun discoverFromRemote() {
        if (this.hostId == null) return
        val creds = sshCredentials ?: run {
            _state.value = _state.value.copy(
                feedback = "Open this host to enable discovery.",
            )
            return
        }
        if (_state.value.discovering) return
        _state.value = _state.value.copy(discovering = true, discoverError = null)
        viewModelScope.launch {
            val result = runCatching { runDiscover(creds) }
            val candidates = result.getOrDefault(emptyList())
            _state.value = _state.value.copy(
                discovering = false,
                discoverError = result.exceptionOrNull()?.message,
                discoveredCandidates = candidates,
            )
            if (result.isSuccess && candidates.isEmpty()) {
                _state.value = _state.value.copy(
                    feedback = "No folders found under ~/git, ~/code, or ~/projects.",
                )
            }
        }
    }

    /**
     * Commit a single discovered candidate to the DAO. Called from
     * the screen when the user taps the "Add" affordance next to a
     * candidate row in the discovery panel.
     */
    fun acceptDiscovered(candidate: DiscoveredFolder) {
        val hostId = this.hostId ?: return
        viewModelScope.launch {
            val existingPaths = projectRootDao.getByHostId(hostId).first()
                .map { it.path }
                .toSet()
            if (candidate.path in existingPaths) {
                _state.value = _state.value.copy(
                    feedback = "${candidate.label} is already in the watched folders list.",
                    discoveredCandidates = _state.value.discoveredCandidates.filterNot { c ->
                        c.path == candidate.path
                    },
                )
                return@launch
            }
            projectRootDao.insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = candidate.label,
                    path = candidate.path,
                ),
            )
            _state.value = _state.value.copy(
                feedback = "Added ${candidate.label}.",
                discoveredCandidates = _state.value.discoveredCandidates.filterNot { c ->
                    c.path == candidate.path
                },
            )
        }
    }

    fun dismissDiscovered() {
        _state.value = _state.value.copy(
            discoveredCandidates = emptyList(),
            discoverError = null,
        )
    }

    fun clearFeedback() {
        _state.value = _state.value.copy(feedback = null)
    }

    /**
     * Open a one-shot SSH session, run the discovery probe, and parse
     * the result into a list of candidates. Runs on the IO dispatcher.
     *
     * The probe shells through `ls -d` with stderr redirected to
     * `/dev/null` so a missing root (e.g. `~/code` doesn't exist) doesn't
     * fail the whole pipeline. Lines look like `/home/alexey/git/foo/`
     * — we trim the trailing slash and derive the label from the last
     * path component.
     */
    private suspend fun runDiscover(creds: SshCredentials): List<DiscoveredFolder> =
        withContext(Dispatchers.IO) {
            val session = SshConnection.connect(
                host = creds.hostname,
                port = creds.port,
                user = creds.username,
                key = SshKey.Path(File(creds.keyPath)),
                passphrase = creds.passphrase,
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow()
            try {
                val result = session.exec(DISCOVER_COMMAND)
                // We don't fail on non-zero exit: a missing ~/git tree
                // makes `ls` exit 1 but stderr is redirected so we only
                // see the empty stdout. That's a valid "no discoveries"
                // outcome and should not raise.
                parseDiscoverOutput(result.stdout)
            } finally {
                runCatching { session.close() }
            }
        }

    /**
     * Test seam — drive [runDiscover] without going through the
     * Flow + view-model state machine. Internal so test code in the
     * same package can call it.
     */
    internal suspend fun parseDiscoverForTest(stdout: String): List<DiscoveredFolder> =
        parseDiscoverOutput(stdout)

    data class SshCredentials(
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SshCredentials) return false
            if (hostname != other.hostname) return false
            if (port != other.port) return false
            if (username != other.username) return false
            if (keyPath != other.keyPath) return false
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
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    private sealed interface Validation {
        data class Valid(val label: String, val path: String) : Validation
        data class Invalid(val message: String) : Validation
    }

    private fun validate(rawLabel: String, rawPath: String): Validation {
        val trimmedPath = rawPath.trim()
        if (trimmedPath.isEmpty()) {
            return Validation.Invalid("Path is required.")
        }
        if (!(trimmedPath == "~" || trimmedPath.startsWith("~/") || trimmedPath.startsWith("/"))) {
            return Validation.Invalid("Use an absolute path or a path under ~.")
        }
        if (trimmedPath.contains("..")) {
            return Validation.Invalid("Parent-directory segments are not allowed.")
        }
        if (trimmedPath.any { it.code < 0x20 || it.code == 0x7F }) {
            return Validation.Invalid("Path cannot contain control characters.")
        }
        val derivedLabel = trimmedPath.trimEnd('/').substringAfterLast('/').ifBlank { trimmedPath }
        val finalLabel = rawLabel.trim().ifBlank { derivedLabel }
        if (finalLabel.any { it.code < 0x20 || it.code == 0x7F }) {
            return Validation.Invalid("Label cannot contain control characters.")
        }
        return Validation.Valid(label = finalLabel, path = trimmedPath)
    }

    companion object {
        /**
         * Discovery probe — list immediate children of three conventional
         * roots, suppressing per-root errors so a missing directory
         * doesn't fail the whole call. `-d` keeps it directory-only
         * even if the user has dropped files into `~/git`.
         */
        internal const val DISCOVER_COMMAND: String =
            "ls -d ~/git/*/ ~/code/*/ ~/projects/*/ 2>/dev/null"

        /**
         * Parse one discovery output line into a [DiscoveredFolder]. Lines
         * are expected to end in a trailing slash (`ls -d <dir>/`); we
         * strip it before deriving the label. Blank lines and lines that
         * don't look like absolute paths are skipped.
         */
        internal fun parseDiscoverOutput(output: String): List<DiscoveredFolder> =
            output
                .lineSequence()
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() && (it.startsWith('/') || it.startsWith("~/")) }
                .filter { !it.endsWith("/.") && !it.endsWith("/..") }
                .map { path ->
                    DiscoveredFolder(
                        label = path.substringAfterLast('/').ifBlank { path },
                        path = path,
                    )
                }
                .distinctBy { it.path }
                .toList()

        /**
         * Build a four-character order prefix `[NN]` that prepends to the
         * label so Room's `ORDER BY label, path` sort yields the desired
         * order. The bracket character (`[` = 0x5B) sorts below all
         * printable letters/digits so prefixed rows always come before
         * any user-typed label.
         */
        internal fun applyOrderPrefix(label: String, index: Int, total: Int): String {
            val withoutPrefix = stripOrderPrefix(label)
            val width = if (total >= 100) 3 else 2
            val key = index.toString().padStart(width, '0')
            return "[$key] $withoutPrefix"
        }

        /**
         * Strip the `[NN]` order prefix from a label so the UI surfaces
         * the user-typed label only. Visible-for-test so the
         * reorder-and-strip round-trip can be asserted directly.
         */
        internal fun stripOrderPrefix(label: String): String {
            val match = ORDER_PREFIX_REGEX.find(label) ?: return label
            return label.substring(match.range.last + 1).trimStart()
        }

        private val ORDER_PREFIX_REGEX: Regex = Regex("^\\[\\d{2,3}]")
    }
}

/**
 * Snapshot rendered by [WatchedFoldersScreen]. Composed of the live
 * Flow over [ProjectRootDao] plus a handful of one-shot UI signals
 * (validation feedback, discover progress).
 */
data class WatchedFoldersUiState(
    val hostId: Long? = null,
    val hostName: String = "",
    val roots: List<ProjectRootEntity> = emptyList(),
    val feedback: String? = null,
    val sshCapable: Boolean = false,
    val discovering: Boolean = false,
    val discoveredCandidates: List<DiscoveredFolder> = emptyList(),
    val discoverError: String? = null,
)

/**
 * One candidate row returned by [WatchedFoldersViewModel.runDiscover].
 * The user can accept individual candidates via
 * [WatchedFoldersViewModel.acceptDiscovered] without committing the
 * full list.
 */
data class DiscoveredFolder(
    val label: String,
    val path: String,
)
