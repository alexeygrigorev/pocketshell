package com.pocketshell.app.proof

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStream

/**
 * Reusable on-device fake [SshSession] that stands in for a host whose
 * `pocketshell` CLI is OLDER than the Android client (epic #848).
 *
 * ## Why this exists (issue #853)
 *
 * The single existing old-host guard ([AgentLaunchVersionMismatchHintE2eTest],
 * #759) inlined its own fake `SshSession` to inject the
 * `No such command 'agent'` probe answer. That seam — "an old host that rejects
 * the new-in-this-release subcommands" — is exactly what the #849 connect-path
 * tree/kind tests and any future version-mismatch proof need. Extracting it to
 * ONE place means every old-host proof drives the SAME seam instead of each
 * re-inventing a one-off fake (the #657-class "narrow proxy" risk).
 *
 * ## What it models
 *
 * The fake records every `exec` command so a test can assert what the gateway
 * issued over the lease (e.g. that no doomed `send-keys` was typed). It answers:
 *
 *  * `pocketshell --version` → the configured [installedVersion] (default
 *    pre-`agent` `0.3.33`).
 *  * The new-in-this-release subcommands named in [unknownCommands] (default
 *    `agent`, `tree`, `agents`) are rejected the way an older Click CLI rejects
 *    an unknown command — stderr `Error: No such command '<cmd>'.` + exit 2 —
 *    UNLESS [connectRpcMode] makes the connect-path RPCs hang or respond slowly
 *    first (see below).
 *  * directory probes (`test -d`) and tmux create (`create-detached` /
 *    `new-session`) succeed, so the rest of the connect/probe path behaves like
 *    a live host.
 *  * everything else returns an empty success.
 *
 * ## Connect-path failure modes (issue #849)
 *
 * [connectRpcMode] governs HOW the new-in-this-release connect-path RPCs
 * (`tree` / `agents kind`) fail, so one seam reproduces the whole class the
 * #849 ask enumerates — not just unknown-command:
 *
 *  * [OldHostConnectRpcMode.UNKNOWN_COMMAND] — the default Click rejection
 *    (exit 2, `No such command`). The v0.4.10 connect-hang trigger.
 *  * [OldHostConnectRpcMode.HANG] — the RPC never returns within
 *    [hangDelayMs]; models a wedged host where the new subcommand exists but
 *    produces no output, so an un-bounded caller hangs on "loading tree".
 *  * [OldHostConnectRpcMode.SLOW] — the RPC eventually rejects, but only after
 *    [slowDelayMs]; models a slow-responding host where a too-tight client
 *    timeout would falsely surface a connect failure.
 *
 * The version-mismatch agent-launch probe (`pocketshell agent --help`) always
 * uses the plain unknown-command rejection regardless of [connectRpcMode] —
 * the connect-path modes only shape the tree/kind RPCs.
 */
class FakeOldHostSshSession(
    val installedVersion: String = DEFAULT_OLD_VERSION,
    private val unknownCommands: Set<String> = DEFAULT_UNKNOWN_COMMANDS,
    private val connectRpcMode: OldHostConnectRpcMode = OldHostConnectRpcMode.UNKNOWN_COMMAND,
    private val hangDelayMs: Long = DEFAULT_HANG_DELAY_MS,
    private val slowDelayMs: Long = DEFAULT_SLOW_DELAY_MS,
) : SshSession {

    /** Every command the session was asked to `exec`, in order. */
    val execCommands = mutableListOf<String>()

    override val isConnected: Boolean = true

    override suspend fun exec(command: String): ExecResult {
        execCommands += command

        // The agent-launch pre-flight (`pocketshell agent --help`) is the #759
        // version-mismatch probe — always the plain Click rejection.
        if (command.contains("pocketshell agent --help")) {
            return unknownCommandResult("agent")
        }

        if (command.contains("pocketshell --version")) {
            return ExecResult(
                stdout = "pocketshell, version $installedVersion",
                stderr = "",
                exitCode = 0,
            )
        }

        // The new connect-path RPCs (tree / agents kind). Shape the failure by
        // the configured connect-rpc mode so one seam covers the whole class.
        val connectRpcName = connectPathRpcName(command)
        if (connectRpcName != null) {
            when (connectRpcMode) {
                OldHostConnectRpcMode.HANG -> {
                    // Never produces output within the window — an un-bounded
                    // caller hangs on "loading tree".
                    delay(hangDelayMs)
                    return ExecResult(stdout = "", stderr = "", exitCode = 0)
                }
                OldHostConnectRpcMode.SLOW -> {
                    delay(slowDelayMs)
                    return unknownCommandResult(connectRpcName)
                }
                OldHostConnectRpcMode.UNKNOWN_COMMAND ->
                    return unknownCommandResult(connectRpcName)
            }
        }

        return when {
            command.contains("test -d") ->
                ExecResult(stdout = "", stderr = "", exitCode = 0)
            command.contains("create-detached") || command.contains("new-session") ->
                ExecResult(stdout = "", stderr = "", exitCode = 0)
            else -> ExecResult(stdout = "", stderr = "", exitCode = 0)
        }
    }

    /**
     * The unknown-command name iff [command] invokes a new-in-this-release
     * connect-path subcommand this old host rejects (excluding the agent-launch
     * probe, handled separately). `tree` and `agents` (the `agents kind` group)
     * are the connect-path RPCs the #849 class covers.
     */
    private fun connectPathRpcName(command: String): String? {
        if (command.contains("pocketshell tree") && "tree" in unknownCommands) return "tree"
        if (command.contains("pocketshell agents") && "agents" in unknownCommands) return "agents"
        return null
    }

    private fun unknownCommandResult(name: String): ExecResult =
        ExecResult(
            stdout = "",
            stderr = "Error: No such command '$name'. " +
                "(Did you mean one of: 'agent-log', 'usage'?)",
            exitCode = 2,
        )

    override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward = error("not used")

    override fun startShell(): SshShell = error("not used")

    override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

    override suspend fun uploadStream(
        input: InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = error("not used")

    override fun close() = Unit

    companion object {
        /** Pre-`agent`-subcommand version (the #759 maintainer dogfood host). */
        const val DEFAULT_OLD_VERSION: String = "0.3.33"

        /**
         * The subcommands a current-but-not-bleeding-edge old host rejects:
         * `agent` (the #759 launch probe), and the new-in-0.4.10 connect-path
         * `tree` / `agents kind` group (#849).
         */
        val DEFAULT_UNKNOWN_COMMANDS: Set<String> = setOf("agent", "tree", "agents")

        const val DEFAULT_HANG_DELAY_MS: Long = 60_000L
        const val DEFAULT_SLOW_DELAY_MS: Long = 8_000L
    }
}

/**
 * How an old host's new connect-path RPCs (`tree` / `agents kind`) fail. One
 * [FakeOldHostSshSession] reproduces the whole #849 class via this knob.
 */
enum class OldHostConnectRpcMode {
    /** Click rejects the unknown subcommand (exit 2). The v0.4.10 trigger. */
    UNKNOWN_COMMAND,

    /** The RPC produces no output within the window — caller hangs. */
    HANG,

    /** The RPC eventually rejects, but only after a long delay. */
    SLOW,
}
