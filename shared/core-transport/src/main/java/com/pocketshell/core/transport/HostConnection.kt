package com.pocketshell.core.transport

import kotlinx.coroutines.flow.StateFlow

/**
 * One live SSH connection to one host. Everything above this module talks to a
 * host exclusively through this interface — it is the single seam between the
 * app and sshj.
 *
 * A connection never reconnects itself: once [state] reaches
 * [TransportState.Lost] or [TransportState.Closed] the instance is spent and a
 * new one must be dialled via [HostConnectionFactory].
 */
interface HostConnection {
    val target: HostTarget

    val state: StateFlow<TransportState>

    /**
     * Runs [command] to completion on its own channel and collects its output.
     * Never throws for a non-zero exit; a wall-clock overrun of [timeoutMs]
     * returns [ExecResult.timedOut] instead.
     */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): ExecResult

    /** Opens an interactive PTY channel running [command] at [cols] x [rows]. */
    suspend fun openPty(
        command: String,
        cols: Int,
        rows: Int,
        term: String = "xterm-256color",
    ): PtyChannel

    /** The SFTP channel for this connection. Cached: repeated calls return the same instance. */
    suspend fun sftp(): SftpChannel

    /**
     * Arms a delayed [close] that fires [graceMs] from now unless the returned
     * handle is cancelled first (D21: the app holds no background work beyond
     * this one bounded timer). A second call replaces the pending close.
     */
    fun scheduleGraceClose(graceMs: Long): GraceHandle

    /** Closes the connection and all its channels. Idempotent. */
    suspend fun close()
}

/**
 * Outcome of a dial attempt.
 *
 * [NeedsTrust] is not a failure: the host key needs a user decision. The caller
 * shows the prompt, records the answer via [TrustStore.recordTrusted], and then
 * calls [NeedsTrust.retry] to dial again with the same target.
 */
sealed interface ConnectResult {
    data class Connected(val connection: HostConnection) : ConnectResult

    data class NeedsTrust(
        val decision: TrustDecision,
        val retry: suspend () -> ConnectResult,
    ) : ConnectResult

    data class Failed(val message: String, val cause: Throwable?) : ConnectResult
}

/**
 * The single dial site. Every connection in the app is created here, so host-key
 * trust cannot be bypassed by an alternative code path.
 */
interface HostConnectionFactory {
    suspend fun connect(target: HostTarget, trust: TrustStore): ConnectResult
}
