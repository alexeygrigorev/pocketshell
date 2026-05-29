package com.pocketshell.app.assistant

/**
 * Forbidden-pattern + length safety checks for shell commands the assistant
 * wants to run.
 *
 * Migrated wholesale from the deleted `core-voice` `CommandPlanner` (D22 hard
 * cut, issue #266). The planner used to validate a whole `CommandPlan`
 * (max-N commands + per-command forbidden-pattern + control-char + length
 * checks); the assistant's `run_command` tool only ever proposes ONE command
 * per dispatch, so the "max commands" cap collapses into a single-command
 * guard here. Multi-statement payloads (joined with `;` / `&&` / `|`) are
 * still allowed — the forbidden patterns are anchored on statement
 * boundaries exactly like the planner's were, so `sudo`, `rm -rf`,
 * `shutdown`, `dd`, `mkfs`, writing to raw block devices, etc. are rejected
 * wherever they appear in the command, not just at the start.
 *
 * Kept as pure, Android-free logic so the agent loop can unit-test the gate
 * without an emulator.
 */
internal object CommandSafety {

    /** Hard cap on a single proposed command's length (chars). */
    private const val MAX_COMMAND_LENGTH: Int = 500

    /**
     * Default forbidden patterns. Copied verbatim from the planner's
     * `DEFAULT_FORBIDDEN_COMMAND_PATTERNS` so the migrated gate keeps the
     * exact same safety surface — `sudo` / `su`, recursive-force `rm`,
     * `shutdown` / `reboot` / `halt`, `mkfs`, `dd`, and redirecting onto a
     * raw block device. Anchored on a line start or a `;` / `&` / `|`
     * statement boundary so they fire mid-pipeline too.
     */
    val DEFAULT_FORBIDDEN_PATTERNS: List<String> = listOf(
        """(^|[;&|]\s*)sudo\b""",
        """(^|[;&|]\s*)su\b""",
        """(^|[;&|]\s*)rm\s+-[^\n;]*[rf][^\n;]*[rf]""",
        """(^|[;&|]\s*)shutdown\b""",
        """(^|[;&|]\s*)reboot\b""",
        """(^|[;&|]\s*)halt\b""",
        """(^|[;&|]\s*)mkfs(\.|$|\s)""",
        """(^|[;&|]\s*)dd\s+""",
        """>\s*/dev/(sd|nvme|mapper/)""",
    )

    private val compiledDefaults: List<CompiledPattern> by lazy {
        DEFAULT_FORBIDDEN_PATTERNS.map { source ->
            CompiledPattern(source, Regex(source, RegexOption.IGNORE_CASE))
        }
    }

    private data class CompiledPattern(val source: String, val regex: Regex)

    /**
     * Validate a single proposed command. Returns `null` when the command is
     * safe to execute, or a human-readable rejection reason otherwise. The
     * reason is surfaced back into the agent loop (and to the user) so the
     * model can revise instead of silently dropping the request.
     */
    fun reject(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return "The proposed command was empty."
        if (trimmed.length > MAX_COMMAND_LENGTH) {
            return "The proposed command was too long (over $MAX_COMMAND_LENGTH characters)."
        }
        if (trimmed.any { it.code == 0 || it == '\r' || it == '\n' }) {
            return "The proposed command contained a control character."
        }
        val normalized = trimmed.lowercase()
        val matched = compiledDefaults.firstOrNull { it.regex.containsMatchIn(normalized) }
        return matched?.let { "The proposed command is blocked by safety rule `${it.source}`." }
    }
}
