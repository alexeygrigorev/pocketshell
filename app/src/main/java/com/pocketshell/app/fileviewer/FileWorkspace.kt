package com.pocketshell.app.fileviewer

/**
 * Issue #1715 — host-durable open-file workspace.
 *
 * Tabs are keyed by the resolved absolute remote path. Order is insertion
 * order (activation does not reshuffle the strip). Recency drives LRU
 * eviction of inactive tabs past [MAX_OPEN_TABS]. The Android client
 * hydrates this once over the warm lease and upserts on open/activate/close;
 * the host registry is the source of truth.
 */
data class OpenFileTab(
    val absolutePath: String,
    val lastActivatedAtMillis: Long,
)

data class FileWorkspace(
    val orderedTabs: List<OpenFileTab>,
    val activePath: String?,
) {
    companion object {
        const val MAX_OPEN_TABS: Int = 12
        val Empty: FileWorkspace = FileWorkspace(orderedTabs = emptyList(), activePath = null)
    }
}

data class FileWorkspaceResult(
    val workspace: FileWorkspace,
    val available: Boolean,
) {
    companion object {
        val Empty: FileWorkspaceResult =
            FileWorkspaceResult(workspace = FileWorkspace.Empty, available = true)
        val Unavailable: FileWorkspaceResult =
            FileWorkspaceResult(workspace = FileWorkspace.Empty, available = false)
    }
}

/**
 * Acknowledgement state for the host-durable workspace write queue.
 *
 * The write is process-scoped rather than ViewModel-scoped, so activity
 * recreation cannot cancel a write that is already queued for persistence.
 * Failures remain observable to both tests and the UI instead of being
 * swallowed by a fire-and-forget coroutine.
 */
sealed interface FileWorkspaceWriteState {
    data object Idle : FileWorkspaceWriteState
    data class Pending(val workspace: FileWorkspace) : FileWorkspaceWriteState
    data class Saved(val workspace: FileWorkspace) : FileWorkspaceWriteState
    data class Failed(
        val workspace: FileWorkspace,
        val message: String,
    ) : FileWorkspaceWriteState
}

sealed class PendingTabAction {
    data object Back : PendingTabAction()
    data class Switch(val tab: OpenFileTab) : PendingTabAction()
    data class Close(val tab: OpenFileTab) : PendingTabAction()
}

object FileWorkspaceReducer {

    /**
     * Canonicalise an absolute Unix path. Relative, blank, and non-string
     * inputs return null so they cannot enter the durable set.
     */
    fun normalizeAbsolutePath(raw: String?): String? {
        val path = raw?.trim().orEmpty()
        if (!path.startsWith("/")) return null
        val parts = ArrayList<String>()
        for (part in path.split('/')) {
            when (part) {
                "", "." -> continue
                ".." -> {
                    // The remote root is the boundary: `/../../file` must
                    // not escape it or leave a literal `..` in the durable
                    // identity.
                    if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                }
                else -> parts.add(part)
            }
        }
        return if (parts.isEmpty()) "/" else "/" + parts.joinToString("/")
    }

    /**
     * Add or promote [absolutePath] as the active tab. Existing identity keeps
     * its visual slot; a new path appends. Past [FileWorkspace.MAX_OPEN_TABS]
     * the least-recently-activated *inactive* tab is evicted — never the
     * active/new one.
     */
    fun open(workspace: FileWorkspace, absolutePath: String, nowMillis: Long): FileWorkspace {
        val path = normalizeAbsolutePath(absolutePath) ?: return workspace
        val existing = workspace.orderedTabs.indexOfFirst { it.absolutePath == path }
        val tabs = workspace.orderedTabs.toMutableList()
        if (existing >= 0) {
            tabs[existing] = OpenFileTab(path, nowMillis)
        } else {
            tabs.add(OpenFileTab(path, nowMillis))
        }
        return cap(FileWorkspace(orderedTabs = tabs, activePath = path), keepPath = path)
    }

    /** Mark [absolutePath] active without moving it. No-op when the tab is unknown. */
    fun activate(workspace: FileWorkspace, absolutePath: String, nowMillis: Long): FileWorkspace {
        val path = normalizeAbsolutePath(absolutePath) ?: return workspace
        val index = workspace.orderedTabs.indexOfFirst { it.absolutePath == path }
        if (index < 0) return workspace
        val tabs = workspace.orderedTabs.toMutableList()
        tabs[index] = OpenFileTab(path, nowMillis)
        return FileWorkspace(orderedTabs = tabs, activePath = path)
    }

    /**
     * Remove [absolutePath]. Closing the active tab selects the nearest tab
     * to the right, otherwise the nearest left. Closing the last tab yields
     * [FileWorkspace.Empty].
     */
    fun close(workspace: FileWorkspace, absolutePath: String): FileWorkspace {
        val path = normalizeAbsolutePath(absolutePath) ?: return workspace
        val index = workspace.orderedTabs.indexOfFirst { it.absolutePath == path }
        if (index < 0) return workspace
        val remaining = workspace.orderedTabs.filterIndexed { i, _ -> i != index }
        if (remaining.isEmpty()) return FileWorkspace.Empty
        val nextActive = if (workspace.activePath == path) {
            remaining.getOrNull(index)?.absolutePath
                ?: remaining.getOrNull(index - 1)?.absolutePath
                ?: remaining.last().absolutePath
        } else {
            workspace.activePath
        }
        return FileWorkspace(orderedTabs = remaining, activePath = nextActive)
    }

    /**
     * Recover a host payload: drop malformed/relative rows, dedupe by path
     * (first slot, later recency), recover a missing active path, cap at 12.
     */
    fun recover(
        tabs: List<OpenFileTab>,
        activePath: String?,
    ): FileWorkspace {
        val byPath = LinkedHashMap<String, OpenFileTab>()
        for (tab in tabs) {
            val path = normalizeAbsolutePath(tab.absolutePath) ?: continue
            val previous = byPath[path]
            if (previous == null || tab.lastActivatedAtMillis >= previous.lastActivatedAtMillis) {
                // LinkedHashMap keeps the first insertion slot on in-place replace.
                byPath[path] = OpenFileTab(path, tab.lastActivatedAtMillis)
            }
        }
        val ordered = byPath.values.toList()
        if (ordered.isEmpty()) return FileWorkspace.Empty
        val active = normalizeAbsolutePath(activePath)
            ?.takeIf { path -> ordered.any { it.absolutePath == path } }
            ?: ordered.maxBy { it.lastActivatedAtMillis }.absolutePath
        return cap(FileWorkspace(orderedTabs = ordered, activePath = active), keepPath = active)
    }

    /**
     * Basename, unless two open tabs share it — then the shortest unique
     * parent suffix (`src/App.kt` vs `test/App.kt`).
     */
    fun uniqueLabels(tabs: List<OpenFileTab>): Map<String, String> {
        val groups = tabs.groupBy { basename(it.absolutePath) }
        val out = LinkedHashMap<String, String>(tabs.size)
        for (tab in tabs) {
            val base = basename(tab.absolutePath)
            val siblings = groups[base].orEmpty()
            out[tab.absolutePath] = if (siblings.size <= 1) {
                base
            } else {
                shortestUniqueSuffix(
                    path = tab.absolutePath,
                    others = siblings.map { it.absolutePath },
                )
            }
        }
        return out
    }

    private fun cap(workspace: FileWorkspace, keepPath: String): FileWorkspace {
        if (workspace.orderedTabs.size <= FileWorkspace.MAX_OPEN_TABS) return workspace
        val dropCount = workspace.orderedTabs.size - FileWorkspace.MAX_OPEN_TABS
        val evictable = workspace.orderedTabs
            .filter { it.absolutePath != keepPath }
            .sortedBy { it.lastActivatedAtMillis }
            .take(dropCount)
            .map { it.absolutePath }
            .toSet()
        val kept = workspace.orderedTabs.filter { it.absolutePath !in evictable }
        val active = workspace.activePath?.takeIf { path -> kept.any { it.absolutePath == path } }
            ?: kept.maxByOrNull { it.lastActivatedAtMillis }?.absolutePath
        return FileWorkspace(orderedTabs = kept, activePath = active)
    }

    private fun basename(path: String): String =
        path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    private fun shortestUniqueSuffix(path: String, others: List<String>): String {
        val parts = path.trim('/').split('/')
        for (n in 1..parts.size) {
            val suffix = parts.takeLast(n).joinToString("/")
            val matches = others.count { other ->
                other.trim('/').split('/').takeLast(n).joinToString("/") == suffix
            }
            if (matches == 1) return suffix
        }
        return path
    }
}
