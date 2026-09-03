package com.pocketshell.next.files

/**
 * Absolute POSIX path arithmetic for the remote file screens (rewrite task
 * P-3a).
 *
 * Pure Kotlin, no Android, no SFTP: the explorer's "go up", "open this folder",
 * "tap a breadcrumb" and "type a path" all reduce to the four functions here, so
 * there is exactly one implementation of "what is the parent of `/`" and one
 * test for it.
 *
 * The old client's equivalent lived as four private helpers inside a 681-line
 * ViewModel plus a separate 171-line `RemotePathResolver`; the resolver existed
 * because paths arrived from terminal taps in every shape (`~`, `./x`,
 * `../x`, relative). Here the only producers are [com.pocketshell.core.transport.SftpEntry.path]
 * (already absolute and server-canonical) and the user's own typed input, so the
 * whole job is normalisation plus join/parent.
 *
 * Contract: every value this object returns is absolute, starts with `/`, and
 * carries no trailing slash — except the root, which is exactly `"/"`.
 */
object RemotePath {

    const val ROOT: String = "/"

    /**
     * Canonicalises [path]: collapses repeated separators, resolves `.` and
     * `..` segments, drops a trailing slash, and prefixes `/` when the input was
     * relative.
     *
     * A blank input is [ROOT] rather than an error — a screen opened with no
     * path argument at all should show *something*, and the root is the one
     * directory every host has. `..` above the root is clamped to the root, the
     * way `cd /..` behaves.
     */
    fun normalize(path: String): String {
        val segments = ArrayDeque<String>()
        path.split('/').forEach { raw ->
            when (raw) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeLast()
                else -> segments.addLast(raw)
            }
        }
        return if (segments.isEmpty()) ROOT else segments.joinToString(separator = "/", prefix = "/")
    }

    /**
     * The absolute path of [name] inside directory [dir].
     *
     * [name] is treated as a single child name, not as a path: a caller joining
     * a server-supplied entry name never wants `..` to escape the directory it
     * is browsing. It is still normalised, so a name containing separators
     * resolves rather than producing a double slash.
     */
    fun join(dir: String, name: String): String {
        val base = normalize(dir)
        val leaf = name.trim('/')
        if (leaf.isEmpty()) return base
        return normalize(if (base == ROOT) "/$leaf" else "$base/$leaf")
    }

    /** The containing directory of [path]. The root is its own parent. */
    fun parent(path: String): String {
        val normalized = normalize(path)
        if (normalized == ROOT) return ROOT
        val cut = normalized.lastIndexOf('/')
        return if (cut <= 0) ROOT else normalized.substring(0, cut)
    }

    /** Last segment of [path] — the display name. The root renders as `/`. */
    fun nameOf(path: String): String {
        val normalized = normalize(path)
        if (normalized == ROOT) return ROOT
        return normalized.substringAfterLast('/')
    }

    /**
     * Breadcrumb trail for [path]: one entry per ancestor including the root and
     * [path] itself, each carrying the label to render and the absolute path to
     * navigate to.
     *
     * `/home/alexey/git` becomes `[("/", "/"), ("home", "/home"),
     * ("alexey", "/home/alexey"), ("git", "/home/alexey/git")]`.
     */
    fun crumbs(path: String): List<Crumb> {
        val normalized = normalize(path)
        val trail = mutableListOf(Crumb(label = ROOT, path = ROOT))
        if (normalized == ROOT) return trail
        var accumulated = ""
        normalized.split('/').filter { it.isNotEmpty() }.forEach { segment ->
            accumulated += "/$segment"
            trail += Crumb(label = segment, path = accumulated)
        }
        return trail
    }

    /** One breadcrumb: the [label] shown and the absolute [path] a tap opens. */
    data class Crumb(val label: String, val path: String)
}
