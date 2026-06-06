package com.pocketshell.core.terminal.selection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for conversation-view link detection (issue #557): file paths,
 * directories, and URLs in agent-message text become tappable. Runs under
 * Robolectric because URL detection uses Android's [android.util.Patterns].
 */
@RunWith(RobolectricTestRunner::class)
class ConversationLinkDetectionTest {

    private fun links(line: String) = detectConversationLinks(line)

    private fun kindsByText(line: String): Map<String, ConversationLinkKind> =
        links(line).associate { it.text to it.kind }

    // --- Files --------------------------------------------------------------

    @Test
    fun detectsFilePathAsFileLink() {
        val out = links("Wrote out/report.png to disk")
        assertEquals(1, out.size)
        assertEquals("out/report.png", out[0].text)
        assertEquals(ConversationLinkKind.FILE, out[0].kind)
    }

    @Test
    fun detectsAbsoluteAndTildeFilePaths() {
        val out = kindsByText("see /var/log/app.log and ~/notes/todo.md")
        assertEquals(ConversationLinkKind.FILE, out["/var/log/app.log"])
        assertEquals(ConversationLinkKind.FILE, out["~/notes/todo.md"])
    }

    @Test
    fun detectsLineBrokenPocketShellAttachmentAsOneFileLink() {
        val out = links(
            """
            Attached files:
            - ~/.pocketshell/attachments/host-1-git-pocketshell-
              c/20260606-153324-01-Screenshot_20260606-153310.png
            """.trimIndent(),
        )

        assertEquals(1, out.size)
        assertEquals(
            "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-153324-01-Screenshot_20260606-153310.png",
            out[0].text,
        )
        assertEquals(ConversationLinkKind.FILE, out[0].kind)
    }

    @Test
    fun detectsIssue583PocketShellAttachmentPathAsFileLink() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-155901-01-Screenshot_20260606-155849.png"
        val out = links("Screenshot context: `$attachment`.")

        assertEquals(1, out.size)
        assertEquals(attachment, out[0].text)
        assertEquals(ConversationLinkKind.FILE, out[0].kind)
    }

    // --- Directories --------------------------------------------------------

    @Test
    fun detectsAbsoluteDirectoryAsDirectoryLink() {
        val out = links("cd /home/alexey/git/pocketshell now")
        assertEquals(1, out.size)
        assertEquals("/home/alexey/git/pocketshell", out[0].text)
        assertEquals(ConversationLinkKind.DIRECTORY, out[0].kind)
    }

    @Test
    fun detectsTildeDirectory() {
        val out = links("the repo lives at ~/git/pocketshell")
        assertEquals(1, out.size)
        assertEquals("~/git/pocketshell", out[0].text)
        assertEquals(ConversationLinkKind.DIRECTORY, out[0].kind)
    }

    @Test
    fun directoryWithTrailingSlashKeepsSlash() {
        val out = links("in /var/log/ there are files")
        assertEquals(1, out.size)
        assertEquals("/var/log/", out[0].text)
        assertEquals(ConversationLinkKind.DIRECTORY, out[0].kind)
    }

    // --- URLs ---------------------------------------------------------------

    @Test
    fun detectsHttpsUrlAsUrlLink() {
        val out = links("docs at https://github.com/owner/repo here")
        assertEquals(1, out.size)
        assertEquals("https://github.com/owner/repo", out[0].text)
        assertEquals(ConversationLinkKind.URL, out[0].kind)
    }

    @Test
    fun detectsLoopbackUrl() {
        val out = links("dev server on http://localhost:3000/admin")
        assertEquals(1, out.size)
        assertEquals("http://localhost:3000/admin", out[0].text)
        assertEquals(ConversationLinkKind.URL, out[0].kind)
    }

    @Test
    fun urlPathTailIsNotReSurfacedAsFile() {
        // The `/repo/main.kt` tail of a URL must NOT also be a file link.
        val out = links("https://github.com/o/repo/main.kt")
        assertEquals(1, out.size)
        assertEquals(ConversationLinkKind.URL, out[0].kind)
    }

    // --- Mixed / ordering ---------------------------------------------------

    @Test
    fun detectsMultipleHeterogeneousLinksLeftToRight() {
        val line = "open ~/proj/a.kt then /tmp/out and https://x.io/y"
        val out = links(line)
        assertEquals(3, out.size)
        assertEquals("~/proj/a.kt" to ConversationLinkKind.FILE, out[0].text to out[0].kind)
        assertEquals("/tmp/out" to ConversationLinkKind.DIRECTORY, out[1].text to out[1].kind)
        assertEquals("https://x.io/y" to ConversationLinkKind.URL, out[2].text to out[2].kind)
        // Sorted by position.
        assertTrue(out[0].start < out[1].start)
        assertTrue(out[1].start < out[2].start)
    }

    @Test
    fun trailingSentencePunctuationIsStrippedFromTarget() {
        val out = links("see https://x.io/y, and out/r.png.")
        val byKind = out.associate { it.kind to it.text }
        assertEquals("https://x.io/y", byKind[ConversationLinkKind.URL])
        assertEquals("out/r.png", byKind[ConversationLinkKind.FILE])
    }

    // --- Negatives ----------------------------------------------------------

    @Test
    fun plainProseHasNoLinks() {
        assertTrue(links("This is a normal sentence with no paths.").isEmpty())
    }

    @Test
    fun bareDomainIsNotAUrlLink() {
        // A schemeless domain mentioned in prose is not a tap target.
        assertTrue(links("visit example.com for details").none { it.kind == ConversationLinkKind.URL })
    }

    @Test
    fun fractionIsNotADirectory() {
        // `5/2` is not a rooted path so the conservative directory shape skips it.
        assertTrue(links("the ratio is 5/2 today").isEmpty())
    }
}
