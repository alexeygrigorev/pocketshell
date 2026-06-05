package com.pocketshell.core.ssh

/**
 * One entry in a remote directory listing — issue #528 (SFTP file explorer).
 *
 * Produced by [SshSession.listDirectory] from a single SFTP `readdir` round
 * trip. Carries only what the file explorer needs to render a compact row and
 * decide navigation: the [name] (basename, no path), the [type], the file
 * [sizeBytes] (0 for non-regular entries), and the optional [modifiedEpochSec]
 * modification time when the server reports one.
 *
 * The explorer joins [name] onto the directory it asked for to build the next
 * path; `core-ssh` deliberately does not return absolute paths here so a single
 * listing can't smuggle in path traversal beyond what the caller asked for.
 */
public data class RemoteEntry(
    /** Basename of the entry within its parent directory (no slashes). */
    public val name: String,
    /** File kind, resolved from the SFTP file mode. */
    public val type: Type,
    /** Size in bytes for a regular file; 0 for directories / specials. */
    public val sizeBytes: Long,
    /** Modification time as Unix epoch seconds, or null when unavailable. */
    public val modifiedEpochSec: Long?,
) {
    /**
     * The kind of a [RemoteEntry], mapped from the SFTP `FileMode.Type`.
     *
     * [SYMLINK] is reported as-is (the explorer does not resolve the target —
     * it shows it as a link and lets the user tap it; descending or opening
     * a symlink resolves server-side on the next listing / download). [OTHER]
     * covers FIFOs, sockets, and device nodes, which the explorer renders but
     * treats as non-navigable, non-openable.
     */
    public enum class Type {
        DIRECTORY,
        FILE,
        SYMLINK,
        OTHER,
    }

    public companion object {
        /** A directory sorts before a file; within a kind, case-insensitive by name. */
        public val FOLDERS_FIRST: Comparator<RemoteEntry> =
            compareByDescending<RemoteEntry> { it.type == Type.DIRECTORY }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}

/**
 * Result of [SshSession.listDirectory]: the directory's entries plus a
 * [truncated] flag set when the listing was capped at the caller's limit so the
 * UI can show a "showing first N of more" note instead of silently hiding
 * entries.
 */
public data class RemoteListing(
    public val entries: List<RemoteEntry>,
    public val truncated: Boolean,
)

/**
 * Thrown by [SshSession.listDirectory] when the remote path is not a directory
 * (e.g. the user navigated into something that turned out to be a file). A
 * subclass of [SshException] so existing catch-all sites keep working; the
 * explorer catches this to show a clear "not a directory" state.
 */
public class SshNotADirectoryException(
    public val remotePath: String,
    cause: Throwable? = null,
) : SshException("Not a directory on remote: $remotePath", cause)

/**
 * Thrown by [SshSession.listDirectory] when the remote refuses access to the
 * directory (SFTP `PERMISSION_DENIED`). A subclass of [SshException]; the
 * explorer catches this to show a clear "permission denied" state.
 */
public class SshPermissionDeniedException(
    public val remotePath: String,
    cause: Throwable? = null,
) : SshException("Permission denied on remote: $remotePath", cause)
