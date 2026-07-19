package com.pocketshell.app.tmux

import android.os.Looper
import com.pocketshell.core.terminal.ui.TerminalRawInputPolicy
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.uikit.model.KeyKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelInputTest : TmuxSessionViewModelTestBase() {

    @Test
    fun terminalGeneratedInputResponseRoutesAsRawHex() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "\u001b]11;rgb:0101/0404/0909\u001b\\".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf("send-keys -H -t %0 1b 5d 31 31 3b 72 67 62 3a 30 31 30 31 2f 30 34 30 34 2f 30 39 30 39 1b 5c"),
            sent,
        )
    }

    @Test
    fun singleEscapeInputStillUsesNamedKeyPath() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", byteArrayOf(0x1B))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(listOf("send-keys -t %0 Escape"), sent)
    }

    @Test
    fun writeInputToPaneIgnoresEmptyBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", ByteArray(0))
        advanceUntilIdle()

        assertTrue(
            "empty input must not produce a send-keys command",
            client.sentCommands.none { it.startsWith("send-keys") },
        )
    }

    @Test
    fun writeInputToPaneResultPropagatesFailedPaneWrite() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
            closeAndThrowException = TmuxClientException("failed to write tmux command `send-keys`")
        }
        vm.attachClientForTest(client)

        val result = vm.writeInputToPaneResult("%0", "hello".toByteArray(Charsets.UTF_8))

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected TmuxClientException, got ${error?.javaClass?.name}", error is TmuxClientException)
        assertTrue(error?.message?.contains("send-keys") == true)
        assertEquals(listOf("send-keys -l -t %0 -- 'hello'"), client.sentCommands.filter { it.startsWith("send-keys") })
        assertTrue("failed pane write must close the dead tmux client", client.closed)
    }

    /**
     * Issue #1636: this asserted the WIRE SHAPE (three `send-keys -H` commands,
     * hex `0a` token counts) — a proxy that stayed green while the bytes the pane
     * received were corrupt. It now asserts the bytes themselves, through the
     * [FakeTmuxPaneServer] input-box model.
     */
    @Test
    fun writeInputToPaneWrapsMultiLineInputInBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxPaneServer()
        vm.attachClientForTest(client)

        val payload = "para one\npara two\npara three"
        vm.writeInputToPane("%4", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        // The pane receives the payload verbatim: the bracketed-paste markers are
        // consumed by the receiver and the LFs stay literal content bytes.
        assertEquals(payload, client.inputBox("%4"))
        assertTrue(
            "multi-line input must not submit — the LFs are paste content, not Enter",
            client.submittedPrompts("%4").isEmpty(),
        )
        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "multi-line input must not emit a separate Enter named-key, got $sent",
            sent.none { it.contains(" Enter") },
        )
    }

    /** Issue #1636: byte assertion replaces the old hex-token-count proxy. */
    @Test
    fun writeInputToPaneNormalisesCrLfToLfInsideBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxPaneServer()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "alpha\r\nbeta".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertEquals("CR LF must reach the pane as a single LF", "alpha\nbeta", client.inputBox("%0"))
        assertTrue(client.submittedPrompts("%0").isEmpty())
    }

    @Test
    fun writeInputToPaneKeepsSingleLineInputOnTheLiteralPath() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "hello".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(1, sent.size)
        assertEquals("send-keys -l -t %0 -- 'hello'", sent[0])
        assertTrue(
            "single-line input must not use the -H bracketed-paste path, got $sent",
            sent.none { it.startsWith("send-keys -H") },
        )
    }

    @Test
    fun writeInputToPaneIssuesSendKeysWithLiteralBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Submission semantics: a `\r` byte (carriage return) is the
        // "Enter / submit" signal coming from the terminal emulator
        // and from the in-app callers (chips, snippets with-Enter,
        // composer send) — see [TmuxSessionScreen]. The single-line
        // route keeps the existing two-token send-keys shape so
        // keyboard typing still submits cleanly.
        //
        // A literal `\n` byte is reserved for the bracketed-paste
        // multi-line route (issue #209); we cover that in
        // [writeInputToPaneWrapsMultiLineInputInBracketedPaste].
        vm.writeInputToPane("%0", "ls\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(sent.toString(), 2, sent.size)
        assertEquals("send-keys -l -t %0 -- 'ls'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun writeInputToPaneExitsTmuxCopyModeBeforeSendingBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "shell",
                    paneIndex = 0,
                    inCopyMode = true,
                ),
            ),
        )

        val result = vm.writeInputToPaneResult("%0", "ls\r".toByteArray(Charsets.UTF_8))
        runCurrent()

        assertTrue("copy-mode recovery should keep pane input successful", result.isSuccess)
        assertFalse("copy-mode recovery must not mark tmux disconnected", client.disconnected.value)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            listOf(
                "send-keys -X -t %0 cancel",
                "send-keys -l -t %0 -- 'ls'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(vm.panes.value.single { it.paneId == "%0" }.inCopyMode)
    }

    @Test
    fun writeInputToPaneSendKeysFailureSurfacesFailedStatus() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
            closeAndThrowException = TmuxClientException(
                "tmux command `send-keys` timed out after 100ms",
            )
            closeAndThrowDisconnectEvent = TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.CommandTimeout,
                source = "command_timeout",
                intent = "command_timeout",
                commandKind = "send-keys",
                timeoutMode = "fatal",
            )
        }
        vm.attachClientForTest(client)
        runCurrent()

        vm.writeInputToPane("%0", "ls\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertTrue(
            "expected send-keys dispatch, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("send-keys") },
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after send-keys failure, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Tmux command timed out from test@test:0. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun writeInputToPaneSeparatesLeadingDashLiteralFromTmuxOptions() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "-tproof".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- '-tproof'", cmd)
    }

    @Test
    fun writeInputToPaneEscapesSingleQuotesViaCloseEscapeOpen() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%2", "it's".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        // Single-quote sequence: closes ', escapes the literal quote, then
        // reopens. The composer wraps with outer single quotes too.
        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertTrue(
            "expected POSIX-shell-style escape in $cmd",
            cmd == "send-keys -l -t %2 -- 'it'\\''s'",
        )
    }

    /**
     * Issue #1636: byte assertion replaces the old "3 send-keys -H invocations"
     * shape proxy — what matters is that the trailing LF reaches the pane as paste
     * CONTENT (not as an Enter that would submit).
     */
    @Test
    fun writeInputToPaneTrailingNewlineGoesThroughBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxPaneServer()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "ls\n".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertEquals("ls\n", client.inputBox("%0"))
        assertTrue(
            "the trailing LF is paste content, not a submit",
            client.submittedPrompts("%0").isEmpty(),
        )
    }

    /**
     * Issue #1636: a large paste must stay BOUNDED per tmux command (no unbounded
     * command line) AND arrive byte-exact. The old version asserted only the hex
     * chunk shape; it could not have seen a payload corrupted by the chunking.
     */
    @Test
    fun largeBracketedPasteIsSplitIntoBoundedCommandsAndArrivesByteExact() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxPaneServer()
        vm.attachClientForTest(client)

        val payload = buildString {
            append("first line\n")
            repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3) { append(('a'.code + (it % 26)).toChar()) }
        }
        vm.writeInputToPane("%0", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertEquals("the large paste must arrive byte-exact", payload, client.inputBox("%0"))

        val fill = client.sentCommands.filter { it.startsWith("set-buffer ") }
        assertTrue("expected a multi-chunk bounded fill, got ${fill.size}: $fill", fill.size > 3)
        assertTrue(
            "the fill must not fall back to one unbounded command: ${fill.map { it.length }}",
            fill.all { it.length <= "set-buffer -ab pspaste0 -- ''".length + TMUX_PASTE_BODY_CHUNK_BYTES + 8 },
        )
        assertEquals(
            "the payload must reach the pane through exactly ONE commit: ${client.sentCommands}",
            1,
            client.sentCommands.count { it.startsWith("paste-buffer ") },
        )
    }

    @Test
    fun buildBracketedPasteHexEmitsExpectedSequenceForKnownInput() {
        val vm = newVm()
        val hex = vm.buildBracketedPasteHexForTest("a\nb".toByteArray(Charsets.UTF_8))
        assertEquals(
            "1b 5b 32 30 30 7e 61 0a 62 1b 5b 32 30 31 7e",
            hex,
        )
    }

    @Test
    fun containsLineBreakIsTrueOnlyForLf() {
        val vm = newVm()
        assertTrue(vm.containsLineBreakForTest("a\nb".toByteArray(Charsets.UTF_8)))
        assertTrue(vm.containsLineBreakForTest("a\r\nb".toByteArray(Charsets.UTF_8)))
        assertFalse(vm.containsLineBreakForTest("a b".toByteArray(Charsets.UTF_8)))
        assertFalse(vm.containsLineBreakForTest("a\rb".toByteArray(Charsets.UTF_8)))
        assertFalse(vm.containsLineBreakForTest(ByteArray(0)))
    }

    @Test
    fun terminalStateInputRoutesThroughTmuxSendKeys() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        vm.panes.value.single().terminalState.writeInput("echo ok\r".toByteArray(Charsets.UTF_8))
        waitForSentCommandCount(client, expectedCount = 2)

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- 'echo ok'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun onKeyBarKeyTranslatesLabelsToTmuxNamedKeys() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "Esc")
        vm.onKeyBarKey("%0", "Tab")
        vm.onKeyBarKey("%0", "←")
        vm.onKeyBarKey("%0", "↑")
        vm.onKeyBarKey("%0", "↓")
        vm.onKeyBarKey("%0", "→")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(6, sent.size)
        assertTrue(sent[0].endsWith("Escape"))
        assertTrue(sent[1].endsWith("Tab"))
        assertTrue(sent[2].endsWith("Left"))
        assertTrue(sent[3].endsWith("Up"))
        assertTrue(sent[4].endsWith("Down"))
        assertTrue(sent[5].endsWith("Right"))
        assertTrue(sent.all { it.contains("-t %0") })
    }

    @Test
    fun onKeyBarKeyIgnoresUnknownLabel() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "ZorkKey")
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    @Test
    fun onKeyBarKeyShiftTabSendsTmuxBackTabNamedKey() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "⇧Tab")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf("send-keys -t %0 BTab"),
            sent,
        )
    }

    @Test
    fun onKeyBarKeyEnterSendsTmuxEnterNamedKeyWithoutReflow() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "⏎")
        vm.onKeyBarKey("%0", "Enter")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -t %0 Enter",
                "send-keys -t %0 Enter",
            ),
            sent,
        )
        assertTrue(
            "Enter must not trigger a resize/refresh-client",
            client.sentCommands.none { it.startsWith("refresh-client") },
        )
    }

    @Test
    fun onKeyBarKeySendsCuratedCtrlCombosAsRawControlBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "^A")
        vm.onKeyBarKey("%0", "^B")
        vm.onKeyBarKey("%0", "^C")
        vm.onKeyBarKey("%0", "^D")
        vm.onKeyBarKey("%0", "^E")
        vm.onKeyBarKey("%0", "^L")
        vm.onKeyBarKey("%0", "^R")
        vm.onKeyBarKey("%0", "^Z")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 01",
                "send-keys -H -t %0 02",
                "send-keys -H -t %0 03",
                "send-keys -H -t %0 04",
                "send-keys -H -t %0 05",
                "send-keys -H -t %0 0c",
                "send-keys -H -t %0 12",
                "send-keys -H -t %0 1a",
            ),
            sent,
        )
    }

    @Test
    fun ctrlBHotkeyDoubleTapSendsRawCtrlBByteTwice() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "^B")
        vm.onKeyBarKey("%0", "^B")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 02",
                "send-keys -H -t %0 02",
            ),
            sent,
        )
    }

    @Test
    fun hotkeyPanelSectionsAreDeDupedAndCarryTheAuditedSet() {
        val labels = TmuxHotkeyPanelSections.flatMap { it.keys }.map { it.label }
        assertEquals(
            listOf(
                "←", "↑", "↓", "→",
                "Esc", "Tab", "Enter", "^C", "^D",
                "^A", "^B", "^C", "^D", "^E", "^G", "^J", "^K", "^L", "^O",
                "^R", "^T", "^U", "^W", "^X", "^Z", "^\\",
                "⇧Tab",
                TmuxHotkeyInterruptX2Label, TmuxHotkeyEofX2Label,
                TmuxHotkeyCtrlModifierLabel,
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
                "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
            ),
            labels,
        )
        assertTrue(labels.contains("⇧Tab"))
        val duplicated = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(setOf("^C", "^D"), duplicated)
        assertTrue(labels.contains(TmuxHotkeyCtrlModifierLabel))
        val ctrlKey = TmuxHotkeyPanelSections.flatMap { it.keys }
            .single { it.label == TmuxHotkeyCtrlModifierLabel }
        assertEquals(KeyKind.Modifier, ctrlKey.kind)
        assertFalse(labels.contains("/"))
        assertTrue(labels.contains("^B"))
        listOf("^G", "^J", "^K", "^O", "^T", "^U", "^W", "^X", "^\\").forEach {
            assertTrue("CTRL COMBOS must offer the filled key $it", labels.contains(it))
        }
        ('a'..'z').forEach { c ->
            assertTrue("LETTERS must offer '$c'", labels.contains(c.toString()))
        }
        assertTrue(labels.contains(TmuxHotkeyInterruptX2Label))
        assertTrue(labels.contains(TmuxHotkeyEofX2Label))
        assertTrue(labels.contains("^C"))
        assertTrue(labels.contains("^D"))
        val arrows = TmuxHotkeyPanelSections.first().keys
        assertEquals(listOf("←", "↑", "↓", "→"), arrows.map { it.label })
        assertTrue(arrows.all { it.kind == KeyKind.Arrow })
    }

    @Test
    fun onKeyBarKeyInterruptAndEofDoubledChordsSendByteTwice() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", TmuxHotkeyInterruptX2Label)
        vm.onKeyBarKey("%0", TmuxHotkeyEofX2Label)
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 03 03",
                "send-keys -H -t %0 04 04",
            ),
            sent,
        )
    }

    @Test
    fun keyBarControlEscapeAndHotkeysClearSmartTextBeforeTmuxRawSends() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                client.sentCommands.add("flush-staged")
            }
        }

        vm.onKeyBarKey("%0", "^C")
        vm.onKeyBarKey("%0", "Esc")
        vm.onKeyBarKey("%0", "→")
        advanceUntilIdle()

        assertEquals(
            listOf(
                TerminalRawInputPolicy.ClearSmartText,
                TerminalRawInputPolicy.ClearSmartText,
                TerminalRawInputPolicy.ClearSmartText,
            ),
            policies,
        )
        assertEquals(
            listOf(
                "send-keys -H -t %0 03",
                "send-keys -t %0 Escape",
                "send-keys -t %0 Right",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(client.sentCommands.contains("flush-staged"))
    }

    @Test
    fun keyBarEnterFlushesSmartTextBeforeTmuxEnter() = runTest(scheduler) {
        val vm = newVm()
        val literalFlushGate = CompletableDeferred<Unit>()
        val client = FakeTmuxClient().apply {
            sendCommandGatePrefix = "send-keys -l -t %0 -- 'staged'"
            sendCommandGate = literalFlushGate
        }
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                state.writeInput("staged".toByteArray(Charsets.UTF_8))
            }
        }

        try {
            vm.onKeyBarKey("%0", "⏎")
            waitForSentCommandCount(client, expectedCount = 1)

            assertEquals(listOf(TerminalRawInputPolicy.FlushSmartText), policies)
            assertEquals(
                "Enter must wait behind the queued SmartText flush while the literal send is suspended",
                listOf("send-keys -l -t %0 -- 'staged'"),
                client.sentCommands.filter { it.startsWith("send-keys") },
            )

            literalFlushGate.complete(Unit)
            waitForSentCommandCount(client, expectedCount = 2)

            assertEquals(
                listOf(
                    "send-keys -l -t %0 -- 'staged'",
                    "send-keys -t %0 Enter",
                ),
                client.sentCommands.filter { it.startsWith("send-keys") },
            )
        } finally {
            literalFlushGate.complete(Unit)
            state.setSmartTextStagingBridgeForTest(null)
        }
    }

    @Test
    fun directTmuxControlHotkeyClearsSmartTextBeforeSendingRawBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                client.sentCommands.add("flush-staged")
            }
        }

        vm.sendControlInputToPane("%0", CtrlCByte, repeatCount = 2)
        advanceUntilIdle()

        assertEquals(listOf(TerminalRawInputPolicy.ClearSmartText), policies)
        assertEquals(
            listOf("send-keys -H -t %0 03 03"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(client.sentCommands.contains("flush-staged"))
    }

    @Test
    fun sendControlInputToPaneCanSendDoublePressPayload() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.sendControlInputToPane("%0", CtrlCByte, repeatCount = 2)
        vm.sendControlInputToPane("%0", CtrlDByte, repeatCount = 2)
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 03 03",
                "send-keys -H -t %0 04 04",
            ),
            sent,
        )
    }

    @Test
    fun dequeuedTmuxInputSendFailureRetriesOnceBeforeRecordingSent() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            throwOnCommandPrefix = "send-keys -l -t %0"
            throwOnCommandRemaining = 1
        }
        vm.attachClientForTest(client)
        val sink = vm.tmuxInputSinkForTest("%0")

        sink.write("retry".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()

        val literalSends = client.sentCommands.filter { it.startsWith("send-keys -l -t %0") }
        assertEquals(
            "the dequeued batch must be retried once after a post-dequeue send failure",
            2,
            literalSends.size,
        )
        val metrics = vm.tmuxInputMetricsForTest("%0") ?: error("input metrics should be recorded")
        assertEquals(5L, metrics.totalEnqueuedBytes)
        assertEquals(
            "bytes should only be counted sent after the retry succeeds",
            5L,
            metrics.totalSentBytes,
        )
        assertFalse("one recovered send failure must not disconnect the client", client.disconnected.value)
    }

    @Test
    fun tmuxHighRateInputStressBatchesWithBoundedBacklogAndNoContentLoss() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            sendCommandDelayMs = 3L
        }
        vm.attachClientForTest(client)
        val sink = vm.tmuxInputSinkForTest("%0")
        val chunks = List(800) { index ->
            ("stress-${index.toString().padStart(4, '0')}-" + "x".repeat(51))
                .toByteArray(Charsets.US_ASCII)
        }
        val expected = chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val writer = Thread({
            chunks.forEach { sink.write(it) }
        }, "tmux-input-stress-writer")
        writer.start()

        waitForTmuxInputBytes(vm, paneId = "%0", expectedBytes = expected.size.toLong(), writer = writer)
        writer.join(1_000)
        sink.close()

        val metrics = vm.tmuxInputMetricsForTest("%0")
            ?: error("stress metrics should be recorded")
        assertEquals(expected.size.toLong(), metrics.totalEnqueuedBytes)
        assertEquals(expected.size.toLong(), metrics.totalSentBytes)
        assertTrue(
            "stress should build real backlog; metrics=$metrics",
            metrics.maxPendingBytes > TMUX_INPUT_MAX_BATCH_BYTES,
        )
        assertTrue(
            "backlog must stay within bounded queue capacity; metrics=$metrics",
            metrics.maxPendingBytes <= vm.tmuxInputCapacityBytesForTest(),
        )
        assertTrue("input should be batched; metrics=$metrics", metrics.sentBatchCount < chunks.size)
        assertTrue("batch size metric should be recorded; metrics=$metrics", metrics.maxBatchBytes > chunks.first().size)
        assertTrue("batch size must remain bounded; metrics=$metrics", metrics.maxBatchBytes <= TMUX_INPUT_MAX_BATCH_BYTES)
        assertTrue("send latency metric should be recorded; metrics=$metrics", metrics.maxSendLatencyMs > 0.0)
        writeTmuxInputStressReport(metrics, expectedBytes = expected.size, chunks = chunks.size)

        val reconstructed = client.sentCommands
            .filter { it.startsWith("send-keys -l -t %0 -- '") }
            .joinToString(separator = "") { command ->
                command.substringAfter("-- '").removeSuffix("'")
            }
            .toByteArray(Charsets.US_ASCII)
        assertEquals(
            "high-rate stress must not lose or reorder input bytes",
            expected.toList(),
            reconstructed.toList(),
        )
    }

    @Test
    fun tmuxInputStreamDropsOverflowAndAcceptsLaterBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            sendCommandGatePrefix = "send-keys -l -t %0"
            sendCommandGate = CompletableDeferred()
        }
        vm.attachClientForTest(client)
        val sink = vm.tmuxInputSinkForTest("%0")
        val chunk = ByteArray(TMUX_INPUT_CHUNK_BYTES) { 'x'.code.toByte() }

        repeat(vm.tmuxInputCapacityBytesForTest() / TMUX_INPUT_CHUNK_BYTES) {
            sink.write(chunk)
        }
        runCurrent()

        val overflow = runCatching { sink.write("overflow".toByteArray(Charsets.US_ASCII)) }
        assertTrue(
            "the bridge-facing input stream must drop overflow instead of killing the input drainer",
            overflow.isSuccess,
        )
        client.sendCommandGate?.complete(Unit)
        advanceUntilIdle()

        sink.write("after".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()

        val literalSends = client.sentCommands.filter { it.startsWith("send-keys -l -t %0") }
        assertTrue(
            "later input must still reach tmux after a transient full-queue drop",
            literalSends.any { it.contains("'after'") },
        )
        assertTrue(
            "overflow bytes should be dropped rather than replayed after the backlog drains",
            literalSends.none { it.contains("overflow") },
        )
    }

    @Test
    fun terminalDaQueryResponsesSuppressedInBridgeMode() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val queryBytes = "\u001b[c".toByteArray(Charsets.US_ASCII)
        state.appendRemoteOutput(queryBytes)
        drainTerminalBridgeHandler()

        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "bridge-mode emulator must not generate DA query responses, got $sent",
            sent.none { it.contains("send-keys -H") || it.contains("send-keys -l") },
        )
    }

    @Test
    fun terminalOsc11QueryResponsesSuppressedInBridgeMode() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val queryBytes = "\u001b]11;?\u001b\\".toByteArray(Charsets.US_ASCII)
        state.appendRemoteOutput(queryBytes)
        drainTerminalBridgeHandler()

        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "bridge-mode emulator must not generate OSC 11 color query responses, got $sent",
            sent.none { it.contains("send-keys -H") || it.contains("send-keys -l") },
        )
    }

    @Test
    fun tmuxInputStreamLargeSingleWriteEnqueuesBoundedPrefixInsteadOfDroppingWholePaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            sendCommandGatePrefix = "send-keys -l -t %0"
            sendCommandGate = CompletableDeferred()
        }
        vm.attachClientForTest(client)
        val sink = vm.tmuxInputSinkForTest("%0")
        val capacity = vm.tmuxInputCapacityBytesForTest()
        val payload = ByteArray(capacity + TMUX_INPUT_CHUNK_BYTES) { index ->
            if (index < capacity) 'a'.code.toByte() else 'z'.code.toByte()
        }

        val write = runCatching { sink.write(payload) }
        assertTrue(
            "a single paste larger than the queue capacity must not be rejected before any bytes are enqueued",
            write.isSuccess,
        )
        val beforeDrain = vm.tmuxInputMetricsForTest("%0")
            ?: error("input metrics should be recorded")
        assertEquals(
            "the bounded queue should retain the prefix it can send instead of dropping the whole paste",
            capacity.toLong(),
            beforeDrain.totalEnqueuedBytes,
        )

        runCurrent()
        client.sendCommandGate?.complete(Unit)
        advanceUntilIdle()

        val sentText = client.sentCommands
            .filter { it.startsWith("send-keys -l -t %0 -- '") }
            .joinToString(separator = "") { command ->
                command.substringAfter("-- '").removeSuffix("'")
            }
        assertEquals(
            "only the bounded prefix should be sent after the overflow tail is dropped",
            capacity,
            sentText.length,
        )
        assertTrue("the retained prefix should reach tmux", sentText.all { it == 'a' })
        assertFalse("overflow tail bytes must not be replayed later", sentText.any { it == 'z' })
    }

    @Test
    fun tmuxInputStreamWriteAfterCloseThrowsInsteadOfSilentlyDroppingBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        val sink = vm.tmuxInputSinkForTest("%0")

        sink.close()
        val write = runCatching { sink.write("after-close".toByteArray(Charsets.US_ASCII)) }

        assertTrue(
            "writes after close must be observable failures, not silent successful drops",
            write.isFailure,
        )
        assertTrue(
            "closed queue writes should fail with IOException",
            write.exceptionOrNull() is IOException,
        )
        advanceUntilIdle()
        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    private fun TestScope.waitForTmuxInputBytes(
        vm: TmuxSessionViewModel,
        paneId: String,
        expectedBytes: Long,
        writer: Thread,
    ) {
        repeat(10_000) {
            advanceTimeBy(3L)
            runCurrent()
            val metrics = vm.tmuxInputMetricsForTest(paneId)
            if (metrics?.totalSentBytes == expectedBytes && !writer.isAlive) return
            Thread.sleep(1)
        }
        val metrics = vm.tmuxInputMetricsForTest(paneId)
        assertEquals(
            "timed out waiting for tmux input stress drain; metrics=$metrics writerAlive=${writer.isAlive}",
            expectedBytes,
            metrics?.totalSentBytes,
        )
    }

    private fun writeTmuxInputStressReport(
        metrics: TmuxInputStressMetrics,
        expectedBytes: Int,
        chunks: Int,
    ) {
        val outputDir = if (File("settings.gradle.kts").isFile) {
            File("app/build/reports/tmux-input-stress")
        } else {
            File("build/reports/tmux-input-stress")
        }
        val report = File(outputDir, "high-rate-input.json")
        report.parentFile?.mkdirs()
        report.writeText(
            """
            {
              "stress": "tmux-high-rate-input",
              "input_chunks": $chunks,
              "expected_bytes": $expectedBytes,
              "max_pending_capacity_bytes": $TMUX_INPUT_MAX_PENDING_BYTES,
              "max_batch_capacity_bytes": $TMUX_INPUT_MAX_BATCH_BYTES,
              "metrics": {
                "total_enqueued_bytes": ${metrics.totalEnqueuedBytes},
                "total_sent_bytes": ${metrics.totalSentBytes},
                "max_pending_bytes": ${metrics.maxPendingBytes},
                "max_pending_chunks": ${metrics.maxPendingChunks},
                "max_batch_bytes": ${metrics.maxBatchBytes},
                "max_batch_chunks": ${metrics.maxBatchChunks},
                "sent_batch_count": ${metrics.sentBatchCount},
                "max_send_latency_ms": ${metrics.maxSendLatencyMs}
              },
              "no_content_loss": ${metrics.totalEnqueuedBytes == expectedBytes.toLong() && metrics.totalSentBytes == expectedBytes.toLong()}
            }
            """.trimIndent(),
        )
    }

    private fun drainTerminalBridgeHandler() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitForSentCommandCount(client: FakeTmuxClient, expectedCount: Int) {
        repeat(100) {
            scheduler.advanceUntilIdle()
            if (client.sentCommands.count { command -> command.startsWith("send-keys") } >= expectedCount) {
                return
            }
            Thread.sleep(10)
        }
        scheduler.advanceUntilIdle()
        assertTrue(
            "expected at least $expectedCount send-keys commands, got ${client.sentCommands}",
            client.sentCommands.count { it.startsWith("send-keys") } >= expectedCount,
        )
    }
}
