package com.pocketshell.app.session

import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.ConversationLinkKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationLinkRoutingTest {

    @Test
    fun urlStaysOnUrlRouteWhileBothPathGuessesUseRemoteProbe() {
        assertEquals(
            ConversationTapTarget.Url("https://example.test/report"),
            conversationTapTarget(link("https://example.test/report", ConversationLinkKind.URL)),
        )
        assertEquals(
            ConversationTapTarget.RemotePath("README"),
            conversationTapTarget(link("README", ConversationLinkKind.FILE)),
        )
        assertEquals(
            ConversationTapTarget.RemotePath("release.v2"),
            conversationTapTarget(link("release.v2", ConversationLinkKind.DIRECTORY)),
        )
    }

    @Test
    fun rootedAttachmentPathIsPassedUnchangedToRemoteResolutionBoundary() {
        val attachment = "~/.pocketshell/attachments/host/screenshot.png"
        assertEquals(
            ConversationTapTarget.RemotePath(attachment),
            conversationTapTarget(link(attachment, ConversationLinkKind.FILE)),
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

    private fun link(text: String, kind: ConversationLinkKind) =
        ConversationLink(text = text, start = 0, endExclusive = text.length, kind = kind)
}
