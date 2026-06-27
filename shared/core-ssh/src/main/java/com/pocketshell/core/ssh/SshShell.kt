package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.InputStream
import java.io.OutputStream

/**
 * Handle to a remote interactive shell opened via [SshSession.startShell].
 *
 * Wraps an SSH "session" channel that the server has joined to its login
 * shell (after `allocateDefaultPTY` + `startShell` on the underlying
 * transport). The three streams below mirror the standard Unix `stdio`
 * triple from the shell's perspective:
 *
 * - [stdin]  — bytes written here become the shell's standard input
 *              (typed keystrokes, IME commits, paste payloads, etc.).
 * - [stdout] — bytes the shell prints to its standard output, plus any
 *              data the PTY merges in. Read blocks until bytes are
 *              available; returns `-1` on remote close.
 * - [stderr] — bytes the shell prints to its standard error. With a PTY
 *              allocated this is usually empty (the kernel merges stderr
 *              into stdout); kept on the interface for parity with the
 *              underlying SSH channel and for non-PTY callers.
 *
 * The streams stay valid until [close] is invoked or the remote shell
 * exits on its own (e.g. the user types `exit`). Reading from a closed
 * stream returns `-1`; writing to one raises `IOException`.
 *
 * `core-ssh`'s implementation goal is to keep this interface free of
 * `net.schmizz.sshj.*` types so downstream modules (notably `:app`) can
 * program against the shell channel without taking a direct dependency on
 * the sshj client library.
 */
public interface SshShell : AutoCloseable {

    /** Standard input of the remote shell. Caller-owned; do NOT close — use [close] on the shell instead. */
    public val stdin: OutputStream

    /** Standard output of the remote shell. Caller-owned; do NOT close — use [close] on the shell instead. */
    public val stdout: InputStream

    /**
     * Standard error of the remote shell. Usually empty when a PTY is
     * allocated (the kernel merges stderr into stdout); still exposed so
     * non-PTY callers can read it independently.
     */
    public val stderr: InputStream

    /**
     * Coroutine-friendly stdin write for control-channel callers that need
     * cancellation to unpark a queued/blocking write. The default preserves the
     * blocking-stream contract for test doubles and simple implementations; the
     * sshj-backed shell overrides this to route through its suspending
     * transport dispatcher without parking a caller IO thread behind the
     * dispatcher's mutex.
     */
    public suspend fun writeStdin(bytes: ByteArray) {
        runInterruptible(Dispatchers.IO) {
            stdin.write(bytes)
            stdin.flush()
        }
    }

    /**
     * Tear down the shell channel. Idempotent. Subsequent reads on the
     * [stdout] / [stderr] streams return `-1`; writes to [stdin] raise
     * `IOException`. Does NOT close the parent [SshSession] — the session
     * remains usable for further exec / tail / port-forward / shell calls.
     */
    override fun close()

    /**
     * Resize the remote PTY for this shell. Implementations that do not own
     * a PTY may ignore the request.
     */
    public fun resizePty(columns: Int, rows: Int) {}
}
