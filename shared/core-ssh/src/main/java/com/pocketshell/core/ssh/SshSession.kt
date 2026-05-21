package com.pocketshell.core.ssh

import kotlinx.coroutines.Job

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

    /** Disconnect and free all resources. Idempotent. */
    override fun close()
}
