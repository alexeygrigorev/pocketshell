package com.pocketshell.app.usage

import com.pocketshell.app.pocketshell.PocketshellCommand
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.usage.PocketshellUsageJsonParser
import com.pocketshell.core.usage.UsageParseException
import com.pocketshell.core.usage.UsageProviderRecord
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
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
 * Result of reading the host's cached latest usage reading (issue #689).
 *
 * The cache is written by a scheduled server-side `pocketshell usage
 * --capture`. The app reads it with `pocketshell usage --cached` for an
 * instant, always-populated render BEFORE its own live foreground refresh.
 */
public sealed interface CachedUsageResult {
    /**
     * A captured reading exists. [capturedAt] powers the "last captured at
     * <time>" provenance label; [records] are the same provider records the
     * live path returns.
     */
    public data class Hit(
        val records: List<UsageProviderRecord>,
        val capturedAt: Instant?,
    ) : CachedUsageResult

    /** No capture has run yet (or `--cached` is unsupported); fall back to live. */
    public data object Empty : CachedUsageResult
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

    /**
     * Read the host's cached latest usage reading (issue #689).
     *
     * Runs `pocketshell usage --cached`, which prints the most recent
     * scheduled capture as a single JSON document `{captured_at, records}`
     * instantly (no provider API round-trip). The app renders this at once
     * with a "last captured at <time>" label, then refreshes live in the
     * foreground.
     *
     * Returns [CachedUsageResult.Empty] when no capture exists yet (the CLI
     * exits 3), when `--cached` is unsupported (exit 127 / 2), or on any
     * parse/transport hiccup — every "no usable cache" case collapses to
     * Empty so the caller simply falls back to a pure live fetch. A
     * per-host [commandOverride] disables the cache path (an override is an
     * arbitrary script that does not speak `--cached`).
     */
    public suspend fun fetchCachedUsage(
        session: SshSession,
        commandOverride: String? = null,
    ): CachedUsageResult {
        if (commandOverride?.trim()?.isNotEmpty() == true) return CachedUsageResult.Empty
        return try {
            val result = session.exec(PocketshellCommand.wrap(CACHED_USAGE_ARGS))
            if (result.exitCode != 0) return CachedUsageResult.Empty
            parseCachedDocument(result.stdout)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            CachedUsageResult.Empty
        }
    }

    private fun parseCachedDocument(stdout: String): CachedUsageResult {
        val trimmed = stdout.trim()
        if (trimmed.isEmpty()) return CachedUsageResult.Empty
        return try {
            val obj = JSONObject(trimmed)
            val capturedAt = obj.optString("captured_at").trim().ifBlank { null }?.let { raw ->
                runCatching { Instant.parse(raw) }.getOrNull()
            }
            val recordsArray: JSONArray = obj.optJSONArray("records") ?: JSONArray()
            // Re-emit the records as NDJSON so the SAME parser the live path
            // uses produces identical UsageProviderRecord shapes.
            val ndjson = buildString {
                for (i in 0 until recordsArray.length()) {
                    val record = recordsArray.optJSONObject(i) ?: continue
                    append(record.toString())
                    append('\n')
                }
            }
            val records = if (ndjson.isBlank()) emptyList() else parser.parse(ndjson)
            CachedUsageResult.Hit(records = records, capturedAt = capturedAt)
        } catch (_: Exception) {
            CachedUsageResult.Empty
        }
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
                parseProviderErrorStdout(result.stdout)?.let { records ->
                    return UsageFetchResult.Success(records)
                }
                val reason = result.stderr.ifBlank { result.stdout }.ifBlank { "usage command exited ${result.exitCode}" }
                return UsageFetchResult.Failed(reason)
            }
            // exit 0: pocketshell resolved and ran (binary present). Parse its
            // usage JSON — but when parsing yields NO records because pocketshell
            // printed its OWN dependency error (e.g. `quse` missing) as plain
            // text on stdout (issue #1220), surface pocketshell's real message +
            // install hint. Do NOT leak the JSON parser internals, and NEVER map
            // this to ToolMissing — "pocketshell not installed" is exit-127 only.
            val records = try {
                parser.parse(result.stdout)
            } catch (e: UsageParseException) {
                return UsageFetchResult.Failed(pocketshellUsageErrorReason(result.stdout, e))
            }
            UsageFetchResult.Success(records)
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
         * The `pocketshell` arguments for the cached-reading probe (#689).
         * Prints the last scheduled capture instantly as a JSON document.
         */
        public const val CACHED_USAGE_ARGS: String = "usage --cached"

        /**
         * Human-readable form of the default usage command, used for snapshot
         * provenance (`UsageSnapshot.command`). The actual SSH invocation is
         * the PATH-robust [PocketshellCommand.wrap] of [DEFAULT_USAGE_ARGS].
         */
        public const val defaultUsageCommand: String = "pocketshell usage --json"

        /**
         * Upper bound on the surfaced pocketshell-error reason (issue #1220) so
         * an unexpectedly large non-JSON stdout can't produce a runaway panel
         * message. The failure panel already caps to a few lines visually; this
         * keeps the model value bounded too.
         */
        private const val POCKETSHELL_ERROR_REASON_MAX_CHARS: Int = 500
    }

    /**
     * Build the user-facing failure reason for an exit-0 usage read whose
     * stdout did not parse into any usage record (issue #1220).
     *
     * pocketshell resolved and ran (exit 0 ⇒ the binary is present), so this is
     * NOT "pocketshell not installed". The most common cause is pocketshell
     * emitting its OWN dependency error — e.g. "`quse` is not installed on this
     * host. Install it via `uv tool install quse` ..." — as plain text. Prefer
     * that message (dropping pocketshell's own "pocketshell: " prefix so it
     * reads as the dependency error, not as pocketshell itself being absent) so
     * the panel shows the real problem + install hint. Fall back to the parse
     * diagnostic only when stdout is blank.
     */
    private fun pocketshellUsageErrorReason(stdout: String, parseError: UsageParseException): String {
        val message = stdout.trim().takeIf { it.isNotEmpty() }
            ?.removePrefix("pocketshell:")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return parseError.message ?: "usage command produced no usable data"
        return message.take(POCKETSHELL_ERROR_REASON_MAX_CHARS)
    }

    private fun parseProviderErrorStdout(stdout: String): List<UsageProviderRecord>? {
        if (stdout.isBlank()) return null
        return try {
            parser.parse(stdout).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
