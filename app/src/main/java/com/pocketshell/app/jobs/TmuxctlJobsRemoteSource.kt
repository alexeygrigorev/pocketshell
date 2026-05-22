package com.pocketshell.app.jobs

import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

public class TmuxctlJobsRemoteSource @Inject constructor(
    private val parser: TmuxctlJobsParser,
) {

    public suspend fun list(
        session: SshSession,
        sessionName: String? = null,
    ): RecurringJobsCommandResult {
        val command = buildString {
            append("tmuxctl jobs list")
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
            append("tmuxctl jobs add ")
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
            append("tmuxctl jobs edit ")
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
        run(session, "tmuxctl jobs remove $jobId") { RecurringJobsCommandResult.Success }

    private suspend fun run(
        session: SshSession,
        command: String,
        onSuccess: (String) -> RecurringJobsCommandResult,
    ): RecurringJobsCommandResult = try {
        val result = session.exec(pathAwareCommand(command))
        when {
            result.exitCode == 0 -> onSuccess(result.stdout)
            result.exitCode == 127 -> RecurringJobsCommandResult.ToolMissing
            else -> RecurringJobsCommandResult.Failed(
                result.stderr.ifBlank { result.stdout }.ifBlank { "tmuxctl exited ${result.exitCode}" },
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        RecurringJobsCommandResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    public companion object {
        public fun pathAwareCommand(command: String): String =
            "PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; $command"

        public fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
