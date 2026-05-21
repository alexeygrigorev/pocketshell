package com.pocketshell.core.ssh

/**
 * Handle to an active local-to-remote SSH port forward.
 *
 * This is the public surface only — the implementation lives in the
 * `core-portfwd` module (issue #5). Keeping the interface here lets
 * [SshSession.openLocalPortForward] have a stable return type that
 * downstream code can program against today, ahead of #5 landing.
 *
 * Semantics mirror `ssh -L <localPort>:<remoteHost>:<remotePort>`.
 */
public interface SshPortForward : AutoCloseable {

    /** Loopback port on the *local* machine the forward listens on. */
    public val localPort: Int

    /** Host the remote end of the channel connects to, from the SSH server's perspective. */
    public val remoteHost: String

    /** Port the remote end of the channel connects to, from the SSH server's perspective. */
    public val remotePort: Int

    /** True while the underlying channel is open and accepting connections. */
    public val isActive: Boolean

    /** Bytes pushed from local clients out through the SSH channel. */
    public val bytesForwarded: Long

    /** Bytes pulled from the SSH channel back to local clients. */
    public val bytesReceived: Long

    /** Tear the forward down. Idempotent. */
    override fun close()
}
