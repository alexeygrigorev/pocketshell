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
    fun attachmentFileLinkStaysHomeRootedEvenWithCwd() {
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
                cwd = "/home/alexey/git/pocketshell",
            ),
            action,
        )
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
