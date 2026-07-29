package com.pocketshell.app.tmux

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1854 — pin the TWO call sites whose drift produced the reported
 * `printf` -> `tf` leading-character loss, so neither can silently regrow.
 *
 * The behavioural halves live in
 * [com.pocketshell.core.terminal.input.BracketedPasteTest] (the predicates),
 * [TmuxSessionViewModelInputTest] (the tmux command wire and the received
 * bytes), and `Issue1854TypedNewlineCommitReachesPaneDockerTest` (the real
 * transport, container-side oracle). This is the compile-free guard that makes
 * the CLASS non-recurring: both sites are one-line policy decisions in files
 * that are edited often, and each one alone is enough to bring the defect back.
 *
 * Runs in the per-push required `Unit tests` job, both variants.
 */
class Issue1854PasteFramingSourceGuardTest {

    /**
     * `TerminalView` must classify a commit by its CONTENT line breaks. Using
     * `containsLineBreak` here turns the everyday "type a command, press Enter"
     * gesture (an AOSP-family keyboard commits Enter as a literal `\n` appended
     * to the text) into a bracketed paste, which is never submitted.
     */
    @Test
    fun `TerminalView routes a paste on content line breaks, not any line break`() {
        val path = "shared/core-terminal/src/main/java/com/termux/view/TerminalView.java"
        val text = source(path)
        assertTrue(
            "$path must classify a multi-line paste with BracketedPaste.hasContentLineBreak " +
                "(issue #1854: a single TRAILING newline is a submit, not paste content)",
            text.contains("return BracketedPaste.hasContentLineBreak(text);"),
        )
        assertTrue(
            "$path must not reintroduce the any-LF paste classifier (issue #1854)",
            !text.contains("BracketedPaste.containsLineBreak(text)"),
        )
    }

    /**
     * The tmux lane must DELIVER an already-framed block, never frame it twice.
     * A doubled frame puts the inner `\e[200~`/`\e[201~` into the receiving
     * program as literal content, and busybox `ash` discards a 16-byte keycode
     * buffer on the unrecognised escape run — the reported 4-character leading
     * loss.
     */
    @Test
    fun `the tmux input lane never frames an already-framed paste block twice`() {
        val cmdPath = "app/src/main/java/com/pocketshell/app/tmux/TmuxInputCommands.kt"
        val cmd = source(cmdPath)
        assertTrue(
            "$cmdPath: deliverPaneInputBytes must pass an ALREADY-framed block through " +
                "sendPasteBlock (issue #1854)",
            cmd.contains("if (BracketedPaste.isFramed(bytes)) {") &&
                cmd.contains("sendPasteBlock(client, paneId, bytes)"),
        )
        assertTrue(
            "app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt must route " +
                "pane input through deliverPaneInputBytes (issue #1854)",
            source("app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt")
                .contains("deliverPaneInputBytes(client, paneId, bytes)"),
        )
        assertTrue(
            "$cmdPath: sendPasteBlock must deliver its bytes without re-framing them " +
                "(issue #1854)",
            cmd.contains("BracketedPaste.textChunks(framedBytes, TMUX_PASTE_BODY_CHUNK_BYTES)"),
        )
        assertTrue(
            "$cmdPath: sendBracketedPaste is the ONLY site that applies the frame " +
                "(issue #1854)",
            cmd.contains("sendPasteBlock(client, paneId, BracketedPaste.frame(bytes), beforeCommit)"),
        )
    }

    private fun source(path: String): String {
        var cursor = java.io.File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = java.io.File(cursor, path)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: return@repeat
        }
        error("Cannot locate $path from ${System.getProperty("user.dir")}")
    }
}
