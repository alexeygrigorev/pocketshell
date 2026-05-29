package com.pocketshell.app.snippets

import com.pocketshell.core.storage.entity.SnippetEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SnippetPickerFilterTest {

    @Test
    fun kindFilter_prompt_hidesCommandsAndPreservesSearch() {
        val command = snippet(id = 1, label = "list files", body = "ls -la", kind = "command")
        val prompt = snippet(id = 2, label = "summarise diff", body = "Please summarise the diff.", kind = "prompt")

        assertEquals(
            listOf(prompt),
            filterSnippetsForPicker(
                snippets = listOf(command, prompt),
                query = "",
                kindFilter = SnippetKind.Prompt,
            ),
        )
        assertEquals(
            listOf(prompt),
            filterSnippetsForPicker(
                snippets = listOf(command, prompt),
                query = "diff",
                kindFilter = SnippetKind.Prompt,
            ),
        )
        assertEquals(
            emptyList<SnippetEntity>(),
            filterSnippetsForPicker(
                snippets = listOf(command, prompt),
                query = "ls",
                kindFilter = SnippetKind.Prompt,
            ),
        )
    }

    @Test
    fun kindFilter_command_hidesPromptsAndPreservesSearch() {
        val command = snippet(id = 1, label = "tail logs", body = "kubectl logs -f deploy/api", kind = "command")
        val prompt = snippet(id = 2, label = "continue task", body = "Continue from the last plan.", kind = "prompt")

        assertEquals(
            listOf(command),
            filterSnippetsForPicker(
                snippets = listOf(command, prompt),
                query = "",
                kindFilter = SnippetKind.Command,
            ),
        )
        assertEquals(
            listOf(command),
            filterSnippetsForPicker(
                snippets = listOf(command, prompt),
                query = "logs",
                kindFilter = SnippetKind.Command,
            ),
        )
        assertEquals(
            emptyList<SnippetEntity>(),
            filterSnippetsForPicker(
                snippets = listOf(command, prompt),
                query = "continue",
                kindFilter = SnippetKind.Command,
            ),
        )
    }

    @Test
    fun snippetsForKindTreatsStorageCaseInsensitively() {
        val command = snippet(id = 1, label = "list files", body = "ls -la", kind = "COMMAND")
        val prompt = snippet(id = 2, label = "summarise diff", body = "Please summarise.", kind = "Prompt")

        assertEquals(listOf(command), snippetsForKind(listOf(command, prompt), SnippetKind.Command))
        assertEquals(listOf(prompt), snippetsForKind(listOf(command, prompt), SnippetKind.Prompt))
    }

    private fun snippet(id: Long, label: String, body: String, kind: String): SnippetEntity =
        SnippetEntity(
            id = id,
            hostId = 1L,
            label = label,
            body = body,
            kind = kind,
        )
}
