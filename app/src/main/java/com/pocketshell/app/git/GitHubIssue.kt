package com.pocketshell.app.git

/**
 * One GitHub issue row in the in-app Issues view — issue #649 (epic #644
 * slice 5).
 *
 * A read-only projection of one entry from `gh issue list --json
 * number,title,state,labels,updatedAt`: enough to render the list (number,
 * title, open/closed state, labels) without pulling the issue body or comments.
 */
data class GitHubIssue(
    /** Issue number, e.g. `649`. */
    val number: Int,
    /** Issue title (first line shown in the row). */
    val title: String,
    /** Open vs closed — drives the status dot / badge. */
    val state: GitHubIssueState,
    /** Label names attached to the issue (may be empty). */
    val labels: List<String>,
    /** Raw `updatedAt` timestamp as gh emits it (ISO-8601), or null when absent. */
    val updatedAt: String?,
)

/**
 * Open/closed state of a GitHub issue. [Unknown] keeps the parser forward-
 * compatible if gh ever emits a state we don't model, rather than dropping the
 * row.
 */
enum class GitHubIssueState {
    Open,
    Closed,
    Unknown,
    ;

    companion object {
        /** Map gh's `state` string (`OPEN` / `CLOSED`, case-insensitive). */
        fun fromRaw(raw: String?): GitHubIssueState = when (raw?.trim()?.uppercase()) {
            "OPEN" -> Open
            "CLOSED" -> Closed
            else -> Unknown
        }
    }
}
