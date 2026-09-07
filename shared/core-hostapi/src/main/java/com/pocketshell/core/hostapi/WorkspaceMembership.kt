package com.pocketshell.core.hostapi

/**
 * One durable workspace membership returned by `pocketshell workspaces list`.
 *
 * [path] is the host-resolved absolute identity. [displayPath] is presentation
 * only and may use a shorter home-relative spelling such as `~/git/app`.
 */
data class WorkspaceMembership(
    val path: String,
    val displayPath: String,
)

/** The complete durable workspace list for one host-side identity. */
data class WorkspacesListing(
    val workspaces: List<WorkspaceMembership>,
)
