package com.pocketshell.core.ssh

import kotlinx.coroutines.Job
import java.io.File
import java.io.InputStream

public data class SshUploadProgress(
    val bytesTransferred: Long,
    val totalBytes: Long,
)

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
     * Why this session's transport went down (issue #969 — reconnect
     * observability). [SshSessionCloseCause.KeepaliveDead] when the always-on
     * transport keepalive (#945) declared the peer dead and closed the
     * transport; [SshSessionCloseCause.Unknown] otherwise (an ordinary
     * reader-EOF drop, an explicit teardown, or a session that never died).
     *
     * The [SshLeaseManager] reads this when it stamps a lease `Closed` event so
     * a keepalive-driven drop is NAMED (`keepalive_dead`) instead of surfacing
     * as an anonymous `lease_down` — the exact attribution ambiguity #964 turns
     * on. This is a pass-through value read by the lease layer; core-ssh never
     * reaches up into the app's `ReconnectCauseTrail` (no layering violation).
     *
     * Has a default body so the many bespoke per-test [SshSession] fakes don't
     * all have to override it; only [RealSshSession] flips it on keepalive death.
     */
    public val closeCause: SshSessionCloseCause
        get() = SshSessionCloseCause.Unknown

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

    public suspend fun uploadFile(
        file: File,
        remotePath: String,
        onProgress: ((SshUploadProgress) -> Unit)?,
    ): String = uploadFile(file, remotePath)

    /**
     * Read the contents of a remote file at [remotePath] into memory.
     *
     * The path is resolved by the remote login shell, so `~`-relative and
     * `$VAR`-relative paths are expanded server-side. The transfer streams
     * the raw bytes over an `exec` channel running `cat`, so it is
     * binary-safe (no charset round-trip, no line-ending mangling).
     *
     * [maxBytes] is a hard ceiling. The remote file size is probed first;
     * if it exceeds [maxBytes] the call throws [SshFileTooLargeException]
     * *without* streaming the bytes, so a multi-gigabyte file never lands
     * in the JVM heap. The cap is also enforced while reading (defence in
     * depth against a file that grows between the size probe and the read,
     * or a remote that ignores the size probe): if the stream delivers more
     * than [maxBytes] the read is aborted and [SshFileTooLargeException] is
     * thrown.
     *
     * Throws [SshFileNotFoundException] when the remote path does not exist
     * or is not a regular file, [SshFileTooLargeException] when it exceeds
     * [maxBytes], and [SshException] on transport-level errors.
     *
     * This is a blocking call wrapped to play well with coroutines via
     * `kotlinx.coroutines.Dispatchers.IO`.
     *
     * Used by the in-app file viewer (issue #497) to fetch a server file
     * for image / text preview.
     *
     * Has a default body that throws [NotImplementedError] so the many
     * bespoke per-test [SshSession] fakes don't all have to override it;
     * the production [RealSshSession] provides the real SFTP/`cat` read.
     */
    public suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray =
        throw NotImplementedError("downloadFile is only implemented by RealSshSession")

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

    public suspend fun uploadStream(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
        onProgress: ((SshUploadProgress) -> Unit)?,
    ): String = uploadStream(input, length, name, remotePath)

    /**
     * List the entries of remote directory [remotePath] (issue #528 — file
     * explorer).
     *
     * Implemented over a structured `exec` (`find -maxdepth 1` + `stat`) rather
     * than SFTP: the Alpine fixtures and many minimal OpenSSH servers ship
     * `openssh-server` without the separate `openssh-sftp-server` package, so
     * the SFTP subsystem isn't available. The exec route only needs a POSIX
     * shell plus `find`/`stat`, the same baseline [downloadFile] relies on.
     *
     * Returns a [RemoteListing]: the directory's [RemoteEntry] rows (name +
     * type + size + optional mtime) and a `truncated` flag set when the listing
     * exceeded [maxEntries] and was capped. The directory's own entry and any
     * `.`/`..` are filtered out — the explorer renders its own parent ("..")
     * affordance from the path.
     *
     * The path is resolved by the remote login shell, so `~`-relative and
     * `$VAR`-relative paths are expanded server-side and a relative path lands
     * under `$HOME`.
     *
     * Throws:
     *  - [SshFileNotFoundException] when the path does not exist.
     *  - [SshNotADirectoryException] when the path exists but is a regular file.
     *  - [SshPermissionDeniedException] when the directory is not readable.
     *  - [SshException] on any other shell / transport error.
     *
     * This is a blocking call wrapped to play well with coroutines via
     * `kotlinx.coroutines.Dispatchers.IO`.
     *
     * Has a default body that throws [NotImplementedError] so the many bespoke
     * per-test [SshSession] fakes don't all have to override it; the production
     * [RealSshSession] provides the real listing.
     */
    public suspend fun listDirectory(
        remotePath: String,
        maxEntries: Int = DEFAULT_MAX_LIST_ENTRIES,
    ): RemoteListing =
        throw NotImplementedError("listDirectory is only implemented by RealSshSession")

    /**
     * Send ONE `keepalive@openssh.com` SSH transport keepalive global request
     * and await its reply (issue #945 — the Terminus-parity transport keepalive).
     *
     * Returns `true` on ANY reply — including the mandatory
     * `SSH_MSG_REQUEST_FAILURE` an OpenSSH server sends for the (intentionally
     * unimplemented) `keepalive@openssh.com` request type, which STILL proves the
     * peer is alive, exactly as OpenSSH's own `ServerAliveInterval` treats it.
     * Returns `false` on a transport error / timeout / no reply (the peer is
     * dead or the link is down).
     *
     * The production [RealSshSession] routes this through the single-writer
     * [TransportDispatcher] (`dispatcher.run { ... }`), so the keepalive is just
     * another FIFO-serialized op — it CANNOT race a KEX/rekey boundary or a
     * channel open the way sshj's removed background `KeepAliveRunner` did (the
     * #847 corruption). This is the safe successor to that removed writer.
     *
     * Has a default body that throws [NotImplementedError] so the many bespoke
     * per-test [SshSession] fakes don't all have to override it; only
     * [RealSshSession] and the keepalive loop drive it.
     *
     * This is a blocking call wrapped to play well with coroutines.
     */
    public suspend fun sendKeepAlive(): Boolean =
        throw NotImplementedError("sendKeepAlive is only implemented by RealSshSession")

    /**
     * Issue #964 — the single coherent liveness budget. True iff the always-on
     * transport keepalive (`TransportKeepAlive`, #945) has observed INBOUND
     * transport activity (a keepalive reply, or any other server bytes) within its
     * ride-through window (`TransportKeepAlive.RIDE_THROUGH_BUDGET_MS`, ~90s) —
     * i.e. the transport is PROVABLY still alive RIGHT NOW.
     *
     * The app-level `com.pocketshell.core.connection.LivenessProbe` (#927) reads
     * this to DEFER its own redial while the keepalive is still successfully riding
     * through. The two mechanisms used to run on MISMATCHED budgets — the probe
     * declared the channel dead at ~48s while the keepalive was designed to ride
     * through to ~90s — so on a slow-but-live link the probe force-redialed BEFORE
     * the keepalive could prove the link alive, a spurious reconnect on a fine
     * link (the v0.4.17 coordination bug). Deferring to this single
     * transport-liveness oracle makes the keepalive the death authority for
     * transport liveness, so the probe never redials a link the keepalive is still
     * proving alive. The ride-through budget lives entirely in the transport layer
     * (`TransportKeepAlive`) so there is ONE coherent number, not two competing
     * ones.
     *
     * Default body returns `false` (no keepalive signal available) so the many
     * per-test [SshSession] fakes need not override it; only [RealSshSession]
     * answers off the keepalive's real activity timestamp.
     */
    public fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = false

    /** Disconnect and free all resources. Idempotent. */
    override fun close()

    public companion object {
        /**
         * Default cap on a single [listDirectory] call. A few thousand rows is
         * already more than a human will scroll; capping here keeps a
         * pathological directory (a build cache with 100k files) from stalling
         * the fetch and the lazy list. The UI surfaces a "truncated" note when
         * the cap is hit so the listing is never silently incomplete.
         */
        public const val DEFAULT_MAX_LIST_ENTRIES: Int = 5_000
    }
}

/**
 * Why an [SshSession]'s transport went down (issue #969 — reconnect
 * observability). Read by [SshLeaseManager] when it stamps a lease `Closed`
 * event so a keepalive-driven drop is NAMED rather than surfacing as an
 * anonymous disconnect.
 */
public enum class SshSessionCloseCause {
    /**
     * The always-on transport keepalive (#945) declared the peer dead after
     * [TransportKeepAlive.DEFAULT_COUNT_MAX] consecutive missed replies and
     * closed the dead transport. This is the proactive silent-drop detection
     * the maintainer can't currently see because it surfaces as an anonymous
     * `lease_down` (#964).
     */
    KeepaliveDead,

    /**
     * No specific attribution: an ordinary reader-EOF drop, an explicit
     * teardown, or a still-live session. The default for every session that did
     * not die via the keepalive watchdog.
     */
    Unknown,
}
