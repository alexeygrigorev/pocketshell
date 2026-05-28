package com.pocketshell.app.repos

import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

public class ReposRemoteSource @Inject constructor(
    private val parser: ReposJsonParser = ReposJsonParser(),
) {
    public suspend fun listRemote(
        session: SshSession,
        limit: Int? = null,
    ): ReposListResult {
        val command = buildString {
            append("pocketshell repos list --remote --json")
            limit?.let {
                append(" --limit ")
                append(it.coerceAtLeast(1))
            }
        }
        return try {
            val result = session.exec(pathAwareCommand(command))
            when {
                result.exitCode == 0 -> ReposListResult.Success(parser.parseList(result.stdout))
                result.exitCode == 127 -> ReposListResult.ToolMissing
                else -> ReposListResult.Failed(result.failureReason("pocketshell repos exited ${result.exitCode}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ReposListResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        }
    }

    public suspend fun open(
        session: SshSession,
        fullName: String,
    ): RepoPathResult =
        runPath(session, "pocketshell repos open ${shellQuote(fullName)}")

    public suspend fun clone(
        session: SshSession,
        fullName: String,
        root: String = "~/git",
        protocol: String = "ssh",
    ): RepoPathResult =
        runPath(
            session,
            "pocketshell repos clone ${shellQuote(fullName)} --root ${shellQuote(root)} --protocol ${shellQuote(protocol)}",
        )

    private suspend fun runPath(
        session: SshSession,
        command: String,
    ): RepoPathResult = try {
        val result = session.exec(pathAwareCommand(command))
        when {
            result.exitCode == 0 -> {
                val path = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                if (path.isNullOrBlank()) {
                    RepoPathResult.Failed("pocketshell repos returned an empty path")
                } else {
                    RepoPathResult.Success(path)
                }
            }
            result.exitCode == 127 -> RepoPathResult.ToolMissing
            else -> RepoPathResult.Failed(result.failureReason("pocketshell repos exited ${result.exitCode}"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        RepoPathResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    public companion object {
        public fun pathAwareCommand(command: String): String =
            "PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; $command"

        public fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}

private fun com.pocketshell.core.ssh.ExecResult.failureReason(fallback: String): String =
    stderr.ifBlank { stdout }.ifBlank { fallback }
