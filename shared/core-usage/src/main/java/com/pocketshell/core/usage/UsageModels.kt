package com.pocketshell.core.usage

import java.time.Instant

/**
 * Normalized provider status emitted by server-side usage tools.
 *
 * The parser is intentionally tolerant: unknown status strings become
 * [Unknown] so a newer `pocketshell usage` can add states without breaking
 * the whole panel. The raw string remains on [UsageProviderRecord.rawStatus].
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
    public val percent: Double
        get() = when (unit.lowercase()) {
            "percent", "%" -> used
            else -> if (limit > 0.0) used / limit * 100.0 else 0.0
        }
}

/**
 * One provider record from `pocketshell usage --json` or another
 * compatible server-side command.
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

    /**
     * Issue #214: derive a threshold-aware state for the in-app warning
     * surfaces (host card badge, sessions dashboard chip, Settings →
     * Usage list, dismissible banner). The state is computed from the
     * most-constrained window's [UsageWindow.percent] and the
     * user-configurable [warnPercent] (default 80%). States, in order of
     * severity:
     *
     * - [UsageThresholdState.Exceeded]    — most-constrained window is
     *   at or above 100% (the provider is hard-blocked).
     * - [UsageThresholdState.Critical]    — most-constrained window is
     *   at or above [CRITICAL_PERCENT] (95%) but below 100%.
     * - [UsageThresholdState.Approaching] — most-constrained window is
     *   at or above the user-configurable [warnPercent] (default 80%)
     *   but below the critical threshold.
     * - [UsageThresholdState.Ok]          — below the warn threshold.
     *
     * If [warnPercent] is greater than or equal to [CRITICAL_PERCENT]
     * the approaching band collapses — a record that would have been
     * "approaching" jumps straight to "critical". This matches the
     * user's expectation when the slider is pulled all the way right.
     *
     * Records with no windows (e.g. `unsupported` or `error` status)
     * always resolve to [UsageThresholdState.Ok] — they don't have a
     * percent to threshold against, and the surface code intentionally
     * does not surface a warning for them. Status-derived blocks are
     * still reflected via [UsageStatus.Blocked] mapping to
     * [UsageThresholdState.Exceeded].
     */
    public fun thresholdState(warnPercent: Double = DEFAULT_WARN_PERCENT): UsageThresholdState {
        if (status == UsageStatus.Blocked) return UsageThresholdState.Exceeded
        val worst = mostConstrainedWindow?.percent ?: return UsageThresholdState.Ok
        return when {
            worst >= EXCEEDED_PERCENT -> UsageThresholdState.Exceeded
            worst >= CRITICAL_PERCENT -> UsageThresholdState.Critical
            worst >= warnPercent -> UsageThresholdState.Approaching
            else -> UsageThresholdState.Ok
        }
    }

    public companion object {
        public const val WARN_PERCENT: Double = 85.0

        /**
         * Default "approaching limit" threshold used by
         * [thresholdState] when the caller does not pass a custom
         * percent. Matches the issue #214 spec: "percent >= 80% →
         * approaching".
         */
        public const val DEFAULT_WARN_PERCENT: Double = 80.0

        /**
         * Threshold above which a provider is classified as "critical"
         * (issue #214 spec: "percent >= 95% → critical"). Fixed by
         * design — only the lower "approaching" threshold is
         * user-configurable.
         */
        public const val CRITICAL_PERCENT: Double = 95.0

        /**
         * Threshold above which a provider is hard-blocked / exceeded
         * (issue #214 spec: "percent >= 100% → exceeded"). Mirrors the
         * existing [isBlocked] derivation.
         */
        public const val EXCEEDED_PERCENT: Double = 100.0
    }
}

/**
 * Threshold-aware state derived from a [UsageProviderRecord] for the
 * issue #214 in-app warning surfaces. Ordered by severity so callers
 * can compare with `>=` to "is at least amber-level" / "is at least
 * red-level" without re-deriving the rules.
 *
 * The colour mapping is owned by the UI layer (see
 * `app/src/main/java/com/pocketshell/app/usage/UsageScreen.kt`) so the
 * shared module stays UI-framework-free. Conceptually:
 *
 *  - [Ok]          → green / neutral
 *  - [Approaching] → amber (caution)
 *  - [Critical]    → red-ish amber (stronger warning, still below the
 *                    blocked colour)
 *  - [Exceeded]    → red (matches `isBlocked` in existing surfaces)
 */
public enum class UsageThresholdState {
    Ok,
    Approaching,
    Critical,
    Exceeded,
    ;

    /**
     * True when the state warrants an in-app warning surface (host
     * card tinted badge, dismissible banner, Sessions dashboard chip
     * tint). False for [Ok] so a single boolean check can drive a
     * "render the banner?" gate.
     */
    public val warrantsWarning: Boolean
        get() = this != Ok
}
