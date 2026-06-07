package com.pocketshell.app.snippets

import com.pocketshell.core.storage.entity.CommandTemplateEntity
import com.pocketshell.core.storage.entity.SnippetEntity

private val PLACEHOLDER_REGEX = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_-]{0,39})\s*}}""")

private const val BUILT_IN_GIT_COMMIT_PUSH_ID: Long = -556_001L
private const val USER_COMMAND_TEMPLATE_SNIPPET_ID_OFFSET: Long = -1_556_000L

private val BUILT_IN_COMMAND_TEMPLATES: List<SnippetEntity> = listOf(
    SnippetEntity(
        id = BUILT_IN_GIT_COMMIT_PUSH_ID,
        hostId = 0L,
        label = "Git add, commit, push",
        body = "git add .\ngit commit -m '{{message}}'\ngit push",
        kind = SnippetKind.Command.storageValue,
    ),
)

/**
 * Built-in rows shown in the same picker as user-authored snippets.
 *
 * These are deliberately not inserted into Room. That keeps defaults available
 * on every host without creating repeated per-host rows or migrations, while
 * preserving the user's normal snippet CRUD for custom templates.
 */
internal fun builtInSnippetTemplates(kindFilter: SnippetKind?): List<SnippetEntity> =
    BUILT_IN_COMMAND_TEMPLATES.filter { snippet ->
        kindFilter == null || SnippetKind.fromStorage(snippet.kind) == kindFilter
    }

internal fun snippetsForPickerWithBuiltIns(
    snippets: List<SnippetEntity>,
    kindFilter: SnippetKind?,
): List<SnippetEntity> {
    val storedBodies = snippets
        .map { snippet -> SnippetDuplicateKey(SnippetKind.fromStorage(snippet.kind), snippet.body) }
        .toSet()
    val builtIns = builtInSnippetTemplates(kindFilter).filterNot { builtIn ->
        SnippetDuplicateKey(SnippetKind.fromStorage(builtIn.kind), builtIn.body) in storedBodies
    }
    return builtIns + snippets
}

internal fun snippetsForPickerWithBuiltInsAndCommandTemplates(
    snippets: List<SnippetEntity>,
    commandTemplates: List<CommandTemplateEntity>,
    kindFilter: SnippetKind?,
): List<SnippetEntity> {
    val base = snippetsForPickerWithBuiltIns(snippets, kindFilter)
    if (kindFilter == SnippetKind.Prompt) return base
    return base + commandTemplates.map(::commandTemplateAsSnippet)
}

internal fun commandTemplateAsSnippet(template: CommandTemplateEntity): SnippetEntity =
    SnippetEntity(
        id = commandTemplateSnippetId(template.id),
        hostId = template.hostId,
        label = template.label,
        body = template.commands,
        kind = SnippetKind.Command.storageValue,
    )

internal fun commandTemplateSnippetId(templateId: Long): Long =
    USER_COMMAND_TEMPLATE_SNIPPET_ID_OFFSET - templateId

private data class SnippetDuplicateKey(
    val kind: SnippetKind,
    val body: String,
)

/**
 * Placeholder names found in [body], in first-use order.
 *
 * Supported syntax is `{{name}}`, with optional whitespace inside the braces.
 * Unknown or malformed brace pairs are left as literal text so a snippet body
 * cannot become unsendable because of prose that happens to contain braces.
 */
internal fun snippetTemplateParameters(body: String): List<String> {
    val seen = LinkedHashSet<String>()
    for (match in PLACEHOLDER_REGEX.findAll(body)) {
        seen += match.groupValues[1]
    }
    return seen.toList()
}

internal fun expandSnippetTemplate(body: String, values: Map<String, String>): String =
    PLACEHOLDER_REGEX.replace(body) { match ->
        values[match.groupValues[1]] ?: match.value
    }

internal fun snippetHasTemplateParameters(snippet: SnippetEntity): Boolean =
    snippetTemplateParameters(snippet.body).isNotEmpty()

/**
 * Text that should be written to the terminal for a snippet pick.
 *
 * Command snippets are shell-oriented. When the user chooses `Send + Enter`,
 * embedded line breaks represent command boundaries, so translate them to
 * carriage returns before appending the final submit. This makes a saved body
 * like:
 *
 * ```
 * git add .
 * git commit -m '{{message}}'
 * git push
 * ```
 *
 * execute as three shell submissions instead of landing as one bracketed paste
 * block in tmux. Prompt snippets keep their literal body; agent/composer
 * surfaces decide how and when to submit them.
 */
public fun snippetDispatchText(snippet: SnippetEntity, withEnter: Boolean): String {
    if (!withEnter) return snippet.body
    val kind = SnippetKind.fromStorage(snippet.kind)
    if (kind != SnippetKind.Command) return snippet.body + "\r"

    val normalized = normalizeSnippetLineEndings(snippet.body).trimEnd('\n')
    return normalized.replace('\n', '\r') + "\r"
}

private fun normalizeSnippetLineEndings(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n')
