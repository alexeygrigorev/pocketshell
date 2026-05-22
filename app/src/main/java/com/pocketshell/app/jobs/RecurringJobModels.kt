package com.pocketshell.app.jobs

public data class RecurringJob(
    val id: Int,
    val enabled: Boolean,
    val sessionName: String,
    val every: String,
    val enterDelayMs: Int?,
    val source: RecurringJobSource,
    val nextRun: String,
    val detail: String,
)

public enum class RecurringJobSource {
    Inline,
    File,
    Unknown,
}

public data class RecurringJobDraft(
    val sessionName: String,
    val every: String,
    val message: String? = null,
)

public sealed interface RecurringJobsCommandResult {
    public data object Success : RecurringJobsCommandResult
    public data class Jobs(val jobs: List<RecurringJob>) : RecurringJobsCommandResult
    public data object ToolMissing : RecurringJobsCommandResult
    public data class Failed(val reason: String) : RecurringJobsCommandResult
}
