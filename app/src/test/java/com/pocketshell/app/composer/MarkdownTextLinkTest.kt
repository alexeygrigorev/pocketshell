package com.pocketshell.app.composer

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.ConversationLinkKind
import com.pocketshell.uikit.theme.PocketShellColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #557: when [MarkdownText] is given an `onLinkTap`, file/dir/URL targets
 * in the rendered text become tappable [LinkAnnotation.Clickable] spans whose
 * tap routes the detected [ConversationLink]. Runs under Robolectric because URL
 * detection touches Android's `Patterns`.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownTextLinkTest {

    private fun clickableLinks(rendered: androidx.compose.ui.text.AnnotatedString) =
        rendered.getLinkAnnotations(0, rendered.length)
            .map { it.item }
            .filterIsInstance<LinkAnnotation.Clickable>()

    @Test
    fun filePathBecomesAClickableLinkAndRoutesTheTap() {
        val tapped = mutableListOf<ConversationLink>()
        val rendered = renderInline("Wrote out/report.png to disk") { tapped += it }

        // Visible text is unchanged (the path stays in the prose).
        assertEquals("Wrote out/report.png to disk", rendered.text)

        val links = clickableLinks(rendered)
        assertEquals(1, links.size)
        assertEquals(
            TextDecoration.Underline,
            links[0].styles?.style?.textDecoration,
        )
        assertEquals(
            PocketShellColors.Accent,
            links[0].styles?.style?.color,
        )

        // Firing the link's listener routes the detected FILE link.
        links[0].linkInteractionListener?.onClick(links[0])
        assertEquals(1, tapped.size)
        assertEquals("out/report.png", tapped[0].text)
        assertEquals(ConversationLinkKind.FILE, tapped[0].kind)
    }

    @Test
    fun urlBecomesAClickableLink() {
        val tapped = mutableListOf<ConversationLink>()
        val rendered =
            renderInline("docs at https://github.com/o/r here") { tapped += it }
        val links = clickableLinks(rendered)
        assertEquals(1, links.size)
        links[0].linkInteractionListener?.onClick(links[0])
        assertEquals(ConversationLinkKind.URL, tapped[0].kind)
        assertEquals("https://github.com/o/r", tapped[0].text)
    }

    @Test
    fun bareLoopbackPortBecomesAClickableUrlLink() {
        val tapped = mutableListOf<ConversationLink>()
        val rendered =
            renderInline("dev server at localhost:5173") { tapped += it }
        val links = clickableLinks(rendered)
        assertEquals(1, links.size)
        links[0].linkInteractionListener?.onClick(links[0])
        assertEquals(ConversationLinkKind.URL, tapped[0].kind)
        assertEquals("localhost:5173", tapped[0].text)
    }

    @Test
    fun lineWrappedAttachmentBulletBecomesOneClickableHomeRelativeFileLink() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-course-management-platform/" +
                "20260607-115723-01-Screenshot_20260607-115718.png"
        val bullet = parseMarkdownBlocks(
            """
            - ~/.pocketshell/attachments/host-1-git-course-management-
              platform/20260607-115723-01-Screenshot_20260607-115718.png
            """.trimIndent(),
        ).single() as MarkdownBlock.Bullet
        val tapped = mutableListOf<ConversationLink>()

        val rendered = renderInline(bullet.text) { tapped += it }
        val links = clickableLinks(rendered)
        assertEquals(1, links.size)

        links[0].linkInteractionListener?.onClick(links[0])
        assertEquals(1, tapped.size)
        assertEquals(ConversationLinkKind.FILE, tapped[0].kind)
        assertEquals(attachment, tapped[0].text)
        assertTrue(tapped[0].text.startsWith("~/"))
    }

    @Test
    fun lineWrappedGeneralPathBulletBecomesOneClickableFileLink() {
        // Issue #753: a non-attachment absolute path wrapped across a bullet's
        // line break (the renderer split it at a `/` segment boundary) is ONE
        // clickable link whose tap opens the FULL path — even though the bullet
        // parser strips the continuation indentation before joining.
        val full = "/home/alexey/inbox/pocketshell/screenshot-20260613-091500.png"
        val bullet = parseMarkdownBlocks(
            """
            - /home/alexey/inbox/pocketshell/
              screenshot-20260613-091500.png
            """.trimIndent(),
        ).single() as MarkdownBlock.Bullet
        val tapped = mutableListOf<ConversationLink>()

        val rendered = renderInline(bullet.text) { tapped += it }
        val links = clickableLinks(rendered)
        assertEquals(1, links.size)

        links[0].linkInteractionListener?.onClick(links[0])
        assertEquals(1, tapped.size)
        assertEquals(ConversationLinkKind.FILE, tapped[0].kind)
        assertEquals(full, tapped[0].text)
    }

    @Test
    fun quotedWhitespacePathBecomesOneClickableFileLink() {
        // Issue #753: a quoted path that contains spaces is one clickable link
        // whose tap opens the full (unquoted) path.
        val tapped = mutableListOf<ConversationLink>()
        val rendered =
            renderInline("""saved to "/home/alexey/My Docs/report final.pdf" ok""") {
                tapped += it
            }
        val links = clickableLinks(rendered)
        assertEquals(1, links.size)
        assertEquals(
            TextDecoration.Underline,
            links[0].styles?.style?.textDecoration,
        )

        links[0].linkInteractionListener?.onClick(links[0])
        assertEquals(1, tapped.size)
        assertEquals(ConversationLinkKind.FILE, tapped[0].kind)
        assertEquals("/home/alexey/My Docs/report final.pdf", tapped[0].text)
    }

    @Test
    fun noLinkTapHandlerLeavesTextWithoutClickableLinks() {
        val rendered = renderInline("Wrote out/report.png to disk")
        assertTrue(clickableLinks(rendered).isEmpty())
    }

    @Test
    fun multipleTargetsEachBecomeTheirOwnLink() {
        val tapped = mutableListOf<ConversationLink>()
        val rendered =
            renderInline("open ~/proj/a.kt then /tmp/out dir") { tapped += it }
        val links = clickableLinks(rendered)
        assertEquals(2, links.size)
        links.forEach { it.linkInteractionListener?.onClick(it) }
        assertEquals(
            setOf(ConversationLinkKind.FILE, ConversationLinkKind.DIRECTORY),
            tapped.map { it.kind }.toSet(),
        )
    }
}
