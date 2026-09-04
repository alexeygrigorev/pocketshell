package com.pocketshell.next.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Markdown renderer (task P-3b) as a real composition on the host JVM.
 *
 * Pins the block shapes a parser change could silently stop painting: a fenced
 * code block's body, a table's cells, and a list's items. `MarkdownParserTest`
 * proves the model; this proves the model reaches the screen.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownRendererTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `every block kind reaches the screen`() {
        setContent(
            """
            # Heading

            Body with **bold** and `code`.

            ```kotlin
            val answer = 42
            ```

            - alpha
            - beta

            > quoted line
            """.trimIndent(),
        )

        composeRule.onNodeWithTag(MARKDOWN_VIEW_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Heading").assertIsDisplayed()
        composeRule.onNodeWithText("val answer = 42").assertIsDisplayed()
        composeRule.onNodeWithText("alpha").assertIsDisplayed()
        composeRule.onNodeWithText("beta").assertIsDisplayed()
        composeRule.onNodeWithText("quoted line").assertIsDisplayed()
        // The fence markers themselves are never painted.
        composeRule.onNodeWithText("```kotlin").assertDoesNotExist()
    }

    @Test
    fun `a pipe table renders as cells, not as pipes`() {
        setContent(
            """
            | file | size |
            |------|------|
            | a.txt | 12 B |
            """.trimIndent(),
        )

        composeRule.onNodeWithTag(MARKDOWN_TABLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("file").assertIsDisplayed()
        composeRule.onNodeWithText("a.txt").assertIsDisplayed()
        composeRule.onNodeWithText("12 B").assertIsDisplayed()
        composeRule.onNodeWithText("| file | size |").assertDoesNotExist()
    }

    @Test
    fun `bold text carries a bold span and code carries its own run`() {
        val annotated = annotated(MarkdownParser.parseInline("plain **loud** `mono`"))

        assertEquals("plain loud mono", annotated.text)
        val bold = annotated.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("loud", annotated.text.substring(bold.start, bold.end))
        assertTrue(
            "inline code must be monospaced",
            annotated.spanStyles.any { it.item.fontFamily != null },
        )
    }

    @Test
    fun `a link becomes a tappable url annotation`() {
        val annotated = annotated(MarkdownParser.parseInline("see [docs](https://example.com/x)"))

        val link = annotated
            .getLinkAnnotations(0, annotated.length)
            .single()
            .item as LinkAnnotation.Url
        assertEquals("https://example.com/x", link.url)
    }

    @Test
    fun `a scheme-less link gets one, so the tap actually opens something`() {
        assertEquals("https://example.com", normalizeUrl("example.com"))
        assertEquals("https://example.com", normalizeUrl("https://example.com"))
        assertEquals("http://box.local:8080", normalizeUrl("http://box.local:8080"))
        assertEquals("mailto:a@b.c", normalizeUrl("mailto:a@b.c"))
    }

    private fun setContent(source: String) {
        val blocks = MarkdownParser.parse(source)
        composeRule.setContent {
            PocketShellTheme { MarkdownView(blocks = blocks) }
        }
    }
}
