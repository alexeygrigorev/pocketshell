package com.pocketshell.next.share

import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.core.transport.SftpChannel
import com.pocketshell.next.connect.ConnectionsRegistry
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Uploads one shared item to a host's inbox (rewrite task P-9).
 *
 * ## What replaced what
 *
 * The shipping client's `ShareUploader` opened its OWN short-lived SSH session
 * through the lease manager (`SshLeaseManager.acquire`, a lease purpose string,
 * a passphrase prompt of its own, a `SshSession.use {}` per file). That is the
 * entire class of problem the rewrite deleted: a share landing while a session
 * was attached raced the lease, and a passphrase-protected key made the share
 * sheet grow a second, parallel auth surface.
 *
 * Here there is no session to own. [ConnectionsRegistry.getOrConnect] hands back
 * the one connection the app already has for that host — or dials one if there
 * is none — and the upload is two calls on it: one [HostConnection.exec] to make
 * and resolve the inbox directory, one [SftpChannel.write] for the bytes.
 *
 * ## Destination
 *
 * `~/inbox/pocketshell/<yyyyMMdd-HHmmss>-<sanitised-name>`, the same convention
 * the shipping client established and the maintainer's server-side workflow
 * reads (`AGENTS.md`: files shared from the phone land in
 * `~/inbox/pocketshell/`). It is resolved to an ABSOLUTE path before the write
 * because SFTP does not expand `~` and does not run a login shell — a relative
 * path would land wherever the SFTP subsystem happens to start.
 *
 * ## Collisions
 *
 * The timestamp prefix makes a collision rare but not impossible: sharing four
 * screenshots at once stamps them all in the same second, and two of them can
 * carry the same name. The shipping client overwrote in that case and still
 * reported both as uploaded — a silent data loss. Here the target is probed with
 * [SftpChannel.stat] and a `-2`, `-3`, … suffix is inserted before the extension
 * until the name is free, so N shared files always produce N remote files.
 */
class ShareUploader(
    private val registry: ConnectionsRegistry,
    private val content: ShareContentReader,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Uploads [item] to [hostId]'s inbox, answering the absolute remote path.
     *
     * Never throws: a connect failure, an unreadable source, a refused `mkdir`
     * and a failed write all come back as `Result.failure` carrying a message
     * that is already fit to render — the share surface must never show a stack
     * trace (and, being an exported one-shot activity, must never die of one).
     */
    suspend fun upload(hostId: Long, item: ShareableItem): Result<String> = runCatching {
        // The payload is read BEFORE the connection is touched: an unreadable or
        // over-size source is the sender's problem, and finding it out first
        // avoids opening a transport to say so.
        val payload = content.read(item)

        val connection = when (val result = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> result.connection
            is ConnectResult.NeedsTrust -> throw IOException(
                "This host's key still needs to be confirmed. Open it from the host " +
                    "list to review the key, then share again.",
            )

            is ConnectResult.Failed -> throw IOException(result.message)
        }

        val directory = ensureInboxDirectory(connection)
        val sanitised = FilenameSanitiser.sanitise(
            payload.displayName ?: item.displayName,
            defaultExtension = item.fallbackExtension,
        )
        val sftp = connection.sftp()
        val path = freeRemotePath(
            sftp = sftp,
            directory = directory,
            name = FilenameSanitiser.composeRemoteName(timestamp(now()), sanitised),
        )
        sftp.write(path, payload.bytes)
        path
    }.recoverCatching { error ->
        throw IOException(shareErrorMessage(error), error)
    }

    /**
     * Creates `~/inbox/pocketshell` if needed and answers its ABSOLUTE path.
     *
     * One exec, not three round trips: `mkdir -p` creates the whole chain, and
     * `cd … && pwd` makes the login shell resolve `$HOME` for us — the SFTP
     * channel deliberately has no "home" verb (see [SftpChannel]'s doc: a verb
     * is added when a screen needs one), and doing it per-segment over SFTP
     * would be three round trips to learn what one `pwd` already knows.
     *
     * `$HOME` inside double quotes rather than `~`: tilde expansion does not
     * happen inside quotes, and leaving the path unquoted would break for a user
     * whose home has a space in it.
     */
    private suspend fun ensureInboxDirectory(connection: HostConnection): String {
        val result = connection.exec(
            "mkdir -p \"\$HOME/$INBOX_RELATIVE_PATH\" && cd \"\$HOME/$INBOX_RELATIVE_PATH\" && pwd",
        )
        if (result.timedOut) throw IOException("The host did not answer in time")
        val resolved = result.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("/") }
        if (result.exitCode != 0 || resolved.isNullOrBlank()) {
            val detail = result.stderr.trim().ifBlank { result.stdout.trim() }
                .ifBlank { "exit ${result.exitCode}" }
            throw IOException("Could not create ~/$INBOX_RELATIVE_PATH on the host: $detail")
        }
        return resolved.trimEnd('/').ifEmpty { "/" }
    }

    /**
     * `<directory>/<name>`, with a `-2`/`-3`/… suffix inserted before the
     * extension when something already sits there.
     *
     * Bounded by [MAX_COLLISION_ATTEMPTS] so a pathological directory cannot
     * turn one share into an unbounded stat loop; past the bound the name is
     * given the millisecond clock, which cannot collide with a second-resolution
     * timestamp prefix from the same batch.
     */
    private suspend fun freeRemotePath(
        sftp: SftpChannel,
        directory: String,
        name: String,
    ): String {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        for (attempt in 1..MAX_COLLISION_ATTEMPTS) {
            val candidate = when (attempt) {
                1 -> name
                else -> render("$stem-$attempt", ext)
            }
            val path = "$directory/$candidate"
            if (sftp.stat(path) == null) return path
        }
        return "$directory/${render("$stem-${now()}", ext)}"
    }

    private fun render(stem: String, ext: String): String =
        if (ext.isEmpty()) stem else "$stem.$ext"

    companion object {

        /** Home-relative inbox directory. Absolute form is resolved per host. */
        const val INBOX_RELATIVE_PATH: String = "inbox/pocketshell"

        /** What the UI calls the destination. */
        const val INBOX_DISPLAY_PATH: String = "~/inbox/pocketshell"

        const val TIMESTAMP_PATTERN: String = "yyyyMMdd-HHmmss"

        private const val MAX_COLLISION_ATTEMPTS: Int = 20

        /**
         * The filename timestamp, in the DEVICE's timezone — the user reads it
         * as "the moment I shared this", and their phone is where that moment
         * happened. The host may well be in another zone; that is fine, the
         * name is a label, not a log entry.
         */
        fun timestamp(epochMillis: Long): String =
            SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getDefault() }
                .format(Date(epochMillis))
    }
}

/**
 * A transport/IO failure as the share surface should word it.
 *
 * Narrow on purpose: it recognises the handful of shapes sshj actually produces
 * and otherwise passes the first line of the original message through, capped.
 * The goal is not a translation layer — it is that a JVM stack dump never
 * reaches a notification or a one-shot activity that the user cannot scroll.
 */
internal fun shareErrorMessage(error: Throwable): String {
    val raw = (error.message ?: error.javaClass.simpleName).trim()
    val lower = raw.lowercase(Locale.ROOT)
    return when {
        lower.contains("permission denied") -> raw.replaceFirstChar { it.uppercase() }
        lower.contains("connection refused") -> "Connection refused"
        lower.contains("connection reset") ||
            lower.contains("connection closed") ||
            lower.contains("connection lost") -> "Connection lost during the upload"

        lower.contains("unknown host") || lower.contains("unknownhost") -> "Cannot resolve host"
        lower.contains("timed out") || lower.contains("timeout") -> "Connection timed out"
        lower.contains("auth") -> "Authentication failed"
        else -> raw.lineSequence().firstOrNull()?.take(160)?.ifBlank { null } ?: "Upload failed"
    }
}
