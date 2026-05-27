package com.pocketshell.core.ssh

import kotlinx.coroutines.Job
import java.io.File
import java.io.InputStream

/**
 * Live SSH connection to a single host. Obtain instances via
 * [SshConnection.connect].
 *
 * Implementations are thread-safe for invocation across the public methods.
 * The session owns the underlying transport, so calling [close] (or
 * [AutoCloseable.use]) tears down the TCP connection and any open channels.
 */
public interface SshSession : AutoCloseable {

    /** True while the underlying transport is connected. */
    public val isConnected: Boolean

    /**
     * Run [command] over a single `exec` channel and wait for it to finish.
     *
     * Returns the full stdout/stderr/exit-code triple. Does NOT throw on
     * non-zero exit codes — callers decide what counts as failure. Throws
     * [SshException] only on transport-level errors (channel open failure,
     * I/O interrupted mid-stream, session closed underneath us).
     *
     * This is a blocking call wrapped to play well with coroutines via
     * `kotlinx.coroutines.Dispatchers.IO`.
     */
    public suspend fun exec(command: String): ExecResult

    /**
     * Stream the tail of a remote file line by line, calling [onLine] for
     * each new line as it arrives. Equivalent to `tail -F path` over a
     * persistent exec channel.
     *
     * Returns a [Job] that the caller can [Job.cancel] to stop the tail and
     * release the underlying channel. The job completes when either the
     * caller cancels it or the remote `tail` exits (e.g. file deleted on
     * non-`-F` semantics, transport drops).
     */
    public fun tail(path: String, onLine: (String) -> Unit): Job

    /**
     * Stream new lines from [path], starting after [fromLineExclusive].
     * A value of 0 starts at the beginning; null-equivalent callers should
     * use [tail]. This lets callers take a line-count snapshot before an
     * initial read and then follow from that exact boundary without the
     * `read file` + `tail -n 0` race.
     */
    public fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job =
        tail(path, onLine)

    /**
     * Open a local-to-remote port forward: traffic to `127.0.0.1:[localPort]`
     * on the *client* machine is tunnelled through this SSH session to
     * `[remoteHost]:[remotePort]`, resolved from the SSH server's side.
     *
     * Signature only — the implementation ships with the `core-portfwd`
     * module (issue #5). Calling this today throws
     * [NotImplementedError]; the type is exposed early so downstream code
     * can program against the eventual return type.
     */
    public fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward

    /**
     * Open a remote interactive shell. Allocates a default PTY on a new
     * session channel and binds it to the user's login shell. Returns an
     * [SshShell] whose [SshShell.stdin] / [SshShell.stdout] / [SshShell.stderr]
     * are ordinary blocking JDK streams pointing at the remote shell's stdio.
     *
     * Closing the returned [SshShell] (or `use`-ing it) tears down only
     * the shell channel — the parent [SshSession] stays connected and can
     * still be used for further [exec] / [tail] / [openLocalPortForward] /
     * `startShell` calls.
     *
     * Throws [SshException] on transport-level failure (channel open
     * failure, PTY allocation refused by the server, shell start refused).
     */
    public fun startShell(): SshShell

    /**
     * Upload a local [file] to [remotePath] via SCP. The remote path is
     * absolute (or tilde-expanded by the remote login shell — SCP runs
     * the receiving command through the user's default shell). Returns
     * the remote path that was written so the caller doesn't have to
     * round-trip a separate query.
     *
     * Used by the Android share-target flow (issue #138) to land files
     * at `~/inbox/pocketshell/<timestamp>-<sanitised-name>`. Throws
     * [SshException] on transport errors (connection lost, auth lost
     * mid-transfer), SCP-protocol errors (permission denied, no such
     * directory), or local I/O errors reading [file].
     *
     * This is a blocking call wrapped to play well with coroutines via
     * `kotlinx.coroutines.Dispatchers.IO`.
     */
    public suspend fun uploadFile(file: File, remotePath: String): String

    /**
     * Upload [length] bytes from [input] to [remotePath] via SCP under
     * the display name [name]. Mirrors [uploadFile] but lets the caller
     * stream from a non-file source (Android `ContentResolver`
     * URIs, in-memory text payloads). Returns the remote path that was
     * written.
     *
     * Both [name] and [remotePath] must already be sanitised by the
     * caller (the SCP protocol itself doesn't escape filenames; we
     * rely on `core-ssh`'s callers to pass values that are safe at the
     * shell layer). [length] must be the exact stream length in bytes;
     * SCP needs the size up front and an under-count would leave the
     * remote file truncated.
     *
     * This is a blocking call wrapped to play well with coroutines via
     * `kotlinx.coroutines.Dispatchers.IO`.
     */
    public suspend fun uploadStream(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String

    /** Disconnect and free all resources. Idempotent. */
    override fun close()
}
