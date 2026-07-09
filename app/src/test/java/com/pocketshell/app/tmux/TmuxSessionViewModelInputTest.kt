package com.pocketshell.app.tmux

import com.pocketshell.core.terminal.ui.TerminalRawInputPolicy
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.uikit.model.KeyKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun writeInputToPaneWrapsMultiLineInputInBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        val payload = "para one\npara two\npara three"
        vm.writeInputToPane("%4", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "expected bracketed paste start, body, and end commands, got $sent",
            3,
            sent.size,
        )
        val cmd = sent[1]
        assertTrue(
            "expected send-keys -H targeting %4, got '$cmd'",
            cmd.startsWith("send-keys -H -t %4 "),
        )
        assertTrue(
            "expected bracketed-paste start marker in hex payload, got '$cmd'",
            sent.first().endsWith("1b 5b 32 30 30 7e"),
        )
        assertTrue(
            "expected bracketed-paste end marker in hex payload, got '$cmd'",
            sent.last().endsWith("1b 5b 32 30 31 7e"),
        )
        val hexBody = cmd.substringAfter("send-keys -H -t %4 ")
        val newlineCount = hexBody.split(' ').count { it == "0a" }
        assertEquals(
            "expected exactly 2 literal LF bytes inside bracketed paste, got '$hexBody'",
            2,
            newlineCount,
        )
        assertTrue(
            "multi-line input must not emit a separate Enter named-key, got $sent",
            sent.none { it.contains(" Enter") },
        )
    }

    @Test
    fun writeInputToPaneNormalisesCrLfToLfInsideBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "alpha\r\nbeta".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals("expected start, body, and end commands, got $sent", 3, sent.size)
        val cmd = sent[1]
        val hexBody = cmd.substringAfter("send-keys -H -t %0 ")
        val tokens = hexBody.split(' ')
        assertEquals(
            "expected exactly 1 LF (not CR LF) inside the paste, got '$hexBody'",
            1,
            tokens.count { it == "0a" },
        )
        assertEquals(
            "expected no CR bytes inside the paste, got '$hexBody'",
            0,
            tokens.count { it == "0d" },
        )
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
    fun writeInputToPaneTrailingNewlineGoesThroughBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "ls\n".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "expected start, body, and end send-keys -H invocations for `\\n`-terminated input, got $sent",
            3,
            sent.size,
        )
        assertTrue(
            "expected send-keys -H, got '$sent'",
            sent.all { it.startsWith("send-keys -H -t %0 ") },
        )
    }

    @Test
    fun largeBracketedPasteIsSplitIntoBoundedSendKeysCommands() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        val payload = buildString {
            append("first line\n")
            repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3) { append(('a'.code + (it % 26)).toChar()) }
        }
        vm.writeInputToPane("%0", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys -H") }
        assertTrue("expected multiple bounded paste chunks, got ${sent.size}: $sent", sent.size > 3)
        assertTrue("paste start marker must be its own bounded command", sent.first().endsWith("1b 5b 32 30 30 7e"))
        assertTrue("paste end marker must be its own bounded command", sent.last().endsWith("1b 5b 32 30 31 7e"))
        val maxHexTokens = sent.drop(1).dropLast(1)
            .maxOf { command -> command.substringAfter("send-keys -H -t %0 ").split(' ').size }
        assertTrue(
            "body chunks must be bounded to $TMUX_PASTE_BODY_CHUNK_BYTES bytes; max tokens=$maxHexTokens",
            maxHexTokens <= TMUX_PASTE_BODY_CHUNK_BYTES,
        )
        assertTrue(
            "large paste must not fall back to one unbounded command",
            sent.none { it.substringAfter("send-keys -H -t %0 ").split(' ').size > TMUX_PASTE_BODY_CHUNK_BYTES },
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
