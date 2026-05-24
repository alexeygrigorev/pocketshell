package com.pocketshell.app.usage

import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.usage.QuseUsageJsonParser
import com.pocketshell.core.usage.UsageProviderRecord
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

public sealed interface UsageToolStatus {
    public data object Installed : UsageToolStatus
    public data object Missing : UsageToolStatus
    public data class Unknown(val reason: String) : UsageToolStatus
}

public sealed interface UsageFetchResult {
    public data class Success(val records: List<UsageProviderRecord>) : UsageFetchResult
    public data object ToolMissing : UsageFetchResult
    public data class Failed(val reason: String) : UsageFetchResult
}

/**
 * SSH-shaped usage source for issue #24.
 *
 * Detection is deliberately per-host and command execution is server-side:
 * PocketShell never asks for provider OAuth/API keys and never calls provider
 * APIs directly. A host can override [defaultUsageCommand] with any compatible
 * script as long as it emits the normalized JSON parsed by core-usage.
 *
 * Issue #128 switched the default command from the legacy `heru usage --json`
 * to `quse --json` — see [docs/usage-panel.md](../../../../../../../docs/usage-panel.md).
 */
public class UsageRemoteSource @Inject constructor(
    private val parser: QuseUsageJsonParser = QuseUsageJsonParser(),
) {
    public suspend fun detectQuse(session: SshSession): UsageToolStatus = try {
        val result = session.exec(DETECT_QUSE_COMMAND)
        when {
            result.exitCode == 0 && result.stdout.isNotBlank() -> UsageToolStatus.Installed
            result.exitCode != 0 -> UsageToolStatus.Missing
            else -> UsageToolStatus.Missing
        }
    } catch (e: SshException) {
        UsageToolStatus.Unknown(e.message ?: e.javaClass.simpleName)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        UsageToolStatus.Unknown("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
    }

    public suspend fun fetchUsage(
        session: SshSession,
        commandOverride: String? = null,
    ): UsageFetchResult {
        val command = commandOverride?.trim()?.takeIf { it.isNotEmpty() } ?: defaultUsageCommand
        return try {
            val result = session.exec(command)
            if (result.exitCode == 127) return UsageFetchResult.ToolMissing
            if (result.exitCode != 0) {
                val reason = result.stderr.ifBlank { result.stdout }.ifBlank { "usage command exited ${result.exitCode}" }
                return UsageFetchResult.Failed(reason)
            }
            UsageFetchResult.Success(parser.parse(result.stdout))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            UsageFetchResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        }
    }

    public companion object {
        public const val DETECT_QUSE_COMMAND: String = "command -v quse"
        public const val defaultUsageCommand: String = "quse --json"
    }
}
