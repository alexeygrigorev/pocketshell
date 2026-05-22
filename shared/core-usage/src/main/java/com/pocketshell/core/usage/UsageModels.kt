package com.pocketshell.core.usage

import java.time.Instant
import kotlin.math.roundToInt

/**
 * Normalized provider status emitted by server-side usage tools.
 *
 * The parser is intentionally tolerant: unknown status strings become
 * [Unknown] so a newer `heru` can add states without breaking the whole
 * panel. The raw string remains on [UsageProviderRecord.rawStatus].
 */
public enum class UsageStatus {
    Ok,
    Warn,
    Blocked,
    Error,
    Unsupported,
    Unknown,
}

/**
 * One usage window for a provider, for example Claude's 5h bucket or a
 * weekly Codex quota.
 */
public data class UsageWindow(
    val name: String,
    val used: Double,
    val limit: Double,
    val unit: String,
    val resetAt: Instant?,
) {
    public val ratio: Double
        get() = if (limit > 0.0) used / limit else 0.0

    public val percent: Double
        get() = when (unit.lowercase()) {
            "percent", "%" -> used
            else -> ratio * 100.0
        }

    public val roundedPercent: Int
        get() = percent.roundToInt().coerceAtLeast(0)
}

/**
 * One provider record from `heru usage --json` or another compatible
 * server-side command.
 */
public data class UsageProviderRecord(
    val provider: String,
    val status: UsageStatus,
    val windows: List<UsageWindow>,
    val rawStatus: String,
    val blockReason: String? = null,
    val lastError: String? = null,
) {
    public val displayName: String
        get() = when (provider.lowercase()) {
            "claude" -> "Claude Code"
            "codex" -> "Codex"
            "opencode", "open_code", "open-code" -> "OpenCode"
            "copilot", "github_copilot", "github-copilot" -> "GitHub Copilot"
            else -> provider
                .split('-', '_', ' ')
                .filter { it.isNotBlank() }
                .joinToString(" ") { token ->
                    token.replaceFirstChar { ch -> ch.uppercase() }
                }
                .ifBlank { provider }
        }

    public val mostConstrainedWindow: UsageWindow?
        get() = windows.maxByOrNull { it.percent }

    public val isBlocked: Boolean
        get() = status == UsageStatus.Blocked || windows.any { it.percent >= 100.0 }

    public val isNearLimit: Boolean
        get() = !isBlocked && windows.any { it.percent >= WARN_PERCENT }

    public companion object {
        public const val WARN_PERCENT: Double = 85.0
    }
}
