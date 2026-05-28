package com.pocketshell.app.repos

public data class RepoEntry(
    val owner: String?,
    val name: String,
    val fullName: String?,
    val local: LocalRepoInfo?,
    val remote: RemoteRepoInfo?,
)

public data class LocalRepoInfo(
    val path: String,
    val head: String?,
)

public data class RemoteRepoInfo(
    val defaultBranch: String?,
    val htmlUrl: String?,
    val sshUrl: String?,
    val updatedAt: String?,
)

public sealed interface ReposListResult {
    public data class Success(val repos: List<RepoEntry>) : ReposListResult
    public data object ToolMissing : ReposListResult
    public data class Failed(val reason: String) : ReposListResult
}

public sealed interface RepoPathResult {
    public data class Success(val path: String) : RepoPathResult
    public data object ToolMissing : RepoPathResult
    public data class Failed(val reason: String) : RepoPathResult
}
