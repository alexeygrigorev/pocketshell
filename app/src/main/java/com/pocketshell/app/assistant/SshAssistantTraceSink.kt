package com.pocketshell.app.assistant

import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException

/**
 * [AssistantTraceSink] that ships events to the canonical sink via
 * `pocketshell logs ingest` over an existing [SshSession] (issue #270
 * contract).
 *
 * The event JSON is piped to the CLI on stdin (`... | pocketshell logs
 * ingest -`) so secret-free args never appear in the process argument list /
 * `ps` output. The command is wrapped with [ReposRemoteSource.pathAwareCommand]
 * so the user's `~/.local/bin` is on PATH exactly like every other
 * `pocketshell` call.
 *
 * Graceful degradation (issue #266): if `pocketshell logs` is absent
 * (exit 127 / "command not found") or any transport error occurs, the emit
 * is a silent no-op. The assistant must never fail an action because logging
 * is unavailable — same pattern as the env auto-export in #263.
 */
internal class SshAssistantTraceSink(
    private val session: SshSession,
) : AssistantTraceSink {

    override suspend fun emit(event: AssistantTraceEvent) {
        val payload = event.toJson()
        val command = ReposRemoteSource.pathAwareCommand(
            "printf '%s' ${shellQuote(payload)} | pocketshell logs ingest -",
        )
        try {
            // We deliberately ignore the result: a non-zero exit (absent
            // subcommand, ingest rejection) must not surface to the user.
            session.exec(command)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Transport error while logging — silent no-op by contract.
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
