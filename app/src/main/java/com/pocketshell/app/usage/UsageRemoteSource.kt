package com.pocketshell.app.usage

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.usage.PocketshellUsageJsonParser
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
 * Issue #231 swapped the default command to the unified `pocketshell usage
 * --json` CLI (which proxies the underlying `quse` provider probe verbatim) —
 * see [docs/usage-panel.md](../../../../../../../docs/usage-panel.md).
 */
public class UsageRemoteSource @Inject constructor(
    private val parser: PocketshellUsageJsonParser = PocketshellUsageJsonParser(),
) {
    /**
     * Probe whether `pocketshell` is resolvable on the host.
     *
     * Runs the PATH-robust [PocketshellCommand.detect] wrapper (issue #484), so
     * a binary that lives in `~/.local/bin` but is hidden from the
     * non-interactive SSH `PATH` is still found via the absolute-path probe.
     * "Missing" therefore only means the binary is genuinely absent (the
     * wrapper exits `127`), not merely off-`PATH`.
     */
    public suspend fun detectPocketshell(session: SshSession): UsageToolStatus = try {
        val result = session.exec(PocketshellCommand.detect())
        when {
            result.exitCode == 0 && result.stdout.isNotBlank() -> UsageToolStatus.Installed
            result.exitCode == 127 -> UsageToolStatus.Missing
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
        val rawArgs = commandOverride?.trim()?.takeIf { it.isNotEmpty() }
        // A bare default ("pocketshell usage --json") is wrapped through the
        // PATH-robust resolver. A per-host override is an arbitrary script the
        // maintainer supplied, so it is run verbatim (the override owner is
        // responsible for its own PATH).
        val command = if (rawArgs == null) {
            PocketshellCommand.wrap(DEFAULT_USAGE_ARGS)
        } else {
            rawArgs
        }
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
        /** The `pocketshell` arguments for the default usage probe. */
        public const val DEFAULT_USAGE_ARGS: String = "usage --json"

        /**
         * Human-readable form of the default usage command, used for snapshot
         * provenance (`UsageSnapshot.command`). The actual SSH invocation is
         * the PATH-robust [PocketshellCommand.wrap] of [DEFAULT_USAGE_ARGS].
         */
        public const val defaultUsageCommand: String = "pocketshell usage --json"
    }
}
