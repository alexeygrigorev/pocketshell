package com.pocketshell.app.git

/**
 * Read-only repository overview for a project directory — issue #647
 * (epic #644 slice 3).
 *
 * A projection of `git branch`, `git worktree list`, and a small status probe
 * (`git status`, current branch, ahead/behind vs upstream, last commit). Surfaced
 * alongside the commit history so the user can understand "what's happening" in
 * the repo at a glance without any write/checkout action.
 */
data class GitRepoOverview(
    /** Working-tree + branch summary (current branch, upstream, dirty state). */
    val status: GitRepoStatus,
    /** Local branches, current branch first. */
    val branches: List<GitBranch>,
    /** Linked working trees (`git worktree list`). Single-entry for a plain repo. */
    val worktrees: List<GitWorktree>,
)

/**
 * Working-tree status: current branch, upstream tracking, ahead/behind counts,
 * dirty-vs-clean, and a one-line summary of the most recent commit.
 */
data class GitRepoStatus(
    /** Current branch name, or null when detached / unknown. */
    val currentBranch: String?,
    /** Upstream ref the current branch tracks, e.g. `origin/main`, or null. */
    val upstream: String?,
    /** Commits the current branch is ahead of [upstream]. */
    val ahead: Int,
    /** Commits the current branch is behind [upstream]. */
    val behind: Int,
    /** True when the working tree has uncommitted/untracked changes. */
    val dirty: Boolean,
    /** Number of changed/untracked paths reported by `git status --porcelain`. */
    val changedFiles: Int,
    /** First line of the most recent commit (`<short-hash> <subject>`), or null. */
    val lastCommit: String?,
    /** True when the repository has no commits yet. */
    val hasNoCommits: Boolean,
) {
    /** True when there is an upstream and the branch diverged from it. */
    val hasUpstream: Boolean get() = upstream != null
}

/** One local branch from `git branch`. */
data class GitBranch(
    /** Branch short name, e.g. `main`. */
    val name: String,
    /** True when this is the currently checked-out branch. */
    val current: Boolean,
    /** Upstream tracking ref, e.g. `origin/main`, or null when not tracking. */
    val upstream: String?,
    /** First line of the branch tip commit subject, or null. */
    val subject: String?,
)

/** One linked working tree from `git worktree list --porcelain`. */
data class GitWorktree(
    /** Absolute path of the working tree. */
    val path: String,
    /** Checked-out branch short name, or null when detached / bare. */
    val branch: String?,
    /** Abbreviated HEAD commit, or null when not reported. */
    val head: String?,
    /** True when this worktree is bare (no checkout). */
    val bare: Boolean,
    /** True when this worktree has a detached HEAD. */
    val detached: Boolean,
)
