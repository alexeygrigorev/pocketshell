package com.pocketshell.app.diagnostics

import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * Mirrors the local reconnect-cause trail to a host-side file
 * `~/.pocketshell/connection-log.jsonl` over the ALREADY-WARM `-CC` lease
 * (issue #969 part 3, wired by #972).
 *
 * Why a host file: the reconnect-cause breadcrumbs ([ReconnectCauseTrail],
 * recorded by default since #969) already live in the on-device rolling JSONL,
 * but the maintainer can't read that without adb / the OS share sheet. Writing
 * the SAME breadcrumbs to a host file lets them open it in PocketShell's own
 * in-app file viewer (no adb, no share) so a real-world drop can be ATTRIBUTED
 * (e.g. `keepalive_dead` vs an anonymous `lease_down`) right on the phone.
 *
 * Reuses the trusted `~/inbox/pocketshell/` write pattern (D21): the write runs
 * on the warm lease via [LeaseSessionExec.withSession] — NO fresh SSH handshake
 * — with a `mkdir -p` exec then an SCP [SshSession.uploadStream]. The destination
 * is the home-relative `~/.pocketshell/` (the in-app file viewer's well-known
 * diagnostics directory), not the inbox.
 *
 * FAIL-SOFT is a hard contract. A diagnostics mirror must NEVER perturb the live
 * connection: any failure (lease unavailable, mkdir denied, upload error) returns
 * [Result.failure] WITHOUT throwing, and the lease helper never closes the warm
 * transport on the write path. A coroutine cancellation still propagates (so a
 * torn-down VM scope cancels cleanly) — it is the only throw that escapes.
 */
object ConnectionLogHostMirror {

    /** Home-relative directory the in-app file viewer surfaces for diagnostics. */
    const val REMOTE_DIR: String = ".pocketshell"

    /** The mirrored connection-log filename inside [REMOTE_DIR]. */
    const val REMOTE_FILENAME: String = "connection-log.jsonl"

    /** Absolute (home-relative) remote path of the mirrored log. */
    const val REMOTE_PATH: String = "$REMOTE_DIR/$REMOTE_FILENAME"

    /** Fixed filename for the opt-in, full connection-journal pull (#1710). */
    const val JOURNAL_REMOTE_FILENAME: String = "connection-journal.jsonl"

    /** Fixed home-relative destination for the opt-in replay archive. */
    const val JOURNAL_REMOTE_PATH: String = "$REMOTE_DIR/$JOURNAL_REMOTE_FILENAME"

    /**
     * Write [jsonl] to `~/.pocketshell/connection-log.jsonl` on the host behind
     * [target], reusing [leaseManager]'s warm transport. Returns the absolute
     * remote path on success, or a fail-soft [Result.failure] on any error.
     *
     * A blank [jsonl] is a no-op success ([Result.success] with `null`): there
     * are no breadcrumbs to mirror yet, and writing an empty file would only
     * confuse the file viewer.
     */
    suspend fun mirror(
        leaseManager: SshLeaseManager,
        target: LeaseSessionTarget,
        jsonl: String,
    ): Result<String?> {
        if (jsonl.isBlank()) return Result.success(null)
        return try {
            LeaseSessionExec.withSession(leaseManager, target) { session ->
                writeOverSession(
                    session = session,
                    jsonl = jsonl,
                    remoteFilename = REMOTE_FILENAME,
                    remotePath = REMOTE_PATH,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // withSession already wraps its own failures in Result.failure; this
            // catch only covers an unexpected throw from building the borrow so
            // the contract "never throws (except cancellation)" holds end-to-end.
            Result.failure(t)
        }
    }

    /**
     * Write the complete replay journal over a session the caller already holds.
     * There is intentionally no lease-manager/acquire overload: #1710 is
     * tap-time warm-only and must never cold-dial or retarget.
     */
    suspend fun mirrorConnectionJournal(
        session: SshSession,
        jsonl: String,
    ): Result<String?> {
        if (jsonl.isBlank()) return Result.success(null)
        return try {
            Result.success(
                writeOverSession(
                    session = session,
                    jsonl = jsonl,
                    remoteFilename = JOURNAL_REMOTE_FILENAME,
                    remotePath = JOURNAL_REMOTE_PATH,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    private suspend fun writeOverSession(
        session: SshSession,
        jsonl: String,
        remoteFilename: String,
        remotePath: String,
    ): String {
        // The exec channel drives `mkdir -p $HOME/.pocketshell` (SFTP's per-segment
        // mkdir is more fragile against minimal OpenSSH images — the ShareUploader
        // inbox pattern uses the same exec route).
        val mk = session.exec("mkdir -p \"\$HOME/$REMOTE_DIR\"")
        if (mk.exitCode != 0) {
            throw ConnectionLogHostMirrorException(
                "mkdir ~/$REMOTE_DIR failed: ${mk.stderr.ifBlank { mk.stdout.trim() }}",
            )
        }
        val bytes = jsonl.toByteArray(StandardCharsets.UTF_8)
        return session.uploadStream(
            input = ByteArrayInputStream(bytes),
            length = bytes.size.toLong(),
            name = remoteFilename,
            remotePath = remotePath,
        )
    }
}

/**
 * Thrown internally by [ConnectionLogHostMirror] when a host write step fails;
 * always captured into the fail-soft [Result.failure] (never escapes to the
 * caller as a throw).
 */
class ConnectionLogHostMirrorException(message: String) : Exception(message)
