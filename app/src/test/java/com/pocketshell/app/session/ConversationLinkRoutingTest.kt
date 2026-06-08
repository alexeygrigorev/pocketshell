package com.pocketshell.app.session

import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.ConversationLinkKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationLinkRoutingTest {

    @Test
    fun fileLinkKeepsLiteralPathAndCwdForFileViewerResolution() {
        val action = conversationLinkAction(
            link = link("out/report.png", ConversationLinkKind.FILE),
            cwd = "/home/alexey/git/pocketshell",
        )

        assertEquals(
            ConversationLinkAction.OpenFile(
                path = "out/report.png",
                cwd = "/home/alexey/git/pocketshell",
            ),
            action,
        )
    }

    @Test
    fun directoryLinkResolvesAgainstCwdBeforeOpeningBrowser() {
        val action = conversationLinkAction(
            link = link("../logs", ConversationLinkKind.DIRECTORY),
            cwd = "/home/alexey/git/pocketshell",
        )

        assertEquals(
            ConversationLinkAction.BrowseDirectory("/home/alexey/git/logs"),
            action,
        )
    }

    @Test
    fun attachmentFileLinkDropsCwdSoItStaysHomeRooted() {
        val attachment =
            "~/.pocketshell/attachments/host-1-git-pocketshell-c/" +
                "20260606-155901-01-Screenshot_20260606-155849.png"

        val action = conversationLinkAction(
            link = link(attachment, ConversationLinkKind.FILE),
            cwd = "/home/alexey/git/pocketshell",
        )

        assertEquals(
            ConversationLinkAction.OpenFile(
                path = attachment,
                cwd = null,
            ),
            action,
        )
    }

    @Test
    fun absoluteFileLinkDropsCwdSoItStaysServerRooted() {
        val action = conversationLinkAction(
            link = link("/home/alexey/.pocketshell/attachments/a.png", ConversationLinkKind.FILE),
            cwd = "/home/alexey/git/pocketshell",
        )

        assertEquals(
            ConversationLinkAction.OpenFile(
                path = "/home/alexey/.pocketshell/attachments/a.png",
                cwd = null,
            ),
            action,
        )
    }

    @Test
    fun localFileUriLinkDropsCwdSoItStaysServerRooted() {
        val action = conversationLinkAction(
            link = link(
                "file:///home/alexey/.pocketshell/attachments/a%20b.png",
                ConversationLinkKind.FILE,
            ),
            cwd = "/home/alexey/git/pocketshell",
        )

        assertEquals(
            ConversationLinkAction.OpenFile(
                path = "file:///home/alexey/.pocketshell/attachments/a%20b.png",
                cwd = null,
            ),
            action,
        )
    }

    @Test
    fun terminalFileTapKeepsCwdOnlyForProjectRelativePath() {
        val cwd = "/home/alexey/git/pocketshell"

        assertEquals(cwd, cwdForDetectedFilePath("out/report.png", cwd))
        assertEquals(null, cwdForDetectedFilePath("~/out/report.png", cwd))
        assertEquals(null, cwdForDetectedFilePath("/home/alexey/out/report.png", cwd))
        assertEquals(null, cwdForDetectedFilePath("file:///home/alexey/out/report.png", cwd))
    }

    @Test
    fun urlLinkRoutesToUrlAction() {
        val action = conversationLinkAction(
            link = link("https://github.com/alexeygrigorev/pocketshell/issues/583", ConversationLinkKind.URL),
            cwd = "/home/alexey/git/pocketshell",
        )

        assertEquals(
            ConversationLinkAction.OpenUrl("https://github.com/alexeygrigorev/pocketshell/issues/583"),
            action,
        )
    }

    private fun link(text: String, kind: ConversationLinkKind) =
        ConversationLink(text = text, start = 0, endExclusive = text.length, kind = kind)
}
