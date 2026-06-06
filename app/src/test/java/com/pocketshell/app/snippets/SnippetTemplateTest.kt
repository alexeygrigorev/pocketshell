package com.pocketshell.app.snippets

import com.pocketshell.core.storage.entity.SnippetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetTemplateTest {

    @Test
    fun templateParameters_areReturnedInFirstUseOrderWithoutDuplicates() {
        val body = "git commit -m '{{message}}'\ngit tag {{ tag_name }}\necho {{message}}"

        assertEquals(
            listOf("message", "tag_name"),
            snippetTemplateParameters(body),
        )
    }

    @Test
    fun templateParameters_ignoreMalformedBraces() {
        val body = "echo {{ }} {{two words}} {{1bad}} {{good-name}}"

        assertEquals(listOf("good-name"), snippetTemplateParameters(body))
    }

    @Test
    fun expandTemplate_replacesKnownParametersAndLeavesUnknownsLiteral() {
        val body = "git commit -m '{{message}}' && echo {{missing}} && git push"

        assertEquals(
            "git commit -m 'ship it' && echo {{missing}} && git push",
            expandSnippetTemplate(body, mapOf("message" to "ship it")),
        )
    }

    @Test
    fun hasTemplateParameters_detectsSnippetBodiesWithPlaceholders() {
        assertTrue(snippetHasTemplateParameters(snippet(body = "git commit -m '{{message}}'")))
        assertFalse(snippetHasTemplateParameters(snippet(body = "git status")))
    }

    @Test
    fun commandSnippetWithEnter_convertsMultiLineSequenceToEnterKeystrokes() {
        val snippet = snippet(
            body = "git add .\ngit commit -m '{{message}}'\ngit push",
            kind = "command",
        ).copy(
            body = expandSnippetTemplate(
                "git add .\ngit commit -m '{{message}}'\ngit push",
                mapOf("message" to "callz"),
            ),
        )

        assertEquals(
            "git add .\rgit commit -m 'callz'\rgit push\r",
            snippetDispatchText(snippet, withEnter = true),
        )
    }

    @Test
    fun commandSnippetWithEnter_normalizesLineEndingsAndAvoidsExtraTrailingEnter() {
        val snippet = snippet(body = "echo one\r\necho two\n", kind = "command")

        assertEquals(
            "echo one\recho two\r",
            snippetDispatchText(snippet, withEnter = true),
        )
    }

    @Test
    fun commandSnippetWithoutEnter_preservesLiteralBodyForEditing() {
        val snippet = snippet(body = "echo one\necho two", kind = "command")

        assertEquals("echo one\necho two", snippetDispatchText(snippet, withEnter = false))
    }

    @Test
    fun promptSnippetWithEnter_preservesMultilinePromptBody() {
        val snippet = snippet(body = "first paragraph\nsecond paragraph", kind = "prompt")

        assertEquals(
            "first paragraph\nsecond paragraph\r",
            snippetDispatchText(snippet, withEnter = true),
        )
    }

    @Test
    fun builtInTemplates_includeGitAddCommitPushForCommandPicker() {
        val builtIns = snippetsForPickerWithBuiltIns(emptyList(), SnippetKind.Command)

        assertEquals(1, builtIns.size)
        assertEquals("Git add, commit, push", builtIns.single().displayLabel())
        assertEquals(
            "git add .\ngit commit -m '{{message}}'\ngit push",
            builtIns.single().body,
        )
        assertTrue(snippetHasTemplateParameters(builtIns.single()))
    }

    @Test
    fun builtInTemplates_areHiddenFromPromptPicker() {
        assertEquals(emptyList<SnippetEntity>(), snippetsForPickerWithBuiltIns(emptyList(), SnippetKind.Prompt))
    }

    @Test
    fun builtInTemplates_doNotDuplicateUserSnippetWithSameBody() {
        val userCopy = snippet(
            body = "git add .\ngit commit -m '{{message}}'\ngit push",
            kind = "command",
        )

        assertEquals(listOf(userCopy), snippetsForPickerWithBuiltIns(listOf(userCopy), SnippetKind.Command))
    }

    @Test
    fun builtInGitTemplate_expandsAndDispatchesAsCommandSequence() {
        val builtIn = snippetsForPickerWithBuiltIns(emptyList(), SnippetKind.Command).single()
        val expanded = builtIn.copy(
            body = expandSnippetTemplate(builtIn.body, mapOf("message" to "callz")),
        )

        assertEquals(
            "git add .\rgit commit -m 'callz'\rgit push\r",
            snippetDispatchText(expanded, withEnter = true),
        )
    }

    private fun snippet(body: String, kind: String = "command"): SnippetEntity =
        SnippetEntity(
            id = 1L,
            hostId = 1L,
            label = null,
            body = body,
            kind = kind,
        )
}
