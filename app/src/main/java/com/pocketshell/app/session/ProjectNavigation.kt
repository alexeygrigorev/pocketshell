package com.pocketshell.app.session

import com.pocketshell.core.storage.entity.ProjectRootEntity

public object ProjectNavigationCommands {
    public fun cd(path: String): Result<String> = runCatching {
        val normalized = requireValidPath(path)
        commandWithFeedback(
            action = "cd",
            command = "cd ${shellPath(normalized)}",
            target = normalized,
        )
    }

    public fun mkdirAndCd(root: String, folderName: String): Result<String> = runCatching {
        val target = childPath(requireValidPath(root), requireValidName(folderName, "folder"))
        commandWithFeedback(
            action = "mkdir + cd",
            command = "mkdir -p ${shellPath(target)} && cd ${shellPath(target)}",
            target = target,
        )
    }

    public fun gitCloneAndCd(root: String, repository: String, folderName: String? = null): Result<String> = runCatching {
        val cleanRoot = requireValidPath(root)
        val cleanRepository = requireValidRepository(repository)
        val cleanFolder = folderName?.trim()?.takeIf { it.isNotEmpty() }
            ?: deriveRepositoryFolder(cleanRepository)
        val target = childPath(cleanRoot, requireValidName(cleanFolder, "folder"))
        commandWithFeedback(
            action = "git clone + cd",
            command = "git clone ${shellQuote(cleanRepository)} ${shellPath(target)} && cd ${shellPath(target)}",
            target = target,
        )
    }

    public fun shellQuote(value: String): String {
        requireNoControlChars(value, "value")
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun shellPath(path: String): String {
        requireNoControlChars(path, "path")
        return when {
            path == "~" -> "~"
            path.startsWith("~/") -> {
                val rest = path.removePrefix("~/")
                if (rest.isEmpty()) "~" else "~/${shellQuote(rest)}"
            }
            else -> shellQuote(path)
        }
    }

    private fun commandWithFeedback(action: String, command: String, target: String): String {
        val quotedAction = shellQuote(action)
        val quotedTarget = shellQuote(target)
        return "if $command; then " +
            "printf '\\n[pocketshell] %s succeeded: %s\\n' $quotedAction $quotedTarget; " +
            "else status=\$?; " +
            "printf '\\n[pocketshell] %s failed with exit %s: %s\\n' $quotedAction \"\$status\" $quotedTarget; " +
            "fi"
    }

    private fun childPath(root: String, child: String): String =
        root.trimEnd('/') + "/" + child.trim('/')

    private fun requireValidPath(path: String): String {
        val clean = path.trim()
        require(clean.isNotEmpty()) { "Path is required." }
        requireNoControlChars(clean, "path")
        require(clean == "~" || clean.startsWith("~/") || clean.startsWith("/")) {
            "Use an absolute path or a path under ~."
        }
        require(!clean.contains("..")) { "Parent-directory segments are not allowed." }
        return clean
    }

    private fun requireValidName(name: String, field: String): String {
        val clean = name.trim().trim('/')
        require(clean.isNotEmpty()) { "${field.replaceFirstChar { it.uppercase() }} is required." }
        requireNoControlChars(clean, field)
        require(!clean.contains('/')) { "${field.replaceFirstChar { it.uppercase() }} must be one directory name." }
        require(clean != "." && clean != "..") { "${field.replaceFirstChar { it.uppercase() }} is not valid." }
        return clean
    }

    private fun requireValidRepository(repository: String): String {
        val clean = repository.trim()
        require(clean.isNotEmpty()) { "Repository URL is required." }
        requireNoControlChars(clean, "repository")
        require(!clean.startsWith("-")) { "Repository URL cannot start with '-'." }
        return clean
    }

    private fun requireNoControlChars(value: String, field: String) {
        require(value.none { it.code < 0x20 || it.code == 0x7F }) {
            "${field.replaceFirstChar { it.uppercase() }} cannot contain control characters."
        }
    }

    private fun deriveRepositoryFolder(repository: String): String {
        val withoutTrailingSlash = repository.trimEnd('/')
        val segment = withoutTrailingSlash.substringAfterLast('/').substringAfterLast(':')
        val folder = segment.removeSuffix(".git")
        require(folder.isNotBlank()) { "Could not derive a folder name from the repository URL." }
        return folder
    }
}

public data class ProjectNavigationItem(
    val label: String,
    val path: String,
    val kind: ProjectNavigationItemKind,
)

public enum class ProjectNavigationItemKind { Common, ProjectRoot, Recent }

public data class ProjectNavigationUiState(
    val hostId: Long? = null,
    val roots: List<ProjectRootEntity> = emptyList(),
    val recentDirectories: List<String> = emptyList(),
    val feedback: String? = null,
) {
    public val items: List<ProjectNavigationItem>
        get() {
            val common = listOf(
                ProjectNavigationItem("home", "~", ProjectNavigationItemKind.Common),
                ProjectNavigationItem("projects", "~/projects", ProjectNavigationItemKind.Common),
                ProjectNavigationItem("src", "~/src", ProjectNavigationItemKind.Common),
                ProjectNavigationItem("tmp", "/tmp", ProjectNavigationItemKind.Common),
            )
            val rootItems = roots.map {
                ProjectNavigationItem(
                    label = it.label.ifBlank { it.path.substringAfterLast('/').ifBlank { it.path } },
                    path = it.path,
                    kind = ProjectNavigationItemKind.ProjectRoot,
                )
            }
            val recentItems = recentDirectories.map {
                ProjectNavigationItem(
                    label = it.substringAfterLast('/').ifBlank { it },
                    path = it,
                    kind = ProjectNavigationItemKind.Recent,
                )
            }
            return (rootItems + recentItems + common).distinctBy { it.path }
        }
}
