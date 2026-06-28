package com.pocketshell.core.ssh

/**
 * Single exception type surfaced from the `core-ssh` module.
 *
 * sshj throws a variety of checked and unchecked exceptions
 * ([net.schmizz.sshj.userauth.UserAuthException],
 * [net.schmizz.sshj.transport.TransportException],
 * [java.io.IOException], ...). Callers shouldn't have to fan out on the full
 * sshj exception hierarchy — we wrap them all into [SshException] with the
 * underlying cause preserved on [Throwable.cause].
 */
public open class SshException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown by [SshSession.downloadFile] when the remote path does not exist or
 * is not a regular file. A subclass of [SshException] so existing catch-all
 * `catch (e: SshException)` sites keep working; callers that want to render a
 * friendly "no such file" message can catch this specifically.
 */
public class SshFileNotFoundException(
    public val remotePath: String,
    cause: Throwable? = null,
) : SshException("No such file on remote: $remotePath", cause)

/**
 * Thrown by [SshSession.downloadFile] when the remote file is larger than the
 * caller's `maxBytes` ceiling. Carries the observed [sizeBytes] (or `-1` when
 * the size was only discovered mid-stream) and the [maxBytes] limit so the UI
 * can render an exact "too large to preview" message.
 */
public class SshFileTooLargeException(
    public val remotePath: String,
    public val sizeBytes: Long,
    public val maxBytes: Long,
    cause: Throwable? = null,
) : SshException(
    "Remote file $remotePath is too large to preview " +
        "(${if (sizeBytes >= 0) "$sizeBytes bytes" else "size unknown"}, limit $maxBytes bytes)",
    cause,
)

/**
 * Thrown by [SshSession.exec] when the command's stdout/stderr read makes NO
 * PROGRESS for the per-read no-progress budget (#935 S4-2; #1046). A half-open /
 * wedged transport leaves the blocking `readBytes()` parked forever; this
 * timeout is the boundary bound that turns "the action silently never returns"
 * into a fast, clear, RETRYABLE failure. The session is closed on the way out
 * so the lease pool discards the corpse and re-dials on the next acquire.
 *
 * Issue #1046: [timeoutMs] is now the per-read NO-PROGRESS window, not a
 * whole-call ceiling — a slow-but-progressing exec (bytes still arriving) is
 * NOT bounded, so this is raised only for a genuinely stalled read.
 *
 * A subclass of [SshException] so existing catch-all `catch (e: SshException)`
 * sites keep working; lease-exec retry logic catches it specifically to treat a
 * wedged read as a stale-channel symptom worth healing on a fresh transport.
 */
public class SshExecTimeoutException(
    public val command: String,
    public val timeoutMs: Long,
    cause: Throwable? = null,
) : SshException(
    "exec read made no progress for ${timeoutMs}ms: ${command.takeLast(64)}",
    cause,
)
