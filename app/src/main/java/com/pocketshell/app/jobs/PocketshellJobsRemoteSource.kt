package com.pocketshell.app.jobs

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Drives recurring-job management over SSH through the unified
 * `pocketshell jobs ...` CLI. Replaces the legacy `tmuxctl jobs ...` probes per
 * the #231 parity swap (D22 hard-cut, no fallback to the old commands).
 *
 * `pocketshell jobs` forwards verbatim to the host scheduler, so the emitted
 * command strings are a direct namespace swap (`tmuxctl jobs <verb>` ->
 * `pocketshell jobs <verb>`) and the listed output stays byte-identical for
 * [RecurringJobsParser].
 */
public class PocketshellJobsRemoteSource @Inject constructor(
    private val parser: RecurringJobsParser,
) {

    public suspend fun list(
        session: SshSession,
        sessionName: String? = null,
    ): RecurringJobsCommandResult {
        val command = buildString {
            append("pocketshell jobs list")
            sessionName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(" --session ")
                append(shellQuote(it))
            }
        }
        return run(session, command) { stdout ->
            RecurringJobsCommandResult.Jobs(parser.parseList(stdout))
        }
    }

    public suspend fun add(
        session: SshSession,
        draft: RecurringJobDraft,
        startNow: Boolean = false,
    ): RecurringJobsCommandResult {
        val message = draft.message?.takeIf { it.isNotBlank() }
            ?: return RecurringJobsCommandResult.Failed("message is required")
        val command = buildString {
            append("pocketshell jobs add ")
            append(shellQuote(draft.sessionName.trim()))
            append(" --every ")
            append(shellQuote(draft.every.trim()))
            append(" --message ")
            append(shellQuote(message))
            if (startNow) append(" --start-now")
        }
        return run(session, command) { RecurringJobsCommandResult.Success }
    }

    public suspend fun edit(
        session: SshSession,
        jobId: Int,
        sessionName: String? = null,
        every: String? = null,
        message: String? = null,
        enabled: Boolean? = null,
    ): RecurringJobsCommandResult {
        val command = buildString {
            append("pocketshell jobs edit ")
            append(jobId)
            sessionName?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(" --session ")
                append(shellQuote(it))
            }
            every?.trim()?.takeIf { it.isNotEmpty() }?.let {
                append(" --every ")
                append(shellQuote(it))
            }
            message?.takeIf { it.isNotEmpty() }?.let {
                append(" --message ")
                append(shellQuote(it))
            }
            enabled?.let {
                append(if (it) " --enable" else " --disable")
            }
        }
        return run(session, command) { RecurringJobsCommandResult.Success }
    }

    public suspend fun remove(
        session: SshSession,
        jobId: Int,
    ): RecurringJobsCommandResult =
        run(session, "pocketshell jobs remove $jobId") { RecurringJobsCommandResult.Success }

    private suspend fun run(
        session: SshSession,
        command: String,
        onSuccess: (String) -> RecurringJobsCommandResult,
    ): RecurringJobsCommandResult = try {
        val result = session.exec(pathAwareCommand(command))
        when {
            result.exitCode == 0 -> onSuccess(result.stdout)
            result.exitCode == 127 -> RecurringJobsCommandResult.ToolMissing
            result.looksLikeDaemonUnavailable() -> RecurringJobsCommandResult.DaemonUnavailable(
                result.stderr.ifBlank { result.stdout }.ifBlank {
                    "pocketshell jobs daemon is unavailable"
                },
            )
            else -> RecurringJobsCommandResult.Failed(
                result.stderr.ifBlank { result.stdout }.ifBlank { "pocketshell exited ${result.exitCode}" },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        RecurringJobsCommandResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    public companion object {
        /**
         * Wrap a `pocketshell jobs ...` command string through the centralised
         * PATH-robust resolver (issue #484) so the CLI is found even when the
         * non-interactive SSH `PATH` lacks `~/.local/bin`. [command] always
         * starts with the literal `pocketshell ` prefix produced by the verb
         * builders above; that prefix is stripped and the remaining arguments
         * are handed to [PocketshellCommand.wrap], which re-resolves and runs
         * the binary.
         */
        public fun pathAwareCommand(command: String): String {
            val args = command.removePrefix("pocketshell ").trim()
            return PocketshellCommand.wrap(args)
        }

        public fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}

private fun ExecResult.looksLikeDaemonUnavailable(): Boolean {
    val text = (stderr + "\n" + stdout).lowercase()
    return "pocketshell-jobs.service" in text ||
        "jobs daemon" in text ||
        "systemctl --user" in text ||
        "failed to connect to bus" in text
}
