package com.pocketshell.app.git

/**
 * One commit row in the Git history view — issue #646 (epic #644 slice 2).
 *
 * A read-only projection of a `git log` entry: enough to render the timeline
 * (short hash, author, relative time, subject) without pulling the full diff.
 */
data class GitCommit(
    /** Abbreviated commit hash, e.g. `a1b2c3d`. */
    val shortHash: String,
    /** Author name as recorded in the commit. */
    val author: String,
    /** Relative age string from git, e.g. `3 hours ago`. */
    val relativeTime: String,
    /** First line of the commit message. */
    val subject: String,
)
